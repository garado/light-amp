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
 * across — Light's 27-unit grid at 40 pixels a unit.
 *
 * Derived from the SDK's grid rather than written as 1080 so the two stay one
 * thing: `px(40)` is exactly one of the SDK's grid units on every panel, and a
 * change to the grid in the SDK reaches here.
 */
const val CANVAS_PX_PER_UNIT = 40
const val CANVAS_W_PX = LightGrid.WIDTH * CANVAS_PX_PER_UNIT

/**
 * How the canvas maps onto the panel this is drawing on.
 *
 * A design pixel is a fraction of the window's width, not a dp. The sizes in the
 * app were measured on the LP3 in pixels and used to be turned into dp by
 * assuming the LP3's 3× density — which held until someone changed it:
 * Android's "smallest width" setting lowers the density, every dp becomes fewer
 * pixels, and the app shrank on a panel that hadn't. Light's own components
 * size from the screen's width (`gridUnitsAsDp`), so density cancels out and
 * they don't move; this does the same, in the unit the app is already written
 * in.
 *
 * Each size rounds to a whole pixel of the panel. On the LP3 the factor is
 * exactly 1 and nothing moves; elsewhere a 3px hairline stays a 3px hairline
 * rather than a 2.9px one the renderer smears across four.
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
    // The window's own width in pixels once it has one. Before first layout it
    // reports zero, and the configuration's width stands in — an integer dp, so
    // up to a pixel short, for one frame.
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
 * Light's type scale on the LP3's panel: the SDK's variants as the phone draws
 * them, measured off its own tools and stated in canvas pixels like everything
 * else. Heading is a menu row and a setting's value; Copy a list title; Detail a
 * subtitle or a label; Fine a page title; Paragraph prose.
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
