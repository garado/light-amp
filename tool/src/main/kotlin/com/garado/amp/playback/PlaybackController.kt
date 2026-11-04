package com.garado.amp.playback

import androidx.media3.common.Player
import com.thelightphone.sdk.cast.DlnaCast
import com.thelightphone.sdk.cast.DlnaRenderer
import com.thelightphone.sdk.cast.DlnaState
import com.garado.amp.data.AppSettings
import com.garado.amp.data.Connectivity
import com.garado.amp.data.DownloadStore
import com.garado.amp.data.LocalLibrary
import com.garado.amp.data.RepeatMode
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import com.garado.amp.App
import com.garado.amp.data.PendingAction
import com.garado.amp.data.SavedQueue
import com.garado.amp.data.StreamFormat
import com.garado.amp.data.MusicServer
import com.garado.amp.data.PlexClient
import com.garado.amp.data.PlexCompanionListener
import com.garado.amp.data.PlexGdm
import com.garado.amp.data.PlexPlayerTimeline
import com.garado.amp.data.plexTimelineFrom
import com.garado.amp.data.PlexPlayer
import com.garado.amp.data.PlexQueue
import com.garado.amp.data.TimelineState
import com.garado.amp.data.Track
import com.garado.amp.data.db.LibraryDao
import com.garado.amp.data.qualityRank
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The single, app-scoped playback engine. Wraps one [LightAudioPlayer] (created
 * from the activity, reused across every screen). All queue mutations route
 * through here; edits use the player's incremental ops so adding or reordering
 * upcoming tracks never interrupts the current one.
 *
 * Playback is foreground-only until LightOS ships background audio; this class
 * needs no change when it does.
 */
class PlaybackController(
    private val settings: AppSettings,
    private val serverClient: StateFlow<MusicServer?>,
    /** Resolved per call: the active source's database, see App.dao. */
    private val daoProvider: () -> LibraryDao,
    private val downloads: DownloadStore,
    private val scope: CoroutineScope,
    /**
     * Whether the server may be contacted at all. See App.metadataAllowed.
     *
     * Does not gate playback itself, only metadata traffic like timelines,
     * now-playing, and scrobbles.
     */
    private val metadataAllowed: () -> Boolean = { true },
) {
    private var player: LightAudioPlayer? = null

    /**
     * Whether anything has actually played since the queue last reached its end.
     *
     * Tells a finished queue apart from one that was merely cued up. See
     * [rewindIfQueueFinished].
     */
    private var hasPlayed = false

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    /**
     * Name of the current queue, if it was started as a named list (e.g. a
     * radio station). Null for an ad hoc queue. Set and cleared with the
     * queue; appending tracks does not clear it.
     */
    private val _queueName = MutableStateFlow<String?>(null)
    val queueName: StateFlow<String?> = _queueName

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    /**
     * Loudness normalisation on/off, fed from settings by App. While on, the
     * playing track's server-measured gain ([Track.gainDb]) becomes the
     * player's own volume; see the collector in [bind].
     */
    val replayGain = MutableStateFlow(true)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    val currentTrack: StateFlow<Track?> =
        combine(_queue, _index) { q, i -> q.getOrNull(i) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Mirrored from [Connectivity.unmetered]; source selection is on the main thread. */
    @Volatile
    private var unmetered = false
    private var lastScrobbledId: String? = null
    private var nowPlayingId: String? = null

    /** Accumulated listening time for the current track */
    private var listenedMs = 0L

    /** When the current run of actual playback began */
    private var playStartedAtMs: Long? = null

    private fun currentListenedMs(): Long {
        val started = playStartedAtMs ?: return listenedMs
        return listenedMs + (System.currentTimeMillis() - started)
    }

    private fun resetListened() {
        listenedMs = 0L
        playStartedAtMs = if (_isPlaying.value) System.currentTimeMillis() else null
    }
    /** Identity of the queue last handed to the server. See pushQueueToServer. */
    private var lastSavedQueueKey: Int? = null

    /**
     * Identifies playback to the server; carried on stream URLs and
     * [reportTimeline] calls. Plex keeps one transcode per session id and
     * tears down any earlier session using the same id, so every queued
     * track needs its own id derived from the track rather than one id
     * reassigned per track change, otherwise preparing the next track can
     * kill the stream currently playing.
     *
     * Changes with each new queue, so replaying the same track starts a new
     * session.
     */
    private var playbackSession: String = UUID.randomUUID().toString()

    private fun sessionIdFor(trackId: String): String = "$playbackSession:$trackId"

    /** The track whose session is currently open, so it can be closed out by id. */
    private var sessionTrackId: String? = null

    // Local files for downloaded tracks, refreshed as downloads land so queue
    // items can be built synchronously.
    private var localFiles: Map<String, Pair<java.io.File, StreamFormat>> = emptyMap()

    private val dao: LibraryDao get() = daoProvider()

    /**
     * How far into the track the current *stream* starts, when the server was
     * asked to seek for us. Zero for files and for a stream played from the top.
     */
    @Volatile
    private var streamOffsetMs = 0L

    /**
     * Track whose queue slot holds a server-seeked remainder instead of the
     * full track. The index collector restores the original item once
     * playback leaves the slot, otherwise a repeat or re-tap would replay
     * the seeked tail instead of the whole song.
     */
    @Volatile
    private var offsetItemTrackId: String? = null

    /**
     * Seek offset for the cast renderer's stream, folded into the URL
     * instead of sent as a UPnP Seek: a live transcode has no seek table,
     * so a Seek sent right after SetAVTransportURI gets refused and the
     * track restarts from the top. Readouts are shifted back by this amount.
     */
    private var castOffsetMs = 0L

    /** Where the current cast track was started from. See pollRenderer. */
    private var castStartedAtMs = 0L

    /**
     * Target of a seek sent to the renderer in place, until a reading near it
     * arrives; -1 when none is in flight. See pollRenderer.
     */
    private var castSeekPending = -1L

    /** Latched by [fallBackOffline]; cleared when the server answers again. */
    @Volatile
    private var forceOffline = false
    private var retriedTrackId: String? = null

    /** Tracks whose stream failed; these play from their downloaded copy while everything else still streams. */
    private val streamFailed = mutableSetOf<String>()

    // Queue order captured when shuffle was switched on, so switching it back off
    // can restore it.
    private var preShuffleOrder: List<Track>? = null

    // --- DLNA casting (TEMPORARY, removed before submitting to Light) --------

    private val _castRenderer = MutableStateFlow<DlnaRenderer?>(null)
    val castRenderer: StateFlow<DlnaRenderer?> = _castRenderer

    /** True while audio is going somewhere else instead of this device. */
    val isCasting: Boolean get() = _castRenderer.value != null || _plexPlayer.value != null

    private var castJob: Job? = null

    // --- Plex Companion casting (permanent, plain HTTP, all in tool code) ----

    private val _plexPlayer = MutableStateFlow<PlexPlayer?>(null)
    val plexPlayer: StateFlow<PlexPlayer?> = _plexPlayer

    /**
     * Whether the fader can control a Companion player's volume. Some
     * players report a fixed or stale volume while actually passing audio
     * through to an external amp that owns the real level. Starts at
     * [Unknown]; a drag is used as a probe, and the result settles it to
     * [Controllable] or [NotOurs].
     */
    enum class PlexVolume { Unknown, Controllable, NotOurs }

    private val _plexVolume = MutableStateFlow(PlexVolume.Unknown)
    val plexVolume: StateFlow<PlexVolume> = _plexVolume

    /** The level last asked for (0–100) while proving control, if any. */
    private var plexVolumeAsked: Int? = null
    private var plexVolumeProbes = 0

    private var plexJob: Job? = null
    private var plexQueue: PlexQueue? = null
    private var plexPushedIds: List<String> = emptyList()

    /** Companion wants a fresh command id per request, per controller. */
    private var plexCommandId = 0

    /**
     * How far into [_queue] the player has been given; -1 means the whole
     * queue. Plex play queues are submitted as one URI in the request line,
     * so a large queue overflows the server's URL length limit. Large
     * queues are sent in a window and topped up as the player nears the
     * end; appending to a play queue is an edit, not a new queue, so this
     * costs nothing audible.
     */
    private var plexWindowEnd = -1

    /** Whether the player is pushing its timeline rather than being asked. */
    @Volatile
    private var plexSubscribed = false

    /** When the last push arrived, so silence can be noticed. */
    @Volatile
    private var plexLastPush = 0L

    /**
     * The little HTTP server a subscription pushes to. Built once and reused:
     * a port opened per cast would be a port leaked per cast.
     */
    private val plexListener = PlexCompanionListener { body ->
        plexLastPush = System.currentTimeMillis()
        plexTimelineFrom(body)?.let { t ->
            if (t.state == "stopped") {
                // The player saying it has stopped is the one push worth
                // acting on rather than mirroring.
                scope.launch(Dispatchers.Main.immediate) { plexCastEnded() }
            } else {
                scope.launch(Dispatchers.Main.immediate) { adoptPlexTimeline(t) }
            }
        }
    }

    // "rendererId|format" -> stream format actually sent + its MIME type.
    private val castFormats = mutableMapOf<String, Pair<StreamFormat, String>>()

    /**
     * Whether a track's stream can be seeked, keyed by track and format. A
     * transcoded stream can't be seeked, but a file served as-is can, so
     * seekability depends on the track, not just the requested format.
     * Failed probes are retried rather than cached as a no.
     */
    private val castSeekable = mutableMapOf<String, Boolean>()

    /** What the renderer is playing, and what it has been given to play next. */
    private var castCurrentUrl: String? = null
    private var castNextUrl: String? = null

    /**
     * Queue index the renderer's next slot is armed for, or was offered and
     * refused. Tracked so a renderer that doesn't implement
     * SetNextAVTransportURI isn't asked again on every poll.
     */
    private var castNextArmedFor: Int? = null

    /**
     * True once [bind] has attached the audio stack. [restoreState] needs a
     * bound player and does not retry, so this is how a concurrent boot
     * restore waits for bind to finish.
     */
    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound

    /**
     * Rewinds a finished queue back to its first track and leaves it stopped.
     *
     * ExoPlayer leaves a finished queue parked on the last track at full
     * duration instead of signaling an end, and the SDK doesn't surface
     * `STATE_ENDED`, so "finished" is inferred from: not casting, stopped,
     * on the last item, repeat off, and position at duration. [hasPlayed]
     * guards against treating a freshly restored but unplayed queue as
     * finished.
     */
    private fun rewindIfQueueFinished(p: LightAudioPlayer) {
        if (isCasting) return
        if (!hasPlayed) return
        if (_repeatMode.value != RepeatMode.OFF) return
        val last = _queue.value.lastIndex
        if (last < 0 || _index.value != last) return
        // A chunked transcode may never report its own duration (it stays 0),
        // so the library's known duration is the fallback.
        val reported = p.durationMs.value
        val endReached = if (reported > 0L) {
            p.positionMs.value >= reported - QUEUE_END_SLACK_MS
        } else {
            val known = _durationMs.value
            known > 0L && _positionMs.value >= known - QUEUE_END_SLACK_MS
        }
        if (!endReached) return
        // Paused first: seeking out of the ended state with playWhenReady still
        // true would start the queue over instead of waiting. ExoPlayer's
        // looper is Main-only, and this runs from a Default-dispatcher
        // collector, so the call is placed on Main explicitly. The rest of the
        // state is set here, outside the launch, so a second emission can't
        // queue a second rewind.
        scope.launch(Dispatchers.Main.immediate) {
            p.pause()
            p.seekToIndex(0)
        }
        _index.value = 0
        _positionMs.value = 0L
        hasPlayed = false
    }

    /** Attach the audio stack from the current activity. Idempotent per process. */
    fun bind(audio: LightAudio) {
        if (player != null) return
        // Detached: playback lives in the SDK's own service, so it survives the
        // screen turning off and the activity going away.
        val p = audio.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)
        player = p
        p.onItemRepeated = {
            // A server-seeked remainder would loop just its last few seconds
            // on repeat-one, so it gets rebuilt from the track's real start.
            if (streamOffsetMs > 0L) seekTo(0)
        }
        p.onPlaybackError = { error ->
            // A bad HTTP status means the server answered but refused the
            // request, which is different from being unreachable.
            fallBackOffline(
                serverAnswered = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        }
        // While casting, the renderer is the source of truth for these: the
        // local player sits paused and would otherwise report position 0 /
        // not-playing over the cast state.
        scope.launch {
            p.isPlaying.collect { playing ->
                if (isCasting) return@collect
                _isPlaying.value = playing
                if (playing) {
                    hasPlayed = true
                    playStartedAtMs = System.currentTimeMillis()
                } else {
                    playStartedAtMs?.let { listenedMs += System.currentTimeMillis() - it }
                    playStartedAtMs = null
                    if (hasPlayed) rewindIfQueueFinished(p)
                }
            }
        }
        // A server-seeked stream starts at 0:00 of a shorter file, so both
        // readouts are shifted back into the track's own timeline.
        scope.launch { p.positionMs.collect { if (!isCasting) _positionMs.value = it + streamOffsetMs } }
        scope.launch {
            p.durationMs.collect { reported ->
                if (isCasting) return@collect
                val current = _queue.value.getOrNull(_index.value)
                val known = current?.durationMs ?: 0L
                val fromFile = current?.id?.let { localFiles.containsKey(it) } == true
                // Navidrome only honors a seek offset while actually
                // transcoding. If the requested format matches the file as-is,
                // it serves the whole file and ignores the offset, so the
                // reported duration equals the track's full known duration
                // instead of the shortened remainder. Detect that and finish
                // the seek on the player side, which has a real seek table.
                if (streamOffsetMs > 0L &&
                    known > 0L &&
                    reported >= known - IGNORED_OFFSET_SLACK_MS
                ) {
                    val target = streamOffsetMs
                    streamOffsetMs = 0L
                    scope.launch(Dispatchers.Main.immediate) { p.seekTo(target) }
                    _durationMs.value = reported
                    return@collect
                }
                _durationMs.value = when {
                    // Plex streams every non-original format chunked, with no
                    // length to derive a duration from.
                    reported <= 0L -> known
                    // A server-seeked stream's own duration is the remainder,
                    // not the full track's.
                    streamOffsetMs > 0 -> known.takeIf { it > 0L } ?: (reported + streamOffsetMs)
                    // A downloaded file transcoded as mp3 has no Xing header,
                    // so ExoPlayer estimates its duration from size divided by
                    // the first frame's bitrate, which is wrong for a
                    // variable-rate encode. The library's server-reported
                    // duration is accurate; prefer it.
                    fromFile && known > 0L -> known
                    else -> reported
                }
                android.util.Log.i(
                    "AmpDur",
                    "duration reported=$reported known=$known off=$streamOffsetMs " +
                        "file=$fromFile -> ${_durationMs.value}",
                )
            }
        }
        // ReplayGain: the playing track's measured gain becomes the player's
        // own volume, attenuate-only and clamped at unity so no track is ever
        // boosted. Not applied while casting, since the renderer plays its
        // own copy.
        scope.launch {
            combine(p.currentMediaItemIndex, _queue, replayGain) { idx, q, on ->
                val db = if (on) q.getOrNull(idx)?.gainDb else null
                db?.let { 10.0.pow(it / 20.0).toFloat().coerceAtMost(1f) } ?: 1f
            }.distinctUntilChanged().collect { factor ->
                withContext(Dispatchers.Main.immediate) { p.playbackGain = factor }
            }
        }
        scope.launch {
            p.currentMediaItemIndex.collect { idx ->
                if (isCasting) return@collect
                if (idx >= 0 && idx != _index.value) {
                    val landed = _queue.value.getOrNull(idx)
                    android.util.Log.i(
                        "AmpDur",
                        "track pIdx=$idx uiIdx=${_index.value} id=${landed?.id} " +
                            "\"${landed?.title}\" libDur=${landed?.durationMs} " +
                            "pDur=${p.durationMs.value} off=$streamOffsetMs " +
                            "local=${landed?.id?.let { localFiles[it] }?.let { (f, fmt) ->
                                "${f.name} ${f.length()}B ${fmt.id}"
                            } ?: "stream"}",
                    )
                    _index.value = idx
                    resetListened()
                    // Reset position and offset for the new track. Paths that
                    // legitimately start a track part-way (a restored queue, a
                    // server seek) set _index before the player moves, so they
                    // never reach this block; see rebuildQueueAt.
                    streamOffsetMs = 0
                    _positionMs.value = 0L
                    // Put the whole song back in its slot when leaving a
                    // server-seeked remainder, so a later repeat wrap or tap on
                    // the queue row plays the track and not the seeked tail.
                    offsetItemTrackId?.let { staleId ->
                        offsetItemTrackId = null
                        val q = _queue.value
                        if (q.getOrNull(idx)?.id == staleId) {
                            // Landed on a stale remainder (a wrap over a slot
                            // that was never restored): rebuild it live.
                            scope.launch(Dispatchers.Main.immediate) { seekTo(0) }
                        } else {
                            val stubIdx = q.indexOfFirst { it.id == staleId }
                            val original = q.getOrNull(stubIdx)
                            if (stubIdx >= 0 && original != null) {
                                scope.launch(Dispatchers.Main.immediate) {
                                    p.replaceRange(stubIdx, stubIdx + 1, listOf(original.toAudioItem()))
                                }
                            }
                        }
                    }
                    // A new track opens a new session; the old one is told it
                    // stopped so it doesn't linger in `/status/sessions`.
                    _queue.value.getOrNull(idx)?.let { openSessionFor(it.id) }
                    // Seeded from the library since a chunked stream may never
                    // report its own duration.
                    _queue.value.getOrNull(idx)?.durationMs
                        ?.takeIf { it > 0L }
                        ?.let { _durationMs.value = it }
                    reportTimeline(TimelineState.PLAYING)
                }
                announceNowPlaying()
            }
        }
        // Any change to what plays next (an edit, a jump, a repeat mode) must
        // be pushed to the renderer, which holds a URI chosen earlier.
        // Debounced since a single edit can touch several of these at once.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch {
            combine(_queue, _index, _repeatMode) { _, _, _ -> Unit }
                .debounce(CAST_REQUEUE_DEBOUNCE_MS)
                .collect {
                    _castRenderer.value?.let {
                        // Whatever the renderer was armed with is now stale.
                        castNextArmedFor = null
                        armNextIfDue(it)
                    }
                }
        }
        // The server answering again is the signal to stop pinning playback to
        // local files; the next track resolves normally.
        scope.launch {
            App.serverReachable.collect { reachable ->
                if (reachable) {
                    forceOffline = false
                    retriedTrackId = null
                    streamFailed.clear()
                }
            }
        }
        scope.launch {
            Connectivity.unmetered.collect {
                unmetered = it
                reresolveQueue()
            }
        }
        // One read of the downloads table feeds both this map and the
        // library, instead of resolving `dao` once at bind time; `dao` is
        // resolved per call so a source switch doesn't leave this map
        // describing the previous source's downloads.
        scope.launch {
            App.library.downloadFiles.collect { rows ->
                localFiles = rows.mapNotNull { row ->
                    downloads.existing(App.source.value.id, row.fileName)?.let { file ->
                        row.trackId to (file to StreamFormat.fromId(row.format))
                    }
                }.toMap()
                reresolveQueue()
            }
        }
        // Mirror the system media volume so the fader reflects the hardware
        // volume keys too. While casting, those keys still drive Android's
        // own media volume rather than the renderer, so forward the change to
        // the renderer instead of intercepting the keys (not possible in the
        // tool sandbox).
        scope.launch {
            p.deviceVolume.collect { level ->
                _volume.value = level
                val renderer = _castRenderer.value ?: return@collect
                DlnaCast.setVolume(renderer, level)
            }
        }

        // A play is submitted part-way through, not at the start; see
        // maybeSubmitPlay.
        scope.launch { _positionMs.collect { maybeSubmitPlay() } }
        // Snapshot periodically rather than on every position tick, trading a
        // few seconds of lost progress on a kill for not writing to DataStore
        // once a second forever.
        scope.launch {
            while (true) {
                delay(SAVE_STATE_INTERVAL_MS)
                persistState()
            }
        }

        // Plex tears a session out of `/status/sessions` if nothing pings it
        // for a while, like a lease. Only pinged while playing: a paused
        // session was already told once, and an idle queue has no session.
        scope.launch {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                if (_isPlaying.value) reportTimeline(TimelineState.PLAYING)
            }
        }
        _bound.value = true
    }

    /** Write the queue, index and position so the next launch can pick them up. */
    private suspend fun persistState(final: Boolean = false) {
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        settings.saveQueue(tracks.map { it.id }, _index.value, _positionMs.value)
        pushQueueToServer(tracks, force = final)
    }

    /**
     * Hand the queue to the server so another client can resume it. Sent
     * only when the queue or current track actually changed, since this is
     * a network round trip rather than a local write.
     */
    private suspend fun pushQueueToServer(tracks: List<Track>, force: Boolean) {
        val client = serverClient.value ?: return
        val at = _index.value.coerceAtLeast(0)
        val currentId = tracks.getOrNull(at)?.id
        // Trimmed to a window starting at the current track. Subsonic takes
        // ids as individual URL parameters, so a large queue would overflow
        // the request's URL length; only the part ahead is sent.
        val ids = if (tracks.size <= MAX_SAVED_QUEUE) {
            tracks.map { it.id }
        } else {
            tracks.subList(at, minOf(at + MAX_SAVED_QUEUE, tracks.size)).map { it.id }
        }
        val key = 31 * ids.hashCode() + currentId.hashCode()
        if (!force && key == lastSavedQueueKey) return
        lastSavedQueueKey = key
        runCatching { client.savePlayQueue(ids, currentId, _positionMs.value) }
    }

    /**
     * Reload the queue saved by a previous run, ready but **paused**.
     *
     * Ids are resolved against the cached library, so a track deleted
     * server-side simply drops out, and the index is remapped onto whatever
     * survived.
     */
    suspend fun restoreState(library: List<Track>) {
        if (player == null || _queue.value.isNotEmpty()) return
        // Each track resolves to a file or URL once, when the queue is
        // built, and that choice sticks for the queue's lifetime. Wait for
        // downloads to load first, or a fully downloaded library restores as
        // a queue of streams instead. Bounded so a database that never
        // answers doesn't block the restore.
        withTimeoutOrNull(DOWNLOADS_READY_TIMEOUT_MS) {
            App.library.downloadsLoaded.first { it }
        }
        val localIds = settings.savedQueueIds.first()
        val saved = if (localIds.isNotEmpty()) {
            SavedQueue(
                localIds,
                localIds.getOrNull(settings.savedQueueIndex.first()),
                settings.savedPositionMs.first(),
            )
        } else {
            // Nothing local to restore (fresh install or cleared cache); fall
            // back to whatever queue the server has on file. Only consulted
            // when this phone has nothing, so a visible local queue is never
            // replaced by a remote one.
            serverClient.value?.let { runCatching { it.getPlayQueue() }.getOrNull() }
        } ?: return
        val byId = library.associateBy { it.id }
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == saved.currentId }.takeIf { it >= 0 } ?: 0

        val p = player ?: return
        val position = saved.positionMs
        // ExoPlayer's looper is Main-only and throws if touched from this
        // coroutine's Default dispatcher.
        withContext(Dispatchers.Main.immediate) {
            _queue.value = tracks
            lastScrobbledId = null
            nowPlayingId = null
            resetListened()
            // Start position is passed in at prepare time, since seekTo()
            // clamps to a duration that isn't known yet and would land on 0.
            rebuildQueueAt(p, tracks, index, position)
            // Deliberately not p.play(): restoring cues the track, it
            // shouldn't start making noise on its own.
            p.pause()
        }
    }

    fun release() {
        // A Companion player is deliberately left playing: it walks its own
        // queue and answers to the server directly, so closing this app only
        // stops steering it, the same as any other Plex remote.
        if (_plexPlayer.value != null) {
            android.util.Log.i("AmpPlex", "leaving the player playing")
            // The subscription goes even though the playback stays: pushes to
            // a port that is about to close would have the player talking to
            // nothing.
            endPlexSubscription(plexClient(), _plexPlayer.value)
            plexJob?.cancel()
            plexJob = null
            _plexPlayer.value = null
            plexQueue = null
        }
        // A renderer keeps playing its URI even after this app closes, so it
        // must be told to stop explicitly. Bounded so teardown can't hang on
        // an unreachable device.
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            runBlocking { withTimeoutOrNull(CAST_STOP_TIMEOUT_MS) { DlnaCast.stop(renderer) } }
        }
        // One last snapshot before the player goes, and the session is closed
        // out here too, since leaving the app never otherwise goes through
        // stop().
        runBlocking {
            withTimeoutOrNull(SAVE_STATE_TIMEOUT_MS) {
                sessionTrackId?.let { reportTimelineStoppedNow(it) }
                persistState(final = true)
            }
        }
        sessionTrackId = null
        player?.release()
        player = null
    }

    /**
     * The blocking form of [reportTimelineStopped], for teardown. The
     * fire-and-forget version launches into [scope], which would already be
     * cancelled during teardown.
     */
    private suspend fun reportTimelineStoppedNow(trackId: String) {
        if (LocalLibrary.isLocal(trackId)) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        runCatching {
            client.reportTimeline(sessionIdFor(trackId), trackId, TimelineState.STOPPED, 0L, 0L)
        }
    }

    // --- Playback controls ---------------------------------------------------

    /** Replace the queue with [tracks] and start at [startIndex]; see [queueName]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, name: String? = null) {
        if (tracks.isEmpty()) return
        _queueName.value = name
        // A new queue is a new listening session, so ids derived from it must
        // be new too, otherwise replaying the same track later would report
        // itself as the same session still going.
        playbackSession = UUID.randomUUID().toString()
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (_shuffle.value) {
            val first = tracks[start]
            val rest = tracks.toMutableList().apply { removeAt(start) }.shuffled()
            setQueue(capped(listOf(first) + rest, 0).first, 0)
        } else {
            val (window, index) = capped(tracks, start)
            setQueue(window, index)
        }
    }

    /**
     * Trim a queue to something the player can actually hold. Media3 mirrors
     * the whole timeline into a legacy MediaSession queue for Bluetooth
     * clients and loads artwork for every entry, so a very large queue runs
     * the process out of memory. The window is centred on the track being
     * played, with a little history kept behind it.
     */
    private fun capped(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
        if (tracks.size <= MAX_QUEUE) return tracks to startIndex
        val first = (startIndex - QUEUE_LOOKBACK).coerceIn(0, tracks.size - MAX_QUEUE)
        return tracks.subList(first, first + MAX_QUEUE) to (startIndex - first)
    }

    fun togglePlayPause() {
        _plexPlayer.value?.let { target ->
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            scope.launch {
                plexClient()?.companionCommand(target, if (wasPlaying) "pause" else "play", ++plexCommandId)
            }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            scope.launch {
                if (wasPlaying) DlnaCast.pause(renderer) else DlnaCast.resume(renderer)
            }
            reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
            return
        }
        val p = player ?: return
        val wasPlaying = _isPlaying.value
        // Resuming needs the server's decision first, exactly as [play] does;
        // this is the button that actually starts a restored queue.
        if (wasPlaying) p.pause() else withStreamDecision { p.play() }
        reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
    }

    fun play() {
        _plexPlayer.value?.let { target ->
            _isPlaying.value = true
            scope.launch { plexClient()?.companionCommand(target, "play", ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = true
            scope.launch { DlnaCast.resume(renderer) }
            reportTimeline(TimelineState.PLAYING)
            return
        }
        // The server's stream decision must land before playback starts; see
        // MusicServer.prepareStream.
        withStreamDecision { player?.play() }
        reportTimeline(TimelineState.PLAYING)
    }

    /**
     * Run [start] once the server has agreed to serve the current track.
     * Only applies to a streamed track; a local file has no server to ask.
     * Bounded, and starts anyway on a timeout or refusal, since a server
     * that won't decide may still serve, and a silent player is worse.
     */
    private fun withStreamDecision(start: () -> Unit) {
        val track = currentTrack.value
        val source = queuedSources.getOrNull(_index.value)
        val client = serverClient.value
        if (track == null || client == null || source !is LightAudioSource.UrlSource) {
            start()
            return
        }
        scope.launch {
            withTimeoutOrNull(STREAM_DECISION_TIMEOUT_MS) {
                client.prepareStream(track.id, effectiveFormat(), sessionIdFor(track.id))
            }
            withContext(Dispatchers.Main.immediate) { start() }
        }
    }

    fun pause() {
        _plexPlayer.value?.let { target ->
            _isPlaying.value = false
            scope.launch { plexClient()?.companionCommand(target, "pause", ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = false
            scope.launch { DlnaCast.pause(renderer) }
            reportTimeline(TimelineState.PAUSED)
            return
        }
        player?.pause()
        reportTimeline(TimelineState.PAUSED)
    }

    /**
     * Seek, by whichever route the source allows. A downloaded file seeks
     * natively. A transcoded stream has no seek table, so the seek is
     * instead handed to the server as a fresh request with `timeOffset`,
     * which starts the encode at that second; [streamOffsetMs] then shifts
     * the readouts back into the track's own timeline.
     */
    fun seekTo(ms: Long) {
        _plexPlayer.value?.let { target ->
            _positionMs.value = ms
            scope.launch { plexClient()?.companionSeek(target, ms, ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            // A seek this close to the end isn't worth the round trip to the
            // renderer; treat it as a request to advance instead.
            val total = _durationMs.value
            if (total > 0L && total - ms <= CAST_SEEK_END_SLACK_MS) {
                android.util.Log.i("AmpCast", "seek $ms of $total is the end → next")
                castJob?.cancel()
                castJob = scope.launch {
                    if (advanceCast(renderer)) pollRenderer(renderer) else DlnaCast.stop(renderer)
                }
                return
            }
            _positionMs.value = ms
            // In place when the renderer can, pushed again when it can't; see
            // seekCast.
            castJob?.cancel()
            castJob = scope.launch {
                if (seekCast(renderer, ms)) pollRenderer(renderer, playedBefore = true)
            }
            return
        }
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || !needsServerSeek(track)) {
            p.seekTo(ms)
            // Trust it, then check; see [verifyNativeSeek].
            if (track != null) verifyNativeSeek(p, track, ms)
            return
        }
        serverSeek(p, track, ms)
    }

    /**
     * Tracks whose native seek was tried and didn't land. Whether a stream
     * can actually be seeked isn't knowable in advance, since the server
     * can still refuse byte ranges on an otherwise-seekable format, so this
     * is measured and remembered rather than predicted.
     */
    private val seekNeedsReload = mutableSetOf<String>()

    /** Bumped per seek, so a slow verify can't act on a stale target. */
    private var seekGeneration = 0

    /** True when the player can't be trusted to seek within this stream. */
    private fun needsServerSeek(track: Track, askPlayer: Boolean = true): Boolean {
        if (track.source() is LightAudioSource.FileSource) return false
        if (track.id in seekNeedsReload) return true
        // A queue being rebuilt has nothing prepared to ask yet: the player's
        // answer would describe the item it held before.
        if (!askPlayer) return effectiveFormat() != StreamFormat.RAW
        // Ask the player, which knows what actually arrived: requesting a
        // format the file already is gets served untouched with byte ranges
        // and real seek support, even though the requested format isn't RAW.
        player?.let { if (it.isCurrentItemSeekable) return false }
        return effectiveFormat() != StreamFormat.RAW
    }

    /**
     * Check where a native seek actually landed, and reload if it missed.
     * The player's position reading is unreliable right after a seek call;
     * wait briefly so it reflects the actual stream.
     */
    private fun verifyNativeSeek(
        p: LightAudioPlayer,
        track: Track,
        target: Long,
        awaitStream: Boolean = false,
    ) {
        val generation = ++seekGeneration
        scope.launch {
            // Wait for the new stream to declare itself, within reason; until
            // then the player just echoes the requested position and this
            // check would pass even on a seek about to be dropped.
            if (awaitStream) {
                withTimeoutOrNull(REBUILD_VERIFY_TIMEOUT_MS) { p.durationMs.first { it > 0L } }
            }
            delay(SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (_castRenderer.value != null) return@launch
            if (_queue.value.getOrNull(_index.value)?.id != track.id) return@launch
            val landed = p.positionMs.value + streamOffsetMs
            if (kotlin.math.abs(landed - target) <= SEEK_TOLERANCE_MS) return@launch
            android.util.Log.i("AmpSeek", "missed: wanted $target, landed $landed — reloading")
            seekNeedsReload += track.id
            serverSeek(p, track, target)
        }
    }

    private fun serverSeek(p: LightAudioPlayer, track: Track, ms: Long) {
        val client = serverClient.value ?: return
        val target = ms.coerceAtLeast(0L)
        val url = client.streamUrl(
            track,
            effectiveFormat(),
            timeOffsetSeconds = (target / 1000).toInt(),
            // An estimated length makes ExoPlayer treat the transcode as
            // bounded and range-seekable; its end-of-file probe then asks for
            // bytes the stream doesn't have, and Navidrome answers 416.
            estimateContentLength = false,
            sessionId = sessionIdFor(track.id),
        )
        streamOffsetMs = target
        offsetItemTrackId = track.id.takeIf { target > 0L }
        // Only the playing item is replaced; the queue either side of it is
        // untouched, so a seek doesn't disturb what plays next.
        _positionMs.value = target
        val item = LightAudioItem(
            source = LightAudioSource.UrlSource(url),
            metadata = LightMediaMetadata(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
            ),
        )
        scope.launch(Dispatchers.Main.immediate) {
            // Intent, not the instantaneous state: isPlaying is false during a
            // rebuffer, and a seek issued in that window read it as "paused"
            // and left the replacement stream parked.
            val wasPlaying = p.playWhenReady
            val at = _index.value
            // Replaced in place rather than removed and re-added: removing
            // it would move the player's current index twice, which the
            // collector above reads as a track change and clears
            // streamOffsetMs.
            p.replaceRange(at, at + 1, listOf(item))
            // Media3 keeps the old position when a replacement item looks like
            // the one it replaced, which would apply the offset twice. Setting
            // the index explicitly avoids that without reading as a track
            // change.
            p.seekToIndex(at)
            if (wasPlaying) p.play()
        }
    }

    /** Stop playback and empty the queue. */
    fun stop() {
        reportTimeline(TimelineState.STOPPED)
        sessionTrackId = null
        _plexPlayer.value?.let { target ->
            val client = plexClient()
            plexJob?.cancel()
            plexJob = null
            _plexPlayer.value = null
            plexQueue = null
            plexWindowEnd = -1
            // Close the subscription too; left open, the player keeps pushing
            // its timeline at a now-dead port. See the note on playerLock in
            // PlexClient.
            endPlexSubscription(client, target)
            scope.launch { client?.companionCommand(target, "stop", ++plexCommandId) }
        }
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            scope.launch { DlnaCast.stop(renderer) }
        }
        // These are plain state writes and run on whatever thread called, with
        // no looper requirement; the queue must be cleared synchronously here,
        // before a source switch (which calls stop() from a Default-dispatcher
        // collector) can resolve stream URLs for tracks against the wrong
        // source.
        _queue.value = emptyList()
        _queueName.value = null
        _index.value = -1
        queuedSources = emptyList()
        streamOffsetMs = 0
        offsetItemTrackId = null
        _positionMs.value = 0
        _durationMs.value = 0
        val p = player ?: return
        // Same rule as restoreState: ExoPlayer's looper is Main-only. Unlike
        // most of this class, stop() can be reached from a Default-dispatcher
        // collector (a source switch), so it places itself on Main; `immediate`
        // keeps a caller already on Main synchronous.
        scope.launch(Dispatchers.Main.immediate) {
            p.stop()
            p.setMediaQueue(emptyList())
        }
    }
    fun skipForward() = player?.skipForward()
    fun skipBack() = player?.skipBack()

    fun next() {
        _plexPlayer.value?.let { target ->
            scope.launch { plexClient()?.companionCommand(target, "skipNext", ++plexCommandId) }
            return
        }
        if (isCasting) {
            jumpTo(_index.value + 1)
            return
        }
        player?.skipToNext()
    }

    /** Restart the current track if we're past the intro, otherwise go to the previous one. */
    fun previous() {
        _plexPlayer.value?.let { target ->
            scope.launch {
                if (_positionMs.value > PREVIOUS_RESTART_MS) {
                    plexClient()?.companionSeek(target, 0, ++plexCommandId)
                } else {
                    plexClient()?.companionCommand(target, "skipPrevious", ++plexCommandId)
                }
            }
            return
        }
        if (isCasting) {
            if (_positionMs.value > PREVIOUS_RESTART_MS) seekTo(0) else jumpTo(_index.value - 1)
            return
        }
        val p = player ?: return
        if (_positionMs.value > PREVIOUS_RESTART_MS) {
            // Must be the controller's seekTo(0), not the player's: a
            // server-seeked stream's own position 0 is really [streamOffsetMs]
            // into the track, so the player's seek alone would land past the
            // restart threshold again.
            seekTo(0)
        } else {
            p.skipToPrevious()
        }
    }

    /** Jump to an arbitrary queue position (tapping a queue row). */
    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        _plexPlayer.value?.let { target ->
            val track = _queue.value[index]
            _index.value = index
            _positionMs.value = 0
            scope.launch {
                if (plexPushedIds.contains(track.id)) {
                    // Within the queue it already holds; see companionSkipTo.
                    plexClient()?.companionSkipTo(target, track.id, ++plexCommandId)
                    // A skipTo outside the player's own window is accepted but
                    // silently ignored, so verify where it actually landed and
                    // rebuild around the track if it didn't move.
                    delay(PLEX_SKIP_VERIFY_MS)
                    if (_plexPlayer.value?.id == target.id &&
                        _queue.value.getOrNull(_index.value)?.id != track.id
                    ) {
                        android.util.Log.i("AmpPlex", "skipTo idx=$index didn't land — rebuilding around it")
                        _index.value = index
                        _positionMs.value = 0
                        repushPlexQueue(positionMs = 0L)
                    }
                } else {
                    // Outside the window the player was given; rebuild the
                    // window around the target track instead.
                    android.util.Log.i("AmpPlex", "jump to idx=$index is outside the window — rebuilding")
                    repushPlexQueue(positionMs = 0L)
                }
            }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _index.value = index
            _positionMs.value = 0
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        val p = player ?: return
        p.seekToIndex(index)
        _index.value = index
        p.play()
    }

    // --- Queue editing (seamless) --------------------------------------------

    fun addToQueue(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        _queue.value = _queue.value + tracks
        p.addItems(tracks.map { it.toAudioItem() })
        plexQueueEdit { client, queue -> client.addToPlayQueue(queue, tracks.map { it.id }, next = false) }
    }

    /**
     * Put these tracks immediately after the one playing. A track already
     * in the queue is moved rather than copied, so it doesn't play twice.
     */
    fun playNext(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        for (track in tracks) {
            val from = _queue.value.indexOfFirst { it.id == track.id }
            val cur = _index.value
            if (from < 0) {
                val at = (cur + 1).coerceIn(0, _queue.value.size)
                _queue.value = _queue.value.toMutableList().apply { add(at, track) }
                p.addItemAt(at, track.toAudioItem())
                plexQueueEdit { client, queue -> client.addToPlayQueue(queue, listOf(track.id), next = true) }
                continue
            }
            if (from == cur) continue
            // Destinations are read after the removal, so a track pulled from
            // behind the cursor lands one place earlier than one pushed forward.
            moveInQueue(from, if (from < cur) cur else cur + 1)
        }
    }

    /**
     * Keep what is playing, replace everything after it. Upcoming items are
     * dropped back to front so each removal can't shift the indices of the
     * ones still to go.
     */
    fun replaceUpcoming(tracks: List<Track>, name: String? = null) {
        val p = player ?: return
        val q = _queue.value
        val cur = _index.value
        if (cur !in q.indices) {
            playQueue(tracks, 0, name)
            return
        }
        val droppedIds = q.drop(cur + 1).map { it.id }
        for (i in q.lastIndex downTo cur + 1) p.removeItem(i)
        _queue.value = q.take(cur + 1) + tracks
        if (tracks.isNotEmpty()) p.addItems(tracks.map { it.toAudioItem() })
        _queueName.value = name
        // Expressed as edits so the currently playing song is untouched.
        plexQueueEdit { client, queue ->
            val emptied = droppedIds.fold<String, PlexQueue?>(queue) { acc, id ->
                acc?.let { client.removePlayQueueItem(it, id) }
            }
            if (tracks.isEmpty()) emptied
            else emptied?.let { client.addToPlayQueue(it, tracks.map { t -> t.id }, next = false) }
        }
    }

    fun removeFromQueue(index: Int) {
        val p = player ?: return
        val q = _queue.value
        if (index !in q.indices) return
        val playingId = q.getOrNull(_index.value)?.id
        val goneId = q[index].id
        p.removeItem(index)
        val newQueue = q.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        plexQueueEdit { client, queue -> client.removePlayQueueItem(queue, goneId) }
    }

    /**
     * Drop several tracks from the queue at once. Removed back to front so
     * each removal can't shift the indices of the ones still to go.
     */
    fun removeFromQueue(ids: Set<String>) {
        val p = player ?: return
        if (ids.isEmpty()) return
        val q = _queue.value
        val targets = q.indices.filter { q[it].id in ids }
        if (targets.isEmpty()) return
        val playingId = q.getOrNull(_index.value)?.id
        targets.sortedDescending().forEach { p.removeItem(it) }
        val gone = targets.toSet()
        val goneIds = targets.map { q[it].id }
        val newQueue = q.filterIndexed { i, _ -> i !in gone }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        plexQueueEdit { client, queue ->
            // One at a time, each answer carrying the places for the next.
            goneIds.fold<String, PlexQueue?>(queue) { acc, id ->
                acc?.let { client.removePlayQueueItem(it, id) }
            }
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        val p = player ?: return
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        val playingId = q.getOrNull(_index.value)?.id
        val movedId = q[from].id
        p.moveItem(from, to)
        val newQueue = q.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        // Plex places an item after another one, so the move is expressed as
        // whoever it now follows (none if it lands at the front).
        val afterId = newQueue.getOrNull(to - 1)?.id
        plexQueueEdit { client, queue -> client.movePlayQueueItem(queue, movedId, afterId) }
    }

    // --- Modes ---------------------------------------------------------------

    fun cycleRepeat() {
        setRepeat(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.QUEUE
                RepeatMode.QUEUE -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.OFF
            }
        )
    }

    fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
        player?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
        }
        _plexPlayer.value?.let { target ->
            // Companion's values: 0 off, 1 the track, 2 the whole queue.
            val plexRepeat = when (mode) {
                RepeatMode.OFF -> "0"
                RepeatMode.TRACK -> "1"
                RepeatMode.QUEUE -> "2"
            }
            scope.launch {
                plexClient()?.companionSetParameters(target, listOf("repeat" to plexRepeat), ++plexCommandId)
            }
        }
    }

    /**
     * Handle a hardware volume key, returning true when it was ours to
     * handle. While casting, Android's volume keys move the ring stream
     * rather than anything the renderer hears, so they're intercepted here
     * and forwarded to the renderer instead.
     */
    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!isCasting) return false
        val step = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_KEY_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_KEY_STEP
            else -> return false
        }
        setVolume(_volume.value + step)
        return true
    }

    /**
     * Set the output's volume, whichever output that is. While casting,
     * also nudges the device's own level to match, so the rocker starts
     * from where the renderer actually is.
     */
    fun setVolume(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        _plexPlayer.value?.let { target ->
            // See judgePlexVolume. Until settled, a drag is used as a probe;
            // the device's own level is left alone since the sound is
            // elsewhere.
            if (_plexVolume.value == PlexVolume.NotOurs) return
            val asked = (v * 100).roundToInt()
            plexVolumeAsked = asked
            _volume.value = v
            scope.launch {
                plexClient()?.companionSetParameters(
                    target,
                    listOf("volume" to asked.toString()),
                    ++plexCommandId,
                )
            }
            return
        }
        _volume.value = v
        val renderer = _castRenderer.value
        if (renderer != null) {
            scope.launch { DlnaCast.setVolume(renderer, v) }
        }
        player?.setSystemVolume(v)
    }

    /**
     * Toggles shuffle by reordering only the upcoming part of the live
     * queue, applied with incremental move ops so the playing track is
     * never re-prepared. Turning shuffle off restores the pre-shuffle order.
     */
    fun toggleShuffle() {
        val on = !_shuffle.value
        _shuffle.value = on
        val q = _queue.value
        val current = _index.value
        if (q.isEmpty() || current < 0) {
            preShuffleOrder = if (on) q.takeIf { it.isNotEmpty() } else null
            return
        }
        val head = q.take(current + 1)
        val tail = q.drop(current + 1)
        val newTail = if (on) {
            preShuffleOrder = q
            tail.shuffled()
        } else {
            val original = preShuffleOrder
            preShuffleOrder = null
            if (original == null) tail else restoreOrder(tail, original)
        }
        if (tail.isEmpty()) return
        _queue.value = head + newTail
        // One replace rather than a move per track, since moving a few
        // hundred positions one at a time locks the main thread and (with
        // artwork) exhausts the heap.
        player?.replaceRange(head.size, q.size, newTail.map { it.toAudioItem() })
        if (_plexPlayer.value != null) scope.launch { repushPlexQueue() }
    }

    // --- Plex Companion casting ----------------------------------------------
    //
    // Unlike DLNA, a Plex player (the Apple TV app, another Plexamp...) is
    // handed a server-side play queue and walks it itself. This side mirrors
    // rather than drives: polling adopts whatever the player reports, and
    // commands are relayed through the server rather than connecting to the
    // player directly. Only available for a Plex source.

    private fun plexClient(): PlexClient? = serverClient.value as? PlexClient

    /**
     * Companion players for the active Plex source. Empty on any other
     * source. Combines three sources since none alone is complete: the
     * server's own list, plex.tv's account devices (which forgets sleeping
     * ones), and [PlexGdm]'s local network broadcast (the only one that
     * finds a player nobody has heard from recently).
     */
    suspend fun findPlexPlayers(): List<PlexPlayer> {
        val client = plexClient() ?: return emptyList()
        // Server/plex.tv lookups can name players on a network this phone
        // isn't on, so skip them under Wi-Fi Only metered mode.
        val listed = if (metadataAllowed()) client.companionPlayers() else emptyList()
        // GDM only reaches players on the local network, so it's pointless
        // off Wi-Fi.
        val onNetwork = if (Connectivity.isOnWifi()) PlexGdm.findPlayers() else emptyList()
        val seen = listed.map { it.id }.toMutableSet()
        val extra = onNetwork.filter { seen.add(it.id) }
        android.util.Log.i("AmpPlex", "gdm found ${onNetwork.map { it.name }}, new here ${extra.map { it.name }}")
        return listed + extra
    }

    /**
     * Move playback to [target]: push the queue to the server, point the player
     * at it from the position we'd reached, and start mirroring its reports.
     */
    fun castToPlex(target: PlexPlayer) {
        val client = plexClient() ?: return
        // One route at a time; a DLNA renderer in progress is stopped quietly.
        if (_castRenderer.value != null) stopCasting(resumeLocally = false)
        player?.pause()
        _plexPlayer.value = target
        _plexVolume.value = PlexVolume.Unknown
        plexVolumeAsked = null
        plexVolumeProbes = 0
        val from = _positionMs.value
        plexWindowEnd = -1
        plexJob?.cancel()
        plexJob = scope.launch {
            if (_queue.value.isEmpty()) { _plexPlayer.value = null; return@launch }
            // A plex.tv-only device is identified by an address on file, and
            // a moved DHCP lease could point it at a different device now, so
            // confirm identity before sending any audio.
            if (!target.identityKnown && !client.playerIsWhoWeThink(target)) {
                android.util.Log.i("AmpPlex", "${target.name} isn't who plex.tv said — not casting")
                _plexPlayer.value = null
                return@launch
            }
            repushPlexQueue(positionMs = from)
            if (plexQueue == null) {
                android.util.Log.i("AmpPlex", "play queue refused — staying on this device")
                _plexPlayer.value = null
                return@launch
            }
            if (_repeatMode.value == RepeatMode.TRACK) {
                client.companionSetParameters(target, listOf("repeat" to "1"), ++plexCommandId)
            }
            subscribeToPlexPlayer(client, target)
            pollPlexPlayer(client, target)
        }
    }

    /** Stop steering [target] and pick playback back up here from where it got to. */
    fun stopPlexCasting(resumeLocally: Boolean = true) {
        val target = _plexPlayer.value ?: return
        val client = plexClient()
        val at = _positionMs.value
        val wasPlaying = _isPlaying.value
        plexJob?.cancel()
        plexJob = null
        _plexPlayer.value = null
        plexQueue = null
        plexWindowEnd = -1
        endPlexSubscription(client, target)
        scope.launch { client?.companionCommand(target, "stop", ++plexCommandId) }
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (resumeLocally) {
            // Same rebuild, not seek, hand-back as the DLNA route; see
            // stopCasting.
            scope.launch(Dispatchers.Main.immediate) {
                val tracks = _queue.value
                if (tracks.isEmpty()) return@launch
                val index = _index.value.coerceIn(0, tracks.lastIndex)
                android.util.Log.i("AmpPlex", "hand-back idx=$index at=$at playing=$wasPlaying")
                rebuildQueueAt(p, tracks, index, at)
                if (wasPlaying) p.play() else p.pause()
            }
        }
    }

    /**
     * Mirror the player's timeline until it stops being one. Subscribes
     * first so the player pushes state changes, falling back to polling for
     * a player or network that can't support that. Polls less often while
     * paused.
     */
    private suspend fun pollPlexPlayer(client: PlexClient, target: PlexPlayer) {
        var misses = 0
        while (coroutineContext.isActive && _plexPlayer.value?.id == target.id) {
            // Nothing to ask while the player is pushing; the loop stays as the
            // heartbeat that notices it going away.
            if (plexSubscribed) {
                delay(PLEX_SUBSCRIBED_CHECK_MS)
                if (System.currentTimeMillis() - plexLastPush > PLEX_PUSH_SILENCE_MS) {
                    android.util.Log.i("AmpPlex", "no pushes for a while — polling again")
                    plexSubscribed = false
                }
                continue
            }
            val t = client.companionTimeline(target, ++plexCommandId)
            if (t == null || t.state == "stopped") {
                // Tolerate a few misses before treating this as a real stop
                // rather than a transient blip.
                if (++misses >= PLEX_GONE_POLLS) {
                    plexCastEnded()
                    return
                }
            } else {
                misses = 0
                adoptPlexTimeline(t)
                topUpPlexWindow(client, target)
            }
            delay(if (_isPlaying.value) CAST_POLL_MS else PLEX_IDLE_POLL_MS)
        }
    }

    /**
     * Take the player's word for what is happening, from a poll or a push.
     * State flows one direction only; nothing read here is echoed back to
     * the player, or setting a mode would loop into reading it back.
     */
    private fun adoptPlexTimeline(t: PlexPlayerTimeline) {
        _isPlaying.value = t.state == "playing"
        _positionMs.value = t.timeMs
        if (t.durationMs > 0) _durationMs.value = t.durationMs
        judgePlexVolume(t.volume, t.controllable)
        // Repeat can be changed from the player's own remote.
        t.repeat?.let { reported ->
            val mode = when (reported) {
                1 -> RepeatMode.TRACK
                2 -> RepeatMode.QUEUE
                else -> RepeatMode.OFF
            }
            if (mode != _repeatMode.value) {
                android.util.Log.i("AmpPlex", "player's repeat is $mode")
                _repeatMode.value = mode
            }
        }
        t.shuffle?.let { on -> if (on != _shuffle.value) _shuffle.value = on }
        val rk = t.ratingKey
        if (rk != null && _queue.value.getOrNull(_index.value)?.id != rk) {
            // The player moved on its own; follow it rather than fight it.
            val idx = _queue.value.indexOfFirst { it.id == rk }
            if (idx >= 0) {
                _index.value = idx
                android.util.Log.i("AmpPlex", "player moved to idx=$idx")
            }
        }
    }

    /**
     * Decide whether the fader owns this player's volume. If the player's
     * own timeline lists what it accepts and omits `volume`, trust that
     * directly. Otherwise, judge by evidence: a level that moves on its own
     * or follows what was asked means the fader is real; one that ignores
     * several requests means it isn't.
     */
    private fun judgePlexVolume(reported: Int?, controllable: String?) {
        if (_plexVolume.value != PlexVolume.NotOurs &&
            controllable != null &&
            !controllable.split(',').any { it.trim().equals("volume", ignoreCase = true) }
        ) {
            plexVolumeNotOurs("player's timeline doesn't list volume")
            return
        }
        if (reported == null) return
        when (_plexVolume.value) {
            PlexVolume.Controllable -> _volume.value = (reported / 100f).coerceIn(0f, 1f)
            PlexVolume.NotOurs -> Unit
            PlexVolume.Unknown -> {
                val asked = plexVolumeAsked
                val followed = asked != null && kotlin.math.abs(reported - asked) <= PLEX_VOLUME_SLACK
                if (reported > 0 || followed) {
                    _plexVolume.value = PlexVolume.Controllable
                    plexVolumeAsked = null
                    _volume.value = (reported / 100f).coerceIn(0f, 1f)
                    android.util.Log.i("AmpPlex", "volume is ours (reported=$reported)")
                } else if (asked != null && ++plexVolumeProbes >= PLEX_VOLUME_PROBES) {
                    plexVolumeNotOurs("asked for $asked, player stayed at $reported")
                }
            }
        }
    }

    /**
     * Where the playing track sits after a queue edit. While casting, the
     * local player's index is stale (it's paused on whatever it held when
     * casting began), so the track is relocated by id instead, since an
     * index doesn't survive a reorder but an id does.
     */
    private fun indexAfterEdit(newQueue: List<Track>, playingId: String?): Int {
        if (newQueue.isEmpty()) return -1
        if (isCasting) {
            val byId = newQueue.indexOfFirst { it.id == playingId }
            return if (byId >= 0) byId else _index.value.coerceIn(0, newQueue.lastIndex)
        }
        return (player?.currentMediaItemIndex?.value ?: _index.value).coerceIn(0, newQueue.lastIndex)
    }

    /**
     * Apply a queue edit to the live play queue on the server, then tell
     * the player to re-read it. Editing in place leaves the playing track
     * alone, unlike pushing a whole new queue, which restarts it.
     */
    private fun plexQueueEdit(edit: suspend (PlexClient, PlexQueue) -> PlexQueue?) {
        val target = _plexPlayer.value ?: return
        val client = plexClient() ?: return
        val queue = plexQueue ?: return
        scope.launch {
            val edited = edit(client, queue)
            if (edited == null) {
                android.util.Log.i("AmpPlex", "queue edit refused — falling back to a re-push")
                repushPlexQueue()
                return@launch
            }
            // Re-read the window just pushed, since an edit's own answer may
            // carry less of the queue than the next edit needs.
            val updated = client.readPlayQueue(edited, PLEX_WINDOW) ?: edited
            plexQueue = updated
            plexPushedIds = _queue.value.map { it.id }
            client.companionRefreshQueue(target, updated, ++plexCommandId)
        }
    }

    /**
     * Hand the player a whole new queue, cued at [positionMs] of wherever
     * the index now points. The fallback for an edit too broad to express
     * in place, or one the server refused; restarts the playing track.
     */
    private suspend fun repushPlexQueue(positionMs: Long = _positionMs.value) {
        val target = _plexPlayer.value ?: return
        val client = plexClient() ?: return
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val index = _index.value.coerceIn(0, tracks.lastIndex)
        // A window around current playback, not the whole queue; see
        // [plexWindowEnd]. Keeps enough history that Previous still works on
        // the player's own remote.
        val from = (index - PLEX_WINDOW_BEHIND).coerceAtLeast(0)
        val to = (from + PLEX_WINDOW).coerceAtMost(tracks.size)
        val window = tracks.subList(from, to)
        val queue = client.createPlayQueue(
            window.map { it.id },
            tracks[index].id,
            repeatAll = _repeatMode.value == RepeatMode.QUEUE,
        ) ?: return
        plexQueue = queue
        plexPushedIds = window.map { it.id }
        plexWindowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionPlay(target, queue, tracks[index].id, positionMs, ++plexCommandId, PLEX_WINDOW)
        android.util.Log.i(
            "AmpPlex",
            "pushed ${window.size} of ${tracks.size} at idx=$index from ${positionMs}ms" +
                if (plexWindowEnd >= 0) ", window ends at $plexWindowEnd" else "",
        )
    }

    /**
     * Ask the player to push its state here instead of being asked for it.
     * Best effort: a player may refuse, or a network may not route back to
     * this phone (guest VLAN, client isolation). Falls back to polling.
     */
    private suspend fun subscribeToPlexPlayer(client: PlexClient, target: PlexPlayer) {
        val port = plexListener.start(scope)
        if (port == null) {
            android.util.Log.i("AmpPlex", "no port to be pushed to — polling")
            return
        }
        plexLastPush = System.currentTimeMillis()
        plexSubscribed = client.companionSubscribe(target, port, ++plexCommandId)
        android.util.Log.i(
            "AmpPlex",
            if (plexSubscribed) "subscribed; ${target.name} pushes to ${plexListener.localAddress()}:$port"
            else "subscribe declined — polling",
        )
    }

    /**
     * Hand the player the next stretch of a long queue as it nears the end
     * of what it has. An append edits the queue it already holds, so
     * nothing restarts, unlike pushing a fresh window.
     */
    private suspend fun topUpPlexWindow(client: PlexClient, target: PlexPlayer) {
        val end = plexWindowEnd
        if (end < 0) return
        val tracks = _queue.value
        if (end >= tracks.lastIndex) {
            plexWindowEnd = -1
            return
        }
        if (_index.value < end - PLEX_WINDOW_REFILL) return
        val queue = plexQueue ?: return
        val to = (end + 1 + PLEX_WINDOW_CHUNK).coerceAtMost(tracks.size)
        val more = tracks.subList(end + 1, to)
        val updated = client.addToPlayQueue(queue, more.map { it.id }, next = false) ?: return
        plexQueue = updated
        plexPushedIds = plexPushedIds + more.map { it.id }
        plexWindowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionRefreshQueue(target, updated, ++plexCommandId)
        android.util.Log.i("AmpPlex", "topped up ${more.size} more; window now ends at $plexWindowEnd")
    }

    private fun plexVolumeNotOurs(why: String) {
        _plexVolume.value = PlexVolume.NotOurs
        plexVolumeAsked = null
        // Shown as full rather than the phone's last level: the stream
        // leaves at unity gain, and the far end does the attenuating.
        _volume.value = 1f
        android.util.Log.i("AmpPlex", "volume isn't ours — $why; fader disabled at unity")
    }

    /** Stop the pushes and close the door behind them. */
    private fun endPlexSubscription(client: PlexClient?, target: PlexPlayer?) {
        val wasSubscribed = plexSubscribed
        plexSubscribed = false
        plexListener.stop()
        if (wasSubscribed && client != null && target != null) {
            scope.launch { client.companionUnsubscribe(target, ++plexCommandId) }
        }
    }

    private fun plexCastEnded() {
        if (_plexPlayer.value == null) return
        android.util.Log.i("AmpPlex", "player gone — keeping the place, paused")
        endPlexSubscription(plexClient(), _plexPlayer.value)
        _plexPlayer.value = null
        plexQueue = null
        _isPlaying.value = false
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        scope.launch(Dispatchers.Main.immediate) {
            val tracks = _queue.value
            if (tracks.isEmpty()) return@launch
            rebuildQueueAt(p, tracks, _index.value.coerceIn(0, tracks.lastIndex), _positionMs.value)
            p.pause()
        }
    }

    // --- DLNA casting --------------------------------------------------------

    /** Find renderers on the network. Blocking on the network for a few seconds. */
    suspend fun findCastDevices(): List<DlnaRenderer> = DlnaCast.discover()

    /**
     * Move playback to [renderer]: stop this device, hand the current track's
     * stream URL to the renderer at the position we'd reached, and start polling
     * it for progress.
     */
    fun castTo(renderer: DlnaRenderer) {
        // One route at a time; a Companion cast in progress hands over quietly.
        if (_plexPlayer.value != null) stopPlexCasting(resumeLocally = false)
        player?.pause()
        _castRenderer.value = renderer
        val from = _positionMs.value
        castJob?.cancel()
        castJob = scope.launch {
            // Adopt the renderer's current level, and move the device's own
            // to match, so the rocker starts from where the speaker is.
            DlnaCast.volume(renderer)?.let {
                _volume.value = it
                withContext(Dispatchers.Main.immediate) { player?.setSystemVolume(it) }
            }
            if (startCastTrack(renderer, from)) pollRenderer(renderer)
        }
    }

    /**
     * Stop casting and (by default) pick playback back up on this device from
     * wherever the renderer had reached.
     */
    fun stopCasting(resumeLocally: Boolean = true) {
        val renderer = _castRenderer.value ?: return
        val at = _positionMs.value
        castJob?.cancel()
        castJob = null
        _castRenderer.value = null
        castOffsetMs = 0L
        castSeekPending = -1L
        scope.launch { DlnaCast.stop(renderer) }
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (resumeLocally) {
            // The local player stayed paused on whatever track it held when
            // casting began, so it must be rebuilt at the current index and
            // position rather than seeked: a paused player has no duration
            // yet for an item it never opened, so a plain seek would clamp
            // to 0. Same reason restoreState rebuilds instead of seeking.
            scope.launch(Dispatchers.Main.immediate) {
                val tracks = _queue.value
                if (tracks.isEmpty()) return@launch
                val index = _index.value.coerceIn(0, tracks.lastIndex)
                android.util.Log.i("AmpCast", "hand-back idx=$index at=$at")
                rebuildQueueAt(p, tracks, index, at)
                p.play()
            }
        }
    }

    /**
     * Seek within the track the renderer is playing. A seekable stream (the
     * file served in ranges) takes one UPnP Seek call. Anything else, a
     * part-way stream, a transcode, or a renderer that refuses Seek, falls
     * back to pushing the track again at the new position.
     */
    private suspend fun seekCast(renderer: DlnaRenderer, ms: Long): Boolean {
        val track = _queue.value.getOrNull(_index.value) ?: return false
        val url = castCurrentUrl
        val wanted = if (ms > 1_000L) ms else 0L
        if (url != null && castOffsetMs == 0L) {
            val (format, _) = castFormatFor(renderer, track)
            val seekKey = "${track.id}|${format.id}"
            val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
            if (seekable == true) {
                if (DlnaCast.seek(renderer, wanted)) {
                    android.util.Log.i("AmpCast", "seek in place → $wanted")
                    _positionMs.value = wanted
                    castStartedAtMs = wanted
                    castSeekPending = wanted
                    if (wanted > 0L) verifyCastSeek(renderer, wanted, url)
                    return true
                }
                android.util.Log.i("AmpCast", "in-place seek refused → push")
            }
        }
        return startCastTrack(renderer, wanted)
    }

    /**
     * A moment after a seek, check the renderer really went there (via
     * GetPositionInfo); if not, ask the server for the track from that
     * position instead. A renderer can accept a Seek it can't honor, e.g. a
     * chunked live transcode with no length to jump to, which otherwise
     * grinds forward in silence. Skipped near the end of a track, since the
     * renderer's position resets to 0 there and would read as a failed seek.
     */
    private fun verifyCastSeek(renderer: DlnaRenderer, wanted: Long, url: String) {
        val nearEnd = _durationMs.value > 0 &&
            _durationMs.value - wanted <= CAST_SEEK_VERIFY_MS + CAST_SEEK_TOLERANCE_MS
        if (nearEnd) return
        val generation = ++seekGeneration
        val seekedIndex = _index.value
        scope.launch {
            delay(CAST_SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (_castRenderer.value?.id != renderer.id) return@launch
            // Moved on, so the seek has nothing left to measure against.
            if (_index.value != seekedIndex || castCurrentUrl != url) return@launch
            val info = DlnaCast.position(renderer) ?: return@launch
            if (info.trackUri != null && info.trackUri != url) return@launch
            if (DlnaCast.state(renderer) == DlnaState.STOPPED) return@launch
            if (info.positionMs >= wanted - CAST_SEEK_TOLERANCE_MS) return@launch
            android.util.Log.i("AmpCast", "seek missed: at ${info.positionMs} wanted $wanted → server")
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer, wanted, askServer = true)) {
                    pollRenderer(renderer, playedBefore = true)
                }
            }
        }
    }

    /**
     * Push the queue's current track to the renderer, optionally seeking in.
     * The renderer tries to seek itself first (any response with a length
     * and byte ranges supports this); only on refusal is the seek handed to
     * the server as a `timeOffset`, tracked by [askServer].
     */
    private suspend fun startCastTrack(
        renderer: DlnaRenderer,
        seekToMs: Long = 0L,
        askServer: Boolean = false,
    ): Boolean {
        val track = _queue.value.getOrNull(_index.value) ?: return false
        // A new track on the renderer is a new session, same as the local
        // player's; see the index collector in bind(). Also covers a seek,
        // since it restarts the renderer's stream the same way a track
        // change does.
        openSessionFor(track.id)
        val (format, mime) = castFormatFor(renderer, track)
        // Anything under a second is where the track starts anyway, and a
        // timeOffset of 0 is a re-encode for nothing.
        val wanted = if (seekToMs > 1_000L) seekToMs else 0L
        val offset = if (askServer) wanted else 0L
        // No estimated length for a renderer: Navidrome's estimate assumes
        // the cap bitrate, but its actual ABR encode comes in smaller, so a
        // UPnP renderer that trusts the declared length stops the stream
        // early. Chunked plays to the real end of the audio.
        val url = serverClient.value?.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offset / 1000).toInt(),
            sessionId = sessionIdFor(track.id),
        ) ?: return false
        val started = DlnaCast.play(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        if (!started) return false
        android.util.Log.i("AmpCast", "pushed idx=${_index.value} wanted=$wanted offset=$offset ${format.id}")
        _isPlaying.value = true
        _durationMs.value = track.durationMs
        castOffsetMs = offset
        _positionMs.value = wanted
        castStartedAtMs = wanted
        if (offset == 0L && wanted > 0L) {
            // Check seekability before asking the renderer to seek, since the
            // same format is a transcode for one file and the file itself for
            // another; see DlnaCast.supportsRanges.
            val seekKey = "${track.id}|${format.id}"
            val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
            android.util.Log.i("AmpCast", "seekable=$seekable")
            if (seekable == false) {
                return startCastTrack(renderer, wanted, askServer = true)
            }
            // Checked a moment later; see verifyCastSeek.
            if (!DlnaCast.seek(renderer, wanted)) {
                android.util.Log.i("AmpCast", "renderer refused seek → server")
                return startCastTrack(renderer, wanted, askServer = true)
            }
            verifyCastSeek(renderer, wanted, url)
        }
        announceNowPlaying()
        castCurrentUrl = url
        castSeekPending = -1L
        // SetAVTransportURI leaves the next slot empty until this track is
        // nearly over; see armNextIfDue.
        castNextUrl = null
        castNextArmedFor = null
        return true
    }

    /**
     * Ask the server whether this track's stream can be seeked, and
     * remember it. Deliberately not the URL the renderer is playing: that
     * one carries the session id, and a second request with the same id
     * would make Plex tear down the first, stopping the music.
     */
    private suspend fun probeSeekable(
        track: Track,
        format: StreamFormat,
        key: String,
    ): Boolean? {
        val probeUrl = serverClient.value?.streamUrl(
            track,
            format,
            estimateContentLength = false,
        ) ?: return null
        return DlnaCast.supportsRanges(probeUrl)?.also { castSeekable[key] = it }
    }

    /**
     * Hand over the following track once the current one is nearly
     * finished. A renderer opens the stream it's given immediately, so
     * handing it over too early leaves the connection open and idle long
     * enough that the server's transcoder output rots before playback
     * reaches it.
     */
    private suspend fun armNextIfDue(renderer: DlnaRenderer) {
        val total = _durationMs.value
        // A track of unknown length (zero) still gets its hand-off, since
        // never arming at all would be a gap on every track.
        if (total > 0L && total - _positionMs.value > CAST_PREQUEUE_LEAD_MS) return
        queueNextOnRenderer(renderer)
    }

    /**
     * Hand the renderer the following track so it can cross into it
     * seamlessly. Records the URL under [castNextUrl] so the poller can
     * recognise the hand-off: the renderer switches on its own, and the
     * only evidence is GetPositionInfo reporting the other URI. Call only
     * through [armNextIfDue], never directly.
     */
    private suspend fun queueNextOnRenderer(renderer: DlnaRenderer) {
        val after = nextCastIndex()
        val track = after?.let { _queue.value.getOrNull(it) }
        if (after == null || track == null) {
            castNextUrl = null
            castNextArmedFor = null
            return
        }
        // Offered once per track only.
        if (castNextArmedFor == after) return
        val (format, mime) = castFormatFor(renderer, track)
        // Its own id, not the current track's: both transcodes are in
        // flight at once during the hand-off, and Plex tears down whichever
        // shares an identifier with the other.
        val url = serverClient.value
            ?.streamUrl(track, format, estimateContentLength = false, sessionId = sessionIdFor(track.id))
            ?: return
        if (url == castNextUrl) return
        castNextArmedFor = after
        castNextUrl = null
        val queued = DlnaCast.setNext(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        // Renderers that don't implement it fault; the stop-and-restart path
        // then carries the queue as before.
        if (queued) castNextUrl = url
    }

    /** The index the queue would move to next, or null at the end of it. */
    private fun nextCastIndex(): Int? {
        val size = _queue.value.size
        if (size == 0) return null
        return when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % size
            RepeatMode.OFF -> (_index.value + 1).takeIf { it < size }
        }
    }

    /**
     * Format to cast in: the user's chosen streaming quality, honoured as
     * long as the renderer can decode what the server will actually send.
     * The format name in the URL doesn't fix the container (a "flac"
     * transcode can arrive as Ogg-FLAC), so the MIME type is probed from
     * the server. An unsupported format doesn't error, the renderer just
     * stalls, so this falls back to MP3 when the sink list doesn't cover
     * the probed MIME. If the device won't list its formats, the user's
     * choice wins.
     */
    private suspend fun castFormatFor(
        renderer: DlnaRenderer,
        track: Track,
    ): Pair<StreamFormat, String> {
        val desired = effectiveFormat()
        val key = "${renderer.id}|${desired.id}"
        castFormats[key]?.let { return it }

        val client = serverClient.value
        val sink = runCatching { DlnaCast.sinkFormats(renderer) }.getOrDefault(emptySet())
        val mime = client?.let { DlnaCast.probeMime(it.streamUrl(track, desired)) }
            ?: assumedMime(desired)
        val choice = if (sinkAccepts(sink, mime)) {
            desired to mime
        } else {
            StreamFormat.MP3 to "audio/mpeg"
        }
        castFormats[key] = choice
        return choice
    }

    private fun assumedMime(format: StreamFormat): String = when (format) {
        StreamFormat.MP3 -> "audio/mpeg"
        StreamFormat.OPUS -> "audio/ogg"
        StreamFormat.FLAC -> "audio/flac"
        // Unknown container up front; the DLNA wildcard lets the device sniff.
        StreamFormat.RAW -> "*"
    }

    /**
     * Whether the renderer's advertised sink covers [mime]. Compared through
     * alias groups because devices and servers spell the same container
     * differently (`audio/mp4` vs `audio/m4a`, `audio/mpeg` vs `audio/mp3`).
     */
    private fun sinkAccepts(sink: Set<String>, mime: String): Boolean {
        if (sink.isEmpty() || mime == "*") return true
        val aliases = when (mime) {
            "audio/mpeg", "audio/mp3" -> setOf("audio/mpeg", "audio/mp3")
            "audio/flac", "audio/x-flac" -> setOf("audio/flac", "audio/x-flac")
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" ->
                setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")
            "audio/ogg", "audio/opus", "application/ogg" ->
                setOf("audio/ogg", "audio/opus", "application/ogg")
            "audio/wav", "audio/x-wav" -> setOf("audio/wav", "audio/x-wav")
            else -> setOf(mime)
        }
        return sink.any { it.substringBefore(';').trim() in aliases }
    }

    /**
     * Mirror the renderer's clock into the UI and advance the queue when a
     * track finishes. A renderer only ever holds one URI, so gapless
     * hand-off has to be driven from here.
     */
    private suspend fun pollRenderer(renderer: DlnaRenderer, playedBefore: Boolean = false) {
        var settled = false
        // A track pushed again for a seek was already playing, so a stop
        // right after counts as the track ending rather than never starting.
        var played = playedBefore
        // A position that hasn't moved is not evidence anything is playing.
        var lastPositionMs = -1L
        // Tracks a manual-start attempt, so a track that refuses to play is
        // asked once and then passed over rather than retried forever.
        var startedIndex = -1
        // A stream opened from scratch deserves a few seconds of patience; a
        // track already handed off and stopped on does not; see the hand-off
        // branch below.
        var restartAfter = STALLED_POLLS_TO_RESTART
        // STOPPED can mean re-buffering or a real end, indistinguishable
        // from a single poll; two in a row means the stream really ended.
        val stall = CastStallCounter()
        while (coroutineContext.isActive && _castRenderer.value?.id == renderer.id) {
            delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            if (position != null) {
                // Follow the renderer's own hand-off to the next track rather
                // than driving it. Must be a different URI from the one we
                // started, since repeat-one queues the same track again.
                val moved = castNextUrl != null &&
                    castNextUrl != castCurrentUrl &&
                    position.trackUri == castNextUrl
                if (moved) {
                    nextCastIndex()?.let { _index.value = it }
                    castCurrentUrl = castNextUrl
                    // The queued track was handed over whole, from its start.
                    castOffsetMs = 0L
                    castStartedAtMs = 0L
                    castSeekPending = -1L
                    _durationMs.value = _queue.value.getOrNull(_index.value)?.durationMs
                        ?: _durationMs.value
                    announceNowPlaying()
                    // Left empty until this track is nearly over.
                    castNextUrl = null
                    castNextArmedFor = null
                    stall.reset()
                    // Reset so the new track gets the same startup grace a
                    // track gets when it first starts; the renderer can
                    // report STOPPED at the old position for a moment while
                    // opening the new stream, which otherwise reads as an end.
                    settled = false
                    played = false
                    // Seeded with the current reading, not cleared, so the
                    // same stale value next poll doesn't look like a change.
                    lastPositionMs = position.positionMs
                    _positionMs.value = 0L
                    // A successful gapless hand-off has the renderer already
                    // PLAYING at position zero. A dead one sits stopped on the
                    // old position and never recovers on its own, so give it
                    // only one further poll before restarting.
                    restartAfter = if (state == DlnaState.PLAYING) {
                        STALLED_POLLS_TO_RESTART
                    } else {
                        HANDOFF_POLLS_TO_RESTART
                    }
                    continue
                }
                // Only trust readings about our own track: between streams
                // the renderer can keep reporting the old one's frozen
                // position for a poll or two.
                val ours = position.trackUri == null || position.trackUri == castCurrentUrl
                if (ours) {
                    // Until settled, only a reading near the start point is
                    // believed, since the renderer can report the new URI
                    // with the old clock briefly while opening the stream.
                    val reading = position.positionMs + castOffsetMs
                    val pending = castSeekPending
                    if (pending >= 0L) {
                        // A seek is in flight; only a reading near the target
                        // is trusted. If none ever comes, the seek's own
                        // check re-pushes.
                        if (kotlin.math.abs(reading - pending) <= CAST_START_WINDOW_MS) {
                            castSeekPending = -1L
                            _positionMs.value = reading
                        }
                    } else if (settled || kotlin.math.abs(reading - castStartedAtMs) <= CAST_START_WINDOW_MS) {
                        _positionMs.value = reading
                    }
                    // A stream that started part-way in reports a short
                    // duration, so only trust it when there's no offset.
                    if (castOffsetMs == 0L && position.durationMs > 0) {
                        _durationMs.value = position.durationMs
                    }
                    // A stopped renderer's position is never evidence the
                    // track has started, since it keeps reporting the
                    // finished track's last position through a hand-off.
                    if (state != DlnaState.STOPPED &&
                        position.positionMs > 0 &&
                        position.positionMs != lastPositionMs
                    ) {
                        settled = true
                    }
                    lastPositionMs = position.positionMs
                }
            }
            stall.observe(state)
            when (state) {
                DlnaState.PLAYING -> {
                    settled = true
                    _isPlaying.value = true
                }
                DlnaState.PAUSED -> _isPlaying.value = false
                else -> Unit
            }
            when {
                // Played, then stopped: the track ended.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE -> {
                    android.util.Log.i("AmpCast", "ended idx=${_index.value} → advance")
                    if (!advanceCast(renderer)) return
                    settled = false
                    played = false
                    stall.reset()
                    startedIndex = -1
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
                // Never played at all: a renderer can take a queued-ahead
                // track and then sit stopped on it forever. Start it rather
                // than advancing past it. Also covers a renderer that has
                // stopped answering.
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex != _index.value -> {
                    android.util.Log.i("AmpCast", "never started idx=${_index.value} → restart")
                    startedIndex = _index.value
                    stall.reset()
                    restartAfter = STALLED_POLLS_TO_RESTART
                    if (!startCastTrack(renderer)) return
                }
                // Asked again and it still won't play: skip the track.
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex == _index.value -> {
                    android.util.Log.i("AmpCast", "still not playing idx=${_index.value} → pass over")
                    if (!advanceCast(renderer)) return
                    stall.reset()
                    startedIndex = -1
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
            }
            armNextIfDue(renderer)
        }
    }

    /**
     * Rebuild the queue's sources when the answer to "file or stream?" may
     * have changed (Wi-Fi state, a finished download, a data mode change).
     * The playing item is left alone, since replacing it would restart the
     * track; everything after it is corrected.
     */
    private fun reresolveQueue() {
        val p = player ?: return
        if (isCasting) return
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val current = _index.value.coerceIn(0, tracks.lastIndex)
        // Skip unless something actually changed; this runs on every
        // completed download, most of which change nothing here.
        val rebuilt = tracks.map { it.source() }
        if (rebuilt == queuedSources) return
        queuedSources = rebuilt
        // ExoPlayer's looper is Main-only; see restoreState.
        scope.launch(Dispatchers.Main.immediate) {
            if (current > 0) {
                p.replaceRange(0, current, tracks.subList(0, current).map { it.toAudioItem() })
            }
            if (current + 1 <= tracks.lastIndex) {
                p.replaceRange(
                    current + 1,
                    tracks.size,
                    tracks.subList(current + 1, tracks.size).map { it.toAudioItem() },
                )
            }
        }
    }

    /** What the queue was last built from, so a no-op rebuild stays a no-op. */
    private var queuedSources: List<LightAudioSource> = emptyList()

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % _queue.value.size.coerceAtLeast(1)
            RepeatMode.OFF -> _index.value + 1
        }
        if (next !in _queue.value.indices) {
            _isPlaying.value = false
            return false
        }
        _index.value = next
        android.util.Log.i("AmpCast", "advance → idx=$next")
        return startCastTrack(renderer)
    }

    // --- internals -----------------------------------------------------------

    private fun setQueue(tracks: List<Track>, startIndex: Int) {
        val p = player ?: return
        _queue.value = tracks
        lastScrobbledId = null
        resetListened()
        // Keep the local player loaded even while casting, so switching back to
        // this device resumes instantly instead of re-preparing the queue.
        queuedSources = tracks.map { it.source() }
        p.setMediaQueue(tracks.map { it.toAudioItem() }, startIndex)
        _index.value = startIndex
        // A leftover offset from a previous seek would get added to this new
        // track's clock. Cleared explicitly since the index collector only
        // fires on an index change, and a new queue often starts on the same
        // index.
        streamOffsetMs = 0
        // Every slot of a new queue is a full item; nothing is left to restore.
        offsetItemTrackId = null
        // Picking something new to play is reason enough to try the server again.
        streamFailed.clear()
        // Seeded immediately rather than waiting on the stream to declare a
        // length; see the durationMs collector in ensurePlayer.
        tracks.getOrNull(startIndex)?.durationMs
            ?.takeIf { it > 0L }
            ?.let { _durationMs.value = it }
        // A new queue follows wherever sound is already going, rather than
        // starting here while a Companion player keeps playing its own queue.
        if (_plexPlayer.value != null) {
            p.pause()
            _positionMs.value = 0L
            scope.launch { repushPlexQueue(positionMs = 0L) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            p.pause()
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        p.play()
    }

    /** Sort [tracks] back into the relative order they had in [original]. */
    private fun restoreOrder(tracks: List<Track>, original: List<Track>): List<Track> {
        val rank = original.withIndex().associate { (i, t) -> t.id to i }
        // Tracks queued after shuffling have no pre-shuffle rank; keep them last.
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * A stream died; carry on from the downloaded copy, at the same
     * position, if there is one. [forceOffline] latches until the server
     * answers again, since the next track would otherwise fail the same way.
     */
    private fun fallBackOffline(serverAnswered: Boolean) {
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (!serverAnswered) App.reportServerReachable(false)
        // Nothing local to fall back to: let the error stand rather than
        // restarting a stream that just failed.
        if (!localFiles.containsKey(track.id)) return
        // One attempt per track, so a file that is itself unplayable (a codec the
        // device can't decode) can't spin here.
        if (retriedTrackId == track.id) return
        retriedTrackId = track.id
        streamFailed += track.id
        // Only a server that never answered justifies pinning everything to
        // local files; the latch releases when the server answers again.
        if (!serverAnswered) forceOffline = true
        val resumeAt = _positionMs.value
        scope.launch(Dispatchers.Main.immediate) {
            p.setMediaQueueAt(_queue.value.map { it.toAudioItem() }, _index.value, resumeAt)
            p.play()
        }
    }

    /**
     * Tell the server this session is playing, paused or stopped. See
     * [MusicServer.reportTimeline]. Fire-and-forget: a dropped heartbeat
     * just leaves the dashboard stale until the next one.
     */
    private fun reportTimeline(state: TimelineState) {
        // A Companion player reports its own timeline directly; a second
        // report from here would double every play.
        if (_plexPlayer.value != null) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (LocalLibrary.isLocal(track.id)) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val sid = sessionIdFor(track.id)
        val position = _positionMs.value
        val duration = _durationMs.value
        scope.launch { runCatching { client.reportTimeline(sid, track.id, state, position, duration) } }
    }

    /**
     * Close out the session for a track that has stopped being the one
     * playing, otherwise it sits in `/status/sessions` until its lease
     * times out. Takes the track explicitly since the queue has usually
     * already moved on by the time this runs.
     */
    private fun reportTimelineStopped(oldTrackId: String) {
        if (LocalLibrary.isLocal(oldTrackId)) return
        // Gated here too, since this calls the client directly rather than
        // through reportTimeline's gate.
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val sid = sessionIdFor(oldTrackId)
        scope.launch {
            runCatching {
                client.reportTimeline(sid, oldTrackId, TimelineState.STOPPED, 0L, 0L)
            }
        }
    }

    /**
     * Hand the open session over to [newTrackId], stopping the outgoing
     * one. The id itself comes from [sessionIdFor]; this only tracks which
     * session is currently open.
     */
    private fun openSessionFor(newTrackId: String) {
        val outgoing = sessionTrackId
        if (outgoing == newTrackId) return
        outgoing?.let { reportTimelineStopped(it) }
        sessionTrackId = newTrackId
    }

    /**
     * Say what is playing, the moment it starts. Distinct from a play
     * submission; see [MusicServer.scrobble]. Not retried on failure,
     * since a now-playing notice replayed later would simply be false.
     */
    private fun announceNowPlaying() {
        // The Companion player reports its own now-playing state directly.
        if (_plexPlayer.value != null) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (LocalLibrary.isLocal(track.id)) return
        if (track.id == nowPlayingId) return
        nowPlayingId = track.id
        val client = serverClient.value ?: return
        scope.launch {
            runCatching { client.scrobble(track.id, submission = false) }
                .onFailure { android.util.Log.w("AmpNowPlaying", "announce failed for ${track.id}", it) }
        }
    }

    /**
     * How much of a track has to be heard before the play counts: half of
     * it or four minutes, whichever is less (Last.fm's rule). Falls back
     * to four minutes for a stream that never reports its length.
     */
    private fun submitAfterMs(durationMs: Long): Long =
        if (durationMs <= 0L) SUBMIT_AFTER_MS else minOf(durationMs / 2, SUBMIT_AFTER_MS)

    /** Submit the play, once enough of the track has actually been heard; gated on [currentListenedMs]. */
    private fun maybeSubmitPlay() {
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (LocalLibrary.isLocal(track.id)) return
        if (track.id == lastScrobbledId) return
        if (currentListenedMs() < submitAfterMs(_durationMs.value)) return
        lastScrobbledId = track.id
        // Timestamped now so an offline play keeps its real time once sent.
        val at = System.currentTimeMillis()
        val client = serverClient.value
        scope.launch {
            runCatching { App.library.markPlayed(track.id) }
            val sent = client != null &&
                runCatching { client.scrobble(track.id, at) }.isSuccess
            if (!sent) {
                runCatching {
                    App.pending.add(PendingAction(PendingAction.Kind.SCROBBLE, track.id, atMs = at))
                }
            }
        }
    }

    /**
     * The format to actually request. Low Data only affects cellular; the
     * chosen Wi-Fi format applies as normal there. Off Wi-Fi, Opus at 192
     * kbps uses less bandwidth than MP3 320 and sounds better, so the
     * cellular default is Opus rather than MP3.
     */
    private fun effectiveFormat(): StreamFormat {
        // Read from the source rather than the client, since the format is
        // a property of which server the source is, and not every backend
        // carries a config object.
        val source = App.source.value
        return if (unmetered) source.wifiFormat else source.cellularFormat
    }

    /**
     * Where to play a track from. Downloads win by default. A download is
     * skipped only when the chosen streaming format is genuinely better
     * than the downloaded copy and the connection is unmetered.
     */
    private fun Track.source(): LightAudioSource {
        // A track from the phone's own library is always the file.
        LocalLibrary.fileOf(id)?.let { return LightAudioSource.FileSource(it) }
        val local = localFiles[id]
        if (local != null) {
            val (file, downloadedFormat) = local
            val streamed = effectiveFormat()
            val preferStream = !forceOffline && id !in streamFailed && unmetered &&
                streamed.qualityRank > downloadedFormat.qualityRank
            if (!preferStream) return LightAudioSource.FileSource(file)
        }
        return LightAudioSource.UrlSource(
            // No estimated Content-Length: declaring a length for a
            // transcode requires the server to finish the encode first,
            // which stalls playback start for however long the track is.
            // Only Subsonic reads this flag; Jellyfin and Plex ignore it.
            serverClient.value?.streamUrl(
                this,
                effectiveFormat(),
                estimateContentLength = false,
                sessionId = sessionIdFor(id),
            ).orEmpty(),
        )
    }

    /**
     * Rebuild the player's queue on [index], cued at [positionMs]. Main
     * only. A chunked transcode has nothing to seek by, so ExoPlayer would
     * silently open it from 0:00; those go through the server like
     * [seekTo] does, with [streamOffsetMs] shifting the readouts. A
     * seekable stream keeps the cheap native route.
     */
    private fun rebuildQueueAt(p: LightAudioPlayer, tracks: List<Track>, index: Int, positionMs: Long) {
        val track = tracks[index]
        val at = positionMs.coerceAtLeast(0L)
        queuedSources = tracks.map { it.source() }
        // Set before the player moves: the index collector reads a change
        // as a new track and would clear the offset this is about to set.
        _index.value = index
        _positionMs.value = at
        // Seeded from the library since the index collector is suppressed
        // here, and a chunked stream may never report its own duration.
        track.durationMs.takeIf { it > 0L }?.let { _durationMs.value = it }
        val client = serverClient.value
        if (at > 1_000L && client != null && needsServerSeek(track, askPlayer = false)) {
            android.util.Log.i("AmpCast", "rebuild idx=$index at=$at via server offset")
            val url = client.streamUrl(
                track,
                effectiveFormat(),
                timeOffsetSeconds = (at / 1000).toInt(),
                // See serverSeek: an estimated length invites range probes the
                // transcode can't answer (416), which errored the restore.
                estimateContentLength = false,
                sessionId = sessionIdFor(track.id),
            )
            streamOffsetMs = at
            offsetItemTrackId = track.id
            p.setMediaQueue(
                tracks.mapIndexed { i, it ->
                    if (i == index) it.toAudioItem(LightAudioSource.UrlSource(url)) else it.toAudioItem()
                },
                index,
            )
        } else {
            android.util.Log.i("AmpCast", "rebuild idx=$index at=$at natively")
            streamOffsetMs = 0
            offsetItemTrackId = null
            p.setMediaQueueAt(tracks.map { it.toAudioItem() }, index, at)
            if (at > 0L) verifyNativeSeek(p, track, at, awaitStream = true)
        }
    }

    private fun Track.toAudioItem(from: LightAudioSource = source()): LightAudioItem = LightAudioItem(
        source = from,
        metadata = LightMediaMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        ),
    )

    companion object {
        private const val PREVIOUS_RESTART_MS = 3_000L
        /** How close a seeked stream's duration must be to the full track's before the offset is judged ignored. */
        private const val IGNORED_OFFSET_SLACK_MS = 3_000L

        /** How near the end still counts as having reached it. */
        private const val QUEUE_END_SLACK_MS = 1_000L

        /** How long to wait for the server's stream decision before starting anyway. */
        private const val STREAM_DECISION_TIMEOUT_MS = 2_000L

        /** Long enough for the player to be reporting the stream, not the request. */
        private const val SEEK_VERIFY_MS = 900L

        /** How long a rebuilt queue gets to open its stream before the seek is judged. */
        private const val REBUILD_VERIFY_TIMEOUT_MS = 8_000L

        /** How far a native seek may land from the target before it counts as a miss. */
        private const val SEEK_TOLERANCE_MS = 5_000L

        /** Long enough for a renderer that can seek to have done so. */
        private const val CAST_SEEK_VERIFY_MS = 4_000L

        /** A cast seek this close to the end is the next track, not a seek. */
        private const val CAST_SEEK_END_SLACK_MS = 3_000L
        private const val CAST_SEEK_TOLERANCE_MS = 15_000L
        /** How far a just-started track's first readings may stray from its start before being disbelieved. */
        const val CAST_START_WINDOW_MS = 15_000L

        private const val CAST_POLL_MS = 1_000L

        /** Consecutive silent/stopped polls before a Companion player counts as gone. */
        private const val PLEX_GONE_POLLS = 5

        /** How much of a long queue a player is given at once, and how much sits behind the playing track. */
        private const val PLEX_WINDOW = 150
        private const val PLEX_WINDOW_BEHIND = 20

        /** Long enough for a player to act on a skip before it is judged. */
        private const val PLEX_SKIP_VERIFY_MS = 2_500L

        /** How often a subscribed cast checks that pushes are still coming. */
        private const val PLEX_SUBSCRIBED_CHECK_MS = 3_000L

        /** Silence from a subscribed player before falling back to polling. */
        private const val PLEX_PUSH_SILENCE_MS = 12_000L

        /** Poll interval while the player is paused, since its state isn't moving. */
        private const val PLEX_IDLE_POLL_MS = 5_000L

        /** Tracks left ahead of the player before the window is topped up. */
        private const val PLEX_WINDOW_REFILL = 30

        /** How many more go over in a top-up. */
        private const val PLEX_WINDOW_CHUNK = 80

        /** How near the asked-for level counts as the player having taken it. */
        private const val PLEX_VOLUME_SLACK = 5

        /** Polls a player gets to follow a volume before the fader gives up on it. */
        private const val PLEX_VOLUME_PROBES = 3

        /** The most tracks the player is ever given at once, and how many already-played ones are kept behind it. */
        private const val MAX_QUEUE = 500
        private const val QUEUE_LOOKBACK = 50

        /** One press of the rocker, as a fraction of full scale. */
        private const val VOLUME_KEY_STEP = 0.05f

        /** Consecutive STOPPED readings that mean the track really ended. */
        private const val STOPPED_POLLS_TO_ADVANCE = 2

        /**
         * How long a track that has never played is given before it's
         * started manually; see the STOPPED branch in pollRenderer. Longer
         * than the advance threshold, since a renderer opening a fresh
         * stream is legitimately stopped for a few seconds while loading.
         */
        private const val STALLED_POLLS_TO_RESTART = 6

        /** Same as [STALLED_POLLS_TO_RESTART], but for a hand-off track that already had time to prepare. */
        private const val HANDOFF_POLLS_TO_RESTART = 2

        /** How close to the end of the current track the next one is handed over; see [armNextIfDue]. */
        private const val CAST_PREQUEUE_LEAD_MS = 15_000L

        /** Settling time before re-arming the renderer's next track after an edit. */
        private const val CAST_REQUEUE_DEBOUNCE_MS = 400L
        private const val CAST_STOP_TIMEOUT_MS = 1_500L

        /** How long a restore waits to learn what is downloaded before deciding. */
        private const val DOWNLOADS_READY_TIMEOUT_MS = 4_000L

        private const val SAVE_STATE_INTERVAL_MS = 5_000L
        private const val SAVE_STATE_TIMEOUT_MS = 1_000L

        /** How often a live session is re-announced; PlexAmp uses roughly this. */
        private const val TIMELINE_INTERVAL_MS = 10_000L

        /** Upper bound on "enough of it was heard to count as a play" (half the track, if shorter). */
        private const val SUBMIT_AFTER_MS = 4 * 60 * 1000L

        /** How many tracks of the queue the server is told about. */
        private const val MAX_SAVED_QUEUE = 100
    }
}
