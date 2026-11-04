package com.garado.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.collectAsState
import com.garado.amp.data.Album
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Album covers in a grid, three across. The A-Z strip uses item indices, same
 * as [ScrollTarget] on the list view.
 */
@Composable
fun AlbumGrid(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    /** Room down the right edge for scroll furniture. */
    endInset: Dp = px(LIST_EDGE_PX),
    onLongPress: ((Album) -> Unit)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    // Counted by album count, not by what the layout reports as visible, so the
    // result is stable on the first frame and the grid doesn't reflow.
    val longEnough = albums.size >= GRID_BAR_MIN_ITEMS
    val furniture = longEnough
    // Must be read unconditionally. Reading it only inside an `if` stops
    // subscribing to the state and the bar freezes in place.
    val target = rememberScrollTarget(state)
    Box(modifier = modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = px(LIST_EDGE_PX),
            end = if (furniture) px(GRID_SCROLLBAR_LANE_PX) else endInset,
            top = px(LIST_TOP_PX),
            bottom = px(LIST_TOP_PX),
        ),
        horizontalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
        verticalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
    ) {
        header?.invoke(this)
        items(albums.size, key = { albums[it].id }) { index ->
            val album = albums[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .rowClickable(
                        onClick = { onOpen(album) },
                        onLongClick = onLongPress?.let { { it(album) } },
                    ),
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) { GridCover(album) }
            }
        }
    }
        if (furniture) ListScrollBar(target, lane = px(GRID_SCROLLBAR_LANE_PX))
    }
}

/** One cover, filling its grid cell. Doesn't use [AppArtwork], which takes a fixed size. */
@Composable
private fun GridCover(album: Album) {
    val density = LocalDensity.current
    val image = rememberArtwork(album.coverArtId, with(density) { px(GRID_CELL_PX).roundToPx() })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(px(GRID_CORNER_PX)))
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // Fallback when there's no artwork: show the title instead.
            AppText(
                album.title,
                pxSp(GRID_FALLBACK_PX),
                lineHeight = pxSp(GRID_FALLBACK_LINE_PX),
                align = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.padding(px(GRID_GAP_PX)),
            )
        }
    }
}

/** Width of the lane the grid's scroll bar lives in. */
private const val GRID_SCROLLBAR_LANE_PX = 30

/** Minimum album count before the grid shows a scroll bar. */
private const val GRID_BAR_MIN_ITEMS = 30

private const val GRID_COLUMNS = 3
private const val GRID_GAP_PX = 12

/**
 * Nominal cell size used only to pick decode resolution; actual cell size is
 * set by the grid.
 */
private const val GRID_CELL_PX = 300
private const val GRID_CORNER_PX = 6
private const val GRID_FALLBACK_PX = 39
private const val GRID_FALLBACK_LINE_PX = 48
