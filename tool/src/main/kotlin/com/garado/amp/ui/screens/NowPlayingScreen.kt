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
 * Which pane Now Playing shows, and whether the lyrics overlay is up. Held
 * here, not on the screen instance, so other screens can drive it and it
 * survives navigating away and back.
 */
object NowPlayingNav {
    val view = mutableStateOf(NpView.ARTWORK)
    val lyricsOverlay = mutableStateOf(false)

    /**
     * The Cover Only full-artwork layout: not a separate screen, just a
     * background change behind the same header, seek line, and transport.
     */
    val coverOnly = mutableStateOf(false)

    /**
     * Whether the controls are up while the cover fills the screen. Not
     * persistent, unlike [coverOnly]; opening the player always restores them.
     * Held here, not in the screen, so toggling it survives a look at the queue
     * pane. The screen resets it on each open.
     */
    val coverChrome = mutableStateOf(true)
}

class NowPlayingScreen(
    sealed: SealedLightActivity,
    /** Set only by callers that deliberately want the queue, e.g. full-screen art. */
    openOnQueue: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    /** Records itself as the last section while up, and hands it back on exit. See [LastSection]. */
    override fun willShow() {
        LibraryNav.record(LastSection.NOW_PLAYING)
    }

    override fun willHide() {
        LibraryNav.record(
            if (LibraryNav.searchActive.value) LastSection.SEARCH else LastSection.LIBRARY,
        )
    }

    init {
        // A fresh open always lands on the controls, not the queue, even though
        // the view is otherwise remembered across navigation.
        NowPlayingNav.view.value = if (openOnQueue) NpView.QUEUE else NpView.ARTWORK
        // Cover Only's own setting is not reset here; it's a page preference.
        // The chrome itself always starts visible; a tap hides it again.
        NowPlayingNav.coverChrome.value = false
    }

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
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
        // Lyrics lookup hits the network (server, then lrclib.net), so it
        // respects Wi-Fi Only via App.metadataAllowed. Collected here so the
        // answer re-reads when the connection or mode changes.
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
                    // Holds the active line at LYRIC_ANCHOR down the pane, not pinned to the top.
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

        // Jumps to the playing track outright on open; animates on later changes.
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
     * A black header over full-bleed artwork, with every control floating on
     * the cover itself. Cover Only moves the sleeve to fill the space under
     * the header; the controls dim and gain a wash to stay readable.
     */
    @Composable
    private fun ArtPlayer(current: Track) {
        val artwork by App.artworkMode.collectAsState()
        // The words want the whole panel, so the title goes back to the header
        // it came from rather than sitting behind them.
        val heroTitle = artwork == ArtworkMode.NONE && !NowPlayingNav.lyricsOverlay.value
        // Either of these leaves nothing to put behind the controls: no cover at
        // all, or the lyrics in its place.
        val coverOnly = NowPlayingNav.coverOnly.value &&
            artwork != ArtworkMode.NONE &&
            !NowPlayingNav.lyricsOverlay.value
        // Held in NowPlayingNav so putting the controls away outlasts a trip to
        // the queue or the library. See NowPlayingNav.coverChrome.
        val chrome = NowPlayingNav.coverChrome.value

        Column(modifier = Modifier.fillMaxSize()) {
            // Explicit background, since the stage below may be a full-bleed cover.
            Box(modifier = Modifier.background(LightThemeTokens.colors.background)) {
                AppHeader(
                    onBack = { goBack() },
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
                    onTap = { if (coverOnly) NowPlayingNav.coverChrome.value = !chrome },
                    onLongPress = {
                        NowPlayingNav.coverOnly.value = !coverOnly
                        // Going full-screen moves the controls out of the way;
                        // a tap brings them back.
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    },
                )
                if (!coverOnly || chrome) {
                    if (coverOnly) ControlScrim()
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CenteredControls(
                                current,
                                heroTitle,
                                barAlpha = if (coverOnly) ON_COVER_ALPHA else 1f,
                            )
                        }
                        // Shuffle and repeat stay at full strength, on black or cover alike.
                        SecondaryRow()
                    }
                }
            }
        }
    }

    /** Metadata (hero mode only), seek line and clocks, and transport, as one centered column. */
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
     * The wash the controls sit on once the cover is behind them. Fades out
     * above the seek line, so only the control area darkens.
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
     * The queue in the big player's own geometry. The header's two corner
     * squares hold shuffle and repeat, except while selecting, when they
     * become the selection count and controls.
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
                        // Stays in selection mode for another pass.
                        selection.begin()
                    },
                    onConfirm = {
                        openSelectionActions(
                            selection.pick(queue) { it.id },
                            selection,
                            // Already in the queue; downloads are a library job.
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
                                title = "Queue",
                                // Only with something playing; "0 of 12" has no
                                // row to point at.
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
                // Outside the list's own margins, hard against the screen edge.
                ListScrollBar(listState)
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(px(HEADER_BAR_PX))
                    // Glyph outer edges land where the simplified nav bar's do. See EDGE_GLYPH_PX.
                    .padding(
                        horizontal = px(EDGE_GLYPH_PX) -
                            (px(HEADER_BAR_PX) - px(QUEUE_TOGGLE_ICON_PX)) / 2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderSlot {
                    HeaderToggle(LightIcons.SHUFFLE, active = shuffle) { App.playback.toggleShuffle() }
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

    /** The queue view's title and position, in a Column so a second line doesn't shift it up. */
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
     * Shuffle and repeat in the queue header. The glyph stays full strength;
     * the underline carries the active state, since dimming read as disabled.
     */
    @Composable
    private fun HeaderToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
        HeaderToggle(active, onClick) { AppIcon(icon, size = px(QUEUE_TOGGLE_ICON_PX)) }
    }

    /** Same toggle, for a LightOS glyph. Light Music's own shuffle, not this app's Material one. */
    @Composable
    private fun HeaderToggle(icon: LightIconConfiguration, active: Boolean, onClick: () -> Unit) {
        HeaderToggle(active, onClick) { AppIcon(icon, size = px(QUEUE_TOGGLE_ICON_PX)) }
    }

    @Composable
    private fun HeaderToggle(active: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(px(HEADER_BAR_PX)).appClickable(onClick = onClick),
        ) {
            icon()
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
     * The cover, or the lyrics in its place, edge to edge. [fullBleed] is
     * Cover Only: the sleeve fills the whole area under the header instead of
     * its usual panel.
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

        // Lyrics take the cover's place rather than sitting over it.
        val panel = if (fullBleed) {
            Modifier.matchParentSize()
        } else {
            // Everything above the controls block, not a fixed height, so the
            // seek line stays at a consistent depth regardless of panel size.
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
        // Long-pressing opens the cover full-screen, the same gesture the album
        // page uses; pressing again closes it.
        Box(
            modifier = panel.slowLongPress(onClick = onTap, onLongPress = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
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

    /** Rewind, play/pause, forward. Full white, the only undimmed controls. */
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
     * Shuffle and repeat, in Light Music's own icons and underline style, not
     * this app's Material icon set.
     */
    @Composable
    private fun SecondaryRow() {
        val shuffle by App.playback.shuffle.collectAsState()
        val repeat by App.playback.repeatMode.collectAsState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(BOTTOM_BAR_PX))
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

    /** An icon with an optional underline beneath it (icon-width). Light Music's own. */
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
        /** Passed in rather than read again, since the player has already decided it. */
        coverOnly: Boolean,
    ) {
        go {
            TrackActionsScreen(
                it,
                trackId,
                followCurrent = true,
                // Only offered when there's a cover to enlarge.
                onShowArtwork = if (artworkMode == ArtworkMode.SMALL) {
                    {
                        // Replaces this sheet rather than stacking on it, so back
                        // from the artwork lands on the player.
                        goBack()
                        NowPlayingNav.coverOnly.value = !coverOnly
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    }
                } else {
                    null
                },
                artworkShowing = coverOnly,
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
 * Geometry for the full-width-artwork player, in LP3 physical pixels. Depths
 * are measured up from the screen's bottom, insets in from the nearer edge,
 * matching the design spec directly.
 */

/** The clocks under the seek line, in physical pixels. */
private const val TIME_PX = 36

/**
 * The block that stands in for the cover when artwork is off: artist, album
 * and title, one size throughout. See [HeroLine].
 */
private const val HERO_LINE_PX = LightType.FINE_PX
private const val HERO_LINE_LINE_PX = LightType.FINE_LINE_PX

/** Room between the three lines, wide enough that they read as separate facts. */
private const val HERO_GAP_PX = 24

/**
 * Space the controls take at the foot of the player. Clears the seek line's
 * band (starting 740px up) by a little. See [QUEUE_END_PX] for the queue page.
 */
private const val PANEL_END_PX = 760

/** The queue list's bounds: between the header and the shuffle/repeat bar. */
private const val QUEUE_TOP_PX = HEADER_BAR_PX
private const val QUEUE_END_PX = HEADER_BAR_PX

/** The underline that marks a header toggle as on. */
private const val UNDERLINE_GAP_PX = 3
private const val UNDERLINE_W_PX = 51
private const val UNDERLINE_H_PX = 3

/** The seek line's own touch height. */
private const val BAR_HIT_PX = 80

/** The centred group's own side margin, and the gap between its three sections. */
private const val INSET_PX = 80
private const val CONTROLS_GAP_PX = 60

/** A transport button's tap target, generous beyond the icon it centres. */
private const val TRANSPORT_TAP_PX = 200

/**
 * Icon boxes, each a multiple of 3 physical pixels so it's a whole number of
 * dp too. A fractional dp softens stroke edges.
 */
private const val PLAY_BOX_PX = 102      // 60px of ink
private const val SEEK_BOX_PX = 84       // 42px
/** The queue header's second line, matched to the card's own second line beside it. */
private const val QUEUE_SUBTITLE_PX = 36
private const val QUEUE_SUBTITLE_LINE_PX = 42

/** Shuffle and repeat on the queue header, sized larger than the header's other glyphs. */
private const val QUEUE_TOGGLE_ICON_PX = 90

/** Shuffle and repeat on the player's bottom bar, Light Music's exact grid-unit size. */
private const val BOTTOM_BAR_ICON_UNITS = 2f

/** Controls over a cover need more contrast than the 0.45 they carry on black. */
private const val ON_COVER_ALPHA = 0.75f

/**
 * The wash under the controls in Cover Only. 860px clears the topmost control
 * (the seek line's band starts 740px up) so the gradient fades out cleanly.
 */
private const val SCRIM_H_PX = 860
private const val SCRIM_ALPHA = 0.82f



