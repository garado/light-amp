package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.data.MusicSource
import com.garado.amp.data.SourceKind
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.PlayAllRow
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.garado.amp.data.LocalLibrary
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Reading the phone's own music; see LocalLibrary and lighttool.toml. */
private val READ_MEDIA_AUDIO = LocalLibrary.PERMISSION

/**
 * Switches which library the app is showing. Adding or editing a source lives in
 * Settings under Sources, see [SourceListScreen]. Each source keeps its own cache,
 * downloads, and likes, so switching is a swap, not a re-sync.
 */
class SourcesScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val activeLibrary by App.settings.libraryId.collectAsState(initial = null)

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                sources.forEach { source ->
                    val chosen = source.id == active?.id
                    item(key = source.id) { SectionLabel(source.name) }
                    items(
                        rowsUnder(source, activeLibrary.takeIf { chosen }),
                        key = { "${source.id}/${it.libraryId ?: "all"}" },
                    ) { entry ->
                        SubRow(
                            name = entry.name,
                            selected = chosen && activeLibrary == entry.libraryId,
                            onClick = { switchTo(source, entry.libraryId) },
                        )
                    }
                }
            }
        }
    }

    /** A row belonging to the source above it: one of its folders, or its page. */
    @Composable
    private fun SubRow(name: String, selected: Boolean = false, onClick: () -> Unit) {
        TextRow(
            title = name,
            modifier = Modifier.padding(start = px(FOLDER_INDENT_PX)),
            onClick = onClick,
            trailing = { if (selected) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    /**
     * One row per thing that can be listened to under this source.
     * [activeLibraryId] is shown even when hidden, so the active library is never hidden
     * from this list while it's in use. Which libraries appear is set in LibraryVisibilityScreen.
     */
    private fun rowsUnder(source: MusicSource, activeLibraryId: String?): List<LibraryEntry> = when {
        source.kind == SourceKind.LOCAL ->
            listOf(LibraryEntry(LocalLibrary.FOLDER, null))
        source.libraries.size >= 2 ->
            (if (source.showsAllLibraries) listOf(LibraryEntry(ALL_LIBRARIES, null)) else emptyList()) +
                source.libraries
                    .filter { it.id !in source.hiddenLibraryIds || it.id == activeLibraryId }
                    .map { LibraryEntry(it.name, it.id) }
        else -> listOf(LibraryEntry(source.libraries.firstOrNull()?.name ?: ALL_LIBRARIES, null))
    }

    /** Switches the active library. Playback keeps going since the queue holds whole tracks. */
    private fun switchTo(source: MusicSource, libraryId: String?) {
        LibraryNav.closeSearch()
        val changingLibrary = source.libraryId != libraryId
        App.scope.launch {
            if (changingLibrary) {
                App.settings.saveSource(source.copy(libraryId = libraryId))
            }
            App.settings.setActiveSource(source.id)

            // Must happen after the write above lands: the root screen syncs on
            // appearing by reading the active library from the store, so unwinding
            // too early would re-sync the previous library instead.
            withContext(Dispatchers.Main) { popToRoot() }

            if (source.id == App.source.value.id && changingLibrary) {
                App.library.switchLibrary(libraryId)
            }
        }
    }
}

/**
 * Which of a source's libraries appear on the Sources page. The last shown
 * library can't be hidden, since these rows are the only way to select the source.
 */
class LibraryVisibilityScreen(
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

        ListScreen(onBack = { goBack() }, title = "Shown on Sources") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                item { SectionLabel("Tap to show or hide") }
                item {
                    VisibilityRow(
                        name = ALL_LIBRARIES,
                        shown = source.showsAllLibraries,
                        onToggle = { toggle(null, !source.showsAllLibraries) },
                    )
                }
                items(source.libraries, key = { it.id }) { library ->
                    val shown = library.id !in source.hiddenLibraryIds
                    VisibilityRow(
                        name = library.name,
                        shown = shown,
                        onToggle = { toggle(library.id, !shown) },
                    )
                }
            }
        }
    }

    @Composable
    private fun VisibilityRow(name: String, shown: Boolean, onToggle: () -> Unit) {
        TextRow(
            title = name,
            onClick = onToggle,
            trailing = { if (shown) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    private fun toggle(libraryId: String?, visible: Boolean) {
        App.scope.launch { App.settings.setLibraryVisible(sourceId, libraryId, visible) }
    }
}

/** The row that means the whole server rather than one of its libraries. */
private const val ALL_LIBRARIES = "All Libraries"

/** One row under a source: a library to switch to, or the whole server. */
private data class LibraryEntry(val name: String, val libraryId: String?)

/** How far a folder sits inside its server. */
private const val FOLDER_INDENT_PX = 60


/** Adding sources and reaching each one's settings. [SourcesScreen] only switches libraries. */
class SourceListScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(sources, key = { it.id }) { source ->
                    TextRow(
                        title = source.name,
                        subtitle = subtitleFor(source),
                        onClick = { go { SourceDetailScreen(it, source.id) } },
                    )
                }
                item { SectionLabel("Add") }
                item {
                    PlayAllRow(AppIcons.Add, "Add Source") { go { AddSourceScreen(it) } }
                }
            }
        }
    }
}

/** The three kinds of source that can be added. */
class AddSourceScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())

        ListScreen(onBack = { goBack() }, title = "Add Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel("Subsonic covers Navidrome, Airsonic and Bandcamp") }
                item {
                    PlayAllRow(AppIcons.Add, "Subsonic Server") {
                        go { ServerScreen(it, sourceId = null, adding = true) }
                    }
                }
                item {
                    PlayAllRow(AppIcons.Add, "Plex Server") { go { PlexLinkScreen(it) } }
                }
                item {
                    PlayAllRow(AppIcons.Add, "Jellyfin Server") { go { JellyfinLinkScreen(it) } }
                }
                if (sources.none { it.kind == SourceKind.LOCAL }) {
                    item {
                        PlayAllRow(AppIcons.Smartphone, "Music on This Phone") { addLocal() }
                    }
                }
            }
        }
    }

    private fun addLocal() {
        App.scope.launch {
            val local = MusicSource.local()
            App.settings.saveSource(local)
            App.settings.setActiveSource(local.id)
        }
        goBack()
    }
}

/** Where a source's music comes from, for the row that names it. */
private fun subtitleFor(source: MusicSource): String = when (source.kind) {
    SourceKind.LOCAL -> LocalLibrary.FOLDER
    SourceKind.SUBSONIC, SourceKind.PLEX, SourceKind.JELLYFIN ->
        source.baseUrl.ifBlank { "Not connected" }
}

/**
 * One source's name and connection. Streaming/download quality and offline
 * behavior live on [DataScreen] and [OfflineScreen] instead.
 */
class SourceDetailScreen(
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

        val sync by App.library.syncState.collectAsState()
        // Re-checked on every sync, since granting access happens off-screen.
        var access by remember { mutableStateOf(LocalLibrary.Access.GRANTED) }
        LaunchedEffect(sync) { access = LocalLibrary.access() }
        val canRead = access == LocalLibrary.Access.GRANTED
        val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                if (source.kind == SourceKind.LOCAL && !canRead) {
                    // Without this row, a missing permission just looks like an empty
                    // library: the folder listing fails silently, not with an error.
                    item {
                        TextRow(
                            title = "Allow Music Access",
                            subtitle = if (access == LocalLibrary.Access.BLOCKED_BY_LIGHTOS) {
                                "LightOS is blocking it for this tool"
                            } else {
                                "Needed to read this phone's music folder"
                            },
                            onClick = { audioPermission?.launch() },
                        )
                    }
                }
                if (source.kind == SourceKind.SUBSONIC) {
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                            onClick = { go { ServerScreen(it, sourceId = source.id) } },
                        )
                    }
                } else if (source.kind != SourceKind.LOCAL) {
                    // Plex and Jellyfin sign in by token, so there are no fields to edit;
                    // switching servers means logging out and linking again.
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                        )
                    }
                }
                // The client name reported to the server (Navidrome's "Player", Plex's
                // product/device fields). Blank keeps the app's own default.
                if (source.kind != SourceKind.LOCAL) {
                    item {
                        TextRow(
                            title = "Player name",
                            value = source.playerName.ifBlank { "Amp (default)" },
                            onClick = {
                                navigateTo<String?>(
                                    {
                                        TextEntryScreen(
                                            it,
                                            title = "Player name",
                                            initial = source.playerName,
                                        )
                                    },
                                    resultCallback = { text ->
                                        if (text != null) {
                                            App.scope.launch {
                                                App.settings.saveSource(
                                                    source.copy(playerName = text.trim()),
                                                )
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
                if (source.libraries.size >= 2) {
                    val shown = source.visibleLibraries.size +
                        (if (source.showsAllLibraries) 1 else 0)
                    item { SectionLabel("Library") }
                    item {
                        TextRow(
                            title = "Shown on Sources",
                            value = "$shown of ${source.libraries.size + 1}",
                            onClick = { go { LibraryVisibilityScreen(it, source.id) } },
                        )
                    }
                }
                item {
                    TextRow(
                        title = if (sync.syncing) "Syncing…" else "Sync Now",
                        subtitle = when {
                            sync.syncing -> sync.phase.ifBlank { "Working" }
                            sync.error != null -> sync.error
                            // True when the server rejects scan requests, so new files
                            // won't appear until it's configured to scan on its own.
                            App.library.scanRefused ->
                                "Server won't scan on request - last synced " +
                                    relativeTime(sync.lastSyncedMs)
                            sync.lastSyncedMs > 0L -> "Last synced ${relativeTime(sync.lastSyncedMs)}"
                            source.kind == SourceKind.LOCAL -> "Rescan this phone's music"
                            else -> "Scan the server, then refresh"
                        },
                        onClick = { if (!sync.syncing) App.library.scanAndSyncInBackground() },
                    )
                }
                item { SectionLabel("Remove") }
                item {
                    TextRow(
                        title = if (source.kind == SourceKind.LOCAL) {
                            "Remove Source"
                        } else {
                            "Log Out"
                        },
                        subtitle = "Forgets its library and its downloads",
                        onClick = {
                            // Stops playback first since the queue holds stream URLs
                            // signed for the server about to be forgotten.
                            if (source.id == App.source.value.id) App.playback.stop()
                            App.scope.launch {
                                App.forgetSource(source)
                                App.settings.removeSource(source.id)
                            }
                            goBack()
                        },
                    )
                }
            }
        }
    }
}

