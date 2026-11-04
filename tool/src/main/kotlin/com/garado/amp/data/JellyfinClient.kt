package com.garado.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Jellyfin server's music library, behind the same [MusicServer] interface
 * Navidrome and Plex use.
 *
 * Jellyfin has no star ratings (only a favourite flag), no per-artist
 * popularity ranking, and no shared play queue across clients. These are
 * left out rather than faked; see the capability flags on [MusicSource].
 * Everything else round-trips with Jellyfin's own clients.
 */
class JellyfinClient(
    /** The server's address, e.g. `http://192.168.1.10:8096`. */
    private val baseUrl: String,
    /** From `/Users/AuthenticateByName`; every request carries it. */
    private val token: String,
    /** Whose library this is. Jellyfin scopes favourites, play state, and visible libraries to a user. */
    private val userId: String,
    /** Overrides the `Client` this reports itself as; null keeps the app's own. */
    private val product: String? = null,
) : MusicServer {

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private fun url(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
    }

    private suspend fun body(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val response = http.get(url(path, params)) { jellyfinHeaders() }
        if (!response.status.isSuccess()) {
            throw JellyfinException("Jellyfin says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    private suspend inline fun <reified T> fetch(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): T = json.decodeFromString(body(path, params))

    /** A call whose answer doesn't matter, and whose failure mustn't propagate. */
    private suspend fun send(
        method: String,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        payload: JsonObject? = null,
    ): Boolean = runCatching {
        val target = url(path, params)
        val response = when (method) {
            "POST" -> http.post(target) {
                jellyfinHeaders()
                if (payload != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(JsonObject.serializer(), payload))
                }
            }
            "DELETE" -> http.delete(target) { jellyfinHeaders() }
            else -> http.get(target) { jellyfinHeaders() }
        }
        response.status.isSuccess()
    }.getOrDefault(false)

    private fun io.ktor.client.request.HttpRequestBuilder.jellyfinHeaders() {
        header("Authorization", authorization(product, token))
        header("Accept", "application/json")
    }

    override suspend fun ping() {
        // Proves the address, the token, and the user in one call: an expired
        // token answers 401 here rather than an empty library later.
        body("/Users/$userId")
    }

    // --- Browsing ------------------------------------------------------------

    /**
     * The user's music libraries. Uses `/Views`, already scoped to what this
     * user may see, rather than `/Items`, which lists everything on the
     * server. Non-music collections are dropped.
     */
    override suspend fun getMusicFolders(): List<MusicFolder> =
        fetch<JellyfinItems>("/Users/$userId/Views").items
            .filter { it.collectionType.equals("music", ignoreCase = true) }
            .map { MusicFolder(id = it.id, name = it.name) }

    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> =
        paged(musicFolderId, ALBUM_TYPE).map { it.toAlbum() }.distinctBy { it.id }

    /**
     * Everything of one type, following the pages to the end, stopping at
     * `TotalRecordCount`. The explicit sort order is required, not cosmetic:
     * a page is an offset into a result set, and without a stable order the
     * server can return different rows for each page, dropping some and
     * repeating others.
     */
    private suspend fun paged(
        musicFolderId: String?,
        itemType: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): List<JellyfinItem> {
        val items = mutableListOf<JellyfinItem>()
        var start = 0
        while (true) {
            val params = buildList {
                add("userId" to userId)
                add("IncludeItemTypes" to itemType)
                add("Recursive" to "true")
                add("SortBy" to "SortName")
                add("SortOrder" to "Ascending")
                add("Fields" to FIELDS)
                add("StartIndex" to start.toString())
                add("Limit" to PAGE_SIZE.toString())
                if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                addAll(extra)
            }
            val page = fetch<JellyfinItems>("/Items", params)
            items += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        return items
    }

    override suspend fun getAlbumTracks(albumId: String): List<Track> =
        fetch<JellyfinItems>(
            "/Items",
            listOf(
                "userId" to userId,
                "ParentId" to albumId,
                "IncludeItemTypes" to TRACK_TYPE,
                "Fields" to FIELDS,
                // Disc first, then track: a two-disc record read in track order
                // alone interleaves the two.
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
                "SortOrder" to "Ascending",
            ),
        ).items.map { it.toTrack() }

    /**
     * Jellyfin's instant mix: songs it judges to go with this one, from its
     * own genre and artist data. The seed track comes back first; the
     * repository drops it rather than relying on that order here.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> = runCatching {
        fetch<JellyfinItems>(
            "/Items/$songId/InstantMix",
            listOf("userId" to userId, "Fields" to FIELDS) +
                if (count > 0) listOf("Limit" to count.toString()) else emptyList(),
        ).items.filter { it.type == TRACK_TYPE }.map { it.toTrack() }
    }.onFailure { android.util.Log.w("AmpRadio", "jellyfin instant mix failed: ${it.message}") }
        .onSuccess { android.util.Log.i("AmpRadio", "jellyfin instant mix: ${it.size} items for $songId") }
        .getOrDefault(emptyList())

    /**
     * Favourites, which Jellyfin keeps per user and per item. Artists come
     * back as names rather than ids, since the app's artist list has no
     * server id to match against.
     */
    override suspend fun getStarred(musicFolderId: String?): Starred {
        val favourite = listOf("Filters" to "IsFavorite")
        val songs = runCatching { paged(musicFolderId, TRACK_TYPE, favourite) }
            .getOrDefault(emptyList())
        val albums = runCatching { paged(musicFolderId, ALBUM_TYPE, favourite) }
            .getOrDefault(emptyList())
        val artists = runCatching {
            fetch<JellyfinItems>(
                "/Artists",
                buildList {
                    add("userId" to userId)
                    add("Filters" to "IsFavorite")
                    add("Limit" to PAGE_SIZE.toString())
                    if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                },
            ).items
        }.getOrDefault(emptyList())
        return Starred(
            songIds = songs.map { it.id }.toSet(),
            albumIds = albums.map { it.id }.toSet(),
            artistNames = artists.map { it.name }.toSet(),
        )
    }

    /**
     * Jellyfin's own library scan. Administrator-only; the refusal for a
     * shared account is reported rather than swallowed, since it would
     * otherwise look like a scan that ran and found nothing.
     */
    override suspend fun startServerScan(musicFolderId: String?): Boolean =
        send("POST", "/Library/Refresh")

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch<List<JellyfinTask>>("/ScheduledTasks")
            .any { it.key == SCAN_TASK && it.state.equals("Running", ignoreCase = true) }
    }.getOrDefault(false)

    /** The server's own artist records, where their pictures live; the library's track tags carry none. */
    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> = runCatching {
        val items = mutableListOf<JellyfinItem>()
        var start = 0
        while (true) {
            val page = fetch<JellyfinItems>(
                "/Artists",
                buildList {
                    add("userId" to userId)
                    add("SortBy" to "SortName")
                    add("SortOrder" to "Ascending")
                    add("StartIndex" to start.toString())
                    add("Limit" to PAGE_SIZE.toString())
                    if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                },
            )
            items += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        items.map { ArtistRef(id = it.id, name = it.name, imageId = it.imageId()) }
            .distinctBy { it.id }
    }.getOrDefault(emptyList())

    // --- Media ---------------------------------------------------------------

    /**
     * A copy to keep; see [SubsonicClient.downloadUrl], which this mirrors.
     * `/Items/{id}/Download` opens no playback session, unlike
     * `/Audio/{id}/stream`, and serves the original only; a smaller copy
     * still comes from the streaming transcode.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String =
        if (format == StreamFormat.RAW) {
            url("/Items/${track.id}/Download", listOf("api_key" to token))
        } else {
            streamUrl(track, format, estimateContentLength = false)
        }

    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        if (format == StreamFormat.RAW) {
            return url(
                "/Audio/$songId/stream",
                listOf(
                    "static" to "true",
                    "api_key" to token,
                ),
            )
        }
        val container = CONTAINERS[format] ?: MP3_CONTAINER
        val params = buildList {
            add("UserId" to userId)
            add("DeviceId" to DEVICE_ID)
            add("api_key" to token)
            add("Container" to container)
            add("AudioCodec" to (CODECS[format] ?: container))
            add("TranscodingContainer" to container)
            add("TranscodingProtocol" to "http")
            format.maxBitRate?.let { add("MaxStreamingBitrate" to (it * 1000).toString()) }
            if (timeOffsetSeconds > 0) {
                add("StartTimeTicks" to (timeOffsetSeconds.toLong() * 1000L * TICKS_PER_MS).toString())
            }
            // Ties a run of reports to one stream, so Jellyfin's dashboard
            // shows one session rather than a new one per request.
            if (!sessionId.isNullOrBlank()) add("PlaySessionId" to sessionId)
        }
        return url("/Audio/$songId/universal", params)
    }

    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    /**
     * Jellyfin serves an image for any id given, answering 404 only on
     * fetch, so a cover id is minted only for an item that says it has one;
     * see [JellyfinItem.imageId].
     */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return url("/Items/$coverArtId/Images/Primary", listOf("api_key" to token))
    }

    /** The cover through Jellyfin's own resizer, shrinking stored art down from the scanner's original size. */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        return url(
            "/Items/$coverArtId/Images/Primary",
            listOf(
                "maxWidth" to maxSizePx.toString(),
                "maxHeight" to maxSizePx.toString(),
                "quality" to COVER_QUALITY.toString(),
                "api_key" to token,
            ),
        )
    }

    /**
     * Words for a song, from Jellyfin 10.9's lyrics endpoint. Older servers
     * answer 404, which is treated as "no lyrics" rather than an error.
     */
    override suspend fun getLyrics(songId: String): Lyrics? = runCatching {
        val parsed = fetch<JellyfinLyrics>("/Audio/$songId/Lyrics")
        val lines = parsed.lyrics
            .filter { it.text.isNotBlank() }
            .map { LyricLine(it.start?.let { ticks -> ticks / TICKS_PER_MS }, it.text.trim()) }
        if (lines.isEmpty()) return@runCatching null
        Lyrics(lines, synced = lines.any { it.timeMs != null })
    }.getOrNull()

    // --- Writing back --------------------------------------------------------

    /**
     * A start notice opens a session on the server; a play is recorded
     * separately against the item. Both are needed: the first without the
     * second announces nothing while a track plays, and the second without
     * the first counts everything skipped past as a play.
     */
    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        if (!submission) {
            send(
                "POST",
                "/Sessions/Playing",
                payload = buildJsonObject {
                    put("ItemId", songId)
                    put("PositionTicks", 0L)
                },
            )
            return
        }
        send("POST", "/Users/$userId/PlayedItems/$songId")
    }

    /**
     * The heartbeat behind Jellyfin's "now playing". Stopping is a separate
     * endpoint rather than a state, since a session merely reported paused
     * forever is still a session as far as the server is concerned.
     */
    override suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) {
        val ticks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS
        val payload = buildJsonObject {
            put("ItemId", songId)
            put("PositionTicks", ticks)
            put("PlaySessionId", sessionId)
            put("IsPaused", state == TimelineState.PAUSED)
            put("CanSeek", true)
        }
        val path = when (state) {
            TimelineState.STOPPED -> "/Sessions/Playing/Stopped"
            else -> "/Sessions/Playing/Progress"
        }
        send("POST", path, payload = payload)
    }

    override suspend fun starSong(songId: String) = favourite(songId, true)
    override suspend fun unstarSong(songId: String) = favourite(songId, false)
    override suspend fun starAlbum(albumId: String) = favourite(albumId, true)
    override suspend fun unstarAlbum(albumId: String) = favourite(albumId, false)
    override suspend fun starArtist(artistId: String) = favourite(artistId, true)
    override suspend fun unstarArtist(artistId: String) = favourite(artistId, false)

    private suspend fun favourite(id: String, on: Boolean) {
        send(if (on) "POST" else "DELETE", "/Users/$userId/FavoriteItems/$id")
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> =
        paged(null, PLAYLIST_TYPE).map {
            Playlist(
                id = it.id,
                name = it.name,
                coverArtId = it.imageId(),
                createdAt = jellyfinDateMs(it.dateCreated),
                updatedAt = jellyfinDateMs(it.dateCreated),
                trackIds = emptyList(),
            )
        }

    override suspend fun getPlaylist(id: String): Playlist {
        val summary = getPlaylists().firstOrNull { it.id == id }
        val trackIds = getPlaylistTracks(id).map { it.id }
        return summary?.copy(trackIds = trackIds)
            ?: Playlist(id, "", null, 0L, 0L, trackIds)
    }

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        playlistEntries(id).map { it.toTrack() }

    /** The raw entries, which carry the playlist-entry ids a removal needs. */
    private suspend fun playlistEntries(id: String): List<JellyfinItem> =
        fetch<JellyfinItems>(
            "/Playlists/$id/Items",
            listOf("userId" to userId, "Fields" to FIELDS),
        ).items

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? = runCatching {
        val payload = buildJsonObject {
            put("Name", name)
            put("UserId", userId)
            put("MediaType", "Audio")
            put(
                "Ids",
                buildJsonArray { songIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
        val response = http.post(url("/Playlists")) {
            jellyfinHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        if (!response.status.isSuccess()) return@runCatching null
        json.decodeFromString<JellyfinNamed>(response.bodyAsText()).id.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Renaming needs the whole item back, not just the new name: Jellyfin's
     * update takes a full record and drops anything missing from it. Servers
     * before 10.9 refuse the call entirely, leaving the name unchanged.
     */
    override suspend fun renamePlaylist(id: String, name: String) {
        send(
            "POST",
            "/Playlists/$id",
            payload = buildJsonObject {
                put("Name", name)
                put("Ids", buildJsonArray { })
            },
        )
    }

    override suspend fun deletePlaylist(id: String) {
        send("DELETE", "/Items/$id")
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        send("POST", "/Playlists/$id/Items", listOf("ids" to songId, "userId" to userId))
    }

    /**
     * Jellyfin removes a playlist entry by the entry's own id, not the
     * song's, since the same song can appear twice. Entry ids are re-read
     * here rather than remembered, since [index] is into the list as last read.
     */
    override suspend fun removeFromPlaylistAt(id: String, index: Int) {
        val entries = runCatching { playlistEntries(id) }.getOrDefault(emptyList())
        val entryId = entries.getOrNull(index)?.playlistItemId ?: return
        send("DELETE", "/Playlists/$id/Items", listOf("entryIds" to entryId))
    }

    /**
     * Reordering is one move at a time; Jellyfin has no bulk reorder call.
     * Entries are re-read after each move rather than computed up front.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        orderedSongIds.forEachIndexed { target, songId ->
            val entries = runCatching { playlistEntries(id) }.getOrDefault(emptyList())
            val at = entries.indexOfFirst { it.id == songId }
            if (at < 0 || at == target) return@forEachIndexed
            val entryId = entries[at].playlistItemId ?: return@forEachIndexed
            send("POST", "/Playlists/$id/Items/$entryId/Move/$target")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    companion object {
        /**
         * The original file, or anything the transcoder will produce. The
         * whole list is offered because Jellyfin's universal endpoint
         * honours the container and codec it's given, unlike Plex.
         */
        val STREAM_FORMATS: List<StreamFormat> = StreamFormat.entries.toList()

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        private const val ALBUM_TYPE = "MusicAlbum"
        private const val TRACK_TYPE = "Audio"
        private const val PLAYLIST_TYPE = "Playlist"

        /** Jellyfin's key for the library scan, used to tell whether one is running. */
        private const val SCAN_TASK = "RefreshLibrary"

        /**
         * The extras a music library needs, which Jellyfin leaves out unless
         * asked. Without these an album has no year, no genre, and no track
         * count, and a compilation's tracks all read as being by its album
         * artist.
         */
        private const val FIELDS =
            "Genres,DateCreated,PremiereDate,ChildCount,AlbumArtists,ArtistItems,People," +
                "NormalizationGain"

        private const val MP3_CONTAINER = "mp3"
        private const val COVER_QUALITY = 90

        /**
         * What to ask the server to send, and what to call it once it does.
         * Opus travels in an Ogg container, so the container and codec names
         * differ; `container=opus` gets a file nothing will open.
         */
        private val CONTAINERS: Map<StreamFormat, String> = mapOf(
            StreamFormat.MP3 to "mp3",
            StreamFormat.OPUS to "ogg",
            StreamFormat.FLAC to "flac",
        )
        private val CODECS: Map<StreamFormat, String> = mapOf(
            StreamFormat.MP3 to "mp3",
            StreamFormat.OPUS to "opus",
            StreamFormat.FLAC to "flac",
        )

        /**
         * Ties this install to its sessions on the server. Stable rather
         * than generated per launch, so Jellyfin's dashboard doesn't fill
         * with devices that played one track and vanished.
         */
        const val DEVICE_ID = "com.garado.amp"

        /**
         * Jellyfin's own authorization scheme, which every request carries.
         * `Device` deliberately stays the hardware name regardless of
         * source, since two sources here are still the same phone.
         */
        fun authorization(product: String? = null, token: String? = null): String = buildString {
            append("MediaBrowser ")
            append("Client=\"${product ?: "Amp"}\", ")
            append("Device=\"Light Phone III\", ")
            append("DeviceId=\"$DEVICE_ID\", ")
            append("Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) append(", Token=\"$token\"")
        }
    }
}

/**
 * The cover id for an item, or null when it hasn't got one. Prefers the
 * album's image over the track's own, even though a track with embedded art
 * reports a Primary image too, so covers are cached once per album rather
 * than once per song.
 */
fun JellyfinItem.imageId(): String? = when {
    !albumPrimaryImageTag.isNullOrBlank() && !albumId.isNullOrBlank() -> albumId
    imageTags.containsKey("Primary") -> id
    else -> null
}

internal fun JellyfinItem.toAlbum(): Album = Album(
    id = id,
    title = name,
    artist = albumArtist ?: albumArtists.firstOrNull()?.name ?: "Unknown Artist",
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    songCount = childCount ?: 0,
    year = productionYear,
    releaseDate = jellyfinDateNumber(premiereDate),
    createdMs = jellyfinDateMs(dateCreated),
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
)

internal fun JellyfinItem.toTrack(): Track = Track(
    id = id,
    title = name,
    // The track's own performers, not the record's: on a compilation the
    // album artist is "Various Artists" for every row.
    artist = artists.firstOrNull()
        ?: artistItems.firstOrNull()?.name
        ?: albumArtist
        ?: "Unknown Artist",
    album = album ?: "Unknown Album",
    albumArtist = albumArtist ?: albumArtists.firstOrNull()?.name ?: "Unknown Artist",
    albumId = albumId,
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    trackNumber = indexNumber,
    discNumber = parentIndexNumber,
    year = productionYear,
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
    // Jellyfin files a composer as a credited person rather than a tag; see
    // JellyfinItem.people.
    composer = people.filter { it.type.equals("Composer", ignoreCase = true) }
        .joinToString("; ") { it.name }
        .trim(),
    gainDb = normalizationGain,
)

class JellyfinException(message: String) : Exception(message)
