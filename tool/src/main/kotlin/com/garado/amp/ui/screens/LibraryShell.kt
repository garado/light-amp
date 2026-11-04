package com.garado.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.garado.amp.App
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.garado.amp.data.shuffled
import com.garado.amp.data.Playlist
import com.garado.amp.data.TagFilter
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.AlphabetIndex
import com.garado.amp.ui.components.CORNER_ICON_PX
import com.garado.amp.ui.components.HEADER_BAR_PX
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import com.garado.amp.ui.components.ROW_LEAD_PX
import com.garado.amp.ui.components.ROW_GAP_PX
import com.garado.amp.ui.components.ROW_TITLE_LINE_PX
import com.garado.amp.ui.components.ROW_TITLE_PX
import com.garado.amp.ui.components.ROW_ACTION_ICON_PX
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.HeaderAction
import com.garado.amp.ui.components.ArtistRow
import com.garado.amp.ui.components.INDEX_STRIP_PX
import com.garado.amp.ui.components.LIST_EDGE_PX
import com.garado.amp.ui.components.ListScrollBar
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.listPadding
import com.garado.amp.ui.components.SCROLLBAR_LANE_PX
import com.garado.amp.ui.components.rememberScrollTarget
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.garado.amp.ui.components.AlbumGrid
import com.garado.amp.data.Album
import com.garado.amp.ui.components.PlayAllRow
import com.garado.amp.data.LocalLibrary
import com.garado.amp.data.LayoutMode
import com.garado.amp.data.SourceKind
import com.garado.amp.data.Track
import com.garado.amp.ui.components.SelectionState
import com.garado.amp.ui.components.rememberGridAnchor
import com.garado.amp.ui.components.rememberListAnchor
import com.garado.amp.ui.components.rememberSelection
import com.garado.amp.ui.components.SelectionHeader
import com.garado.amp.ui.components.TrackRow
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.garado.amp.ui.components.appClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.garado.amp.ui.components.EmptyState
import com.garado.amp.ui.components.SectionLabel
import com.garado.amp.ui.components.TextRow

/**
 * Height of the app's bottom bars in LP3 physical pixels, the same 4 grid
 * units (160px) the stock LightOS bottom bar uses.
 */
const val BOTTOM_BAR_PX = 160

/** Reading the phone's own music; see LocalLibrary and lighttool.toml. */
private val READ_MEDIA_AUDIO = LocalLibrary.PERMISSION

enum class LibraryTab(val title: String) {
    PLAYLISTS("Playlists"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    ALBUMS("Albums"),
}

internal fun iconFor(tab: LibraryTab): ImageVector = when (tab) {
    LibraryTab.PLAYLISTS -> AppIcons.QueueMusic
    LibraryTab.ARTISTS -> AppIcons.RecordVoiceOver
    LibraryTab.SONGS -> AppIcons.MusicNote
    LibraryTab.ALBUMS -> AppIcons.AlbumStack
}

class ShellActions(
    val nowPlaying: () -> Unit,
    val settings: () -> Unit,
    val search: () -> Unit,
    /** The SDK's full-screen editor. Simplified's way of typing a query. */
    val editSearch: (String) -> Unit,
    /** Carries the showing page's modifiers up with it. See [LibraryPage]. */
    val more: (LibraryPage) -> Unit,
    /** Simplified's centre button: the library index. See [LibraryNav]. */
    val browse: () -> Unit,
    /** Each takes the page to land on when going back. See [Parent]. */
    val openAlbum: (String, Parent) -> Unit,
    val openArtist: (String, Parent) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val openPlaylistFolder: (String) -> Unit,
    val trackOptions: (String, SelectionState?) -> Unit,
    /** Opens the bulk-action sheet for a multi-selection. */
    val selectionActions: (List<Track>, SelectionState) -> Unit,
    val albumOptions: (String) -> Unit,
    /** Long-press on an artist. The only way to like one. */
    val artistOptions: (String) -> Unit,
    val playlistOptions: (String, String) -> Unit,
)

@Composable
fun LibraryShell(
    currentTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit,
    /** Simplified's library index. See [LibraryNav.libraryIndex]. */
    libraryIndex: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    actions: ShellActions,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (searchActive) {
                    SearchView(
                        searchQuery,
                        onSearchQueryChange,
                        onSearchClose,
                        currentTab,
                        actions,
                    )
                } else if (libraryIndex) {
                    LibraryIndex(currentTab, actions)
                } else {
                    TabContent(currentTab, actions)
                }
            }
            // Hidden while the keyboard is up; it would sit unreachable under it.
            if (!LibraryNav.typing.collectAsState().value) {
            Navbar(
                // Search is not its own destination, so it keeps the tab it
                // was started from lit.
                current = if (libraryIndex) null else currentTab,
                onSelect = onSelectTab,
                onMore = {
                    actions.more(if (searchActive) LibraryPage.SEARCH else currentTab.page)
                },
                onSearch = actions.search,
                searchActive = searchActive,
                onNowPlaying = actions.nowPlaying,
                onBrowse = actions.browse,
            )
            }
        }
    }
}

/** One of the four tab lists, with or without its header. Search borrows the body without the header; see [SearchView]. */
@Composable
private fun TabContent(tab: LibraryTab, actions: ShellActions, header: Boolean = true) {
    when (tab) {
        LibraryTab.ALBUMS -> AlbumsTab(actions, header)
        LibraryTab.SONGS -> SongsTab(actions, header)
        LibraryTab.ARTISTS -> ArtistsTab(actions, header)
        LibraryTab.PLAYLISTS -> PlaylistsTab(actions, header)
    }
}

@Composable
private fun SearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    /** The list search was started from. Shown until something is typed. */
    tab: LibraryTab,
    actions: ShellActions,
) {
    val typing by LibraryNav.typing.collectAsState()
    // While the keyboard is up, results follow this field keystroke by keystroke.
    val state = rememberTextFieldState(query)
    val live = if (typing) state.text.toString() else query
    LaunchedEffect(live, typing) { if (typing) onQueryChange(live) }

    val library by App.library.tracks.collectAsState()
    val results = remember(live, library.size) { App.library.search(live) }
    val listState = rememberListAnchor("search")

    // Each row takes back the scrollbar's lane individually, so the list keeps
    // equal margins.
    val lane = px(SCROLLBAR_LANE_PX) - px(LIST_EDGE_PX)
    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = live,
            onEdit = {
                // Simplified's search is the SDK's own full-screen editor, never
                // the inline keyboard.
                if (App.layoutMode.value == LayoutMode.SIMPLIFIED) {
                    actions.editSearch(live)
                } else {
                    LibraryNav.typing.value = true
                }
            },
            onCancel = {
                // Abandons the search entirely, including the typed query, not
                // just the keyboard.
                state.clearText()
                onQueryChange("")
                LibraryNav.typing.value = false
                onClose()
            },
            onMenu = { actions.more(LibraryPage.SEARCH) },
        )
        Box(modifier = Modifier.weight(1f)) {
        if (live.isBlank()) {
            TabContent(tab, actions, header = false)
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(end = px(LIST_EDGE_PX)),
        ) {
            if (live.isNotBlank() && results.isEmpty) {
                item { EmptyState("No results") }
            }
            if (results.artists.isNotEmpty()) {
                item { SectionLabel("Artists") }
                items(results.artists, key = { "ar-${it.name}" }) { artist ->
                    ArtistRow(
                        name = artist.name,
                        subtitle = "",
                        imageId = artist.imageId,
                        modifier = Modifier.padding(end = lane),
                        onClick = {
                            onClose()
                            actions.openArtist(artist.name, Parent.tab(LibraryTab.ARTISTS))
                        },
                    )
                }
            }
            if (results.albums.isNotEmpty()) {
                item { SectionLabel("Albums") }
                items(results.albums, key = { "al-${it.id}" }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        modifier = Modifier.padding(end = lane),
                        onClick = { onClose(); actions.openAlbum(album.id, Parent.artist(album.artist)) },
                        onLongClick = { onClose(); actions.albumOptions(album.id) },
                    )
                }
            }
            if (results.tracks.isNotEmpty()) {
                item { SectionLabel("Songs") }
                items(results.tracks, key = { "tr-${it.id}" }) { track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.artist,
                        coverArtId = track.coverArtId,
                        modifier = Modifier.padding(end = lane),
                        onClick = {
                            // Pushes the player on top; search stays underneath, so
                            // backing out returns to the results, still typed.
                            if (App.playback.currentTrack.value?.id != track.id) {
                                App.playback.playQueue(listOf(track), 0)
                            }
                            actions.nowPlaying()
                        },
                        onLongClick = { actions.trackOptions(track.id, null) },
                    )
                }
            }
        }
        ListScrollBar(listState)
        }
        }
        if (typing) SearchKeyboard(state) { LibraryNav.typing.value = false }
    }
}

/**
 * The search page's header: the query, and a way out.
 *
 * Same header whether or not the keyboard is up; the field doesn't move when
 * the keyboard closes. The cross takes the full 160px corner every other page
 * gives its menu.
 */
@Composable
private fun SearchHeader(
    query: String,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(HEADER_BAR_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .appClickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(px(SEARCH_AXIS_PX)))
            AppIcon(AppIcons.Search, size = px(SEARCH_GLYPH_PX))
            Spacer(Modifier.width(px(ROW_GAP_PX)))
            Box(modifier = Modifier.weight(1f)) {
                AppText(
                    text = query.ifEmpty { "Search" },
                    size = pxSp(SEARCH_TEXT_PX),
                    lineHeight = pxSp(ROW_TITLE_LINE_PX),
                    maxLines = 1,
                )
            }
        }
        // The cross sits in the slot the magnifier used on the tab search came from.
        Box(
            modifier = Modifier
                .width(px(SEARCH_SLOT_PX))
                .fillMaxHeight()
                .appClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppIcons.Close, size = px(SEARCH_GLYPH_PX))
        }
        Box(
            modifier = Modifier
                .size(px(HEADER_BAR_PX))
                .appClickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppIcons.Sort, size = px(CORNER_ICON_PX))
        }
    }
}

/** AppHeader's inner slot, which the cross now stands in. */
private const val SEARCH_SLOT_PX = 80

/** Where the search glyph sits, on the axis the list's own field uses. */
private const val SEARCH_AXIS_PX = LIST_EDGE_PX + ROW_LEAD_PX

/** The search header's type and glyph, sized against ROW_TITLE_PX so they match the rows beneath. */
private val SEARCH_TEXT_PX: Int @Composable get() = ROW_TITLE_PX
private const val SEARCH_GLYPH_PX = ROW_ACTION_ICON_PX



/**
 * The header every tab shares: sort at the left corner, search and now-playing
 * at the right, plus the liked/all switch when the setting enables it.
 */
@Composable
private fun TabHeader(tab: LibraryTab, actions: ShellActions) {
    // Under Simplified the bar already holds the player, so this corner becomes
    // the way back to the library index this tab was opened from instead.
    val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
    AppHeader(
        title = tabTitle(tab, likedOnly(tab), tagFilter(tab)),
        onBack = if (simplified) ({ LibraryNav.openLibraryIndex() }) else null,
        // Expanded only; Simplified already has search in the bar. Opens the
        // keyboard directly, not a search page.
        searchAction = if (simplified) {
            null
        } else {
            ({ LibraryNav.openSearch(withKeyboard = true) })
        },
        fitTitle = true,
        rightAction = HeaderAction(AppIcons.Sort, onLongClick = actions.settings) { actions.more(tab.page) },
    )
}

/** The tab, as the page whose modifiers More shows. */
val LibraryTab.page: LibraryPage
    get() = when (this) {
        LibraryTab.ALBUMS -> LibraryPage.ALBUMS
        LibraryTab.SONGS -> LibraryPage.SONGS
        LibraryTab.ARTISTS -> LibraryPage.ARTISTS
        LibraryTab.PLAYLISTS -> LibraryPage.PLAYLISTS
    }

/**
 * The genre and composer a tab has been narrowed to. See [TagFilterScreen].
 * Only Albums and Songs have one; artists and playlists aren't tagged.
 */
@Composable
fun tagFilter(tab: LibraryTab): TagFilter = when (tab) {
    LibraryTab.ALBUMS -> App.albumsTagFilter.collectAsState().value
    LibraryTab.SONGS -> App.songsTagFilter.collectAsState().value
    else -> TagFilter()
}

/** Whether this tab is currently showing only liked items. */
@Composable
fun likedOnly(tab: LibraryTab): Boolean = when (tab) {
    LibraryTab.ALBUMS -> App.likedAlbumsOnly.collectAsState().value
    LibraryTab.SONGS -> App.likedSongsOnly.collectAsState().value
    LibraryTab.ARTISTS -> App.likedArtistsOnly.collectAsState().value
    // Nothing likes a playlist.
    LibraryTab.PLAYLISTS -> false
}

/**
 * What the tab is called, given the narrowing over it. The title is the only
 * thing on the page that shows a list has been narrowed.
 */
fun tabTitle(tab: LibraryTab, likedOnly: Boolean, filter: TagFilter = TagFilter()): String {
    val base = if (likedOnly) "Liked " + tab.title else tab.title
    val tags = listOf(filter.genre, filter.composer).filter { it.isNotEmpty() }
    return if (tags.isEmpty()) base else (listOf(base) + tags).joinToString(" · ")
}

@Composable
private fun rememberLocalAccess(): Boolean {
    val source = App.source.collectAsState().value
    val sync by App.library.syncState.collectAsState()
    var readable by remember(source.id) { mutableStateOf(true) }
    LaunchedEffect(source.id, sync.lastSyncedMs) {
        readable = source.kind != SourceKind.LOCAL || LocalLibrary.permitted()
    }
    return readable
}

/** The one row a local library shows when it hasn't been granted access yet. */
private fun LazyListScope.localAccessNotice(needed: Boolean, onAsk: () -> Unit) {
    if (!needed) return
    item { PlayAllRow(AppIcons.Smartphone, "Allow Music Access", onClick = onAsk) }
}

/**
 * Why the last sync failed, shown at the top of the list it left behind.
 * A refused login leaves cached rows in place, so this can sit above a library
 * that looks fine but has stopped updating. Tapping opens Settings.
 */
private fun LazyListScope.syncErrorNotice(error: String?, onOpen: () -> Unit) {
    if (error.isNullOrBlank()) return
    item { PlayAllRow(AppIcons.CloudOff, error, onClick = onOpen) }
}

@Composable
private fun AlbumsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedAlbums.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.albumSortReversed.collectAsState(initial = false)
    val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
    // Read unconditionally. Putting the second collectAsState behind && makes
    // it a conditional composable call, which stops recomposing on that state.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val needsAccess = !rememberLocalAccess()
    val syncError = App.library.syncState.collectAsState().value.error
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    val grid = App.albumGrid.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ALBUMS, actions)
        if (grid) {
            val gridState = rememberGridAnchor("tab:albums/grid", headerCount = 1)
            Box(modifier = Modifier.weight(1f)) {
                AlbumGrid(
                    albums = sorted,
                    onOpen = { actions.openAlbum(it.id, Parent.Here) },
                    onLongPress = { actions.albumOptions(it.id) },
                    state = gridState,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RandomAlbumRow(sorted, actions)
                    }
                }
            }
        } else {
            IndexedList(
                anchor = "tab:albums",
                letters = letters,
                // Counts the action row so the A-Z strip still lands correctly.
                // See IndexedList.headerCount.
                headerCount = 1,
                reversed = reversed,
            ) {
                item { RandomAlbumRow(sorted, actions) }
                localAccessNotice(needsAccess) { audioPermission?.launch() }
                syncErrorNotice(syncError, actions.settings)
                items(sorted, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { actions.openAlbum(album.id, Parent.Here) },
                        onLongClick = { actions.albumOptions(album.id) },
                    )
                }
            }
        }
    }
}

/**
 * One record off the shelf, at random.
 *
 * Shuffle's place on the song lists, but not shuffle's job: an album is a thing
 * someone sequenced, so this picks one and plays it in its own order. Obeys the
 * list as it stands, so a filter or a search narrows what can come up.
 */
@Composable
private fun RandomAlbumRow(albums: List<Album>, actions: ShellActions) {
    PlayAllRow(LightIcons.SHUFFLE, "Play Random Album") {
        val album = albums.randomOrNull() ?: return@PlayAllRow
        App.scope.launch {
            // Reading tracks is a database call, off the main thread. Handing
            // them to the player must be on it; ExoPlayer's looper is Main and
            // enforces that. See PlaybackController.
            val queue = App.library.albumQueue(listOf(album.id))
            if (queue.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                App.playback.playQueue(queue, 0)
                actions.nowPlaying()
            }
        }
    }
}

/**
 * A tab list with the A-Z jump strip down its right edge.
 *
 * [letters] is empty to hide the strip. [headerCount] is the row count above
 * the indexed content, so a jump lands on the right item.
 */
@Composable
private fun ColumnScope.IndexedList(
    anchor: String,
    letters: List<Char>,
    headerCount: Int,
    reversed: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberListAnchor(anchor, headerCount)
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.weight(1f)) {
        val indexed = letters.isNotEmpty()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Keeps titles clear of whichever bar is down the right edge.
            contentPadding = listPadding(
                end = px(if (indexed) INDEX_STRIP_PX else SCROLLBAR_LANE_PX),
            ),
            content = content,
        )
        if (!indexed) ListScrollBar(listState)
        // A descending list keeps its index; the strip just reads Z→A to match.
        if (indexed) {
            AlphabetIndex(
                letters = letters,
                target = rememberScrollTarget(listState),
                scope = scope,
                headerCount = headerCount,
                reversed = reversed,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}


@Composable
private fun SongsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedSongs.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.songSortReversed.collectAsState(initial = false)
    val downloadedIds by App.library.downloadedTrackIds.collectAsState()
    val selection = rememberSelection("songs")
    // Read unconditionally. Putting the second collectAsState behind && makes
    // it a conditional composable call, which stops recomposing on that state.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val needsAccess = !rememberLocalAccess()
    val syncError = App.library.syncState.collectAsState().value.error
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)

    Column(Modifier.fillMaxSize()) {
        if (header) {
            if (selection.active) {
                SelectionHeader(selection) {
                    actions.selectionActions(selection.pick(sorted) { it.id }, selection)
                }
            } else {
                TabHeader(LibraryTab.SONGS, actions)
            }
        }
        IndexedList(
            anchor = "tab:songs",
            letters = letters,
            headerCount = if (selection.active) 0 else 1,
            reversed = reversed,
        ) {
            if (!selection.active) {
                item {
                    PlayAllRow(LightIcons.SHUFFLE, "Shuffle") {
                        App.playback.playQueue(shuffled(sorted), 0)
                        actions.nowPlaying()
                    }
                }
                localAccessNotice(needsAccess) { audioPermission?.launch() }
                syncErrorNotice(syncError, actions.settings)
            }
            items(sorted, key = { it.id }) { track ->
                TrackRow(
                    title = track.title,
                    subtitle = track.artist,
                    coverArtId = track.coverArtId,
                    downloaded = track.id in downloadedIds,
                    selected = if (selection.active) track.id in selection.selected else null,
                    onClick = {
                        if (selection.active) {
                            selection.toggle(track.id)
                        } else {
                            if (App.playback.currentTrack.value?.id != track.id) {
                                val index = sorted.indexOfFirst { it.id == track.id }
                                App.playback.playQueue(sorted, index.coerceAtLeast(0))
                            }
                            actions.nowPlaying()
                        }
                    },
                    onLongClick = {
                        if (!selection.active) actions.trackOptions(track.id, selection)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedArtists.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.artistSortReversed.collectAsState(initial = false)
    // Read unconditionally. Putting the second collectAsState behind && makes
    // it a conditional composable call, which stops recomposing on that state.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val downloadedArtists by App.library.downloadedArtistNames.collectAsState()
    // Track-tag artists have no images. Primed from the server's artist records
    // instead, skipped entirely when images are switched off.
    val artistImages = !App.hideArtistImages.collectAsState().value
    LaunchedEffect(artistImages) { if (artistImages) App.library.primeArtistImages() }
    val needsAccess = !rememberLocalAccess()
    val syncError = App.library.syncState.collectAsState().value.error
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ARTISTS, actions)
        IndexedList(
            anchor = "tab:artists",
            letters = letters,
            headerCount = 0,
            reversed = reversed,
        ) {
            localAccessNotice(needsAccess) { audioPermission?.launch() }
            items(sorted, key = { it.name }) { artist ->
                ArtistRow(
                    name = artist.name,
                    subtitle = "${artist.albumCount} albums · ${artist.trackCount} songs",
                    downloaded = artist.name in downloadedArtists,
                    imageId = artist.imageId,
                    onClick = { actions.openArtist(artist.name, Parent.Here) },
                    onLongClick = { actions.artistOptions(artist.name) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedPlaylists.collectAsState()
    val playlists = view.items
    val folderOf by App.playlistFolders.collectAsState()
    val starred by App.starredPlaylists.collectAsState()
    val reversed by App.settings.playlistSortReversed.collectAsState(initial = false)
    val downloadedPlaylists by App.library.downloadedPlaylistIds.collectAsState()
    // getPlaylists returns metadata only, not membership; fetch tracks once here.
    LaunchedEffect(playlists) {
        App.library.primePlaylistTrackIds(playlists.map { it.id })
    }
    val source = App.source.collectAsState().value
    // Keyed on the source so switching sources refreshes the list.
    LaunchedEffect(source.id) { App.library.refreshPlaylists() }

    val starredList = remember(playlists, starred) { playlists.filter { it.id in starred } }
    val byFolder = remember(playlists, folderOf) {
        playlists.filter { folderOf[it.id] != null }.groupBy { folderOf[it.id]!! }
    }
    val folderNames = remember(byFolder) { byFolder.keys.sorted() }
    // Starred-and-ungrouped playlists are pinned at the top, so excluded here.
    val ungrouped = remember(playlists, folderOf, starred) {
        playlists.filter { folderOf[it.id] == null && it.id !in starred }
    }

    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.PLAYLISTS, actions)
        IndexedList(
            anchor = "tab:playlists",
            letters = emptyList(),
            headerCount = starredList.size + folderNames.size,
            reversed = reversed,
        ) {
            items(starredList, key = { "star:${it.id}" }) { playlist ->
                PlaylistRow(playlist, downloadedPlaylists, actions, starred = true)
            }
            items(folderNames, key = { "folder:$it" }) { name ->
                val count = byFolder[name]!!.size
                TrackRow(
                    title = name,
                    subtitle = if (count == 1) "1 playlist" else "$count playlists",
                    coverArtId = null,
                    showIcon = false,
                    onClick = { actions.openPlaylistFolder(name) },
                )
            }
            items(ungrouped, key = { it.id }) { playlist ->
                PlaylistRow(playlist, downloadedPlaylists, actions)
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    downloadedPlaylists: Set<String>,
    actions: ShellActions,
    starred: Boolean = false,
) {
    TrackRow(
        title = if (starred) "★  ${playlist.name}" else playlist.name,
        subtitle = "",
        coverArtId = playlist.coverArtId,
        fallback = AppIcons.QueueMusic,
        downloaded = playlist.id in downloadedPlaylists,
        onClick = { actions.openPlaylist(playlist.id, playlist.name) },
        onLongClick = { actions.playlistOptions(playlist.id, playlist.name) },
    )
}


/**
 * Everything there is to browse, in one list. Simplified's middle button lands
 * here: the four tabs plus the tag lists otherwise reachable only through More.
 */
@Composable
private fun LibraryIndex(currentTab: LibraryTab, actions: ShellActions) {
    val source by App.source.collectAsState()
    val currentLikedOnly = likedOnly(currentTab)

    // Own Column: the shell hands each page a Box, which would stack a header
    // and list on top of each other.
    Column(modifier = Modifier.fillMaxSize()) {
    AppHeader(
        title = "Library",
        fitTitle = true,
        rightAction = HeaderAction(AppIcons.Sort, onLongClick = actions.settings) {
            actions.more(LibraryPage.LIBRARY)
        },
    )
    ScrollableList(modifier = Modifier.fillMaxSize()) {
        // A source with no server has nowhere to keep a playlist. See
        // MusicSource.supportsPlaylists.
        if (source.supportsPlaylists) {
            item { IndexRow(LibraryTab.PLAYLISTS, current = currentTab == LibraryTab.PLAYLISTS) }
        }
        item { IndexRow(LibraryTab.ARTISTS, current = currentTab == LibraryTab.ARTISTS && !currentLikedOnly) }
        item { IndexRow(LibraryTab.ALBUMS, current = currentTab == LibraryTab.ALBUMS && !currentLikedOnly) }
        item { IndexRow(LibraryTab.SONGS, current = currentTab == LibraryTab.SONGS && !currentLikedOnly) }
    }
    }
}

/** One tab, on the index: its name, underlined when it's where you already are. */
@Composable
private fun IndexRow(tab: LibraryTab, current: Boolean) {
    TextRow(
        title = tab.title,
        underline = current,
        onClick = {
            // Clears the liked filter so the tab matches its unqualified name.
            App.scope.launch { setLikedOnly(tab, false) }
            LibraryNav.selectTab(tab)
        },
    )
}

suspend fun setLikedOnly(tab: LibraryTab, value: Boolean) {
    when (tab) {
        LibraryTab.ALBUMS -> App.settings.setLikedAlbumsOnly(value)
        LibraryTab.SONGS -> App.settings.setLikedSongsOnly(value)
        LibraryTab.ARTISTS -> App.settings.setLikedArtistsOnly(value)
        LibraryTab.PLAYLISTS -> Unit
    }
}

/**
 * The bottom bar, in whichever shape the Layout setting asks for.
 * See [AppSettings.layoutMode].
 */
@Composable
fun Navbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onMore: () -> Unit,
    moreActive: Boolean = false,
    /** Opens library search. The bar's fifth destination in the classic layout. */
    onSearch: (() -> Unit)? = null,
    searchActive: Boolean = false,
    /** Simplified keeps the player in the bar rather than the header's corner. */
    onNowPlaying: (() -> Unit)? = null,
    /** Simplified's centre tile: which library you are looking at. */
    onBrowse: (() -> Unit)? = null,
) {
    // Read from the eager state, not the preference Flow, to avoid a frame of
    // the wrong bar before the stored answer arrives.
    val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
    if (simplified && onSearch != null && onNowPlaying != null && onBrowse != null) {
        SimplifiedNavbar(onSearch, onNowPlaying, onBrowse, searchActive)
    } else {
        StandardNavbar(current, onSelect, onNowPlaying)
    }
}

/**
 * Simplified layout: 3-button navbar with Browse (left), Now Playing (center),
 * and Search (right).
 */
@Composable
private fun SimplifiedNavbar(
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: (() -> Unit)?,
    searchActive: Boolean,
) {
    val artworkEdge = px(EDGE_GLYPH_PX)
    // The end glyphs align to this edge. Padding is reduced by each glyph's own
    // slack inside its box, which differs because the glyphs draw at different sizes.
    val tile = px(BOTTOM_BAR_PX)
    val librarySlack = (tile - px(NAV_ICON_PX) * navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX)) / 2
    val searchSlack = (tile - px(NAV_ICON_PX) * SEARCH_SCALE) / 2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(
                start = (artworkEdge - librarySlack).coerceAtLeast(0.dp),
                end = (artworkEdge - searchSlack).coerceAtLeast(0.dp),
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The library tile, not the current tab. It's a place you go to, not a
        // control for changing tabs.
        NavTile(
            AppIcons.LibraryMusic,
            active = !searchActive,
            scale = navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX),
            box = BOTTOM_BAR_PX,
        ) { onBrowse?.invoke() }
        Spacer(Modifier.weight(1f))
        // The one tile that isn't a place to browse. Under this layout, the bar
        // is the only route to the player.
        NavTile(AppIcons.Waveform, active = false, scale = NOW_PLAYING_SCALE) { onNowPlaying() }
        Spacer(Modifier.weight(1f))
        NavTile(
            AppIcons.Search,
            active = searchActive,
            box = BOTTOM_BAR_PX,
        ) { onSearch() }
    }
}

/** Standard layout: 5-button navbar with separate tabs for Playlists, Artists, Albums, Songs, and Search. */
@Composable
private fun StandardNavbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onNowPlaying: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(horizontal = 0.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every tab goes through [onSelect], never LibraryNav directly: this bar
        // is also drawn over sub-pages, where selecting a tab must unwind the
        // stack as well as change it.
        //
        // A source with no server has nowhere to keep a playlist. See
        // MusicSource.supportsPlaylists.
        if (App.source.collectAsState().value.supportsPlaylists) {
            NavIcon(
                AppIcons.QueueMusic,
                current == LibraryTab.PLAYLISTS,
                scale = PLAYLISTS_SCALE,
                lift = PLAYLISTS_LIFT_PX,
            ) { onSelect(LibraryTab.PLAYLISTS) }
        }
        NavIcon(
            AppIcons.RecordVoiceOver,
            current == LibraryTab.ARTISTS,
            scale = navScale(ARTISTS_DRAWN_PX, ARTISTS_TARGET_PX),
        ) { onSelect(LibraryTab.ARTISTS) }
        NavIcon(
            AppIcons.AlbumStack,
            current == LibraryTab.ALBUMS,
            scale = navScale(ALBUMS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.ALBUMS) }
        NavIcon(
            AppIcons.MusicNote,
            current == LibraryTab.SONGS,
            scale = navScale(SONGS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.SONGS) }
        // Fifth destination, after the four browse tabs. Never lit, since the
        // player screen hides this bar entirely. Drawn at the simplified bar's
        // 80px so both layouts agree on size.
        if (onNowPlaying != null) {
            NavIcon(AppIcons.Waveform, active = false, scale = NOW_PLAYING_SCALE) { onNowPlaying() }
        }
    }
}


@Composable
private fun NavIcon(
    icon: ImageVector,
    active: Boolean,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = 1f,
    /** Nudges the glyph up inside its tile, in the same px units as everything else. */
    lift: Int = 0,
    onClick: () -> Unit,
) {
    // The selected tab gets a tile behind it, colored from the theme so Invert
    // Colors still works.
    Box(
        modifier = Modifier
            .size(px(NAV_TILE_PX))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .then(
                Modifier,
            )
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            size = px(NAV_ICON_PX) * scale,
            // Unselected glyphs dim via explicit alpha rather than the theme's
            // secondary grey, which reads too close to white here.
            tint = LightThemeTokens.colors.content,
            modifier = (if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA))
                .offset(y = -px(lift)),
        )
    }
}

/** A bar button with no tab behind it. Simplified's search and player. */
@Composable
private fun NavTile(
    icon: ImageVector,
    /** The one you are on is the bright one; the rest step back, as in the tabs. */
    active: Boolean = false,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = SEARCH_SCALE,
    /** The square the glyph centres in, and the size of its tap target. */
    box: Int = NAV_TILE_PX,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(box))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            // Matches the tabs' drawn size so the bar reads as one row, whichever
            // layout is showing.
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA),
        )
    }
}

private const val NAV_TILE_PX = 144
private const val NAV_TILE_RADIUS_PX = 6

// --- Standard layout constants ---

/** The tile a selected tab inverts into, and the glyph inside it. */
private const val NAV_ICON_PX = 114

/** Matches the player's secondary row, the app's one "present but not chosen" style. */
private const val NAV_DIM_ALPHA = 0.45f

/**
 * Brings the tab glyphs closer to one height. `*_DRAWN_PX` is what each glyph
 * actually draws at the shared box, measured off the panel; re-measure rather
 * than guess if a glyph changes. The playlist glyph is the reference and is
 * left alone.
 */
private const val ARTISTS_DRAWN_PX = 90
private const val ALBUMS_DRAWN_PX = 105
private const val SONGS_DRAWN_PX = 86

/** The simplified bar's library glyph, standing in for the Albums tab. Its own measure, not the album stack's. */
private const val LIBRARY_DRAWN_PX = 95

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/** Distance from a glyph's outer edge to the screen edge, for bars that stand against it. */
const val EDGE_GLYPH_PX = 80

/** A shade off the reference size, and a shade higher in the tile. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5



/** The magnifier, at the same reference size as the rest of the bar. */
private const val SEARCH_SCALE = PLAYLISTS_SCALE

/** The waveform, at the 80px box LightOS gives it. */
private const val NOW_PLAYING_PX = 80
private val NOW_PLAYING_SCALE = NOW_PLAYING_PX.toFloat() / NAV_ICON_PX


