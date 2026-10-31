package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.ui.LightType
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.LibraryList
import com.garado.amp.ui.components.TrackRow
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

class PlaylistFolderScreen(
    sealed: SealedLightActivity,
    private val folderName: String,
) : SimpleLightScreen<Unit>(sealed) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val view by App.sortedPlaylists.collectAsState()
        val folderOf by App.playlistFolders.collectAsState()
        val playlists = view.items.filter { folderOf[it.id] == folderName }
        val downloadedPlaylists by App.library.downloadedPlaylistIds.collectAsState()

        LibrarySubPage(LibraryPage.PLAYLISTS, folderName) {
            AppHeader(onBack = { goBack() }, title = folderName, fitTitle = true)
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText("Empty folder", pxSp(LightType.DETAIL_PX), dim = true)
                }
            } else {
                LibraryList(anchor = "folder:$folderName", headerCount = 0) {
                    items(playlists, key = { it.id }) { playlist ->
                        TrackRow(
                            title = playlist.name,
                            subtitle = "",
                            coverArtId = playlist.coverArtId,
                            fallback = AppIcons.QueueMusic,
                            downloaded = playlist.id in downloadedPlaylists,
                            onClick = { go { PlaylistDetailScreen(it, playlist.id, playlist.name) } },
                            onLongClick = { go { PlaylistActionsScreen(it, playlist.id, playlist.name) } },
                        )
                    }
                }
            }
        }
    }
}
