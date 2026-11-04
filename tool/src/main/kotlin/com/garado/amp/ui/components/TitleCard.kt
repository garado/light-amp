package com.garado.amp.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp

/**
 * The two-line title card the player wears in its header, shared with any
 * screen showing the same track.
 */
@Composable
fun TitleCard(top: String, bottom: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleLine(top, TITLE_PX, TITLE_LINE_PX, dim = false)
        TitleLine(bottom, SUB_PX, SUB_LINE_PX, dim = true)
    }
}

@Composable
private fun TitleLine(text: String, sizePx: Int, linePx: Int, dim: Boolean) {
    AppText(
        text,
        pxSp(sizePx),
        lineHeight = pxSp(linePx),
        role = TextRole.Subheading,
        dim = dim,
        maxLines = 1,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().basicMarquee(),
    )
}

private const val TITLE_PX = 45
private const val TITLE_LINE_PX = 54
private const val SUB_PX = 36
private const val SUB_LINE_PX = 42

/**
 * The player header's track identity: artist, album, title, all at one size
 * and weight so none reads as a caption for the others. Artist and album are
 * clickable links to their pages; title has nowhere else to go.
 */
@Composable
fun TrackMetadataCard(
    artist: String,
    album: String,
    title: String,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(px(METADATA_GAP_PX)),
    ) {
        MetadataLine(artist, onArtistClick)
        MetadataLine(album, onAlbumClick)
        MetadataLine(title)
    }
}

@Composable
private fun MetadataLine(text: String, onClick: (() -> Unit)? = null) {
    AppText(
        text,
        pxSp(METADATA_PX),
        lineHeight = pxSp(METADATA_LINE_PX),
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

private const val METADATA_PX = 33
private const val METADATA_LINE_PX = 39

/** Room between the three lines, wide enough that they read as separate facts. */
private const val METADATA_GAP_PX = 12
