package com.garado.amp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** How much of the player the cover gets. */
enum class ArtworkMode {
    /** In the panel between the header and the controls, as a square. */
    SMALL,

    /** None at all. Not drawn anywhere in the app, and not fetched either. */
    NONE,
}

/** What gets downloaded for offline playback. */
enum class OfflineMode {
    /** Nothing automatic. The user downloads albums and songs by hand. */
    MANUAL,

    /** Liked albums, liked songs, and everything in the user's playlists. */
    FAVORITES,

    /** Everything, favourites first, as far as the size limit allows. */
    ALL,
}

/**
 * How freely the app may use the network. User-set, not detected:
 * ConnectivityManager is blocked by the plugin sandbox.
 */
enum class DataMode {
    /** Nothing waits for Wi-Fi: downloads, covers, and streams all run on any connection. */
    MAKE_IT_HURT,

    /**
     * Off Wi-Fi, prefer whatever is downloaded over streaming it again.
     * Streaming quality on cellular is set per source; see [MusicSource.cellularFormat].
     */
    LOW_DATA,

    /** Assume no usable connection: play and show only what's downloaded. */
    WIFI_ONLY,
}

enum class AlbumSort { TITLE, ARTIST, YEAR, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING, RANDOM }
enum class SongSort { TITLE, ARTIST, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING }
enum class ArtistSort { NAME, MOST_PLAYED }
enum class PlaylistSort { RECENTLY_UPDATED, NAME, DATE_CREATED }

/** Which of the bar's three destinations was last showing. */
enum class LastSection { LIBRARY, SEARCH, NOW_PLAYING }

/** How the bottom bar is arranged, and what that implies for the pages above it. */
enum class LayoutMode {
    /** Expanded: the four library tabs along the bar, with search as a fifth. */
    STANDARD,

    /** Library, the player, and search, with the library as a page of its own. */
    SIMPLIFIED,
}

/**
 * All persisted state: the server credentials plus user preferences, backed by
 * the tool's private Preferences DataStore. Everything is exposed as a Flow so
 * the UI recomposes reactively.
 */
class AppSettings(private val dataStore: DataStore<Preferences>) {

    private val sourcesJson = Json { ignoreUnknownKeys = true }

    // --- Sources -------------------------------------------------------------

    /**
     * Every configured source, in the order they were added. Stored as one
     * JSON blob since DataStore has no list-of-records type. A pre-sources
     * install's single server, stored under the old flat keys, is folded in
     * on read rather than migrated in place, so downgrading still finds it.
     */
    val sources: Flow<List<MusicSource>> = dataStore.data.map { p ->
        val stored = p[SOURCES]?.let { text ->
            runCatching { sourcesJson.decodeFromString<List<MusicSource>>(text) }.getOrNull()
        }
        val list = stored ?: legacySource(p)?.let { listOf(it) } ?: emptyList()
        // Downloads were once configured once for the whole app. A source
        // with none of its own falls back to those old values on read,
        // rather than having them written back.
        list.map { source ->
            source.copy(
                downloadFormatId = source.downloadFormatId ?: p[DOWNLOAD_FORMAT],
                offlineModeName = source.offlineModeName ?: legacyOfflineMode(p[OFFLINE_MODE]).name,
                downloadLimitBytes = source.downloadLimitBytes ?: p[DOWNLOAD_LIMIT] ?: DEFAULT_DOWNLOAD_LIMIT,
            )
        }
    }.distinctUntilChanged()

    /** The source the library is currently showing; the first one until chosen. */
    val activeSource: Flow<MusicSource?> = combine(
        sources,
        dataStore.data.map { it[ACTIVE_SOURCE] }.distinctUntilChanged(),
    ) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.distinctUntilChanged()

    /**
     * Add a source, or replace the one with the same id. A Plex source is
     * also matched against `machineIdentifier`, Plex's stable server id,
     * since [MusicSource.id] is minted fresh on every re-link.
     */
    suspend fun saveSource(source: MusicSource): String {
        var effectiveId = source.id
        editSources { list ->
            val existing = list.indexOfFirst { it.id == source.id }.takeIf { it >= 0 }
                ?: source.machineIdentifier.takeIf { it.isNotBlank() }?.let { mid ->
                    list.indexOfFirst { it.kind == source.kind && it.machineIdentifier == mid }
                        .takeIf { it >= 0 }
                }
            if (existing == null) {
                list + source
            } else {
                val old = list[existing]
                // Keep the id, and with it the downloads database and the
                // user's preferences for this source.
                effectiveId = old.id
                val merged = source.copy(
                    id = old.id,
                    downloadFormatId = source.downloadFormatId ?: old.downloadFormatId,
                    offlineModeName = source.offlineModeName ?: old.offlineModeName,
                    downloadLimitBytes = source.downloadLimitBytes ?: old.downloadLimitBytes,
                    libraryId = source.libraryId ?: old.libraryId,
                )
                list.toMutableList().also { it[existing] = merged }
            }
        }
        return effectiveId
    }

    suspend fun removeSource(id: String) {
        editSources { list -> list.filterNot { it.id == id } }
        dataStore.edit { p -> if (p[ACTIVE_SOURCE] == id) p.remove(ACTIVE_SOURCE) }
    }

    suspend fun setActiveSource(id: String) {
        dataStore.edit { it[ACTIVE_SOURCE] = id }
    }

    private suspend fun editSources(block: (List<MusicSource>) -> List<MusicSource>) {
        dataStore.edit { p ->
            val current = p[SOURCES]?.let { text ->
                runCatching { sourcesJson.decodeFromString<List<MusicSource>>(text) }.getOrNull()
            } ?: listOfNotNull(legacySource(p))
            p[SOURCES] = sourcesJson.encodeToString(block(current))
        }
    }

    private fun legacySource(p: Preferences): MusicSource? {
        val base = p[BASE_URL]
        val user = p[USERNAME]
        val pass = p[PASSWORD]
        if (base.isNullOrBlank() || user == null || pass == null) return null
        return MusicSource(
            id = LEGACY_SOURCE_ID,
            kind = SourceKind.SUBSONIC,
            name = MusicSource.nameFor(base),
            baseUrl = base,
            username = user,
            password = pass,
            streamFormatId = StreamFormat.fromId(p[STREAM_FORMAT]).id,
            libraryId = p[LIBRARY_ID],
        )
    }

    /** The active source's connection, or null when it is the phone's own music. */
    val serverConfig: Flow<SubsonicConfig?> =
        activeSource.map { it?.toConfig() }.distinctUntilChanged()

    /** Selected library (music folder) id; null means all libraries. */
    val libraryId: Flow<String?> = activeSource.map { it?.libraryId }.distinctUntilChanged()

    suspend fun setLibraryId(id: String?) {
        val current = activeSource.first() ?: return
        saveSource(current.copy(libraryId = id))
    }

    /** Record the folders a server reported, for the Sources page. */
    suspend fun setSourceLibraries(sourceId: String, libraries: List<SourceLibrary>) {
        val current = sources.first().firstOrNull { it.id == sourceId } ?: return
        if (current.libraries == libraries) return
        saveSource(current.copy(libraries = libraries))
    }

    /** Streaming and download quality, both per source; the phone's own music has neither. */
    suspend fun setStreamFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(streamFormatId = format.id) }
    }

    suspend fun setCellularFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(cellularFormatId = format.id) }
    }

    suspend fun setSourceDownloadFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(downloadFormatId = format.id) }
    }

    // --- One source's downloads ----------------------------------------------

    /**
     * Show or hide one of a source's libraries on the Sources page. Refuses
     * to hide the last one, since those rows are the only way to switch to it.
     */
    suspend fun setLibraryVisible(sourceId: String, libraryId: String?, visible: Boolean) {
        editSource(sourceId) { source ->
            val hidden = source.hiddenLibraryIds.toMutableList()
            if (visible) {
                hidden.remove(libraryId)
            } else {
                val remaining = source.libraries.count { it.id !in hidden } +
                    (if (source.showsAllLibraries) 1 else 0)
                if (remaining <= 1) return@editSource source
                if (libraryId !in hidden) hidden.add(libraryId)
            }
            source.copy(hiddenLibraryIds = hidden)
        }
    }

    suspend fun setSourceOfflineMode(sourceId: String, mode: OfflineMode) {
        editSource(sourceId) { it.copy(offlineModeName = mode.name) }
    }

    /**
     * Record that this source's cache has been refilled by the current
     * parser; see [MusicSource.parserGeneration]. Written only after a sync
     * finishes, so an interrupted sync retries next time.
     */
    suspend fun setParserGeneration(sourceId: String, generation: Int) {
        editSource(sourceId) { it.copy(parserGeneration = generation) }
    }

    private suspend fun editSource(sourceId: String, block: (MusicSource) -> MusicSource) {
        val current = sources.first().firstOrNull { it.id == sourceId } ?: return
        saveSource(block(current))
    }

    // --- Preferences ---------------------------------------------------------

    val albumSort: Flow<AlbumSort> = enumFlow(ALBUM_SORT, AlbumSort.TITLE)
    val songSort: Flow<SongSort> = enumFlow(SONG_SORT, SongSort.TITLE)
    val artistSort: Flow<ArtistSort> = enumFlow(ARTIST_SORT, ArtistSort.NAME)
    val playlistSort: Flow<PlaylistSort> = enumFlow(PLAYLIST_SORT, PlaylistSort.RECENTLY_UPDATED)

    /** The tag each list is narrowed to, or blank for all of it. One setting per tab. */
    val albumsGenre: Flow<String> = stringFlow(ALBUMS_GENRE)
    val albumsComposer: Flow<String> = stringFlow(ALBUMS_COMPOSER)
    val songsGenre: Flow<String> = stringFlow(SONGS_GENRE)
    val songsComposer: Flow<String> = stringFlow(SONGS_COMPOSER)

    val likedAlbumsOnly: Flow<Boolean> = boolFlow(LIKED_ALBUMS_ONLY, false)
    val likedSongsOnly: Flow<Boolean> = boolFlow(LIKED_SONGS_ONLY, false)
    val likedArtistsOnly: Flow<Boolean> = boolFlow(LIKED_ARTISTS_ONLY, false)

    // Inverts each tab's sort; re-tapping the selected option flips it.
    val albumSortReversed: Flow<Boolean> = boolFlow(ALBUM_SORT_REV, false)
    val songSortReversed: Flow<Boolean> = boolFlow(SONG_SORT_REV, false)
    val artistSortReversed: Flow<Boolean> = boolFlow(ARTIST_SORT_REV, false)
    val playlistSortReversed: Flow<Boolean> = boolFlow(PLAYLIST_SORT_REV, false)

    /** Bumped each time Random is re-tapped for a new deal; see sortAlbums. */
    val shuffleNonce: Flow<Int> =
        dataStore.data.map { it[SHUFFLE_NONCE] ?: 0 }.distinctUntilChanged()

    suspend fun bumpShuffleNonce() {
        dataStore.edit { it[SHUFFLE_NONCE] = (it[SHUFFLE_NONCE] ?: 0) + 1 }
    }

    // --- Offline / downloads -------------------------------------------------

    /**
     * The old app-wide offline mode, read only to seed a source with none of
     * its own; see [sources]. "RANDOM" is mapped to ALL rather than left to
     * fail enumValueOf, so anyone mid-download doesn't drop back to Manual.
     */
    private fun legacyOfflineMode(stored: String?): OfflineMode = when (stored) {
        null -> OfflineMode.MANUAL
        "RANDOM" -> OfflineMode.ALL
        else -> runCatching { enumValueOf<OfflineMode>(stored) }.getOrNull() ?: OfflineMode.MANUAL
    }

    /** Defaults to Low Data: safe on a metered plan without blocking a first sync. */
    val dataMode: Flow<DataMode> = enumFlow(DATA_MODE, DataMode.LOW_DATA)

    /** How much of the phone the downloads may take, across the whole tool, not per source. */
    val downloadLimit: Flow<Long> =
        dataStore.data.map { it[DOWNLOAD_LIMIT] ?: DEFAULT_DOWNLOAD_LIMIT }
            .distinctUntilChanged()

    suspend fun setDownloadLimit(bytes: Long) {
        dataStore.edit { it[DOWNLOAD_LIMIT] = bytes }
    }

    /**
     * TEMPORARY. Whether the one-time repair of downloaded mp3s still has to
     * run. Delete this, [markMp3IndexRepaired], their caller, and
     * DownloadStore.indexMp3s before the tool is submitted for community review.
     */
    suspend fun mp3IndexRepairNeeded(): Boolean =
        dataStore.data.first()[MP3_INDEX_REPAIRED] != true

    /** Record that the repair finished. Call only after the walk completes, never before. */
    suspend fun markMp3IndexRepaired() {
        dataStore.edit { it[MP3_INDEX_REPAIRED] = true }
    }

    suspend fun migrateDownloadLimit() {
        val p = dataStore.data.first()
        if (p[DOWNLOAD_LIMIT] != null) return
        val perSource = runCatching {
            sourcesJson.decodeFromString<List<MusicSource>>(p[SOURCES].orEmpty())
        }.getOrDefault(emptyList()).mapNotNull { it.downloadLimitBytes }
        if (perSource.isEmpty()) return
        setDownloadLimit(perSource.max())
    }

    suspend fun setDataMode(value: DataMode) = putString(DATA_MODE, value.name)


    val invertColors: Flow<Boolean> = boolFlow(INVERT_COLORS, false)
    val karaokeLyrics: Flow<Boolean> = boolFlow(KARAOKE_LYRICS, true)

    /**
     * Even out the volume between tracks, from the loudness the server
     * measured. On by default. Attenuate-only, so it never adds clipping.
     */
    val replayGain: Flow<Boolean> = boolFlow(REPLAY_GAIN, true)

    /** Whether to use the simplified or standard layout mode. */
    val layoutMode: Flow<LayoutMode> = enumFlow(LAYOUT_MODE, LayoutMode.STANDARD)

    /** Where the app was when it was last closed; see [LastSection]. */
    val lastSection: Flow<LastSection> = enumFlow(LAST_SECTION, LastSection.LIBRARY)

    /**
     * Show album lists as a wall of covers instead of a column of titles.
     * Ignored when artwork is off.
     */
    val albumGrid: Flow<Boolean> = boolFlow(ALBUM_GRID, false)

    /**
     * The same choice, kept separately for an artist's own page, since a
     * discography and the whole library aren't the same kind of list.
     */
    val artistAlbumGrid: Flow<Boolean> = boolFlow(ARTIST_ALBUM_GRID, false)


    /** Drop the artists' own pictures, keeping every other kind of artwork. Hidden by default. */
    val hideArtistImages: Flow<Boolean> = boolFlow(HIDE_ARTIST_IMAGES, true)

    /** Stop marking downloaded rows; the layout's leading slot stays, just empty. */
    val hideDownloadIcons: Flow<Boolean> = boolFlow(HIDE_DOWNLOAD_ICONS, false)


    /**
     * The queue as it stood when the app last went away: track ids, the
     * index into them, and roughly where playback had got to. Ids rather
     * than full rows; capped at [MAX_SAVED_QUEUE].
     */
    val savedQueueIds: Flow<List<String>> =
        dataStore.data.map { p ->
            p[SAVED_QUEUE]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        }.distinctUntilChanged()

    val savedQueueIndex: Flow<Int> =
        dataStore.data.map { it[SAVED_INDEX] ?: 0 }.distinctUntilChanged()

    val savedPositionMs: Flow<Long> =
        dataStore.data.map { it[SAVED_POSITION] ?: 0L }.distinctUntilChanged()

    /**
     * The last known playlists per source, as JSON, so a cold start offline
     * still lists them. Keyed by source id so a source switch can't fall
     * back to another source's list.
     */
    suspend fun cachedPlaylists(sourceId: String): String =
        dataStore.data.first()[cachedPlaylistsKey(sourceId)] ?: ""

    suspend fun setCachedPlaylists(sourceId: String, value: String) =
        putString(cachedPlaylistsKey(sourceId), value)

    private fun cachedPlaylistsKey(sourceId: String) =
        stringPreferencesKey("${CACHED_PLAYLISTS.name}.$sourceId")

    /** Read once per operation rather than observed: see [PendingActions]. */
    suspend fun pendingActions(): String = dataStore.data.first()[PENDING_ACTIONS] ?: ""

    suspend fun setPendingActions(value: String) = putString(PENDING_ACTIONS, value)

    suspend fun saveQueue(ids: List<String>, index: Int, positionMs: Long) {
        dataStore.edit { p ->
            p[SAVED_QUEUE] = ids.take(MAX_SAVED_QUEUE).joinToString(",")
            p[SAVED_INDEX] = index
            p[SAVED_POSITION] = positionMs
        }
    }

    /** The user's own hold on downloading, kept across launches. */
    val downloadsPaused: Flow<Boolean> = boolFlow(DOWNLOADS_PAUSED, false)

    /** How much of the player the cover gets, if any. */
    val artwork: Flow<ArtworkMode> = enumFlow(ARTWORK, ArtworkMode.SMALL)

    suspend fun setAlbumSort(value: AlbumSort) = putString(ALBUM_SORT, value.name)
    suspend fun setSongSort(value: SongSort) = putString(SONG_SORT, value.name)
    suspend fun setArtistSort(value: ArtistSort) = putString(ARTIST_SORT, value.name)
    suspend fun setPlaylistSort(value: PlaylistSort) = putString(PLAYLIST_SORT, value.name)
    suspend fun setAlbumsGenre(value: String) = putString(ALBUMS_GENRE, value)
    suspend fun setAlbumsComposer(value: String) = putString(ALBUMS_COMPOSER, value)
    suspend fun setSongsGenre(value: String) = putString(SONGS_GENRE, value)
    suspend fun setSongsComposer(value: String) = putString(SONGS_COMPOSER, value)

    suspend fun setLikedAlbumsOnly(value: Boolean) = putBool(LIKED_ALBUMS_ONLY, value)
    suspend fun setLikedSongsOnly(value: Boolean) = putBool(LIKED_SONGS_ONLY, value)
    suspend fun setLikedArtistsOnly(value: Boolean) = putBool(LIKED_ARTISTS_ONLY, value)

    /**
     * Drop every narrowing of the library lists (liked switches, genre and
     * composer filters) in one write. Called on a source switch; see
     * App.swapSource.
     */
    suspend fun clearLibraryFilters() {
        dataStore.edit { p ->
            p.remove(LIKED_ALBUMS_ONLY)
            p.remove(LIKED_SONGS_ONLY)
            p.remove(LIKED_ARTISTS_ONLY)
            p.remove(ALBUMS_GENRE)
            p.remove(ALBUMS_COMPOSER)
            p.remove(SONGS_GENRE)
            p.remove(SONGS_COMPOSER)
        }
    }

    suspend fun setAlbumSortReversed(value: Boolean) = putBool(ALBUM_SORT_REV, value)
    suspend fun setSongSortReversed(value: Boolean) = putBool(SONG_SORT_REV, value)
    suspend fun setArtistSortReversed(value: Boolean) = putBool(ARTIST_SORT_REV, value)
    suspend fun setPlaylistSortReversed(value: Boolean) = putBool(PLAYLIST_SORT_REV, value)

    /** Local-only: playlist id -> folder name. Keyed by "<sourceId>:<playlistId>". */
    val playlistFolders: Flow<Map<String, String>> = dataStore.data.map { p ->
        decodePlaylistFolders(p[PLAYLIST_FOLDERS])
    }.distinctUntilChanged()

    suspend fun setPlaylistFolder(sourceId: String, playlistId: String, folder: String?) {
        val key = "$sourceId:$playlistId"
        dataStore.edit { p ->
            val current = decodePlaylistFolders(p[PLAYLIST_FOLDERS])
            val updated = if (folder.isNullOrBlank()) current - key else current + (key to folder)
            p[PLAYLIST_FOLDERS] = sourcesJson.encodeToString(updated)
        }
    }

    suspend fun prunePlaylistFolders(sourceId: String, liveIds: Set<String>) {
        dataStore.edit { p ->
            val current = decodePlaylistFolders(p[PLAYLIST_FOLDERS])
            val prefix = "$sourceId:"
            val pruned = current.filterKeys { k -> !k.startsWith(prefix) || k.removePrefix(prefix) in liveIds }
            if (pruned.size != current.size) p[PLAYLIST_FOLDERS] = sourcesJson.encodeToString(pruned)
        }
    }

    private fun decodePlaylistFolders(raw: String?): Map<String, String> =
        raw?.let { runCatching { sourcesJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()

    /** Local-only: starred playlist ids. Keyed by "<sourceId>:<playlistId>". */
    val starredPlaylists: Flow<Set<String>> = dataStore.data.map { p ->
        decodeStarredPlaylists(p[STARRED_PLAYLISTS])
    }.distinctUntilChanged()

    suspend fun setPlaylistStarred(sourceId: String, playlistId: String, starred: Boolean) {
        val key = "$sourceId:$playlistId"
        dataStore.edit { p ->
            val current = decodeStarredPlaylists(p[STARRED_PLAYLISTS])
            val updated = if (starred) current + key else current - key
            p[STARRED_PLAYLISTS] = sourcesJson.encodeToString(updated)
        }
    }

    suspend fun pruneStarredPlaylists(sourceId: String, liveIds: Set<String>) {
        dataStore.edit { p ->
            val current = decodeStarredPlaylists(p[STARRED_PLAYLISTS])
            val prefix = "$sourceId:"
            val pruned = current.filterTo(mutableSetOf()) { k -> !k.startsWith(prefix) || k.removePrefix(prefix) in liveIds }
            if (pruned.size != current.size) p[STARRED_PLAYLISTS] = sourcesJson.encodeToString(pruned)
        }
    }

    private fun decodeStarredPlaylists(raw: String?): Set<String> =
        raw?.let { runCatching { sourcesJson.decodeFromString<Set<String>>(it) }.getOrNull() } ?: emptySet()

    suspend fun setInvertColors(value: Boolean) = putBool(INVERT_COLORS, value)
    suspend fun setKaraokeLyrics(value: Boolean) = putBool(KARAOKE_LYRICS, value)
    suspend fun setAlbumGrid(value: Boolean) = putBool(ALBUM_GRID, value)
    suspend fun setArtistAlbumGrid(value: Boolean) = putBool(ARTIST_ALBUM_GRID, value)
    suspend fun setHideArtistImages(value: Boolean) = putBool(HIDE_ARTIST_IMAGES, value)
    suspend fun setHideDownloadIcons(value: Boolean) = putBool(HIDE_DOWNLOAD_ICONS, value)
    suspend fun setReplayGain(value: Boolean) = putBool(REPLAY_GAIN, value)

    suspend fun setArtwork(value: ArtworkMode) = putString(ARTWORK, value.name)
    suspend fun setDownloadsPaused(value: Boolean) = putBool(DOWNLOADS_PAUSED, value)
    suspend fun setLayoutMode(value: LayoutMode) = putString(LAYOUT_MODE, value.name)

    suspend fun setLastSection(value: LastSection) = putString(LAST_SECTION, value.name)

    // --- helpers -------------------------------------------------------------

    private fun stringFlow(key: Preferences.Key<String>): Flow<String> =
        dataStore.data.map { it[key].orEmpty() }.distinctUntilChanged()

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[key] ?: default }.distinctUntilChanged()

    private inline fun <reified E : Enum<E>> enumFlow(
        key: Preferences.Key<String>,
        default: E,
    ): Flow<E> = dataStore.data.map { prefs ->
        prefs[key]?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: default
    }.distinctUntilChanged()

    private suspend fun putString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun putBool(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("server.baseUrl")
        private val USERNAME = stringPreferencesKey("server.username")
        private val PASSWORD = stringPreferencesKey("server.password")
        private val STREAM_FORMAT = stringPreferencesKey("server.streamFormat")
        private val LIBRARY_ID = stringPreferencesKey("server.libraryId")

        private val ALBUM_SORT = stringPreferencesKey("pref.albumSort")
        private val SONG_SORT = stringPreferencesKey("pref.songSort")
        private val ARTIST_SORT = stringPreferencesKey("pref.artistSort")
        private val PLAYLIST_SORT = stringPreferencesKey("pref.playlistSort")
        private val ALBUMS_GENRE = stringPreferencesKey("pref.albumsGenre")
        private val ALBUMS_COMPOSER = stringPreferencesKey("pref.albumsComposer")
        private val SONGS_GENRE = stringPreferencesKey("pref.songsGenre")
        private val SONGS_COMPOSER = stringPreferencesKey("pref.songsComposer")

        /** TEMPORARY, see [mp3IndexRepairNeeded]; goes with it. */
        private val MP3_INDEX_REPAIRED = booleanPreferencesKey("pref.mp3IndexRepaired")

        private val LIKED_ALBUMS_ONLY = booleanPreferencesKey("pref.likedAlbumsOnly")
        private val LIKED_SONGS_ONLY = booleanPreferencesKey("pref.likedSongsOnly")
        private val LIKED_ARTISTS_ONLY = booleanPreferencesKey("pref.likedArtistsOnly")

        private val ALBUM_SORT_REV = booleanPreferencesKey("pref.albumSortReversed")
        private val SONG_SORT_REV = booleanPreferencesKey("pref.songSortReversed")
        private val ARTIST_SORT_REV = booleanPreferencesKey("pref.artistSortReversed")
        private val PLAYLIST_SORT_REV = booleanPreferencesKey("pref.playlistSortReversed")
        private val PLAYLIST_FOLDERS = stringPreferencesKey("pref.playlistFolders")
        private val STARRED_PLAYLISTS = stringPreferencesKey("pref.starredPlaylists")
        private val SHUFFLE_NONCE = intPreferencesKey("pref.shuffleNonce")

        private val INVERT_COLORS = booleanPreferencesKey("pref.invertColors")
        private val KARAOKE_LYRICS = booleanPreferencesKey("pref.karaokeLyrics")
        private val REPLAY_GAIN = booleanPreferencesKey("pref.replayGain")
        private val ALBUM_GRID = booleanPreferencesKey("pref.albumGrid")
        private val ARTIST_ALBUM_GRID = booleanPreferencesKey("pref.artistAlbumGrid")
        private val HIDE_ARTIST_IMAGES = booleanPreferencesKey("pref.hideArtistImages")
        private val HIDE_DOWNLOAD_ICONS = booleanPreferencesKey("pref.hideDownloadIcons")
        private val ARTWORK = stringPreferencesKey("pref.artworkMode")
        private val LAYOUT_MODE = stringPreferencesKey("pref.layoutMode")
        private val LAST_SECTION = stringPreferencesKey("pref.lastSection")
        private val SOURCES = stringPreferencesKey("pref.sources")
        private val ACTIVE_SOURCE = stringPreferencesKey("pref.activeSource")

        /** The id given to a server configured before sources existed. */
        const val LEGACY_SOURCE_ID = "server"

        private val DOWNLOADS_PAUSED = booleanPreferencesKey("pref.downloadsPaused")
        private val CACHED_PLAYLISTS = stringPreferencesKey("state.playlists")
        private val PENDING_ACTIONS = stringPreferencesKey("state.pendingActions")
        private val SAVED_QUEUE = stringPreferencesKey("state.queueIds")
        private val SAVED_INDEX = intPreferencesKey("state.queueIndex")
        private val SAVED_POSITION = longPreferencesKey("state.positionMs")

        /** Plenty for an album or a long playlist, short of a whole library. */
        const val MAX_SAVED_QUEUE = 1000

        private val OFFLINE_MODE = stringPreferencesKey("pref.offlineMode")
        private val DATA_MODE = stringPreferencesKey("pref.dataMode")
        /** The old app-wide download format, read once to seed a source with no choice of its own. */
        private val DOWNLOAD_FORMAT = stringPreferencesKey("pref.downloadFormat")
        private val DOWNLOAD_LYRICS = booleanPreferencesKey("pref.downloadLyrics")
        private val DOWNLOAD_LIMIT = longPreferencesKey("pref.downloadLimitBytes")

        /** 2 GB: a few hundred albums as MP3. */
        const val DEFAULT_DOWNLOAD_LIMIT = 2L * 1024 * 1024 * 1024
    }
}
