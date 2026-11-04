package com.garado.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.garado.amp.App
import com.garado.amp.data.LayoutMode
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.AppIcons
import com.garado.amp.ui.components.HeaderAction
import com.thelightphone.sdk.SimpleLightScreen

/** Opens library search from a sub-page by activating it and unwinding to the shell. */
fun SimpleLightScreen<*>.openLibrarySearch(withKeyboard: Boolean = false) {
    LibraryNav.openSearch(withKeyboard)
    popToRoot()
}

/** True while the simplified layout is the one in use. */
@Composable
fun simplifiedLayout(): Boolean =
    App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED

/**
 * Back action for a page opened from the simplified layout's library index.
 * Null outside Simplified, where the standard layout's tab bar handles leaving instead.
 */
@Composable
fun SimpleLightScreen<*>.libraryBackAction(): (() -> Unit)? =
    if (simplifiedLayout()) ({ goBack() }) else null

/** The More corner action in every library page's header. See LibraryShell and [MoreScreen]. */
@Composable
fun SimpleLightScreen<*>.libraryCornerAction(
    page: LibraryPage,
    /** Only needed where the page's name isn't a constant. See [MoreScreen]. */
    pageTitle: String? = null,
): HeaderAction =
    HeaderAction(
        AppIcons.Sort,
        onLongClick = { go { SettingsScreen(it) } },
    ) { go { MoreScreen(it, page, pageTitle) } }

/** Chrome for every library sub-page: the page's content with the library bottom bar under it. */
@Composable
fun SimpleLightScreen<*>.LibrarySubPage(
    /** Which page this is, for the More the bar below opens. See [MoreScreen]. */
    page: LibraryPage,
    /** Only needed where the page's name isn't a constant. See [MoreScreen]. */
    pageTitle: String? = null,
    /** Set by More, which is a tab in its own right rather than a page of one. */
    moreActive: Boolean = false,
    onNowPlaying: (() -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) { content() }
            }
            val current by LibraryNav.currentTab.collectAsState()
            Navbar(
                // The tab stays lit regardless of how deep the current page is nested.
                current = if (moreActive) null else current,
                moreActive = moreActive,
                onSelect = { tab ->
                    LibraryNav.selectTab(tab)
                    popToRoot()
                },
                onMore = { if (!moreActive) go { MoreScreen(it, page, pageTitle) } },
                onSearch = { openLibrarySearch() },
                onNowPlaying = { go { NowPlayingScreen(it) } },
                onBrowse = {
                    LibraryNav.openLibraryIndex()
                    popToRoot()
                },
            )
        }
    }
}
