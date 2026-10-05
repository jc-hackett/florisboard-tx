// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: ported from florisboard-tx (feat/dictate), same file name and keys.
package helium314.keyboard.tx

import android.content.Context

/**
 * Where the dictation server lives and the bearer token for it.
 *
 * Kept in its own private SharedPreferences file ("dictation") rather than the main preference
 * store on purpose: the token should not travel with a settings backup.
 */
class DictationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** The user's own words (names, jargon), one per line, sent with each dictation. */
    var words: String
        get() = prefs.getString(KEY_WORDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORDS, value.trim()).apply()

    val wordList: List<String>
        get() = words.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_WORDS)

    /** Auto-sparkle: tidy the paragraph just ended after a space following . ? ! : ; … — or –. */
    var autoCleanupOnPeriod: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEANUP, value).apply()

    companion object {
        private const val FILE = "dictation"
        const val DEFAULT_SERVER = "https://dictate.limn.dev"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_WORDS = "words"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup_on_period"
        const val MAX_WORDS = 200
    }
}
