package com.garado.amp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Multi-select over a track list. Tracks ids rather than rows, so a refresh of
 * the underlying list can't strand a stale copy of a track.
 */
class SelectionState {

    var active by mutableStateOf(false)
        private set

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    val count: Int get() = selected.size

    /** Enter selection mode, optionally with the track that was long-pressed. */
    fun begin(firstId: String? = null) {
        active = true
        selected = firstId?.let { setOf(it) } ?: emptySet()
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clear() {
        active = false
        selected = emptySet()
    }

    /** The chosen tracks in the order they appear in [list], not tap order. */
    fun <T> pick(list: List<T>, id: (T) -> String): List<T> =
        list.filter { id(it) in selected }
}

/**
 * Selection state that outlives the screen's composition. The underlying list
 * is torn down while the long-press sheet is on top of it, so a plain
 * `remember` would lose the selection just started; this survives the round
 * trip. Keyed by [owner] (e.g. "songs", "playlist:42") and cleared when
 * selection ends.
 */
object Selections {
    private val states = mutableMapOf<String, SelectionState>()

    fun of(owner: String): SelectionState = states.getOrPut(owner) { SelectionState() }

    /** Call when leaving the library, so no list comes back mid-selection. */
    fun clearAll() {
        states.values.forEach { it.clear() }
        states.clear()
    }
}

@Composable
fun rememberSelection(owner: String): SelectionState = remember(owner) { Selections.of(owner) }

/**
 * The header a list wears while selecting: the count replaces the title, X
 * leaves the mode, "+" confirms, and an optional delete action takes the
 * search button's slot.
 */
@Composable
fun SelectionHeader(
    selection: SelectionState,
    onDelete: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    AppHeader(
        leftAction = HeaderAction(AppIcons.Close) { selection.clear() },
        title = selectionTitle(selection.count),
        searchAction = onDelete?.let { delete -> { if (selection.count > 0) delete() } },
        searchIcon = AppIcons.DeleteOutline,
        rightAction = HeaderAction(AppIcons.Add) { if (selection.count > 0) onConfirm() },
    )
}

/** "3 selected", or a prompt while nothing is chosen yet. */
private fun selectionTitle(count: Int): String =
    if (count == 0) "Select songs" else "$count selected"
