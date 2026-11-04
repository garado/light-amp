package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.garado.amp.data.Track
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.data.AppSettings
import com.garado.amp.data.DataMode
import com.garado.amp.data.OfflineMode
import com.garado.amp.data.formatBytes
import com.garado.amp.data.formatGb
import com.garado.amp.data.sortSongs
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.EmptyState
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.components.TrackRow
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.LibraryList
import com.garado.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

fun offlineModeLabel(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "Manual"
    OfflineMode.FAVORITES -> "Favorites"
    OfflineMode.ALL -> "Everything"
}

private fun offlineModeDetail(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "Hold a song, album or artist to download it"
    OfflineMode.FAVORITES -> "Liked albums, liked songs and playlists"
    OfflineMode.ALL -> "Favorites first, then the rest of the library"
}

fun dataModeLabel(mode: DataMode): String = when (mode) {
    DataMode.UNRESTRICTED -> "Unrestricted"
    DataMode.LOW_DATA -> "Low Data"
    DataMode.WIFI_ONLY -> "Wi-Fi Only"
}

/**
 * Room for the longest of the six explanations below to wrap rather than be cut
 * off mid-sentence. Two lines covers them all at the widest of these screens'
 * layouts; the cap stays so a row can't grow without bound if one is reworded.
 */
private const val MODE_DETAIL_LINES = 2

private fun dataModeDetail(mode: DataMode): String = when (mode) {
    DataMode.UNRESTRICTED -> "Streaming, downloads and artwork all use cellular data when there's no Wi-Fi"
    DataMode.LOW_DATA -> "Streams music on cellular data, but downloads and artwork wait for Wi-Fi"
    DataMode.WIFI_ONLY -> "Never uses cellular data. Without Wi-Fi, only downloaded items play"
}

/** Everything the user has downloaded, newest first. */
class DownloadsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        // Every source's downloads, not just the active one. See App.downloadsBySource.
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val progress by App.downloader.progress.collectAsState()
        var bySource by remember { mutableStateOf<List<Pair<String, List<Track>>>>(emptyList()) }
        // Sort order comes from the header, same as the song lists. See App.songSort.
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val sorted = remember(bySource, sort, reversed) {
            bySource.map { (name, list) -> name to sortSongs(list, sort, reversed) }
        }
        val total = remember(sorted) { sorted.sumOf { it.second.size } }
        val named = sorted.size > 1
        // Bytes used across every source, not just the active one.
        var bytes by remember { mutableStateOf(0L) }
        LaunchedEffect(downloadedIds, progress.completed) {
            // Disk reads; keep them off the composition thread.
            val read = withContext(Dispatchers.IO) {
                App.downloadsBySource() to App.downloads.usedBytesEverywhere()
            }
            bySource = read.first
            bytes = read.second
        }
        val limit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val paused by App.settings.downloadsPaused.collectAsState(initial = false)
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val severalServers = sources.count { it.supportsDownloads } > 1

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = { goBack() },
                title = "Downloads",
                fitTitle = true,
            )
            if (total == 0 && !progress.active && bytes == 0L) {
                EmptyState("Nothing downloaded yet.\nHold a song, album or artist and choose Download.")
                return@Column
            }
            LibraryList(
                anchor = "downloaded-songs",
                modifier = Modifier.fillMaxSize(),
            ) {
                if (progress.active || paused) {
                    item {
                        // Cancel clears the queue; pause (below) just stops for now.
                        // Rebuilding a cleared queue can take an hour.
                        TextRow(
                            title = "Cancel Downloads",
                            subtitle = "Clears what's still queued",
                            onClick = { App.downloader.cancelAll() },
                            trailing = { AppIcon(AppIcons.Close, size = px(61)) },
                        )
                    }
                    item {
                        TextRow(
                            title = progress.currentTitle ?: "Downloading",
                            // Shown when more than one server downloads, since the row is shared.
                            subtitle = when {
                                paused -> "Paused"
                                severalServers && progress.currentSource != null ->
                                    "Downloading · ${progress.currentSource}"
                                else -> "Downloading"
                            },
                            onClick = { App.downloader.setUserPaused(!paused) },
                            trailing = {
                                AppIcon(
                                    if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                                    size = px(61),
                                )
                            },
                        )
                    }
                }
                item {
                    SectionLabel("$total tracks · ${formatGb(bytes)} / ${formatGb(limit)}")
                }
                // Rows are read-only. Playing from here would resolve through the wrong
                // server for any source that isn't the active one.
                sorted.forEach { (source, list) ->
                    if (named) item(key = "src-$source") { SectionLabel(source) }
                    items(list, key = { "$source-${it.id}" }) { track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            downloaded = true,
                            onClick = {},
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * What one server downloads without being asked.
 *
 * Titled "Auto-Download", not the OfflineMode name the setting keeps.
 * "Offline Mode" reads like a switch that takes the app offline.
 */
class OfflineModeScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val current = sources.firstOrNull { it.id == sourceId }?.offlineMode ?: OfflineMode.MANUAL
        ListScreen(onBack = { goBack() }, title = "Auto-Download") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(OfflineMode.entries.toList()) { mode ->
                    TextRow(
                        title = offlineModeLabel(mode),
                        // A sentence, not a label. See TextRow.subtitleLines.
                        subtitle = offlineModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.setSourceOfflineMode(sourceId, mode) }
                            goBack()
                        },
                        trailing = { if (mode == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

class DataModeScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        ListScreen(onBack = { goBack() }, title = "Data Mode") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(DataMode.entries.toList()) { mode ->
                    TextRow(
                        title = dataModeLabel(mode),
                        // A sentence, not a label. See TextRow.subtitleLines.
                        subtitle = dataModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.setDataMode(mode) }
                            goBack()
                        },
                        trailing = { if (mode == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}


/**
 * Size budget. Offered values stop at what the device can spare.
 * See [com.garado.amp.data.DownloadStore.maxSelectableBytes].
 */
class SizeLimitScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val max = remember { App.downloads.maxSelectableBytes() }
        val free = remember { App.downloads.freeBytes() }
        val used = remember { App.downloads.usedBytesEverywhere() }
        val options = remember(max) {
            val gb = 1024L * 1024 * 1024
            listOf(1L, 2L, 5L, 10L, 20L, 32L, 48L).map { it * gb }.filter { it < max } + listOf(max)
        }

        ListScreen(onBack = { goBack() }, title = "Size Limit") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Both numbers, because a limit is chosen against both: free
                // space alone makes the options look arbitrarily small next to
                // what is already downloaded.
                item {
                    SectionLabel(
                        "${formatBytes(used)} downloaded · ${formatBytes(free)} free",
                    )
                }
                items(options) { bytes ->
                    TextRow(
                        title = formatBytes(bytes),
                        subtitle = if (bytes == max) "Maximum" else null,
                        onClick = {
                            App.scope.launch { App.settings.setDownloadLimit(bytes) }
                            goBack()
                        },
                        trailing = { if (bytes == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

/** Generic destructive confirmation; returns true only if confirmed. */
class ConfirmScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack(false) }, title = title) {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(message) }
                item { TextRow(title = confirmLabel) { goBack(true) } }
                item { TextRow(title = "Cancel") { goBack(false) } }
            }
        }
    }
}
