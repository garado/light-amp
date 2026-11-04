package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.data.MusicSource
import com.garado.amp.data.SourceKind
import com.garado.amp.data.newSourceId
import com.garado.amp.data.SubsonicClient
import com.garado.amp.data.SubsonicConfig
import com.garado.amp.data.SubsonicException
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Edit the server connection in place. Fields are tap-to-edit, same as the login form.
 *
 * The cache clears only when the address or username changes, since those are
 * the fields that can point at a different library. A password change keeps
 * the same library in place.
 */
class ServerScreen(
    sealed: SealedLightActivity,
    /** Which source is being edited; the active one when not given. */
    private val sourceId: String? = null,
    /** Start blank and create a new source on save, rather than editing one. */
    private val adding: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // Held on the screen object, not in composition state. Opening a field's text editor
    // tears this screen's composition down, which would reset any remember{} in Content().
    // The screen object itself survives on the back stack. Same pattern as PlaylistsScreen.
    private var nickname by mutableStateOf("")
    private var url by mutableStateOf<String?>(null)
    private var user by mutableStateOf<String?>(null)
    // null means unchanged. The stored password is never seeded into this field.
    private var newPass by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)

    // Set the first time Save succeeds while adding, so a second Save updates that
    // source instead of minting another one.
    private var createdId: String? = null

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val source = if (adding) null else sources.firstOrNull { it.id == sourceId } ?: active
        val config = source?.toConfig()

        // Seed from the stored config once it arrives, without clobbering edits.
        LaunchedEffect(config) {
            val current = config ?: return@LaunchedEffect
            if (url == null) url = current.baseUrl
            if (user == null) user = current.username
        }

        val address = url.orEmpty()
        val name = user.orEmpty()
        val storedLength = config?.password?.length ?: 0
        val passwordSet = newPass?.isNotEmpty() ?: (storedLength > 0)
        val dots = "•".repeat((newPass?.length ?: storedLength).coerceIn(0, 24))

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Add Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(status ?: "Tap a field to change it") }
                if (source == null) {
                    item {
                        TextRow(
                            title = "Name",
                            value = nickname.ifBlank { "Required" },
                            onClick = { edit("Name", nickname) { nickname = it } },
                        )
                    }
                }
                if (source != null) {
                    item {
                    TextRow(
                        title = "Name",
                        value = source.name,
                        onClick = {
                            edit("Name", source.name) { text ->
                                App.scope.launch {
                                    App.settings.saveSource(source.copy(name = text.trim()))
                                }
                            }
                        },
                    )
                    }
                }
                item {
                    TextRow(
                        title = "Address",
                        value = address.ifBlank { "Not set" },
                        onClick = { edit("Address", address) { url = it } },
                    )
                }
                item {
                    TextRow(
                        title = "Username",
                        value = name.ifBlank { "Not set" },
                        onClick = { edit("Username", name) { user = it } },
                    )
                }
                item {
                    TextRow(
                        title = "Password",
                        value = when {
                            !passwordSet -> "Not set"
                            newPass != null -> "$dots · changed"
                            else -> dots
                        },
                        // Opens empty; submitting nothing leaves the stored password alone.
                        onClick = {
                            navigateTo<String?>(
                                { PasswordEntryScreen(it, title = "New password") },
                                resultCallback = { text -> if (text != null) newPass = text },
                            )
                        },
                    )
                }
                item { SectionLabel("Connection") }
                item {
                    TextRow(
                        title = if (saving) "Checking…" else "Save",
                        onClick = {
                            if (saving) return@TextRow
                            if (source == null && nickname.isBlank()) {
                                status = "Give this source a name."
                                return@TextRow
                            }
                            val previous = config
                            saving = true
                            status = "Checking…"
                            App.scope.launch {
                                val outcome = verifyAndSave(
                                    address.trim(),
                                    name.trim(),
                                    newPass ?: previous?.password.orEmpty(),
                                    previous,
                                    source,
                                    nickname.trim(),
                                )
                                when (outcome) {
                                    // Navigate on to the library instead of leaving the
                                    // user stuck on a "Saved" form.
                                    is SaveOutcome.Added -> withContext(Dispatchers.Main) {
                                        LibraryNav.selectTab(LibraryTab.ALBUMS)
                                        popToRoot()
                                    }
                                    is SaveOutcome.Said -> {
                                        status = outcome.text
                                        saving = false
                                    }
                                }
                            }
                        },
                    )
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

    /** Verifies each candidate spelling of the address is reachable before saving it. */
    private suspend fun verifyAndSave(
        address: String,
        username: String,
        password: String,
        previous: SubsonicConfig?,
        source: MusicSource?,
        nickname: String,
    ): SaveOutcome {
        if (address.isBlank() || username.isBlank()) {
            return SaveOutcome.Said("Enter an address and username.")
        }
        var lastError: Throwable? = null
        for (candidate in SubsonicConfig.candidates(address)) {
            val config = SubsonicConfig(candidate, username, password)
            val client = SubsonicClient(config)
            val ok = runCatching { client.ping() }
            client.close()
            if (ok.isFailure) {
                lastError = ok.exceptionOrNull()
                continue
            }
            // A changed address or account may point at a different library, so the cache
            // is invalidated. A password-only change keeps the same library.
            val movedServer = previous != null &&
                (previous.baseUrl != candidate || previous.username != username)
            if (movedServer) {
                App.playback.stop()
                App.library.clearCache()
            }
            val existing = source ?: MusicSource(
                id = createdId ?: newSourceId().also { createdId = it },
                kind = SourceKind.SUBSONIC,
                name = nickname,
            )
            App.settings.saveSource(
                existing.copy(
                    baseUrl = candidate,
                    username = username,
                    password = password,
                ),
            )
            if (source == null) {
                App.settings.setActiveSource(existing.id)
                return SaveOutcome.Added
            }
            return SaveOutcome.Said(if (movedServer) "Saved, reloading library" else "Saved")
        }
        return SaveOutcome.Said(explain(lastError))
    }
}

/** What came of pressing Save. */
private sealed interface SaveOutcome {
    /** Shown on the form; the user stays where they are. */
    data class Said(val text: String) : SaveOutcome

    /** A source was added and made active. */
    data object Added : SaveOutcome
}

/** Turns a connection exception into a message that points at a fix. */
private fun explain(error: Throwable?): String {
    if (error == null) return "Couldn't connect."
    // Ktor wraps exceptions, so the cause chain is walked for the real one.
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    // SubsonicException messages are already user-facing text, passed through as-is.
    chain.filterIsInstance<SubsonicException>().firstOrNull()?.let {
        return it.message ?: "Couldn't connect."
    }
    return when {
        chain.any { it is SerializationException } ->
            "That address answered with a web page, not a music server. Check the " +
                "address, and if the server is on plain HTTP enter it as http://…"
        chain.any { it is UnknownHostException } ->
            "Couldn't find that address. Check the spelling, and that this phone is " +
                "on the same network."
        chain.any { it is SSLException } ->
            "The HTTPS certificate wasn't accepted. A self-signed certificate needs " +
                "http:// instead."
        chain.any { it is ConnectException || it is SocketTimeoutException } ->
            "Nothing answered at that address. Check the server is running and the " +
                "port is right."
        else -> error.message ?: "Couldn't connect."
    }
}
