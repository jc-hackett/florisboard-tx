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
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Autocorrect for typing, done entirely on the phone.
 *
 * FlorisBoard has no autocorrect of its own. When the user presses space, this asks the phone's
 * system spell checker (the one chosen in Android's Settings > Languages > Spell checker) about
 * the word just finished. If it looks like a typo and the checker has a confident fix, the word
 * is swapped for the fix. The space is typed first and the check runs afterwards, so typing never
 * waits; the swap only happens if the text still ends with exactly "word ".
 *
 * A backspace straight after a correction puts the original word back (and the swap is then left
 * alone for that word).
 *
 * Never touches passwords, email or web addresses, words containing digits or symbols, words in
 * capitals, or anything on the user's dictation word list.
 */
class AutoCorrector(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings = DictationSettings(appContext)

    private var session: SpellCheckerSession? = null
    private var sessionLocale: Locale? = null

    /** Words in flight, by the sequence number they were sent with. */
    private val pending = LinkedHashMap<Int, String>()
    private var sequence = 0

    /** The last swap made, for backspace-to-undo: original -> replacement. */
    private var lastCorrection: Pair<String, String>? = null

    /** Words the user reverted this session; never corrected again. */
    private val refused = mutableSetOf<String>()

    /** Call just before the space is committed, with the text before the cursor at that moment. */
    fun onSpace(textBeforeCursor: String, editorInfo: FlorisEditorInfo, locale: Locale) {
        lastCorrection = null
        if (!settings.autocorrect || !isEligibleField(editorInfo)) return
        val word = WORD_AT_END.find(textBeforeCursor)?.value ?: return
        if (!isEligibleWord(word)) return
        val s = sessionFor(locale) ?: return
        sequence += 1
        pending[sequence] = word
        while (pending.size > 8) pending.remove(pending.keys.first())
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, word.length, 0, sequence)), 3) }
    }

    /** Plain-words status of the phone's spell checker, for Settings > Dictation. */
    fun spellCheckerStatus(): String {
        val tsm = appContext.getSystemService(TextServicesManager::class.java)
            ?: return "No spell checker service on this phone."
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return "This Android version doesn't say which spell checker is in use; tap to test it."
        }
        if (!tsm.isSpellCheckerEnabled) return "The phone's spell checker is switched off (Settings > System > Languages > Spell checker)."
        val info = tsm.currentSpellCheckerInfo ?: return "No spell checker is chosen (Settings > System > Languages > Spell checker)."
        val name = info.loadLabel(appContext.packageManager).toString()
        val isOurs = info.packageName == appContext.packageName
        return if (isOurs) {
            "Spell checker in use: $name. That is FlorisBoard's own, which knows almost no words; choose Gboard's or Android's instead."
        } else {
            "Spell checker in use: $name."
        }
    }

    /** Asks the spell checker about [word] directly and describes the answer, for testing. */
    suspend fun test(word: String, locale: Locale): String {
        val tsm = appContext.getSystemService(TextServicesManager::class.java) ?: return "No spell checker service."
        val result = CompletableDeferred<String>()
        val testListener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                val sentence = results?.firstOrNull()
                if (sentence == null || sentence.suggestionsCount == 0) {
                    result.complete("\"$word\": the spell checker answered with nothing.")
                    return
                }
                val info = sentence.getSuggestionsInfoAt(0)
                val a = info.suggestionsAttributes
                val known = a and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0
                val typo = a and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
                val sure = a and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0
                val list = (0 until maxOf(0, info.suggestionsCount)).mapNotNull { info.getSuggestionAt(it) }
                val fix = pickFix(word, info)
                result.complete(
                    "\"$word\": " + when {
                        known -> "it knows this word."
                        !typo -> "it doesn't flag it as a typo."
                        else -> "typo" + (if (sure) ", confident" else "") +
                            "; suggests ${list.joinToString().ifEmpty { "nothing" }}"
                    } + (fix?.let { ". Autocorrect would type \"$it\"." } ?: ". Autocorrect would leave it.")
                )
            }
        }
        val s = runCatching { tsm.newSpellCheckerSession(null, locale, testListener, true) }.getOrNull()
            ?: return "Couldn't open the spell checker (it may be off)."
        return try {
            s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, word.length, 0, 1)), 3)
            withTimeoutOrNull(4_000) { result.await() } ?: "\"$word\": no answer from the spell checker within 4 seconds."
        } finally {
            s.close()
        }
    }

    /** Call on backspace. Returns true if it undid a correction (and the backspace is used up). */
    fun onBackspace(): Boolean {
        val (original, replacement) = lastCorrection ?: return false
        lastCorrection = null
        val ic = FlorisImeService.currentInputConnection() ?: return false
        val before = ic.getTextBeforeCursor(replacement.length + 1, 0)?.toString() ?: return false
        if (before != "$replacement ") return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(replacement.length + 1, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        refused += original.lowercase()
        return true
    }

    /** Call when the user types anything other than space/backspace, so undo can't fire late. */
    fun onOtherInput() {
        lastCorrection = null
    }

    private fun isEligibleField(info: FlorisEditorInfo): Boolean {
        val attrs = info.inputAttributes
        if (attrs.type != InputAttributes.Type.TEXT) return false
        return attrs.variation !in SKIP_VARIATIONS
    }

    private fun isEligibleWord(word: String): Boolean {
        if (word.length < 2) return false
        if (word.length > 1 && word.all { it.isUpperCase() || it == '\'' }) return false // acronyms
        if (word.lowercase() in refused) return false
        val mine = settings.wordList.map { it.substringBefore('=').trim().lowercase() }
        return word.lowercase() !in mine
    }

    private fun sessionFor(locale: Locale): SpellCheckerSession? {
        if (session != null && sessionLocale == locale) return session
        session?.close()
        val tsm = appContext.getSystemService(TextServicesManager::class.java) ?: return null
        session = runCatching {
            tsm.newSpellCheckerSession(null, locale, listener, true)
        }.getOrNull()
        sessionLocale = locale
        return session
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val sentence = results?.firstOrNull() ?: return
            if (sentence.suggestionsCount == 0) return
            val info = sentence.getSuggestionsInfoAt(0)
            // Some checkers don't echo the sequence back; then it can only be the latest word.
            // Either way apply() re-checks that the text still ends with exactly "word ".
            val word = pending.remove(info.sequence) ?: pending[sequence] ?: return
            val fix = pickFix(word, info) ?: return
            mainHandler.post { apply(word, fix) }
        }
    }

    private fun pickFix(word: String, info: SuggestionsInfo): String? {
        val attrs = info.suggestionsAttributes
        if (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        if (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) return null
        if (info.suggestionsCount <= 0) return null
        val top = info.getSuggestionAt(0)?.takeIf { it.isNotBlank() && !it.contains(' ') } ?: return null
        if (top.equals(word, ignoreCase = true)) return null
        val confident = attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0
        val close = editDistance(word.lowercase(), top.lowercase()) <= if (word.length >= 6) 2 else 1
        if (!confident && !close) return null
        return matchCase(word, top)
    }

    private fun apply(word: String, fix: String) {
        val ic = FlorisImeService.currentInputConnection() ?: return
        val before = ic.getTextBeforeCursor(word.length + 2, 0)?.toString() ?: return
        // Only if nothing has changed since the space: text must end with exactly "word ".
        if (!before.endsWith("$word ")) return
        if (before.length > word.length + 1 && before[before.length - word.length - 2].isLetterOrDigit()) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length + 1, 0)
        ic.commitText("$fix ", 1)
        ic.endBatchEdit()
        lastCorrection = word to fix
    }

    private fun matchCase(original: String, fix: String): String {
        return if (original.first().isUpperCase()) {
            fix.replaceFirstChar { it.uppercaseChar() }
        } else {
            fix
        }
    }

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    companion object {
        /** The last run of letters (and inner apostrophes) right before the cursor. */
        private val WORD_AT_END = Regex("""(?<![\p{L}\p{N}@._/-])\p{L}[\p{L}']*$""")

        private val SKIP_VARIATIONS = setOf(
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
            InputAttributes.Variation.EMAIL_ADDRESS,
            InputAttributes.Variation.WEB_EMAIL_ADDRESS,
            InputAttributes.Variation.URI,
            InputAttributes.Variation.PERSON_NAME,
        )
    }
}
