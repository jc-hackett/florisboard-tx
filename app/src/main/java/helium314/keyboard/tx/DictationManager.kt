// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: ported from florisboard-tx (feat/dictate). Same gestures and guardrails.
package helium314.keyboard.tx

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.PersistableBundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
        capturing = true
        _state.value = DictationState.RECORDING
        sessionJob = scope.launch {
            val hardStop = launch {
                delay(MAX_SESSION_MS)
                capturing = false
            }
            val text = try {
                transcriber.transcribe(stillRecording = { capturing })
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
            if (!text.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    val ic = ime.currentInputConnection
                    if (ic != null) {
                        // Keep a word that was being composed instead of replacing it.
                        ic.finishComposingText()
                        // Where the text goes, read before the edit (many editors still report the
                        // old cursor right after one; see UndoSlot).
                        val at = SovereignUndo.snapshot(ic)?.let { minOf(it.selStart, it.selEnd) }
                        ic.commitText(text, 1)
                        if (at != null) SovereignUndo.dictation.offer(text, at + text.length, at + text.length)
                        copyToClipboard(text)
                    }
                }
            }
            _state.value = DictationState.IDLE
        }
    }

    /**
     * Also puts the dictated text on the clipboard, so it lands in clipboard history (switch in the
     * SovereignBoard settings, on by default). Never in password fields or incognito mode. Marked not
     * sensitive, so Android 13+ shows the text in its overlay instead of hiding it.
     */
    private fun copyToClipboard(text: String) {
        if (text.isEmpty() || !DictationSettings(appContext).copyToClipboard) return
        if (Settings.getValues().mIncognitoModeEnabled) return
        val inputType = ime.currentInputEditorInfo?.inputType ?: 0
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) return
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
            }
        }
        runCatching { clipboard.setPrimaryClip(clip) }
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
        /** Label on clips made from a dictation (the clipboard suggestion chip skips these). */
        const val CLIP_LABEL = "Dictation"

        private val sharedState = MutableStateFlow(DictationState.IDLE)

        /** What dictation is doing right now, for whoever draws the mic key. */
        val current: StateFlow<DictationState> = sharedState.asStateFlow()

        /** A press shorter than this latches; anything longer is treated as hold-to-talk. */
        const val TAP_THRESHOLD_MS = 300L

        /** Upper bound on a single latched recording. */
        const val MAX_SESSION_MS = 120_000L
    }
}
