package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.garado.amp.App
import com.garado.amp.data.primaryAlbumArtist
import com.garado.amp.ui.components.EmptyState
import com.garado.amp.ui.components.ActionList
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackActionsScreen(
    sealed: SealedLightActivity,
    private val trackId: String,
    private val queueIndex: Int? = null,
    /** Set when opened from the player, so the sheet tracks whatever is playing now
     *  instead of the track that was playing when it opened. */
    private val followCurrent: Boolean = false,
    private val showLike: Boolean = true,
    /** Cleared by the queue: what's queued is already downloaded or already streaming. */
    private val showDownload: Boolean = true,
    private val showRating: Boolean = true,
    private val showAddToPlaylist: Boolean = true,
    /** Set by the player; a cover at full width is only about what's playing. */
    private val onShowArtwork: (() -> Unit)? = null,
    /** True when the cover already fills the screen, which flips this row to go back out. */
    private val artworkShowing: Boolean = false,
    /** Set by lists that support multi-select; starts selection on this track. */
    private val onSelect: (() -> Unit)? = null,
    // When set (opened from a playlist detail), adds a "Remove from Playlist" row.
    private val onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Set by the player, whose bottom bar no longer has a cast button of its own. */
    private val onOutput: (() -> Unit)? = null,
    /** Set by the player, whose bottom bar no longer has a queue button of its own. */
    private val onQueue: (() -> Unit)? = null,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // Status for the radio row, held on the screen (not composition state), same as
    // ServerScreen's status.
    private var status by mutableStateOf<String?>(null)
    private var tuning by mutableStateOf(false)

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        val playing by App.playback.currentTrack.collectAsState()
        val id = if (followCurrent) playing?.id ?: trackId else trackId
        // Prefers the library's copy over the queue's, since like/rating edits update the cache.
        val track = tracks.firstOrNull { it.id == id } ?: playing?.takeIf { it.id == id }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val downloaded = id in downloadedIds
        val queue by App.playback.queue.collectAsState()
        val queueIndexOf = queue.indexOfFirst { it.id == id }
        val inQueue = queueIndexOf >= 0
        val playsNext = queueIndexOf == App.playback.index.collectAsState().value + 1
        val isCurrent = playing?.id == id

        ListScreen(onBack = { goBack() }, title = track?.title ?: "Track", subtitle = track?.artist) {
            if (track == null) {
                EmptyState("Track not found")
                return@ListScreen
            }
            ActionList {
                status?.let { SectionLabel(it) }
                if (onSelect != null) {
                    TextRow(title = "Select") {
                        onSelect.invoke()
                        goBack()
                    }
                }
                if (queueIndex != null) {
                    TextRow(title = "Remove from Queue") {
                        App.playback.removeFromQueue(queueIndex)
                        goBack()
                    }
                }
                if (onRemoveFromPlaylist != null) {
                    TextRow(title = "Remove from Playlist") {
                        onRemoveFromPlaylist.invoke()
                        goBack()
                    }
                }
                if (!isCurrent) {
                    if (!playsNext) {
                        TextRow(title = "Play Next") {
                            App.playback.playNext(listOf(track))
                            goBack()
                        }
                    }
                    if (!inQueue) {
                        TextRow(title = "Add to Queue") {
                            App.playback.addToQueue(listOf(track))
                            goBack()
                        }
                    }
                }
                // Builds a queue starting on this song and following the server's radio
                // suggestions. See LibraryRepository.radioFrom.
                if (source.supportsRadio) {
                    TextRow(title = "Start Song Radio") {
                        if (tuning) return@TextRow
                        tuning = true
                        status = "Finding songs…"
                        App.scope.launch {
                            val radio = App.library.radioFrom(track)
                            // The player's looper is Main. See PlaybackController.
                            withContext(Dispatchers.Main) {
                                tuning = false
                                if (radio.isEmpty()) {
                                    status = "No radio for this song"
                                } else if (isCurrent) {
                                    // Replaces what's queued after the current song instead of
                                    // restarting. The radio's first entry is the seed itself, dropped.
                                    status = null
                                    App.playback.replaceUpcoming(radio.drop(1), name = "${track.title} Radio")
                                    goBack()
                                } else {
                                    status = null
                                    App.playback.playQueue(radio, 0, name = "${track.title} Radio")
                                    replaceWithPlayer()
                                }
                            }
                        }
                    }
                }
                if (showAddToPlaylist && source.supportsPlaylists) {
                    TextRow(title = "Add to Playlist") {
                        // Dismisses this sheet too once a playlist is picked.
                        navigateTo<Unit>(
                            { AddToPlaylistScreen(it, track.id) },
                            resultCallback = { goBack() },
                        )
                    }
                }
                if (onShowArtwork != null) {
                    TextRow(
                        title = if (artworkShowing) "Hide Full Artwork" else "Show Full Artwork",
                    ) {
                        onShowArtwork.invoke()
                    }
                }
                if (onOutput != null) {
                    TextRow(title = "Output") { onOutput.invoke() }
                }
                if (onQueue != null) {
                    TextRow(title = "Queue") { onQueue.invoke() }
                }
                if (showLike && source.supportsLikes) {
                    TextRow(title = if (track.liked) "Unlike" else "Like") {
                        App.scope.launch { App.library.setTrackLiked(track, !track.liked) }
                        goBack()
                    }
                }
                if (showRating && source.supportsRatings) {
                    TextRow(title = "Rating", value = ratingLabel(track.rating)) {
                        go { RatingScreen(it, track.id, track.title, track.rating, isAlbum = false) }
                    }
                }
                // Uses the record's primary credit, since there's no single album/artist
                // parent to navigate back through. See primaryAlbumArtist.
                val artist = track.primaryAlbumArtist()
                if (track.albumId != null) {
                    TextRow(title = "Go to Album") {
                        openAlbum(track.albumId, Parent.artist(artist))
                    }
                }
                TextRow(title = "Go to Artist") {
                    openArtist(artist, Parent.tab(LibraryTab.ARTISTS))
                }
                if (showDownload && source.supportsDownloads) {
                    if (downloaded) {
                        TextRow(title = "Remove from Downloads") {
                            App.scope.launch { App.downloader.remove(track.id) }
                            goBack()
                        }
                    } else {
                        TextRow(title = "Add to Downloads") {
                            App.downloader.enqueue(listOf(track))
                            goBack()
                        }
                    }
                }
            }
        }
    }
}
