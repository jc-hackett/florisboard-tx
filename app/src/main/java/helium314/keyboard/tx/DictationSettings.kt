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

    /**
     * Also add each dictation to the keyboard's own clipboard history (not the system clipboard).
     * Same stored key as the old "copy dictation to clipboard" switch, so the user's choice carries over.
     */
    var addToClipboardHistory: Boolean
        get() = prefs.getBoolean(KEY_COPY_TO_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_COPY_TO_CLIPBOARD, value).apply()

    /**
     * Opt-in: ask the server to keep each dictation's audio (encrypted) to train on the user's own
     * voice. Off by default; sent as X-Dictate-Keep: 1 only while on.
     */
    var keepRecordings: Boolean
        get() = prefs.getBoolean(KEY_KEEP_RECORDINGS, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_RECORDINGS, value).apply()

    /** The server answered 401 / 403 to the saved token; cleared when a call with it succeeds. */
    var tokenRejected: Boolean
        get() = prefs.getBoolean(KEY_TOKEN_REJECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TOKEN_REJECTED, value).apply()

    /** When the token was last checked against the server (System.currentTimeMillis). */
    var lastTokenCheck: Long
        get() = prefs.getLong(KEY_LAST_TOKEN_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TOKEN_CHECK, value).apply()

    companion object {
        private const val FILE = "dictation"
        private const val KEY_TOKEN_REJECTED = "token_rejected"
        private const val KEY_LAST_TOKEN_CHECK = "last_token_check"
        private const val KEY_COPY_TO_CLIPBOARD = "copy_to_clipboard"
        private const val KEY_KEEP_RECORDINGS = "keep_recordings"
        const val DEFAULT_SERVER = "https://dictate.limn.dev"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_WORDS = "words"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup_on_period"
        const val MAX_WORDS = 200
    }
}
