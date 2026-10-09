// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: developer mode (unlocks the screenshot fact check).
package helium314.keyboard.tx

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Developer mode: the screenshot fact check is for the developer only. The lock is on the server,
 * not here (this app is open source): the server only checks screenshots for a person its admin page
 * marks "developer" AND who sends a dev token, which POST /v1/dev/unlock hands out for the right PIN
 * (valid 30 days; 5 tries an hour). The PIN is never stored on the phone, only the dev token
 * ([DictationSettings.devToken]). Lock forgets it here and asks the server to forget it too.
 */
object DeveloperMode {
    sealed class Outcome {
        object Unlocked : Outcome()
        object WrongPin : Outcome()
        object TooManyTries : Outcome()
        class Failed(val message: String) : Outcome()
    }

    fun isOn(settings: DictationSettings) = settings.devToken.isNotBlank()

    /** Blocking (call off the main thread). On success the dev token is saved in [settings]. */
    fun unlock(settings: DictationSettings, pin: String): Outcome {
        if (settings.serverUrl.isBlank() || settings.token.isBlank())
            return Outcome.Failed("Add the server address and access token first.")
        val bytes = JSONObject().put("pin", pin).toString().toByteArray()
        val conn = connect(settings, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                val tok = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("dev_token")
                if (tok.isBlank()) return Outcome.Failed("The server gave an odd answer. Try again.")
                settings.devToken = tok
                return Outcome.Unlocked
            }
            val detail = runCatching {
                JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("detail")
            }.getOrDefault("")
            return when {
                code == 429 -> Outcome.TooManyTries
                code == 403 && detail == "Wrong PIN" -> Outcome.WrongPin
                code == 403 -> Outcome.Failed(detail.ifBlank { "Developer mode isn't allowed for you." })
                code == 401 -> Outcome.Failed("The server didn't accept your access token.")
                code == 503 -> Outcome.Failed("Developer mode isn't set up on the server.")
                else -> Outcome.Failed("The server had a problem ($code). Try again.")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking. Forget the dev token here; tell the server too (best effort). */
    fun lock(settings: DictationSettings) {
        val tok = settings.devToken
        settings.devToken = ""
        if (tok.isBlank()) return
        runCatching {
            val conn = connect(settings, "DELETE").apply { setRequestProperty("X-Dev-Token", tok) }
            try { conn.responseCode } finally { conn.disconnect() }
        }
    }

    private fun connect(settings: DictationSettings, method: String): HttpURLConnection =
        (URL(settings.serverUrl.trimEnd('/') + "/v1/dev/unlock").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
}
