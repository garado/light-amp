package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.App
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.AppProgressBar
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.garado.amp.data.PlexPlayer
import com.garado.amp.playback.PlaybackController
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.cast.DlnaRenderer
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.garado.amp.ui.components.appClickable
import kotlin.math.roundToInt

/**
 * Output + volume sheet reached from the Now Playing transport.
 *
 * The volume fader drives the phone's media volume. Speaker-vs-Bluetooth
 * routing is LightOS's job; this list is informational for that part. Network
 * speakers over DLNA and other Plex players can be switched to directly.
 */
class OutputScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val volume by App.playback.volume.collectAsState()
        val casting by App.playback.castRenderer.collectAsState()
        val plexCasting by App.playback.plexPlayer.collectAsState()
        val plexVolume by App.playback.plexVolume.collectAsState()
        var renderers by remember { mutableStateOf<List<DlnaRenderer>>(emptyList()) }
        var plexPlayers by remember { mutableStateOf<List<PlexPlayer>>(emptyList()) }
        var scanning by remember { mutableStateOf(true) }

        // DLNA discovery is a few seconds of UDP waiting, so it runs once on open,
        // repeatable from the Scan row. The Plex HTTP check goes first, so its
        // players appear while the UDP wait is still running.
        var scanToken by remember { mutableStateOf(0) }
        LaunchedEffect(scanToken) {
            scanning = true
            plexPlayers = App.playback.findPlexPlayers()
            renderers = App.playback.findCastDevices()
            scanning = false
        }

        // Some Plex players (e.g. an Apple TV feeding a receiver) report a volume
        // they don't actually own. Disabled rather than left to lie, dimmed
        // rather than hidden so the page doesn't jump.
        val faderLive = plexCasting == null ||
            plexVolume != PlaybackController.PlexVolume.NotOurs

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(onBack = { goBack() }, title = "Output")

                Column(
                    modifier = Modifier
                        .padding(horizontal = px(80), vertical = px(32))
                        .alpha(if (faderLive) 1f else 0.35f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AppText("Volume", pxSp(LightType.DETAIL_PX))
                        AppText(
                            if (faderLive) "${(volume * 100).roundToInt()}%" else "on the TV",
                            pxSp(LightType.DETAIL_PX),
                            dim = true,
                        )
                    }
                    Spacer(Modifier.height(px(26)))
                    VolumeFader(volume, enabled = faderLive)
                }

                Spacer(Modifier.height(px(20)))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { SectionLabel("Playing on") }
                    item {
                        OutputRow(
                            AppIcons.Smartphone,
                            "This device",
                            selected = casting == null && plexCasting == null,
                        ) {
                            if (casting != null) App.playback.stopCasting()
                            if (plexCasting != null) App.playback.stopPlexCasting()
                        }
                    }
                    item {
                        OutputRow(AppIcons.Bluetooth, "Bluetooth", selected = false, enabled = false)
                    }

                    item { SectionLabel(if (scanning) "Network - searching…" else "Network") }
                    items(renderers, key = { it.id }) { renderer ->
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = renderer.name,
                            selected = casting?.id == renderer.id,
                        ) {
                            if (casting?.id == renderer.id) {
                                App.playback.stopCasting()
                            } else {
                                App.playback.castTo(renderer)
                            }
                        }
                    }
                    items(plexPlayers, key = { "plex-" + it.id }) { p ->
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = p.name,
                            selected = plexCasting?.id == p.id,
                        ) {
                            if (plexCasting?.id == p.id) {
                                App.playback.stopPlexCasting()
                            } else {
                                App.playback.castToPlex(p)
                            }
                        }
                    }
                    if (!scanning && renderers.isEmpty() && plexPlayers.isEmpty()) {
                        item {
                            AppText(
                                "No network speakers found. They need to be on the same Wi-Fi.",
                                pxSp(LightType.DETAIL_PX),
                                lineHeight = pxSp(LightType.DETAIL_LINE_PX),
                                dim = true,
                                modifier = Modifier.padding(horizontal = px(80), vertical = px(20)),
                            )
                        }
                    }
                    if (!scanning) {
                        item {
                            OutputRow(AppIcons.Refresh, "Scan again", selected = false) { scanToken++ }
                        }
                    }

                    item {
                        AppText(
                            "Bluetooth speakers and headphones play automatically when they're " +
                                "paired - Light handles that routing, so it can't be switched here.",
                            pxSp(LightType.DETAIL_PX),
                            lineHeight = pxSp(LightType.DETAIL_LINE_PX),
                            dim = true,
                            modifier = Modifier.padding(horizontal = px(80), vertical = px(31)),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VolumeFader(volume: Float, enabled: Boolean = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(px(31)),
        ) {
            AppIcon(AppIcons.VolumeDown, size = px(56))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(px(71))
                    // A disabled control shouldn't move under the thumb either.
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun ratioAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
                            var r = ratioAt(down.position.x)
                            App.playback.setVolume(r)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                r = ratioAt(change.position.x)
                                App.playback.setVolume(r)
                                if (!change.pressed) break
                                change.consume()
                            }
                            App.playback.setVolume(r)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AppProgressBar(volume)
            }
            AppIcon(AppIcons.VolumeUp, size = px(56))
        }
    }

    @Composable
    private fun OutputRow(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
    ) {
        TextRow(
            title = label,
            leading = {
                AppIcon(icon, size = px(56), modifier = if (selected) Modifier else Modifier.alpha(0.5f))
            },
            trailing = { if (selected) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
            onClick = if (enabled) onClick else null,
        )
    }
}
