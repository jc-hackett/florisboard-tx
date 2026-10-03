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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps a copy of the server's word list (GET /v1/words) on the phone, so names and terms kept
 * on the server also show up in word suggestions. Refreshed in the background at most hourly.
 */
object WordSync {
    private const val MAX_AGE_MS = 60L * 60 * 1000
    @Volatile private var running = false

    fun refreshIfStale(context: Context) {
        val settings = DictationSettings(context)
        if (running || settings.token.isBlank()) return
        if (System.currentTimeMillis() - settings.serverWordsFetchedAt < MAX_AGE_MS) return
        running = true
        val server = settings.serverUrl
        val token = settings.token
        Thread {
            try {
                val conn = (URL(server.trimEnd('/') + "/v1/words").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("words")
                        val words = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.trim() }
                            .filter { it.isNotEmpty() }
                        settings.serverWords = words.joinToString("\n")
                    }
                    settings.serverWordsFetchedAt = System.currentTimeMillis()
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Offline or server down: try again next time.
            } finally {
                running = false
            }
        }.start()
    }
}
