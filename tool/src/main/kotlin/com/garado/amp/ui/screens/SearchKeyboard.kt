package com.garado.amp.ui.screens

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard

/**
 * The LP3 keyboard, placed at the bottom of the search page instead of inside
 * the SDK's [com.thelightphone.sdk.ui.LightTextInputEditor] screen, so results
 * can narrow below it as the user types. The keyboard composable and its view
 * model are public; only `TextInputKeyboardCallback` is internal, so
 * [SearchKeyboardCallback] reimplements its public interface.
 */
@Composable
fun SearchKeyboard(state: TextFieldState, onReturn: () -> Unit) {
    val callback = remember(state) { SearchKeyboardCallback(state, onReturn) }
    val options = rememberPhoneKeyboardOptions()
    // Held via remember, not viewModel(): the tool doesn't depend on lifecycle-viewmodel-compose.
    val keyboard = remember(callback) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = options,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboard)
}

/**
 * Ports the SDK's internal `TextInputKeyboardCallback` for use here. Single-line
 * only: Return submits the search instead of inserting a line break. This copy
 * will not pick up changes to the SDK's version.
 */
private class SearchKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onKeyReleased(code: Int) = insertCodePoint(code)

    override fun onKeyRepeated(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            // Counts surrogate pairs as one unit, or deleting an emoji leaves half of one behind.
            SpecialKey.Backspace -> deleteBefore(surrogateAwareCount())
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onKeyLongPressed(code: Int) = Unit

    /** Holding backspace takes the whole word, as it does in the SDK's editor. */
    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key != SpecialKey.Backspace) return
        val before = state.text.subSequence(0, state.selection.min)
        val trimmed = before.trimEnd()
        val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
        deleteBefore(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun surrogateAwareCount(): Int {
        val before = state.text.subSequence(0, state.selection.min)
        if (before.isEmpty()) return 0
        return if (Character.isLowSurrogate(before[before.length - 1])) 2 else 1
    }

    private fun insertCodePoint(code: Int) = insert(buildString { appendCodePoint(code) })

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
