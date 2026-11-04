package com.garado.amp.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What the A-Z strip and scroll bar need from a list: position, length, and
 * how to move it. Abstracts over `LazyListState` and `LazyGridState`, both of
 * which work in item indices.
 */
class ScrollTarget(
    val firstVisibleIndex: Int,
    val totalItems: Int,
    val visibleItems: Int,
    val scrollTo: suspend (Int) -> Unit,
)

@Composable
fun rememberScrollTarget(state: LazyListState): ScrollTarget {
    val info = state.layoutInfo
    return remember(state, state.firstVisibleItemIndex, info.totalItemsCount, info.visibleItemsInfo.size) {
        ScrollTarget(
            firstVisibleIndex = state.firstVisibleItemIndex,
            totalItems = info.totalItemsCount,
            visibleItems = info.visibleItemsInfo.size,
            scrollTo = { state.scrollToItem(it) },
        )
    }
}

@Composable
fun rememberScrollTarget(state: LazyGridState): ScrollTarget {
    val info = state.layoutInfo
    return remember(state, state.firstVisibleItemIndex, info.totalItemsCount, info.visibleItemsInfo.size) {
        ScrollTarget(
            firstVisibleIndex = state.firstVisibleItemIndex,
            totalItems = info.totalItemsCount,
            visibleItems = info.visibleItemsInfo.size,
            scrollTo = { state.scrollToItem(it) },
        )
    }
}

/**
 * The same facts from a [ScrollState], for a plain scrolling column measured
 * in pixels rather than item indices. Divides by a nominal row height to
 * approximate an index; the bar only needs proportions, not exact indices.
 */
@Composable
fun rememberScrollTarget(state: ScrollState, rowPx: Int = SCROLL_ROW_PX): ScrollTarget {
    val viewport = state.viewportSize
    val content = viewport + state.maxValue
    return remember(state, state.value, viewport, content) {
        val rows = (content / rowPx).coerceAtLeast(1)
        val onScreen = (viewport / rowPx).coerceAtLeast(1)
        ScrollTarget(
            firstVisibleIndex = state.value / rowPx,
            totalItems = rows,
            visibleItems = onScreen.coerceAtMost(rows),
            scrollTo = { state.scrollTo(it * rowPx) },
        )
    }
}

/** A text row's height, near enough for a bar that only shows proportions. */
private const val SCROLL_ROW_PX = 108
