// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: ported from florisboard-tx (feat/dictate). Same protocol: one chunked POST to
// <server>/v1/dictate/stream, 16 kHz mono PCM16, Bearer token, X-Dictate-Words, reply {"text": ...}.
package helium314.keyboard.tx

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A dictation failure with a message fit to show the user in a toast. */
class DictationException(val userMessage: String) : Exception(userMessage)

/**
 * One finished dictation: the [text] to type in, and [keepId], the server's id for the saved
 * recording when "Save my recordings" is on (null otherwise).
 */
data class Transcript(val text: String, val keepId: String? = null)

/** Turns captured speech into text ready to be committed to the editor. */
interface Transcriber {
    /**
     * Captures audio until [stillRecording] returns false, then returns the finished text, or null
     * if nothing usable was said. Throws [DictationException] with a user-facing message on failure.
     * [midSentence]: the cursor sits inside a sentence, so the server shouldn't start with a capital
     * (only that flag is sent, never the text around the cursor).
     */
    suspend fun transcribe(stillRecording: () -> Boolean, midSentence: Boolean = false): Transcript?
}

/**
 * Records from the microphone and streams the audio, as it is recorded, to the user's own
 * dictation server. Audio is held in memory only and never written to storage.
 */
class ServerTranscriber(context: Context) : Transcriber {
    private val appContext = context.applicationContext

    override suspend fun transcribe(stillRecording: () -> Boolean, midSentence: Boolean): Transcript? {
        val settings = DictationSettings(appContext)
        val server = settings.serverUrl
        val token = settings.token
        val words = settings.wordList
        val keep = settings.keepRecordings
        if (server.isBlank() || token.isBlank()) throw DictationException(DictationMessages.NOT_SET_UP)
        if (!server.startsWith("https://")) throw DictationException(DictationMessages.NOT_HTTPS)
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw DictationException(DictationMessages.NO_MICROPHONE)
        }

        return withContext(Dispatchers.IO) {
            coroutineScope {
                // Recording never waits on the network: chunks queue here while the connection
                // is still being set up, and the uploader drains them as fast as it can.
                val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                val upload = async { stream(server, token, words, keep, midSentence, chunks) }
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
            throw DictationException(DictationMessages.MICROPHONE_BUSY)
        }
        val chunk = ByteArray(SAMPLE_RATE * BYTES_PER_SAMPLE / 10)
        var total = 0
        val ctx = currentCoroutineContext()
        try {
            recorder.startRecording()
            while (stillRecording()) {
                ctx.ensureActive()
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    total += n
                    onChunk(chunk.copyOf(n))
                }
            }
        } finally {
            // Microphone closed the moment recording ends, whatever happens next.
            runCatching { recorder.stop() }
            recorder.release()
        }
        return total
    }

    private suspend fun stream(
        server: String,
        token: String,
        words: List<String>,
        keep: Boolean,
        midSentence: Boolean,
        chunks: ReceiveChannel<ByteArray>,
    ): Transcript? {
        val conn = (URL(server.trimEnd('/') + "/v1/dictate/stream").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(0)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            if (words.isNotEmpty()) {
                setRequestProperty("X-Dictate-Words", URLEncoder.encode(words.joinToString("\n"), "UTF-8"))
            }
            // A flag only: the text around the cursor never leaves the phone.
            if (midSentence) setRequestProperty("X-Dictate-Context", "mid-sentence")
            // Opt-in only ("Save my recordings"): without this header the server stores nothing.
            if (keep) {
                setRequestProperty("X-Dictate-Keep", "1")
                setRequestProperty("X-Dictate-App", BuildConfig.BUILD_COMMIT_HASH.take(12))
            }
        }
        // A cancelled dictation must drop the connection at once, even mid-write.
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) conn.disconnect() }
        try {
            conn.outputStream.use { out ->
                for (piece in chunks) {
                    out.write(piece)
                    out.flush()
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                SovereignToken.onRejected(appContext)
                throw DictationException(DictationMessages.BAD_TOKEN)
            }
            if (code !in 200..299) throw DictationException(DictationMessages.SERVER)
            SovereignToken.onAccepted(appContext)
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val reply = JSONObject(json)
            val text = reply.optString("text").trim()
            // Only sent back when the server kept this recording (opt-in): its id, for fix labels.
            val keepId = reply.optString("keep_id").takeIf { keep && KEEP_ID.matches(it) }
            return if (text.isEmpty()) null else Transcript("$text ", keepId)
        } catch (e: DictationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The server may have refused before we finished sending (a bad token, say).
            val code = runCatching { conn.responseCode }.getOrNull()
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                SovereignToken.onRejected(appContext)
                throw DictationException(DictationMessages.BAD_TOKEN)
            }
            throw DictationException(DictationMessages.NETWORK)
        } catch (e: Exception) {
            throw DictationException(DictationMessages.NETWORK)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        /** Clips shorter than this (in tenths of a second) are treated as accidental presses. */
        private const val MIN_SECONDS_TENTHS = 3
        /** Short on purpose: the server normally answers well under a second after release. */
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        /** Shape of a kept recording's id (the server checks it again). */
        val KEEP_ID = Regex("""^\d{8}T\d{6}Z-[0-9a-f]{8}-\d+(\.\d)?s$""")
    }
}

/**
 * The user's opt-in kept recordings on the server ("Save my recordings"): how many there are, and
 * deleting them all. Both throw [DictationException] with a user-facing message on failure.
 */
object KeptRecordings {
    /** Number of kept recordings, and of corrections (fixes) kept with them. */
    suspend fun count(context: Context): Pair<Int, Int> =
        call(context, "GET").let { it.optInt("count", 0) to it.optInt("fixes", 0) }

    /** Deletes every kept recording; returns how many were deleted. */
    suspend fun deleteAll(context: Context): Int = call(context, "DELETE").optInt("deleted", 0)

    private suspend fun call(context: Context, method: String): JSONObject = withContext(Dispatchers.IO) {
        val settings = DictationSettings(context)
        val server = settings.serverUrl
        val token = settings.token
        if (server.isBlank() || token.isBlank()) throw DictationException(DictationMessages.NOT_SET_UP)
        if (!server.startsWith("https://")) throw DictationException(DictationMessages.NOT_HTTPS)
        val conn = (URL(server.trimEnd('/') + "/v1/keep").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                SovereignToken.onRejected(context)
                throw DictationException(DictationMessages.BAD_TOKEN)
            }
            if (code !in 200..299) throw DictationException(DictationMessages.SERVER)
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: DictationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DictationException(DictationMessages.NETWORK)
        } finally {
            conn.disconnect()
        }
    }
}

/** User-facing messages, English only for now. */
object DictationMessages {
    const val NOT_SET_UP = "Dictation isn't set up yet. Add the access token in SovereignBoard settings."
    const val NOT_HTTPS = "The dictation server address must start with https://"
    const val NO_MICROPHONE = "Microphone permission is needed. Grant it in SovereignBoard settings."
    const val MICROPHONE_BUSY = "The microphone is busy in another app."
    const val BAD_TOKEN = "The dictation server refused the access token."
    const val SERVER = "The dictation server had a problem. Try again."
    const val NETWORK = "Couldn't reach the dictation server. Check your connection."
    const val CANCELLED = "Dictation cancelled."
}
