// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: ported from florisboard-tx (feat/dictate) ime/ai/AiCleanup.kt. Same server call,
// same messages; HeliBoard-specific bits are the editor access and composing check.
package helium314.keyboard.tx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.widget.Toast
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.utils.InputTypeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min
import java.net.HttpURLConnection
import java.net.URL

/**
 * The toolbar's ✨ "AI cleanup" key: proofreads the selected text, or the whole field if nothing
 * is selected, through the user's own server (POST /v1/tidy). After a cleanup the key turns into an
 * undo key (see [SovereignUndo]): tapping it puts the original back. Typing anything, moving the
 * cursor, switching field or hiding the keyboard ends that offer.
 *
 * Also "auto-sparkle": after a space following . ? ! : ; … — or –, the paragraph just ended is
 * cleaned in the background ([cleanSentenceJustEnded]). Off with the switch in SovereignBoard settings.
 *
 * Inert in incognito mode and in password fields.
 */
class AiCleanup private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(ime: LatinIME) {
        if (_busy.value) return
        val ic = ime.currentInputConnection ?: return
        if (SovereignUndo.cleanup.offered.value) return undoLastCleanup(ime, ic)
        if (isPassword(ime)) return toast("AI cleanup doesn't touch password fields")
        val settings = DictationSettings(appContext)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) {
            return toast("AI cleanup: add the server address and token in Settings, SovereignBoard")
        }
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

        val refs = fixRefs(settings, original)
        _busy.value = true
        scope.launch {
            try {
                val cleaned = withContext(Dispatchers.IO) {
                    request(settings.serverUrl, settings.token, original, refs, FIX_KIND_MANUAL)
                }
                if (cleaned == null || cleaned == original) {
                    toast("AI cleanup: nothing to change")
                    return@launch
                }
                val ic2 = ime.currentInputConnection ?: return@launch
                // Only replace what we read: if the user typed meanwhile, leave everything alone.
                if (selected.isNotEmpty()) {
                    if (ic2.getSelectedText(0)?.toString() != selected) return@launch toast("AI cleanup: text changed, not applied")
                } else {
                    val nowBefore = ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    val nowAfter = ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    if (nowBefore != before || nowAfter != after) return@launch toast("AI cleanup: text changed, not applied")
                }
                // Where the cleaned text will start, from the text before the edit: everything before
                // the cursor (or the selection) when the whole box was read; null if the box is too
                // long to see its start. Not read back after the edit, since many editors still
                // answer with the old text then (see UndoSlot).
                val start: Int? = if (selected.isEmpty()) 0
                    else ic2.getTextBeforeCursor(MAX_CHARS, 0)?.length?.takeIf { it < MAX_CHARS }
                ic2.beginBatchEdit()
                ic2.finishComposingText()
                if (selected.isEmpty()) ic2.deleteSurroundingText(before.length, after.length)
                ic2.commitText(cleaned, 1)
                ic2.endBatchEdit()
                resync(ime, ic2)
                if (start != null) {
                    val cursor = start + cleaned.length
                    SovereignUndo.cleanup.offer(CleanupDone(start, cleaned, original), cursor, cursor)
                    toast("Cleaned up. Tap ✨ again to undo.")
                } else {
                    toast("Cleaned up.")
                }
            } catch (e: Exception) {
                toast("AI cleanup: ${e.message ?: "couldn't reach the server."}")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Called right after a space that follows one of [SEGMENT_ENDS]: quietly cleans up the
     * paragraph that just ended, in the background, while the user keeps typing. The text is found
     * again by its position when the answer arrives and replaced only if it is still exactly as it
     * was, with the cursor kept where the user has got to.
     */
    fun cleanSentenceJustEnded(ime: LatinIME) {
        val settings = DictationSettings(appContext)
        if (!settings.autoCleanupOnPeriod) return
        if (isPassword(ime)) return
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) return
        val ic = ime.currentInputConnection ?: return
        val snap = SovereignUndo.snapshot(ic) ?: return
        val full = snap.text
        val cursor = snap.selStart
        if (cursor < 3 || cursor > full.length || full[cursor - 1] != ' ' || full[cursor - 2] !in SEGMENT_ENDS) return
        // The whole paragraph up to and including the mark just typed: one sentence on its own
        // lacks the context to fix it well. If long, start at a sentence boundary under the limit.
        val end = cursor - 1
        var start = full.lastIndexOf('\n', end - 1) + 1
        if (end - start > SEND_MAX) {
            val window = full.substring(end - SEND_MAX, end - 1)
            val cut = window.indexOfAny(SEGMENT_ENDS)
            start = if (cut >= 0) end - SEND_MAX + cut + 1 else end - SEND_MAX
        }
        while (start < end && full[start].isWhitespace()) start++
        val sentence = full.substring(start, end)
        if (sentence.length < 3) return
        val refs = fixRefs(settings, sentence)

        scope.launch {
            val cleaned = try {
                withContext(Dispatchers.IO) {
                    request(settings.serverUrl, settings.token, sentence, refs, FIX_KIND_AUTO)
                }?.trim()
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            if (cleaned == sentence || cleaned.isEmpty()) return@launch
            // Wait until the user is between words: swapping text while a word is still being
            // composed would put the cleaned text in the wrong place.
            var waited = 0L
            while (ime.sovereignIsComposingWord() && waited < 8_000) {
                delay(250)
                waited += 250
            }
            if (ime.sovereignIsComposingWord()) return@launch toast("Auto-sparkle skipped: you were still typing")
            val ic2 = ime.currentInputConnection ?: return@launch
            val now = SovereignUndo.snapshot(ic2) ?: return@launch
            val text = now.text
            if (text.length < end || text.substring(start, end) != sentence) {
                return@launch toast("Auto-sparkle skipped: that sentence changed")
            }
            if (now.selStart < end || now.selEnd < end) {
                return@launch toast("Auto-sparkle skipped: you moved back into the text")
            }
            val delta = cleaned.length - sentence.length
            ic2.beginBatchEdit()
            ic2.setSelection(start, end)
            ic2.commitText(cleaned, 1)
            ic2.setSelection(now.selStart + delta, now.selEnd + delta)
            ic2.endBatchEdit()
            resync(ime, ic2)
            // Offered at the cursor position our edit leaves, not one read back (see UndoSlot).
            SovereignUndo.cleanup.offer(CleanupDone(start, cleaned, sentence), now.selStart + delta, now.selEnd + delta)
        }
    }

    /**
     * Kept dictations in [text] (see [KeptDictations]), so the server can keep this correction with
     * their recordings. Empty unless "Save my recordings" is on.
     */
    private fun fixRefs(settings: DictationSettings, text: String): List<String> =
        if (settings.keepRecordings) KeptDictations.idsFor(text) else emptyList()

    private fun request(server: String, token: String, text: String, refs: List<String>, kind: String): String? {
        val conn = (URL(server.trimEnd('/') + "/v1/tidy").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            // Opt-in only ("Save my recordings"): without these the server keeps nothing.
            if (refs.isNotEmpty()) {
                setRequestProperty("X-Dictate-Keep", "1")
                setRequestProperty("X-Dictate-Keep-Ref", refs.joinToString(","))
                setRequestProperty("X-Dictate-Fix-Kind", kind)
                setRequestProperty("X-Dictate-App", BuildConfig.BUILD_COMMIT_HASH.take(12))
            }
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            when (val code = conn.responseCode) {
                in 200..299 -> SovereignToken.onAccepted(appContext)
                401, 403 -> {
                    SovereignToken.onRejected(appContext)
                    error("the server didn't accept your access token.")
                }
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

    /** Let HeliBoard re-read the box after we changed it behind its back (its text cache would be stale). */
    private fun resync(ime: LatinIME, ic: InputConnection): EditorSnapshot? {
        val now = SovereignUndo.snapshot(ic) ?: return null
        ime.sovereignReloadAfterExternalEdit(now.selStart, now.selEnd)
        return now
    }

    /**
     * The ✨ key in its undo state: put the original back, if the cleaned text is still exactly where
     * we wrote it. The cursor keeps its place relative to the text around it.
     */
    private fun undoLastCleanup(ime: LatinIME, ic: InputConnection) {
        val done = SovereignUndo.cleanup.take() ?: return
        val end = done.start + done.cleaned.length
        val now = SovereignUndo.snapshot(ic)
        if (now == null || now.text.length < end || now.text.substring(done.start, end) != done.cleaned) {
            return toast("Nothing to undo")
        }
        val delta = done.original.length - done.cleaned.length
        fun moved(pos: Int) = if (pos >= end) pos + delta else min(pos, done.start + done.original.length)
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.setSelection(done.start, end)
        ic.commitText(done.original, 1)
        ic.setSelection(moved(now.selStart), moved(now.selEnd))
        ic.endBatchEdit()
        toast("Original put back.")
        resync(ime, ic)
    }

    private fun isPassword(ime: LatinIME): Boolean =
        InputTypeUtils.isAnyPasswordInputType(ime.currentInputEditorInfo?.inputType ?: 0)

    private fun toast(text: String) {
        mainHandler.post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val MAX_CHARS = 6000
        /** Must match the server's TIDY_MAX_CHARS. */
        private const val SEND_MAX = 2000
        private const val FIX_KIND_MANUAL = "sparkle"
        private const val FIX_KIND_AUTO = "auto-sparkle"

        /** Marks that end a stretch worth cleaning when followed by a space (commas left out on
         *  purpose: half-sentences clean badly and would use up the per-minute limit). */
        private val SEGMENT_ENDS = charArrayOf('.', '?', '!', ':', ';', '…', '—', '–')

        @JvmStatic
        fun isSegmentEnd(c: Char): Boolean = c in SEGMENT_ENDS

        private val _busy = MutableStateFlow(false)
        /** True while a tap's cleanup is waiting for the server; the toolbar key shows it. */
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        @Volatile private var instance: AiCleanup? = null

        @JvmStatic
        fun get(context: Context): AiCleanup =
            instance ?: synchronized(this) { instance ?: AiCleanup(context).also { instance = it } }
    }
}
