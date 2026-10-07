// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the one-step undos offered after a ✨ cleanup and after a dictation. Each stays on
// offer only while the cursor is exactly where our own edit left it: typing, moving the cursor,
// switching field or hiding the keyboard withdraws it.
package helium314.keyboard.tx

import android.os.SystemClock
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The whole box and the cursor, read whichever way the app allows. */
data class EditorSnapshot(val text: String, val selStart: Int, val selEnd: Int)

/**
 * One undo on offer, valid while the cursor stays where our edit put it. Main thread only.
 *
 * The position is the one our edit is *expected* to leave, worked out from the text before the edit,
 * not read back afterwards: many editors (Jetpack Compose text fields among them) answer a read made
 * right after an edit with the text from before it, and report the real cursor only a frame later.
 * Recording that stale position made the editor's own report of our edit look like a cursor move,
 * which withdrew the undo at once. For a short while after the offer, reports that differ from the
 * expected position are taken as our edit still landing and followed, not treated as a move; typing
 * in that window is caught separately ([SovereignUndo.onUserInput]).
 */
class UndoSlot<T : Any> {
    private val _offered = MutableStateFlow(false)
    /** True while the undo is available; the toolbar redraws the key on every change. */
    val offered: StateFlow<Boolean> = _offered.asStateFlow()

    private var item: T? = null
    private var selStart = -1
    private var selEnd = -1
    private var expectedStart = -1
    private var expectedEnd = -1
    private var settled = false
    private var settleUntil = 0L

    /** Offers [item]; [selStart] / [selEnd] are where our edit leaves the cursor. */
    fun offer(item: T, selStart: Int, selEnd: Int) {
        this.item = item
        this.selStart = selStart
        this.selEnd = selEnd
        expectedStart = selStart
        expectedEnd = selEnd
        settled = false
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS
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
        if (newSelStart == expectedStart && newSelEnd == expectedEnd) {
            // the editor's own report of our edit
            settled = true
            selStart = newSelStart
            selEnd = newSelEnd
            return
        }
        if (newSelStart == selStart && newSelEnd == selEnd) return
        if (!settled && SystemClock.uptimeMillis() < settleUntil) {
            // our edit still landing (in-between or app-adjusted positions): follow it
            selStart = newSelStart
            selEnd = newSelEnd
            return
        }
        // Possibly a late report from before our own edit: believe the editor's current state.
        val now = ic?.let { SovereignUndo.snapshot(it) }
        if (now != null && now.selStart == selStart && now.selEnd == selEnd) return
        clear()
    }

    private companion object {
        const val SETTLE_MS = 1500L
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

    /** The user typed, deleted, pasted, swiped a word or picked a suggestion: nothing stays on offer. */
    @JvmStatic
    fun onUserInput() {
        cleanup.clear()
        dictation.clear()
    }

    /** Field changed or keyboard hidden: nothing stays on offer, and kept-dictation ids are dropped. */
    @JvmStatic
    fun forgetAll() {
        cleanup.clear()
        dictation.clear()
        KeptDictations.forget()
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

/**
 * The last few dictations typed into the current field whose recordings the server kept ("Save my
 * recordings" on): the server's id and the text typed in. When the user then cleans up text with ✨,
 * the ids whose dictation is in that text go with the request, so the server can keep the
 * correction next to the recording. Forgotten when the field changes or the keyboard hides.
 */
object KeptDictations {
    private const val MAX = 5
    private val items = ArrayDeque<Pair<String, String>>()

    @Synchronized
    fun remember(id: String, text: String) {
        items.removeAll { it.first == id }
        items.addLast(id to text)
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun forget() = items.clear()

    /**
     * Ids of remembered dictations that are (mostly) in [text]: at least half of the dictated words
     * still appear there, so a dictation the user since corrected a little still counts.
     */
    @Synchronized
    fun idsFor(text: String): List<String> {
        if (items.isEmpty()) return emptyList()
        val present = words(text).toHashSet()
        return items.filter { (_, said) ->
            val w = words(said)
            w.isNotEmpty() && w.count { it in present } * 2 >= w.size
        }.map { it.first }
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")
}
