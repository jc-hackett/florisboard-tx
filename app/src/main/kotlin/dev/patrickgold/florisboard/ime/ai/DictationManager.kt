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
import dev.patrickgold.florisboard.editorInstance
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
 * Implementations are expected to do the network work; the manager owns only the gesture, the
 * lifecycle and the guardrails. [StubTranscriber] stands in until the AWS implementation lands,
 * which keeps the key testable without a microphone permission or an AWS account.
 */
interface Transcriber {
    /**
     * Captures audio until [stillRecording] returns false, then returns the finished text, or null
     * if nothing usable was said.
     */
    suspend fun transcribe(stillRecording: () -> Boolean): String?
}

/** Placeholder that ignores the microphone entirely and returns a fixed sentence. */
class StubTranscriber : Transcriber {
    override suspend fun transcribe(stillRecording: () -> Boolean): String {
        while (stillRecording()) {
            delay(POLL_INTERVAL_MS)
        }
        delay(STUB_WORK_MS)
        return "This is a placeholder transcript. "
    }

    companion object {
        private const val POLL_INTERVAL_MS = 50L
        private const val STUB_WORK_MS = 400L
    }
}

/**
 * Owns the dictation key's behaviour: one key, two gestures, and the guardrails that stop a
 * forgotten recording from sitting there with the microphone open.
 *
 * - A short tap latches recording on; the next tap ends it.
 * - A longer press records for as long as it is held and ends on release.
 *
 * A latched recording also ends itself after [MAX_SESSION_MS], so the worst case for a key pressed
 * by accident is bounded rather than open-ended.
 */
class DictationManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(DictationState.IDLE)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    /** Swapped for the AWS-backed implementation once it exists. */
    var transcriber: Transcriber = StubTranscriber()

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
                // Busy finishing the last one; ignore rather than queue a second.
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
            } catch (e: Exception) {
                hardStop.cancel()
                _state.value = DictationState.ERROR
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
                }
            }
            _state.value = DictationState.IDLE
        }
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
