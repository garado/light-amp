package com.garado.amp.data

import com.garado.amp.data.db.AlbumEntity
import com.garado.amp.data.db.DownloadFile
import com.garado.amp.data.db.LibraryDao
import com.garado.amp.data.db.LikedArtistEntity
import com.garado.amp.data.db.TopSongEntity
import com.garado.amp.data.db.toAlbum
import com.garado.amp.data.db.toEntity
import com.garado.amp.data.db.toTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class SyncState(
    val syncing: Boolean = false,
    val phase: String = "",
    val error: String? = null,
    val lastSyncedMs: Long = 0L,
)

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/**
 * The library: reads come from the Room cache (instant on launch) and are
 * kept fresh by [sync], which pulls the server catalogue incrementally.
 * Only albums that are new or whose song count changed are re-fetched.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(
    /**
     * The active source's DAO, as a flow rather than an instance so switching
     * sources re-subscribes the observed lists to the new database.
     */
    private val daos: StateFlow<LibraryDao?>,
    private val serverClient: StateFlow<MusicServer?>,
    // The persisted selection as a raw flow (not a cached StateFlow) so
    // [sync] reads the actual stored value, since a stateIn's initial
    // `null` would race the DataStore load on boot.
    private val libraryId: Flow<String?>,
    /** True when only downloaded media should be visible (offline / Wi-Fi only). */
    offlineOnly: Flow<Boolean>,
    /** Whether the server may be spoken to at all; see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean = { true },
    /** Where likes and ratings go when the server can't be told about them. */
    private val pending: PendingActions,
    /** For the handful of things cached in preferences rather than in a table. */
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The database in use right now, for the imperative half of this class. */
    private val dao: LibraryDao get() = daos.value ?: error("Library database not ready")

    /**
     * [daos] as a flow of tables, for the observed half. Every list clears
     * the moment the database underneath changes, before the new one has
     * answered, so nothing downstream keeps serving the previous source's
     * rows while Room loads the new one.
     */
    private fun <T> observing(empty: T, select: (LibraryDao) -> Flow<T>): Flow<T> =
        daos.flatMapLatest { current ->
            current?.let { dao -> select(dao).onStart { emit(empty) } } ?: emptyFlow()
        }

    /**
     * The downloads table, read once and shared by every derived value (ids,
     * total bytes, per-album placements, newest-first order, files on disk),
     * since Room re-runs every observer of a table on each write to it.
     * Lyrics stay out; see [LibraryDao.observeDownloads].
     */
    val downloadFiles: StateFlow<List<DownloadFile>> =
        observing(emptyList()) { it.observeDownloads() }
            .onEach { downloadsLoaded.value = true }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether [downloadFiles] has heard from the database yet. Needed
     * because [downloadFiles] seeds empty, and "no downloads" and "not
     * asked yet" would otherwise be indistinguishable. See PlaybackController.
     */
    val downloadsLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Track ids with a completed download. */
    val downloadedTrackIds: StateFlow<Set<String>> =
        downloadFiles.map { rows -> rows.mapTo(HashSet()) { it.trackId } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val offline: StateFlow<Boolean> =
        offlineOnly.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Sweep every library as it is opened, not only when a sync
        // succeeds, so an orphaned track isn't stuck behind an expired login
        // that never gets to sync again.
        scope.launch {
            daos.filterNotNull().collect { d ->
                val orphans = runCatching { d.orphanedTrackCount() }.getOrDefault(0)
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans orphaned track(s) on open")
                    runCatching { d.deleteOrphanedTracks() }
                }
            }
        }
    }

    /**
     * Every cached track, whichever library it belongs to. [allTracks]
     * narrows this with a filter rather than a second scan.
     */
    private val trackRows: StateFlow<List<Track>> =
        observing(emptyList()) { it.observeTracks() }.map { rows -> rows.map { row -> row.toTrack() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Which of the server's libraries is chosen, or null for all of them. */
    private val selectedLibrary: StateFlow<String?> =
        libraryId.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The chosen library's albums, filtered out of the cache rather than
     * fetched, so switching works with no server at all. A row with no
     * library recorded shows under every one, since it predates the column.
     */
    /** The album table as stored, library tags and all. */
    private val albumRows: StateFlow<List<AlbumEntity>> =
        observing(emptyList()) { it.observeAlbums() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val allAlbums: StateFlow<List<Album>> =
        combine(albumRows, selectedLibrary) { rows, library ->
            rows.asSequence()
                .filter { library == null || it.libraryId == null || it.libraryId == library }
                .map { it.toAlbum() }
                .toList()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * And its songs, which are the ones on those albums. A track carries no
     * library of its own; it belongs to whichever library its album does. A
     * track with no album is kept, since the sync prunes those separately.
     */
    private val allTracks: StateFlow<List<Track>> =
        combine(trackRows, allAlbums) { rows, albums ->
            val visible = albums.mapTo(HashSet()) { it.id }
            rows.filter { it.albumId == null || it.albumId in visible }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The whole cached library, ignoring the offline view filter. The
     * download top-up triggers off this, never off [tracks], since [tracks]
     * narrows to what's downloaded while offline.
     */
    val fullTracks: StateFlow<List<Track>> get() = allTracks
    val fullAlbums: StateFlow<List<Album>> get() = allAlbums

    /**
     * The library as the UI should see it. When offline, this narrows to
     * what's actually playable, so browsing, search, and queue-building all
     * follow automatically instead of each screen having to check.
     */
    val tracks: StateFlow<List<Track>> =
        combine(allTracks, downloadedTrackIds, offline) { list, downloaded, offlineOnlyNow ->
            if (offlineOnlyNow) list.filter { it.id in downloaded } else list
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> =
        combine(allAlbums, tracks, offline) { list, visible, offlineOnlyNow ->
            if (!offlineOnlyNow) {
                list
            } else {
                val ids = visible.mapNotNull { it.albumId }.toSet()
                list.filter { it.id in ids }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // How many tracks each album has, memoized on the list instance so a
    // completed download doesn't re-group the whole library.
    private var albumSizesFor: List<Track>? = null
    private var albumSizes: Map<String, Int> = emptyMap()

    private fun albumSizes(list: List<Track>): Map<String, Int> {
        if (albumSizesFor === list) return albumSizes
        albumSizes = list.mapNotNull { it.albumId }.groupingBy { it }.eachCount()
        albumSizesFor = list
        return albumSizes
    }

    /**
     * Albums whose every cached track is downloaded; drives the download
     * badge. Counts downloaded rows per album against the album's size, so
     * the per-download cost tracks the number of downloads, not library size.
     */
    val downloadedAlbumIds: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) return@combine emptySet()
            val sizes = albumSizes(list)
            val byId = trackIndex(list)
            val have = HashMap<String, Int>()
            for (row in rows) {
                val album = byId[row.trackId]?.albumId ?: continue
                have[album] = (have[album] ?: 0) + 1
            }
            have.entries.mapNotNullTo(mutableSetOf()) { (album, n) ->
                album.takeIf { sizes[album] == n }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // How many tracks each artist has; same memoization as albumSizes.
    private var artistSizesFor: List<Track>? = null
    private var artistSizes: Map<String, Int> = emptyMap()

    private fun artistSizes(list: List<Track>): Map<String, Int> {
        if (artistSizesFor === list) return artistSizes
        artistSizes = list.flatMap { it.albumArtistNames() }.groupingBy { it }.eachCount()
        artistSizesFor = list
        return artistSizes
    }

    /** Artists whose every cached track is downloaded; drives the artist list's download badge. */
    val downloadedArtistNames: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) return@combine emptySet()
            val sizes = artistSizes(list)
            val byId = trackIndex(list)
            val have = HashMap<String, Int>()
            for (row in rows) {
                val credits = byId[row.trackId]?.albumArtistNames() ?: continue
                for (artist in credits) {
                    have[artist] = (have[artist] ?: 0) + 1
                }
            }
            have.entries.mapNotNullTo(mutableSetOf()) { (artist, n) ->
                artist.takeIf { sizes[artist] == n }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Track lookup, memoized on the library list instance so a completed
    // download doesn't rebuild the whole map.
    private var trackIndexFor: List<Track>? = null
    private var trackIndex: Map<String, Track> = emptyMap()

    private fun trackIndex(list: List<Track>): Map<String, Track> {
        if (trackIndexFor === list) return trackIndex
        trackIndex = list.associateBy { it.id }
        trackIndexFor = list
        return trackIndex
    }

    /** Downloaded tracks, newest first, for the Downloads page. Spans every library, not just the chosen one. */
    val downloads: Flow<List<Track>> =
        combine(downloadFiles, trackRows) { rows, library ->
            val byId = trackIndex(library)
            rows.sortedByDescending { it.downloadedAtMs }.mapNotNull { byId[it.trackId] }
        }

    /**
     * The tag values this library actually uses, derived from the cache
     * rather than fetched, since there's no server endpoint for composers.
     * Empty means the server doesn't fill that field.
     */
    val genres: StateFlow<List<String>> =
        tracksFlowOf { it.genre }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val composers: StateFlow<List<String>> =
        tracksFlowOf { it.composer }.stateIn(scope, SharingStarted.Lazily, emptyList())

    private fun tracksFlowOf(select: (Track) -> String): Flow<List<String>> =
        tracks.map { list ->
            list.asSequence()
                .flatMap { splitTagValues(select(it)) }
                .distinct()
                .sortedBy { nameKey(it) }
                .toList()
        }


    /** Everything tagged with [genre], as tracks. */
    fun tracksWithGenre(genre: String): List<Track> =
        tracks.value.filter { track -> splitTagValues(track.genre).any { it.equals(genre, true) } }

    fun tracksWithComposer(composer: String): List<Track> =
        tracks.value.filter { track -> splitTagValues(track.composer).any { it.equals(composer, true) } }

    /**
     * How many songs carry each value of one tag; what the tag lists sort by
     * when not in name order. Keyed exactly as [genres] and [composers] list them.
     */
    fun tagCounts(byComposer: Boolean): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        tracks.value.forEach { track ->
            splitTagValues(if (byComposer) track.composer else track.genre).forEach { value ->
                counts[value] = (counts[value] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * The artists starred on the server. Deliberately not derived from
     * [artists], which narrows with the offline view and chosen library;
     * whether you starred someone isn't a fact about what's on screen.
     */
    val likedArtistNames: StateFlow<Set<String>> =
        observing(emptyList()) { it.observeLikedArtists() }.map { names -> names.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Each artist's picture, keyed by name. The app's artists come from
     * track tags and carry only a name; this joins them against the
     * server's own artist records, filled by [primeArtistImages].
     */
    private val _artistImages = MutableStateFlow<Map<String, String>>(emptyMap())

    val artists: StateFlow<List<Artist>> =
        combine(tracks, likedArtistNames, _artistImages) { list, liked, images ->
            deriveArtists(list, liked, images)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Fetch the server's artist index for its pictures, once per source,
     * on demand rather than on every sync. Failure leaves the map as it was.
     */
    suspend fun primeArtistImages() {
        if (_artistImages.value.isNotEmpty()) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val refs = runCatching { client.getArtistIndex(libraryId.first()) }.getOrNull() ?: return
        // Doubles as the id index starring needs, from the same call.
        if (artistIds == null) artistIds = refs.associate { it.name to it.id }
        _artistImages.value = refs
            .mapNotNull { ref -> ref.imageId?.takeIf { it.isNotBlank() }?.let { ref.name to it } }
            .toMap()
    }

    // State, not cold flows, so a screen collecting one of these doesn't
    // flash "No liked albums yet" while the real value is still arriving.
    val likedArtists: StateFlow<List<Artist>> =
        artists.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val likedTracks: StateFlow<List<Track>> =
        tracks.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val likedAlbums: StateFlow<List<Album>> =
        albums.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    // Serializes sync + switch so they never write the cache concurrently;
    // the job handle lets a library switch cancel an in-flight sync.
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    // name -> server artist id, resolved once and reused for starring.
    private var artistIds: Map<String, String>? = null

    // artist name -> top songs, so revisiting an artist page doesn't re-hit
    // the server.
    private val topSongs = ConcurrentHashMap<String, List<Track>>()

    // Playlists are fetched live from the server (not cached in Room).
    /**
     * Membership cache backing [downloadedPlaylistIds]. `getPlaylists`
     * never returns a playlist's songs, only its metadata, so nothing to
     * compare against exists until [playlistTracks] or
     * [primePlaylistTrackIds] fills it in.
     */
    private val _playlistTrackIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /**
     * Which songs each playlist holds, for the playlists primed so far; see
     * [primePlaylistTrackIds]. [Playlist.trackIds] is always empty for a
     * server playlist, since no server sends membership with its list.
     */
    val playlistTrackIds: StateFlow<Map<String, List<String>>> = _playlistTrackIds

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /**
     * The playlists as the UI should see them, narrowed offline like the
     * other lists. A playlist whose membership isn't known stays visible,
     * since empty [Playlist.trackIds] usually means "not asked yet", not
     * "empty".
     */
    val playlists: StateFlow<List<Playlist>> =
        combine(_playlists, _playlistTrackIds, downloadedTrackIds, offline) {
                list, membership, downloaded, offlineOnlyNow ->
            if (!offlineOnlyNow) {
                list
            } else {
                list.filter { playlist ->
                    val ids = membership[playlist.id] ?: playlist.trackIds
                    ids.isEmpty() || ids.any { it in downloaded }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Re-read the playlists, keeping the last known set when the server can't be reached. */
    /** Whether playlists live on this phone rather than on a server; see [LocalPlaylists]. */
    private suspend fun playlistsAreLocal(): Boolean =
        settings.activeSource.first()?.kind == SourceKind.LOCAL

    suspend fun refreshPlaylists() {
        val sourceId = settings.activeSource.first()?.id ?: return
        if (playlistsAreLocal()) {
            // Read straight off the disk every time: the files are the
            // truth, and another app may have changed them.
            val local = LocalPlaylists.list()
            _playlists.value = local
            val liveIds = local.map { it.id }.toSet()
            settings.prunePlaylistFolders(sourceId, liveIds)
            settings.pruneStarredPlaylists(sourceId, liveIds)
            return
        }
        if (!metadataAllowed()) return
        val fetched = runCatching { serverClient.value?.getPlaylists(libraryId.first()) }.getOrNull()
        if (fetched != null) {
            _playlists.value = fetched
            settings.setCachedPlaylists(sourceId, json.encodeToString(fetched))
            val liveIds = fetched.map { it.id }.toSet()
            settings.prunePlaylistFolders(sourceId, liveIds)
            settings.pruneStarredPlaylists(sourceId, liveIds)
            return
        }
        if (_playlists.value.isEmpty()) _playlists.value = loadCachedPlaylists()
    }

    /** Restores the last known playlists so a cold start offline isn't blank. */
    suspend fun primePlaylists() {
        if (_playlists.value.isEmpty()) _playlists.value = loadCachedPlaylists()
    }

    private suspend fun loadCachedPlaylists(): List<Playlist> {
        val sourceId = settings.activeSource.first()?.id ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Playlist>>(settings.cachedPlaylists(sourceId))
        }.getOrDefault(emptyList())
    }

    /**
     * A playlist's songs, from the server, or rebuilt from the cached
     * membership against the local library when it can't be asked. The
     * offline rebuild keeps order and drops tracks the library no longer holds.
     */
    suspend fun playlistTracks(id: String): List<Track> {
        val tracks = fetchPlaylistTracks(id)
        // An empty answer is not cached: it could mean a genuinely empty
        // playlist or a failed fetch, and caching it would satisfy
        // primePlaylistTrackIds' "already known" guard for the rest of the
        // session even on a transient failure.
        if (tracks.isNotEmpty()) {
            _playlistTrackIds.update { it + (id to tracks.map { track -> track.id }) }
        }
        val downloaded = downloadedTrackIds.value
        return if (offline.value) tracks.filter { it.id in downloaded } else tracks
    }

    private suspend fun fetchPlaylistTracks(id: String): List<Track> {
        if (playlistsAreLocal()) return getTracksByIds(LocalPlaylists.trackIds(id))
        if (metadataAllowed()) {
            serverClient.value?.let { client ->
                runCatching { client.getPlaylistTracks(id) }.getOrNull()?.let { return it }
            }
        }
        val ids = (_playlists.value.firstOrNull { it.id == id } ?: return emptyList()).trackIds
        return getTracksByIds(ids)
    }

    /**
     * Primes currently running, by playlist id, so two callers racing for the
     * same playlist share one fetch instead of each starting their own.
     */
    private val playlistPrimes = mutableMapOf<String, Deferred<Unit>>()
    private val playlistPrimeLock = Mutex()

    /**
     * Fills the membership cache for [id] if it isn't already known or on
     * its way, and otherwise waits for the fetch already running. Talks to
     * the server directly rather than through [playlistTracks] and skips
     * the cache write on failure, so a transient failure isn't frozen in as
     * "confirmed empty".
     */
    suspend fun primePlaylistTrackIds(id: String) {
        if (id in _playlistTrackIds.value) return
        val prime = playlistPrimeLock.withLock {
            // Re-read inside the lock: the fetch this caller would have
            // started may have finished while it waited for the lock.
            if (id in _playlistTrackIds.value) return
            val running = playlistPrimes[id]
            if (running != null && running.isActive) {
                running
            } else {
                // In the repository's scope, not the caller's, so a screen
                // going away mid-fetch doesn't cancel it for other waiters.
                scope.async { primePlaylistOnce(id) }.also { playlistPrimes[id] = it }
            }
        }
        // Deliberately unguarded: cancelling this caller should not cancel
        // the shared fetch for the others still waiting on it.
        prime.await()
    }

    private suspend fun primePlaylistOnce(id: String) {
        if (playlistsAreLocal() || serverClient.value == null) {
            playlistTracks(id)
            return
        }
        if (!metadataAllowed()) return
        val tracks = runCatching { serverClient.value?.getPlaylistTracks(id) }.getOrNull() ?: return
        _playlistTrackIds.update { it + (id to tracks.map { track -> track.id }) }
    }

    /**
     * The same, for a whole list; what the playlists tab actually needs.
     * Runs concurrently rather than one request at a time, gated the same
     * as the library sync.
     */
    suspend fun primePlaylistTrackIds(ids: List<String>) = coroutineScope {
        val gate = Semaphore(SYNC_CONCURRENCY)
        ids.filterNot { it in _playlistTrackIds.value }
            .map { id -> launch { gate.withPermit { primePlaylistTrackIds(id) } } }
            .joinAll()
    }

    /** Playlists whose every known track is downloaded; drives the download badge. */
    val downloadedPlaylistIds: StateFlow<Set<String>> =
        combine(_playlistTrackIds, downloadedTrackIds) { membership, downloaded ->
            membership.entries.mapNotNullTo(mutableSetOf()) { (playlistId, ids) ->
                playlistId.takeIf { ids.isNotEmpty() && ids.all { trackId -> trackId in downloaded } }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun createPlaylist(name: String) = createPlaylist(name, emptyList())

    /**
     * [trackIds] are the playlist's opening contents, not a hint; see
     * [MusicServer.createPlaylist]. Callers must not add them again.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>) {
        if (playlistsAreLocal()) {
            LocalPlaylists.create(name.trim(), trackIds)
        } else {
            runCatching { serverClient.value?.createPlaylist(name.trim(), trackIds) }
        }
        refreshPlaylists()
    }

    suspend fun renamePlaylist(id: String, name: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.rename(id, name.trim())
        } else {
            runCatching { serverClient.value?.renamePlaylist(id, name.trim()) }
        }
        refreshPlaylists()
    }

    suspend fun deletePlaylist(id: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.delete(id)
        } else {
            runCatching { serverClient.value?.deletePlaylist(id) }
        }
        refreshPlaylists()
    }

    /**
     * Append one track. Sequential by contract: the server's update appends, so
     * concurrent calls would race the order.
     */
    suspend fun addToPlaylist(id: String, trackId: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.add(id, trackId)
        } else {
            runCatching { serverClient.value?.addToPlaylist(id, trackId) }
        }
    }

    suspend fun removeFromPlaylistAt(id: String, index: Int) {
        if (playlistsAreLocal()) {
            LocalPlaylists.removeAt(id, index)
        } else {
            runCatching { serverClient.value?.removeFromPlaylistAt(id, index) }
        }
    }

    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        if (playlistsAreLocal()) {
            LocalPlaylists.reorder(id, orderedSongIds)
        } else {
            runCatching { serverClient.value?.reorderPlaylist(id, orderedSongIds) }
        }
    }

    // --- Sync ----------------------------------------------------------------

    /** The server's libraries (music folders); empty if unsupported or on error. */
    suspend fun musicFolders(): List<MusicFolder> =
        runCatching { serverClient.value?.getMusicFolders().orEmpty() }.getOrDefault(emptyList())

    /** Full incremental sync of the currently selected library. Safe to repeat. */
    suspend fun sync() = syncScoped(libraryId.first())

    /**
     * Switch to [musicFolderId]: cancel any in-flight sync and re-sync just
     * that library, serialized through [syncMutex]. Nothing is thrown away;
     * every library stays cached side by side, tagged with where it came
     * from, and choosing one filters the cache; see [allAlbums]. The sync
     * that follows is a refresh, not what makes the switch work: if it
     * fails, the chosen library just shows whatever was last cached for it.
     */
    suspend fun switchLibrary(musicFolderId: String?) {
        syncJob?.cancelAndJoin()
        val job = scope.launch {
            syncMutex.withLock {
                runSync(musicFolderId)
            }
        }
        syncJob = job
        job.join()
    }

    private suspend fun syncScoped(musicFolderId: String?) {
        syncMutex.withLock { runSync(musicFolderId) }
    }

    /**
     * Drop whatever sync is in flight, for a source we have left, so a
     * failure there doesn't get reported against the new source.
     */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Put back a `syncing` claim that [runSync] turned out to have no work
     * for. Only clears the flag; an error set by an earlier run stands.
     */
    private fun releaseSyncClaim() {
        _syncState.value = _syncState.value.copy(syncing = false, phase = "")
    }

    /** The actual scoped sync (null = all libraries); callers serialize via [syncMutex]. */
    /**
     * Whether a failure was the server turning us away rather than not
     * being there at all. An unreachable server narrows the library to
     * downloads; a refused login means the library is fine and the
     * credentials are stale.
     */
    private fun isAuthFailure(e: Exception): Boolean {
        val code = Regex("""\b(\d{3})\b""").find(e.message.orEmpty())?.value?.toIntOrNull()
        return code == 401 || code == 403
    }

    /** A sync failure in words the person holding the phone can act on, rather than the server's raw message. */
    private fun syncErrorMessage(e: Exception): String {
        val code = Regex("""\b(\d{3})\b""").find(e.message.orEmpty())?.value?.toIntOrNull()
        return when {
            code == null -> e.message?.takeIf { it.length < 60 } ?: "Couldn't reach the server"
            // 52x are Cloudflare's: it answered, the server behind it didn't.
            code in 520..529 -> "Your server isn't responding ($code)"
            code in 500..599 -> "The server had an error ($code)"
            code == 401 || code == 403 -> "The server refused the login"
            code == 404 -> "That library isn't on the server any more"
            else -> "The server said $code"
        }
    }

    /**
     * Try the server's other advertised addresses and keep one that
     * answers. Plex typically links over the LAN address, which stops
     * working once the phone leaves that network. Returns true when the
     * address changed; saving the source rebuilds the client.
     */
    private suspend fun repointPlex(): Boolean {
        val source = settings.activeSource.first() ?: return false
        if (source.kind != SourceKind.PLEX || source.connections.isEmpty()) return false
        val ordered = (source.connections - source.baseUrl) + source.baseUrl
        val found = PlexAccount.firstReachable(ordered, source.token) ?: return false
        if (found == source.baseUrl) return false
        android.util.Log.i("AmpSync", "plex moved: ${source.baseUrl} -> $found")
        settings.saveSource(source.copy(baseUrl = found))
        return true
    }

    private suspend fun runSync(musicFolderId: String?, allowRepoint: Boolean = true) {
        val source = settings.activeSource.first()
        // Each early return below clears the claim its caller staked, since
        // callers set `syncing` before launching to turn away a duplicate
        // sync while this one is still awaiting its first suspend.
        // runLocalScan is exempt; it owns the flag across its whole run.
        val dao = daos.value ?: return releaseSyncClaim()
        // The phone's own music has no server to ask; it is read off disk.
        if (source?.kind == SourceKind.LOCAL) {
            runLocalScan(dao)
            return
        }
        if (!metadataAllowed()) return releaseSyncClaim()
        // A build that changed how a track is read refills the cache once,
        // since the incremental filter below can only see server-side
        // changes; see MusicSource.parserGeneration.
        val refillForParser =
            source != null && source.parserGeneration != TRACK_PARSER_GENERATION
        val client = serverClient.value ?: return releaseSyncClaim()
        // `dao` and `client` are pinned for this run rather than re-read via
        // the properties, which follow the active source: a switch mid-sync
        // must not move these writes onto a different source's database.
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Connecting")
        try {
            // Rows with no library recorded show in every library (see
            // allAlbums), so an untagged album leaks into libraries it was
            // never in. Fetches each folder's album list (cheap, no songs)
            // and stamps every row it names. Only runs while untagged rows
            // exist, which after the first pass is never.
            val untagged = dao.untaggedAlbumCount()
            if (untagged > 0) {
                val folders = client.getMusicFolders()
                if (folders.size >= 2) {
                    _syncState.value = _syncState.value.copy(phase = "Sorting libraries")
                    var stamped = 0
                    var named = 0
                    for (folder in folders) {
                        val ids = client.getAllAlbums(folder.id).map { it.id }
                        named += ids.size
                        ids.chunked(500).forEach { chunk -> stamped += dao.tagAlbums(folder.id, chunk) }
                    }
                    // Anything still untagged after every folder has spoken is
                    // an album the server has nowhere; delete it. Same
                    // empty-answer rule as the main prune below.
                    val dead = if (named > 0) dao.untaggedAlbumIds() else emptyList()
                    if (dead.isNotEmpty()) {
                        val deadSet = dead.toSet()
                        val sample = dao.allAlbumsSnapshot()
                            .filter { it.id in deadSet }
                            .take(5)
                            .joinToString { "${it.artist} – ${it.title} [${it.id}]" }
                        android.util.Log.w("AmpSync", "dropping albums the server has nowhere, e.g. $sample")
                        dead.forEach { dao.deleteTracksForAlbum(it) }
                        dao.deleteAlbums(dead)
                    }
                    android.util.Log.i(
                        "AmpSync",
                        "retagged $stamped album rows across ${folders.size} libraries; " +
                            "$untagged had no library, ${dead.size} of them exist nowhere and were dropped",
                    )
                }
            }
            // "All libraries" is fanned out per folder and merged: an
            // unscoped getAlbumList2 returns only the server's default
            // library.
            val folderIds: List<String?> = if (musicFolderId != null) {
                listOf(musicFolderId)
            } else {
                client.getMusicFolders().map { it.id }.ifEmpty { listOf(null) }
            }

            val starred = folderIds.map { client.getStarred(it) }.mergeStarred()

            _syncState.value = _syncState.value.copy(phase = "Fetching albums")
            // Which folder each album came from is kept, written onto the
            // row so switching library later is a filter over the cache
            // rather than a fetch; see AlbumEntity.libraryId. An album in
            // two folders keeps the first.
            val fetched = folderIds
                .flatMap { folder ->
                    val list = client.getAllAlbums(folder)
                    android.util.Log.i(
                        "AmpSync",
                        "folder=$folder returned ${list.size} albums" +
                            list.take(3).joinToString(prefix = " e.g. ") { it.title },
                    )
                    list.map { it to folder }
                }
                .distinctBy { (album, _) -> album.id }
            val serverAlbums = fetched.map { (album, _) ->
                album.copy(liked = album.id in starred.albumIds)
            }
            val libraryOf = fetched.associate { (album, folder) -> album.id to folder }
            val serverAlbumById = serverAlbums.associateBy { it.id }

            val cached = dao.allAlbumsSnapshot().associateBy { it.id }
            android.util.Log.i(
                "AmpSync",
                "scope=$musicFolderId cached rows by library tag: " +
                    cached.values.groupBy { it.libraryId }.mapValues { it.value.size },
            )

            dao.upsertAlbums(serverAlbums.map { it.toEntity(libraryOf[it.id]) })

            // Prune albums (and their tracks) that no longer exist on the
            // server, but never on an empty result: a fetch that succeeds
            // and returns nothing is indistinguishable from a stale scope
            // or a transient empty response, and pruning on that would
            // delete the whole cached library with no way to recover it.
            if (serverAlbums.isEmpty()) {
                android.util.Log.w("AmpSync", "returned no albums; keeping ${cached.size} cached")
            } else {
                // Only within the library that was just fetched; pruning
                // against one folder's answer must not delete albums
                // belonging to other folders. Rows with no library recorded
                // are left alone, since no fetch can speak for them.
                val inScope = if (musicFolderId == null) {
                    cached
                } else {
                    cached.filterValues { it.libraryId == musicFolderId }
                }
                val staleAlbumIds = inScope.keys - serverAlbumById.keys
                if (staleAlbumIds.isNotEmpty()) {
                    staleAlbumIds.forEach { dao.deleteTracksForAlbum(it) }
                    dao.deleteAlbums(staleAlbumIds.toList())
                }
                // And the tracks that belong to no album here at all. The
                // prune above only reaches albums this library knows, so a
                // song written in by another source's interrupted sync is
                // never considered otherwise. Guarded by the same
                // empty-result rule as the album prune above.
                val orphans = dao.orphanedTrackCount()
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans track(s) belonging to no album here")
                    dao.deleteOrphanedTracks()
                }
            }

            // Only new or changed albums need their songs re-fetched, but
            // "changed" must include "its songs aren't actually here":
            // albums are written before their songs, so a sync interrupted
            // in between leaves complete-looking album rows with missing
            // tracks. Checking the real per-album count makes it self-heal.
            val cachedTrackCounts = dao.trackCountsByAlbum().associate { it.albumId to it.tracks }
            val reasons = mutableMapOf<String, Int>()
            var unchanged = 0
            val toFetch = if (refillForParser) serverAlbums else serverAlbums.filter { album ->
                val prev = cached[album.id]
                val cachedCount = cachedTrackCounts[album.id] ?: 0
                val why = when {
                    prev == null -> "new album"
                    cachedCount == 0 -> "no songs cached"
                    // A track count that held steady doesn't mean nothing
                    // changed: a delete-and-replace of every file can leave
                    // the same count. Checked only when both sides have a
                    // real timestamp, so it can't misfire on rows written
                    // before this column existed.
                    album.updatedMs != 0L && prev.updatedMs != 0L && album.updatedMs != prev.updatedMs ->
                        "album changed on the server"
                    // Plex omits leafCount from the album listing, so a
                    // missing count must not be read as zero: that treats
                    // every Plex album as changed on every sync. Fall back
                    // to updatedAt instead; a server that sends neither
                    // leaves both sides at 0, which keeps the cache.
                    album.songCount == 0 ->
                        if (album.updatedMs != prev.updatedMs) {
                            "album changed on the server"
                        } else {
                            unchanged++
                            null
                        }
                    prev.songCount != album.songCount -> "server's count changed"
                    cachedCount != album.songCount -> "cache short of the count"
                    else -> null
                }
                if (why != null) reasons[why] = (reasons[why] ?: 0) + 1
                why != null
            }
            android.util.Log.i(
                "AmpSync",
                if (refillForParser) {
                    "parser refill: fetching songs for all ${serverAlbums.size} albums"
                } else {
                    "fetching songs for ${toFetch.size} of ${serverAlbums.size} albums $reasons" +
                        if (unchanged > 0) "; $unchanged uncounted and untouched, cache kept" else ""
                },
            )

            // Counted in albums, since that is what is walking: one request
            // per album for its songs.
            _syncState.value = _syncState.value.copy(phase = "Albums 0/${toFetch.size}")
            fetchAndStoreSongs(dao, client, toFetch, starred.songIds, libraryOf)

            // Reconcile track likes across the whole library (cheap two-query pass).
            dao.replaceTrackLikes(starred.songIds.toList())

            dao.replaceLikedArtists(starred.artistNames.map { LikedArtistEntity(it) })

            // Only mark the parser generation after a full run completes, so
            // an interrupted refill is retried rather than marked done.
            if (refillForParser && source != null) {
                settings.setParserGeneration(source.id, TRACK_PARSER_GENERATION)
            }

            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = System.currentTimeMillis(),
            )
            onSyncSucceeded?.invoke()
        } catch (e: CancellationException) {
            // A library switch cancelled this sync; don't record it as an error.
            _syncState.value = _syncState.value.copy(syncing = false)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AmpSync", "sync failed: ${e::class.simpleName}: ${e.message}", e)
            // The address may simply have moved; see repointPlex. Only once,
            // so a genuinely dead server can't loop.
            if (allowRepoint && repointPlex()) {
                runSync(musicFolderId, allowRepoint = false)
                return
            }
            _syncState.value = _syncState.value.copy(
                syncing = false,
                error = syncErrorMessage(e),
            )
            onSyncFailed?.invoke(isAuthFailure(e))
        }
    }

    /**
     * Rebuild the library from the files on this phone. Wholesale rather
     * than incremental, since the filesystem is the index. Likes, ratings,
     * and play counts have nowhere to live on a local source.
     */
    private suspend fun runLocalScan(dao: LibraryDao) {
        _syncState.value = _syncState.value.copy(
            syncing = true,
            error = null,
            phase = "Reading this phone",
        )
        try {
            val access = LocalLibrary.access()
            if (access != LocalLibrary.Access.GRANTED) {
                _syncState.value = SyncState(
                    syncing = false,
                    // Blocked is not "not yet allowed": the permission
                    // prompt cannot change a LightOS policy.
                    error = when (access) {
                        LocalLibrary.Access.BLOCKED_BY_LIGHTOS -> "LightOS is blocking this tool's music access"
                        else -> "Allow music access to read this phone"
                    },
                )
                return
            }
            val scan = LocalLibrary.scan { count ->
                _syncState.value = _syncState.value.copy(phase = "$count tracks")
            }
            dao.clearTracks()
            dao.clearAlbums()
            dao.upsertAlbums(scan.albums.map { it.toEntity() })
            dao.upsertTracks(scan.tracks.map { it.toEntity() })
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = System.currentTimeMillis(),
            )
            onSyncSucceeded?.invoke()
        } catch (e: CancellationException) {
            _syncState.value = _syncState.value.copy(syncing = false)
            throw e
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(
                syncing = false,
                error = e.message ?: "Couldn't read the music folder",
            )
        }
    }

    /**
     * Set by [com.garado.amp.App] to learn whether the server is reachable
     * and to top up automatic downloads once fresh library data has landed.
     */
    var onSyncSucceeded: (() -> Unit)? = null
    /**
     * Called when a sync gives up. The flag is true when the server
     * answered and refused us; see [isAuthFailure]. Not the same as unreachable.
     */
    var onSyncFailed: ((authFailure: Boolean) -> Unit)? = null

    private suspend fun fetchAndStoreSongs(
        /** The sync's own database, never the property, which follows the active source. */
        dao: LibraryDao,
        client: MusicServer,
        albums: List<Album>,
        starredSongIds: Set<String>,
        /** Which library each album came from; see the count write-back below. */
        libraryOf: Map<String, String?>,
    ) = coroutineScope {
        val gate = Semaphore(SYNC_CONCURRENCY)
        val done = AtomicInteger(0)
        // Fetch concurrently, but write in batches: one transaction per
        // album means every observer's whole-table query re-runs once per
        // album across a full sync. Network stays parallel; the database
        // sees a handful of swaps.
        albums.chunked(SYNC_WRITE_BATCH).forEach { batch ->
            val fetched = batch.map { album ->
                async {
                    gate.withPermit {
                        val songs = try {
                            client.getAlbumTracks(album.id)
                        } catch (_: Exception) {
                            emptyList()
                        }.map { it.copy(liked = it.id in starredSongIds) }
                        val n = done.incrementAndGet()
                        if (n % 10 == 0 || n == albums.size) {
                            _syncState.value =
                                _syncState.value.copy(phase = "Albums $n/${albums.size}")
                        }
                        songs
                    }
                }
            }.awaitAll()
            // A switch away cancels this sync; a batch already fetched must
            // not be written on the way out.
            ensureActive()
            dao.replaceAlbumTracks(
                batch.map { it.id },
                fetched.flatten().map { it.toEntity() },
            )
            // Write back the count we actually got for any album whose
            // server didn't provide one, so the next sync has something to
            // compare against.
            val counted = batch.mapIndexedNotNull { index, album ->
                val songs = fetched.getOrNull(index)?.size ?: 0
                album.copy(songCount = songs).takeIf { album.songCount == 0 && songs > 0 }
            }
            // With its library tag, or the row would show in every library; see allAlbums.
            if (counted.isNotEmpty()) dao.upsertAlbums(counted.map { it.toEntity(libraryOf[it.id]) })
        }
    }

    fun syncInBackground() {
        // Claimed before the launch: runSync only says "syncing" once it
        // holds the mutex and has awaited the active source, so a second
        // call arriving before that isn't turned away otherwise. The caller
        // is a LaunchedEffect on the boot screen, re-asked on every
        // recomposition.
        if (_syncState.value.syncing) return
        // A floor between runs, since the claim above only stops overlap,
        // not repetition. Sync Now is deliberately exempt; see
        // scanAndSyncInBackground.
        if (System.currentTimeMillis() - _syncState.value.lastSyncedMs < SYNC_MIN_INTERVAL_MS) return
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Connecting")
        syncJob = scope.launch { sync() }
    }

    /**
     * Ask the server to look for new files, then sync what it finds. The
     * wait for the scan is bounded; giving up and syncing anyway picks up
     * whatever the scan found by then, with the rest at the next sync. Only
     * the explicit Sync Now goes through here.
     */
    /**
     * Set when the server would not be told to scan; see
     * [scanAndSyncInBackground]. Read by the Sync Now row so a refusal is
     * visible rather than mistaken for a library with nothing new in it.
     */
    @Volatile
    var scanRefused: Boolean = false
        private set

    fun scanAndSyncInBackground() {
        // Claimed before anything is awaited, so a second tap before the
        // scan request returns doesn't start a second scan-and-sync.
        if (_syncState.value.syncing) return
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Scanning server")
        val previous = syncJob
        syncJob = scope.launch {
            previous?.cancelAndJoin()
            val client = serverClient.value
            val folder = libraryId.first()
            val asked = client != null &&
                runCatching { client.startServerScan(folder) }.getOrDefault(false)
            // Said plainly rather than left looking like an empty scan. On
            // Plex this is what a library someone shared with you does.
            scanRefused = client != null && !asked
            if (asked) {
                var waited = 0L
                while (waited < SCAN_WAIT_MS) {
                    delay(SCAN_POLL_MS)
                    waited += SCAN_POLL_MS
                    val busy = runCatching { client.serverScanning(folder) }.getOrDefault(false)
                    if (!busy) break
                }
            }
            sync()
        }
    }

    // --- Reads ---------------------------------------------------------------

    /** An album's tracks, from cache when present, otherwise fetched and cached. */
    suspend fun getAlbumTracks(albumId: String): List<Track> {
        val cached = dao.tracksForAlbum(albumId).map { it.toTrack() }
        if (cached.isNotEmpty()) return narrowedToDownloads(cached).sortedWith(TRACK_ORDER)
        val client = serverClient.value ?: return emptyList()
        val fresh = client.getAlbumTracks(albumId)
        dao.upsertTracks(fresh.map { it.toEntity() })
        return narrowedToDownloads(fresh).sortedWith(TRACK_ORDER)
    }

    /**
     * What of these will actually play, offline; all of them otherwise.
     * A record's page must agree with [tracks]'s offline narrowing, or it
     * shows songs the library can't actually play.
     */
    private fun narrowedToDownloads(tracks: List<Track>): List<Track> {
        if (!offline.value) return tracks
        val downloaded = downloadedTrackIds.value
        return tracks.filter { it.id in downloaded }
    }

    /**
     * A queue of whole albums in random order: each album's tracks stay in
     * their own order, so a record is still heard in sequence. Capped at
     * [limit] albums.
     */
    suspend fun shuffledAlbumQueue(limit: Int = SHUFFLE_ALBUM_LIMIT): List<Track> =
        albumQueue(albums.value.shuffled().map { it.id }, limit)

    /** The same, in the order given; what Play on the albums list means. */
    suspend fun albumQueue(albumIds: List<String>, limit: Int = SHUFFLE_ALBUM_LIMIT): List<Track> =
        albumIds.take(limit).flatMap { id ->
            dao.tracksForAlbum(id).map { it.toTrack() }.sortedWith(TRACK_ORDER)
        }

    /** Tracks for the given ids, preserving id order (used by playlists/queue restore). */
    suspend fun getTracksByIds(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = dao.tracksByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toTrack() }
    }

    /** All tracks for an artist, from cache, ordered by album then track. */
    fun tracksForArtist(name: String): List<Track> =
        tracks.value.filter { name in it.albumArtistNames() }
            .sortedWith(compareBy({ it.album.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))

    /**
     * An artist's most popular songs, in popularity order. Kept in the
     * source's database once fetched, and refreshed in the background once
     * older than [TOP_SONGS_TTL_MS]. Only ids are stored, matched back onto
     * the library's own rows. An empty answer is never stored, since it
     * can't be told apart from a transient failure.
     */
    suspend fun topSongsForArtist(name: String): List<Track> {
        topSongs[name]?.let { return it }
        val stored = runCatching { dao.topSongs(name) }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) {
            val byId = tracks.value.associateBy { it.id }
            val held = stored.mapNotNull { byId[it.trackId] }
            val stale = System.currentTimeMillis() - stored.first().fetchedAtMs > TOP_SONGS_TTL_MS
            if (stale && !offline.value && serverClient.value != null) {
                scope.launch { refreshTopSongs(name) }
            }
            if (held.isNotEmpty()) {
                topSongs[name] = held
                return held
            }
            // Stored, but none of it is downloaded, so nothing to play
            // offline. Online, ask again rather than show nothing.
            if (offline.value) return emptyList()
        }
        return refreshTopSongs(name)
    }

    /** Ask the server, store what it says, answer with the library's own rows. */
    private suspend fun refreshTopSongs(name: String): List<Track> {
        val client = serverClient.value ?: return emptyList()
        val answered = runCatching { client.getTopSongs(name) }.getOrDefault(emptyList())
        if (answered.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        runCatching {
            dao.replaceTopSongs(name, answered.mapIndexed { i, t -> TopSongEntity(name, i, t.id, now) })
        }
        val byId = tracks.value.associateBy { it.id }
        // A song the library doesn't hold is still shown this once, playing
        // through the server like any other.
        val result = answered.map { byId[it.id] ?: it }
        topSongs[name] = result
        return result
    }

    /**
     * A radio seeded by [seed]: the song itself, then what the server
     * thinks follows from it. Server rows are swapped for cached ones so
     * likes, play counts, and downloads agree with the UI. Empty when the
     * server has nothing, rather than shuffling the library and calling it radio.
     */
    suspend fun radioFrom(seed: Track): List<Track> {
        val client = serverClient.value ?: return emptyList()
        val byId = tracks.value.associateBy { it.id }
        val answered = client.getSimilarSongs(seed.id, RADIO_LENGTH)
        val similar = answered
            .filter { it.id != seed.id }
            .distinctBy { it.id }
            .map { byId[it.id] ?: it }
        android.util.Log.i("AmpRadio", "radio from ${seed.id}: server ${answered.size}, usable ${similar.size}")
        return if (similar.isEmpty()) emptyList() else listOf(seed) + similar
    }

    /** An artist's albums as a discography: oldest release first, undated last. */
    fun albumsForArtist(name: String): List<Album> {
        val albumIds = tracks.value
            .filter { name in it.albumArtistNames() }
            .mapNotNull { it.albumId }
            .toSet()
        return albums.value.filter { it.id in albumIds }.sortedWith(RELEASE_ORDER)
    }

    // --- Likes ---------------------------------------------------------------

    /**
     * The server, when it may be spoken to and the mode allows the bytes.
     * Null in both cases, so every caller that already queues its action
     * when there's no server also queues it when there's no allowance.
     */
    private fun reachableClient(): MusicServer? =
        if (metadataAllowed()) serverClient.value else null

    /**
     * Like or unlike a song. The local row is the truth the moment it's
     * tapped, and stays that way even if the server can't be told.
     */
    suspend fun setTrackLiked(track: Track, liked: Boolean) {
        dao.setTrackLiked(track.id, liked)
        val client = reachableClient()
        val kind = if (liked) PendingAction.Kind.STAR_SONG else PendingAction.Kind.UNSTAR_SONG
        if (client == null) {
            pending.add(PendingAction(kind, track.id))
            return
        }
        try {
            if (liked) client.starSong(track.id) else client.unstarSong(track.id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, track.id))
        }
    }

    /**
     * Like an artist. Stored locally by name, since Artist rows are derived
     * from track tags, and mirrored to the server when the name resolves to
     * a real server-side artist.
     */
    suspend fun setArtistLiked(name: String, liked: Boolean) {
        if (liked) dao.likeArtists(listOf(LikedArtistEntity(name))) else dao.unlikeArtist(name)
        val kind = if (liked) PendingAction.Kind.STAR_ARTIST else PendingAction.Kind.UNSTAR_ARTIST
        val client = reachableClient()
        if (client == null) {
            pending.add(PendingAction(kind, name))
            return
        }
        try {
            val id = artistIdFor(name, client) ?: return
            if (liked) client.starArtist(id) else client.unstarArtist(id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, name))
        }
    }

    /** Server-side id for a display name, from a lazily built index. */
    private suspend fun artistIdFor(name: String, client: MusicServer): String? {
        val ids = artistIds ?: client.getArtistIndex()
            .associate { it.name to it.id }
            .also { artistIds = it }
        return ids[name]
    }

    suspend fun setAlbumLiked(album: Album, liked: Boolean) {
        dao.setAlbumLiked(album.id, liked)
        val kind = if (liked) PendingAction.Kind.STAR_ALBUM else PendingAction.Kind.UNSTAR_ALBUM
        val client = reachableClient()
        if (client == null) {
            pending.add(PendingAction(kind, album.id))
            return
        }
        try {
            if (liked) client.starAlbum(album.id) else client.unstarAlbum(album.id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, album.id))
        }
    }

    // --- Search --------------------------------------------------------------

    /**
     * Folded search text for every track, memoized so typing "olafur" to
     * reach "Ólafur Arnalds" doesn't re-fold the whole library per keystroke.
     */
    private var haystackFor: List<Track>? = null
    private var haystack: List<String> = emptyList()

    private fun haystack(list: List<Track>): List<String> {
        if (haystackFor === list) return haystack
        haystack = list.map { titleKey(it.title + "\n" + it.artist + "\n" + it.album) }
        haystackFor = list
        return haystack
    }

    fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val q = titleKey(query)
        if (q.isBlank()) return SearchResults()
        val trackList = tracks.value
        val hay = haystack(trackList)
        val matchingTracks = trackList
            .filterIndexed { i, _ -> hay[i].contains(q) }
            .take(SEARCH_LIMIT)
        // Albums and artists are much smaller than tracks, so they fold in
        // place rather than carrying a cache of their own.
        val matchingAlbums = albums.value.filter {
            titleKey(it.title).contains(q) || titleKey(it.artist).contains(q)
        }.take(SEARCH_LIMIT)
        val matchingArtists = artists.value.filter {
            titleKey(it.name).contains(q)
        }.take(SEARCH_LIMIT)
        return SearchResults(matchingArtists, matchingAlbums, matchingTracks)
    }

    /** Rate a song or album, 0 to clear. Applied locally, queued when the server can't be reached. */
    suspend fun setRating(id: String, rating: Int, isAlbum: Boolean): Boolean {
        if (isAlbum) dao.setAlbumRating(id, rating) else dao.setTrackRating(id, rating)
        val kind = if (isAlbum) PendingAction.Kind.RATE_ALBUM else PendingAction.Kind.RATE_SONG
        val client = reachableClient()
        if (client == null || !client.setRating(id, rating)) {
            pending.add(PendingAction(kind, id, value = rating))
        }
        return true
    }

    /**
     * Note a play against the cached row, so "Recently Played" is right
     * immediately rather than waiting for the next sync to confirm it.
     */
    suspend fun markPlayed(trackId: String) {
        dao.markPlayed(trackId, System.currentTimeMillis())
    }

    /**
     * Send everything that happened while the server couldn't be told.
     * Goes through [reachableClient], so a queue built up in Wi-Fi Only
     * isn't emptied over cellular the moment this is called.
     */
    suspend fun flushPending(): Int {
        val client = reachableClient() ?: return 0
        return pending.flush(client) { name -> runCatching { artistIdFor(name, client) }.getOrNull() }
    }

    suspend fun clearCache() {
        dao.clearTracks()
        dao.clearAlbums()
        dao.clearAllTopSongs()
        forgetDerived()
    }

    /**
     * Throw away everything held in memory about the library, leaving the
     * rows alone. None of this is keyed by source, so it would otherwise
     * answer for the wrong server after a switch. Separate from
     * [clearCache], which also deletes rows.
     */
    fun forgetDerived() {
        _playlists.value = emptyList()
        _playlistTrackIds.value = emptyMap()
        topSongs.clear()
        artistIds = null
        _artistImages.value = emptyMap()
        haystackFor = null
        haystack = emptyList()
    }

    private fun deriveArtists(
        list: List<Track>,
        liked: Set<String>,
        images: Map<String, String>,
    ): List<Artist> =
        list.flatMap { track -> track.albumArtistNames().map { it to track } }
            .groupBy({ it.first }, { it.second })
            .map { (name, ts) ->
                Artist(
                    name = name,
                    imageId = images[name],
                    albumCount = ts.mapNotNull { it.albumId }.distinct().size,
                    trackCount = ts.size,
                    playCount = ts.sumOf { it.playCount },
                    lastPlayedMs = ts.maxOfOrNull { it.lastPlayedMs } ?: 0L,
                    liked = name in liked,
                )
            }
            // Key once per artist, not once per comparison; see LibrarySort.
            .map { sortName(it.name) to it }
            .sortedBy { it.first }
            .map { it.second }

    /** Union of the per-folder starred results (one call per library when merging). */
    private fun List<Starred>.mergeStarred(): Starred =
        if (size == 1) first() else Starred(
            songIds = flatMapTo(mutableSetOf()) { it.songIds },
            albumIds = flatMapTo(mutableSetOf()) { it.albumIds },
            artistNames = flatMapTo(mutableSetOf()) { it.artistNames },
        )

    companion object {
        /** How long Sync Now will wait on a server scan before syncing anyway. */
        private const val SCAN_WAIT_MS = 30_000L
        private const val SCAN_POLL_MS = 1_500L

        /**
         * Bump when a change to how a track is parsed means the cached rows
         * are wrong, not when the server's own data changes (the ordinary
         * incremental sync already catches that).
         */
        const val TRACK_PARSER_GENERATION = 2

        private const val SYNC_WRITE_BATCH = 40
        private const val SYNC_CONCURRENCY = 6
        private const val SEARCH_LIMIT = 100


        /** Chronological by release date; albums with no date sort to the end. */
        val RELEASE_ORDER: Comparator<Album> = compareBy(
            { if (it.releaseDate == 0L) Long.MAX_VALUE else it.releaseDate },
            { it.title.lowercase() },
        )
        /** Albums per shuffle: a few hundred tracks, not the whole library. */
        private const val SHUFFLE_ALBUM_LIMIT = 25

        val TRACK_ORDER: Comparator<Track> =
            compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() })
    }
}

/** How many songs a radio asks the server for: Subsonic's own default. */
/**
 * How long an automatic sync waits after the last one finished. Only
 * [LibraryRepository.syncInBackground] consults it; the periodic job and
 * Sync Now both mean "now".
 */
private const val SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000

private const val RADIO_LENGTH = 50

/** How long a stored popular-songs list is served before being refreshed. */
private const val TOP_SONGS_TTL_MS = 7L * 24 * 60 * 60 * 1000
