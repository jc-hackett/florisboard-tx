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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A dictation failure with a message fit to show the user in a toast. */
class DictationException(val messageRes: Int) : Exception()

/**
 * Records from the microphone while the key is held and streams the audio, as it is recorded,
 * to the user's own dictation server (see DictationSettings), which transcribes it on the fly.
 * When the press ends, the upload ends and the server answers with the finished text almost at
 * once, because the transcript was built while the user was still talking.
 *
 * The audio is 16 kHz mono 16-bit PCM, held in memory only and never written to storage. Nothing
 * is sent unless the user explicitly pressed the key; a press too short to be speech is cancelled,
 * which closes the connection and makes the server drop what it had.
 */
class ServerTranscriber(context: Context) : Transcriber {
    private val appContext = context.applicationContext

    override suspend fun transcribe(stillRecording: () -> Boolean): String? {
        val settings = DictationSettings(appContext)
        val server = settings.serverUrl
        val token = settings.token
        if (server.isBlank() || token.isBlank()) throw DictationException(R.string.dictation__error_not_set_up)
        if (!server.startsWith("https://")) throw DictationException(R.string.dictation__error_not_https)
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw DictationException(R.string.dictation__error_no_microphone)
        }

        return withContext(Dispatchers.IO) {
            coroutineScope {
                // Recording never waits on the network: chunks queue here while the connection
                // is still being set up, and the uploader drains them as fast as it can.
                val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                val upload = async { stream(server, token, chunks) }
                val recorded = try {
                    record(stillRecording) { chunks.trySend(it) }
                } finally {
                    chunks.close()
                }
                if (recorded < SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_SECONDS_TENTHS / 10) {
                    upload.cancel()
                    return@coroutineScope null
                }
                upload.await()
            }
        }
    }

    @SuppressLint("MissingPermission") // checked in transcribe()
    private suspend fun record(stillRecording: () -> Boolean, onChunk: (ByteArray) -> Unit): Int {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // ~100 ms per read: small enough that the server hears speech almost as it is spoken.
        val bufSize = maxOf(minBuf, SAMPLE_RATE * BYTES_PER_SAMPLE / 10)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw DictationException(R.string.dictation__error_microphone_busy)
        }
        val chunk = ByteArray(SAMPLE_RATE * BYTES_PER_SAMPLE / 10)
        var total = 0
        try {
            recorder.startRecording()
            while (stillRecording()) {
                coroutineContext.ensureActive()
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    total += n
                    onChunk(chunk.copyOf(n))
                }
            }
        } finally {
            // Microphone closed the moment the press ends, whatever happens next.
            runCatching { recorder.stop() }
            recorder.release()
        }
        return total
    }

    private suspend fun stream(server: String, token: String, chunks: ReceiveChannel<ByteArray>): String? {
        val conn = (URL(server.trimEnd('/') + "/v1/dictate/stream").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(0)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        // A cancelled dictation must drop the connection at once, even mid-write.
        coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) conn.disconnect() }
        try {
            conn.outputStream.use { out ->
                for (piece in chunks) {
                    out.write(piece)
                    out.flush()
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw DictationException(R.string.dictation__error_bad_token)
            if (code !in 200..299) throw DictationException(R.string.dictation__error_server)
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(json).optString("text").trim()
            return if (text.isEmpty()) null else "$text "
        } catch (e: DictationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The server may have refused before we finished sending (a bad token, say).
            val code = runCatching { conn.responseCode }.getOrNull()
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw DictationException(R.string.dictation__error_bad_token)
            throw DictationException(R.string.dictation__error_network)
        } catch (e: Exception) {
            throw DictationException(R.string.dictation__error_network)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        /** Clips shorter than this (in tenths of a second) are treated as accidental presses. */
        private const val MIN_SECONDS_TENTHS = 3
        /** Short on purpose: the server normally answers in well under a second after release,
         *  so a long silence means the connection is dead, and waiting helps no one. */
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
