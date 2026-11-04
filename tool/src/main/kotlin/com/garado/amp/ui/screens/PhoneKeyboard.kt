package com.garado.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.refreshKeyboardOptions
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cached fallback for [rememberPhoneKeyboardOptions] while LightOS is asked again. */
private var lastKnown: KeyboardOptions? = null

/**
 * Keyboard options (swipe typing, key animation, voice key) as set in LightOS.
 * Queried once per keyboard instance rather than per recomposition.
 *
 * Emoji is always disabled: an empty emoji list makes the keyboard library draw
 * a spacer instead of an emoji key.
 *
 * [swipe] overrides the LightOS setting. Pass false for masked input fields,
 * since a masked keyboard has no word suggestions and would silently drop a
 * swiped word (see MaskedKeyboardCallback).
 */
@Composable
fun rememberPhoneKeyboardOptions(swipe: Boolean? = null): StateFlow<KeyboardOptions> {
    fun pinned(options: KeyboardOptions) = options.copy(
        emojis = emptyList(),
        swipeEnabled = swipe ?: options.swipeEnabled,
    )
    val flow = remember { MutableStateFlow(pinned(lastKnown ?: defaultKeyboardOptions())) }
    LaunchedEffect(Unit) {
        refreshKeyboardOptions()?.let {
            lastKnown = it
            flow.value = pinned(it)
        }
    }
    return flow
}
