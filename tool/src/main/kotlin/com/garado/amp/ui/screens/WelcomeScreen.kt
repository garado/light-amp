package com.garado.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import com.garado.amp.ui.LightType
import com.garado.amp.ui.components.AppText
import com.garado.amp.ui.components.TextRole
import com.garado.amp.ui.px
import com.garado.amp.ui.pxSp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.appClickable

/**
 * First run: pick where the music comes from. The three source kinds are offered
 * as equals, none the default. Each choice hands off to the same screen used to
 * add that kind of source later from Settings.
 */
@Composable
fun WelcomeContent(
    onSubsonic: () -> Unit,
    onPlex: () -> Unit,
    onJellyfin: () -> Unit,
    onLocal: () -> Unit,
) {
    PlayerTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = px(80)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppText("amp", pxSp(LightType.HEADING_PX))
            AppText(
                "Where is your music?",
                pxSp(LightType.DETAIL_PX),
                dim = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = px(80)),
            )

            SourceChoice("SUBSONIC SERVER", onSubsonic)
            SourceChoice("PLEX SERVER", onPlex)
            SourceChoice("JELLYFIN SERVER", onJellyfin)
            SourceChoice("MUSIC ON THIS PHONE", onLocal)

            Spacer(Modifier.height(px(60)))

            // "Subsonic" is the protocol name, not the server most people run, so this
            // spells out which servers it covers (e.g. Navidrome).
            AppText(
                "Subsonic covers Bandcamp, Navidrome, Airsonic and Ampache.",
                pxSp(LightType.DETAIL_PX),
                dim = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One of the three, all drawn identically so none reads as the default. */
@Composable
private fun SourceChoice(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .appClickable(onClick = onClick)
            .padding(vertical = px(40)),
        contentAlignment = Alignment.Center,
    ) {
        // Forces Regular weight: the Button style asks for Medium, which the phone lacks,
        // and AppText would otherwise round it up to Bold.
        AppText(
            label,
            pxSp(LightType.COPY_PX),
            role = TextRole.Button,
            weight = FontWeight.Normal,
        )
    }
}
