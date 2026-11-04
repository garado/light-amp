package com.garado.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Plex Media Server's music library, behind the same [MusicServer] interface
 * Navidrome uses.
 *
 * Not supported: favourites (Plex has only star ratings, no separate "loved"
 * flag), popular songs (no reliable per-artist ranking via the API), and
 * lyrics (only present when a track happens to carry a lyric stream). See the
 * capability flags on [MusicSource].
 */
class PlexClient(
    /** A server connection URI, e.g. `http://192.168.1.10:32400`. */
    private val baseUrl: String,
    private val token: String,
    /** Needed to build the URIs that add items to a playlist. */
    private val machineIdentifier: String = "",
    /** Overrides `X-Plex-Product`/`X-Plex-Device`; null keeps the app's defaults. */
    private val product: String? = null,
) : MusicServer {

    private val identityHeaders: List<Pair<String, String>> = plexIdentity(product)

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    /** Serialises requests aimed at a player; see [companionXml]. */
    private val playerLock = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private suspend fun fetch(path: String, params: List<Pair<String, String>> = emptyList()): PlexResponse =
        decode(rawBody(path, params))

    /** The body as text, for the one answer whose shape isn't declared. See [stationKeyFor]. */
    private suspend fun rawBody(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.get(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    /** A POST whose answer matters: creating a play queue hands its items back. */
    private suspend fun rawPost(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.post(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    private fun decode(text: String): PlexResponse =
        if (text.isBlank()) PlexResponse() else json.decodeFromString(text)

    private suspend fun send(method: String, path: String, params: List<Pair<String, String>> = emptyList()) {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        runCatching {
            when (method) {
                "GET" -> http.get(url) { plexHeaders() }
                "POST" -> http.post(url) { plexHeaders() }
                "PUT" -> http.put(url) { plexHeaders() }
                "DELETE" -> http.delete(url) { plexHeaders() }
                else -> error("unsupported method $method")
            }
        }
    }

    /** Like [send], but says whether the server took it. */
    private suspend fun sendChecked(path: String, params: List<Pair<String, String>> = emptyList()): Boolean {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        return runCatching { http.get(url) { plexHeaders() }.status.isSuccess() }.getOrDefault(false)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders() {
        header("X-Plex-Token", token)
        header("Accept", "application/json")
        identityHeaders.forEach { (k, v) -> header(k, v) }
    }

    override suspend fun ping() {
        // Any authenticated endpoint works; sections is the cheapest that
        // confirms both the address and the token.
        fetch("/library/sections")
    }

    /**
     * The server's own id, used to build playlist URIs. Comes free with a
     * linked account; a server reached by address and token must request it.
     */
    suspend fun identity(): String? =
        runCatching { fetch("/identity").container.machineIdentifier }.getOrNull()

    // --- Browsing ------------------------------------------------------------

    override suspend fun getMusicFolders(): List<MusicFolder> =
        fetch("/library/sections").container.directories
            .filter { it.type == "artist" }
            .map { MusicFolder(id = it.key, name = it.title) }

    /**
     * Every album, from one section or all music sections.
     * Plex section types: 8 artist, 9 album, 10 track.
     */
    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> {
        val sections = musicFolderId?.let { listOf(it) } ?: getMusicFolders().map { it.id }
        return sections.flatMap { section -> albumsInSection(section) }.distinctBy { it.id }
    }

    /**
     * One section's albums, fetched a page at a time. Plex caps large
     * responses without saying so beyond `totalSize`, so paging is required
     * for a big library. An explicit sort is required too: without one, the
     * server can reorder rows between page requests, dropping or repeating
     * albums across the page boundary.
     */
    private suspend fun albumsInSection(section: String): List<Album> =
        pagedSection(section, ALBUM_TYPE).map { it.toAlbum() }

    /** Everything of one type in a section, following the pages to the end. */
    private suspend fun pagedSection(section: String, type: String): List<PlexMetadata> =
        paged(
            "/library/sections/$section/all",
            listOf("type" to type, "sort" to "titleSort:asc"),
        )

    /**
     * A container's every item, fetched a page at a time (see [pagedSection]
     * for why Plex is never asked for everything at once). Also used for
     * playlists, whose per-track media descriptions can make a single
     * response too large to hold in memory. No `sort` is added, since a
     * playlist's order is its own.
     */
    private suspend fun paged(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): List<PlexMetadata> {
        val items = mutableListOf<PlexMetadata>()
        var start = 0
        while (true) {
            val container = fetch(
                path,
                params + listOf(
                    "X-Plex-Container-Start" to start.toString(),
                    "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                ),
            ).container
            items += container.metadata
            val total = container.totalSize ?: container.size
            start += container.metadata.size
            if (container.metadata.isEmpty() || start >= total) break
        }
        return items
    }

    /**
     * Triggers Plex's library scan for new files. Uses the plain refresh, not
     * `force=1`, which walks every file and is much slower. Does nothing on a
     * server this token doesn't administer (e.g. a shared library).
     */
    override suspend fun startServerScan(musicFolderId: String?): Boolean {
        val sections = musicFolderId?.let { listOf(it) }
            ?: runCatching { getMusicFolders().map { it.id } }.getOrDefault(emptyList())
        if (sections.isEmpty()) return false
        // Scanning is an owner privilege; a refusal must be reported, since it
        // looks identical to a scan that found nothing.
        return sections.map { sendChecked("/library/sections/$it/refresh") }.any { it }
    }

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch("/library/sections").container.directories
            .filter { musicFolderId == null || it.key == musicFolderId }
            .any { it.isRefreshing }
    }.getOrDefault(false)

    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> {
        val sections = musicFolderId?.let { listOf(it) } ?: getMusicFolders().map { it.id }
        return sections
            .flatMap { pagedSection(it, ARTIST_TYPE) }
            .map { ArtistRef(id = it.ratingKey, name = it.title, imageId = it.thumb) }
            .distinctBy { it.id }
    }

    /**
     * An artist's best-known songs, using the same query as PlexAmp's Popular
     * Tracks. Ranked by `ratingCount` (Plex's listener-count metadata, not
     * local play count). Compilations and live albums are excluded, and
     * results are grouped by title. Returns empty if the library's metadata
     * carries no rating counts.
     */
    override suspend fun getTopSongs(artistName: String, count: Int): List<Track> {
        val (section, key) = artistLocation(artistName) ?: return emptyList()
        return runCatching {
            fetch(
                "/library/sections/$section/all",
                listOf(
                    "type" to TRACK_TYPE,
                    "artist.id" to key,
                    // `!` is "not one of", `>>` is "greater than".
                    "album.subformat!" to "Compilation,Live",
                    "group" to "title",
                    "ratingCount>>" to "0",
                    "sort" to "ratingCount:desc",
                    "limit" to (if (count > 0) count else TOP_SONGS_LIMIT).toString(),
                ),
            ).container.metadata.map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    /** Artist name to the section it lives in and its rating key. */
    private val artistKeys = mutableMapOf<String, Pair<String, String>>()
    private val artistKeyLock = Mutex()

    private suspend fun artistLocation(name: String): Pair<String, String>? =
        artistKeyLock.withLock {
            if (artistKeys.isEmpty()) {
                val sections = runCatching { getMusicFolders() }.getOrDefault(emptyList())
                for (section in sections) {
                    runCatching { pagedSection(section.id, ARTIST_TYPE) }
                        .getOrDefault(emptyList())
                        .forEach { artistKeys.putIfAbsent(it.title, section.id to it.ratingKey) }
                }
            }
            artistKeys[name]
        }

    override suspend fun getAlbumTracks(albumId: String): List<Track> =
        fetch("/library/metadata/$albumId/children").container.metadata.map { it.toTrack() }

    /**
     * Plex's "songs like this one": either the station built for the track,
     * or the tracks it judges nearest via sonic analysis (a Plex Pass
     * feature). Falls back from track station, to nearest tracks, to the
     * artist's station. Results are logged under the `AmpRadio` tag. Empty if
     * the server has neither.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> {
        val limit = if (count > 0) count else RADIO_DEFAULT
        val own = runCatching { stationTracks(songId, "track") }
            .onFailure { android.util.Log.w(RADIO_TAG, "plex track station failed: ${it.message}") }
            .getOrDefault(StationAnswer())
        if (own.tracks.isNotEmpty()) return own.tracks.take(limit)
        val nearest = runCatching { nearestTracks(songId, limit) }
            .onFailure { android.util.Log.w(RADIO_TAG, "plex nearest failed: ${it.message}") }
            .getOrDefault(emptyList())
        android.util.Log.i(RADIO_TAG, "plex nearest: ${nearest.size} tracks for $songId")
        if (nearest.isNotEmpty()) return nearest
        val artist = own.grandparentRatingKey ?: return emptyList()
        // A station returns a small batch and expects position reports to
        // refill it. This client doesn't report position, so it asks
        // repeatedly (each ask mints a fresh station) and merges the results.
        val merged = LinkedHashMap<String, Track>()
        for (ask in 1..STATION_ASKS) {
            val answer = runCatching { stationTracks(artist, "artist") }
                .onFailure { android.util.Log.w(RADIO_TAG, "plex artist station failed: ${it.message}") }
                .getOrDefault(StationAnswer())
            val before = merged.size
            answer.tracks.forEach { merged.putIfAbsent(it.id, it) }
            if (merged.size == before || merged.size >= limit) break
        }
        android.util.Log.i(RADIO_TAG, "plex artist radio: ${merged.size} tracks after up to $STATION_ASKS asks")
        return merged.values.take(limit)
    }

    /** What asking an item for its station came to, plus the artist it named. */
    private class StationAnswer(
        val tracks: List<Track> = emptyList(),
        val grandparentRatingKey: String? = null,
    )

    /** An item's station, read once as a play queue; no tracks when it has none. */
    private suspend fun stationTracks(ratingKey: String, what: String): StationAnswer {
        val text = rawBody("/library/metadata/$ratingKey", listOf("includeStations" to "1"))
        val tree = json.parseToJsonElement(text)
        val stationKey = findStationKey(tree)
        val artistKey = findString(tree, "grandparentRatingKey")
        android.util.Log.i(RADIO_TAG, "plex $what $ratingKey: station=${stationKey != null}, artist=$artistKey")
        if (stationKey == null) return StationAnswer(grandparentRatingKey = artistKey)
        val machine = machineIdentifier.ifBlank { identity().orEmpty() }
        if (machine.isBlank()) {
            android.util.Log.w(RADIO_TAG, "plex: no machine identifier, can't build the queue")
            return StationAnswer(grandparentRatingKey = artistKey)
        }
        // `continuous` would let Plex keep refilling the queue, but only for
        // clients that report position back; this one reads once instead.
        val queueText = rawPost(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to "server://$machine/$LIBRARY_IDENTIFIER$stationKey",
                "continuous" to "1",
                "shuffle" to "0",
                "repeat" to "0",
                "includeChapters" to "0",
            ),
        )
        var tracks = decode(queueText).container.metadata.filter { it.type == "track" }.map { it.toTrack() }
        val queueTree = json.parseToJsonElement(queueText)
        val queueId = findString(queueTree, "playQueueID")
        android.util.Log.i(
            RADIO_TAG,
            "plex $what station queue $queueId: ${tracks.size} tracks, " +
                "total=${findString(queueTree, "playQueueTotalCount")}",
        )
        if (queueId != null) {
            val wider = runCatching {
                fetch("/playQueues/$queueId", listOf("window" to "100", "includeChapters" to "0"))
                    .container.metadata.filter { it.type == "track" }.map { it.toTrack() }
            }.getOrDefault(emptyList())
            android.util.Log.i(RADIO_TAG, "plex queue $queueId re-read, window=100: ${wider.size} tracks")
            if (wider.size > tracks.size) tracks = wider
        }
        return StationAnswer(tracks, artistKey)
    }

    /** Sonically nearest tracks, python-plexapi's `sonicallySimilar`. */
    private suspend fun nearestTracks(ratingKey: String, limit: Int): List<Track> =
        fetch("/library/metadata/$ratingKey/nearest", listOf("limit" to limit.toString()))
            .container.metadata.filter { it.type == "track" }.map { it.toTrack() }

    /**
     * Walked rather than declared: Plex nests its Stations block differently
     * between XML and JSON, and the one stable fact is a playlist entry whose
     * key says `/station/`. The first such key is the track's own station.
     */
    /** The first string under [name] anywhere in the tree. */
    private fun findString(node: JsonElement, name: String): String? = when (node) {
        is JsonObject -> (node[name] as? JsonPrimitive)?.contentOrNull
            ?: node.values.firstNotNullOfOrNull { findString(it, name) }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, name) }
        else -> null
    }

    private fun findStationKey(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val key = (node["key"] as? JsonPrimitive)?.contentOrNull
            if (key != null && "/station/" in key) {
                key
            } else {
                node.values.firstNotNullOfOrNull { findStationKey(it) }
            }
        }
        is JsonArray -> node.firstNotNullOfOrNull { findStationKey(it) }
        else -> null
    }

    // --- Media ---------------------------------------------------------------

    /**
     * Plex's universal transcoder, keyed by metadata id rather than a file
     * path. `offset` seeds the transcode that many seconds in.
     *
     * Doesn't serve original quality (see the [Track] overload): a
     * direct-play request here still returns the original bytes behind a
     * `.mp3` URL, misleading anything that sniffs the extension instead of
     * the content type.
     *
     * This endpoint always transcodes to MP3, regardless of the requested
     * format. [MusicSource.streamFormats] offers a Plex source only the
     * original file and MP3 for this reason.
     */
    override suspend fun prepareStream(
        songId: String,
        format: StreamFormat,
        sessionId: String?,
    ): Boolean {
        // A blank sessionId skips the decision call, since Plex only
        // enforces this against sessions it's tracking.
        if (sessionId.isNullOrBlank()) return true
        return sendChecked("/music/:/transcode/universal/decision", transcodeParams(songId, format, 0, sessionId))
    }

    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        val query = transcodeParams(songId, format, timeOffsetSeconds, sessionId)
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
    }

    /**
     * Transcode request parameters. Must match between [prepareStream] and
     * the stream URL; any difference describes a different playback, leaving
     * the stream without a matching decision.
     */
    private fun transcodeParams(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        sessionId: String?,
    ): List<Pair<String, String>> {
        val original = format == StreamFormat.RAW
        return buildList {
            add("path" to "/library/metadata/$songId")
            add("mediaIndex" to "0")
            add("partIndex" to "0")
            add("protocol" to "http")
            add("directPlay" to if (original) "1" else "0")
            add("directStream" to "1")
            add("hasMDE" to "1")
            if (timeOffsetSeconds > 0) add("offset" to timeOffsetSeconds.toString())
            format.maxBitRate?.let { add("musicBitrate" to it.toString()) }
            // Plex tears down the previous holder of a transcode identifier,
            // so reusing a session id (e.g. for a concurrent download) kills
            // the other playback with a 400. Callers must mint a fresh id per
            // playback (see PlaybackController).
            if (!sessionId.isNullOrBlank()) add("X-Plex-Session-Identifier" to sessionId)
            add("X-Plex-Token" to token)
            identityHeaders.forEach { (k, v) -> add(k to v) }
        }
    }

    /**
     * Original quality straight from the file, when the path is known.
     * No transcode: Plex serves the real container and content type, so a
     * FLAC arrives as FLAC with its seek table intact.
     */
    override fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        if (format != StreamFormat.RAW || track.streamPath.isBlank()) {
            return streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength, sessionId)
        }
        // Served whole and direct, with no transcode session to attach sessionId to.
        return baseUrl.trimEnd('/') + track.streamPath +
            (if (track.streamPath.contains('?')) "&" else "?") +
            "download=0&X-Plex-Token=" + enc(token)
    }

    /**
     * A copy to keep, rather than a stream to play. The original file needs
     * no transcode. Anything else uses `download=1` to get a complete file
     * instead of the segmented live encode `start.mp3` produces, which has
     * no Xing header and leaves players unable to read its real duration.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String {
        if (format == StreamFormat.RAW && track.streamPath.isNotBlank()) {
            return streamUrl(track, format, estimateContentLength = false)
        }
        val query = (transcodeParams(track.id, format, 0, null) + ("download" to "1"))
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
    }

    /**
     * Words for a song, where the library has them. Plex imports an `.lrc`
     * beside a track as a subtitle stream, so lyrics require a separate
     * metadata request to find.
     */
    override suspend fun getLyrics(songId: String): Lyrics? {
        val streams = runCatching { fetch("/library/metadata/$songId") }.getOrNull()
            ?.container?.metadata?.firstOrNull()
            ?.media?.flatMap { it.parts }?.flatMap { it.streams }
            .orEmpty()
        val stream = streams.firstOrNull {
            it.streamType == LYRIC_STREAM && !it.key.isNullOrBlank()
        } ?: return null
        val raw = runCatching {
            val response = http.get(baseUrl.trimEnd('/') + stream.key) {
                // Must send Accept: */* here, not plexHeaders()'s
                // application/json. This endpoint serves the lyric file
                // itself, and Plex 404s on the mismatch.
                header("X-Plex-Token", token)
                header("Accept", "*/*")
                identityHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        // Falls back to plain lines when the file has no timestamps.
        LyricsRepository.parseLrc(raw)?.let { return it }
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.isEmpty()) null else Lyrics(lines.map { LyricLine(null, it) }, synced = false)
    }

    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    /** Plex hands out cover paths, not ids; the token rides along in the URL. */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return baseUrl.trimEnd('/') + coverArtId +
            (if (coverArtId.contains('?')) "&" else "?") + "X-Plex-Token=" + enc(token)
    }

    /**
     * The cover through Plex's own image resizer. `upscale=0` keeps a small
     * original from being blown up and scaled back down again.
     */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        val params = listOf(
            "width" to maxSizePx.toString(),
            "height" to maxSizePx.toString(),
            "minSize" to "1",
            "upscale" to "0",
            "url" to coverArtId,
            "X-Plex-Token" to token,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/photo/:/transcode?" + params
    }

    // --- Writing back --------------------------------------------------------

    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        // Plex's now-playing notice is [reportTimeline], not this call.
        // Reporting a play on start would count every track skipped past.
        if (!submission) return
        send(
            "GET",
            "/:/scrobble",
            listOf("key" to songId, "identifier" to LIBRARY_IDENTIFIER),
        )
    }

    /**
     * Heartbeat for Plex's "Now Playing" view (`/status/sessions`). Without
     * it, Plex has no session object to show. `time`/`duration` are
     * milliseconds here, unlike most calls in this client, which use seconds.
     */
    override suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) {
        val params = buildList {
            add("ratingKey" to songId)
            add("key" to "/library/metadata/$songId")
            add("identifier" to LIBRARY_IDENTIFIER)
            add("state" to state.plexValue)
            add("time" to positionMs.coerceAtLeast(0L).toString())
            if (durationMs > 0) add("duration" to durationMs.toString())
            add("hasMDE" to "1")
            add("X-Plex-Session-Identifier" to sessionId)
        }
        send("GET", "/:/timeline", params)
    }

    private val TimelineState.plexValue: String
        get() = when (this) {
            TimelineState.PLAYING -> "playing"
            TimelineState.PAUSED -> "paused"
            TimelineState.STOPPED -> "stopped"
            TimelineState.BUFFERING -> "buffering"
        }

    /** Plex rates 0–10; the app's 0–100 scale divides by ten to fit. */
    override suspend fun setRating(id: String, rating: Int): Boolean {
        send(
            "PUT",
            "/:/rate",
            listOf(
                "key" to id,
                "identifier" to LIBRARY_IDENTIFIER,
                "rating" to (rating.coerceIn(0, 100) / 10).toString(),
            ),
        )
        return true
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> =
        fetch("/playlists", listOf("playlistType" to "audio")).container.metadata.map {
            Playlist(
                id = it.ratingKey,
                name = it.title,
                coverArtId = it.composite ?: it.thumb,
                createdAt = (it.addedAt ?: 0L) * 1000L,
                updatedAt = (it.updatedAt ?: it.addedAt ?: 0L) * 1000L,
                trackIds = emptyList(),
                readOnly = it.isSmart,
            )
        }

    override suspend fun getPlaylist(id: String): Playlist {
        val summary = getPlaylists().firstOrNull { it.id == id }
        val trackIds = getPlaylistTracks(id).map { it.id }
        return summary?.copy(trackIds = trackIds)
            ?: Playlist(id, "", null, 0L, 0L, trackIds)
    }

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        paged("/playlists/$id/items").map { it.toTrack() }

    /**
     * Plex has no call for an empty playlist; a request needs a `uri` naming
     * its contents. See [MusicServer.createPlaylist].
     */
    override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        if (songIds.isEmpty()) return null
        val uri = itemUri(songIds.joinToString(","))
        val response = runCatching {
            http.post(
                baseUrl.trimEnd('/') +
                    "/playlists?type=audio&smart=0&title=${enc(name)}&uri=${enc(uri)}",
            ) { plexHeaders() }
        }.getOrNull() ?: return null
        val text = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            json.decodeFromString<PlexResponse>(text).container.metadata.firstOrNull()?.ratingKey
        }.getOrNull()
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        send("PUT", "/playlists/$id", listOf("title" to name))
    }

    override suspend fun deletePlaylist(id: String) {
        send("DELETE", "/playlists/$id")
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        send("PUT", "/playlists/$id/items", listOf("uri" to itemUri(songId)))
    }

    /**
     * Removes by playlist entry id, not song id, since the same song can
     * appear twice. Entry ids are re-read on each call rather than cached.
     */
    override suspend fun removeFromPlaylistAt(id: String, index: Int) {
        val entry = playlistItemIds(id).getOrNull(index) ?: return
        send("DELETE", "/playlists/$id/items/$entry")
    }

    /**
     * Plex can only move one entry after another; there's no call to set a
     * whole order at once. Walks the target order and moves each entry into
     * place, skipping entries already correct.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        val entries = playlistEntries(id)
        // Several entries can share a song id; take each in turn so a playlist
        // holding a song twice still moves the right one.
        val bySong = entries.groupBy { it.first }
            .mapValues { (_, v) -> v.map { it.second }.toMutableList() }
        val wanted = orderedSongIds.mapNotNull { songId ->
            bySong[songId]?.removeFirstOrNull()
        }
        var previous: Long? = null
        val current = entries.map { it.second }.toMutableList()
        for (entry in wanted) {
            val target = previous?.let { current.indexOf(it) + 1 } ?: 0
            val at = current.indexOf(entry)
            if (at != target) {
                send(
                    "PUT",
                    "/playlists/$id/items/$entry/move",
                    if (previous == null) emptyList() else listOf("after" to previous.toString()),
                )
                current.removeAt(at)
                current.add(target, entry)
            }
            previous = entry
        }
    }

    /** Each entry's `(songId, playlistItemID)`, in playlist order. */
    private suspend fun playlistEntries(id: String): List<Pair<String, Long>> =
        paged("/playlists/$id/items").mapNotNull { item ->
            item.playlistItemID?.let { item.ratingKey to it }
        }

    private suspend fun playlistItemIds(id: String): List<Long> =
        playlistEntries(id).map { it.second }

    /** `server://{machine}/com.plexapp.plugins.library/library/metadata/{id}`. */
    private fun itemUri(songId: String): String =
        "server://$machineIdentifier/$LIBRARY_IDENTIFIER/library/metadata/$songId"

    // --- Mapping -------------------------------------------------------------

    private fun PlexMetadata.toAlbum(): Album = Album(
        id = ratingKey,
        title = title,
        artist = parentTitle ?: "Unknown Artist",
        coverArtId = thumb,
        durationMs = duration ?: 0L,
        songCount = leafCount ?: 0,
        year = year,
        releaseDate = originallyAvailableAt?.replace("-", "")?.toLongOrNull() ?: 0L,
        createdMs = (addedAt ?: 0L) * 1000L,
        // Plex omits leafCount from this listing, so updatedAt (seconds on
        // the wire) is the only signal that an album changed.
        updatedMs = (updatedAt ?: 0L) * 1000L,
        playCount = viewCount ?: 0,
        lastPlayedMs = (lastViewedAt ?: 0L) * 1000L,
        rating = starsFrom(userRating),
        genre = genres.firstOrNull()?.tag.orEmpty(),
    )

    // --- Plex Companion: casting to another Plex player -----------------------
    //
    // Companion is Plex's remote-control protocol: a connected player accepts
    // `/player/...` commands relayed through the server and pulls the audio
    // from the server itself; Amp only steers. Unlike a DLNA renderer, a Plex
    // player holds the whole play queue and advances through it on its own.
    //
    // These endpoints answer XML, unlike the rest of the server, so they go
    // through [companionXml] and the attribute parser at the bottom of this file.

    /**
     * Companion-capable players, from two lists since neither has everyone:
     * the server's `/clients` only knows players discovered on its own LAN
     * (missing, e.g., the Apple TV app, which registers with plex.tv
     * instead). The account's resources cover those. The server relays
     * commands to either kind the same way.
     */
    suspend fun companionPlayers(): List<PlexPlayer> {
        val fromServer = runCatching {
            plexXmlElements(companionXml("/clients"), "Server").mapNotNull { attrs ->
                val id = attrs["machineIdentifier"] ?: return@mapNotNull null
                // Entries that can't take playback commands are controllers
                // or servers, not somewhere sound can go.
                if (!(attrs["protocolCapabilities"] ?: "").contains("playback")) return@mapNotNull null
                // The server's list names the player's own address and
                // Companion port; commands go there, not back through here.
                val host = attrs["address"] ?: attrs["host"] ?: return@mapNotNull null
                val port = attrs["port"]?.toIntOrNull() ?: COMPANION_PORT
                PlexPlayer(
                    id = id,
                    name = attrs["name"] ?: attrs["product"] ?: "Plex player",
                    product = attrs["product"].orEmpty(),
                    directUrl = "http://$host:$port",
                    // The server found it; it is who the server says.
                    identityKnown = true,
                )
            }
        }.getOrDefault(emptyList())
        val fromAccount = runCatching {
            val response = http.get("$PLEX_TV_RESOURCES") { plexHeaders() }
            if (!response.status.isSuccess()) {
                throw PlexException("plex.tv says ${response.status.value} for the device list")
            }
            val body = response.bodyAsText()
            val players = json.decodeFromString<List<PlexResource>>(body)
                .filter { it.provides.contains("player") && it.clientIdentifier.isNotBlank() }
            android.util.Log.i(
                "AmpPlex",
                "plex.tv lists ${players.size} player(s): " + players.joinToString {
                    "${it.name}(present=${it.presence}, connections=${it.connections.size})"
                },
            )
            players
                // Not filtered on plex.tv's `presence`, which can go stale.
                // Reachability is decided by whether an address answers.
                .mapNotNull { resource ->
                    // The local address first: this Wi-Fi, plain http, no
                    // round trip through Plex's relay. A player with no
                    // address is one nothing here can command, so it is not
                    // offered.
                    val direct = resource.connections
                        .filter { it.address.isNotBlank() && it.port > 0 }
                        .sortedByDescending { it.local }
                        .firstOrNull()
                        ?.let { "http://${it.address}:${it.port}" }
                        ?: return@mapNotNull null
                    PlexPlayer(
                        id = resource.clientIdentifier,
                        name = resource.name.ifBlank { resource.product.ifBlank { "Plex player" } },
                        product = resource.product,
                        directUrl = direct,
                    )
                }
        }.getOrElse {
            android.util.Log.i("AmpPlex", "plex.tv device list failed: ${it.message}")
            emptyList()
        }
        val seen = fromServer.map { it.id }.toSet()
        val merged = fromServer + fromAccount.filter { it.id !in seen }
        android.util.Log.i(
            "AmpPlex",
            "players: server=${fromServer.map { it.name }} " +
                "account=${fromAccount.map { "${it.name}@${it.directUrl ?: "no-address"}" }}",
        )
        return merged
    }

    /**
     * Create a server-side play queue over [trackIds], cued on [startId].
     * The queue belongs to the server; whoever plays it walks it themselves.
     */
    suspend fun createPlayQueue(trackIds: List<String>, startId: String, repeatAll: Boolean): PlexQueue? = runCatching {
        val mid = machineIdentifier.ifBlank { identity().orEmpty() }
        if (mid.isBlank()) {
            android.util.Log.i("AmpPlex", "no machine identifier - the server can't be named in a queue uri")
            return@runCatching null
        }
        android.util.Log.i("AmpPlex", "building a queue of ${trackIds.size} on $mid, starting at $startId")
        // A queue is built from this server's own rating keys. Ids from
        // another source name nothing here, and the server rejects the
        // whole batch without saying which id was bad.
        val alien = trackIds.filterNot { it.all(Char::isDigit) }
        if (alien.isNotEmpty()) {
            android.util.Log.i(
                "AmpPlex",
                "queue holds ${alien.size} track(s) that aren't Plex ids, e.g. ${alien.first()}",
            )
            return@runCatching null
        }
        val uri = "server://$mid/com.plexapp.plugins.library/library/metadata/" +
            trackIds.joinToString(",")
        val body = companionXml(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to uri,
                "key" to "/library/metadata/$startId",
                "shuffle" to "0",
                "repeat" to if (repeatAll) "1" else "0",
                "own" to "1",
            ),
            post = true,
        )
        queueFrom(body).also {
            if (it == null) android.util.Log.i("AmpPlex", "the server made a queue with no id in it")
        }
    }.getOrElse {
        android.util.Log.i("AmpPlex", "play queue refused: ${it.message}")
        null
    }

    /** The queue id, version and item places, out of any play-queue answer. */
    private fun queueFrom(body: String): PlexQueue? {
        val container = plexXmlElements(body, "MediaContainer").firstOrNull() ?: return null
        val id = container["playQueueID"] ?: return null
        val items = plexXmlElements(body, "Track").mapNotNull { t ->
            val rating = t["ratingKey"] ?: return@mapNotNull null
            val place = t["playQueueItemID"] ?: return@mapNotNull null
            rating to place
        }.toMap()
        return PlexQueue(id, items, container["playQueueVersion"])
    }

    /**
     * Re-reads a queue, asking for [window] items around the current one.
     * An edit's response carries only part of the queue, and an item whose
     * place isn't known can't be moved or removed. This asks for the full
     * pushed window to recover the places needed.
     */
    suspend fun readPlayQueue(queue: PlexQueue, window: Int): PlexQueue? = runCatching {
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}",
                listOf("window" to window.toString(), "own" to "1"),
            )
        )
    }.getOrElse {
        android.util.Log.i("AmpPlex", "re-reading the queue failed: ${it.message}")
        null
    }

    /**
     * Moves a track so it follows [afterTrackId], or to the front if null.
     * The server returns the queue as it now stands.
     */
    suspend fun movePlayQueueItem(queue: PlexQueue, trackId: String, afterTrackId: String?): PlexQueue? = runCatching {
        val place = queue.items[trackId] ?: return@runCatching null
        val after = afterTrackId?.let { queue.items[it] }
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}/items/$place/move",
                if (after != null) listOf("after" to after) else emptyList(),
                put = true,
            )
        )
    }.getOrNull()

    /** Drop a track's place from the queue. */
    suspend fun removePlayQueueItem(queue: PlexQueue, trackId: String): PlexQueue? = runCatching {
        val place = queue.items[trackId] ?: return@runCatching null
        queueFrom(companionXml("/playQueues/${queue.id}/items/$place", delete = true))
    }.getOrNull()

    /** Append [trackIds], or put them next when [next]. */
    suspend fun addToPlayQueue(queue: PlexQueue, trackIds: List<String>, next: Boolean): PlexQueue? = runCatching {
        if (trackIds.isEmpty()) return@runCatching null
        val mid = machineIdentifier.ifBlank { identity() ?: return@runCatching null }
        val uri = "server://$mid/com.plexapp.plugins.library/library/metadata/" + trackIds.joinToString(",")
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}",
                listOf("uri" to uri) + if (next) listOf("next" to "1") else emptyList(),
                put = true,
            )
        )
    }.getOrNull()

    /** Tell [target] its play queue changed underneath it. */
    suspend fun companionRefreshQueue(target: PlexPlayer, queue: PlexQueue, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/refreshPlayQueue",
            listOfNotNull(
                "playQueueID" to queue.id,
                queue.version?.let { "playQueueVersion" to it },
                "type" to "music",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "refreshPlayQueue refused: ${it.message}")
        false
    }

    /**
     * Whether the player at this address really is the one we mean.
     * An address isn't a stable identity: a DHCP lease can move, pointing
     * this at whatever answers there now. GDM replies carry their own
     * identity; this check is for the ones that don't.
     */
    suspend fun playerIsWhoWeThink(target: PlexPlayer): Boolean = runCatching {
        val reported = plexXmlElements(companionXml("/resources", target = target), "Player")
            .firstNotNullOfOrNull { it["machineIdentifier"] }
        if (reported == null) {
            // Nothing said either way. Not proof of an impostor, and refusing
            // on silence would be worse than the risk.
            android.util.Log.i("AmpPlex", "${target.name} didn't say who it is; going ahead")
            return@runCatching true
        }
        (reported == target.id).also {
            if (!it) android.util.Log.i("AmpPlex", "${target.directUrl} is $reported, not ${target.id}")
        }
    }.getOrElse {
        android.util.Log.i("AmpPlex", "couldn't check who ${target.name} is: ${it.message}")
        false
    }

    /**
     * Asks [target] to push its timeline to [port] instead of being polled
     * every second. See [PlexCompanionListener]. Polling remains the
     * fallback for a player that won't subscribe.
     */
    suspend fun companionSubscribe(target: PlexPlayer, port: Int, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/timeline/subscribe",
            listOf(
                "port" to port.toString(),
                "protocol" to "http",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "subscribe refused: ${it.message}")
        false
    }

    /** Stop the pushes started by [companionSubscribe]. */
    suspend fun companionUnsubscribe(target: PlexPlayer, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/timeline/unsubscribe",
            listOf("commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrDefault(false)

    /** Start [queue] on [target] at [startId]/[offsetMs]. True when the player took it. */
    suspend fun companionPlay(
        target: PlexPlayer,
        queue: PlexQueue,
        startId: String,
        offsetMs: Long,
        commandId: Int,
        window: Int = DEFAULT_QUEUE_WINDOW,
    ): Boolean = runCatching {
        val u = URI(baseUrl)
        val mid = machineIdentifier.ifBlank { identity().orEmpty() }
        companionXml(
            "/player/playback/playMedia",
            listOf(
                "key" to "/library/metadata/$startId",
                "offset" to offsetMs.coerceAtLeast(0L).toString(),
                "machineIdentifier" to mid,
                "protocol" to (u.scheme ?: "http"),
                "address" to u.host.orEmpty(),
                "port" to (if (u.port > 0) u.port else if (u.scheme == "https") 443 else 32400).toString(),
                // The window the player is told to fetch is the window it was
                // given; a smaller number here means it re-reads less of the
                // queue than we pushed.
                "containerKey" to "/playQueues/${queue.id}?window=$window&own=1",
                "token" to token,
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "playMedia refused: ${it.message}")
        false
    }

    /**
     * Jumps to a track within the queue the player already holds.
     * Unlike [companionPlay], which hands over a queue to fetch, this moves
     * inside what the player already has, avoiding a WAN refetch that can
     * time out.
     */
    suspend fun companionSkipTo(target: PlexPlayer, trackId: String, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/skipTo",
            listOf(
                "key" to "/library/metadata/$trackId",
                "type" to "music",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "skipTo refused: ${it.message}")
        false
    }

    /** A plain transport command: `play`, `pause`, `stop`, `skipNext`, `skipPrevious`. */
    suspend fun companionCommand(target: PlexPlayer, command: String, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/$command",
            listOf("type" to "music", "commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "$command refused: ${it.message}")
        false
    }

    suspend fun companionSeek(target: PlexPlayer, ms: Long, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/seekTo",
            listOf("offset" to ms.coerceAtLeast(0L).toString(), "type" to "music", "commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrDefault(false)

    /** `volume` 0-100 and/or `repeat` 0 off / 1 track / 2 queue. The player keeps what it understands. */
    suspend fun companionSetParameters(target: PlexPlayer, params: List<Pair<String, String>>, commandId: Int): Boolean =
        runCatching {
            companionXml(
                "/player/playback/setParameters",
                params + listOf("type" to "music", "commandID" to commandId.toString()),
                target = target,
            )
            true
        }.getOrDefault(false)

    /** The player's music timeline, or null when it can't be asked. */
    suspend fun companionTimeline(target: PlexPlayer, commandId: Int): PlexPlayerTimeline? = runCatching {
        plexTimelineFrom(
            companionXml(
                "/player/timeline/poll",
                listOf("wait" to "0", "commandID" to commandId.toString()),
                target = target,
            )
        )
    }.getOrNull()

    /**
     * A Companion request: to the player when one is named, to the server
     * otherwise (the client list and play queues are the server's).
     *
     * @param inBody Sends parameters as a form body instead of a query
     *   string. A play queue's `uri` can list many tracks, and percent-encoded
     *   that can be large enough for a reverse proxy to reject the request
     *   before Plex ever sees it.
     */
    private suspend fun companionXml(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        target: PlexPlayer? = null,
        post: Boolean = false,
        put: Boolean = false,
        delete: Boolean = false,
        inBody: Boolean = false,
    ): String {
        val host = target?.directUrl ?: baseUrl
        val encoded = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = host.trimEnd('/') + path + if (encoded.isEmpty() || inBody) "" else "?$encoded"
        val form: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            companionHeaders(target)
            if (inBody) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(encoded)
            }
        }
        // A player answers one caller at a time. The poll runs every second,
        // so without this lock, overlapping requests wedge the socket.
        val response = if (target != null) {
            playerLock.withLock {
                when {
                    post -> http.post(url, form)
                    put -> http.put(url, form)
                    delete -> http.delete(url) { companionHeaders(target) }
                    else -> http.get(url) { companionHeaders(target) }
                }
            }
        } else {
            when {
                post -> http.post(url, form)
                put -> http.put(url, form)
                delete -> http.delete(url) { companionHeaders(target) }
                else -> http.get(url) { companionHeaders(target) }
            }
        }
        if (!response.status.isSuccess()) {
            // Captured for the exception message; a 400 with no detail is hard to diagnose.
            val why = runCatching { response.bodyAsText() }.getOrDefault("").take(300).replace('\n', ' ')
            throw PlexException("Plex says ${response.status.value} for $path at $host — $why")
        }
        return response.bodyAsText()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.companionHeaders(target: PlexPlayer?) {
        header("X-Plex-Token", token)
        // XML on purpose: proxied player replies are XML whatever we ask for,
        // so asking for it everywhere keeps one parser.
        header("Accept", "application/xml")
        identityHeaders.forEach { (k, v) -> header(k, v) }
        if (target != null) {
            header("X-Plex-Target-Client-Identifier", target.id)
            // A player answering for itself checks who is asking: an unknown
            // controller with no provides header is refused before the command
            // is read.
            header("X-Plex-Provides", "controller")
        }
    }

    private fun PlexMetadata.toTrack(): Track = Track(
        id = ratingKey,
        // The track's own artist first: on a compilation grandparentTitle is
        // the album artist, and every song would read "Various Artists".
        artist = originalTitle ?: grandparentTitle ?: "Unknown Artist",
        title = title,
        album = parentTitle ?: "Unknown Album",
        albumArtist = grandparentTitle ?: "Unknown Artist",
        albumId = parentRatingKey,
        // The album's own thumb first: a track's thumb is usually the same
        // path, so keying the cache by album avoids caching it once per song.
        coverArtId = parentThumb ?: thumb,
        durationMs = duration ?: 0L,
        trackNumber = index,
        discNumber = parentIndex,
        year = year,
        playCount = viewCount ?: 0,
        lastPlayedMs = (lastViewedAt ?: 0L) * 1000L,
        rating = starsFrom(userRating),
        genre = genres.firstOrNull()?.tag.orEmpty(),
        streamPath = media.firstOrNull()?.parts?.firstOrNull()?.key.orEmpty(),
    )

    /** Plex's 0–10 back to the app's 0–100. */
    private fun starsFrom(rating: Float?): Int =
        rating?.let { (it * 10f).toInt().coerceIn(0, 100) } ?: 0

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    companion object {
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"

        /** Songs per radio when the caller leaves it to the server. */
        private const val RADIO_DEFAULT = 50
        private const val RADIO_TAG = "AmpRadio"

        /** How many fresh stations to deal from before calling the radio full. */
        private const val STATION_ASKS = 5

        /**
         * The original file, or MP3. Plex's universal music transcoder
         * (`start.mp3`) always produces MP3, so no other transcoded format is offered.
         */
        val STREAM_FORMATS: List<StreamFormat> = listOf(StreamFormat.RAW, StreamFormat.MP3)

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        private const val ARTIST_TYPE = "8"
        private const val ALBUM_TYPE = "9"
        private const val TRACK_TYPE = "10"

        /** What PlexAmp asks for when it shows Popular Tracks. */
        private const val TOP_SONGS_LIMIT = 100

        /** What a library calls an album that isn't by one artist. */

        /** Plex's stream types: 1 video, 2 audio, 3 subtitle, 4 lyrics. */
        private const val LYRIC_STREAM = 4

        /**
         * Identity headers sent on every request. The client identifier ties
         * a token to a device, so it's the app's own fixed id, not something
         * generated per launch. [product] overrides `X-Plex-Product` (the
         * name Plex shows a session as). `X-Plex-Device` stays the hardware
         * name regardless of per-source naming, so Plex can still tell
         * devices apart.
         */
        private const val PLEX_TV_RESOURCES = "https://plex.tv/api/v2/resources?includeHttps=1"

        /** Where a Plex player listens for Companion commands. */
        private const val COMPANION_PORT = 32500

        /** Queue items a player is asked to fetch when nobody says otherwise. */
        const val DEFAULT_QUEUE_WINDOW = 150

        fun plexIdentity(product: String? = null): List<Pair<String, String>> = listOf(
            "X-Plex-Client-Identifier" to "com.garado.amp",
            "X-Plex-Product" to (product ?: "Amp"),
            "X-Plex-Version" to "1.0.0",
            "X-Plex-Device" to "Light Phone III",
            // The friendly name, shown as a session's "Player" in Plex. If
            // missing, Plex falls back to the product name instead.
            "X-Plex-Device-Name" to "Light Phone III",
            "X-Plex-Platform" to "Android",
        )
    }
}

class PlexException(message: String) : Exception(message)

/**
 * A Companion-capable player, from the server's list or the account's.
 * Commands go to [directUrl] directly, never through the server: the server
 * 404s for a player it hasn't discovered on its own LAN (e.g. an Apple TV
 * registered via plex.tv instead). A player is listed only when reachable directly.
 */
data class PlexPlayer(
    /** The player's own client identifier, the command target. */
    val id: String,
    val name: String,
    val product: String,
    /** `http://host:32500`, the player's own address. */
    val directUrl: String,
    /**
     * Whether the device at [directUrl] confirmed its own identity.
     * True for GDM replies and the server's own client list. False for a
     * device known only via plex.tv, whose address may be stale.
     */
    val identityKnown: Boolean = false,
)

/**
 * A server-side play queue.
 * [items] maps a track id to its place in the queue (`playQueueItemID`),
 * which the edit endpoints address by. The same track can appear twice, so
 * edits target place, not song id.
 */
data class PlexQueue(
    val id: String,
    val items: Map<String, String> = emptyMap(),
    /**
     * The queue's version, bumped by Plex on every edit. Sent with a
     * refresh so the player re-reads the intended version instead of
     * racing a concurrent edit.
     */
    val version: String? = null,
)

/** What a player reports about its music playback. */
data class PlexPlayerTimeline(
    /** `playing`, `paused`, `stopped` or `buffering`. */
    val state: String,
    val timeMs: Long,
    val durationMs: Long,
    /** The playing track, in the server's terms: Amp's Plex track id. */
    val ratingKey: String?,
    /** 0-100 where the player reports one; null where volume isn't its to control. */
    val volume: Int?,
    /** The player's repeat: 0 off, 1 the track, 2 the queue. Null when unsaid. */
    val repeat: Int?,
    /** Whether the player is shuffling. Null when unsaid. */
    val shuffle: Boolean?,
    /**
     * What the player says it accepts (e.g. `playPause,stop,volume,seekTo`),
     * or null if unsaid.
     */
    val controllable: String?,
)

/**
 * The attributes of every `<tag ...>` in [xml], in document order.
 * Companion bodies are flat attribute lists, so a full XML parser isn't needed.
 */
internal fun plexXmlElements(xml: String, tag: String): List<Map<String, String>> {
    val out = mutableListOf<Map<String, String>>()
    var from = 0
    while (true) {
        val at = xml.indexOf("<$tag", from).takeIf { it >= 0 } ?: break
        // The character after the tag name has to end it, or "Timeline"
        // would also match "TimelineEntry".
        val after = xml.getOrNull(at + tag.length + 1)
        val end = xml.indexOf('>', at).takeIf { it >= 0 } ?: break
        if (after == null || after == ' ' || after == '>' || after == '/' || after == '\n' || after == '\t') {
            val attrs = ATTR.findAll(xml.substring(at, end)).associate {
                it.groupValues[1] to unescapeXml(it.groupValues[2])
            }
            out += attrs
        }
        from = end + 1
    }
    return out
}

private val ATTR = Regex("""([A-Za-z0-9_:-]+)="([^"]*)"""")

private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&amp;", "&")

/** The music `<Timeline>` out of a `/player/timeline/poll` body, if any. */
internal fun plexTimelineFrom(body: String): PlexPlayerTimeline? {
    val music = plexXmlElements(body, "Timeline").firstOrNull { it["type"] == "music" } ?: return null
    return PlexPlayerTimeline(
        state = music["state"] ?: "stopped",
        timeMs = music["time"]?.toLongOrNull() ?: 0L,
        durationMs = music["duration"]?.toLongOrNull() ?: 0L,
        ratingKey = music["ratingKey"]?.takeIf { it.isNotBlank() },
        volume = music["volume"]?.toIntOrNull(),
        controllable = music["controllable"]?.takeIf { it.isNotBlank() },
        repeat = music["repeat"]?.toIntOrNull(),
        shuffle = music["shuffle"]?.let { it == "1" || it.equals("true", ignoreCase = true) },
    )
}
