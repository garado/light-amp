package com.garado.amp.data

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The index frame is read by decoders that were never told about this code,
 * so its layout is pinned byte by byte: the lengths here are the published
 * MPEG-1 Layer III frame sizes at 44.1 kHz, worked out independently of the
 * formula the indexer uses, and the field offsets are where ExoPlayer's
 * XingSeeker and LAME's VbrTag.c put them.
 */
class Mp3VbrIndexTest {

    // Frame lengths for MPEG-1 Layer III, 44.1 kHz, no padding.
    private val lengthAt44k = mapOf(32 to 104, 64 to 208, 128 to 417, 192 to 626, 320 to 1044)
    private val indexAt44k = mapOf(32 to 1, 64 to 5, 128 to 9, 192 to 11, 320 to 14)

    private fun header(bitrate: Int, mono: Boolean = false, mpeg2: Boolean = false): ByteArray {
        val version = if (mpeg2) 2 else 3
        val raw = (0x7FF shl 21) or (version shl 19) or (1 shl 17) or (1 shl 16) or
            ((if (mpeg2) mapOf(32 to 4, 64 to 8)[bitrate]!! else indexAt44k[bitrate]!!) shl 12) or
            (0 shl 10) or ((if (mono) 3 else 1) shl 6)
        return byteArrayOf((raw ushr 24).toByte(), (raw ushr 16).toByte(), (raw ushr 8).toByte(), raw.toByte())
    }

    /** A frame of the given bitrate: header plus audio bytes that are not zero, so a shift would show. */
    private fun frame(bitrate: Int, seed: Int, mono: Boolean = false, mpeg2: Boolean = false): ByteArray {
        val length = if (mpeg2) 72_000 * bitrate / 22_050 else lengthAt44k[bitrate]!!
        return header(bitrate, mono, mpeg2) + Random(seed).nextBytes(length - 4).also {
            // No accidental sync words in the payload.
            for (i in it.indices) if (it[i] == 0xFF.toByte()) it[i] = 0x7E
        }
    }

    private fun stream(count: Int, pattern: List<Int> = listOf(32, 128, 320, 192)): ByteArray =
        (0 until count).fold(ByteArray(0)) { acc, i -> acc + frame(pattern[i % pattern.size], i) }

    private fun tmp(bytes: ByteArray): File =
        File.createTempFile("amp-", ".mp3").apply { deleteOnExit(); writeBytes(bytes) }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun tagAt(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test
    fun `writes the frame a decoder expects, and moves no audio`() {
        val audio = stream(300)
        val file = tmp(audio)
        val outcome = Mp3VbrIndex.index(file)
        val out = file.readBytes()

        assertEquals(Mp3VbrIndex.Outcome.Written(frames = 300, bytes = out.size), outcome)
        // The index frame: a valid header, then 32 bytes of side info for
        // MPEG-1 stereo, then the tag at byte 36.
        assertEquals(0xFF.toByte(), out[0])
        assertEquals("Xing", tagAt(out, 36))
        assertEquals(1 or 2 or 4, readInt(out, 40))
        assertEquals(300, readInt(out, 44))
        assertEquals(out.size, readInt(out, 48))
        val toc = out.copyOfRange(52, 152).map { it.toInt() and 0xFF }
        assertEquals(0, toc[0])
        assertTrue(toc.zipWithNext().all { (a, b) -> a <= b }, "TOC must not run backwards")
        assertTrue(toc.last() < 256)
        // Everything after the index is the stream as it came, byte for byte.
        val indexLength = out.size - audio.size
        assertContentEquals(audio, out.copyOfRange(indexLength, out.size))
    }

    @Test
    fun `the table lands on the frame at each percent`() {
        val audio = stream(400)
        val file = tmp(audio)
        Mp3VbrIndex.index(file)
        val out = file.readBytes()
        val bytes = readInt(out, 48)
        val indexLength = out.size - audio.size
        // Where frame 200 (the 50% mark of 400) actually is, from the start of the file.
        var expected = indexLength
        for (i in 0 until 200) expected += lengthAt44k[listOf(32, 128, 320, 192)[i % 4]]!!
        val fromToc = (out[52 + 50].toInt() and 0xFF).toLong() * bytes / 256
        assertTrue(kotlin.math.abs(fromToc - expected) <= bytes / 256 + 1, "toc[50]=$fromToc expected≈$expected")
    }

    @Test
    fun `indexing twice changes nothing`() {
        val file = tmp(stream(50))
        Mp3VbrIndex.index(file)
        val once = file.readBytes()
        assertEquals(Mp3VbrIndex.Outcome.AlreadyIndexed, Mp3VbrIndex.index(file))
        assertContentEquals(once, file.readBytes())
    }

    @Test
    fun `an ID3v2 tag stays in front of the index`() {
        val body = ByteArray(100) { 0x41 }
        // "ID3", version 4.0, no flags, syncsafe size 100.
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 100) + body
        val audio = stream(40)
        val file = tmp(tag + audio)
        Mp3VbrIndex.index(file)
        val out = file.readBytes()
        assertContentEquals(tag, out.copyOfRange(0, tag.size))
        assertEquals("Xing", tagAt(out, tag.size + 36))
        assertContentEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }

    @Test
    fun `a file that already declares itself is left alone`() {
        val info = frame(128, 0).also { "Info".toByteArray().copyInto(it, 36) }
        val file = tmp(info + stream(20))
        val before = file.readBytes()
        assertEquals(Mp3VbrIndex.Outcome.AlreadyIndexed, Mp3VbrIndex.index(file))
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `refuses what it cannot walk to the end`() {
        assertEquals(Mp3VbrIndex.Outcome.NotMpegAudio, Mp3VbrIndex.index(tmp(Random(1).nextBytes(5000))))
        // Sync lost part-way: junk between two runs of frames.
        val broken = stream(30) + ByteArray(50) { 0x11 } + stream(30)
        val file = tmp(broken)
        assertEquals(Mp3VbrIndex.Outcome.Unparseable, Mp3VbrIndex.index(file))
        assertContentEquals(broken, file.readBytes())
    }

    @Test
    fun `a final frame the stream ended inside is tolerated, and an ID3v1 tag`() {
        val whole = stream(60)
        val cut = whole + frame(320, 99).copyOfRange(0, 500)
        assertEquals(60, (Mp3VbrIndex.index(tmp(cut)) as Mp3VbrIndex.Outcome.Written).frames)
        val tagged = whole + ("TAG".toByteArray() + ByteArray(125))
        assertEquals(60, (Mp3VbrIndex.index(tmp(tagged)) as Mp3VbrIndex.Outcome.Written).frames)
    }

    @Test
    fun `mono MPEG-2 puts the tag where a decoder looks for it`() {
        val audio = (0 until 80).fold(ByteArray(0)) { acc, i -> acc + frame(if (i % 2 == 0) 32 else 64, i, mono = true, mpeg2 = true) }
        val file = tmp(audio)
        val outcome = Mp3VbrIndex.index(file)
        val out = file.readBytes()
        assertEquals(80, (outcome as Mp3VbrIndex.Outcome.Written).frames)
        // MPEG-2 mono: 9 bytes of side info, so the tag sits at byte 13.
        assertEquals("Xing", tagAt(out, 13))
        assertContentEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }
}
