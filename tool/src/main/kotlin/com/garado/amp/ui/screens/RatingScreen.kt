package com.garado.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garado.amp.App
import com.garado.amp.ui.PlayerTheme
import com.garado.amp.ui.components.NumericKeypad
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.launch

private const val MAX_RATING = 100
private const val MAX_RATING_DIGITS = 3

/** "81 / 100" for a rating, so the value reads at a glance. */
fun ratingLabel(rating: Int): String = "${rating.coerceIn(0, 100)} / 100"

/**
 * Enter a 0-100 rating on a numeric keypad.
 *
 * The rating is written to the server first and cached locally only once accepted.
 * See LibraryRepository.setRating, which queues retries if the server is unreachable.
 */
class RatingScreen(
    sealed: SealedLightActivity,
    private val id: String,
    private val title: String,
    private val current: Int,
    private val isAlbum: Boolean,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker, see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        PlayerTheme {
            var draft by remember { mutableStateOf(current.toString()) }

            fun submit() {
                val rating = draft.toIntOrNull()?.coerceIn(0, MAX_RATING) ?: 0
                App.scope.launch {
                    App.library.setRating(id, rating, isAlbum)
                    goBack()
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Rating"),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(
                        text = title,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                    LightText(
                        text = draft,
                        variant = LightTextVariant.Title,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(2.dp)
                            .background(LightThemeTokens.colors.content),
                    )
                }

                NumericKeypad(
                    onDigit = { digit ->
                        if (draft.length < MAX_RATING_DIGITS) {
                            // Drops the leading zero so typing over the prefilled "0" works
                            // without a backspace first.
                            val candidate = (draft + digit).trimStart('0').ifEmpty { "0" }
                            if ((candidate.toIntOrNull() ?: 0) <= MAX_RATING) draft = candidate
                        }
                    },
                    onBackspace = { draft = draft.dropLast(1) },
                    onSubmit = { submit() },
                )
            }
        }
    }
}
