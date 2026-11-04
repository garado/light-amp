package com.garado.amp.data

/**
 * A queue the server is holding, from this client or another one.
 * Uses [currentId] rather than an index, since the local library may be
 * missing some of the tracks the queue names.
 */
data class SavedQueue(
    val trackIds: List<String>,
    val currentId: String?,
    val positionMs: Long,
)

/**
 * What the app needs from a music server, regardless of kind.
 *
 * Not every server supports every call. Each [MusicSource] declares its own
 * capabilities (see [MusicSource.supportsLikes] and similar) and the UI hides
 * what isn't supported. Unsupported methods here default to no-ops.
 */
interface MusicServer {

    /** Throws if the server isn't reachable or the credentials are wrong. */
    suspend fun ping()

    /** Releases the HTTP client. */
    fun close()

    // --- Browsing ------------------------------------------------------------

    /** The server's separate libraries (Subsonic folders, Plex sections), if it has the concept. */
    suspend fun getMusicFolders(): List<MusicFolder>

    suspend fun getAllAlbums(musicFolderId: String? = null): List<Album>

    suspend fun getAlbumTracks(albumId: String): List<Track>

    /** The user's favourites. Empty on a server with no such concept. */
    suspend fun getStarred(musicFolderId: String? = null): Starred =
        Starred(songIds = emptySet(), albumIds = emptySet(), artistNames = emptySet())

    /**
     * Asks the server to scan its files for new content.
     * Returns false if the server has no such call, or this token can't use it.
     */
    suspend fun startServerScan(musicFolderId: String? = null): Boolean = false

    /** True while a [startServerScan] is still running. */
    suspend fun serverScanning(musicFolderId: String? = null): Boolean = false

    /** Server-side artist ids, for the star calls that need one. */
    suspend fun getArtistIndex(musicFolderId: String? = null): List<ArtistRef> = emptyList()

    /**
     * An artist's best-known songs. Empty where the server doesn't rank them.
     * [count] of 0 means the server's own default, not a request for zero results.
     */
    suspend fun getTopSongs(artistName: String, count: Int = 0): List<Track> = emptyList()

    /**
     * Songs to follow on from this one, a radio seeded by a track.
     * Empty if the server has nothing to suggest. [count] of 0 means the
     * server's own default, as with [getTopSongs].
     */
    suspend fun getSimilarSongs(songId: String, count: Int = 0): List<Track> = emptyList()

    // --- Media ---------------------------------------------------------------

    fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        /**
         * Caller-generated id for this playback, distinct from any other stream
         * or download in flight. Ignored by servers with no session concept
         * (Subsonic); Plex uses it to tell concurrent streams apart.
         */
        sessionId: String? = null,
    ): String

    /**
     * Same as above, for a caller that already has the track.
     * Plex can only serve original bytes via [Track.streamPath]; prefer this
     * overload whenever a [Track] is already in hand.
     */
    fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        sessionId: String? = null,
    ): String = streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength, sessionId)

    /**
     * URL for fetching a copy, as opposed to streaming it now.
     * Same as [streamUrl] for most servers. Plex's streaming mode produces a
     * live segmented encode with no length header, so downloads need its
     * separate mode instead. See [PlexClient].
     */
    fun downloadUrl(track: Track, format: StreamFormat): String =
        streamUrl(track, format, estimateContentLength = false)

    /**
     * Settles whatever the server needs before it will serve a stream. Most
     * servers need nothing. Plex requires this call first, or the transcode
     * request is refused with a bare 400. See [PlexClient.prepareStream].
     *
     * Use the same [sessionId] passed to the stream URL. Returns false if the
     * server refused; playback is attempted anyway.
     */
    suspend fun prepareStream(
        songId: String,
        format: StreamFormat,
        sessionId: String?,
    ): Boolean = true

    fun coverArtUrl(coverArtId: String?): String?

    /**
     * The same cover, resized to at most [maxSizePx] on its long edge.
     * Defaults to the full-size URL for servers that can't resize.
     */
    fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? = coverArtUrl(coverArtId)

    /**
     * Formats this server can actually deliver. A server asked for a format it
     * can't produce sends something else without saying so, so the app must
     * never request a format missing from this list.
     */
    val streamFormats: List<StreamFormat>

    /** Words for a song, timed where the server has them. */
    suspend fun getLyrics(songId: String): Lyrics? = null

    // --- Writing back --------------------------------------------------------

    /**
     * [submission] false reports "playing now"; true reports a completed play
     * (what reaches scrobbling services). These are distinct calls to the server.
     */
    suspend fun scrobble(songId: String, atMs: Long? = null, submission: Boolean = true) = Unit

    /**
     * Reports that this session is still active, for servers with a live
     * "Now Playing" view (Plex). [sessionId] should match the one passed to
     * [streamUrl] for this stream. No-op on servers with no session concept.
     */
    suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) = Unit

    /** Hands the queue to the server so another client can pick it up. See [SavedQueue]. */
    suspend fun savePlayQueue(trackIds: List<String>, currentId: String?, positionMs: Long) = Unit

    /** The queue this account last left somewhere, or null if the server keeps none. */
    suspend fun getPlayQueue(): SavedQueue? = null

    /** 1–100, or 0 to clear. False when the server wouldn't take it. */
    suspend fun setRating(id: String, rating: Int): Boolean = false

    suspend fun starSong(songId: String) = Unit
    suspend fun unstarSong(songId: String) = Unit
    suspend fun starAlbum(albumId: String) = Unit
    suspend fun unstarAlbum(albumId: String) = Unit
    suspend fun starArtist(artistId: String) = Unit
    suspend fun unstarArtist(artistId: String) = Unit

    // --- Playlists -----------------------------------------------------------

    suspend fun getPlaylists(musicFolderId: String? = null): List<Playlist> = emptyList()
    suspend fun getPlaylist(id: String): Playlist =
        Playlist(id = id, name = "", coverArtId = null, createdAt = 0L, updatedAt = 0L, trackIds = emptyList())
    suspend fun getPlaylistTracks(id: String): List<Track> = emptyList()
    /**
     * Creates a playlist and returns its id. [songIds] are the initial
     * contents, don't add them again afterward. Plex can't create an empty
     * playlist, so they're required there; see [MusicSource.supportsEmptyPlaylists].
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? = null
    suspend fun renamePlaylist(id: String, name: String) = Unit
    suspend fun deletePlaylist(id: String) = Unit
    suspend fun addToPlaylist(id: String, songId: String) = Unit
    suspend fun removeFromPlaylistAt(id: String, index: Int) = Unit
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) = Unit
}

/** What [MusicServer.reportTimeline] tells the server a session is doing. */
enum class TimelineState {
    PLAYING,
    PAUSED,
    STOPPED,
    BUFFERING,
}
