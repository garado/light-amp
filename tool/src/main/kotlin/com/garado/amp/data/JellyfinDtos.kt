package com.garado.amp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Just enough of Jellyfin's JSON to build the app's own models. Every field
 * is optional with a default, since Jellyfin omits what a library hasn't got
 * rather than sending nulls. Jellyfin counts time in 100-nanosecond ticks
 * throughout; see [TICKS_PER_MS].
 */
@Serializable
data class JellyfinItems(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    /** How many there are altogether, which is how paging knows when to stop. */
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("Type") val type: String = "",
    /** What a view holds; "music" is the one this app cares about. Only set on `/Users/{id}/Views` entries. */
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JellyfinNamed> = emptyList(),
    /**
     * The performers on this track, not the record's artist. A
     * compilation's every track carries the album artist "Various Artists".
     */
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JellyfinNamed> = emptyList(),
    @SerialName("Genres") val genres: List<String> = emptyList(),
    /** Jellyfin's loudness measurement in dB (10.9+), its ReplayGain stand-in. Absent until the server scans. */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
    /**
     * Everyone credited on the track, composers among them. Jellyfin has no
     * composer field of its own: a composer is a person with
     * [JellyfinPerson.type] "Composer". Only arrives when "People" is
     * requested; see FIELDS.
     */
    @SerialName("People") val people: List<JellyfinPerson> = emptyList(),
    /** Which images the item actually has; "Primary" is the cover. Jellyfin 404s at fetch time otherwise. */
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    /** An album's own cover, on a track that hasn't got one of its own. */
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /**
     * The playlist entry's own id, not the track's, since the same song can
     * sit in a playlist twice; see JellyfinClient.removeFromPlaylistAt.
     */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

/** An id-and-name pair, which Jellyfin uses wherever it references something. */
@Serializable
data class JellyfinNamed(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

/** A credited person; [type] is Jellyfin's PersonType, e.g. "Composer". */
@Serializable
data class JellyfinPerson(
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

/** What `/Users/AuthenticateByName` hands back. */
@Serializable
data class JellyfinAuthResult(
    @SerialName("User") val user: JellyfinNamed = JellyfinNamed(),
    @SerialName("AccessToken") val accessToken: String = "",
    @SerialName("ServerId") val serverId: String = "",
)

/** The public bits of `/System/Info/Public`, used to name a server. */
@Serializable
data class JellyfinPublicInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
)

/** One scheduled task, which is how a library scan reports that it is running. */
@Serializable
data class JellyfinTask(
    @SerialName("Key") val key: String = "",
    @SerialName("State") val state: String = "",
)

/** `/Audio/{id}/Lyrics`, present from Jellyfin 10.9. */
@Serializable
data class JellyfinLyrics(
    @SerialName("Lyrics") val lyrics: List<JellyfinLyricLine> = emptyList(),
)

@Serializable
data class JellyfinLyricLine(
    /** Ticks from the start, absent on an unsynced sheet. */
    @SerialName("Start") val start: Long? = null,
    @SerialName("Text") val text: String = "",
)

/** Jellyfin's clock: 100-nanosecond units, so ten thousand to the millisecond. */
const val TICKS_PER_MS = 10_000L

/**
 * A Jellyfin date to epoch millis, or 0 when there isn't one. Only the date
 * part is parsed; Jellyfin's ISO-8601 timestamps vary too much in format to
 * be worth a full parser for fields the app only sorts and displays by day.
 */
fun jellyfinDateMs(value: String?): Long {
    val text = value?.takeIf { it.length >= 10 } ?: return 0L
    val year = text.substring(0, 4).toIntOrNull() ?: return 0L
    val month = text.substring(5, 7).toIntOrNull() ?: return 0L
    val day = text.substring(8, 10).toIntOrNull() ?: return 0L
    return java.util.GregorianCalendar(
        java.util.TimeZone.getTimeZone("UTC"),
    ).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis
}

/** The same date as a sortable YYYYMMDD, which is how [Album.releaseDate] reads. */
fun jellyfinDateNumber(value: String?): Long {
    val text = value?.takeIf { it.length >= 10 } ?: return 0L
    val year = text.substring(0, 4).toIntOrNull() ?: return 0L
    val month = text.substring(5, 7).toIntOrNull() ?: return 0L
    val day = text.substring(8, 10).toIntOrNull() ?: return 0L
    return year * 10_000L + month * 100L + day
}
