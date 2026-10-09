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

    /**
     * The server's own word list (from GET /v1/words, one per line), kept so a dictation into the middle
     * of a sentence can tell a name ("Erin") from an ordinary word that only got a capital at the start.
     */
    var serverWords: String
        get() = prefs.getString(KEY_SERVER_WORDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_WORDS, value).apply()

    /** The server answered 401 / 403 to the saved token; cleared when a call with it succeeds. */
    var tokenRejected: Boolean
        get() = prefs.getBoolean(KEY_TOKEN_REJECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TOKEN_REJECTED, value).apply()

    /** When the token was last checked against the server (System.currentTimeMillis). */
    var lastTokenCheck: Long
        get() = prefs.getLong(KEY_LAST_TOKEN_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TOKEN_CHECK, value).apply()

    /**
     * Record from the phone's own microphone even when Bluetooth or wired headphones with a mic are
     * connected. On by default: the built-in mic is clearer, and Bluetooth SCO is never started.
     */
    var usePhoneMic: Boolean
        get() = prefs.getBoolean(KEY_USE_PHONE_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_PHONE_MIC, value).apply()

    /** Offer the newest screenshot (under 3 minutes old) as a chip in the suggestion strip. */
    var offerScreenshots: Boolean
        get() = prefs.getBoolean(KEY_OFFER_SCREENSHOTS, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFER_SCREENSHOTS, value).apply()

    /** A changed token checked out; the keyboard says so once, the next time it opens. */
    var tokenChangedPending: Boolean
        get() = prefs.getBoolean(KEY_TOKEN_CHANGED_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_TOKEN_CHANGED_PENDING, value).apply()

    /** The build the update server last offered (empty if none), and when it was last asked. */
    var updateBuild: String
        get() = prefs.getString(KEY_UPDATE_BUILD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_BUILD, value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** The one-time "names aren't hidden" note in the fact-check panel has been seen and accepted. */
    var factCheckNoteSeen: Boolean
        get() = prefs.getBoolean(KEY_FACT_CHECK_NOTE, false)
        set(value) = prefs.edit().putBoolean(KEY_FACT_CHECK_NOTE, value).apply()

    companion object {
        private const val FILE = "dictation"
        private const val KEY_TOKEN_CHANGED_PENDING = "token_changed_pending"
        private const val KEY_USE_PHONE_MIC = "use_phone_mic"
        private const val KEY_OFFER_SCREENSHOTS = "offer_screenshots"
        private const val KEY_UPDATE_BUILD = "update_build"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_FACT_CHECK_NOTE = "fact_check_note_seen"
        private const val KEY_TOKEN_REJECTED = "token_rejected"
        private const val KEY_SERVER_WORDS = "server_words"
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
