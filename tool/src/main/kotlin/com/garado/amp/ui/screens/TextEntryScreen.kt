package com.garado.amp.ui.screens

import android.view.KeyEvent
import com.garado.amp.App
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import com.garado.amp.ui.PlayerTheme
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightTextInputEditor

/**
 * Full-screen text entry backed by the LP3 keyboard. Returns the submitted text
 * as its navigation result, or null when dismissed with back.
 */
class TextEntryScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val singleLine: Boolean = true,
    private val submitLabel: String = "DONE",
    private val submitIcon: LightIconConfiguration? = null,
) : SimpleLightScreen<String?>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        PlayerTheme {
            val state = rememberTextFieldState(initial)
            val options = rememberPhoneKeyboardOptions()
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = options,
                singleLine = singleLine,
                submitLabel = submitLabel,
                submitIcon = submitIcon,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
            )
        }
    }
}
