package com.garado.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garado.amp.App
import com.garado.amp.data.SourceKind
import com.garado.amp.ui.CANVAS_W_PX
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun AppArtwork(
    coverArtId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = px(10),
    fallback: ImageVector = AppIcons.MusicNote,
) {
    // Skips the placeholder tile too, not just real artwork.
    if (App.hideArtwork.collectAsState().value) return
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberArtwork(coverArtId, px)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
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
            AppIcon(fallback, size = size * 0.42f, tint = LightThemeTokens.colors.contentSecondary)
        }
    }
}

/**
 * A list row: title at [ROW_TITLE_PX], dimmer subtitle at [ROW_SUB_PX] below it.
 * The download mark sits immediately left of the title, not as a badge at the
 * row's far end.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    coverArtId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallback: ImageVector = AppIcons.MusicNote,
    onLongClick: (() -> Unit)? = null,
    /** Shows the offline badge. Set for a downloaded song or a complete album. */
    downloaded: Boolean = false,
    /**
     * Selection state, or null when the list isn't in multi-select mode. The
     * selection circle replaces the artwork slot rather than sitting beside it,
     * keeping every title on the same left edge either way.
     */
    selected: Boolean? = null,
    /** No artwork slot at all, for a folder row. */
    showIcon: Boolean = true,
) {
    val artworkHidden = App.hideArtwork.collectAsState().value
    // Must be read unconditionally. Reading it only inside the branch below
    // stops subscribing to the flow and the marks freeze in place.
    val marks = !App.hideDownloadIcons.collectAsState().value
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (subtitle.isBlank()) ROW_SINGLE_H_PX else ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Slot stays present even when empty, to keep titles on one axis
        // across rows with and without the download mark.
        Box(modifier = Modifier.width(px(ROW_LEAD_PX))) {
            if (downloaded && marks) AppIcon(AppIcons.Downloaded, size = px(ROW_MARK_PX))
        }
        if (selected != null) {
            Box(
                modifier = Modifier.size(px(ROW_ART_PX)),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    if (selected) AppIcons.Selected else AppIcons.Unselected,
                    size = px(66),
                    tint = if (selected) {
                        LightThemeTokens.colors.content
                    } else {
                        LightThemeTokens.colors.contentSecondary
                    },
                )
            }
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (showIcon && !artworkHidden) {
            AppArtwork(coverArtId = coverArtId, size = px(ROW_ART_PX), fallback = fallback)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                title,
                pxSp(ROW_TITLE_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(ROW_SUB_PX),
                    lineHeight = pxSp(ROW_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Row height in canvas pixels. Shared by the queue and artist list. */
const val ROW_H_PX = 150

/** A row carrying one line only; see [ArtistRow] with its counts switched off. */
const val ROW_SINGLE_H_PX = 108
const val ROW_TITLE_PX = 54
const val ROW_TITLE_LINE_PX = 66
const val ROW_SUB_PX = 39
const val ROW_SUB_LINE_PX = 48

/** Cover size and the gap beside it. */
const val ROW_ART_PX = 128
const val ROW_GAP_PX = 32

/** Width of the leading slot the download mark sits in. */
const val ROW_LEAD_PX = 51

private const val ROW_MARK_PX = 33
/** Trimmed in from the list's usual icon/gap sizes so long action labels fit. */
const val ROW_ACTION_ICON_PX = 57
private const val ACTION_GAP_PX = 24
const val ROW_ACTION_H_PX = 102

/** X position of the left button's center in a two-button action row. */
private const val SPLIT_LEFT_X_PX = CANVAS_W_PX * 3 / 10

/** Max width of each slot in a two-button action row, without the slots overlapping. */
private const val ACTION_SLOT_PX = CANVAS_W_PX - SPLIT_LEFT_X_PX * 2

/** Width available to a single button with no neighbor. */
private const val SOLO_SLOT_PX = 600

/** List padding; aligns a row's left edge with the reference app's. */
const val LIST_EDGE_PX = 21

/** Top padding for a library list, set so the header row sits evenly between
 * the app header above and the first cover below. */
const val LIST_TOP_PX = 8

/** An artist row: same metrics as a track row, minus the cover, keeping the
 * leading slot so names stay on the same axis as other list titles. */
@Composable
fun ArtistRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Shows the offline badge when every track by the artist is downloaded. */
    downloaded: Boolean = false,
    /** The server's picture of them, where it has one; see Artist.imageId. */
    imageId: String? = null,
) {
    // Must read unconditionally; see the note in TrackRow.
    val wantsCovers = !App.hideArtistImages.collectAsState().value
    val local = App.source.collectAsState().value.kind == SourceKind.LOCAL
    // Local library has no artist pictures to fetch, so the slot would just be
    // empty circles; fall back to the tighter text-only row instead.
    val covers = wantsCovers && !local
    // Must read unconditionally; see the note in TrackRow.
    val marks = !App.hideDownloadIcons.collectAsState().value
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (covers) ROW_H_PX else ROW_SINGLE_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(px(ROW_LEAD_PX))) {
            if (downloaded && marks) AppIcon(AppIcons.Downloaded, size = px(ROW_MARK_PX))
        }
        if (covers) {
            // Round rather than square, to read as a portrait instead of a
            // sleeve that failed to load.
            AppArtwork(
                imageId,
                size = px(ROW_ART_PX),
                corner = px(ROW_ART_PX) / 2,
                fallback = AppIcons.RecordVoiceOver,
            )
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column {
            AppText(
                name,
                pxSp(ROW_TITLE_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
            )
        }
    }
}

/** Icon + label action row (Liked, Shuffle, Play All). */
@Composable
fun PlayAllRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlayAllRow(label, modifier, onClick) { AppIcon(icon, size = px(ROW_ACTION_ICON_PX)) }
}

/** Same row, for a LightOS glyph instead of a Material icon. */
@Composable
fun PlayAllRow(
    icon: LightIconConfiguration,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlayAllRow(label, modifier, onClick) { AppIcon(icon, size = px(ROW_ACTION_ICON_PX)) }
}

@Composable
private fun PlayAllRow(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = px(ROW_ACTION_H_PX))
            .appClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(px(ROW_LEAD_PX)))
        icon()
        Spacer(Modifier.width(px(ROW_GAP_PX)))
        // Not TextRole.Button: its Bold 0.15em style reads too heavy for a
        // full-width row label.
        AppText(label, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
    }
}

/** Two action buttons side by side, each centred in its own half. */
@Composable
fun SplitActionRow(
    leftIcon: ImageVector? = null,
    leftLabel: String = "",
    onLeft: (() -> Unit)? = null,
    /**
     * The right-hand button, or nothing. Null keeps the left button in its
     * split-row position instead of centring it, so a button that arrives
     * later (e.g. an artist's popular tracks, once fetched) doesn't cause the
     * left button to jump sideways.
     */
    rightIcon: ImageVector? = null,
    rightLabel: String = "",
    onRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** A lone button centres instead of keeping the left slot. See below. */
    center: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = px(ROW_ACTION_H_PX)),
    ) {
        val left = if (leftIcon != null && onLeft != null) leftIcon to onLeft else null
        val right = if (rightIcon != null && onRight != null) rightIcon to onRight else null
        val soloX = if (center) CANVAS_W_PX / 2 else SPLIT_LEFT_X_PX
        when {
            left != null && right != null -> {
                ActionSlot(SPLIT_LEFT_X_PX, left.first, leftLabel, left.second)
                ActionSlot(CANVAS_W_PX - SPLIT_LEFT_X_PX, right.first, rightLabel, right.second)
            }
            // A lone button keeps the left slot position unless `center` is set.
            left != null ->
                ActionSlot(soloX, left.first, leftLabel, left.second, SOLO_SLOT_PX)
            right != null ->
                ActionSlot(soloX, right.first, rightLabel, right.second, SOLO_SLOT_PX)
        }
    }
}

/**
 * One action button, centred on an absolute position across the screen.
 * Positioned explicitly rather than via a weighted Row, since list padding
 * varies with whatever furniture is on its right edge. [centreX] is measured
 * from the screen's left edge; [LIST_EDGE_PX] is subtracted since that's
 * where list content begins.
 */
@Composable
private fun BoxScope.ActionSlot(
    centreX: Int,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    width: Int = ACTION_SLOT_PX,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = px(centreX - LIST_EDGE_PX - width / 2))
            .width(px(width))
            .heightIn(min = px(ROW_ACTION_H_PX))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ActionContent(icon, label)
    }
}

@Composable
private fun ActionContent(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        Spacer(Modifier.width(px(ACTION_GAP_PX)))
        AppText(label, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
    }
}

/** An album's own tracklist, set a notch smaller than a library list. */
private const val NUMBERED_TITLE_PX = 48
private const val NUMBERED_TITLE_LINE_PX = 60
private const val NUMBERED_SUB_PX = 36
private const val NUMBERED_SUB_LINE_PX = 45
/** A row with just the title, which is most of them now the number is inline. */
private const val NUMBERED_ROW_H_PX = 96

/** A row that also carries a subtitle, e.g. who plays on the song. */
private const val NUMBERED_ROW_TALL_PX = 141
/** Air between the waveform/selection icon and the title that follows it. */
private const val NUMBER_GAP_PX = 15

/** Title with its track number inline, plus a now-playing or selection icon before it, where present. */
@Composable
fun NumberedRow(
    number: Int?,
    title: String,
    /** Line under the title, e.g. performer credit. Blank drops the line entirely. */
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Selection state, or null when the list isn't in multi-select mode. */
    selected: Boolean? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (subtitle.isNotBlank()) NUMBERED_ROW_TALL_PX else NUMBERED_ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selected != null -> {
                AppIcon(
                    if (selected) AppIcons.Selected else AppIcons.Unselected,
                    size = px(41),
                    tint = if (selected) {
                        LightThemeTokens.colors.content
                    } else {
                        LightThemeTokens.colors.contentSecondary
                    },
                )
                Spacer(Modifier.width(px(NUMBER_GAP_PX)))
            }
            current -> {
                AppIcon(AppIcons.Waveform, size = px(41))
                Spacer(Modifier.width(px(NUMBER_GAP_PX)))
            }
            else -> Spacer(Modifier.width(px(ROW_LEAD_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                if (number != null) "$number. $title" else title,
                pxSp(NUMBERED_TITLE_PX),
                lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(NUMBERED_SUB_PX),
                    lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Padding shared by every list in the app. */
@Composable
fun listPadding(
    end: Dp = px(LIST_EDGE_PX),
): PaddingValues =
    PaddingValues(
        start = px(LIST_EDGE_PX),
        end = end,
        top = px(LIST_TOP_PX),
        bottom = px(LIST_TOP_PX),
    )


/**
 * A list with a scroll bar down its right edge. Tab lists use the A-Z strip
 * instead. Content is inset by the bar's lane so a long title never runs
 * underneath it.
 */
@Composable
fun ScrollableList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(end = px(SCROLLBAR_LANE_PX)),
            content = content,
        )
        ListScrollBar(state)
    }
}

/**
 * A library list that remembers its scroll position across navigation, with
 * an optional scroll bar.
 */
@Composable
fun LibraryList(
    anchor: String,
    modifier: Modifier = Modifier,
    headerCount: Int = 0,
    /** Whether to show the right-hand scroll bar. */
    scrollBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberListAnchor(anchor, headerCount)
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(
                end = if (scrollBar) px(SCROLLBAR_LANE_PX) else px(LIST_EDGE_PX),
            ),
            content = content,
        )
        if (scrollBar) ListScrollBar(state)
    }
}
