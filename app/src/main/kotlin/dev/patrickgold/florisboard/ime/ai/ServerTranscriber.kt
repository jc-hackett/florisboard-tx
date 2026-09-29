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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** A dictation failure with a message fit to show the user in a toast. */
class DictationException(val messageRes: Int) : Exception()

/**
 * Records from the microphone while the key is held, then sends the clip to the user's own
 * dictation server (see DictationSettings) and returns the text it sends back.
 *
 * The clip is 16 kHz mono PCM wrapped as WAV and held in memory only; it is never written to
 * storage. Nothing is sent until recording has ended on an explicit press or release.
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

        val pcm = record(stillRecording)
        if (pcm.size < SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_SECONDS_TENTHS / 10) return null
        val wav = wrapWav(pcm)
        return withContext(Dispatchers.IO) { upload(server, token, wav) }
    }

    @SuppressLint("MissingPermission") // checked in transcribe()
    private suspend fun record(stillRecording: () -> Boolean): ByteArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE * BYTES_PER_SAMPLE / 5)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw DictationException(R.string.dictation__error_microphone_busy)
        }
        val out = ByteArrayOutputStream(SAMPLE_RATE * BYTES_PER_SAMPLE * 15)
        val chunk = ByteArray(bufSize)
        try {
            recorder.startRecording()
            while (stillRecording()) {
                ensureActive()
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) out.write(chunk, 0, n)
            }
        } finally {
            // Microphone closed the moment the press ends, whatever happens next.
            runCatching { recorder.stop() }
            recorder.release()
        }
        out.toByteArray()
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private fun upload(server: String, token: String, wav: ByteArray): String? {
        val boundary = "----tx" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL(server.trimEnd('/') + "/v1/dictate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { body ->
                body.writeBytes("--$boundary\r\n")
                body.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"clip.wav\"\r\n")
                body.writeBytes("Content-Type: audio/wav\r\n\r\n")
                body.write(wav)
                body.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw DictationException(R.string.dictation__error_bad_token)
            if (code !in 200..299) throw DictationException(R.string.dictation__error_server)
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(json).optString("text").trim()
            return if (text.isEmpty()) null else "$text "
        } catch (e: DictationException) {
            throw e
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
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
