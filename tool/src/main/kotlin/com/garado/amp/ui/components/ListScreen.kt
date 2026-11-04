package com.garado.amp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.px

/**
 * Screen chrome using the app's [AppHeader] (back + title, optional 2-line
 * subtitle and right action) followed by the body. Used by the utility screens
 * (search, settings, sort, actions) so they match the rest of the app.
 */
@Composable
fun ListScreen(
    onBack: () -> Unit,
    title: String,
    subtitle: String? = null,
    rightAction: HeaderAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = onBack,
                title = if (subtitle == null) title else null,
                // Uses the player's own two-line title card.
                titleContent = if (subtitle != null) {
                    { TitleCard(title, subtitle) }
                } else {
                    null
                },
                rightAction = rightAction,
            )
            content()
        }
    }
}

/**
 * The body of an action sheet: rows that scroll once there are more than fit.
 * Row count varies by item, so a plain Column could clip actions off the
 * bottom with no way to reach them.
 */
@Composable
fun ActionList(content: @Composable ColumnScope.() -> Unit) {
    val state = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state)
                .padding(end = px(SCROLLBAR_LANE_PX)),
            content = content,
        )
        // Same scroll bar used by lists; see rememberScrollTarget(ScrollState).
        ListScrollBar(rememberScrollTarget(state))
    }
}
