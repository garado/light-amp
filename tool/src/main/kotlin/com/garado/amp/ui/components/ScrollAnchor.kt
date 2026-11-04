package com.garado.amp.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a list was scrolled to, remembered across navigation. A plain
 * `rememberLazyListState` resets to row one whenever the composable is torn
 * down and rebuilt, which happens on any screen transition.
 *
 * Positions are stored in content coordinates, with header rows above the
 * list subtracted out, so a list that changes header count (e.g. entering
 * selection mode) still restores to the same row.
 */
object ScrollAnchors {

    private data class Anchor(val index: Int, val offset: Int)

    private val anchors = mutableMapOf<String, Anchor>()

    internal fun save(owner: String, index: Int, offset: Int) {
        anchors[owner] = Anchor(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    internal fun load(owner: String): Pair<Int, Int> =
        anchors[owner]?.let { it.index to it.offset } ?: (0 to 0)

    /**
     * Scroll these lists to the top immediately, including ones already on
     * screen. [clear] alone only resets the saved position for next time; a
     * live list stays where it is until told via this signal.
     */
    fun requestTop(owner: String) {
        clear(owner)
        _topRequests.value = TopRequest(owner, _topRequests.value.count + 1)
    }

    data class TopRequest(val owner: String = "", val count: Int = 0) {
        /** Whether this names that list, or a view of it (e.g. "tab:albums/grid"). */
        fun names(other: String): Boolean =
            count > 0 && (other == owner || other.startsWith("$owner/"))
    }

    /**
     * Must be called once the request is handled. A rebuilt list would
     * otherwise read the same standing request again and re-scroll to top,
     * discarding the position just restored.
     */
    fun consumeTopRequest() {
        _topRequests.value = TopRequest()
    }

    private val _topRequests = MutableStateFlow(TopRequest())
    val topRequests: StateFlow<TopRequest> = _topRequests

    fun clear(vararg owners: String) {
        anchors.keys.removeAll { key -> owners.any { key == it || key.startsWith("$it/") } }
    }
}

/**
 * A [LazyListState] that resumes where this list last was. [headerCount] may
 * differ between the save and the restore.
 */
@Composable
fun rememberListAnchor(owner: String, headerCount: Int = 0): LazyListState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        // A header-rows-only anchor restores to the top, not a mid-header offset.
        if (index <= 0) LazyListState() else LazyListState(index + headerCount, offset)
    }
    val top by ScrollAnchors.topRequests.collectAsState()
    LaunchedEffect(top) {
        if (top.names(owner)) {
            state.scrollToItem(0)
            ScrollAnchors.consumeTopRequest()
        }
    }
    DisposableEffect(owner, headerCount) {
        onDispose {
            ScrollAnchors.save(
                owner,
                state.firstVisibleItemIndex - headerCount,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}

/**
 * The same, for a grid of covers. Needs its own [owner] rather than sharing
 * the list's, since a grid index means a different screen position than a
 * list index for the same item.
 */
@Composable
fun rememberGridAnchor(owner: String, headerCount: Int = 0): LazyGridState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        if (index <= 0) LazyGridState() else LazyGridState(index + headerCount, offset)
    }
    val top by ScrollAnchors.topRequests.collectAsState()
    LaunchedEffect(top) {
        if (top.names(owner)) {
            state.scrollToItem(0)
            ScrollAnchors.consumeTopRequest()
        }
    }
    DisposableEffect(owner, headerCount) {
        onDispose {
            ScrollAnchors.save(
                owner,
                state.firstVisibleItemIndex - headerCount,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}
