// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the "+" button in the suggestion strip, shown while the word being typed is not in
// any dictionary. Tapping it saves the word to the personal dictionary (Android's user dictionary,
// the same one Settings > Personal dictionary edits), for all languages.
package helium314.keyboard.tx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SovereignAddWord {
    /** HeliBoard's user dictionary skips longer words (UserBinaryDictionary.MAX_WORD_LENGTH). */
    private const val MAX_LENGTH = 48
    private const val FREQUENCY = 250 // the user dictionary's usual default
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * What "+" would add, given the text before the cursor: the whole run of non-space characters
     * up to the cursor (so "jchackett@proton.me" in full, although HeliBoard composes only the part
     * after the last "."), without punctuation at either end. Null if there is nothing worth adding
     * or it is already a known word ([isKnown] is asked as typed and in lower case).
     */
    @JvmStatic
    fun candidate(textBeforeCursor: CharSequence?, isKnown: (String) -> Boolean): String? {
        val before = textBeforeCursor?.toString() ?: return null
        var start = before.length
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val word = before.substring(start).trim { !it.isLetterOrDigit() }
        if (word.length < 2 || word.length > MAX_LENGTH || word.none { it.isLetter() }) return null
        if (isKnown(word) || isKnown(word.lowercase())) return null
        return word
    }

    /** Saves [word] to the personal dictionary and says so. */
    @JvmStatic
    fun add(context: Context, word: String) {
        val app = context.applicationContext
        scope.launch {
            // can throw IllegalArgumentException (Unknown URL content://user_dictionary/words) on some devices
            val ok = runCatching { UserDictionary.Words.addWord(app, word, FREQUENCY, null, null) }.isSuccess
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, if (ok) "Added $word" else "Couldn't add $word", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
