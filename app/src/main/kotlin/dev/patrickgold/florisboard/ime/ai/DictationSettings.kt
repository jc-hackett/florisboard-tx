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
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
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

    /** Keep the opt-in "Recent edits" log on this phone (see EditLog). Off unless switched on. */
    var editLog: Boolean
        get() = prefs.getBoolean(KEY_EDIT_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_EDIT_LOG, value).apply()

    /** Button colours by name (see ButtonColors); "theme" keeps the theme's own colour. */
    var enterColor: String
        get() = prefs.getString(KEY_ENTER_COLOR, "theme") ?: "theme"
        set(value) = prefs.edit().putString(KEY_ENTER_COLOR, value).apply()
    var micColor: String
        get() = prefs.getString(KEY_MIC_COLOR, "theme") ?: "theme"
        set(value) = prefs.edit().putString(KEY_MIC_COLOR, value).apply()
    var sparkleColor: String
        get() = prefs.getString(KEY_SPARKLE_COLOR, "theme") ?: "theme"
        set(value) = prefs.edit().putString(KEY_SPARKLE_COLOR, value).apply()

    /** Smarter tapping near key borders (TouchGuess). On unless switched off. */
    var touchGuess: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_GUESS, true)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_GUESS, value).apply()

    /** Spellings from the server's own word list (see WordSync), cached here. */
    var serverWords: String
        get() = prefs.getString(KEY_SERVER_WORDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_WORDS, value).apply()
    var serverWordsFetchedAt: Long
        get() = prefs.getLong(KEY_SERVER_WORDS_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SERVER_WORDS_AT, value).apply()

    /** The user's own words for suggestions: their list (spellings only) plus the server's. */
    val myWords: List<String>
        get() = (wordList.map { it.substringBefore('=').trim() } + serverWords.lines().map { it.trim() })
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    val wordList: List<String>
        get() = words.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_WORDS)

    companion object {
        private const val FILE = "dictation"
        /** Filled in for new installs so only the access token (or a setup link) is needed. */
        const val DEFAULT_SERVER = "https://dictate.limn.dev"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_WORDS = "words"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup_on_period"
        private const val KEY_EDIT_LOG = "edit_log"
        private const val KEY_SERVER_WORDS = "server_words"
        private const val KEY_SERVER_WORDS_AT = "server_words_at"
        private const val KEY_TOUCH_GUESS = "touch_guess"
        private const val KEY_ENTER_COLOR = "enter_color"
        private const val KEY_MIC_COLOR = "mic_color"
        private const val KEY_SPARKLE_COLOR = "sparkle_color"
        const val MAX_WORDS = 200
    }
}
