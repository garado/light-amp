package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

/**
 * Picks list vs. grid layout for an album list. Reached from the list's own
 * title, not Settings. Only offered while artwork is on.
 */
class AlbumViewScreen(
    sealed: SealedLightActivity,
    /** Editing an artist's own page rather than the whole album list. */
    private val forArtist: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val setting = if (forArtist) App.settings.artistAlbumGrid else App.settings.albumGrid
        val grid by setting.collectAsState(initial = false)

        // Named, because the two pickers are otherwise identical and setting one
        // no longer changes the other.
        ListScreen(
            onBack = { goBack() },
            title = "View",
            subtitle = if (forArtist) "An artist's albums" else "All albums",
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Choice("List", chosen = !grid) { choose(false) }
                Choice("Grid", chosen = grid) { choose(true) }
            }
        }
    }

    @Composable
    private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
        TextRow(
            title = label,
            onClick = onClick,
            trailing = { if (chosen) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    private fun choose(grid: Boolean) {
        App.scope.launch {
            if (forArtist) App.settings.setArtistAlbumGrid(grid) else App.settings.setAlbumGrid(grid)
        }
        // Reported so the More screen that pushed this closes with it. See MoreScreen.
        goBack(Unit)
    }
}
