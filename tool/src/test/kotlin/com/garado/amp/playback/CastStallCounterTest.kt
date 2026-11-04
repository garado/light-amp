package com.garado.amp.playback

import com.thelightphone.sdk.cast.DlnaState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CastStallCounterTest {

    /**
     * A renderer answering UNKNOWN and STOPPED intermittently. Six consecutive
     * not-playing readings trigger a restart, but STOPPED alone never reaches six here.
     */
    private val whenACastNeverStarted = listOf(
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
        DlnaState.STOPPED,
        DlnaState.STOPPED,
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
    )

    @Test
    fun `a renderer that flaps between stopped and unanswered still trips the restart`() {
        val stall = CastStallCounter()
        whenACastNeverStarted.forEach { stall.observe(it) }

        assertEquals(7, stall.notPlaying)
        assertTrue(stall.notPlaying >= 6, "six in a row is the restart threshold")
    }

    @Test
    fun `going quiet is never evidence a playing track has ended`() {
        val stall = CastStallCounter()
        whenACastNeverStarted.forEach { stall.observe(it) }

        // Advancing past a track requires STOPPED specifically, not just "not playing".
        assertEquals(0, stall.stopped)
    }

    @Test
    fun `an unanswered poll between two stops does not add up to an ending`() {
        val stall = CastStallCounter()
        stall.observe(DlnaState.STOPPED)
        stall.observe(DlnaState.UNKNOWN)
        stall.observe(DlnaState.STOPPED)

        assertEquals(1, stall.stopped)
        assertEquals(3, stall.notPlaying)
    }

    @Test
    fun `a track that really ends reaches the advance threshold`() {
        val stall = CastStallCounter()
        stall.observe(DlnaState.PLAYING)
        stall.observe(DlnaState.STOPPED)
        stall.observe(DlnaState.STOPPED)

        assertEquals(2, stall.stopped)
    }

    @Test
    fun `playing clears both counts`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.STOPPED) }
        stall.observe(DlnaState.PLAYING)

        assertEquals(0, stall.stopped)
        assertEquals(0, stall.notPlaying)
    }

    @Test
    fun `pausing is not a stall`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.UNKNOWN) }
        stall.observe(DlnaState.PAUSED)

        assertEquals(0, stall.notPlaying)
    }

    @Test
    fun `transitioning earns patience rather than a restart`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.UNKNOWN) }
        stall.observe(DlnaState.TRANSITIONING)

        assertEquals(0, stall.stopped)
        assertEquals(0, stall.notPlaying)
    }
}
