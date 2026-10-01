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

/**
 * Where the dictation server lives and the bearer token for it.
 *
 * Kept in its own private SharedPreferences file rather than the main preference store on purpose:
 * the app's backup/export and Android's auto backup (res/xml/backup_rules.xml) cover the main
 * store, and the token should not travel with a settings backup.
 */
class DictationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * The user's own words (names, jargon), one per line, spelled and capitalised as they should
     * appear. Sent with each dictation so the speech model leans towards them. Kept here, out of
     * backups, because it tends to hold names.
     */
    var words: String
        get() = prefs.getString(KEY_WORDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORDS, value.trim()).apply()

    /** Autocorrect while typing (see AutoCorrector). On unless the user turns it off. */
    var autocorrect: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /** AI cleanup of each sentence when a double-space types its period (uses Claude usage). */
    var autoCleanupOnPeriod: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEANUP, value).apply()

    val wordList: List<String>
        get() = words.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_WORDS)

    companion object {
        private const val FILE = "dictation"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_WORDS = "words"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup_on_period"
        const val MAX_WORDS = 200
    }
}
