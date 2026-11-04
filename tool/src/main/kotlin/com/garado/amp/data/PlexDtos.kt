package com.garado.amp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Just enough of Plex's JSON to build the app's own models.
 * Plex serves XML by default and JSON with `Accept: application/json`.
 * Every response is one `MediaContainer`. Fields are optional since Plex
 * omits absent values rather than sending null.
 */
@Serializable
data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable
data class PlexContainer(
    /** How many came back in *this* response. */
    val size: Int = 0,
    /** How many there are altogether, when the server pages the result. */
    val totalSize: Int? = null,
    @SerialName("machineIdentifier") val machineIdentifier: String? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

/** A library section: `type` is "artist" for a music library. */
@Serializable
data class PlexDirectory(
    val key: String = "",
    val type: String = "",
    val title: String = "",
    /**
     * Set while the server is scanning this section. A [JsonElement] because
     * Plex writes this inconsistently (`true` or `1`); as a Boolean, the
     * numeric form would silently read as false via `coerceInputValues`.
     */
    val refreshing: JsonElement? = null,
) {
    val isRefreshing: Boolean
        get() = (refreshing as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1")
}

@Serializable
data class PlexMetadata(
    /**
     * Set on a smart playlist, one Plex builds from a filter. A
     * [JsonElement] for the same reason as [PlexDirectory.refreshing]: Plex
     * writes `1` or `true` inconsistently.
     */
    val smart: JsonElement? = null,
    val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    /**
     * A track's own artist, sent only when it differs from the album artist.
     * On a compilation, [grandparentTitle] would put "Various Artists"
     * against every song instead.
     */
    val originalTitle: String? = null,
    /** The album, on a track; the artist, on an album. */
    val parentTitle: String? = null,
    /** The artist, on a track. */
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val year: Int? = null,
    val originallyAvailableAt: String? = null,
    /** Track number within its disc; disc number is [parentIndex] on a track. */
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    val leafCount: Int? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    /** A playlist's mosaic cover. */
    val composite: String? = null,
    val lastViewedAt: Long? = null,
    val viewCount: Int? = null,
    /** Plex rates 0-10; the app uses a 0-100 scale. */
    val userRating: Float? = null,
    /**
     * A track's position within a playlist, not its rating key: the same
     * song can appear in a playlist twice, and edits need the entry id,
     * not the song id.
     */
    val playlistItemID: Long? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
) {
    /** Plex builds this one from a filter, so its contents can't be edited. */
    val isSmart: Boolean
        get() = (smart as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1")
}

/** Plex's tag shape. Genres, moods, and styles all arrive as `{ "tag": "..." }`. */
@Serializable
data class PlexTag(val tag: String = "")

@Serializable
data class PlexMedia(
    val audioCodec: String? = null,
    val bitrate: Int? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val key: String = "",
    val container: String? = null,
    val size: Long? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

/**
 * One track of media inside a file. `streamType`: 1 video, 2 audio,
 * 3 subtitles, 4 lyrics.
 *
 * A lyric stream may have no file on disk (Plex's LyricFind agent supplies
 * most lyrics without one). No field distinguishes a fetchable stream from
 * one the server will 404 on; the only way to know is to ask.
 */
@Serializable
data class PlexStream(
    val id: Long = 0,
    val streamType: Int = 0,
    val key: String? = null,
    val format: String? = null,
    val codec: String? = null,
)

// --- plex.tv, for linking and for finding the user's servers ----------------

@Serializable
data class PlexPin(
    val id: Long = 0,
    val code: String = "",
    val authToken: String? = null,
)

@Serializable
data class PlexResource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val accessToken: String? = null,
    val owned: Boolean = false,
    /** "Plex for Apple TV", "Plexamp", etc. Which app the device is. */
    val product: String = "",
    /** Whether plex.tv believes the device is currently reachable. */
    val presence: Boolean = false,
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable
data class PlexConnection(
    val uri: String = "",
    val address: String = "",
    val port: Int = 0,
    val local: Boolean = false,
    val relay: Boolean = false,
)
