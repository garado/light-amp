package com.garado.amp.data

import kotlinx.serialization.Serializable

/** A fresh source id: never reused, and it names the source's database file. */
fun newSourceId(): String =
    "src-" + System.currentTimeMillis().toString(36) + "-" + (0..0xffff).random().toString(16)

/** One of a Subsonic server's music folders, as last seen. */
@Serializable
data class SourceLibrary(val id: String, val name: String)

/** Where a source's music comes from. */
enum class SourceKind {
    /** A Subsonic-compatible server: Navidrome, Airsonic, Gonic, … */
    SUBSONIC,

    /** A Plex Media Server's music library. No favourites or popular songs; see the flags below. */
    PLEX,

    /** A Jellyfin server's music library. No star ratings or popular songs; see the flags below. */
    JELLYFIN,

    /**
     * The phone's own music folder, the same files the Light Music app plays.
     * No server behind it, so likes, ratings, play counts, scrobbles, and
     * playlists are all unavailable; see [MusicSource.supportsLikes] and its
     * neighbours.
     */
    LOCAL,
}

/**
 * One place the library can come from.
 * Sources are a list rather than a single server, so several can be configured
 * at once and switched between instantly; each keeps its own cache, downloads,
 * sort order, and library selection.
 */
@Serializable
data class MusicSource(
    /** Stable, generated once and never reused. Names this source's database file. */
    val id: String,
    val kind: SourceKind,
    /** What the Servers list calls it. Defaults to the server's host. */
    val name: String,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** What to stream on Wi-Fi. */
    val streamFormatId: String = StreamFormat.DEFAULT.id,
    /** What to stream on cellular. Null follows the Wi-Fi choice. */
    val cellularFormatId: String? = null,
    /** What downloads from this source are fetched as. Null means never chosen. */
    val downloadFormatId: String? = null,
    /** What gets downloaded without being asked for. */
    val offlineModeName: String? = null,
    /**
     * What this source was allowed on its own, before download budgets became
     * tool-wide. Kept only for [AppSettings.migrateDownloadLimit] to migrate;
     * nothing else reads it.
     */
    val downloadLimitBytes: Long? = null,
    /**
     * What this source reports itself as: Subsonic's `c=` client parameter, or
     * Plex's `X-Plex-Product`/`X-Plex-Device` headers. Blank means the app's
     * own default.
     */
    val playerName: String = "",
    /** Plex's `X-Plex-Token` or Jellyfin's access token, from signing in. */
    val token: String = "",
    /** Whose library this is, on a server that scopes one per user (Jellyfin). */
    val userId: String = "",
    /** Plex's server id, needed to build the URIs that fill a playlist. */
    val machineIdentifier: String = "",
    /**
     * Which build's track parsing filled this source's cache. A sync only
     * refetches an album whose song count changed, so a change to how a track
     * is parsed needs a bump to [LibraryRepository.TRACK_PARSER_GENERATION] to
     * force one refetch. Zero means filled before this field existed.
     */
    val parserGeneration: Int = 0,
    /** Subsonic music folder, or null for all of them. */
    val libraryId: String? = null,
    /** The server's music folders, cached from the last successful sync. */
    val libraries: List<SourceLibrary> = emptyList(),
    /**
     * Libraries kept off the Sources page, by id. `null` represents the "All
     * Libraries" row. Stored as what to hide, so a library added later shows
     * up by default.
     */
    val hiddenLibraryIds: List<String?> = emptyList(),
    /**
     * Every address Plex advertised for this server, best first. [baseUrl] is
     * whichever one answered when the server was linked, usually the LAN
     * address. The alternatives let a failed request retry against another.
     */
    val connections: List<String> = emptyList(),
) {
    /**
     * The formats this source can actually serve. Read from the class rather
     * than a live client, so settings screens work for a source that isn't
     * the one connected. Local music is never transcoded.
     */
    val streamFormats: List<StreamFormat> get() = when (kind) {
        SourceKind.SUBSONIC -> SubsonicClient.STREAM_FORMATS
        SourceKind.PLEX -> PlexClient.STREAM_FORMATS
        SourceKind.JELLYFIN -> JellyfinClient.STREAM_FORMATS
        SourceKind.LOCAL -> emptyList()
    }

    /**
     * Streaming quality, per connection. Falls back to the original file if
     * the chosen format isn't in [streamFormats]. Cellular follows Wi-Fi
     * until set otherwise.
     */
    val wifiFormat: StreamFormat get() = supported(streamFormatId)
    val cellularFormat: StreamFormat get() = supported(cellularFormatId ?: streamFormatId)
    val downloadFormat: StreamFormat get() = supported(downloadFormatId)

    val offlineMode: OfflineMode
        get() = offlineModeName?.let { name ->
            OfflineMode.entries.firstOrNull { it.name == name }
        } ?: OfflineMode.MANUAL

    /** The libraries this source offers on the Sources page, in order. */
    val visibleLibraries: List<SourceLibrary>
        get() = libraries.filterNot { it.id in hiddenLibraryIds }

    /** Whether the whole-server row is offered alongside the individual ones. */
    val showsAllLibraries: Boolean get() = null !in hiddenLibraryIds


    private fun supported(id: String?): StreamFormat {
        val wanted = StreamFormat.fromId(id)
        return if (streamFormats.isEmpty() || wanted in streamFormats) wanted else StreamFormat.RAW
    }

    /** The client that talks to this source, or null for the phone's own music. */
    fun toClient(): MusicServer? = when (kind) {
        SourceKind.LOCAL -> null
        SourceKind.SUBSONIC -> toConfig()?.let { SubsonicClient(it) }
        SourceKind.PLEX ->
            if (baseUrl.isBlank() || token.isBlank()) {
                null
            } else {
                PlexClient(baseUrl, token, machineIdentifier, playerName.ifBlank { null })
            }
        SourceKind.JELLYFIN ->
            if (baseUrl.isBlank() || token.isBlank() || userId.isBlank()) {
                null
            } else {
                JellyfinClient(baseUrl, token, userId, playerName.ifBlank { null })
            }
    }

    /** Null unless this is a Subsonic server with somewhere to point. */
    fun toConfig(): SubsonicConfig? =
        if (kind == SourceKind.SUBSONIC && baseUrl.isNotBlank()) {
            SubsonicConfig(baseUrl, username, password, playerName.ifBlank { null })
        } else {
            null
        }

    // What this kind of source can actually do. Read by the UI so a feature that
    // can't work is absent rather than present and broken: a heart on a local
    // file would write a like nothing can store and nothing can restore.
    /** Plex has ratings but no separate favourite; Jellyfin has favourites but no ratings. */
    val supportsLikes: Boolean get() = kind == SourceKind.SUBSONIC || kind == SourceKind.JELLYFIN
    val supportsRatings: Boolean get() = kind == SourceKind.SUBSONIC || kind == SourceKind.PLEX

    /** A radio seeded by one song. See [MusicServer.getSimilarSongs]. */
    val supportsRadio: Boolean get() = kind != SourceKind.LOCAL
    /** The phone keeps its own, as m3u8 files. See [LocalPlaylists]. */
    val supportsPlaylists: Boolean get() = true

    /** Plex can't create an empty playlist; songs must be added at creation. */
    val supportsEmptyPlaylists: Boolean get() = kind != SourceKind.PLEX

    /** Downloading only means something when the audio is somewhere else. */
    val supportsDownloads: Boolean get() = kind != SourceKind.LOCAL

    /** Separate libraries to browse. Local music is one folder by definition. */
    val supportsLibraries: Boolean get() = kind != SourceKind.LOCAL

    companion object {
        /** The id the phone's own music always has. There can only be one. */
        const val LOCAL_ID = "local"

        fun local(): MusicSource =
            MusicSource(id = LOCAL_ID, kind = SourceKind.LOCAL, name = "Local Music")

        /** A readable default name for a server the user just added. */
        fun nameFor(baseUrl: String): String =
            baseUrl.substringAfter("://").substringBefore('/').ifBlank { "Server" }
    }
}
