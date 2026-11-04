package com.garado.amp.ui.screens

import kotlinx.coroutines.launch
import com.garado.amp.data.LastSection
import com.garado.amp.App
import com.garado.amp.data.Track
import com.garado.amp.ui.components.SelectionState
import com.garado.amp.ui.components.ScrollAnchors
import com.garado.amp.ui.components.Selections
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Library navigation state shared by the shell and every library sub-page.
 * A sub-page several levels deep records a tab selection here; the root
 * screen owns the shell and observes it.
 */
object LibraryNav {
    val currentTab = MutableStateFlow(LibraryTab.ALBUMS)
    val searchActive = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")

    /**
     * Show the search page. [withKeyboard] is set when opened from a list's own
     * search field; the tab bar's button opens straight to the last results.
     */
    fun openSearch(withKeyboard: Boolean = false) {
        searchActive.value = true
        record(LastSection.SEARCH)
        typing.value = withKeyboard
    }

    /**
     * Whether the keyboard is up. Search has one page with two states, typing
     * and reading, rather than two screens, so results stay visible throughout.
     */
    val typing = MutableStateFlow(false)

    /**
     * The library index, Simplified's middle button. A shell state, not a
     * screen, since what it opens onto (a tab) is a state too, not a stack push.
     */
    val libraryIndex = MutableStateFlow(false)

    /** Show the index, leaving whatever was showing. */
    fun openLibraryIndex() {
        closeSearch()
        libraryIndex.value = true
    }

    /** Back to the library as last left. Drops search only; tab and scroll position stay. */
    fun returnToLibrary() {
        closeSearch()
    }

    /**
     * The bar's middle button, pressed while the shell is showing. First press
     * returns to the library; a second press (already there) steps up to the
     * index. Search is only ever left by this, never toggled into.
     */
    fun pressLibrary() {
        if (searchActive.value) {
            closeSearch()
            return
        }
        libraryIndex.value = true
    }

    /**
     * Guards the opening destination from firing more than once. The root
     * screen re-enters composition every time a pushed screen pops, so a
     * plain `LaunchedEffect(Unit)` there would run again on each return.
     */
    private var landed = false

    /** True once. The caller (see BootScreen) decides the opening destination. */
    fun claimFirstLanding(): Boolean {
        if (landed) return false
        landed = true
        return true
    }

    fun setQuery(query: String) {
        searchQuery.value = query
        searchActive.value = true
    }

    /** Leave the search page, keeping the query so the results are still there next visit. */
    fun closeSearch() {
        searchActive.value = false
        typing.value = false
        record(LastSection.LIBRARY)
    }

    /** Note where the app is, so it can reopen there. No-op before boot finishes. */
    internal fun record(section: LastSection) {
        if (!App.isReady) return
        App.scope.launch { App.settings.setLastSection(section) }
    }

    /** Tapping the tab you're already on scrolls to its top instead of reselecting it. */
    fun tapTab(tab: LibraryTab) {
        if (currentTab.value == tab && !searchActive.value && !libraryIndex.value) {
            ScrollAnchors.requestTop("tab:${tab.name.lowercase()}")
            return
        }
        selectTab(tab)
    }

    fun selectTab(tab: LibraryTab) {
        closeSearch()
        libraryIndex.value = false
        // A selection belongs to the list it was made in, so changing tabs clears it.
        Selections.clearAll()
        currentTab.value = tab
    }
}

/**
 * Which library page More was opened over. More reads that page's settings
 * live when it draws, rather than a snapshot taken when opened.
 */
enum class LibraryPage {
    /** The four tabs. */
    ALBUMS,
    SONGS,
    ARTISTS,
    PLAYLISTS,

    /** Results, which are the library in its own order. */
    SEARCH,

    /** The simplified layout's index of everything there is to browse. */
    LIBRARY,

    /** Pages whose order is the record's, the playlist's, or the server's. */
    ALBUM,
    ARTIST,
    ARTIST_SONGS,
    PLAYLIST,

    /** Peers of the tabs, reached from More, that carry a tab's sort. */
    DOWNLOADS,
}

/**
 * Push a screen that returns no result, as a single-parameter helper so
 * `go { SomeScreen(it) }` reads cleanly.
 */
fun SimpleLightScreen<*>.go(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
    navigateTo(factory)
}

/**
 * Push the bulk-action sheet for [tracks] and leave selection mode only if an
 * action actually ran.
 */
fun SimpleLightScreen<*>.openSelectionActions(
    tracks: List<Track>,
    selection: SelectionState,
    showAddToQueue: Boolean = true,
    showDownload: Boolean = true,
) {
    if (tracks.isEmpty()) return
    navigateTo<Boolean>(
        { SelectionActionsScreen(it, tracks, showAddToQueue, showDownload) },
        resultCallback = { acted -> if (acted) selection.clear() },
    )
}

/**
 * Open the player from a long-press sheet, taking the sheet off the stack, so
 * the player's back button returns to the list, not the sheet.
 */
fun SimpleLightScreen<Unit>.replaceWithPlayer() {
    goBack()
    go { NowPlayingScreen(it) }
}

/** Long-press sheet for a track in a list that supports multi-select. */
fun SimpleLightScreen<*>.openTrackActions(trackId: String, selection: SelectionState?) {
    go { TrackActionsScreen(it, trackId, onSelect = selection?.let { s -> { s.begin(trackId) } }) }
}

/**
 * What the page being opened should have underneath it, where back leads.
 *
 * [Here] pushes directly, for opening from a page already in the hierarchy.
 * [Walk] unwinds to the library and rebuilds the ancestor chain first, for
 * jumps from outside the library (a long-press menu, the queue, a search
 * result) that have nothing useful to return to otherwise.
 */
sealed interface Parent {
    /** The page doing the opening. Just push onto it. */
    data object Here : Parent

    /**
     * Unwinds to the library, selects [tab], and pushes [artist]'s discography
     * first if given, for pages that live under an artist.
     */
    data class Walk(val tab: LibraryTab, val artist: String? = null) : Parent

    companion object {
        /** A tab's own list, which is the top of its hierarchy. */
        fun tab(tab: LibraryTab): Parent = Walk(tab)

        /** An artist's discography, under Artists, where an album belongs. */
        fun artist(name: String): Parent =
            // No artist means no discography to sit under; fall back to the album list.
            if (name.isBlank()) Walk(LibraryTab.ALBUMS) else Walk(LibraryTab.ARTISTS, name)
    }
}

/** Put [parent] on the stack, so that whatever is pushed next sits on top of it. */
private fun SimpleLightScreen<*>.layDown(parent: Parent) {
    if (parent !is Parent.Walk) return
    popToRoot()
    LibraryNav.selectTab(parent.tab)
    parent.artist?.let { artist -> go { ArtistDetailScreen(it, artist) } }
}

fun SimpleLightScreen<*>.openAlbum(albumId: String, parent: Parent) {
    layDown(parent)
    go { AlbumDetailScreen(it, albumId) }
}

fun SimpleLightScreen<*>.openArtist(name: String, parent: Parent) {
    if (name.isBlank()) return
    layDown(parent)
    go { ArtistDetailScreen(it, name) }
}
