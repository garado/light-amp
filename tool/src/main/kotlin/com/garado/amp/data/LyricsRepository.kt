package com.garado.amp.data

import com.garado.amp.App
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Falls back to lrclib.net for timed lyrics when the server doesn't provide any.
 * A synced result always wins over plain text, regardless of source.
 */
object LyricsRepository {

    private const val USER_AGENT = "Amp (Light Phone III music player)"
    private const val LRCLIB = "https://lrclib.net/api"

    private val http by lazy { HttpClient(OkHttp) { expectSuccess = false } }
    private val json = Json { ignoreUnknownKeys = true }

    /** [Unavailable] means the lookup failed; [None] means it succeeded and found nothing. */
    sealed interface Outcome {
        data class Found(val lyrics: Lyrics) : Outcome
        data object None : Outcome
        data object Unavailable : Outcome

        /** Nothing stored, and the caller said not to go looking for more. */
        data object NotFetched : Outcome
    }

    /** Settled answers only. A failed lookup is never remembered. */
    private val cache = ConcurrentHashMap<String, Outcome>()

    /** When [allowNetwork] is false, only lyrics already downloaded with the track are used. */
    suspend fun forTrack(
        track: Track,
        client: MusicServer?,
        allowNetwork: Boolean = true,
    ): Outcome {
        cache[track.id]?.let { return it }
        val resolved = resolve(track, client, allowNetwork)
        // Unavailable and NotFetched aren't settled answers, so they aren't cached.
        if (resolved != Outcome.Unavailable && resolved != Outcome.NotFetched) {
            cache[track.id] = resolved
        }
        return resolved
    }

    private suspend fun resolve(
        track: Track,
        client: MusicServer?,
        allowNetwork: Boolean,
    ): Outcome {
        val cached = runCatching { App.downloader.cachedLyrics(track.id) }.getOrNull()
        val stored = parseStored(cached)
        stored?.let { if (it.synced) return Outcome.Found(it) }

        if (!allowNetwork) return stored?.let { Outcome.Found(it) } ?: Outcome.NotFetched

        val server = runCatching { client?.getLyrics(track.id) }
        val fromServer = server.getOrNull()
        if (fromServer?.synced == true) return Outcome.Found(fromServer)

        val lrclib = runCatching { fetchLrclib(track) }
        val fromLrclib = lrclib.getOrNull()
        if (fromLrclib?.synced == true) return Outcome.Found(fromLrclib)

        val plain = fromServer ?: fromLrclib ?: parseStored(cached)
        if (plain != null) return Outcome.Found(plain)

        // If either lookup threw, this is an outage, not a confirmed absence of lyrics.
        val asked = server.isSuccess && lrclib.isSuccess
        return if (asked) Outcome.None else Outcome.Unavailable
    }

    /** The `[millis]text` form the downloader writes alongside an audio file. */
    private fun parseStored(raw: String?): Lyrics? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('\n').mapNotNull { line ->
            val match = STORED_STAMP.find(line)
            if (match != null) {
                val text = line.substring(match.range.last + 1).trim()
                if (text.isEmpty()) null else LyricLine(match.groupValues[1].toLong(), text)
            } else {
                line.trim().takeIf { it.isNotEmpty() }?.let { LyricLine(null, it) }
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines, synced = lines.all { it.timeMs != null })
    }

    /** Timed lyrics from lrclib, or null. Used when downloading for karaoke. */
    suspend fun timedFromLrclib(track: Track): Lyrics? =
        fetchLrclib(track)?.takeIf { it.synced }

    private suspend fun fetchLrclib(track: Track): Lyrics? {
        if (track.title.isBlank() || track.artist.isBlank()) return null
        // get-cached is cheap; get does a full match and is the fallback.
        for (endpoint in listOf("get-cached", "get")) {
            val response = http.get("$LRCLIB/$endpoint") {
                header("User-Agent", USER_AGENT)
                parameter("track_name", track.title)
                parameter("artist_name", track.artist)
                if (track.album.isNotBlank()) parameter("album_name", track.album)
                parameter("duration", (track.durationMs / 1000).toString())
            }
            if (!response.status.isSuccess()) continue
            val body = runCatching {
                json.decodeFromString(LrcLibResponse.serializer(), response.bodyAsText())
            }.getOrNull() ?: continue
            if (body.instrumental) return null
            body.syncedLyrics?.let { parseLrc(it)?.let { parsed -> return parsed } }
            body.plainLyrics?.let { plain ->
                val lines = plain.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    return Lyrics(lines.map { LyricLine(null, it) }, synced = false)
                }
            }
        }
        return null
    }

    private val STORED_STAMP = Regex("""^\[(\d+)]""")

    /** `[mm:ss.xx]`, one or more may prefix a single line. */
    private val LRC_STAMP = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * Parses LRC into timed lines, or null if there are no usable timestamps.
     * A line with multiple stamps produces one entry per stamp; empty-text stamps
     * (instrumental gaps) are dropped.
     */
    fun parseLrc(raw: String): Lyrics? {
        val parsed = mutableListOf<LyricLine>()
        for (line in raw.split('\n')) {
            val stamps = LRC_STAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val text = LRC_STAMP.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                // The fraction may be centi- or milliseconds; normalise to ms.
                val fraction = stamp.groupValues[3]
                    .takeIf { it.isNotEmpty() }
                    ?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                parsed += LyricLine(minutes * 60_000 + seconds * 1000 + fraction, text)
            }
        }
        if (parsed.isEmpty()) return null
        return Lyrics(parsed.sortedBy { it.timeMs ?: 0L }, synced = true)
    }
}

@kotlinx.serialization.Serializable
private data class LrcLibResponse(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val instrumental: Boolean = false,
)
