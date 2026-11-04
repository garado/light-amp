package com.garado.amp

import com.garado.amp.art.ArtworkLoader
import com.garado.amp.data.AppSettings
import com.garado.amp.data.Album
import com.garado.amp.data.AlbumSort
import com.garado.amp.data.Artist
import com.garado.amp.data.ArtworkMode
import com.garado.amp.data.LayoutMode
import com.garado.amp.data.ArtistSort
import com.garado.amp.data.PendingActions
import com.garado.amp.data.Playlist
import com.garado.amp.data.PlaylistSort
import com.garado.amp.data.SongSort
import com.garado.amp.data.sortAlbums
import com.garado.amp.data.sortArtists
import com.garado.amp.data.sortPlaylists
import com.garado.amp.data.sortSongs
import com.garado.amp.data.sortName
import com.garado.amp.data.titleKey
import com.garado.amp.ui.components.indexLetterOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.garado.amp.data.Track
import com.garado.amp.data.Connectivity
import com.garado.amp.data.DataMode
import com.garado.amp.data.DownloadStore
import com.garado.amp.data.Downloader
import com.garado.amp.data.LibraryRepository
import com.garado.amp.data.MusicSource
import com.garado.amp.data.OfflineMode
import com.garado.amp.data.SourceKind
import com.garado.amp.data.TagFilter
import com.garado.amp.data.hasComposer
import com.garado.amp.data.hasGenre
import com.garado.amp.data.SourceLibrary
import com.garado.amp.data.db.LibraryDao
import com.garado.amp.data.db.toTrack
import com.garado.amp.data.MusicServer
import com.garado.amp.data.db.LibraryDatabase
import com.garado.amp.playback.PlaybackController
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.display.LightDisplayColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * App-scoped service locator, initialized once from the boot screen and reused
 * by every screen.
 */
object App {
    lateinit var scope: CoroutineScope
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var library: LibraryRepository
    lateinit var pending: PendingActions
        private set
    lateinit var playback: PlaybackController
        private set
    lateinit var artwork: ArtworkLoader
        private set
    lateinit var downloads: DownloadStore
        private set
    lateinit var downloader: Downloader
        private set

    private val _serverClient = MutableStateFlow<MusicServer?>(null)
    val serverClient: StateFlow<MusicServer?> = _serverClient

    /** The source the library is showing. */
    private val _source = MutableStateFlow(MusicSource.local())
    val source: StateFlow<MusicSource> = _source

    /** One Room database per source, cached by id and kept open once built. */
    private val databases = mutableMapOf<String, LibraryDatabase>()
    private lateinit var lightContext: SealedLightContext
    private val _dao = MutableStateFlow<LibraryDao?>(null)

    /** The active source's DAO. Never read before [boot] has built the first. */
    private fun dao(): LibraryDao = _dao.value ?: error("Library database not ready")

    private fun databaseFor(source: MusicSource): LibraryDatabase = databases.getOrPut(source.id) {
        // The legacy source keeps the original file name so pre-multi-source
        // installs find their existing cache instead of re-syncing.
        val name = if (source.id == AppSettings.LEGACY_SOURCE_ID) {
            DB_NAME
        } else {
            "$DB_NAME-${source.id}"
        }
        lightContext.buildDatabase(LibraryDatabase::class.java, name)
    }

    /**
     * One client per source, kept alive for as long as the source is.
     *
     * Downloads run for every source at once, so a background source needs its
     * own client, shared with the library if it becomes the active one. Keyed
     * on the whole [MusicSource] record so a renewed token or changed address
     * rebuilds it; closed only when the source is removed.
     */
    private val clients = mutableMapOf<String, Pair<MusicSource, MusicServer>>()

    /** The client for [source], or null for the phone's own music. */
    fun clientFor(source: MusicSource): MusicServer? {
        if (source.kind == SourceKind.LOCAL) return null
        synchronized(clients) {
            val held = clients[source.id]
            if (held != null && held.first == source) return held.second
            held?.second?.close()
            val built = source.toClient()
            if (built == null) clients.remove(source.id) else clients[source.id] = source to built
            return built
        }
    }

    private fun forgetClient(sourceId: String) {
        synchronized(clients) { clients.remove(sourceId)?.second?.close() }
    }

    /**
     * Every downloaded track on the phone, source by source.
     *
     * Reads each source's database directly rather than through
     * [LibraryRepository], which only tracks the active source. A download
     * whose track row is missing is left out here, but its bytes still count
     * toward the on-disk storage figure.
     */
    suspend fun downloadsBySource(): List<Pair<String, List<Track>>> =
        settings.sources.first().mapNotNull { source ->
            val dao = databaseFor(source).libraryDao()
            val ids = runCatching { dao.downloadedIds() }.getOrDefault(emptyList())
            if (ids.isEmpty()) return@mapNotNull null
            val tracks = runCatching { dao.tracksByIds(ids).map { it.toTrack() } }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) null else source.name to tracks
        }

    /** Drop a source's cached library and downloaded audio when the user removes it. */
    suspend fun forgetSource(source: MusicSource) {
        // Cancel queued downloads first so nothing below races a transfer
        // landing after.
        downloader.cancelSource(source.id)
        val db = databaseFor(source)
        // Read cover ids before the tables are cleared; see ArtworkLoader.forget.
        val covers = runCatching { db.libraryDao().allCoverArtIds() }.getOrDefault(emptyList())
        db.libraryDao().apply {
            clearTracks()
            clearAlbums()
            clearLikedArtists()
            clearDownloads()
            clearAllTopSongs()
        }
        downloads.deleteSource(source.id)
        artwork.forget(source.id, covers)
        backgroundPlaylistTracks.remove(source.id)
        backgroundPlaylistFailedAt.keys.removeAll { it.startsWith("${source.id}/") }
        forgetClient(source.id)
    }

    /**
     * Cover files the artwork budget must never evict: one per downloaded
     * song's album, across every source. Freed when the download is removed,
     * not by the budget.
     */
    private suspend fun protectedCoverFiles(): Set<String> =
        settings.sources.first().flatMap { source ->
            runCatching { databaseFor(source).libraryDao().downloadedCoverArtIds() }
                .getOrDefault(emptyList())
                .map { artwork.fileNameFor(source.id, it) }
        }.toSet()

    /** Delete every source's downloaded audio. Used by the Offline page's Delete All row. */
    suspend fun deleteAllDownloads() {
        downloader.cancelAll()
        settings.sources.first().forEach { wipeDownloads(it) }
    }

    /** Delete one source's downloaded audio, used by the per-server Delete on the Offline page. */
    suspend fun deleteDownloadsFor(source: MusicSource) {
        downloader.cancelSource(source.id)
        wipeDownloads(source)
    }

    private suspend fun wipeDownloads(source: MusicSource) {
        runCatching { databaseFor(source).libraryDao().clearDownloads() }
        downloads.deleteSource(source.id)
    }

    /**
     * Whether the server looks reachable. Inferred from request success because
     * ConnectivityManager is blocked by the plugin sandbox.
     */
    private val _serverReachable = MutableStateFlow(true)
    val serverReachable: StateFlow<Boolean> = _serverReachable

    fun reportServerReachable(reachable: Boolean) {
        _serverReachable.value = reachable
    }

    /**
     * True when the library should show only downloaded media: Wi-Fi Only is set
     * and the connection is metered, or the server isn't answering at all.
     */
    val offlineOnly: Flow<Boolean> by lazy {
        combine(
            settings.dataMode,
            serverReachable,
            Connectivity.unmetered,
        ) { mode, reachable, unmetered ->
            (mode == DataMode.WIFI_ONLY && !unmetered) || !reachable
        }
    }

    /** The mode as it stands, for gates that must answer synchronously. */
    val dataMode: StateFlow<DataMode> by lazy {
        settings.dataMode.stateIn(scope, SharingStarted.Eagerly, DataMode.WIFI_ONLY)
    }

    /** Whether the server may be spoken to at all: sync, playlists, lyrics. */
    fun metadataAllowed(): Boolean =
        dataMode.value != DataMode.WIFI_ONLY || Connectivity.isUnmetered()

    /** Whether real bytes may move: downloads, cover art. */
    fun heavyDataAllowed(): Boolean =
        Connectivity.isUnmetered() || dataMode.value == DataMode.MAKE_IT_HURT

    private var coreReady = false
    val isReady: Boolean get() = coreReady

    const val SYNC_JOB_KEY = "library-sync"

    fun boot(sealedActivity: SealedLightActivity, context: SealedLightContext) {
        if (!coreReady) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settings = AppSettings(context.dataStore)
            lightContext = context
            Connectivity.bind(context, scope)
            artwork = ArtworkLoader(
                context.filesDir,
                serverClient,
                fetchAllowed = { heavyDataAllowed() },
                artworkOff = { hideArtwork.value },
            ) { _source.value.id }
            runBlocking { settings.migrateDownloadLimit() }
            val first = runBlocking { settings.activeSource.first() }
                ?: MusicSource(AppSettings.LEGACY_SOURCE_ID, SourceKind.SUBSONIC, "Server")
            _source.value = first
            _dao.value = databaseFor(first).libraryDao()
            downloads = DownloadStore(context.filesDir)
            // Audio downloaded before sources existed lives in the old flat
            // folder and belongs to whichever source inherited that server.
            if (first.id == AppSettings.LEGACY_SOURCE_ID) downloads.adoptLegacyFiles(first.id)
            pending = PendingActions(settings)
            library = LibraryRepository(
                daos = _dao,
                serverClient = serverClient,
                libraryId = settings.libraryId,
                offlineOnly = offlineOnly,
                metadataAllowed = ::metadataAllowed,
                pending = pending,
                settings = settings,
                scope = scope,
            )
            downloader = Downloader(
                activeSource = { _source.value },
                daoFor = { databaseFor(it).libraryDao() },
                clientFor = ::clientFor,
                store = downloads,
                settings = settings,
                scope = scope,
                lightContext = context,
                heavyDataAllowed = ::heavyDataAllowed,
            )
            playback = PlaybackController(
                settings, serverClient, ::dao, downloads, scope,
                metadataAllowed = ::metadataAllowed,
            )

            library.onSyncSucceeded = {
                reportServerReachable(true)
                scope.launch {
                    runCatching { dao().backfillDownloadAlbums() }
                    runCatching { downloader.refillMissingLyrics() }
                    runCatching { downloader.removeOrphanedDownloads() }
                    topUpDownloads()
                }
            }
            scope.launch { library.primePlaylists() }
            scope.launch {
                pending.refreshCount()
                serverReachable.collect { reachable ->
                    // Reachability is inferred from the browsed source's sync,
                    // so only that source's downloads pause and resume here.
                    downloader.setOffline(source.value.id, !reachable)
                    if (reachable) library.flushPending()
                }
            }
            // Also flush whenever the client and the connection are both ready.
            // serverReachable starts true and only flips on a failed sync, so it
            // never fires the flush on its own after boot; this covers Wi-Fi
            // returning after a period offline.
            scope.launch {
                combine(serverClient, Connectivity.changed) { client, (connected, _) ->
                    client != null && connected
                }
                    .distinctUntilChanged()
                    .collect { ready -> if (ready) runCatching { library.flushPending() } }
            }
            scope.launch { downloader.setUserPaused(settings.downloadsPaused.first()) }
            // Colour follows the artwork switch: with no covers shown there is
            // nothing for the off-SDK colour spike (LightDisplayColor) to do.
            scope.launch {
                settings.artwork.collect { LightDisplayColor.enabled = it != ArtworkMode.NONE }
            }
            // A refused login isn't the server being gone; marking it
            // unreachable would needlessly narrow the library to downloads.
            library.onSyncFailed = { authFailure -> if (!authFailure) reportServerReachable(false) }

            // The client is built inside swapSource rather than by a collector
            // of its own, so the client, database, and source switch together
            // in one defined order instead of racing on separate collectors.
            scope.launch {
                settings.activeSource.collect { active -> swapSource(active) }
            }
            // Remembered on the source so the Sources page can list libraries
            // instantly and offline.
            scope.launch {
                library.syncState.collect { state ->
                    if (state.syncing || state.lastSyncedMs == 0L) return@collect
                    val current = source.value
                    if (!current.supportsLibraries) return@collect
                    val folders = runCatching { library.musicFolders() }.getOrNull() ?: return@collect
                    // musicFolders() returns emptyList() both for "no libraries"
                    // and for "couldn't ask" (network failure, client not up
                    // yet). Treat empty as unknown so a transient failure
                    // doesn't wipe libraries already discovered.
                    if (folders.isEmpty()) return@collect
                    settings.setSourceLibraries(
                        current.id,
                        folders.map { SourceLibrary(it.id, it.name) },
                    )
                }
            }
            // Re-run when any source's offline mode changes, so switching one to
            // e.g. Favorites starts fetching without waiting for the next sync.
            scope.launch {
                settings.sources
                    .map { list -> list.map { it.id to it.offlineMode } }
                    .distinctUntilChanged()
                    .collect { topUpDownloads() }
            }
            // Rebuild the download index from disk before anything reads it,
            // since a schema bump wipes the table.
            scope.launch { downloader.reindexFromDisk() }
            // Apply the artwork budget at launch. Tracks are pointed at their
            // album's cover first so the protected set is one file per
            // downloaded album, not one per song; see ArtworkLoader.trimToBudget.
            scope.launch {
                settings.sources.first().forEach { source ->
                    runCatching { databaseFor(source).libraryDao().collapseTrackCovers() }
                        .onSuccess { if (it > 0) android.util.Log.i("AmpArt", "${source.name}: $it tracks now share their album's cover") }
                }
                artwork.trimToBudget(protectedCoverFiles())
            }
            // TEMPORARY, REMOVE BEFORE COMMUNITY REVIEW. One-time repair of
            // mp3s downloaded before Amp wrote their index. Delete this block
            // together with DownloadStore.indexMp3s and
            // AppSettings.mp3IndexRepairNeeded / markMp3IndexRepaired.
            scope.launch(Dispatchers.IO) {
                if (!settings.mp3IndexRepairNeeded()) return@launch
                val n = downloads.indexMp3s()
                settings.markMp3IndexRepaired()
                android.util.Log.i("AmpMp3", "one-time repair: indexed $n downloaded mp3(s) that had none")
            }
            // A source's downloads pause during its own sync, since both hit
            // the same server and the sync is many sequential requests.
            scope.launch {
                library.syncState.collect {
                    downloader.setSyncing(if (it.syncing) source.value.id else null)
                }
            }
            // Restore the queue from the last session. Waits for the cache
            // (the saved queue is only ids) and for the player to be attached.
            scope.launch {
                val tracks = library.fullTracks.filter { it.isNotEmpty() }.first()
                playback.bound.first { it }
                // Also wait for the client, when this source has one: an audio
                // item's stream URL is fixed when the queue is built, so
                // restoring before the client exists leaves every item with an
                // empty URL. A local source never has a client and must not be
                // waited for.
                val active = settings.activeSource.filter { it != null }.first()
                if (active?.kind != SourceKind.LOCAL) {
                    serverClient.filter { it != null }.first()
                }
                playback.restoreState(tracks)
            }
            scope.launch {
                library.fullTracks
                    .filter { it.isNotEmpty() }
                    .distinctUntilChangedBy { it.size }
                    .collect { topUpDownloads() }
            }
            coreReady = true
            // Periodic background library refresh (min 15 min on WorkManager).
            LightWork.enqueuePeriodic(context, SYNC_JOB_KEY, 30.minutes)
        }
        playback.bind(DefaultLightAudio(sealedActivity))
        scope.launch { settings.replayGain.collect { playback.replayGain.value = it } }
    }

    fun shutdown() {
        playback.release()
    }

    /**
     * A sorted library view plus its A-Z buckets, computed off the main thread
     * on [scope] (Dispatchers.Default) rather than per-composable.
     */
    data class SortedView<T>(val items: List<T> = emptyList(), val letters: List<Char> = emptyList())

    /** Sort settings, kept warm from boot so [sortedSongs] and its neighbours start settled. */
    val songSort: StateFlow<SongSort> by lazy {
        settings.songSort.stateIn(scope, SharingStarted.Eagerly, SongSort.TITLE)
    }
    val songSortReversed: StateFlow<Boolean> by lazy {
        settings.songSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val albumSort: StateFlow<AlbumSort> by lazy {
        settings.albumSort.stateIn(scope, SharingStarted.Eagerly, AlbumSort.TITLE)
    }
    val albumSortReversed: StateFlow<Boolean> by lazy {
        settings.albumSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val artistSort: StateFlow<ArtistSort> by lazy {
        settings.artistSort.stateIn(scope, SharingStarted.Eagerly, ArtistSort.NAME)
    }
    val artistSortReversed: StateFlow<Boolean> by lazy {
        settings.artistSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    /** Whether each tab is narrowed to what you have liked. One flag per tab; see AppSettings. */
    val likedAlbumsOnly: StateFlow<Boolean> by lazy {
        settings.likedAlbumsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val likedSongsOnly: StateFlow<Boolean> by lazy {
        settings.likedSongsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val likedArtistsOnly: StateFlow<Boolean> by lazy {
        settings.likedArtistsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** Which tag each list is narrowed to. Blank means no filter. */
    val songsTagFilter: StateFlow<TagFilter> by lazy {
        combine(settings.songsGenre, settings.songsComposer) { g, c -> TagFilter(g, c) }
            .stateIn(scope, SharingStarted.Eagerly, TagFilter())
    }
    val albumsTagFilter: StateFlow<TagFilter> by lazy {
        combine(settings.albumsGenre, settings.albumsComposer) { g, c -> TagFilter(g, c) }
            .stateIn(scope, SharingStarted.Eagerly, TagFilter())
    }

    /**
     * The albums a tag filter leaves standing, or null when nothing is filtered.
     * Derived from tracks, since an album has no composer of its own.
     */
    val albumsMatchingTags: StateFlow<Set<String>?> by lazy {
        combine(library.tracks, albumsTagFilter) { tracks, filter ->
            if (filter.isEmpty) return@combine null
            tracks.asSequence()
                .filter { filter.genre.isEmpty() || it.hasGenre(filter.genre) }
                .filter { filter.composer.isEmpty() || it.hasComposer(filter.composer) }
                .mapNotNull { it.albumId }
                .toSet()
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    val sortedSongs: StateFlow<SortedView<Track>> by lazy {
        combine(library.tracks, songSort, songSortReversed, likedSongsOnly, songsTagFilter) {
                list, sort, rev, liked, tags ->
            val narrowed = list.asSequence()
                .filter { !liked || it.liked }
                .filter { tags.genre.isEmpty() || it.hasGenre(tags.genre) }
                .filter { tags.composer.isEmpty() || it.hasComposer(tags.composer) }
                .toList()
            val sorted = sortSongs(narrowed, sort, rev)
            SortedView(
                sorted,
                when (sort) {
                    SongSort.TITLE -> sorted.map { indexLetterOf(titleKey(it.title)) }
                    SongSort.ARTIST -> sorted.map { indexLetterOf(sortName(it.artist)) }
                    else -> emptyList()
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    /** Whether covers are switched off. */
    val hideArtwork: StateFlow<Boolean> by lazy {
        settings.artwork.map { it == ArtworkMode.NONE }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** The artwork setting; see [AppSettings.artwork]. */
    val artworkMode: StateFlow<ArtworkMode> by lazy {
        settings.artwork.stateIn(scope, SharingStarted.Eagerly, ArtworkMode.SMALL)
    }

    /**
     * Read by both album lists; see [AppSettings.albumGrid]. False whenever
     * artwork is off, since the grid is nothing but artwork.
     */
    val albumGrid: StateFlow<Boolean> by lazy {
        combine(settings.albumGrid, hideArtwork) { grid, hidden -> grid && !hidden }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** The same, for an artist's own page; see [AppSettings.artistAlbumGrid]. */
    val artistAlbumGrid: StateFlow<Boolean> by lazy {
        combine(settings.artistAlbumGrid, hideArtwork) { grid, hidden -> grid && !hidden }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** Which shape the bottom bar takes; see [AppSettings.layoutMode]. */
    val layoutMode: StateFlow<LayoutMode> by lazy {
        settings.layoutMode.stateIn(scope, SharingStarted.Eagerly, LayoutMode.STANDARD)
    }

    /**
     * Artists' own pictures off, sleeves untouched; see
     * [AppSettings.hideArtistImages]. Also true whenever artwork is off wholesale.
     */
    val hideArtistImages: StateFlow<Boolean> by lazy {
        combine(settings.hideArtistImages, hideArtwork) { off, all -> off || all }
            .stateIn(scope, SharingStarted.Eagerly, true)
    }

    /** Rows against the screen's edge, download marks gone; see [AppSettings.hideDownloadIcons]. */
    val hideDownloadIcons: StateFlow<Boolean> by lazy {
        settings.hideDownloadIcons.stateIn(scope, SharingStarted.Eagerly, false)
    }

    val sortedAlbums: StateFlow<SortedView<Album>> by lazy {
        // Fold the three order settings into one flow first: combine takes at
        // most five flows, and the reshuffle nonce would make six.
        val order = combine(albumSort, albumSortReversed, settings.shuffleNonce, ::Triple)
        combine(library.albums, order, likedAlbumsOnly, albumsMatchingTags) {
                list, (sort, rev, nonce), liked, tagged ->
            val narrowed = list.filter { (!liked || it.liked) && (tagged == null || it.id in tagged) }
            val sorted = sortAlbums(narrowed, sort, rev, nonce)
            SortedView(
                sorted,
                when (sort) {
                    AlbumSort.TITLE -> sorted.map { indexLetterOf(titleKey(it.title)) }
                    AlbumSort.ARTIST -> sorted.map { indexLetterOf(sortName(it.artist)) }
                    else -> emptyList()
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    val sortedArtists: StateFlow<SortedView<Artist>> by lazy {
        combine(library.artists, artistSort, artistSortReversed, likedArtistsOnly) { list, sort, rev, liked ->
            val sorted = sortArtists(list.filter { !liked || it.liked }, sort, rev)
            SortedView(
                sorted,
                if (sort == ArtistSort.NAME) sorted.map { indexLetterOf(sortName(it.name)) } else emptyList(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    val sortedPlaylists: StateFlow<SortedView<Playlist>> by lazy {
        combine(library.playlists, settings.playlistSort, settings.playlistSortReversed) { list, sort, rev ->
            val sorted = sortPlaylists(list, sort, rev)
            SortedView(
                sorted,
                if (sort == PlaylistSort.NAME) sorted.map { indexLetterOf(titleKey(it.name)) } else emptyList(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    /** Playlist id -> folder name, for the active source only. */
    val playlistFolders: StateFlow<Map<String, String>> by lazy {
        combine(settings.playlistFolders, source) { all, src ->
            val prefix = "${src.id}:"
            all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    fun setPlaylistFolder(playlistId: String, folder: String?) {
        scope.launch { settings.setPlaylistFolder(source.value.id, playlistId, folder) }
    }

    /** Starred playlist ids, for the active source only. */
    val starredPlaylists: StateFlow<Set<String>> by lazy {
        combine(settings.starredPlaylists, source) { all, src ->
            val prefix = "${src.id}:"
            all.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    fun setPlaylistStarred(playlistId: String, starred: Boolean) {
        scope.launch { settings.setPlaylistStarred(source.value.id, playlistId, starred) }
    }

    /**
     * Change over to [active]: the client, the database, then the source.
     *
     * The source is what everything else keys on, so it changes last, once
     * the client and database it implies are already the new one's. Done in
     * one place rather than split across collectors of the same flow, so the
     * three never run out of order and briefly point at mismatched servers.
     */
    private suspend fun swapSource(active: MusicSource?) {
        val next = active ?: return
        if (next.id == _source.value.id) {
            // Same source: unchanged, or changed in place by a renewed token or
            // a new address. `_serverClient.value == null` also covers boot,
            // where this is the first emission and the client has not been
            // built yet.
            if (next != _source.value || _serverClient.value == null) {
                _serverClient.value = clientFor(next)
            }
            _source.value = next
            return
        }
        // The playing queue's stream URLs are signed for the source being
        // left and don't survive the change.
        playback.stop()
        // A sync for the source being left has nothing to report, and its
        // failure would land on the new one instead.
        library.cancelSync()
        downloader.setSyncing(null)
        // Popular songs, playlists, search index, and artist ids are keyed by
        // name rather than by source; clear before the switch so nothing
        // recomposing on the new source catches stale data.
        library.forgetDerived()
        // Liked switches and genre/composer filters narrow this library's
        // lists; a filter chosen on one server means nothing on the next.
        settings.clearLibraryFilters()
        // The previous client stays open in the pool; its source may still
        // have downloads to fetch.
        _serverClient.value = clientFor(next)
        _dao.value = databaseFor(next).libraryDao()
        _source.value = next
    }

    /**
     * Ensures only one top-up pass runs at a time; a burst of trigger calls
     * collapses into one extra pass, not one each.
     */
    private val topUpRunning = Mutex()
    private val topUpRequested = AtomicBoolean(false)

    /** How long a pass waits after being requested, to settle a burst of triggers into one. */
    private const val TOP_UP_SETTLE_MS = 3_000L

    /** How long a playlist that failed to answer is left alone before being asked again. */
    private const val PLAYLIST_RETRY_MS = 30 * 60 * 1000L

    /** When a background source's playlist last failed to answer, keyed "source/playlist". */
    private val backgroundPlaylistFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Playlist membership for sources not being browsed, by source id, for the
     * life of the process. A source in the background has no repository, so
     * its membership is fetched from its own server here. Each playlist's
     * track list is null until fetched; a failed fetch stays null so only that
     * playlist is retried on the next pass. Dropped with the source; see
     * [forgetSource].
     */
    private val backgroundPlaylistTracks = ConcurrentHashMap<String, MutableMap<String, List<String>?>>()

    /** Queue whatever each source's offline mode wants that isn't downloaded yet. */
    private suspend fun topUpDownloads() {
        if (!coreReady) return
        topUpRequested.set(true)
        while (true) {
            // If another caller is already running the pass, the flag tells
            // it to go round again when it finishes.
            if (!topUpRunning.tryLock()) return
            try {
                while (topUpRequested.get()) {
                    delay(TOP_UP_SETTLE_MS)
                    topUpRequested.set(false)
                    topUpPass()
                }
            } finally {
                topUpRunning.unlock()
            }
            // A request that landed between the last check and the unlock
            // needs this loop to pick it up.
            if (!topUpRequested.get()) return
        }
    }

    private suspend fun topUpPass() {
        for (source in settings.sources.first()) {
            if (!source.supportsDownloads || source.offlineMode == OfflineMode.MANUAL) continue
            runCatching { topUpDownloads(source) }
                .onFailure { android.util.Log.w("AmpDl", "top-up failed for ${source.name}", it) }
        }
    }

    private suspend fun topUpDownloads(source: MusicSource) {
        val startedMs = System.currentTimeMillis()
        // Reads this source's own tables directly, not the repository's lists,
        // which only follow the browsed source.
        val dao = databaseFor(source).libraryDao()
        // Only download from libraries kept visible on the Sources page's
        // "Shown on" setting.
        val hidden = source.hiddenLibraryIds.filterNotNull().toSet()
        val albums = dao.allAlbumsSnapshot()
            .filter { hidden.isEmpty() || it.libraryId == null || it.libraryId !in hidden }
        val albumIds = albums.mapTo(HashSet()) { it.id }
        val tracks = dao.allTracksSnapshot()
            .asSequence()
            .filter { hidden.isEmpty() || it.albumId == null || it.albumId in albumIds }
            .map { it.toTrack() }
            .toList()
        if (tracks.isEmpty()) return
        // No server sends playlist membership with the playlist list, so each
        // playlist's songs are fetched once, here.
        val playlistTrackIds = playlistTrackIdsFor(source)
        downloader.applyAutoMode(
            source = source,
            allTracks = tracks,
            likedTracks = tracks.filter { it.liked },
            likedAlbumIds = albums.filter { it.liked }.mapTo(HashSet()) { it.id },
            playlistTracks = tracks.filter { it.id in playlistTrackIds },
            // Starred on the server, not derived from the artists list, which
            // narrows with the offline view.
            likedArtistNames = dao.likedArtistNames().toSet(),
        )
        android.util.Log.i(
            "AmpDl",
            "top-up ${source.name} (${source.offlineMode}): ${tracks.size} rows, " +
                "${playlistTrackIds.size} in playlists, ${System.currentTimeMillis() - startedMs} ms",
        )
    }

    /** Every track id in any of [source]'s playlists, as far as they can be known. */
    private suspend fun playlistTrackIdsFor(source: MusicSource): Set<String> {
        if (source.id == _source.value.id) {
            // The browsed source uses the repository's own cache, shared with
            // the Playlists tab.
            if (library.playlists.value.isEmpty()) runCatching { library.refreshPlaylists() }
            runCatching { library.primePlaylistTrackIds(library.playlists.value.map { it.id }) }
            return library.playlistTrackIds.value.values.flatten().toHashSet()
        }
        val client = clientFor(source) ?: return emptySet()
        val known = backgroundPlaylistTracks[source.id] ?: run {
            if (!metadataAllowed()) return emptySet()
            val playlists = runCatching { client.getPlaylists(source.libraryId) }.getOrNull()
                ?: return emptySet()
            HashMap<String, List<String>?>()
                .also { map -> playlists.forEach { map[it.id] = null } }
                .also { backgroundPlaylistTracks[source.id] = it }
        }
        // Fetch only playlists that haven't answered yet, concurrently rather
        // than one after another.
        val now = System.currentTimeMillis()
        val missing = known.filterValues { it == null }.keys.filter { id ->
            now - (backgroundPlaylistFailedAt["${source.id}/$id"] ?: 0L) > PLAYLIST_RETRY_MS
        }
        if (missing.isNotEmpty() && metadataAllowed()) {
            val gate = Semaphore(PLAYLIST_PRIME_CONCURRENCY)
            val fetched = coroutineScope {
                missing.map { id ->
                    async {
                        gate.withPermit {
                            id to runCatching { client.getPlaylistTracks(id) }.getOrNull()?.map { it.id }
                        }
                    }
                }.awaitAll()
            }
            // A failed fetch stays null and dated, so the next pass retries
            // only that playlist.
            fetched.forEach { (id, ids) ->
                if (ids != null) known[id] = ids else backgroundPlaylistFailedAt["${source.id}/$id"] = now
            }
        }
        return known.values.flatMapTo(HashSet()) { it.orEmpty() }
    }

    private const val PLAYLIST_PRIME_CONCURRENCY = 6

    private const val DB_NAME = "amp-library.db"
}
