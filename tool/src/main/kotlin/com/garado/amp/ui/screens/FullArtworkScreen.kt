package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import com.garado.amp.App
import com.garado.amp.ui.PlayerTheme
import androidx.compose.foundation.layout.fillMaxWidth
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.TitleCard
import com.garado.amp.ui.components.rememberArtwork
import com.garado.amp.ui.currentScale
import com.garado.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * An album's cover at full width, uncropped, with no playback controls.
 * Reads the album from the library rather than a copy, so a title edited by
 * sync is reflected here too.
 */
class AlbumArtworkScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val image = rememberArtwork(album?.coverArtId, currentScale().windowWidthPx)

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    onBack = { goBack() },
                    titleContent = {
                        TitleCard(album?.title.orEmpty(), album?.artist.orEmpty())
                    },
                )
                // Clip on this outer box, not the image's own graphicsLayer: a
                // layer's clip scales with its transform, so clipping on the
                // image would let a zoomed-in picture spill over the header.
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    if (image != null) {
                        var zoom by remember { mutableStateOf(1f) }
                        var pan by remember { mutableStateOf(Offset.Zero) }
                        var frame by remember { mutableStateOf(IntSize.Zero) }

                        /**
                         * Clamps pan to [-(size * (zoom - 1)), 0] on each axis, the
                         * range that keeps the zoomed image covering the frame.
                         */
                        fun clamped(next: Offset, at: Float): Offset {
                            val slackX = (frame.width * (at - 1f)).coerceAtLeast(0f)
                            val slackY = (frame.height * (at - 1f)).coerceAtLeast(0f)
                            return Offset(
                                next.x.coerceIn(-slackX, 0f),
                                next.y.coerceIn(-slackY, 0f),
                            )
                        }

                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .onSizeChanged { frame = it }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                    // Top-left origin matches the clamp above.
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, drag, pinch, _ ->
                                        val was = zoom
                                        val now = (was * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        // Keeps the point under the fingers fixed while zooming.
                                        val focus = (centroid - pan) * (now / was)
                                        // Drag scaled by zoom, so a swipe covers the same
                                        // fraction of the image at any zoom level.
                                        zoom = now
                                        pan = clamped(centroid - focus + drag * now, now)
                                    }
                                },
                        )
                    } else {
                        AppIcon(
                            AppIcons.Album,
                            size = px(163),
                            tint = LightThemeTokens.colors.contentSecondary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

/** Zoom range. The bitmap is decoded at screen width, so 4x is past the point of finding more detail. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

