package com.garado.amp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightGrid
import kotlin.math.roundToInt

/**
 * The canvas every size in the app is stated on: the LP3's panel, 1080 pixels
 * across. Derived from the SDK's grid rather than hardcoded as 1080, so a
 * grid change in the SDK is picked up automatically.
 */
const val CANVAS_PX_PER_UNIT = 40
const val CANVAS_W_PX = LightGrid.WIDTH * CANVAS_PX_PER_UNIT

/**
 * Maps the design canvas onto the panel's actual pixel width. A design pixel
 * is a fraction of window width, not a dp: converting via a fixed density
 * assumption breaks when Android's "smallest width" setting changes the
 * effective density. Scaling by width instead keeps sizes stable, and
 * rounding to whole panel pixels avoids sub-pixel blur on hairlines.
 */
@Immutable
class Scale(val windowWidthPx: Int, private val density: Float) {
    /** Panel pixels per design pixel: 1 on the LP3, whatever its density is set to. */
    val factor: Float = windowWidthPx / CANVAS_W_PX.toFloat()

    /** A design size as whole pixels of this panel. */
    fun pxValue(design: Number): Int = (design.toFloat() * factor).roundToInt()

    fun px(design: Number): Dp = (pxValue(design) / density).dp

    /**
     * Text in the same unit. Stated in sp, as the SDK's own text is, so a system
     * font scale applies to the app the way it applies to everything else.
     */
    fun sp(design: Number): TextUnit = (pxValue(design) / density).sp
}

/** Provided by [PlayerTheme]; anything composed outside it computes its own. */
val LocalScale = compositionLocalOf<Scale?> { null }

@Composable
fun rememberScale(): Scale {
    val density = LocalDensity.current.density
    val window = LocalWindowInfo.current.containerSize.width
    val configured = LocalConfiguration.current.screenWidthDp
    // Window width is 0 before first layout; fall back to configuration width
    // for that one frame, which may be off by a pixel.
    val width = if (window > 0) window else (configured * density).roundToInt()
    return remember(width, density) { Scale(width, density) }
}

@Composable
fun currentScale(): Scale = LocalScale.current ?: rememberScale()

/** A size measured on the LP3's panel, as it is on this one. */
@Composable
fun px(design: Number): Dp = currentScale().px(design)

/** Type measured on the LP3's panel, as it is on this one. */
@Composable
fun pxSp(design: Number): TextUnit = currentScale().sp(design)

/**
 * Light's type scale, stated in canvas pixels. Heading is a menu row and a
 * setting's value; Copy a list title; Detail a subtitle or label; Fine a page
 * title; Paragraph prose.
 */
object LightType {
    const val DETAIL_PX = 41
    const val DETAIL_LINE_PX = 51
    const val FINE_PX = 52
    const val FINE_LINE_PX = 63
    const val PARAGRAPH_PX = 51
    const val PARAGRAPH_LINE_PX = 64
    const val COPY_PX = 62
    const val COPY_LINE_PX = 72
    const val HEADING_PX = 79
    const val HEADING_LINE_PX = 96
}
