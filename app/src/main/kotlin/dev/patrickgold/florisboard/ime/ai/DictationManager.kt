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

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
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
import org.florisboard.lib.android.showShortToast

/**
 * What the dictation key is currently doing. Drives the key's appearance, so the user can always
 * tell from the keyboard alone whether the microphone is open.
 */
enum class DictationState {
    /** Nothing happening, no microphone open. */
    IDLE,

    /** Microphone open and capturing. */
    RECORDING,

    /** Microphone closed, waiting on transcription and cleanup. */
    WORKING,

    /** Last attempt failed. Resets to [IDLE] on the next press. */
    ERROR;
}

/**
 * Turns captured speech into text ready to be committed to the editor.
 *
 * Implementations own the microphone and the network work; the manager owns only the gesture, the
 * lifecycle and the guardrails. The real implementation is [ServerTranscriber], which talks to the
 * user's own dictation server (self-hosted Whisper, optional de-identified Claude cleanup).
 */
interface Transcriber {
    /**
     * Captures audio until [stillRecording] returns false, then returns the finished text, or null
     * if nothing usable was said. Throws [DictationException] with a user-facing message on failure.
     */
    suspend fun transcribe(stillRecording: () -> Boolean): String?
}

/**
 * Owns the dictation key's behaviour: one key, two gestures, and the guardrails that stop a
 * forgotten recording from sitting there with the microphone open.
 *
 * - A short tap latches recording on; the next tap ends it.
 * - A longer press records for as long as it is held and ends on release.
 * - A tap while it is still waiting for the text is the kill switch: it drops the connection,
 *   types nothing and frees the key at once.
 *
 * A latched recording also ends itself after [MAX_SESSION_MS], so the worst case for a key pressed
 * by accident is bounded rather than open-ended.
 */
class DictationManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(DictationState.IDLE)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    var transcriber: Transcriber = ServerTranscriber(appContext)

    private var pressStartedAt = 0L
    private var endOnRelease = false
    private var capturing = false
    private var sessionJob: Job? = null

    /**
     * True while the key should read as live. Exposed separately from [state] because the key also
     * looks busy while transcription finishes, and both cases mean "don't start another one".
     */
    val isBusy: Boolean
        get() = _state.value == DictationState.RECORDING || _state.value == DictationState.WORKING

    fun onPointerDown() {
        when (_state.value) {
            DictationState.RECORDING -> {
                // Second tap of a latched recording: finish when this press is released, however
                // long the user happens to hold it.
                endOnRelease = true
            }
            DictationState.WORKING -> {
                // Kill switch: the user is done waiting. Drop it and free the key.
                abort()
                scope.launch { appContext.showShortToast(R.string.dictation__cancelled) }
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
        if (endOnRelease || heldFor >= TAP_THRESHOLD_MS) {
            stopCapturing()
        }
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
                val message = (e as? DictationException)?.messageRes ?: R.string.dictation__error_network
                appContext.showShortToast(message)
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
                    val editorInstance by appContext.editorInstance()
                    editorInstance.commitText(text)
                    // No clipboard copy: Android pops its clipboard overlay on every copy, and the
                    // user found that intrusive (2026-10-01).
                }
            }
            _state.value = DictationState.IDLE
        }
    }

    /**
     * Also puts the dictated text on the clipboard, so it can be pasted elsewhere if the field
     * lost it. Marked sensitive: Android hides it from clipboard previews, and FlorisBoard's own
     * clipboard history treats it as sensitive too.
     */
    private fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Dictation", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        runCatching { clipboard.setPrimaryClip(clip) }
    }

    /** Closes the microphone and lets the in-flight transcription finish. */
    private fun stopCapturing() {
        capturing = false
        _state.value = DictationState.WORKING
    }

    /** Closes the microphone and discards whatever was captured. */
    private fun abort() {
        capturing = false
        sessionJob?.cancel()
        sessionJob = null
        _state.value = DictationState.IDLE
    }

    companion object {
        /** A press shorter than this latches; anything longer is treated as hold-to-talk. */
        const val TAP_THRESHOLD_MS = 300L

        /** Upper bound on a single latched recording. */
        const val MAX_SESSION_MS = 120_000L
    }
}
