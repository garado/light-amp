package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.garado.amp.ui.components.SectionLabel
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

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
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
                        // The artist is in the facts line below, so the title omits it.
                        title = album?.title ?: "Album",
                        // The album's own action menu, not a sort control. Same menu
                        // as a long-press on the album's row.
                        rightAction = HeaderAction(
                            AppIcons.MoreHoriz,
                            onLongClick = { go { SettingsScreen(it) } },
                        ) {
                            go { AlbumActionsScreen(it, albumId) }
                        },
                        fitTitle = true,
                    )
                }

                // Placed outside the list, not as its first item, so it isn't inset
                // for the scrollbar lane or clipped by the list's content padding.
                if (!selection.active && album != null) {
                    AlbumFactsRow(album, tracks.orEmpty())
                }

                val list = tracks
                val multiDisc = list != null && list.mapNotNull { it.discNumber }.distinct().size > 1
                when {
                    list == null -> Loading()
                    else -> LibraryList(
                        anchor = "album:$albumId",
                        headerCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        var lastDisc: Int? = null
                        list.forEachIndexed { index, track ->
                            if (multiDisc && track.discNumber != lastDisc) {
                                lastDisc = track.discNumber
                                item(key = "disc-${track.discNumber}") {
                                    SectionLabel("Disc ${track.discNumber ?: 1}")
                                }
                            }
                            item(key = track.id) {
                                NumberedRow(
                                    number = track.trackNumber ?: (index + 1),
                                    title = track.title,
                                    subtitle = if (track.artist != album?.artist) track.artist else "",
                                    current = current?.id == track.id,
                                    selected = if (selection.active) track.id in selection.selected else null,
                                    onClick = {
                                        if (selection.active) {
                                            selection.toggle(track.id)
                                        } else {
                                            if (current?.id != track.id) App.playback.playQueue(list, index)
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
    }

    /** Album facts only, artist, song count, run time. Actions live in [AlbumActionsScreen]. */
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
