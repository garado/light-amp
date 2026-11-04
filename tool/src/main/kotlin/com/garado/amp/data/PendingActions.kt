package com.garado.amp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Something the user did that the server hasn't been told about yet.
 * [atMs] is when it happened, not when it's sent, so a scrobble replayed
 * later lands in history at the right time.
 */
@Serializable
data class PendingAction(
    val kind: Kind,
    /** Song id, album id, or artist name. Artists are keyed by name here. */
    val id: String,
    /** Stars for [Kind.RATE]; unused otherwise. */
    val value: Int = 0,
    val atMs: Long = System.currentTimeMillis(),
) {
    @Serializable
    enum class Kind {
        SCROBBLE,
        STAR_SONG,
        UNSTAR_SONG,
        STAR_ALBUM,
        UNSTAR_ALBUM,
        STAR_ARTIST,
        UNSTAR_ARTIST,
        RATE_SONG,
        RATE_ALBUM,
    }
}

/**
 * Plays, likes, and ratings made while the server was unreachable, queued
 * until it comes back. Stored in preferences rather than a database table, to
 * avoid a schema migration (which would wipe the library cache on this app's
 * destructive-migration setup). Replay preserves order and stops at the first
 * failure.
 */
class PendingActions(private val settings: AppSettings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _count = MutableStateFlow(0)

    /** How many actions are waiting. Surfaced in Settings. */
    val count: StateFlow<Int> = _count

    suspend fun load(): List<PendingAction> = decode(settings.pendingActions())

    /** Call once at boot so the count is right before anything is added. */
    suspend fun refreshCount() {
        _count.value = load().size
    }

    suspend fun add(action: PendingAction) = lock.withLock {
        val queue = decode(settings.pendingActions()).toMutableList()
        // A rating or like replaces the previous one for the same id. Scrobbles are never collapsed.
        if (action.kind != PendingAction.Kind.SCROBBLE) {
            queue.removeAll { it.supersededBy(action) }
        }
        queue += action
        val trimmed = if (queue.size > MAX_QUEUED) queue.takeLast(MAX_QUEUED) else queue
        settings.setPendingActions(json.encodeToString(trimmed))
        _count.value = trimmed.size
    }

    /**
     * Sends everything waiting, oldest first. Stops at the first failure.
     * Exception: Subsonic error code 70 ("data not found") means this one row
     * is stale, not that the server is unreachable, so it's dropped instead
     * of blocking the rest of the queue.
     */
    suspend fun flush(client: MusicServer, artistId: suspend (String) -> String?): Int =
        lock.withLock {
            val remaining = decode(settings.pendingActions()).toMutableList()
            var sent = 0
            while (remaining.isNotEmpty()) {
                val result = runCatching { apply(remaining.first(), client, artistId) }
                when {
                    result.getOrDefault(false) -> {
                        remaining.removeAt(0)
                        sent++
                    }
                    (result.exceptionOrNull() as? SubsonicException)?.code == 70 ->
                        remaining.removeAt(0)
                    else -> break
                }
            }
            settings.setPendingActions(if (remaining.isEmpty()) "" else json.encodeToString(remaining))
            _count.value = remaining.size
            sent
        }

    /** True when this action was sent, or when it can never be sent at all. */
    private suspend fun apply(
        action: PendingAction,
        client: MusicServer,
        artistId: suspend (String) -> String?,
    ): Boolean {
        when (action.kind) {
            PendingAction.Kind.SCROBBLE -> client.scrobble(action.id, action.atMs)
            PendingAction.Kind.STAR_SONG -> client.starSong(action.id)
            PendingAction.Kind.UNSTAR_SONG -> client.unstarSong(action.id)
            PendingAction.Kind.STAR_ALBUM -> client.starAlbum(action.id)
            PendingAction.Kind.UNSTAR_ALBUM -> client.unstarAlbum(action.id)
            // Dropped if the server doesn't know this artist name; the local like still stands.
            PendingAction.Kind.STAR_ARTIST -> artistId(action.id)?.let { client.starArtist(it) }
            PendingAction.Kind.UNSTAR_ARTIST -> artistId(action.id)?.let { client.unstarArtist(it) }
            PendingAction.Kind.RATE_SONG, PendingAction.Kind.RATE_ALBUM ->
                if (!client.setRating(action.id, action.value)) return false
        }
        return true
    }

    private fun decode(raw: String): List<PendingAction> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PendingAction>>(raw) }.getOrDefault(emptyList())
    }

    private fun PendingAction.supersededBy(next: PendingAction): Boolean =
        id == next.id && kind.group() == next.kind.group()

    /** Which actions describe the same piece of state, whichever way they set it. */
    private fun PendingAction.Kind.group(): String = when (this) {
        PendingAction.Kind.STAR_SONG, PendingAction.Kind.UNSTAR_SONG -> "like-song"
        PendingAction.Kind.STAR_ALBUM, PendingAction.Kind.UNSTAR_ALBUM -> "like-album"
        PendingAction.Kind.STAR_ARTIST, PendingAction.Kind.UNSTAR_ARTIST -> "like-artist"
        PendingAction.Kind.RATE_SONG -> "rate-song"
        PendingAction.Kind.RATE_ALBUM -> "rate-album"
        PendingAction.Kind.SCROBBLE -> "scrobble"
    }

    private companion object {
        /** Weeks of offline queueing before this limit is reached. */
        const val MAX_QUEUED = 2_000
    }
}
