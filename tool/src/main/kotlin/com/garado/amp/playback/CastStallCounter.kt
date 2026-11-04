package com.garado.amp.playback

import com.thelightphone.sdk.cast.DlnaState

/**
 * How long a cast renderer has gone without being seen to play, tracked two ways.
 *
 * [stopped] counts only readings where the renderer reported STOPPED. A
 * false positive here cuts off a track mid-playback, so an unanswered
 * reading must not count.
 *
 * [notPlaying] also counts UNKNOWN readings (a failed SOAP call). A false
 * positive here costs nothing, since there was no audio playing to
 * interrupt. If it only counted STOPPED, a renderer that flaps between
 * STOPPED and UNKNOWN would never reach the threshold.
 *
 * TRANSITIONING resets both counts.
 */
internal class CastStallCounter {

    /** Consecutive readings of STOPPED, and nothing else. */
    var stopped = 0
        private set

    /** Consecutive readings that were not the renderer playing. */
    var notPlaying = 0
        private set

    fun observe(state: DlnaState) {
        when (state) {
            DlnaState.PLAYING, DlnaState.PAUSED, DlnaState.TRANSITIONING -> reset()
            DlnaState.STOPPED -> {
                stopped++
                notPlaying++
            }
            // UNKNOWN means it didn't answer, not that it's stopped. Only
            // notPlaying, which can tolerate being wrong, counts it.
            DlnaState.UNKNOWN -> {
                stopped = 0
                notPlaying++
            }
        }
    }

    fun reset() {
        stopped = 0
        notPlaying = 0
    }
}
