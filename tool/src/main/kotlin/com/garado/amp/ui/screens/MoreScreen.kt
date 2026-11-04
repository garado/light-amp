package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.App
import com.garado.amp.BuildConfig
import com.garado.amp.data.AlbumSort
import com.garado.amp.data.LayoutMode
import com.garado.amp.data.PlaylistSort
import com.garado.amp.data.descendingByNature
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.AppHeader
import com.garado.amp.ui.components.AppIcon
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.HeaderAction
import com.garado.amp.ui.components.ListScreen
import com.garado.amp.ui.components.ScrollableList
import com.garado.amp.ui.components.TextRow
import com.garado.amp.ui.LightType
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch
import com.thelightphone.sdk.SimpleLightScreen

/**
 * The page's own menu: where its music comes from, and the three ways of
 * looking at it. Every row shows its value even when the page can't change it,
 * since an absent row reads as a missing feature. Those rows don't take a
 * press. See [TextRow].
 */
class MoreScreen(
    sealed: SealedLightActivity,
    /** The list underneath, whose modifiers this page carries. */
    private val page: LibraryPage,
    /** The list's name, for pages without a constant title. See [defaultTitle]. */
    private val pageTitle: String? = null,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker. See handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        // Read here rather than at the row: a list's items are built outside
        // composition, where a @Composable can't be called.
        val view = viewOf(page)
        val sort = sortOf(page)
        // Under Simplified, liked lists are entries on the library page instead
        // of a filter here.
        val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
        val likedTab = if (simplified) null else likedFilterTab(page)
        val liked = likedTab != null && likedOnly(likedTab)
        val genre = tagSettingOf(page, byComposer = false)
        val composer = tagSettingOf(page, byComposer = true)

        // Covers the tab bar rather than sitting above it, so this reads as the
        // library page's own menu, not a separate page.
        val body: @Composable ColumnScope.() -> Unit = {
            AppHeader(
                title = pageTitle ?: defaultTitle(page),
                fitTitle = true,
                onBack = { goBack() },
                rightAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
            )
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Hidden when there's nothing to choose: one source with one
                // library has only the one entry you're already looking at.
                if (sources.size > 1 || (active?.libraries?.size ?: 0) > 1) {
                item {
                    TextRow(
                        title = "Source",
                        value = when {
                            sources.size > 1 -> "${active?.name.orEmpty()} · ${sources.size} sources"
                            else -> active?.name
                        },
                        onClick = { go { SourcesScreen(it) } },
                    )
                }
                }
                if (likedTab != null) {
                    item {
                        // Uses tabTitle's own label, e.g. "Liked Albums", not "Liked Only".
                        ToggleRow(tabTitle(likedTab, likedOnly = true), liked) {
                            App.scope.launch { setLikedOnly(likedTab, !liked) }
                            goBack()
                        }
                    }
                }
                if (view.open != null) item { Setting("View", view) }
                if (sort.open != null) item { Setting("Sort by", sort) }
                if (genre != null) item { Setting("Genre", genre) }
                if (composer != null) item { Setting("Composer", composer) }
                // A server that can only create a playlist with songs in it has no
                // use for a bare "New Playlist". See MusicSource.supportsEmptyPlaylists.
                if (page == LibraryPage.PLAYLISTS && active?.supportsEmptyPlaylists == true) {
                    item { TextRow(title = "New Playlist", onClick = { createPlaylistFlow() }) }
                }
            }
        }

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) { body() }
        }
    }

    /** A modifier's row: its name, its value, and a way in where there is one. */
    @Composable
    private fun Setting(name: String, state: PageSetting) {
        TextRow(
            title = name,
            value = state.value,
            trailing = state.trailing,
            onClick = state.open?.let { factory -> { change(factory) } },
        )
    }

    /**
     * Opens a picker and leaves with its result, so the reordered list ends up
     * one tap below the choice. Backing out without choosing lands back here.
     */
    private fun change(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
        navigateTo(factory, resultCallback = { goBack() })
    }

    private fun createPlaylistFlow() {
        navigateTo<String?>(
            { TextEntryScreen(it, title = "New Playlist") },
            resultCallback = { name ->
                if (!name.isNullOrBlank()) App.scope.launch { App.library.createPlaylist(name) }
            },
        )
    }
}

/** What the page is called, for the pages whose name never changes. */
@Composable
private fun defaultTitle(page: LibraryPage): String = when (page) {
    LibraryPage.ALBUMS -> tabTitle(LibraryTab.ALBUMS, likedOnly(LibraryTab.ALBUMS))
    LibraryPage.SONGS -> tabTitle(LibraryTab.SONGS, likedOnly(LibraryTab.SONGS))
    LibraryPage.ARTISTS -> tabTitle(LibraryTab.ARTISTS, likedOnly(LibraryTab.ARTISTS))
    LibraryPage.PLAYLISTS -> LibraryTab.PLAYLISTS.title
    LibraryPage.SEARCH -> "Search"
    LibraryPage.LIBRARY -> "Library"
    LibraryPage.DOWNLOADS -> "Downloads"
    // These get their title from the page itself. See MoreScreen.pageTitle.
    LibraryPage.ALBUM -> "Album"
    LibraryPage.ARTIST, LibraryPage.ARTIST_SONGS -> "Artist"
    LibraryPage.PLAYLIST -> "Playlist"
}

/**
 * What one modifier is set to on a given page, and where to change it.
 * [open] is null when the page offers no choice. The row still appears.
 * See [MoreScreen].
 */
private class PageSetting(
    val value: String,
    val open: ((SealedLightActivity) -> SimpleLightScreen<Unit>)? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

/**
 * List or grid. Only album lists have the choice, and only while artwork is
 * on. The albums tab and an artist's discography keep separate answers.
 */
@Composable
private fun viewOf(page: LibraryPage): PageSetting {
    val hidden = App.hideArtwork.collectAsState().value
    val grid = when (page) {
        LibraryPage.ALBUMS -> App.albumGrid.collectAsState().value
        LibraryPage.ARTIST -> App.artistAlbumGrid.collectAsState().value
        else -> false
    }
    val choosable = !hidden && (page == LibraryPage.ALBUMS || page == LibraryPage.ARTIST)
    return PageSetting(
        value = if (grid) "Grid" else "List",
        open = if (!choosable) null else {
            { AlbumViewScreen(it, forArtist = page == LibraryPage.ARTIST) }
        },
    )
}

/**
 * The order the page is in. Pages with a fixed order still return a value;
 * the caller decides whether to show the row. See Content.
 */
@Composable
private fun sortOf(page: LibraryPage): PageSetting = when (page) {
    LibraryPage.ALBUMS -> {
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        sortable(
            albumSortLabel(sort),
            sort.descendingByNature,
            reversed,
            // A shuffle has no direction for the arrow to claim.
            directionless = sort == AlbumSort.RANDOM,
        ) {
            AlbumsSortScreen(it)
        }
    }

    LibraryPage.SONGS, LibraryPage.DOWNLOADS -> {
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val from = if (page == LibraryPage.DOWNLOADS) "Downloads" else null
        sortable(songSortLabel(sort), sort.descendingByNature, reversed) {
            SongsSortScreen(it, from)
        }
    }

    LibraryPage.ARTISTS -> {
        val sort by App.artistSort.collectAsState()
        val reversed by App.artistSortReversed.collectAsState()
        sortable(artistSortLabel(sort), sort.descendingByNature, reversed) {
            ArtistsSortScreen(it)
        }
    }

    LibraryPage.PLAYLISTS -> {
        val sort by App.settings.playlistSort.collectAsState(initial = PlaylistSort.RECENTLY_UPDATED)
        val reversed by App.settings.playlistSortReversed.collectAsState(initial = false)
        sortable(playlistSortLabel(sort), sort.descendingByNature, reversed) {
            PlaylistsSortScreen(it)
        }
    }

    // Fixed orders. See LibraryRepository.
    LibraryPage.ALBUM -> PageSetting("Track order")
    LibraryPage.ARTIST -> PageSetting("Date Released")
    LibraryPage.ARTIST_SONGS -> PageSetting("Album")
    LibraryPage.PLAYLIST -> PageSetting("Playlist order")
    LibraryPage.SEARCH -> PageSetting("Library order")
    // A menu of places, in the order the app lists them.
    LibraryPage.LIBRARY -> PageSetting("Fixed")
}

/** A changeable order, with the arrow the sort menus use for its direction. */
private fun sortable(
    label: String,
    naturallyDescending: Boolean,
    reversed: Boolean,
    /** True for an order with no direction, like Random, which gets no arrow. */
    directionless: Boolean = false,
    open: (SealedLightActivity) -> SimpleLightScreen<Unit>,
): PageSetting {
    // `reversed` inverts the option's natural direction, as in SortOptions.
    val descending = naturallyDescending != reversed
    return PageSetting(
        value = label,
        open = open,
        trailing = if (directionless) {
            null
        } else {
            {
                AppIcon(
                    if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                    size = px(46),
                )
            }
        },
    )
}

/**
 * The tab this page can be narrowed to liked-only, or null where it can't.
 * A toggle rather than a picker, since liked/all are the only two answers.
 * Null for sources with no likes too. See MusicSource.supportsLikes.
 */
@Composable
private fun likedFilterTab(page: LibraryPage): LibraryTab? {
    if (!App.source.collectAsState().value.supportsLikes) return null
    return when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        LibraryPage.ARTISTS -> LibraryTab.ARTISTS
        else -> null
    }
}

/**
 * The genre or composer this page has been narrowed to, where it can be.
 * Only Albums and Songs, and only where the library carries that tag.
 */
@Composable
private fun tagSettingOf(page: LibraryPage, byComposer: Boolean): PageSetting? {
    // Read unconditionally; a collectAsState behind a branch is a conditional
    // composable call and stops recomposing on that state.
    val values by (if (byComposer) App.library.composers else App.library.genres)
        .collectAsState()
    val albums = App.albumsTagFilter.collectAsState().value
    val songs = App.songsTagFilter.collectAsState().value
    val tab = when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        else -> return null
    }
    val filter = if (tab == LibraryTab.ALBUMS) albums else songs
    val chosen = if (byComposer) filter.composer else filter.genre
    // Also shown when a filter is already set, so there's always a way to clear it.
    if (values.isEmpty() && chosen.isEmpty()) return null
    return PageSetting(
        value = chosen.ifEmpty { "All" },
        open = { TagFilterScreen(it, tab, byComposer) },
    )
}

class AboutScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "About") {
            Column(
                modifier = Modifier.fillMaxSize().padding(px(40)),
                verticalArrangement = Arrangement.spacedBy(px(26)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText("amp", pxSp(LightType.HEADING_PX))
                AppText("Version ${BuildConfig.VERSION_NAME}", pxSp(LightType.DETAIL_PX), dim = true)
                AppText(
                    "(A)nother (M)usic (P)layer - for the Light Phone III. Streams " +
                        "and downloads from your own Navidrome, Subsonic, Plex or " +
                        "Bandcamp library, and plays files kept on the phone.",
                    pxSp(LightType.PARAGRAPH_PX),
                    lineHeight = pxSp(LightType.PARAGRAPH_LINE_PX),
                    dim = true,
                    align = TextAlign.Center,
                )
            }
        }
    }
}
