@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.garado.amp.ui.components

import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.App
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.ui.lightClickable

// --- Artwork ----------------------------------------------------------------

@Composable
fun rememberArtwork(coverArtId: String?, sizePx: Int): ImageBitmap? {
    // This is the only entry point into the artwork cache, so hiding covers
    // here also stops fetching and decoding them.
    val hidden by App.hideArtwork.collectAsState()
    // Seeded from the memory cache so an already-decoded cover shows on the
    // first frame; see ArtworkLoader.peek.
    val cached = if (hidden) null else App.artwork.peek(coverArtId, sizePx)
    // Must be a fresh state per cover, not held across the key change via
    // produceState: a cover already in the memory cache returns without
    // assigning a new value, leaving the previous track's cover on screen.
    var image by remember(coverArtId, sizePx, hidden) { mutableStateOf(cached) }
    LaunchedEffect(coverArtId, sizePx, hidden) {
        if (image != null) return@LaunchedEffect
        image = if (hidden || coverArtId.isNullOrBlank()) {
            null
        } else {
            App.artwork.load(coverArtId, sizePx)
        }
    }
    return image
}

// --- Clickable helpers ------------------------------------------------------

/**
 * Haptics for presses: a light tick for taps, a heavier pattern for holds.
 * [lightClickable] draws no press indication, so haptics are the only
 * confirmation a tap registered.
 */
@Composable
fun rememberTapHaptics(): Pair<() -> Unit, () -> Unit> {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        Pair(
            { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }
}

/**
 * A long press held longer than the platform default of ~500ms, which is
 * short enough to misfire during a slow scroll or a resting finger.
 */
@Composable
fun Modifier.slowLongPress(
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    holdMs: Long = LONG_PRESS_MS,
): Modifier {
    val (tick, thud) = rememberTapHaptics()
    if (onLongPress == null) return this.rowClickable(onClick = onClick)
    // Uses rememberUpdatedState rather than keying on these, so the gesture
    // detector isn't torn down and restarted on every recomposition.
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    return this.pointerInput(holdMs) {
        awaitEachGesture {
            awaitFirstDown()
            val startedAt = System.currentTimeMillis()
            val up = withTimeoutOrNull(holdMs) { waitForUpOrCancellation() }
            when {
                up != null -> {
                    tick()
                    click()
                }
                // Null also happens when a scroll claims the pointer. Check the
                // elapsed time to tell that apart from a real long press.
                System.currentTimeMillis() - startedAt >= holdMs -> {
                    thud()
                    long()
                    // Swallow the release so it doesn't also read as a tap.
                    waitForUpOrCancellation()
                }
            }
        }
    }
}

/** Long enough to be deliberate, short enough not to feel broken. */
const val LONG_PRESS_MS = 900L

/**
 * A row that responds to a tap, and optionally to a long press. Rows with a
 * long press go through [slowLongPress] instead of `combinedClickable`'s own
 * timeout, since the composition local to override that timeout is blocked
 * in the SDK sandbox.
 */
@Composable
fun Modifier.rowClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    if (onLongClick != null) {
        return this.slowLongPress(onClick = onClick, onLongPress = onLongClick)
    }
    val interaction = remember { MutableInteractionSource() }
    val (tick, _) = rememberTapHaptics()
    return this.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = { tick(); onClick() },
    )
}

/**
 * Icon/button counterpart to [rowClickable]: the SDK's clickable plus a
 * haptic tick. Use this instead of calling `lightClickable` directly.
 */
@Composable
fun Modifier.appClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val (tick, _) = rememberTapHaptics()
    return this.lightClickable(enabled = enabled) {
        tick()
        onClick()
    }
}

// --- Rows -------------------------------------------------------------------

/**
 * A menu row, styled like LightOS's own stock menus: one line for an action
 * row, or a small label above a large value for a setting row.
 */
@Composable
fun TextRow(
    title: String,
    modifier: Modifier = Modifier,
    /** The setting's current value; title becomes its label when this is set. */
    value: String? = null,
    /** A line under the title. One line by default; raise [subtitleLines] for longer text. */
    subtitle: String? = null,
    subtitleLines: Int = 1,
    onLongClick: (() -> Unit)? = null,
    /** Sits before the text, for a row that needs saying what it opens. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Marks this row as the current selection among a set of rows. */
    underline: Boolean = false,
    /** Null for a non-interactive row; its main line is dimmed to show that. */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (onClick == null) it else it.slowLongPress(onClick, onLongPress = onLongClick)
            }
            .padding(horizontal = MENU_INSET_DP, vertical = px(MENU_PAD_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(px(MENU_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (value != null) {
                MenuLabel(title)
                MenuLine(value, dim = onClick == null, underline = underline)
            } else {
                MenuLine(title, dim = onClick == null, underline = underline)
                if (!subtitle.isNullOrBlank()) MenuLabel(subtitle, maxLines = subtitleLines)
            }
        }
        if (trailing != null) trailing()
    }
}

/** A menu row's main line, at Light's Copy size (Heading reads too big here). */
@Composable
private fun MenuLine(text: String, dim: Boolean, underline: Boolean = false) {
    AppText(
        text,
        pxSp(LightType.COPY_PX),
        lineHeight = pxSp(LightType.COPY_LINE_PX),
        role = TextRole.Copy,
        dim = dim,
        maxLines = 1,
        underline = underline,
    )
}

/** A menu row's small line, above a value or under a verb: Light's Detail. */
@Composable
private fun MenuLabel(text: String, maxLines: Int = 1) {
    AppText(
        text,
        pxSp(LightType.DETAIL_PX),
        lineHeight = pxSp(LightType.DETAIL_LINE_PX),
        role = TextRole.Detail,
        dim = true,
        maxLines = maxLines,
    )
}

/** Where a menu row's text starts, and a section label's. */
val MENU_INSET_DP = 16.dp

/** Above and below a row's text: with Copy's line, a 136px pitch. */
private const val MENU_PAD_PX = 32

/** Between a leading glyph and the text: one unit. */
private const val MENU_GAP_PX = 40

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppText(
            text,
            pxSp(LightType.COPY_PX),
            lineHeight = pxSp(LightType.COPY_LINE_PX),
            dim = true,
            align = TextAlign.Center,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    AppText(
        text,
        pxSp(LightType.DETAIL_PX),
        lineHeight = pxSp(LightType.DETAIL_LINE_PX),
        role = TextRole.Detail,
        dim = true,
        modifier = Modifier.padding(
            start = MENU_INSET_DP,
            top = px(SECTION_TOP_PX),
            bottom = px(SECTION_BOTTOM_PX),
        ),
    )
}

private const val SECTION_TOP_PX = 40
private const val SECTION_BOTTOM_PX = 12
