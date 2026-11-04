package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.App
import com.garado.amp.data.MusicFolder
import com.garado.amp.data.MusicSource
import com.garado.amp.data.PlexClient
import com.garado.amp.data.PlexAccount
import com.garado.amp.data.PlexResource
import com.garado.amp.data.SourceKind
import com.garado.amp.data.SourceLibrary
import com.garado.amp.data.newSourceId
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/**
 * Links a Plex account by code: the phone shows a code, the user enters it at
 * plex.tv/link on another device. No password or server address is typed here.
 * For a server not linked to any account, see [PlexManualScreen].
 */
class PlexLinkScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        var code by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("Asking Plex for a code…") }
        var servers by remember { mutableStateOf<List<PlexResource>>(emptyList()) }
        var token by remember { mutableStateOf<String?>(null) }
        // Set when the picked server has more than one music library.
        var pending by remember { mutableStateOf<PendingServer?>(null) }

        LaunchedEffect(Unit) {
            val pin = PlexAccount.requestPin()
            if (pin == null) {
                status = "Couldn't reach plex.tv."
                return@LaunchedEffect
            }
            code = pin.code
            status = "Waiting for you to enter it…"
            val linked = PlexAccount.awaitToken(pin)
            if (linked == null) {
                status = "The code expired. Go back and try again."
                code = null
                return@LaunchedEffect
            }
            token = linked
            status = "Finding your servers…"
            servers = PlexAccount.resources(linked)
            status = if (servers.isEmpty()) "No servers on that account." else "Choose a server"
        }

        ListScreen(onBack = { goBack() }, title = "Link Plex") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(px(40)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(px(31)),
                        ) {
                            AppText("plex.tv/link", pxSp(LightType.COPY_PX), align = TextAlign.Center)
                            code?.let {
                                AppText(it, pxSp(122), align = TextAlign.Center)
                            }
                            AppText(status, pxSp(LightType.DETAIL_PX), dim = true, align = TextAlign.Center)
                        }
                    }
                } else if (pending != null) {
                    val choice = pending!!
                    item { SectionLabel("Which library?") }
                    items(choice.sections, key = { it.id }) { section ->
                        TextRow(title = section.name) {
                            save(choice.source.copy(libraryId = section.id))
                        }
                    }
                    item {
                        TextRow(title = "All libraries") { save(choice.source) }
                    }
                } else {
                    item { SectionLabel(status) }
                    items(servers, key = { it.clientIdentifier }) { server ->
                        TextRow(
                            title = server.name,
                            subtitle = if (server.owned) "Yours" else "Shared with you",
                            onClick = {
                                val accountToken = token ?: return@TextRow
                                status = "Looking at ${server.name}…"
                                App.scope.launch {
                                    val next = prepare(server, accountToken)
                                    if (next == null) {
                                        status = "Couldn't reach ${server.name}."
                                    } else if (next.sections.size < 2) {
                                        save(next.source)
                                    } else {
                                        pending = next
                                    }
                                }
                            },
                        )
                    }
                }
                item { SectionLabel("Server not on an account?") }
                item {
                    TextRow(title = "Enter address and token") {
                        navigateTo<Unit>({ PlexManualScreen(it) }, resultCallback = { goBack() })
                    }
                }
            }
        }
    }

    /**
     * Resolves which of the server's advertised addresses is reachable, then
     * reads its music libraries.
     */
    private suspend fun prepare(server: PlexResource, accountToken: String): PendingServer? {
        val uri = PlexAccount.reachable(server, accountToken) ?: return null
        val pending = inspectPlexServer(
            name = server.name,
            uri = uri,
            token = server.accessToken ?: accountToken,
            machineIdentifier = server.clientIdentifier,
        ) ?: return null
        // Keep the non-winning addresses too; see MusicSource.connections.
        return pending.copy(
            source = pending.source.copy(connections = PlexAccount.candidates(server)),
        )
    }

    private fun save(source: MusicSource) {
        App.scope.launch {
            // Re-linking an already-known server (by machine id) updates it in
            // place; see AppSettings.saveSource. Activate whatever id it returns.
            val id = App.settings.saveSource(source)
            App.settings.setActiveSource(id)
        }
        goBack()
    }
}

/** A server we can reach, and the music libraries on it. */
data class PendingServer(val source: MusicSource, val sections: List<MusicFolder>)

/** Validates that a server is reachable and lists its music libraries. Returns null if not. */
suspend fun inspectPlexServer(
    name: String,
    uri: String,
    token: String,
    machineIdentifier: String? = null,
): PendingServer? {
    val client = PlexClient(uri, token, machineIdentifier.orEmpty())
    try {
        // The machine id is required: without it, tracks can't be added to a playlist.
        val identity = machineIdentifier ?: client.identity() ?: return null
        val sections = runCatching { client.getMusicFolders() }.getOrNull() ?: return null
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.PLEX,
            name = name,
            baseUrl = uri,
            token = token,
            machineIdentifier = identity,
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return PendingServer(source, sections)
    } finally {
        client.close()
    }
}

/** The fallback: a server address and a token, typed in. */
class PlexManualScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // Held on the screen, not in composition state: opening a field's editor pushes a
    // new screen and tears this composition down. See the same note in ServerScreen.
    private var name by mutableStateOf("")
    private var address by mutableStateOf("")
    private var token by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var pending by mutableStateOf<PendingServer?>(null)
    // Guards against a second tap re-checking (and re-adding) the server while one check is in flight.
    private var checking by mutableStateOf(false)

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "Plex Server") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                val choice = pending
                if (choice != null) {
                    item { SectionLabel("Which library?") }
                    items(choice.sections, key = { it.id }) { section ->
                        TextRow(title = section.name) {
                            save(choice.source.copy(libraryId = section.id))
                        }
                    }
                    item { TextRow(title = "All libraries") { save(choice.source) } }
                    return@ScrollableList
                }
                item { SectionLabel(status ?: "For a server not linked to an account") }
                item {
                    TextRow(title = "Name", value = name.ifBlank { "Required" }) {
                        edit("Name", name) { name = it }
                    }
                }
                item {
                    TextRow(
                        title = "Address",
                        value = address.ifBlank { "http://192.168.1.10:32400" },
                    ) { edit("Address", address) { address = it } }
                }
                item {
                    TextRow(title = "Token", value = token.ifBlank { "X-Plex-Token" }) {
                        edit("Token", token) { token = it }
                    }
                }
                item {
                    TextRow(title = if (checking) "Checking…" else "Save") {
                        if (checking) return@TextRow
                        if (name.isBlank() || address.isBlank() || token.isBlank()) {
                            status = "Fill in all three."
                            return@TextRow
                        }
                        checking = true
                        status = "Checking…"
                        App.scope.launch {
                            val next = inspectPlexServer(
                                name = name.trim(),
                                uri = address.trim().trimEnd('/'),
                                token = token.trim(),
                            )
                            checking = false
                            when {
                                next == null -> status = "Couldn't get in with that address and token."
                                next.sections.size < 2 -> save(next.source)
                                else -> pending = next
                            }
                        }
                    }
                }
            }
        }
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        navigateTo<String?>(
            { TextEntryScreen(it, title = title, initial = initial) },
            resultCallback = { text -> if (text != null) onResult(text) },
        )
    }

    private fun save(source: MusicSource) {
        App.scope.launch {
            // See PlexLinkScreen.save: saveSource may resolve to an existing source's id
            // if this server was already linked under a different address.
            val id = App.settings.saveSource(source)
            App.settings.setActiveSource(id)
        }
        goBack(Unit)
    }
}
