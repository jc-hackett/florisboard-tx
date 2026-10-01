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
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Autocorrect for typing, done entirely on the phone.
 *
 * FlorisBoard has no autocorrect of its own. When the user presses space, this asks the phone's
 * system spell checker (the one chosen in Android's Settings > Languages > Spell checker) about
 * the word just finished. If the checker says it isn't a real word, the fix is chosen from the
 * keyboard's own English frequency list (assets ime/dict/data.json): the closest common word,
 * then the most common among equally close ones. Gboard's own suggestion order proved unhelpful
 * ("teh" -> "tech" first), so it only decides "real word or not" and adds candidates. The space is typed first and the check runs afterwards, so typing never
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

    /** Common English words -> frequency (0-255), loaded once in the background. */
    @Volatile private var words: Map<String, Int> = emptyMap()
    private val worker = Executors.newSingleThreadExecutor()

    init {
        worker.execute {
            words = runCatching {
                val o = JSONObject(appContext.assets.open("ime/dict/data.json").bufferedReader().use { it.readText() })
                val m = HashMap<String, Int>(o.length() * 2)
                for (k in o.keys()) m[k] = o.optInt(k)
                m
            }.getOrDefault(emptyMap())
        }
    }

    /** Call just before the space is committed, with the text before the cursor at that moment. */
    fun onSpace(textBeforeCursor: String, editorInfo: FlorisEditorInfo, locale: Locale) {
        lastCorrection = null
        if (!settings.autocorrect || !isEligibleField(editorInfo)) return
        val word = WORD_AT_END.find(textBeforeCursor)?.value ?: return
        // "i", "i'm", "i've"... -> "I", "I'm": no spell checker needed. Runs after the space lands.
        if (word == "i" || word.startsWith("i'")) {
            val fixed = "I" + word.drop(1)
            mainHandler.post { apply(word, fixed) }
            return
        }
        if (!isEligibleWord(word)) return
        val s = sessionFor(locale) ?: return
        sequence += 1
        pending[sequence] = word
        while (pending.size > 8) pending.remove(pending.keys.first())
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, word.length, 0, sequence)), 10) }
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
            s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, word.length, 0, 1)), 10)
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
            // Choosing a fix can mean checking thousands of spellings; keep it off the main thread.
            worker.execute {
                val fix = pickFix(word, info) ?: return@execute
                mainHandler.post { apply(word, fix) }
            }
        }
    }

    private fun pickFix(word: String, info: SuggestionsInfo): String? {
        val attrs = info.suggestionsAttributes
        if (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        if (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) return null
        val lower = word.lowercase()
        val maxDist = if (word.length >= 4) 2 else 1
        // Candidates: the checker's own suggestions, plus every common word one or two edits away.
        val fromChecker = (0 until maxOf(0, info.suggestionsCount))
            .mapNotNull { info.getSuggestionAt(it)?.lowercase() }
            .filter { it.isNotBlank() && !it.contains(' ') }
        val local = if (words.isEmpty()) emptySet() else {
            val one = edits(lower).filterTo(HashSet()) { it in words }
            if (one.isEmpty() && maxDist >= 2 && lower.length <= 12) edits(lower).flatMap { edits(it) }.filterTo(HashSet()) { it in words } else one
        }
        val best = (fromChecker + local).distinct()
            .map { it to editDistance(lower, it) }
            // The frequency list was built from real text and contains common typos too ("teh"),
            // so being on it proves nothing; a fix must just be clearly more common than the typo.
            .filter { (c, d) -> d in 1..maxDist && c != lower && (words[c] ?: 0) >= (words[lower]?.plus(20) ?: 0) }
            // A missing apostrophe ("dont", "cant", "im") beats any other fix ("can", "i").
            .minWithOrNull(compareBy<Pair<String, Int>>(
                { if (it.first.replace("'", "") == lower) 0 else 1 },
                { it.second },
                { -(words[it.first] ?: 0) },
            ))
            ?: return null
        val fix = matchCase(word, best.first)
        return if (fix.startsWith("i'")) "I" + fix.drop(1) else fix  // "im" -> "I'm", not "i'm"
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

    /** Edit distance where swapping two neighbouring letters counts as one edit ("teh" -> "the"). */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    /** Every spelling one edit away: delete, swap neighbours, replace, insert a letter. */
    private fun edits(w: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0..w.length) {
            val l = w.substring(0, i)
            val r = w.substring(i)
            if (r.isNotEmpty()) out += l + r.substring(1)
            if (r.length > 1) out += l + r[1] + r[0] + r.substring(2)
            for (c in ALPHABET) {
                if (r.isNotEmpty()) out += l + c + r.substring(1)
                out += l + c + r
            }
        }
        return out
    }

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz'"

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
