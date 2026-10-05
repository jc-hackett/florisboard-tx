// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the one-step undos offered after a ✨ cleanup and after a dictation. Each stays on
// offer only while the cursor is exactly where our own edit left it: typing, moving the cursor,
// switching field or hiding the keyboard withdraws it.
package helium314.keyboard.tx

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The whole box and the cursor, read whichever way the app allows. */
data class EditorSnapshot(val text: String, val selStart: Int, val selEnd: Int)

/** One undo on offer, valid while the cursor stays where our edit put it. Main thread only. */
class UndoSlot<T : Any> {
    private val _offered = MutableStateFlow(false)
    /** True while the undo is available; the toolbar redraws the key on every change. */
    val offered: StateFlow<Boolean> = _offered.asStateFlow()

    private var item: T? = null
    private var selStart = -1
    private var selEnd = -1

    fun offer(item: T, selStart: Int, selEnd: Int) {
        this.item = item
        this.selStart = selStart
        this.selEnd = selEnd
        _offered.value = true
    }

    /** Takes the item off offer and returns it (null if nothing was on offer). */
    fun take(): T? = item.also { clear() }

    fun clear() {
        item = null
        _offered.value = false
    }

    fun onSelectionChanged(ic: InputConnection?, newSelStart: Int, newSelEnd: Int) {
        if (item == null) return
        if (newSelStart == selStart && newSelEnd == selEnd) return
        // Possibly a late report from before our own edit: believe the editor's current state.
        val now = ic?.let { SovereignUndo.snapshot(it) }
        if (now != null && now.selStart == selStart && now.selEnd == selEnd) return
        clear()
    }
}

object SovereignUndo {
    private const val MAX_CHARS = 6000

    /** Undo for the last ✨ cleanup (manual tap or auto-sparkle). */
    internal val cleanup = UndoSlot<CleanupDone>()

    /** Undo for the last dictation: the text it typed in. */
    internal val dictation = UndoSlot<String>()

    /** True while the ✨ key should show as undo. */
    val cleanupOffered: StateFlow<Boolean> get() = cleanup.offered

    /** Called by LatinIME on every cursor / selection change. */
    @JvmStatic
    fun onUpdateSelection(ic: InputConnection?, newSelStart: Int, newSelEnd: Int) {
        cleanup.onSelectionChanged(ic, newSelStart, newSelEnd)
        dictation.onSelectionChanged(ic, newSelStart, newSelEnd)
    }

    /** Field changed or keyboard hidden: nothing stays on offer. */
    @JvmStatic
    fun forgetAll() {
        cleanup.clear()
        dictation.clear()
    }

    fun snapshot(ic: InputConnection): EditorSnapshot? {
        // 1. The standard way: the app hands over its whole text and cursor.
        val et = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        if (et?.text != null && et.startOffset == 0 && et.selectionStart >= 0) {
            return EditorSnapshot(et.text.toString(), et.selectionStart, et.selectionEnd)
        }
        // 2. Some apps (WhatsApp among them) don't; rebuild it from the text around the cursor.
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: return null
        if (before.length >= MAX_CHARS) return null // box longer than we can see: positions unknown
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
        return EditorSnapshot(before + selected + after, before.length, before.length + selected.length)
    }
}

/** A cleanup that can be undone: [cleaned] was written at [start] in place of [original]. */
internal data class CleanupDone(val start: Int, val cleaned: String, val original: String)
