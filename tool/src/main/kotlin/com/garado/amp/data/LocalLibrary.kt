package com.garado.amp.data

import android.media.MediaMetadataRetriever
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.hasRuntimePermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The phone's own music: the files the Light Music app plays, read straight
 * off the filesystem. `android.content` (MediaStore) is a blocked import for
 * a tool, so this walks the shared music folder with `java.io` instead and
 * reads tags with [MediaMetadataRetriever]. Ids are the file's own path
 * behind [ID_PREFIX], so they're stable across rescans with no database of
 * their own.
 */
object LocalLibrary {

    /** Marks an id as a path on this phone rather than something from a server. */
    const val ID_PREFIX = "file:"

    fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    fun pathOf(id: String): String? =
        if (id.startsWith(ID_PREFIX)) id.removePrefix(ID_PREFIX) else null

    fun fileOf(id: String): File? = pathOf(id)?.let { File(it) }.takeIf { it?.isFile == true }

    fun isLocal(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * The folder this app claims inside the shared music directory:
     * `Music/<AppName>`, matching the shape other LP3 music tools use (e.g.
     * Reverb's `Music/Reverb`).
     */
    const val FOLDER = "Music/Amp"

    /**
     * Where music lands: a folder of our own inside the shared Music
     * directory, not the phone's other audio (ringtones, voice notes, other
     * apps' folders). `/sdcard` and `/storage/emulated/0` are the same
     * directory; both are listed and deduplicated by canonical path below.
     */
    private val ROOTS = listOf(
        "/storage/emulated/0/$FOLDER",
        "/sdcard/$FOLDER",
    )

    /** Created on demand, so the folder exists to be copied into. */
    fun ensureFolder() {
        runCatching { File("/storage/emulated/0/$FOLDER").mkdirs() }
    }

    /**
     * What the phone can actually play, by extension. `ape`, `wv`, and
     * `dsf` are excluded: there's no decoder for them here, so listing
     * them would add tracks that fail the moment they're pressed.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus",
        "wav", "wma", "mp4", "mka", "aiff", "aif",
    )

    /** How deep to walk. Deep enough for Artist/Album/Disc, shallow enough to end. */
    private const val MAX_DEPTH = 6

    data class Scan(val tracks: List<Track>, val albums: List<Album>)

    /** The permission that makes any of this readable; see lighttool.toml. */
    const val PERMISSION = "android.permission.READ_MEDIA_AUDIO"

    /** What stands between the app and the music folder, if anything. */
    enum class Access { GRANTED, NOT_GRANTED, BLOCKED_BY_LIGHTOS }

    /**
     * Whether the app may actually read the music folder, and if not, why.
     * Not inferred from the filesystem: without the permission, listing
     * `/sdcard/Music` returns an empty array rather than null or an error,
     * so an empty folder and a forbidden one look identical from here.
     * The process's own grant decides; LightOS is asked only when the
     * process can't say yet.
     */
    suspend fun access(): Access {
        val held = hasRuntimePermission(PERMISSION)
        val lightOs = checkPermission(PERMISSION).asKotlinResult
            .map { it.permissionResult }
            .getOrNull()
        Log.i(TAG, "Music access: grant=${held ?: "unknown"} lightos=${lightOs ?: "no answer"}")
        return when {
            held == true -> Access.GRANTED
            held == null && lightOs == LightServiceMethod.GetPermission.Result.Granted -> Access.GRANTED
            lightOs == LightServiceMethod.GetPermission.Result.BlockedByServer -> Access.BLOCKED_BY_LIGHTOS
            else -> Access.NOT_GRANTED
        }
    }

    suspend fun permitted(): Boolean = access() == Access.GRANTED

    private fun roots(): List<File> =
        ROOTS.map(::File).filter { it.isDirectory }.distinctBy { it.canonicalPath }

    /**
     * Walk the folders and read every file's tags. On the IO dispatcher and
     * cancellable between files, since the user may walk away mid-scan.
     */
    suspend fun scan(onProgress: (Int) -> Unit = {}): Scan = withContext(Dispatchers.IO) {
        ensureFolder()
        val files = mutableListOf<File>()
        roots().forEach { root -> collect(root, 0, files) }
        // "No songs" has two causes that look the same from the library
        // screen: an unreadable folder and an empty one. Logged to tell them apart.
        Log.i(TAG, "Scanned ${roots().joinToString { it.path }}: ${files.size} files")

        val retriever = MediaMetadataRetriever()
        val tracks = mutableListOf<Track>()
        try {
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                readTrack(retriever, file)?.let(tracks::add)
                if (index % PROGRESS_EVERY == 0) onProgress(index)
            }
        } finally {
            runCatching { retriever.release() }
        }
        Scan(tracks, albumsFrom(tracks))
    }

    private fun collect(dir: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            when {
                // Hidden folders are caches and thumbnails, never someone's music.
                entry.name.startsWith(".") -> Unit
                entry.isDirectory -> collect(entry, depth + 1, into)
                entry.extension.lowercase() in AUDIO_EXTENSIONS -> into += entry
            }
        }
    }

    private fun readTrack(retriever: MediaMetadataRetriever, file: File): Track? {
        val tags = runCatching {
            retriever.setDataSource(file.absolutePath)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        }.getOrElse {
            Log.w(TAG, "Unreadable audio file: ${file.name}")
            null
        } ?: return null

        // A file with no tags at all is still music: fall back to the file
        // name and the containing folder.
        val title = tags.title?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
        val album = tags.album?.trim().orEmpty().ifBlank { file.parentFile?.name.orEmpty() }
        val artist = tags.artist?.trim().orEmpty().ifBlank { UNKNOWN_ARTIST }
        val albumArtist = tags.albumArtist?.trim().orEmpty().ifBlank { artist }

        return Track(
            id = idFor(file),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            albumId = albumId(albumArtist, album),
            // The file is its own cover; see ArtworkLoader, which reads the
            // embedded picture for any id that points at a path.
            coverArtId = idFor(file),
            durationMs = tags.durationMs,
            trackNumber = tags.track?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = tags.disc?.substringBefore('/')?.trim()?.toIntOrNull(),
            year = tags.year?.take(4)?.toIntOrNull(),
            // No server here to keep a play tally, and inventing one locally
            // would make Most Played mean something different from every
            // other source.
            playCount = 0,
            lastPlayedMs = 0L,
            genre = tags.genre?.trim().orEmpty(),
            composer = tags.composer?.trim().orEmpty(),
            // ReplayGain is a custom tag the retriever can't see; read
            // separately off the same file. See ReplayGainTags.
            gainDb = ReplayGainTags.gainDb(file),
        )
    }

    /** Albums are derived from the tags, the way the Artists list already is. */
    private fun albumsFrom(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }
            .mapNotNull { (id, group) ->
                if (id == null) return@mapNotNull null
                val first = group.first()
                Album(
                    id = id,
                    title = first.album,
                    artist = first.albumArtist,
                    coverArtId = group.firstNotNullOfOrNull { it.coverArtId },
                    durationMs = group.sumOf { it.durationMs },
                    songCount = group.size,
                    year = group.firstNotNullOfOrNull { it.year },
                    releaseDate = 0L,
                    // Newest file in the album, matching "Recently Added" elsewhere.
                    createdMs = group.mapNotNull { fileOf(it.id)?.lastModified() }.maxOrNull() ?: 0L,
                )
            }

    private fun albumId(albumArtist: String, album: String): String =
        ID_PREFIX + "album/" + albumArtist.lowercase() + "/" + album.lowercase()

    private data class Tags(
        val title: String?,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val track: String?,
        val disc: String?,
        val year: String?,
        val genre: String?,
        val composer: String?,
        val durationMs: Long,
    )

    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val PROGRESS_EVERY = 25
    private const val TAG = "LocalLibrary"
}
