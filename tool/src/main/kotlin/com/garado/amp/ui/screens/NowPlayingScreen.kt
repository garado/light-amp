package com.garado.amp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garado.amp.App
import com.garado.amp.data.ArtworkMode
import com.garado.amp.data.Connectivity
import com.garado.amp.data.DataMode
import com.garado.amp.data.LyricLine
import com.garado.amp.data.LyricsRepository
import com.garado.amp.data.LastSection
import com.garado.amp.data.RepeatMode
import com.garado.amp.data.Track
import com.garado.amp.data.primaryAlbumArtist
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.AppArtwork
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.HeaderAction
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.AppProgressBar
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.garado.amp.ui.components.TextRole
import com.garado.amp.ui.components.rememberArtwork
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.ListScrollBar
import com.garado.amp.ui.components.ROW_TITLE_PX
import com.garado.amp.ui.components.ROW_TITLE_LINE_PX
import com.garado.amp.ui.components.ROW_ART_PX
import com.garado.amp.ui.components.ROW_GAP_PX
import com.garado.amp.ui.components.ROW_SUB_PX
import com.garado.amp.ui.components.ROW_SUB_LINE_PX
import com.garado.amp.ui.components.SelectionHeader
import com.garado.amp.ui.components.SelectionState
import com.garado.amp.ui.components.rememberSelection
import com.garado.amp.ui.components.TrackMetadataCard
import com.garado.amp.ui.components.HEADER_BAR_PX
import com.garado.amp.ui.components.formatTime
import com.garado.amp.ui.LightType
import com.garado.amp.ui.currentScale
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.garado.amp.ui.components.rowClickable
import com.garado.amp.ui.components.slowLongPress
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.foundation.shape.CircleShape
import com.garado.amp.ui.components.appClickable
import kotlinx.coroutines.launch

/** Where the active synced-lyric line sits in the pane, as a fraction of its height. */
private const val LYRIC_ANCHOR = 0.38f

/**
 * Shared width for the trailing control on a queue row, so the drag handles line
 * up down the right edge of the list.
 */
private const val QUEUE_TRAILING_SLOT_PX = 51

enum class NpView { ARTWORK, QUEUE }

/**
 * Which pane Now Playing shows, and whether the lyrics overlay is up.
 *
 * Held here rather than on the screen instance so other screens can drive it —
 * the full-width artwork view needs to be able to send the user to the queue —
 * and so it survives navigating away and back.
 */
object NowPlayingNav {
    val view = mutableStateOf(NpView.ARTWORK)
    val lyricsOverlay = mutableStateOf(false)

    /**
     * The cover behind the controls — the Cover Only full-artwork layout.
     *
     * Not a screen of its own, deliberately. "The same page with the sleeve
     * behind it" is only true if it *is* the same page: any second copy of the
     * header, seek line and transport would have to be kept in step with this
     * one by hand, and the two earlier attempts at that were both rejected for
     * drifting out of place. So the player draws itself exactly as it always
     * does and this only changes what is behind it.
     */
    val coverOnly = mutableStateOf(false)

    /**
     * Whether the controls are up while the cover fills the screen.
     *
     * Deliberately *not* persistent, unlike [coverOnly]: opening the player puts
     * them back. Hiding them is something you do to look at a sleeve for a
     * moment, not a preference about the page — and a player that opens with no
     * controls on it reads as a fault, with the tap that recovers them
     * discoverable only to someone who already knows it is there. Held here
     * rather than in the screen so that toggling it survives a look at the queue
     * pane; the screen resets it on each open.
     */
    val coverChrome = mutableStateOf(true)
}

class NowPlayingScreen(
    sealed: SealedLightActivity,
    /** Set only by callers that deliberately want the queue, e.g. full-screen art. */
    openOnQueue: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    /**
     * The player is the third of the bar's destinations, so it says so while it
     * is up and hands the section back on the way out — see [LastSection].
     *
     * On the way out rather than leaving it set: what is revealed underneath is
     * a library page or the results, and only this screen knows the moment it
     * stops being the one showing.
     */
    override fun willShow() {
        LibraryNav.record(LastSection.NOW_PLAYING)
    }

    override fun willHide() {
        LibraryNav.record(
            if (LibraryNav.searchActive.value) LastSection.SEARCH else LastSection.LIBRARY,
        )
    }

    init {
        // Opening Now Playing always lands on the playback controls. The view is
        // remembered across navigation so the queue survives a trip into track
        // options, but a fresh open should never drop the user in the list.
        NowPlayingNav.view.value = if (openOnQueue) NpView.QUEUE else NpView.ARTWORK
        // The cover is deliberately *not* reset with it. Which pane you are on is
        // a step in a task — you opened the queue to do something, and coming
        // back to the player later means coming back to the controls. Whether
        // the sleeve fills the screen is a preference about how the player
        // looks, and a preference that undoes itself every time you leave the
        // page is one you have to keep setting.
        //
        // The controls start out of the way every time, whichever way you got
        // here. Cover Only is a page about the picture, and a tap brings them
        // back — one rule rather than a set of arrivals each with its own.
        NowPlayingNav.coverChrome.value = false
    }

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val track by App.playback.currentTrack.collectAsState()
        val queue by App.playback.queue.collectAsState()
        val index by App.playback.index.collectAsState()
        val view = NowPlayingNav.view.value

        PlayerTheme {
            val current = track
            if (current == null) {
                NothingPlaying()
                return@PlayerTheme
            }
            when (view) {
                NpView.ARTWORK -> ArtPlayer(current)
                NpView.QUEUE -> QueuePlayer(current, queue, index)
            }
        }
    }

    /** Reached only by opening the player before anything has been played. */
    @Composable
    private fun NothingPlaying() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Same button as the player itself, so the way out doesn't move
            // depending on whether anything happens to be playing.
            AppHeader(
                onBack = { goBack() },
                title = "Now Playing",
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("Nothing playing", pxSp(LightType.COPY_PX))
            }
        }
    }

    @Composable
    private fun LyricsOverlay(
        current: Track,
        scrim: androidx.compose.ui.graphics.Color,
        modifier: Modifier,
    ) {
        val karaoke by App.settings.karaokeLyrics.collectAsState(initial = true)
        val position by App.playback.positionMs.collectAsState()
        var outcome by remember(current.id) { mutableStateOf<LyricsRepository.Outcome?>(null) }
        var loading by remember(current.id) { mutableStateOf(true) }
        // Looking lyrics up is two network calls — the server, then lrclib.net
        // when the server has none — for something nobody explicitly asked to
        // download, so Wi-Fi Only doesn't. Words downloaded with the song are
        // read either way; the decision is passed down rather than applied
        // here, so the free copy is never withheld along with the paid one.
        //
        // The rule itself stays in App.metadataAllowed; collecting what it
        // depends on is only what makes the answer re-read when the connection
        // or the mode changes, so turning Wi-Fi on fetches them there and then.
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        val unmetered by Connectivity.unmetered.collectAsState(initial = false)
        LaunchedEffect(current.id, dataMode, unmetered) {
            loading = true
            outcome = LyricsRepository.forTrack(
                current,
                App.serverClient.value,
                allowNetwork = App.metadataAllowed(),
            )
            loading = false
        }
        val lyrics = (outcome as? LyricsRepository.Outcome.Found)?.lyrics
        val lines = lyrics?.lines ?: emptyList()
        val synced = (lyrics?.synced == true) && karaoke
        val activeIndex = if (synced) indexForTime(lines, position) else -1

        Box(modifier = modifier.fillMaxWidth().background(scrim)) {
            when {
                loading -> CenteredText("Loading lyrics…")
                outcome == LyricsRepository.Outcome.NotFetched ->
                    CenteredText("Lyrics wait for Wi-Fi")
                outcome == LyricsRepository.Outcome.Unavailable ->
                    CenteredText("Couldn't reach the lyrics service")
                lines.isEmpty() -> CenteredText("No lyrics")
                else -> {
                    val listState = rememberLazyListState()
                    // Hold the active line ~38% down the pane (matching the old
                    // app) instead of pinning it to the very top, and pad the
                    // bottom so the closing lines can still reach that anchor.
                    LaunchedEffect(activeIndex, synced) {
                        if (!synced || activeIndex < 0) return@LaunchedEffect
                        val viewport = listState.layoutInfo.viewportSize.height
                        if (viewport <= 0) return@LaunchedEffect
                        listState.animateScrollToItem(
                            activeIndex,
                            -(viewport * LYRIC_ANCHOR).toInt(),
                        )
                    }
                    // Room under the last line for it to reach the anchor: the
                    // pane's own height less the anchor's share of it. It was a
                    // share of the screen's height, which is only the pane's on
                    // the LP3.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val tailPadding = maxHeight * (1f - LYRIC_ANCHOR)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = px(51),
                                end = px(51),
                                top = px(46),
                                bottom = if (synced) tailPadding else px(46),
                            ),
                        ) {
                            itemsIndexed(lines) { i, line ->
                                AppText(
                                    text = line.text,
                                    size = pxSp(LightType.DETAIL_PX),
                                    lineHeight = pxSp(54),
                                    align = TextAlign.Center,
                                    dim = synced && i != activeIndex,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = px(5)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CenteredText(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
        }
    }

    private fun indexForTime(lines: List<LyricLine>, positionMs: Long): Int {
        var result = -1
        for (i in lines.indices) {
            val t = lines[i].timeMs ?: break
            if (t > positionMs) break
            result = i
        }
        return result
    }

    @Composable
    private fun QueueView(
        queue: List<Track>,
        index: Int,
        selection: SelectionState,
        modifier: Modifier = Modifier,
        horizontalPadding: Dp = px(41),
        listState: LazyListState = rememberLazyListState(),
    ) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        val rowPx = with(LocalDensity.current) { px(160).toPx() }

        // The playing track stays at the top: what's already gone is the least
        // interesting part of a queue. Opening the page jumps there outright,
        // while a track ending animates, so the list is seen to move rather than
        // appearing to have been somewhere else all along.
        var anchored by remember { mutableStateOf(false) }
        LaunchedEffect(index) {
            val target = index.coerceAtLeast(0)
            if (anchored) listState.animateScrollToItem(target) else listState.scrollToItem(target)
            anchored = true
        }

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        ) {
            itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, track ->
                val isCurrent = i == index
                val isPast = i < index
                val isDragging = i == draggingIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(px(160))
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                        .alpha(if (isPast) 0.5f else 1f)
                        .rowClickable(
                            // Tapping the playing row is still play/pause — that
                            // was always the row's job; the button beside it only
                            // said so twice.
                            onClick = {
                                when {
                                    selection.active -> selection.toggle(track.id)
                                    isCurrent -> App.playback.togglePlayPause()
                                    else -> App.playback.jumpTo(i)
                                }
                            },
                            onLongClick = { if (!selection.active) openQueueOptions(i, selection) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selection.active) {
                        val checked = track.id in selection.selected
                        Box(Modifier.size(px(ROW_ART_PX)), contentAlignment = Alignment.Center) {
                            AppIcon(
                                if (checked) AppIcons.Selected else AppIcons.Unselected,
                                size = px(66),
                                tint = if (checked) {
                                    LightThemeTokens.colors.content
                                } else {
                                    LightThemeTokens.colors.contentSecondary
                                },
                            )
                        }
                    } else {
                        AppArtwork(track.coverArtId, size = px(ROW_ART_PX))
                    }
                    Spacer(Modifier.width(px(ROW_GAP_PX)))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCurrent) {
                                AppIcon(AppIcons.Waveform, size = px(46))
                                Spacer(Modifier.width(px(15)))
                            }
                            AppText(
                                track.title,
                                pxSp(ROW_TITLE_PX),
                                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                                maxLines = 1,
                            )
                        }
                        AppText(
                            track.artist,
                            pxSp(ROW_SUB_PX),
                            lineHeight = pxSp(ROW_SUB_LINE_PX),
                            dim = true,
                            maxLines = 1,
                        )
                    }
                    // Upcoming rows get a drag handle; drag to reorder within the
                    // upcoming portion of the queue (can't move into history/current).
                    if (!isPast && !isCurrent && !selection.active) {
                        AppIcon(
                            AppIcons.Dehaze,
                            size = px(51),
                            modifier = Modifier.width(px(QUEUE_TRAILING_SLOT_PX)).pointerInput(queue.size, index) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = i; dragOffsetY = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetY += amount.y
                                    },
                                    onDragEnd = {
                                        val from = draggingIndex
                                        if (from != null && queue.size > index + 1) {
                                            val target = (from + (dragOffsetY / rowPx).roundToInt())
                                                .coerceIn(index + 1, queue.lastIndex)
                                            if (target != from) App.playback.moveInQueue(from, target)
                                        }
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = { draggingIndex = null; dragOffsetY = 0f },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrubButton(
        icon: ImageVector,
        direction: Int,
        size: androidx.compose.ui.unit.Dp = px(107),
        onSkip: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        Box(
            // A fixed tap target, generous beyond the icon, so tap-to-skip and
            // hold-to-scrub have room to land.
            modifier = Modifier.size(px(TRANSPORT_TAP_PX)).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var scrubbing = false
                    val hold = scope.launch {
                        delay(300)
                        scrubbing = true
                        while (isActive) {
                            val pos = App.playback.positionMs.value
                            val dur = App.playback.durationMs.value
                            if (dur > 0) App.playback.seekTo((pos + direction * 5000L).coerceIn(0L, dur))
                            delay(250)
                        }
                    }
                    waitForUpOrCancellation()
                    hold.cancel()
                    if (!scrubbing) onSkip()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = size)
        }
    }

    // --- Player -------------------------------------------------

    /**
     * A black header over full-bleed artwork, with every control floating on the
     * cover itself.
     *
     * Metadata (hero mode only), the seek line and clocks, and the transport are
     * one auto-laid-out column, centred in whatever space is left above the
     * shuffle/repeat row — real Compose layout, not a hand-measured depth for
     * each piece, so they stay centred as a group regardless of screen size or a
     * font-metric change.
     *
     * Cover Only is this same page with the sleeve moved out from its panel to
     * fill everything under the header. Nothing else moves — the controls dim,
     * gain a wash to be read against, and a tap can put them away.
     */
    @Composable
    private fun ArtPlayer(current: Track) {
        val artwork by App.settings.artwork.collectAsState(initial = ArtworkMode.SMALL)
        // The words want the whole panel, so the title goes back to the header
        // it came from rather than sitting behind them.
        val heroTitle = artwork == ArtworkMode.NONE && !NowPlayingNav.lyricsOverlay.value
        // Either of these leaves nothing to put behind the controls: no cover at
        // all, or the lyrics in its place.
        val coverOnly = NowPlayingNav.coverOnly.value &&
            artwork != ArtworkMode.NONE &&
            !NowPlayingNav.lyricsOverlay.value
        // Held in NowPlayingNav, not here, so putting the controls away outlasts
        // a trip to the queue or the library — see NowPlayingNav.coverChrome.
        val chrome = NowPlayingNav.coverChrome.value

        Column(modifier = Modifier.fillMaxSize()) {
            // The same header as every other page — 160px bar, 160px corner
            // squares that take the tap and the hold, icons on the shared edge
            // axis — with the track card in the title's place. It used to be
            // a hand-built copy with the taps hung on the glyphs, which left
            // this one screen with smaller targets and no hold. Kept on OLED
            // black explicitly: the stage below may be a full-bleed cover.
            Box(modifier = Modifier.background(LightThemeTokens.colors.background)) {
                AppHeader(
                    onBack = { goBack() },
                    // Nothing in the card's place in hero mode, where the
                    // title lives on the stage instead — see heroTitle.
                    titleContent = if (heroTitle) {
                        null
                    } else {
                        ({
                            TrackMetadataCard(
                                current.artist,
                                current.album,
                                current.title,
                                onArtistClick = { openArtist(current.primaryAlbumArtist(), Parent.tab(LibraryTab.ARTISTS)) },
                                onAlbumClick = current.albumId?.let { id ->
                                    { openAlbum(id, Parent.artist(current.primaryAlbumArtist())) }
                                },
                            )
                        })
                    },
                    rightAction = HeaderAction(
                        AppIcons.MoreHoriz,
                        onLongClick = { go { SettingsScreen(it) } },
                    ) { openOptions(current.id, artwork, coverOnly) },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Stage(
                    current,
                    fullBleed = coverOnly,
                    // A tap puts the controls away and brings them back; the
                    // long press that opened the cover closes it, which is how
                    // the other two layouts leave as well. (A plain tap that
                    // toggled the cover itself was tried and rejected — too
                    // easy, too rewarding, on a phone meant to be light.)
                    onTap = { if (coverOnly) NowPlayingNav.coverChrome.value = !chrome },
                    onLongPress = {
                        NowPlayingNav.coverOnly.value = !coverOnly
                        // Going full-screen means going to the picture, so the
                        // controls step out of its way; a tap brings them back.
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    },
                )
                if (!coverOnly || chrome) {
                    if (coverOnly) ControlScrim()
                    // A real column, not a set of hand-measured depths: the
                    // centred group takes whatever space the shuffle/repeat
                    // row leaves it and centres itself in it, the way Light
                    // Music's own player does.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CenteredControls(
                                current,
                                heroTitle,
                                barAlpha = if (coverOnly) ON_COVER_ALPHA else 1f,
                            )
                        }
                        // Shuffle and repeat, full strength always — Light Music
                        // never knocks these two back, on black or on a cover alike.
                        SecondaryRow()
                    }
                }
            }
        }
    }

    /**
     * Metadata (hero mode only), the seek line and clocks, and the transport —
     * one column, centred as a group by [ArtPlayer]'s own layout rather than by
     * pixel depths measured up from the screen's edge.
     */
    @Composable
    private fun CenteredControls(current: Track, heroTitle: Boolean, barAlpha: Float) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = px(INSET_PX)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (heroTitle) {
                Column(verticalArrangement = Arrangement.spacedBy(px(HERO_GAP_PX))) {
                    HeroLine(current.artist) {
                        openArtist(current.primaryAlbumArtist(), Parent.tab(LibraryTab.ARTISTS))
                    }
                    val albumId = current.albumId
                    HeroLine(current.album, onClick = if (albumId != null) {
                        { openAlbum(albumId, Parent.artist(current.primaryAlbumArtist())) }
                    } else {
                        null
                    })
                    HeroLine(current.title)
                }
                Spacer(Modifier.height(px(CONTROLS_GAP_PX)))
            }
            SeekBarAndTime(barAlpha)
            Spacer(Modifier.height(px(CONTROLS_GAP_PX)))
            TransportButtons()
        }
    }

    /**
     * The wash the controls sit on once the cover is behind them.
     *
     * There to stop white-on-white, not to dim the picture: it fades out
     * entirely a little above the seek line, so the sleeve is untouched over most
     * of the screen and only the part carrying controls is darkened.
     */
    @Composable
    private fun BoxScope.ControlScrim() {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(px(SCRIM_H_PX))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            LightThemeTokens.colors.background.copy(alpha = SCRIM_ALPHA),
                        ),
                    ),
                ),
        )
    }

    /**
     * The queue in the big player's own geometry.
     *
     * The controls do not move: seek line, transport and the bottom row stay
     * exactly where they were, and only what is behind them changes from cover to
     * list. The header gives its two squares to shuffle and repeat — the queue is
     * where you set how it will be played — and drops the title card, except
     * while selecting, when it becomes the count and the two selection controls.
     * The list shares the seek line's 80px side inset and stops short of the
     * controls.
     */
    @Composable
    private fun QueuePlayer(current: Track, queue: List<Track>, index: Int) {
        val shuffle by App.playback.shuffle.collectAsState()
        val repeat by App.playback.repeatMode.collectAsState()
        val selection = rememberSelection("queue")

        val background = LightThemeTokens.colors.background

        Box(modifier = Modifier.fillMaxSize().background(background)) {
            if (selection.active) {
                SelectionHeader(
                    selection = selection,
                    onDelete = {
                        App.playback.removeFromQueue(selection.selected)
                        // Stay in selection mode with nothing chosen: pruning a
                        // queue is usually more than one pass, and the X is there.
                        selection.begin()
                    },
                    onConfirm = {
                        openSelectionActions(
                            selection.pick(queue) { it.id },
                            selection,
                            // Everything here is in the queue already, and
                            // downloads are a library job — see the track menu.
                            showAddToQueue = false,
                            showDownload = false,
                        )
                    },
                )
            } else {
                Box(modifier = Modifier.background(LightThemeTokens.colors.background)) {
                    AppHeader(
                        onBack = { NowPlayingNav.view.value = NpView.ARTWORK },
                        titleContent = {
                            QueueTitle(
                                // Not the track — that one is on screen already,
                                // in the row marked as playing. This says which
                                // page you are on.
                                title = "Queue",
                                // Where in it you are. Only with something
                                // playing: a queue with nothing current has no
                                // "of" to answer, and "0 of 12" would be a
                                // position no row is at.
                                subtitle = if (index in queue.indices) {
                                    "${index + 1} of ${queue.size}"
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
            }
            val listState = rememberLazyListState()
            Box(
                // Between the header and the bar at the foot: whatever the
                // screen leaves between them, not a height of its own.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = px(QUEUE_TOP_PX), bottom = px(QUEUE_END_PX)),
            ) {
                QueueView(
                    queue,
                    index,
                    selection,
                    Modifier.fillMaxSize(),
                    horizontalPadding = px(INSET_PX),
                    listState = listState,
                )
                // Outside the list's own margins, hard against the screen edge —
                // inside them it sat on top of the drag handles.
                ListScrollBar(listState)
            }
            // Shuffle and repeat as a bar of their own, on the axis the library's
            // tabs use: the queue is where you set how it will be played, and the
            // transport is one tap away on the page this came from.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(px(HEADER_BAR_PX))
                    // Both glyphs' outer edges land where the simplified nav
                    // bar's do — see EDGE_GLYPH_PX. Their slots are the bar's
                    // own square, so the inset is that distance less the slack
                    // the glyph already has inside one.
                    .padding(
                        horizontal = px(EDGE_GLYPH_PX) -
                            (px(HEADER_BAR_PX) - px(QUEUE_TOGGLE_ICON_PX)) / 2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderSlot {
                    HeaderToggle(AppIcons.Shuffle, active = shuffle) { App.playback.toggleShuffle() }
                }
                Spacer(Modifier.weight(1f))
                HeaderSlot {
                    HeaderToggle(
                        if (repeat == RepeatMode.TRACK) AppIcons.RepeatOne else AppIcons.Repeat,
                        active = repeat != RepeatMode.OFF,
                    ) { App.playback.cycleRepeat() }
                }
            }
        }
    }

    /**
     * The queue view's page title: its name, and where in it you are.
     *
     * A column even with nothing under it, so a title that gains a second line
     * doesn't shift up as it arrives.
     */
    @Composable
    private fun QueueTitle(title: String, subtitle: String?) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                title,
                pxSp(LightType.FINE_PX),
                role = TextRole.Subheading,
                maxLines = 1,
                align = TextAlign.Center,
            )
            if (subtitle != null) {
                AppText(
                    subtitle,
                    pxSp(QUEUE_SUBTITLE_PX),
                    lineHeight = pxSp(QUEUE_SUBTITLE_LINE_PX),
                    dim = true,
                    maxLines = 1,
                    align = TextAlign.Center,
                )
            }
        }
    }

    /** One of the header's two 160px squares. */
    @Composable
    private fun HeaderSlot(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier.size(px(HEADER_BAR_PX)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }

    /**
     * Shuffle and repeat in the queue header, in the big player's sizes.
     *
     * Same rule as the other layouts' toggles: the glyph stays full strength and
     * the underline carries the state, because dimming it made an available
     * control look disabled.
     */
    @Composable
    private fun HeaderToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(px(HEADER_BAR_PX)).appClickable(onClick = onClick),
        ) {
            AppIcon(icon, size = px(QUEUE_TOGGLE_ICON_PX))
            Box(
                modifier = Modifier
                    .padding(top = px(UNDERLINE_GAP_PX))
                    .width(px(UNDERLINE_W_PX))
                    .height(px(UNDERLINE_H_PX))
                    .background(
                        if (active) LightThemeTokens.colors.content else Color.Transparent,
                    ),
            )
        }
    }

    /**
     * The cover, or the lyrics in its place — 1080 square, edge to edge.
     *
     * [fullBleed] is Cover Only: the sleeve leaves its panel for the whole area
     * under the header. That area is the screen's width squared, so a square
     * cover fills it exactly — which is also why the header stays put in this
     * mode. Take it away and the box is taller than it is wide, and the sleeve
     * would have to be cropped at the sides to fill it.
     */
    @Composable
    private fun BoxScope.Stage(
        current: Track,
        fullBleed: Boolean,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        // Decoded at the panel's own width, which is what a full-bleed cover fills.
        val image = rememberArtwork(current.coverArtId, currentScale().windowWidthPx)

        // One panel, one occupant. Lyrics take the cover's place rather than
        // sitting over it: a wash dark enough to read against had already hidden
        // most of the sleeve, and the artwork comes straight back on closing.
        val panel = if (fullBleed) {
            Modifier.matchParentSize()
        } else {
            // Everything above the controls: the screen less the block they
            // take, rather than a height of its own — so a taller panel gives
            // the cover more room and a shorter one takes some back, and the
            // seek line stays at the depth the design puts it either way.
            Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
                .padding(bottom = px(PANEL_END_PX))
                .padding(horizontal = px(INSET_PX))
        }

        if (NowPlayingNav.lyricsOverlay.value) {
            LyricsOverlay(current, Color.Transparent, panel)
            return
        }
        if (image == null) return
        // The cover square inside the panel: cropping a sleeve to the panel's
        // shape would be worse than the margins.
        //
        // Long-pressing it opens the cover full-screen — the same gesture the
        // album page uses for the same thing, and the way back is the same press
        // again rather than hunting for the back arrow.
        Box(
            modifier = panel.slowLongPress(onClick = onTap, onLongPress = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Filling rather than squaring off the height: the two are the
                // same number here, and only this one still holds on a screen
                // whose header doesn't leave a square behind it.
                modifier = if (fullBleed) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxHeight().aspectRatio(1f)
                },
            )
        }
    }


    @Composable
    private fun HeroLine(text: String, onClick: (() -> Unit)? = null) {
        if (text.isBlank()) return
        AppText(
            text,
            pxSp(HERO_LINE_PX),
            lineHeight = pxSp(HERO_LINE_LINE_PX),
            role = TextRole.Subheading,
            dim = false,
            maxLines = 1,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee()
                .let { if (onClick != null) it.appClickable(onClick = onClick) else it },
        )
    }

    /** Seek line and its clocks, both inset [INSET_PX] from either edge. */
    @Composable
    private fun SeekBarAndTime(alpha: Float) {
        val position by App.playback.positionMs.collectAsState()
        val duration by App.playback.durationMs.collectAsState()
        var dragRatio by remember { mutableStateOf<Float?>(null) }

        val effectiveDuration = if (duration > 0) duration else 1L
        val ratio = dragRatio ?: (position.toFloat() / effectiveDuration).coerceIn(0f, 1f)

        Column(modifier = Modifier.fillMaxWidth().alpha(alpha)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(BAR_HIT_PX))
                    .pointerInput(duration) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun ratioAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
                            dragRatio = ratioAt(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                dragRatio = ratioAt(change.position.x)
                                if (!change.pressed) break
                                change.consume()
                            }
                            dragRatio?.let { r -> if (duration > 0) App.playback.seekTo((r * duration).toLong()) }
                            dragRatio = null
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AppProgressBar(ratio)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    formatTime(if (dragRatio != null) (ratio * effectiveDuration).toLong() else position),
                    pxSp(TIME_PX),
                    lineHeight = pxSp(TIME_PX),
                )
                Spacer(Modifier.weight(1f))
                AppText(formatTime(duration), pxSp(TIME_PX), lineHeight = pxSp(TIME_PX))
            }
        }
    }

    /** Rewind, play/pause, forward — full white, the only undimmed controls. */
    @Composable
    private fun TransportButtons() {
        val isPlaying by App.playback.isPlaying.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrubButton(AppIcons.FastRewind, direction = -1, size = px(SEEK_BOX_PX)) {
                App.playback.previous()
            }
            Box(
                modifier = Modifier
                    .size(px(TRANSPORT_TAP_PX))
                    .appClickable { App.playback.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    size = px(PLAY_BOX_PX),
                )
            }
            ScrubButton(AppIcons.FastForward, direction = 1, size = px(SEEK_BOX_PX)) {
                App.playback.next()
            }
        }
    }

    /**
     * Shuffle and repeat, ported from Light Music's own player bottom bar
     * exactly: its icons, its full width, its underline, its "1" badge for
     * repeat-one — not this app's Material icon set or its dimming rules.
     */
    @Composable
    private fun SecondaryRow() {
        val shuffle by App.playback.shuffle.collectAsState()
        val repeat by App.playback.repeatMode.collectAsState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(SECONDARY_ROW_PX))
                .padding(horizontal = 2f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                UnderlinedIconButton(
                    icon = LightIcons.SHUFFLE,
                    underlined = shuffle,
                    contentDescription = if (shuffle) "Shuffle on" else "Shuffle off",
                    onClick = { App.playback.toggleShuffle() },
                )
            }
            Box(Modifier.weight(1f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                RepeatButton(mode = repeat, onClick = { App.playback.cycleRepeat() })
            }
        }
    }

    /** Light Music's repeat button: the loop glyph, ringed with a "1" in repeat-one. */
    @Composable
    private fun RepeatButton(mode: RepeatMode, onClick: () -> Unit) {
        Box(modifier = Modifier.lightClickable(onClick = onClick)) {
            UnderlinedIconButton(
                icon = LightIcons.LOOP,
                underlined = mode != RepeatMode.OFF,
                contentDescription = "Repeat: ${mode.name.lowercase()}",
                onClick = null,
            )
            if (mode == RepeatMode.TRACK) {
                LightText(
                    text = "1",
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = (-4).dp, x = 3.dp)
                        .background(LightThemeTokens.colors.background, CircleShape)
                        .padding(horizontal = 3.dp, vertical = 0.75.dp),
                )
            }
        }
    }

    /** An icon with an optional underline beneath it (icon-width) — Light Music's own. */
    @Composable
    private fun UnderlinedIconButton(
        icon: LightIconConfiguration,
        underlined: Boolean,
        contentDescription: String,
        onClick: (() -> Unit)?,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier,
        ) {
            LightIcon(icon = icon, size = BOTTOM_BAR_ICON_UNITS, contentDescription = contentDescription)
            Box(
                Modifier
                    .width(BOTTOM_BAR_ICON_UNITS.gridUnitsAsDp())
                    .height(2.dp)
                    .background(if (underlined) LightThemeTokens.colors.content else Color.Transparent),
            )
        }
    }

    private fun openOutput() {
        go { OutputScreen(it) }
    }

    private fun openOptions(
        trackId: String,
        artworkMode: ArtworkMode,
        /** Passed in rather than read again: the player has already decided it. */
        coverOnly: Boolean,
    ) {
        go {
            TrackActionsScreen(
                it,
                trackId,
                followCurrent = true,
                // Only worth offering when there is a cover to enlarge; with
                // artwork off there is nothing to show.
                //
                // In Cover Only it stays as the way back out too. A hold on the
                // cover toggles it either way, but that gesture is invisible —
                // the menu is where you look when you don't already know it
                // exists.
                onShowArtwork = if (artworkMode == ArtworkMode.SMALL) {
                    {
                        // Replaces this sheet rather than stacking on it, so back
                        // from the artwork lands on the player as intended.
                        goBack()
                        NowPlayingNav.coverOnly.value = !coverOnly
                        // Going full-screen means going to the picture, so the
                        // controls step out of its way; a tap brings them back.
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    }
                } else {
                    null
                },
                artworkShowing = coverOnly,
                // The "+" sheet's two remaining jobs, now that it's gone —
                // everything else it did (like, rating, playlists, downloads)
                // is already a row here with the defaults restored above.
                onOutput = { goBack(); openOutput() },
                onQueue = { goBack(); NowPlayingNav.view.value = NpView.QUEUE },
            )
        }
    }

    private fun openQueueOptions(index: Int, selection: SelectionState) {
        val track = App.playback.queue.value.getOrNull(index) ?: return
        go {
            TrackActionsScreen(
                it,
                track.id,
                queueIndex = index,
                showDownload = false,
                onSelect = { selection.begin(track.id) },
            )
        }
    }
}

// --- Big-artwork variant -----------------------------------------------------

/**
 * Geometry for the full-width-artwork player, in LP3 physical pixels.
 *
 * Every depth is measured up from the bottom of the screen and every x in from
 * the nearer edge, exactly as the design states them, so a number here can be
 * checked against the design with a ruler rather than by adding up the elements
 * above it. Nothing states the screen's own size: whatever is not a depth or an
 * inset is what the screen leaves, so another panel gets the same controls at
 * the same depths and a cover sized to the rest.
 */

/** The clocks under the seek line, in physical pixels. */
private const val TIME_PX = 36

/**
 * The block that stands in for the cover when artwork is off: artist, album and
 * title, one size throughout so none of the three reads as a caption for the
 * others — see [HeroLine].
 */
private const val HERO_LINE_PX = LightType.FINE_PX
private const val HERO_LINE_LINE_PX = LightType.FINE_LINE_PX

/** Room between the three lines — wide enough that they read as separate facts. */
private const val HERO_GAP_PX = 24

/**
 * What the controls take at the foot of the player; the panel the lyrics and a
 * small cover fill is everything above it. Clears the seek line's band, which
 * starts 740px up, by a little. The queue page has no controls to clear, so it
 * runs deeper — see [QUEUE_END_PX].
 */
private const val PANEL_END_PX = 760

/** The queue list's bounds: between the header and the shuffle/repeat bar. */
private const val QUEUE_TOP_PX = HEADER_BAR_PX
private const val QUEUE_END_PX = HEADER_BAR_PX

/** The underline that marks a header toggle as on. */
private const val UNDERLINE_GAP_PX = 3
private const val UNDERLINE_W_PX = 51
private const val UNDERLINE_H_PX = 3

/** The seek line's own touch height, and the row's height at the foot of the player. */
private const val BAR_HIT_PX = 80
private const val SECONDARY_ROW_PX = 100

/** The centred group's own side margin, and the gap between its three sections. */
private const val INSET_PX = 80
private const val CONTROLS_GAP_PX = 60

/** A transport button's tap target — generous beyond the icon it centres. */
private const val TRANSPORT_TAP_PX = 200

/**
 * Icon boxes, in physical pixels and every one a multiple of 3 — so each is a
 * whole number of dp as well. A fractional dp puts the glyph's edges between
 * pixels and its strokes go soft, which is exactly what you see on a stem one
 * pixel wide.
 *
 * The ink is smaller than its box: play and pause fill 14/24 of it, the double
 * arrows 12/24. The comments give what a ruler finds on screen.
 */
private const val PLAY_BOX_PX = 102      // 60px of ink
private const val SEEK_BOX_PX = 84       // 42px
/** The queue header's second line — the card's own second line, which it sits beside. */
private const val QUEUE_SUBTITLE_PX = 36
private const val QUEUE_SUBTITLE_LINE_PX = 42

/**
 * Shuffle and repeat on the queue header, sized off the Light Music app's own
 * pair rather than off this header's other glyphs — they are the page's only
 * controls, and at header size they read as an afterthought.
 */
private const val QUEUE_TOGGLE_ICON_PX = 90

/**
 * Shuffle and repeat on the player's own bottom bar — Light Music's exact
 * grid-unit size for the same pair, ported along with the rest of that bar.
 */
private const val BOTTOM_BAR_ICON_UNITS = 2f

/**
 * The same controls once they are standing on a sleeve rather than on black.
 *
 * Well up from the 0.45 these controls carry on black: over a picture they have
 * to hold their own against whatever is behind them, and the wash only does half
 * that job. Judged on the device across several values — the transport stays the
 * only thing at full strength, so the hierarchy survives the higher setting.
 */
private const val ON_COVER_ALPHA = 0.75f

/**
 * The wash under the controls in Cover Only, and how solid it gets at the very
 * bottom of it.
 *
 * 860px clears the topmost control — the seek line's band starts 740px up — so
 * the gradient has run out before it reaches anything, rather than stopping
 * against an edge you can see.
 */
private const val SCRIM_H_PX = 860
private const val SCRIM_ALPHA = 0.82f



