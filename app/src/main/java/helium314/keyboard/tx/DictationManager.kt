// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: ported from florisboard-tx (feat/dictate). Same gestures and guardrails.
package helium314.keyboard.tx

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the dictation key is currently doing. */
enum class DictationState { IDLE, RECORDING, WORKING, ERROR }

/**
 * Owns the dictation key's behaviour and the guardrails that stop a forgotten recording from
 * sitting there with the microphone open.
 *
 * - A tap latches recording on; the next tap ends it ([onTap]).
 * - A longer press records for as long as it is held ([onPointerDown] / [onPointerUp]).
 * - A tap while it is still waiting for the text is the kill switch: it drops the connection,
 *   types nothing and frees the key at once.
 *
 * A latched recording also ends itself after [MAX_SESSION_MS].
 *
 * A long-press on the mic takes the last dictation back out ([undoLastDictation]), as long as the
 * user hasn't typed or moved the cursor since (see [SovereignUndo]).
 */
class DictationManager(private val ime: InputMethodService) {
    private val appContext = ime.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Shared with the toolbar (see SovereignToolbar), which shows a dot on the mic key.
    private val _state = sharedState
    val state: StateFlow<DictationState> = _state.asStateFlow()

    init {
        _state.value = DictationState.IDLE
    }

    var transcriber: Transcriber = ServerTranscriber(appContext)

    private var pressStartedAt = 0L
    private var endOnRelease = false
    @Volatile private var capturing = false
    private var sessionJob: Job? = null

    val isBusy: Boolean
        get() = _state.value == DictationState.RECORDING || _state.value == DictationState.WORKING

    /** A single tap on the voice key: start, stop, or cancel, depending on the state. */
    fun onTap() {
        when (_state.value) {
            DictationState.RECORDING -> stopCapturing()
            DictationState.WORKING -> {
                // Kill switch: the user is done waiting. Drop it and free the key.
                abort()
                toast(DictationMessages.CANCELLED)
            }
            DictationState.IDLE, DictationState.ERROR -> start()
        }
    }

    fun onPointerDown() {
        when (_state.value) {
            DictationState.RECORDING -> endOnRelease = true
            DictationState.WORKING -> {
                abort()
                toast(DictationMessages.CANCELLED)
            }
            DictationState.IDLE, DictationState.ERROR -> {
                pressStartedAt = System.currentTimeMillis()
                endOnRelease = false
                start()
            }
        }
    }

    fun onPointerUp() {
        if (_state.value != DictationState.RECORDING) return
        val heldFor = System.currentTimeMillis() - pressStartedAt
        if (endOnRelease || heldFor >= TAP_THRESHOLD_MS) stopCapturing()
        // Otherwise this was a tap: leave it latched and recording until the next press.
    }

    fun onPointerCancel() {
        if (_state.value == DictationState.RECORDING) abort()
    }

    private fun start() {
        // Without the microphone permission: say so in the keyboard's red banner (tap opens settings).
        if (!SovereignToken.hasMic(appContext)) {
            SovereignToken.onMicWithoutPermission(appContext)
            toast(SovereignToken.MIC_TEXT.replace("tap to allow", "tap the red bar to allow"))
            return
        }
        capturing = true
        _state.value = DictationState.RECORDING
        // Where the dictation will land, read now so the server can be told (a flag, no text).
        val midSentence = DictationCasing.isMidSentence(textBeforeCursor())
        sessionJob = scope.launch {
            val hardStop = launch {
                delay(MAX_SESSION_MS)
                capturing = false
            }
            val result = try {
                transcriber.transcribe(stillRecording = { capturing }, midSentence = midSentence)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hardStop.cancel()
                _state.value = DictationState.ERROR
                toast((e as? DictationException)?.userMessage ?: DictationMessages.NETWORK)
                return@launch
            }
            hardStop.cancel()
            if (_state.value != DictationState.RECORDING && _state.value != DictationState.WORKING) {
                // Aborted while we were working; drop the result rather than typing it into
                // whatever the user moved on to.
                return@launch
            }
            _state.value = DictationState.WORKING
            val heard = result?.text
            if (!heard.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    val ic = ime.currentInputConnection
                    if (ic != null) {
                        // Keep a word that was being composed instead of replacing it.
                        ic.finishComposingText()
                        // Fit it to what is before the cursor: lower-case start mid-sentence, a space
                        // between it and the previous word (read again now; the cursor may have moved).
                        val before = textBeforeCursor()
                        val text = DictationCasing.fit(heard, before, knownWords())
                        // Where the text goes, read before the edit (many editors still report the
                        // old cursor right after one; see UndoSlot).
                        val at = SovereignUndo.snapshot(ic)?.let { minOf(it.selStart, it.selEnd) }
                        ic.commitText(text, 1)
                        if (at != null) SovereignUndo.dictation.offer(text, at + text.length, at + text.length)
                        // Every dictation comes back already cleaned up, so ✨ turns straight into
                        // its undo: one tap puts back exactly what was heard.
                        val raw = result?.raw?.let { DictationCasing.fit(it, before, knownWords()) }
                        if (at != null && raw != null && raw != text) {
                            SovereignUndo.cleanup.offer(CleanupDone(at, text, raw), at + text.length, at + text.length)
                        }
                        result?.keepId?.let { KeptDictations.remember(it, text.trim()) }
                        addToClipboardHistory(text)
                    }
                }
            }
            _state.value = DictationState.IDLE
        }
    }

    /** Up to [DictationCasing.CONTEXT_CHARS] characters before the cursor ("" at the start of the field). */
    private fun textBeforeCursor(): String? = runCatching {
        ime.currentInputConnection?.getTextBeforeCursor(DictationCasing.CONTEXT_CHARS, 0)?.toString()
    }.getOrNull()

    /** The user's own words and the server's word list: spellings whose capitals must stay. */
    private fun knownWords(): Collection<String> {
        val s = DictationSettings(appContext)
        return s.wordList + s.serverWords.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Also adds the dictated text to the keyboard's own clipboard history (switch in the SovereignBoard
     * settings, on by default). The system clipboard is left alone, so Android shows no "copied"
     * overlay. Never in password fields or incognito mode; follows the history on/off setting and
     * its retention time.
     */
    private fun addToClipboardHistory(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !DictationSettings(appContext).addToClipboardHistory) return
        if (Settings.getValues().mIncognitoModeEnabled) return
        val inputType = ime.currentInputEditorInfo?.inputType ?: 0
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) return
        runCatching { (ime as? LatinIME)?.clipboardHistoryManager?.addTextToHistory(trimmed) }
    }

    /**
     * Long-press on the mic: removes exactly the text the last dictation typed in, if it is still
     * right before the cursor, unchanged, with nothing selected. Returns true if it did.
     */
    fun undoLastDictation(): Boolean {
        val text = SovereignUndo.dictation.take()
        val ic = ime.currentInputConnection
        if (text == null || ic == null || isBusy) return false.also { toast("Nothing to undo") }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty() || ic.getTextBeforeCursor(text.length, 0)?.toString() != text) {
            toast("Nothing to undo")
            return false
        }
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(text.length, 0)
        ic.endBatchEdit()
        return true
    }

    /** Closes the microphone and lets the in-flight transcription finish. */
    private fun stopCapturing() {
        capturing = false
        _state.value = DictationState.WORKING
    }

    /** Closes the microphone and discards whatever was captured. */
    fun abort() {
        capturing = false
        sessionJob?.cancel()
        sessionJob = null
        _state.value = DictationState.IDLE
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private val sharedState = MutableStateFlow(DictationState.IDLE)

        /** What dictation is doing right now, for whoever draws the mic key. */
        val current: StateFlow<DictationState> = sharedState.asStateFlow()

        /** A press shorter than this latches; anything longer is treated as hold-to-talk. */
        const val TAP_THRESHOLD_MS = 300L

        /** Upper bound on a single latched recording. */
        const val MAX_SESSION_MS = 120_000L
    }
}
