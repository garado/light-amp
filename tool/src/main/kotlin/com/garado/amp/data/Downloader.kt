package com.garado.amp.data

import com.garado.amp.App
import com.garado.amp.data.db.DownloadEntity
import com.garado.amp.data.db.LibraryDao
import com.garado.amp.data.db.toTrack
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.transfer.LightTransferService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val pending: Int = 0,
    val completed: Int = 0,
    val currentTitle: String? = null,
    /** Which server the current track is coming from, by name, for the Downloads page. */
    val currentSource: String? = null,
    /** Set when the size limit stopped the queue rather than it finishing. */
    val limitReached: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = pending > 0
}

/**
 * Downloads tracks for offline playback and keeps the download index in step
 * with the files on disk.
 *
 * Queues tracks from every configured server at once, each tagged with its
 * source; the worker resolves the client, tables, folder, and format per
 * track from that tag rather than from whichever source is being browsed.
 * Downloads are held back per source by data mode, user pause, server
 * unreachability, or a mid-sync library; see [DownloadQueue] for the order.
 *
 * Runs one worker at a time: measured against a real Navidrome server,
 * concurrent streams did not add bandwidth (transcoding is the bottleneck)
 * and increased completion latency instead.
 */
class Downloader(
    /**
     * The source being browsed, for calls the library screens make about the
     * tracks on them: [enqueue] without a source, [remove], [cachedLyrics].
     * The worker never reads it; every queued track names its own source.
     */
    private val activeSource: () -> MusicSource,
    /** A source's tables; App.databaseFor. */
    private val daoFor: (MusicSource) -> LibraryDao,
    /** A source's client; App.clientFor. Null for the phone's own music. */
    private val clientFor: (MusicSource) -> MusicServer?,
    private val store: DownloadStore,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Used only to hold the process at foreground priority while draining. */
    private val lightContext: SealedLightContext,
    /** Whether real bytes may move right now; see App.heavyDataAllowed. Checked between tracks. */
    private val heavyDataAllowed: () -> Boolean,
) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    /** Everything waiting, from every source; guarded by [lock]. */
    private val queue = DownloadQueue()
    private val lock = Mutex()
    private var worker: Job? = null

    /**
     * The entry being transferred right now, and one that [cancelSource] has
     * disowned mid-transfer. The transfer is blocking I/O and isn't
     * interrupted; its result is discarded when it ends instead, rather than
     * cancelling the worker and dropping the foreground service for every
     * other source.
     */
    @Volatile
    private var inFlight: DownloadQueue.Key? = null

    @Volatile
    private var abandoned: DownloadQueue.Key? = null

    /**
     * The source whose library is syncing, if one is. That source's downloads
     * yield to the sync, since both compete for the same server's transcoder.
     * Only the browsed source ever syncs, so one id is enough.
     */
    @Volatile
    private var syncingSourceId: String? = null

    /** Set from the Downloads page and remembered across launches. */
    @Volatile
    private var userPaused = false

    /**
     * When to try each source again after its server stopped answering, as a
     * deadline rather than a latched flag, so retries happen on their own. Per
     * source, so one down server doesn't hold up the others; see [eligible].
     */
    private val retryAtBySource = ConcurrentHashMap<String, Long>()

    /** Consecutive failed transfers per source, for how long to wait before the next try. */
    private val failuresBySource = ConcurrentHashMap<String, Int>()

    private fun waiting(sourceId: String): Boolean =
        System.currentTimeMillis() < (retryAtBySource[sourceId] ?: 0L)

    /** Whether a source's tracks may be picked right now. */
    private fun eligible(sourceId: String): Boolean =
        sourceId != syncingSourceId && !waiting(sourceId)

    /** Backs off to a minute, so a server that is really down isn't hammered. */
    private fun backOff(sourceId: String) {
        val failures = (failuresBySource[sourceId] ?: 0) + 1
        failuresBySource[sourceId] = failures
        val wait = (RETRY_BASE_MS * (1L shl (failures - 1).coerceAtMost(6)))
            .coerceAtMost(RETRY_MAX_MS)
        retryAtBySource[sourceId] = System.currentTimeMillis() + wait
    }

    /** A transfer that worked proves the server is there, whatever else said. */
    private fun clearBackOff(sourceId: String) {
        failuresBySource.remove(sourceId)
        retryAtBySource.remove(sourceId)
    }

    // --- Public API ----------------------------------------------------------

    /** Queue [tracks] from the source being browsed; what the library screens call. */
    fun enqueue(tracks: List<Track>, manual: Boolean = true) =
        enqueue(activeSource(), tracks, manual)

    /**
     * Queue [tracks] of [source] for download. [manual] marks a request the
     * user asked for by name, which jumps ahead of anything an offline mode
     * queued; only [applyAutoMode] passes false.
     */
    fun enqueue(source: MusicSource, tracks: List<Track>, manual: Boolean = true) {
        if (tracks.isEmpty()) return
        if (!source.supportsDownloads) return
        // A manual request is also a request to try now, overriding any
        // current back-off.
        if (manual) clearBackOff(source.id)
        scope.launch {
            // One query for the whole set, rather than one per track.
            val already = daoFor(source).downloadedIds().toHashSet()
            lock.withLock {
                tracks.forEach { track ->
                    if (track.id in already) return@forEach
                    if (LocalLibrary.isLocal(track.id)) return@forEach
                    queue.add(QueuedDownload(source.id, track), manual)
                }
                _progress.value = _progress.value.copy(
                    pending = queue.size + (if (inFlight != null) 1 else 0),
                    limitReached = false,
                    error = null,
                )
                // Started while holding the lock, so two concurrent enqueue
                // calls can't both start a worker for the same track.
                startWorkerLocked()
            }
        }
    }

    /**
     * Hold [sourceId]'s downloads while its library syncs; null when no sync
     * is running. See [syncingSourceId].
     */
    fun setSyncing(sourceId: String?) {
        syncingSourceId = sourceId
    }

    /** Hold one source's downloads while its server is unreachable. */
    fun setOffline(sourceId: String, value: Boolean) {
        if (value) backOff(sourceId) else clearBackOff(sourceId)
    }

    /** The user's own pause, from the Downloads page. Survives a restart. */
    fun setUserPaused(value: Boolean) {
        userPaused = value
        scope.launch { settings.setDownloadsPaused(value) }
        if (!value) scope.launch { lock.withLock { startWorkerLocked() } }
    }

    fun cancelAll() {
        scope.launch {
            lock.withLock {
                queue.clear()
                abandoned = null
            }
            worker?.cancel()
            worker = null
            _progress.value = DownloadProgress()
        }
    }

    /**
     * Drop everything waiting for one source: its Delete Downloads, or the
     * source itself going. A transfer already under way is disowned rather
     * than interrupted; see [abandoned].
     */
    suspend fun cancelSource(sourceId: String) {
        lock.withLock {
            queue.removeSource(sourceId)
            val current = inFlight
            if (current?.sourceId == sourceId) abandoned = current
            _progress.value = if (queue.isEmpty() && current == null) {
                DownloadProgress(completed = _progress.value.completed)
            } else {
                _progress.value.copy(pending = queue.size + (if (current != null) 1 else 0))
            }
        }
    }

    suspend fun remove(trackId: String) {
        val source = activeSource()
        val dao = daoFor(source)
        dao.download(trackId)?.let { store.delete(source.id, it.fileName) }
        dao.deleteDownload(trackId)
    }

    suspend fun removeAll(trackIds: List<String>) = trackIds.forEach { remove(it) }

    /** Drop download rows (and their files) for tracks no longer in the library. */
    suspend fun removeOrphanedDownloads() {
        val source = activeSource()
        val dao = daoFor(source)
        dao.orphanedDownloads().forEach { orphan ->
            store.delete(source.id, orphan.fileName)
            dao.deleteDownload(orphan.trackId)
        }
    }

    /** Lyrics captured with a download, for offline display. */
    suspend fun cachedLyrics(trackId: String): String? =
        daoFor(activeSource()).download(trackId)?.lyrics

    /**
     * Re-index audio that is on disk but missing from the database, for
     * every source. Needed because a schema bump drops all tables; files on
     * disk are the durable record.
     */
    suspend fun reindexFromDisk() {
        settings.sources.first()
            .filter { it.supportsDownloads }
            .forEach { source -> runCatching { reindexFromDisk(source) } }
    }

    private suspend fun reindexFromDisk(source: MusicSource) {
        val dao = daoFor(source)
        val known = dao.downloadedIds().toHashSet()
        val missing = store.onDisk(source.id).filter { (id, _) -> id !in known }
        if (missing.isEmpty()) return
        // The file name carries only the track id; the album is resolved from
        // the library, which may still be empty here.
        // [LibraryDao.backfillDownloadAlbums] finishes the job after the sync.
        val albums = dao.tracksByIds(missing.map { it.first })
            .associate { it.id to it.albumId }
        for ((id, format) in missing) {
            val file = store.fileFor(source.id, id, format)
            if (!file.isFile) continue
            dao.upsertDownload(
                DownloadEntity(
                    trackId = id,
                    albumId = albums[id],
                    fileName = file.name,
                    format = format.id,
                    bytes = file.length(),
                    lyrics = null,
                    downloadedAtMs = file.lastModified(),
                ),
            )
        }
    }

    /**
     * Fetch the words for the browsed source's downloads that have none.
     * Lyrics live only in the database, so a schema bump leaves downloaded
     * audio with no words that nothing else would ever refetch. A track with
     * no lyrics available anywhere stores a blank rather than null, so it is
     * asked about once, not on every launch. Capped per run; what's left
     * comes back on the next sync.
     */
    suspend fun refillMissingLyrics() {
        val source = activeSource()
        val client = clientFor(source) ?: return
        val dao = daoFor(source)
        val ids = dao.downloadsMissingLyrics(LYRICS_REFILL_PER_RUN)
        if (ids.isEmpty()) return
        val wantKaraoke = settings.karaokeLyrics.first()
        val tracks = dao.tracksByIds(ids).associateBy { it.id }
        for (id in ids) {
            val track = tracks[id]?.toTrack() ?: continue
            val row = dao.download(id) ?: continue
            val words = runCatching { fetchLyrics(client, track, wantKaraoke) }.getOrNull()
            dao.upsertDownload(row.copy(lyrics = words.orEmpty()))
        }
    }

    /**
     * Queue whatever [source]'s [OfflineMode] implies. Safe to call
     * repeatedly; already-downloaded tracks are skipped.
     */
    suspend fun applyAutoMode(
        source: MusicSource,
        allTracks: List<Track>,
        likedTracks: List<Track>,
        likedAlbumIds: Set<String>,
        playlistTracks: List<Track>,
        likedArtistNames: Set<String>,
    ) {
        // Order is priority: the queue drains front to back and the size
        // limit cuts it off wherever it runs out.
        val favourites = likedTracks +
            playlistTracks +
            allTracks.filter { it.albumId in likedAlbumIds } +
            allTracks.filter {
                it.albumArtistNames().any { name -> name in likedArtistNames }
            }
        val wanted = when (source.offlineMode) {
            OfflineMode.MANUAL -> return
            OfflineMode.FAVORITES -> favourites
            OfflineMode.ALL -> favourites + allTracks
        }
        enqueue(source, wanted.distinctBy { it.id }, manual = false)
    }

    private companion object {
        const val PAUSE_POLL_MS = 1_000L

        /** How many lyric refills one pass will attempt; see refillMissingLyrics. */
        private const val LYRICS_REFILL_PER_RUN = 200

        /** First retry after a failed transfer; doubles up to [RETRY_MAX_MS]. */
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
    }

    // --- Worker --------------------------------------------------------------

    /** Must be called with [lock] held. */
    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                drain()
            } catch (_: CancellationException) {
                // Cancelled by cancelAll(); progress was already reset there.
            } finally {
                inFlight = null
                LightTransferService.stop(lightContext)
            }
        }
    }

    /**
     * Lyrics to store beside a downloaded track. With karaoke on, lrclib is
     * also asked when the server only has plain text, matching what the
     * player does online.
     */
    private suspend fun fetchLyrics(
        client: MusicServer,
        track: Track,
        wantKaraoke: Boolean,
    ): String? {
        val fromServer = runCatching { client.getLyrics(track.id) }.getOrNull()
        val best = if (wantKaraoke && fromServer?.synced != true) {
            runCatching { LyricsRepository.timedFromLrclib(track) }.getOrNull() ?: fromServer
        } else {
            fromServer
        }
        return best?.lines?.joinToString("\n") { line ->
            if (line.timeMs != null) "[${line.timeMs}]${line.text}" else line.text
        }
    }

    /** Why nothing is moving although something is queued; see [eligible]. */
    private fun heldReason(sourceIds: Set<String>): String =
        if (sourceIds.isNotEmpty() && sourceIds.all { waiting(it) }) {
            "Waiting for the server"
        } else {
            "Paused while syncing"
        }

    private suspend fun drain() {
        // Held around actual transfers rather than around the worker, since
        // starting the foreground service for a queue that turns out to be
        // empty can get the process killed before it reaches the foreground.
        var holdingService = false
        val wantKaraoke = settings.karaokeLyrics.first()
        val limit = settings.downloadLimit.first()
        var completed = 0

        while (true) {
            // Checked between tracks so a sync starting mid-file lets that
            // file finish rather than abandoning the bytes already fetched.
            while (userPaused || !heavyDataAllowed()) {
                _progress.value = _progress.value.copy(
                    currentTitle = if (userPaused) "Paused" else "Waiting for Wi-Fi",
                    currentSource = null,
                )
                delay(PAUSE_POLL_MS)
            }

            // Manual lane first, of whichever source can be asked right now;
            // see [DownloadQueue.next].
            val (picked, held) = lock.withLock {
                val next = queue.next(::eligible)
                next to (if (next == null) queue.sourceIds() else emptySet())
            }
            if (picked == null) {
                if (held.isEmpty()) break
                _progress.value = _progress.value.copy(
                    currentTitle = heldReason(held),
                    currentSource = null,
                )
                delay(PAUSE_POLL_MS)
                continue
            }
            val entry = picked.entry
            val next = entry.track

            // The record as it stands now, not as it was when queued: a token
            // renewed or an address changed since then is the one to use.
            val source = settings.sources.first().firstOrNull { it.id == entry.sourceId }
            if (source == null) {
                // Removed since it was queued; the entry goes with it.
                continue
            }

            // Measured across every source, since the budget covers all of
            // them combined, not any one source's usage.
            if (store.usedBytesEverywhere() >= limit) {
                lock.withLock { queue.clear() }
                _progress.value = _progress.value.copy(
                    pending = 0,
                    currentTitle = null,
                    currentSource = null,
                    limitReached = true,
                )
                return
            }

            _progress.value = _progress.value.copy(
                pending = lock.withLock { queue.size } + 1,
                completed = completed,
                currentTitle = next.title,
                currentSource = source.name,
            )

            val client = clientFor(source)
            if (client == null) {
                _progress.value = _progress.value.copy(error = "Not connected")
                lock.withLock { queue.requeueFront(picked) }
                backOff(source.id)
                continue
            }

            val format = source.downloadFormat
            val dao = daoFor(source)

            if (!holdingService) {
                // Without this the process drops to the cached bucket once
                // the user leaves the app, throttling transfers; see
                // LightTransferService for the measurements.
                LightTransferService.start(lightContext, "Syncing your library for offline")
                holdingService = true
            }
            inFlight = entry.key
            val file = try {
                store.download(source.id, client.downloadUrl(next, format), next.id, format)
            } finally {
                inFlight = null
            }

            // Disowned while transferring: its source's downloads were
            // deleted, or the source itself was. Discard the result.
            val disowned = lock.withLock {
                (abandoned == entry.key).also { if (it) abandoned = null }
            }
            if (disowned) {
                file?.delete()
                continue
            }

            if (file != null) {
                // An mp3 streamed down has no VBR index, so every player that
                // opens it has to guess its length; see Mp3VbrIndex.
                if (format == StreamFormat.MP3) {
                    runCatching { Mp3VbrIndex.index(file) }
                        .onSuccess { if (it is Mp3VbrIndex.Outcome.Written) android.util.Log.i("AmpMp3", "indexed ${file.name}: ${it.frames} frames") }
                        .onFailure { android.util.Log.w("AmpMp3", "couldn't index ${file.name}", it) }
                }
                runCatching { App.artwork.prefetch(source.id, client, next.coverArtId) }
                val lyrics = fetchLyrics(client, next, wantKaraoke)
                dao.upsertDownload(
                    DownloadEntity(
                        trackId = next.id,
                        albumId = next.albumId,
                        fileName = file.name,
                        format = format.id,
                        bytes = file.length(),
                        lyrics = lyrics,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
                completed++
                clearBackOff(source.id)
                lock.withLock { queue.complete(entry) }
            } else {
                // A cancelled worker must not put the track back: cancelAll
                // has just emptied the queue it would go into.
                coroutineContext.ensureActive()
                lock.withLock { queue.fail(picked) }
                _progress.value = _progress.value.copy(error = store.lastError)
                backOff(source.id)
                continue
            }
        }

        _progress.value = DownloadProgress(completed = completed)
    }
}
