package com.garado.amp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.remember
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons

/**
 * A header corner button. [onLongClick] is declared before [onClick] so the
 * trailing-lambda call form still names the tap.
 */
data class HeaderAction(
    val icon: ImageVector,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Right-hand geometry when a search button is present. The now-playing icon
 * stays centred at 1000px (80px in from the right edge) in its 120px square;
 * search centres at 880px in an 80px square. Their hit boxes meet at 920px
 * without overlapping.
 */
private const val EDGE_INSET_PX = 80
private const val SEARCH_SLOT_PX = 80
private const val NOW_PLAYING_SLOT_PX = 120

/** Gap between the two icon centres. */
private const val SEARCH_TO_NOW_PLAYING_PX = 120

/** Corner glyph box size in physical pixels, matching the player header's icon size. */
const val CORNER_ICON_PX = 66

/**
 * A header title that drops to a smaller size when the full size would
 * overflow. Overflow is measured directly against the actual render style and
 * available width, rather than inferred from character count, since the font
 * is proportional.
 */
@Composable
private fun HeaderTitle(title: String, fit: Boolean, roomPx: Int) {
    val full = pxSp(LightType.FINE_PX)
    val style = appTextStyle(full, role = TextRole.Subheading, align = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val fits = !fit || roomPx <= 0 || remember(title, roomPx, style) {
        !measurer.measure(
            AnnotatedString(title),
            style = style,
            maxLines = 1,
            constraints = Constraints(maxWidth = roomPx),
        ).hasVisualOverflow
    }
    AppText(
        title,
        if (fits) full else pxSp(SUBPAGE_TITLE_PX),
        lineHeight = if (fits) TextUnit.Unspecified else pxSp(SUBPAGE_TITLE_LINE_PX),
        role = TextRole.Subheading,
        maxLines = 1,
        align = TextAlign.Center,
    )
}

/** Size a title drops to when it doesn't fit; matches the player's title card. */
private const val SUBPAGE_TITLE_PX = 45
private const val SUBPAGE_TITLE_LINE_PX = 54

/**
 * Header height in LP3 pixels. Fixed rather than derived from
 * `screenHeight - screenWidth`, because that value excludes insets on some
 * devices (the emulator) and breaks the 120x120 corner squares.
 */
const val HEADER_BAR_PX = 120

/**
 * Standard page header: a [HEADER_BAR_PX] band with a full-height square at
 * each end. Left is back or a custom action, center is a title or custom
 * content, right is an optional action; a search icon can sit left of it.
 */
@Composable
fun AppHeader(
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    leftAction: HeaderAction? = null,
    rightAction: HeaderAction? = null,
    searchAction: (() -> Unit)? = null,
    /** What the [searchAction] slot draws; the slot's geometry never changes. */
    searchIcon: ImageVector = AppIcons.Search,
    /** A second left-hand button, mirroring the search slot on the right. */
    secondaryLeftAction: HeaderAction? = null,
    /** Let a title that doesn't fit drop to a smaller size instead of being cut. */
    fitTitle: Boolean = false,
) {
    val headerHeight = px(HEADER_BAR_PX)
    val hasSearch = searchAction != null
    val rightSlot = if (hasSearch) px(NOW_PLAYING_SLOT_PX) else headerHeight
    // Gap between the search slot and the now-playing slot centres.
    val searchGap = px(SEARCH_TO_NOW_PLAYING_PX - NOW_PLAYING_SLOT_PX / 2 - SEARCH_SLOT_PX / 2)
    // Extra width beyond the 120px corner square; applied as a spacer on
    // whichever side lacks it, so the title stays centred regardless of which
    // optional slots are present.
    val extraSlot = px(SEARCH_SLOT_PX) + searchGap
    val zero = 0.dp
    val leadingSpacer = if (hasSearch && secondaryLeftAction == null) extraSlot else zero
    val trailingSpacer = if (secondaryLeftAction != null && !hasSearch) extraSlot else zero

    Row(
        modifier = Modifier.fillMaxWidth().height(headerHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderSquare(headerHeight, onClick = leftAction?.onClick ?: onBack) {
            when {
                leftAction != null -> AppIcon(leftAction.icon, size = px(71))
                onBack != null -> LightIcon(LightIcons.BACK, size = 2f)
                else -> {}
            }
        }

        if (secondaryLeftAction != null) {
            Spacer(Modifier.width(searchGap).height(headerHeight))
            HeaderSlot(
                px(SEARCH_SLOT_PX),
                px(SEARCH_SLOT_PX),
                onClick = secondaryLeftAction.onClick,
            ) {
                AppIcon(secondaryLeftAction.icon, size = px(66))
            }
        }
        Spacer(Modifier.width(leadingSpacer).height(headerHeight))

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val room = constraints.maxWidth
            when {
                titleContent != null -> titleContent()
                title != null -> HeaderTitle(title, fitTitle, room)
                else -> {}
            }
        }

        if (searchAction != null) {
            HeaderSlot(px(SEARCH_SLOT_PX), px(SEARCH_SLOT_PX), onClick = searchAction) {
                AppIcon(searchIcon, size = px(66))
            }
            Spacer(Modifier.width(searchGap).height(headerHeight))
        }
        Spacer(Modifier.width(trailingSpacer).height(headerHeight))
        HeaderSlot(
            rightSlot,
            headerHeight,
            onClick = rightAction?.onClick,
            onLongClick = rightAction?.onLongClick,
        ) {
            if (rightAction != null) {
                AppIcon(rightAction.icon, size = px(CORNER_ICON_PX))
            }
        }
    }
}

@Composable
private fun HeaderSquare(size: Dp, onClick: (() -> Unit)?, content: @Composable () -> Unit) =
    HeaderSlot(width = size, height = size, onClick = onClick, content = content)

@Composable
private fun HeaderSlot(
    width: Dp,
    height: Dp,
    onClick: (() -> Unit)?,
    /** A hold, where the slot has one. Same long-press duration every row uses. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val press = when {
        onLongClick != null -> Modifier.rowClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
        onClick != null -> Modifier.appClickable(onClick = onClick)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
