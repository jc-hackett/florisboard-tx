/*
 * Copyright (C) 2026 The florisboard-tx Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import android.content.Context
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smartbar's "AI cleanup" button: proofreads the selected text, or the whole field if nothing
 * is selected, through the user's own server (POST /v1/tidy), which de-identifies it before Claude
 * sees it and restores the details afterwards. Tapping again within a minute, while the cleaned
 * text is untouched, puts the original back. No clipboard copy: Android pops its clipboard overlay
 * on every copy, and the user found it intrusive (2026-10-01).
 *
 * Costs a little Claude usage per tap, so it only ever runs on an explicit tap. Inert in incognito
 * mode and in password fields.
 */
class AiCleanup private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The last cleanup, for tap-again-to-undo: what we wrote and what it replaced. */
    private data class Done(val cleaned: String, val original: String, val at: Long)
    private var lastDone: Done? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun run(editorInfo: FlorisEditorInfo) {
        if (_busy.value) return
        if (editorInfo.inputAttributes.variation in PASSWORDS) return toast("AI cleanup doesn't touch password fields")
        val settings = DictationSettings(appContext)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) {
            return toast("AI cleanup: add the server address and token in Settings, Customization")
        }
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (undoIfJustCleaned(ic)) return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before: String
        val after: String
        val original: String
        if (selected.isNotEmpty()) {
            before = ""; after = ""; original = selected
        } else {
            before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
            after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
            original = before + after
        }
        if (original.isBlank()) return toast("AI cleanup: nothing to clean up")
        if (original.length > SEND_MAX) {
            return toast("AI cleanup: that's too long (about 300 words at most). Select a part and tap again.")
        }

        _busy.value = true
        scope.launch {
            try {
                val cleaned = withContext(Dispatchers.IO) { request(settings.serverUrl, settings.token, original) }
                if (cleaned == null || cleaned == original) {
                    toast("AI cleanup: nothing to change")
                    return@launch
                }
                val ic2 = FlorisImeService.currentInputConnection() ?: return@launch
                // Only replace what we read: if the user typed meanwhile, leave everything alone.
                if (selected.isNotEmpty()) {
                    if (ic2.getSelectedText(0)?.toString() != selected) return@launch toast("AI cleanup: text changed, not applied")
                } else {
                    val nowBefore = ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    val nowAfter = ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    if (nowBefore != before || nowAfter != after) return@launch toast("AI cleanup: text changed, not applied")
                }
                ic2.beginBatchEdit()
                if (selected.isEmpty()) ic2.deleteSurroundingText(before.length, after.length)
                ic2.commitText(cleaned, 1)
                ic2.endBatchEdit()
                lastDone = Done(cleaned, original, System.currentTimeMillis())
                EditLog.add(appContext, "Sparkle", original, cleaned)
                toast("Cleaned up. Tap again to undo.")
            } catch (e: Exception) {
                toast("AI cleanup: ${e.message ?: "couldn't reach the server."}")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Called right after a double-space has typed ". ": quietly cleans up the sentence that just
     * ended, in the background, while the user keeps typing. The sentence is found again by its
     * position when the answer arrives and replaced only if it is still exactly as it was, with
     * the cursor kept where the user has got to. Off with Settings > Dictation's switch.
     */
    fun cleanSentenceJustEnded(editorInfo: FlorisEditorInfo) {
        val settings = DictationSettings(appContext)
        if (!settings.autoCleanupOnPeriod) return note("off")
        if (editorInfo.inputAttributes.variation in PASSWORDS) return note("skipped: password field")
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) return note("skipped: server not set up")
        val ic = FlorisImeService.currentInputConnection() ?: return note("skipped: no text box")
        val snap = snapshot(ic) ?: return note("skipped: this app won't let the keyboard read the box")
        val full = snap.text
        val cursor = snap.selStart
        if (cursor < 3 || cursor > full.length || full[cursor - 1] != ' ' || full[cursor - 2] !in SEGMENT_ENDS) {
            return note("skipped: couldn't find the punctuation it just typed")
        }
        // The sentence: from the previous sentence end (or line start) up to and including the ".".
        val end = cursor - 1
        val head = full.substring(0, end - 1)
        val prevEnd = head.lastIndexOfAny(SEGMENT_ENDS + '\n')
        var start = prevEnd + 1
        while (start < end && full[start].isWhitespace()) start++
        val sentence = full.substring(start, end)
        if (sentence.length < 3) return note("skipped: sentence too short")
        if (sentence.length > SEND_MAX) return note("skipped: sentence too long")
        note("sent: \"${sentence.take(40)}\" (${snap.how})")

        scope.launch {
            val cleaned = try {
                withContext(Dispatchers.IO) { request(settings.serverUrl, settings.token, sentence) }?.trim()
            } catch (e: Exception) {
                return@launch note("not changed: ${e.message ?: "couldn't reach the server"}")
            } ?: return@launch note("not changed: empty answer")
            if (cleaned == sentence || cleaned.isEmpty()) return@launch note("checked: nothing to change")
            // Wait until the user is between words: swapping text while a word is still being typed
            // (the keyboard's "composing" word) put the cleaned sentence in the wrong place.
            val editorInstance by appContext.editorInstance()
            var waited = 0L
            while (editorInstance.activeContent.composing.let { it.isValid && it.length > 0 } && waited < 8_000) {
                delay(250)
                waited += 250
            }
            if (editorInstance.activeContent.composing.let { it.isValid && it.length > 0 }) {
                return@launch note("not changed: you kept typing")
            }
            val ic2 = FlorisImeService.currentInputConnection() ?: return@launch note("not changed: text box closed")
            val now = snapshot(ic2) ?: return@launch note("not changed: couldn't read the box again")
            val text = now.text
            if (text.length < end || text.substring(start, end) != sentence) {
                return@launch note("not changed: you edited that sentence meanwhile")
            }
            if (now.selStart < end || now.selEnd < end) {
                return@launch note("not changed: your cursor moved into or before that sentence")
            }
            val delta = cleaned.length - sentence.length
            val newSelStart = if (now.selStart >= end) now.selStart + delta else now.selStart
            val newSelEnd = if (now.selEnd >= end) now.selEnd + delta else now.selEnd
            ic2.beginBatchEdit()
            ic2.setSelection(start, end)
            ic2.commitText(cleaned, 1)
            ic2.setSelection(newSelStart, newSelEnd)
            ic2.endBatchEdit()
            note("replaced: \"${cleaned.take(40)}\"")
            EditLog.add(appContext, "Auto (sentence)", sentence, cleaned)
        }
    }

    /** The whole box and the cursor, read whichever way the app allows. */
    private data class Snapshot(val text: String, val selStart: Int, val selEnd: Int, val how: String)

    private fun snapshot(ic: android.view.inputmethod.InputConnection): Snapshot? {
        // 1. The standard way: the app hands over its whole text and cursor.
        val et = runCatching { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
        if (et?.text != null && et.startOffset == 0 && et.selectionStart >= 0) {
            return Snapshot(et.text.toString(), et.selectionStart, et.selectionEnd, "read directly")
        }
        // 2. Some apps (WhatsApp among them) don't; rebuild it from the text around the cursor.
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: return null
        if (before.length >= MAX_CHARS) return null // box longer than we can see: positions unknown
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
        return Snapshot(before + selected + after, before.length, before.length + selected.length, "rebuilt")
    }

    private fun note(text: String) {
        lastAutoEvent = text
    }

    private fun request(server: String, token: String, text: String): String? {
        val conn = (URL(server.trimEnd('/') + "/v1/tidy").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401 -> error("the server didn't accept your access token.")
                413 -> error("that's too long (about 300 words at most). Select a part and tap again.")
                429 -> error(
                    if (serverReason(conn).contains("daily")) "you've reached today's limit. It resets tomorrow."
                    else "too many in a row. Wait a minute and try again."
                )
                503 -> error("it isn't switched on on the server.")
                else -> error("the server had a problem ($code). Try again.")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return o.optString("text").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    /** The server's own reason for refusing, if it sent one. */
    private fun serverReason(conn: HttpURLConnection): String = runCatching {
        JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("detail")
    }.getOrDefault("")

    /** If the text just before the cursor is exactly what the last cleanup wrote, restore it. */
    private fun undoIfJustCleaned(ic: android.view.inputmethod.InputConnection): Boolean {
        val done = lastDone ?: return false
        lastDone = null
        if (System.currentTimeMillis() - done.at > UNDO_WINDOW_MS) return false
        val before = ic.getTextBeforeCursor(done.cleaned.length, 0)?.toString() ?: return false
        if (before != done.cleaned) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(done.cleaned.length, 0)
        ic.commitText(done.original, 1)
        ic.endBatchEdit()
        toast("Original put back.")
        return true
    }

    private fun toast(text: String) {
        scope.launch { appContext.showShortToast(text) }
    }

    companion object {
        private const val MAX_CHARS = 6000
        /** Must match the server's TIDY_MAX_CHARS. */
        private const val SEND_MAX = 2000

        /** Marks that end a stretch worth cleaning when followed by a space (commas left out on
         *  purpose: half-sentences clean badly and would use up the per-minute limit). */
        val SEGMENT_ENDS = charArrayOf('.', '?', '!', ':', ';', '…', '—', '–')
        private const val UNDO_WINDOW_MS = 60_000L
        private val PASSWORDS = setOf(
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
        )

        /** What the per-sentence cleanup last did, in plain words; shown in Settings > Customization. */
        @Volatile var lastAutoEvent: String = "nothing yet since the keyboard started"
            private set

        @Volatile private var instance: AiCleanup? = null

        fun get(context: Context): AiCleanup =
            instance ?: synchronized(this) { instance ?: AiCleanup(context).also { instance = it } }
    }
}
