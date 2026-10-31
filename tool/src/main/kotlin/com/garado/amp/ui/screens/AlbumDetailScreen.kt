package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.App
import com.garado.amp.data.Album
import com.garado.amp.data.Track
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.LibraryList
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.NumberedRow
import com.garado.amp.ui.components.SelectionHeader
import com.garado.amp.ui.components.rememberSelection
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.garado.amp.ui.components.HeaderAction
import com.garado.amp.ui.components.ROW_SUB_PX
import com.garado.amp.ui.components.ROW_SUB_LINE_PX
import com.garado.amp.ui.components.formatRunTime
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp

class AlbumDetailScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val current by App.playback.currentTrack.collectAsState()

        var tracks by remember(albumId) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(albumId) { tracks = App.library.getAlbumTracks(albumId) }

        val selection = rememberSelection("album:$albumId")

        LibrarySubPage(LibraryPage.ALBUM, album?.title) {
            run {
                if (selection.active) {
                    SelectionHeader(selection) {
                        openSelectionActions(
                            selection.pick(tracks.orEmpty()) { it.id },
                            selection,
                        )
                    }
                } else {
                    AppHeader(
                        onBack = { goBack() },
                        // The album's name alone. The artist is in the facts
                        // line below, so a second line here would repeat it.
                        title = album?.title ?: "Album",
                        // The album's own menu, not the library's sort: a record
                        // has no order to choose, so the corner that offered a
                        // dead sort now offers the record's verbs — download,
                        // queue, playlist, artist. Same menu as a long-press on
                        // the album's row, reachable from the album itself.
                        rightAction = HeaderAction(
                            AppIcons.MoreHoriz,
                            onLongClick = { go { SettingsScreen(it) } },
                        ) {
                            go { AlbumActionsScreen(it, albumId) }
                        },
                        fitTitle = true,
                    )
                }

                // Outside the list, not its first item: a scrollable row would
                // sit inset for the scrollbar's own lane and lose the top of
                // its height to the list's content padding — this is neither,
                // so it centres on the true screen width and sits close under
                // the header.
                if (!selection.active && album != null) {
                    AlbumFactsRow(album, tracks.orEmpty())
                }

                val list = tracks
                when {
                    list == null -> Loading()
                    else -> LibraryList(
                        anchor = "album:$albumId",
                        headerCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                            NumberedRow(
                                number = track.trackNumber ?: (index + 1),
                                title = track.title,
                                // Only where this track's own credit differs
                                // from the album's — otherwise it's just the
                                // record's own artist, said again.
                                subtitle = if (track.artist != album?.artist) track.artist else "",
                                current = current?.id == track.id,
                                selected = if (selection.active) track.id in selection.selected else null,
                                onClick = {
                                    if (selection.active) {
                                        selection.toggle(track.id)
                                    } else {
                                        App.playback.playQueue(list, index)
                                        go { NowPlayingScreen(it) }
                                    }
                                },
                                onLongClick = {
                                    if (!selection.active) openTrackActions(track.id, selection)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The record's own facts, one centred line above its tracklist: who made
     * it, how many songs, how long it runs. Liking and shuffling are the •••
     * menu's job now — see [AlbumActionsScreen] — so this line states the
     * record rather than offering anything to do to it.
     */
    @Composable
    private fun AlbumFactsRow(album: Album, tracks: List<Track>) {
        AppText(
            albumFacts(album, tracks),
            pxSp(ROW_SUB_PX),
            lineHeight = pxSp(ROW_SUB_LINE_PX),
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(top = px(CARD_TOP_PX), bottom = px(CARD_GAP_PX)),
        )
    }

    /** Artist · N songs · total running time. */
    private fun albumFacts(album: Album, tracks: List<Track>): String {
        val songs = if (tracks.isNotEmpty()) tracks.size else album.songCount
        val lengthMs = if (tracks.isNotEmpty()) tracks.sumOf { it.durationMs } else album.durationMs
        return listOf(
            album.artist,
            "$songs ${if (songs == 1) "song" else "songs"}",
            formatRunTime(lengthMs),
        ).joinToString(" · ")
    }

    @Composable
    private fun Loading() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText("Loading…", pxSp(LightType.DETAIL_PX))
        }
    }
}

/** Room above the record's facts line, tight under the header. */
private const val CARD_TOP_PX = 0

/** Room under the record's facts line, above the tracklist. */
private const val CARD_GAP_PX = 12
