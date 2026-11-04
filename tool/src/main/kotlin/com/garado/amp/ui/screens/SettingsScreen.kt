package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.data.AppSettings
import com.garado.amp.data.formatBytes
import com.garado.amp.data.formatGb
import com.garado.amp.data.ArtworkMode
import com.garado.amp.data.DataMode
import com.garado.amp.data.LayoutMode
import com.garado.amp.data.MusicSource
import com.garado.amp.data.SourceKind
import com.garado.amp.data.StreamFormat
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

fun formatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "MP3 (320 kbps)"
    StreamFormat.OPUS -> "Opus (192 kbps)"
    StreamFormat.FLAC -> "FLAC (lossless)"
    StreamFormat.RAW -> "Original file"
}

/** The format alone, for a subtitle that pairs it with another fact. */
fun shortFormatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "MP3"
    StreamFormat.OPUS -> "Opus"
    StreamFormat.FLAC -> "FLAC"
    StreamFormat.RAW -> "Original"
}

/** Both connections on one line, for the server rows on the Data page. */
private fun streamingSummary(server: MusicSource): String =
    if (server.wifiFormat == server.cellularFormat) {
        shortFormatLabel(server.wifiFormat)
    } else {
        "Wi-Fi ${shortFormatLabel(server.wifiFormat)} · " +
            "Cellular ${shortFormatLabel(server.cellularFormat)}"
    }

/** "3 minutes ago" and friends, for the last-synced line. */
fun relativeTime(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    if (delta < 0) return "just now"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** The settings hub: five categories, each a page, each row's subtitle showing its current value. */
class SettingsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        val downloadLimit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        // Reads actual disk usage, not the per-source indexes.
        val used = remember { App.downloads.usedBytesEverywhere() }
        val sourceNames = sources.joinToString(", ") { it.name }
        // True if any source is a server, regardless of which source is active.
        val hasServers = sources.any { it.kind != SourceKind.LOCAL }

        ListScreen(onBack = { goBack() }, title = "Settings") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(title = "Appearance") { go { AppearanceScreen(it) } }
                }
                item {
                    TextRow(title = "Playback") { go { PlaybackScreen(it) } }
                }
                item {
                    TextRow(
                        title = "Sources",
                        value = sourceNames,
                        onClick = { go { SourceListScreen(it) } },
                    )
                }
                if (hasServers) {
                    item {
                        TextRow(
                            title = "Data",
                            value = dataModeLabel(dataMode),
                            onClick = { go { DataScreen(it) } },
                        )
                    }
                    item {
                        TextRow(
                            title = "Offline",
                            value = if (used == 0L) {
                                "Nothing downloaded"
                            } else {
                                "${formatGb(used)} / ${formatGb(downloadLimit)}"
                            },
                            onClick = { go { OfflineScreen(it) } },
                        )
                    }
                }
                item { TextRow(title = "About") { go { AboutScreen(it) } } }
            }
        }
    }
}

/** Playback level settings, not source settings. */
class PlaybackScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val replayGain by App.settings.replayGain.collectAsState(initial = true)

        ListScreen(onBack = { goBack() }, title = "Playback") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    // Uses server-measured loudness (Navidrome ReplayGain tags, Jellyfin's
                    // scan). Only ever attenuates, so it can't introduce clipping.
                    ToggleRow("Replay Gain", replayGain) {
                        App.scope.launch { App.settings.setReplayGain(!replayGain) }
                    }
                }
            }
        }
    }
}

/** Appearance switches; none are specific to any one source. */
class AppearanceScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val invert by App.settings.invertColors.collectAsState(initial = false)
        val karaoke by App.settings.karaokeLyrics.collectAsState(initial = true)
        val hideArtistImages by App.settings.hideArtistImages.collectAsState(initial = true)
        val hideDownloadIcons by App.hideDownloadIcons.collectAsState()
        val artwork by App.settings.artwork.collectAsState(initial = ArtworkMode.SMALL)
        val layoutMode by App.settings.layoutMode.collectAsState(initial = LayoutMode.STANDARD)

        ListScreen(onBack = { goBack() }, title = "Appearance") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    ToggleRow("Invert Colors", invert) {
                        App.scope.launch { App.settings.setInvertColors(!invert) }
                    }
                }
                item {
                    // Hides artwork everywhere and stops the app fetching and decoding it.
                    ToggleRow("Hide Artwork", artwork == ArtworkMode.NONE) {
                        App.scope.launch {
                            App.settings.setArtwork(
                                if (artwork == ArtworkMode.NONE) {
                                    ArtworkMode.SMALL
                                } else {
                                    ArtworkMode.NONE
                                },
                            )
                        }
                    }
                }
                item {
                    // Hidden when Hide Artwork is on, since artists have already lost theirs.
                    if (artwork != ArtworkMode.NONE) {
                        ToggleRow("Hide Artist Photos", hideArtistImages) {
                            App.scope.launch {
                                App.settings.setHideArtistImages(!hideArtistImages)
                            }
                        }
                    }
                }
                item {
                    // Hides the download marks without reflowing layout. See
                    // AppSettings.hideDownloadIcons.
                    ToggleRow("Hide Download Icons", hideDownloadIcons) {
                        App.scope.launch {
                            App.settings.setHideDownloadIcons(!hideDownloadIcons)
                        }
                    }
                }
                item {
                    // On: library is a single page reached via the phone's own buttons.
                    // Off (default): library tabs spread across the bar.
                    val simplified = layoutMode == LayoutMode.SIMPLIFIED
                    ToggleRow("Simplified Library View", simplified) {
                        App.scope.launch {
                            App.settings.setLayoutMode(
                                if (simplified) LayoutMode.STANDARD else LayoutMode.SIMPLIFIED,
                            )
                        }
                    }
                }
                item {
                    ToggleRow("Karaoke Lyrics", karaoke) {
                        App.scope.launch { App.settings.setKaraokeLyrics(!karaoke) }
                    }
                }
            }
        }
    }
}

/**
 * Streaming quality and Data Mode. Download quality lives on [OfflineScreen] instead.
 * Quality is set per server since servers can differ in what formats they can transcode to.
 */
class DataScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        // The phone's own music never crosses the network.
        val servers = sources.filter { it.kind != SourceKind.LOCAL }

        ListScreen(onBack = { goBack() }, title = "Data") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(
                        title = "Data Mode",
                        value = dataModeLabel(dataMode),
                        onClick = { go { DataModeScreen(it) } },
                    )
                }
                if (servers.isNotEmpty()) item { SectionLabel("Streaming Quality") }
                if (servers.size == 1) {
                    val server = servers.first()
                    item(key = "${server.id}/wifi") {
                        TextRow(
                            title = "On Wi-Fi",
                            value = formatLabel(server.wifiFormat),
                            onClick = { go { StreamFormatScreen(it, server.id) } },
                        )
                    }
                    item(key = "${server.id}/cellular") {
                        TextRow(
                            title = "On Cellular",
                            value = formatLabel(server.cellularFormat),
                            onClick = { go { CellularFormatScreen(it, server.id) } },
                        )
                    }
                } else {
                    servers.forEach { server ->
                        item(key = server.id) {
                            TextRow(
                                title = server.name,
                                subtitle = streamingSummary(server),
                                onClick = { go { SourceStreamingScreen(it, server.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Downloaded music, storage limit, per-server auto-download and download quality,
 * and deleting downloads.
 */
class OfflineScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // Held on the screen, not in composition state, since pushing the confirmation
    // screen tears this composition down. See the same note in ServerScreen.
    private var used by mutableStateOf(App.downloads.usedBytesEverywhere())

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val servers = sources.filter { it.supportsDownloads }
        val downloadLimit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        // Refreshed each time this screen returns to the top, since the pages it
        // leads to can change the total.
        LaunchedEffect(Unit) { used = App.downloads.usedBytesEverywhere() }

        ListScreen(onBack = { goBack() }, title = "Offline") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(
                        title = "Downloads",
                        value = if (used == 0L) "Nothing downloaded yet" else formatBytes(used),
                        onClick = { go { DownloadsScreen(it) } },
                    )
                }
                item {
                    TextRow(
                        title = "Storage Limit",
                        value = formatBytes(downloadLimit),
                        onClick = { go { SizeLimitScreen(it) } },
                    )
                }
                if (servers.size == 1) {
                    val server = servers.first()
                    item(key = "${server.id}/auto") {
                        TextRow(
                            title = "Auto-Download",
                            value = offlineModeLabel(server.offlineMode),
                            onClick = { go { OfflineModeScreen(it, server.id) } },
                        )
                    }
                    item(key = "${server.id}/quality") {
                        TextRow(
                            title = "Download Quality",
                            value = formatLabel(server.downloadFormat),
                            onClick = { go { SourceDownloadFormatScreen(it, server.id) } },
                        )
                    }
                } else if (servers.size > 1) {
                    item { SectionLabel("What each server downloads") }
                    servers.forEach { server ->
                        item(key = server.id) {
                            TextRow(
                                title = server.name,
                                subtitle = "${offlineModeLabel(server.offlineMode)} · " +
                                    shortFormatLabel(server.downloadFormat),
                                onClick = { go { SourceOfflineScreen(it, server.id) } },
                            )
                        }
                    }
                }
                // Deletes every server's downloads, not just the active source's.
                // See App.deleteAllDownloads.
                if (servers.size > 1) {
                    item { SectionLabel("Remove") }
                }
                item {
                    TextRow(
                        title = "Delete All Downloads",
                        subtitle = if (servers.size > 1) "Every server's downloads" else null,
                    ) {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Delete All Downloads",
                                    message = "This removes every source's downloaded music " +
                                        "from this phone. Your libraries on the servers are " +
                                        "untouched.",
                                    confirmLabel = "Delete",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch {
                                        App.deleteAllDownloads()
                                        used = App.downloads.usedBytesEverywhere()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Streaming quality for one source, which may not be the active one. */
class StreamFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.wifiFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "On Wi-Fi") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Limited to formats this server can actually send, see MusicSource.streamFormats.
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch { App.settings.setStreamFormat(sourceId, format) }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

/** Streaming quality on cellular; the counterpart to [StreamFormatScreen]. */
class CellularFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.cellularFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "On cellular") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch { App.settings.setCellularFormat(sourceId, format) }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

/** Download quality for one source; the counterpart to [StreamFormatScreen]. */
class SourceDownloadFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.downloadFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "Download quality") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch {
                                App.settings.setSourceDownloadFormat(sourceId, format)
                            }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}


/**
 * One server's streaming quality, reached from its row on the Data page.
 * Only used when there are several servers; a single server's rows sit on the Data page itself.
 */
class SourceStreamingScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Streaming Quality") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                item { SectionLabel("Streaming Quality") }
                item {
                    TextRow(
                        title = "On Wi-Fi",
                        value = formatLabel(source.wifiFormat),
                        onClick = { go { StreamFormatScreen(it, source.id) } },
                    )
                }
                item {
                    TextRow(
                        title = "On Cellular",
                        value = formatLabel(source.cellularFormat),
                        onClick = { go { CellularFormatScreen(it, source.id) } },
                    )
                }
            }
        }
    }
}

/**
 * One server's download behavior: auto-download, quality, and deleting its music.
 * Only used when there are several servers; a single server's rows sit on the Offline page itself.
 */
class SourceOfflineScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Offline") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                item {
                    TextRow(
                        title = "Auto-Download",
                        value = offlineModeLabel(source.offlineMode),
                        onClick = { go { OfflineModeScreen(it, source.id) } },
                    )
                }
                item {
                    TextRow(
                        title = "Download Quality",
                        value = formatLabel(source.downloadFormat),
                        onClick = { go { SourceDownloadFormatScreen(it, source.id) } },
                    )
                }
                item { SectionLabel("Remove") }
                item {
                    TextRow(
                        title = "Delete Downloads",
                        subtitle = "This server's music only",
                    ) {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Delete Downloads",
                                    message = "This removes ${source.name}'s downloaded " +
                                        "music from this phone. The library on the server " +
                                        "is untouched.",
                                    confirmLabel = "Delete",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch { App.deleteDownloadsFor(source) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    TextRow(
        title = label,
        onClick = onToggle,
        trailing = {
            LightIcon(
                icon = if (value) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 2.2f,
            )
        },
    )
}
