package com.garado.amp.data

/** A single playable song. Mirrors the fields the UI and playback layers need. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: String?,
    val coverArtId: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean = false,
    /** Rating 1-100, or 0 when unrated. */
    val rating: Int = 0,
    /** Tags, when the server sends them. Blank means this library has none. */
    val genre: String = "",
    val composer: String = "",
    /** Direct file path from the server (used by Plex). Blank means stream by id. */
    val streamPath: String = "",
    /** Loudness-normalisation gain in dB from the server, or null if none was provided. */
    val gainDb: Float? = null,
)

/**
 * Splits a multi-artist credit into individual names.
 *
 * Splits only on `;`. A comma can appear inside a single artist's name
 * (e.g. "Earth, Wind & Fire").
 */
fun Track.albumArtistNames(): List<String> {
    val raw = albumArtist.ifBlank { artist }
    val names = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return names.ifEmpty { listOfNotNull(raw.takeIf { it.isNotBlank() }) }
}

/** The first credited artist, the one a record is filed under. */
fun Track.primaryAlbumArtist(): String =
    albumArtistNames().firstOrNull() ?: albumArtist.ifBlank { artist }

/**
 * Splits a multi-valued tag (genre, composer) on comma or semicolon.
 * Unlike [albumArtistNames], a comma here is a real separator.
 */
fun splitTagValues(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** A library filter by genre and/or composer. Both apply together when set. */
data class TagFilter(val genre: String = "", val composer: String = "") {
    val isEmpty: Boolean get() = genre.isEmpty() && composer.isEmpty()
}

fun Track.hasGenre(value: String): Boolean =
    splitTagValues(genre).any { it.equals(value, ignoreCase = true) }

fun Track.hasComposer(value: String): Boolean =
    splitTagValues(composer).any { it.equals(value, ignoreCase = true) }

/** An album as listed by the server (songs are fetched separately on demand). */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val durationMs: Long,
    val songCount: Int,
    val year: Int?,
    // Sortable YYYYMMDD release date, 0 if the server doesn't send one.
    val releaseDate: Long = 0L,
    val createdMs: Long = 0L,
    /** When the server last changed this album, 0 if it doesn't say. */
    val updatedMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val liked: Boolean = false,
    /** Rating 1-100, or 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
)

/** Derived client-side by grouping tracks; the server is album-centric. */
data class Artist(
    val name: String,
    /** The server's own picture of them, when it has one. See ArtistRef. */
    val imageId: String? = null,
    val albumCount: Int,
    val trackCount: Int,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val liked: Boolean = false,
)

@kotlinx.serialization.Serializable
data class Playlist(
    val id: String,
    val name: String,
    val coverArtId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val trackIds: List<String>,
    /** True for server-managed playlists (e.g. Plex smart playlists). Shown but not editable. */
    val readOnly: Boolean = false,
)

/** Starred ids returned together by getStarred2. */
data class Starred(
    val songIds: Set<String>,
    val albumIds: Set<String>,
    // Artists are matched by name: the local Artist list is derived from track
    // tags and has no server ids.
    val artistNames: Set<String> = emptySet(),
)

/** A server-side artist entry, used to resolve ids for starring and artist art. */
data class ArtistRef(
    val id: String,
    val name: String,
    /** Cover id for their picture; format varies by server. */
    val imageId: String? = null,
)

/** A server library / music folder (Navidrome exposes each library as one). */
data class MusicFolder(val id: String, val name: String)

/** Streaming format. RAW streams the original file; the others transcode via Navidrome. */
enum class StreamFormat(val id: String, val maxBitRate: Int?) {
    MP3("mp3", 320),
    OPUS("opus", 192),
    FLAC("flac", null),
    RAW("raw", null);

    companion object {
        /** Default for both streaming and downloads: near-transparent quality at a low bitrate. */
        val DEFAULT = OPUS

        fun fromId(id: String?): StreamFormat =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class RepeatMode { OFF, TRACK, QUEUE }

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)
