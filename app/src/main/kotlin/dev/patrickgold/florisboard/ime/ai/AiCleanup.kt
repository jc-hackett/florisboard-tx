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
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smartbar's "AI cleanup" button: proofreads the selected text, or the whole field if nothing
 * is selected, through the user's own server (POST /v1/tidy), which de-identifies it before Claude
 * sees it and restores the details afterwards. The original is put on the clipboard (flagged
 * sensitive) before anything is replaced, so it can always be pasted back.
 *
 * Costs a little Claude usage per tap, so it only ever runs on an explicit tap. Inert in incognito
 * mode and in password fields.
 */
class AiCleanup private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun run(editorInfo: FlorisEditorInfo) {
        if (_busy.value) return
        if (editorInfo.inputAttributes.variation in PASSWORDS) return toast("AI cleanup doesn't touch password fields")
        val settings = DictationSettings(appContext)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) {
            return toast("AI cleanup: add the server address and token in Settings, Dictation")
        }
        val ic = FlorisImeService.currentInputConnection() ?: return
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
        if (original.length >= MAX_CHARS) return toast("AI cleanup: that's too long; select a part of it")

        _busy.value = true
        scope.launch {
            try {
                val cleaned = withContext(Dispatchers.IO) { request(settings.serverUrl, settings.token, original) }
                if (cleaned == null || cleaned == original) {
                    toast("AI cleanup: nothing to change")
                    return@launch
                }
                val ic2 = FlorisImeService.currentInputConnection() ?: return@launch
                // Only replace what we read: if the user typed meanwhile, leave everything alone.
                if (selected.isNotEmpty()) {
                    if (ic2.getSelectedText(0)?.toString() != selected) return@launch toast("AI cleanup: text changed, not applied")
                } else {
                    val nowBefore = ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    val nowAfter = ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
                    if (nowBefore != before || nowAfter != after) return@launch toast("AI cleanup: text changed, not applied")
                }
                copyOriginal(original)
                ic2.beginBatchEdit()
                if (selected.isEmpty()) ic2.deleteSurroundingText(before.length, after.length)
                ic2.commitText(cleaned, 1)
                ic2.endBatchEdit()
                toast("Cleaned up. Your original is on the clipboard.")
            } catch (e: Exception) {
                toast("AI cleanup failed: ${e.message ?: "no connection"}")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun request(server: String, token: String, text: String): String? {
        val conn = (URL(server.trimEnd('/') + "/v1/tidy").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401 -> error("the server didn't accept the token")
                503 -> error("not set up on the server")
                else -> error("server answered $code")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return o.optString("text").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    private fun copyOriginal(text: String) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Before AI cleanup", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        runCatching { clipboard.setPrimaryClip(clip) }
    }

    private fun toast(text: String) {
        scope.launch { appContext.showShortToast(text) }
    }

    companion object {
        private const val MAX_CHARS = 6000
        private val PASSWORDS = setOf(
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
        )

        @Volatile private var instance: AiCleanup? = null

        fun get(context: Context): AiCleanup =
            instance ?: synchronized(this) { instance ?: AiCleanup(context).also { instance = it } }
    }
}
