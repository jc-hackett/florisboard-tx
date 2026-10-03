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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Recent edits" log: each dictation, sparkle and per-sentence cleanup as before and after,
 * with a note the user can add, so the cleanup instructions and word list can be tuned from real
 * examples.
 *
 * Off unless switched on. Kept only on the phone, in the app's private storage (not backed up),
 * and anything older than seven days is dropped every time the log is read or written.
 */
object EditLog {
    data class Entry(
        val id: Long,
        val time: Long,
        val kind: String,
        val before: String,
        val after: String,
        val note: String,
    )

    private const val FILE = "edit-log.json"
    private const val MAX_ENTRIES = 200
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** The most recent result (kind, before, after), kept in memory only, for the thumbs-down. */
    @Volatile private var lastResult: Triple<String, String, String>? = null

    @Synchronized
    fun add(context: Context, kind: String, before: String, after: String) {
        lastResult = Triple(kind, before, after)
        if (!DictationSettings(context).editLog) return
        val list = load(context)
        val now = System.currentTimeMillis()
        list.add(0, Entry(now, now, kind, before.trim(), after.trim(), ""))
        save(context, list.take(MAX_ENTRIES))
    }

    /** A 👎: always kept (it's an explicit choice), whether or not the log is switched on. */
    @Synchronized
    fun addMarked(context: Context, kind: String, before: String, after: String) {
        val list = load(context)
        val now = System.currentTimeMillis()
        list.add(0, Entry(now, now, kind, before.trim(), after.trim(), "👎"))
        save(context, list.take(MAX_ENTRIES))
    }

    /** Thumbs-down on the most recent dictation or cleanup. False if there's nothing to mark. */
    fun markLatest(context: Context): Boolean {
        val (kind, before, after) = lastResult ?: return false
        addMarked(context, "$kind 👎", before, after)
        lastResult = null
        return true
    }

    @Synchronized
    fun load(context: Context): MutableList<Entry> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return mutableListOf()
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val out = mutableListOf<Entry>()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    o.getLong("id"), o.getLong("time"), o.optString("kind"),
                    o.optString("before"), o.optString("after"), o.optString("note"),
                )
                if (e.time >= cutoff) out += e
            }
        }
        return out
    }

    @Synchronized
    fun setNote(context: Context, id: Long, note: String) {
        save(context, load(context).map { if (it.id == id) it.copy(note = note) else it })
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** Plain text for sharing: every entry, newest first, with its note. */
    fun exportText(context: Context): String {
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        return buildString {
            appendLine("Keyboard edits, for tuning the cleanup")
            appendLine()
            for (e in load(context)) {
                appendLine("## ${e.kind}, ${fmt.format(Date(e.time))}")
                appendLine("Before: ${e.before}")
                appendLine("After:  ${e.after}")
                if (e.note.isNotBlank()) appendLine("Note:   ${e.note}")
                appendLine()
            }
        }
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject()
                .put("id", e.id).put("time", e.time).put("kind", e.kind)
                .put("before", e.before).put("after", e.after).put("note", e.note))
        }
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
