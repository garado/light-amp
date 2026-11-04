package com.garado.amp.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.garado.amp.ui.px
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightThemeTokens

/** Material icons matching the original app, referenced by name. */
object AppIcons {
    val Album = Icons.Filled.Album

    /** The Albums tab icon: two offset squares, representing a stack of sleeves. */
    val AlbumStack = Icons.Filled.FilterNone
    val MusicNote = Icons.Filled.MusicNote
    /** Filled mic, to match the visual weight of the icons next to it. */
    val RecordVoiceOver = Icons.Filled.Mic
    val QueueMusic = Icons.AutoMirrored.Filled.QueueMusic
    val PlaylistPlay = Icons.AutoMirrored.Filled.PlaylistPlay
    val Folder = Icons.Filled.Folder
    val MoreHoriz = Icons.Filled.MoreHoriz
    val GraphicEq = Icons.Filled.GraphicEq

    /** LightOS's own waveform; see [WaveformSymbol]. */
    val Waveform = WaveformSymbol
    val Lyrics = Icons.Filled.Lyrics

    val LibraryMusic = Icons.Filled.LibraryMusic

    /** Genres, on the library index. */
    val Genre = Icons.Filled.LocalOffer

    /** Composers, on the library index. */
    val Composer = Icons.Filled.HistoryEdu
    val Sort = Icons.AutoMirrored.Filled.Sort
    val Favorite = Icons.Filled.Favorite
    val FavoriteBorder = Icons.Filled.FavoriteBorder
    val Star = Icons.Filled.Star
    val PlayArrow = Icons.Filled.PlayArrow
    val Pause = Icons.Filled.Pause
    val FastRewind = Icons.Filled.FastRewind
    val FastForward = Icons.Filled.FastForward
    val Repeat = Icons.Filled.Repeat
    val RepeatOne = Icons.Filled.RepeatOne
    val Add = Icons.Filled.Add

    /** The same plus, ringed: what the player shows for a liked track. */
    val AddCircle = Icons.Filled.AddCircleOutline

    /** Selection state in multi-select mode: filled when picked, ring when not. */
    val Selected = Icons.Filled.CheckCircle
    val Unselected = Icons.Filled.RadioButtonUnchecked

    /** Marks a header title that opens a menu. */
    val ArrowDropDown = Icons.Filled.ArrowDropDown
    val Close = Icons.Filled.Close
    val Search = Icons.Filled.Search
    val Settings = Icons.Filled.Settings
    val Dehaze = Icons.Filled.Dehaze
    val FormatListBulleted = Icons.AutoMirrored.Filled.FormatListBulleted
    val VolumeUp = Icons.AutoMirrored.Filled.VolumeUp

    /** Output routing, not volume level. */
    val Speaker = Icons.Filled.Speaker
    val VolumeDown = Icons.AutoMirrored.Filled.VolumeDown
    val Bluetooth = Icons.Filled.Bluetooth
    val Smartphone = Icons.Filled.Smartphone
    val DeleteOutline = Icons.Filled.DeleteOutline
    val ArrowUpward = Icons.Filled.ArrowUpward
    val ArrowDownward = Icons.Filled.ArrowDownward
    val Whatshot = Icons.Filled.Whatshot
    val Cast = Icons.Filled.Cast
    val Refresh = Icons.Filled.Refresh
    val Download = Icons.Filled.Download
    val DownloadDone = Icons.Filled.DownloadDone

    val Downloaded = Icons.Filled.Download
    val CloudOff = Icons.Filled.CloudOff
}

@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = px(71),
    tint: Color = LightThemeTokens.colors.content,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Same as [AppIcon] above, for icons from LightOS's own icon set instead of Material. */
@Composable
fun AppIcon(
    icon: LightIconConfiguration,
    modifier: Modifier = Modifier,
    size: Dp = px(71),
    tint: Color = LightThemeTokens.colors.content,
) {
    Icon(
        painter = painterResource(icon.drawableResource),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Which of the LP3's type styles a string belongs to. */
enum class TextRole { Title, Heading, Subheading, Copy, Button, Detail, Fine }

@Composable
private fun TextRole.style(): TextStyle {
    val t = LightThemeTokens.typography
    return when (this) {
        TextRole.Title -> t.title
        TextRole.Heading -> t.heading
        TextRole.Subheading -> t.subheading
        TextRole.Copy -> t.copy
        TextRole.Button -> t.button
        TextRole.Detail -> t.detail
        TextRole.Fine -> t.fine
    }
}

/**
 * A role's letter spacing, scaled to the size actually being drawn. The SDK
 * states spacing in absolute sp against its own font size, so it must be
 * converted to a ratio before applying it to a different size.
 */
private fun trackingFor(style: TextStyle, size: TextUnit): TextUnit {
    val spacing = style.letterSpacing
    val base = style.fontSize
    if (!spacing.isSp || !base.isSp || base.value <= 0f || spacing.value == 0f) return 0.sp
    return size * (spacing.value / base.value)
}

/**
 * Snap a requested weight to a face the LP3 actually ships. The device font has
 * no Medium (500); Android silently resolves it to Regular, so requests round
 * up to the next available face instead of to the nearest one.
 */
private val DEVICE_WEIGHTS = listOf(
    FontWeight.Thin,
    FontWeight.Light,
    FontWeight.Normal,
    FontWeight.Bold,
    FontWeight.Black,
)

private fun onDeviceWeight(requested: FontWeight): FontWeight =
    DEVICE_WEIGHTS.firstOrNull { it.weight >= requested.weight } ?: FontWeight.Black

/**
 * The style [AppText] draws with, exposed so it can also be measured. Fit
 * checks must measure in this exact style, not an approximation, since a
 * different face, size, weight, or tracking changes the measured width.
 */
@Composable
fun appTextStyle(
    size: TextUnit,
    lineHeight: TextUnit = TextUnit.Unspecified,
    role: TextRole = TextRole.Copy,
    weight: FontWeight? = null,
    align: TextAlign? = null,
    underline: Boolean = false,
): androidx.compose.ui.text.TextStyle {
    val base = role.style()
    return base.copy(
        fontSize = size,
        lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else size * 1.25f,
        fontWeight = onDeviceWeight(weight ?: base.fontWeight ?: FontWeight.Normal),
        letterSpacing = trackingFor(base, size),
        textAlign = align ?: TextAlign.Unspecified,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
    )
}

@Composable
fun AppText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    color: Color = LightThemeTokens.colors.content,
    dim: Boolean = false,
    role: TextRole = TextRole.Copy,
    /** Overrides the role's weight when a specific one is wanted. */
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    /** Marks the current selection among a set of rows. See [TextRow]. */
    underline: Boolean = false,
) {
    val style = appTextStyle(size, lineHeight, role, weight, align, underline)
    Text(
        text = text,
        modifier = if (dim) modifier.alpha(0.5f) else modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * The stock LP3 fader, used for both seeking and volume. Delegates drawing to
 * the SDK's [LightProgressBar]; touch handling stays with each caller, since
 * `LightTouchableProgressBar` doesn't support a scrub preview.
 */
@Composable
fun AppProgressBar(progress: Float) {
    LightProgressBar(LightThemeTokens.colors, progress)
}
