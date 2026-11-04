package com.garado.amp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.garado.amp.data.LocalLibrary
import com.garado.amp.data.MusicServer
import com.garado.amp.data.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads album art. Bytes are fetched once and cached on disk; decoded bitmaps
 * are downsampled to the requested display size and kept in a small memory LRU.
 * RGB_565 halves bitmap memory, which suits both the display and the battery.
 */
class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
    /** Whether a cover may be fetched over the network now; see App.heavyDataAllowed. */
    private val fetchAllowed: () -> Boolean = { true },
    /** Whether covers are switched off entirely; see App.hideArtwork. Checked only by [prefetch]. */
    private val artworkOff: () -> Boolean = { false },
    /**
     * Which source a cover belongs to, read at the moment it is asked for.
     * Cover ids are only unique within a server, so caching must be keyed by
     * source id or an id can be fetched from the wrong server and filed under
     * that id permanently.
     */
    private val sourceId: () -> String,
) {
    private val http = HttpClient(OkHttp) { expectSuccess = false }
    private val diskDir = File(filesDir, "artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_ENTRIES) {}
    private val gate = Semaphore(FETCH_CONCURRENCY)

    /** A cover already decoded at this size, without suspending, for drawing on the first frame. */
    fun peek(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        return memory.get(memoryKey(coverArtId, sizeBucket(targetSizePx)))
    }

    private fun memoryKey(coverArtId: String, bucket: Int) =
        "${sourceId()}|$coverArtId@$bucket"

    suspend fun load(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val memKey = memoryKey(coverArtId, bucket)
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            // Cached bytes that don't decode as an image are discarded and
            // re-fetched rather than trusted forever.
            val source = sourceId()
            val cached = readDisk(source, coverArtId)?.takeIf { looksLikeImage(it) }
            if (cached == null) diskFile(source, coverArtId).delete()
            val bytes = cached ?: fetch(serverClient.value, coverArtId)?.also { writeDisk(source, coverArtId, it) }
                ?: return@withContext null
            val bitmap = decodeDownsampled(bytes, bucket) ?: return@withContext null
            val image = bitmap.asImageBitmap()
            memory.put(memKey, image)
            image
        }
    }

    /**
     * Put a cover on disk without decoding it. Called as tracks are downloaded
     * so an offline library has its sleeves. Takes its source and client as
     * parameters rather than reading the active ones, because downloads run
     * for every source regardless of which one is being browsed.
     */
    suspend fun prefetch(sourceId: String, client: MusicServer, coverArtId: String?) {
        if (coverArtId.isNullOrBlank()) return
        if (artworkOff()) return
        withContext(Dispatchers.IO) {
            if (diskFile(sourceId, coverArtId).let { it.exists() && it.length() > 0 }) return@withContext
            fetch(client, coverArtId)?.let { writeDisk(sourceId, coverArtId, it) }
        }
    }

    private suspend fun fetch(client: MusicServer?, coverArtId: String): ByteArray? {
        // A local track's cover id is its own file path: the sleeve is
        // embedded, and there is no server to ask.
        LocalLibrary.fileOf(coverArtId)?.let { return embedded(it) }
        if (!fetchAllowed()) return null
        if (client == null) return null
        val sized = client.coverArtUrl(coverArtId, panelWidthPx)
        val original = client.coverArtUrl(coverArtId)
        // Limit concurrent fetches: a grid can ask for a screenful of covers
        // at once.
        return gate.withPermit {
            // Fall back to the full-size URL if the server can't resize.
            download(sized) ?: download(original.takeIf { it != sized })
        }
    }

    private suspend fun download(url: String?): ByteArray? {
        if (url == null) return null
        return try {
            val response = http.get(url)
            if (!response.status.isSuccess()) return null
            response.body<ByteArray>().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** The picture tagged into an audio file. */
    private fun embedded(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun diskFile(sourceId: String, coverArtId: String) =
        File(diskDir, fileNameFor(sourceId, coverArtId))

    private fun readDisk(sourceId: String, coverArtId: String): ByteArray? {
        val file = diskFile(sourceId, coverArtId).takeIf { it.exists() && it.length() > 0 } ?: return null
        // Touch the mtime on read so the LRU trim evicts by last use.
        file.setLastModified(System.currentTimeMillis())
        return file.readBytes()
    }

    /**
     * Drop one source's covers, called when its library is removed. The
     * caller must read the cover ids out of that source's database before
     * clearing it, since cover files are named by hash and can't otherwise be
     * matched to a source.
     */
    suspend fun forget(sourceId: String, coverArtIds: Collection<String>) {
        withContext(Dispatchers.IO) {
            coverArtIds.forEach { File(diskDir, fileNameFor(sourceId, it)).delete() }
        }
        memory.snapshot().keys.filter { it.startsWith("$sourceId|") }.forEach { memory.remove(it) }
    }

    /**
     * Hold the cache to [DISK_BUDGET_BYTES], evicting least recently used
     * first. [keep] names files that must survive regardless: the covers of
     * downloaded albums, which belong to their songs and are freed only when
     * the songs are. See App.protectedCoverFiles.
     */
    suspend fun trimToBudget(keep: Set<String>) = withContext(Dispatchers.IO) {
        val files = diskDir.listFiles().orEmpty().filter { it.isFile }
        val before = files.sumOf { it.length() }
        var total = before
        if (total <= DISK_BUDGET_BYTES) return@withContext
        var removed = 0
        for (file in files.filter { it.name !in keep }.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET_BYTES) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
            }
        }
        android.util.Log.i(
            "AmpArt",
            "artwork cache trimmed: $removed files, ${before shr 20} MB -> ${total shr 20} MB",
        )
    }

    /** The file a cover is kept in, for anything naming files to keep or drop. */
    fun fileNameFor(sourceId: String, coverArtId: String): String = md5Hex("$sourceId|$coverArtId")

    /** Whether these bytes are a picture, checked by magic number since a server can 200 an error page. */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
        val gif = at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46
        val webp = at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        val bmp = at(0) == 0x42 && at(1) == 0x4D
        return jpeg || png || gif || webp || bmp
    }

    private fun writeDisk(sourceId: String, coverArtId: String, bytes: ByteArray) {
        if (!looksLikeImage(bytes)) return
        try {
            diskFile(sourceId, coverArtId).writeBytes(bytes)
        } catch (_: Exception) {
            // A failed cache write is non-fatal; the image still displays this time.
        }
    }

    private fun decodeDownsampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        if (target <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        val smallest = minOf(width, height)
        while (smallest / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /** The panel's own fetch width, set by the first themed frame; defaults to the LP3's width. */
    @Volatile
    var panelWidthPx: Int = FETCH_PX

    /** Snap to a few size buckets so different callers reuse the same decode. */
    private fun sizeBucket(px: Int): Int = when {
        px <= 0 -> 128
        px <= 160 -> 128
        px <= 360 -> 320
        px <= 720 -> 640
        else -> panelWidthPx
    }

    companion object {
        private const val MEMORY_ENTRIES = 150

        /**
         * The size covers are fetched at: the LP3 panel's full width (1080px),
         * the largest place a cover is ever shown. Smaller views downsample
         * from the same cached bytes.
         */
        private const val FETCH_PX = 1080

        /** How many covers are fetched at once; the rest wait their turn. */
        private const val FETCH_CONCURRENCY = 4

        /** What the covers on disk may add up to, beyond the protected ones. */
        private const val DISK_BUDGET_BYTES = 200L * 1024 * 1024
    }
}
