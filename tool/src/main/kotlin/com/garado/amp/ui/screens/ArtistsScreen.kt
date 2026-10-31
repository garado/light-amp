package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.data.Track
import com.garado.amp.data.shuffled
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcons
import com.thelightphone.sdk.ui.LightIcons
import com.garado.amp.ui.components.EmptyState
import com.garado.amp.ui.components.SelectionHeader
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.TrackRow
import com.garado.amp.ui.components.LibraryList
import com.garado.amp.ui.components.AlbumGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.garado.amp.ui.components.ActionList
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.components.PlayAllRow
import com.garado.amp.ui.components.SplitActionRow
import com.garado.amp.ui.components.rememberGridAnchor
import com.garado.amp.ui.components.rememberSelection
import com.garado.amp.ui.LightType
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ArtistDetailScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {

    @Composable
    private fun SongShortcuts() {
        SplitActionRow(
            leftIcon = AppIcons.FormatListBulleted,
            leftLabel = "All Songs",
            onLeft = { go { ArtistSongsScreen(it, name) } },
            center = true,
        )
    }
    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val albums by App.library.albums.collectAsState()
        val artistAlbums = remember(tracks, albums, name) { App.library.albumsForArtist(name) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()

        val grid = App.artistAlbumGrid.collectAsState().value

        LibrarySubPage(LibraryPage.ARTIST, name) {
            AppHeader(
                onBack = { goBack() },
                title = name,
                fitTitle = true,
                // List or grid is a row on More now, with the rest of what can
                // be changed about how this page looks.
                rightAction = libraryCornerAction(LibraryPage.ARTIST, name),
            )
            if (grid) {
                AlbumGrid(
                    albums = artistAlbums,
                    onOpen = { album -> openAlbum(album.id, Parent.Here) },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id) } },
                    // One anchor per artist, and one header item — the song
                    // shortcuts spanning the first row.
                    state = rememberGridAnchor("artist-albums:$name/grid", headerCount = 1),
                ) {
                    // Spans the row, so the two song shortcuts read as a header
                    // over the records rather than as the first one.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongShortcuts()
                    }
                }
                return@LibrarySubPage
            }
            LibraryList(
                anchor = "artist-albums:$name",
                headerCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Liking lives on the artist list's long-press, not here: this
                // page is the discography, and a heart at the top of it was the
                // one row that wasn't a way into the music.
                item { SongShortcuts() }
                // No Shuffle here: this page is a discography, and shuffling a
                // whole artist belongs on the song list instead.
                items(artistAlbums, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.year?.toString() ?: "",
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { openAlbum(album.id, Parent.Here) },
                        onLongClick = { go { AlbumActionsScreen(it, album.id) } },
                    )
                }
            }
        }
    }
}

/**
 * Long-press sheet for an artist: the one place an artist is liked.
 *
 * A heart on the artist page competed with the discography for the top of the
 * screen while being the one row there that didn't lead to music; here it is
 * where every other list keeps its per-item actions.
 */
class ArtistActionsScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val artists by App.library.artists.collectAsState()
        val liked = artists.firstOrNull { it.name == name }?.liked == true
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        val artistTracks = remember(tracks, name) { App.library.tracksForArtist(name) }
        val downloadedArtists by App.library.downloadedArtistNames.collectAsState()
        val fullyDownloaded = artistTracks.isNotEmpty() && name in downloadedArtists

        ListScreen(onBack = { goBack() }, title = name) {
            ActionList {
                if (source.supportsLikes) {
                    TextRow(title = if (liked) "Unlike Artist" else "Like Artist") {
                        App.scope.launch { App.library.setArtistLiked(name, !liked) }
                        goBack()
                    }
                }
                TextRow(title = "All Songs") { go { ArtistSongsScreen(it, name) } }
                if (artistTracks.isNotEmpty() && source.supportsDownloads) {
                    if (fullyDownloaded) {
                        TextRow(title = "Remove from Downloads") {
                            App.scope.launch { App.downloader.removeAll(artistTracks.map { it.id }) }
                            goBack()
                        }
                    } else {
                        TextRow(title = "Download All Albums") {
                            App.downloader.enqueue(artistTracks)
                            goBack()
                        }
                    }
                }
            }
        }
    }
}


class ArtistSongsScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val songs = remember(tracks, name) { App.library.tracksForArtist(name) }
        val current by App.playback.currentTrack.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()

        val selection = rememberSelection("artist-songs:$name")

        LibrarySubPage(LibraryPage.ARTIST_SONGS, name) {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(songs) { it.id }, selection)
                }
            } else {
                AppHeader(
                    onBack = { goBack() },
                    title = name,
                    rightAction = libraryCornerAction(LibraryPage.ARTIST_SONGS, name),
                    fitTitle = true,
                )
            }
            LibraryList(
                anchor = "artist-songs:$name",
                headerCount = if (selection.active) 0 else 1,
                scrollBar = false,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!selection.active) {
                    item {
                        PlayAllRow(LightIcons.SHUFFLE, "Shuffle") {
                            App.playback.playQueue(shuffled(songs), 0)
                            go { NowPlayingScreen(it) }
                        }
                    }
                }
                itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.album,
                        coverArtId = track.coverArtId,
                        downloaded = track.id in downloadedIds,
                        selected = if (selection.active) track.id in selection.selected else null,
                        onClick = {
                            if (selection.active) {
                                selection.toggle(track.id)
                            } else {
                                App.playback.playQueue(songs, index)
                                go { NowPlayingScreen(it) }
                            }
                        },
                        onLongClick = { if (!selection.active) openTrackActions(track.id, selection) },
                    )
                }
            }
        }
    }
}
