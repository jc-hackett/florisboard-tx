/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // florisboard-tx word completion (frequencies are 0-255; the list also holds common
        // typos around 130-155, so "common word" starts above that).
        private const val COMMON_WORD_FREQ = 160
        private const val CONFIDENT_COMPLETION_FREQ = 170
        private const val COMPLETION_LEAD = 5
    }

    private val appContext by context.appContext()

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        wordData.withLock { wordData ->
            if (wordData.isEmpty()) {
                // Here we use readText() because the test dictionary is a json dictionary
                val rawData = appContext.assets.readText("ime/dict/data.json")
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                wordData.putAll(jsonData)
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return when (word.lowercase()) {
            // Use typo for typing errors
            "typo" -> SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            // Use grammar error if the algorithm can detect this. On Android 11 and lower grammar errors are visually
            // marked as typos due to a lack of support
            "gerror" -> SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
            // Use valid word for valid input
            else -> SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // florisboard-tx: upstream left this as a stub returning nothing. Word completion from the
        // bundled frequency list: the strip shows the typed word (if it is a common word) and the
        // most frequent words that start with it. Space finishes the word only when the typed
        // letters are not a common word themselves and one completion clearly leads, so "in"
        // stays "in" while "tomo" becomes "tomorrow".
        val typed = content.composingText
        if (typed.isBlank() || typed.length > 40 || !typed.all { it.isLetter() || it == '\'' }) {
            return emptyList()
        }
        val lower = typed.lowercase()
        val count = maxCandidateCount.coerceIn(1, 3)
        // The user's own words (their list + the server's) come first: "sov" -> SovereignBoard.
        val settings = dev.patrickgold.florisboard.ime.ai.DictationSettings(appContext)
        dev.patrickgold.florisboard.ime.ai.WordSync.refreshIfStale(appContext)
        val mine = settings.myWords.filter { it.length > lower.length && it.lowercase().startsWith(lower) }
        return wordData.withLock { data ->
            val typedFreq = data[lower] ?: 0
            val myFinish = mine.size == 1 && lower.length >= 3 && typedFreq < COMMON_WORD_FREQ
            val completions = data.entries.asSequence()
                .filter { it.key.length > lower.length && it.key.startsWith(lower) }
                .sortedByDescending { it.value }
                .take(count + 1)
                .toList()
            val top = completions.firstOrNull()
            val second = completions.getOrNull(1)
            val autoFinish = !myFinish && mine.isEmpty() && top != null && lower.length >= 2 &&
                typedFreq < COMMON_WORD_FREQ && top.value >= CONFIDENT_COMPLETION_FREQ &&
                (second == null || top.value - second.value >= COMPLETION_LEAD)
            buildList {
                if (typedFreq >= COMMON_WORD_FREQ) {
                    add(WordSuggestionCandidate(text = typed, confidence = typedFreq / 255.0,
                        sourceProvider = this@LatinLanguageProvider))
                }
                mine.take(count).forEach { word ->
                    if (size >= count) return@forEach
                    add(WordSuggestionCandidate(
                        text = word,
                        confidence = 1.0,
                        isEligibleForAutoCommit = myFinish,
                        sourceProvider = this@LatinLanguageProvider,
                    ))
                }
                completions.forEachIndexed { i, (word, freq) ->
                    if (size >= count) return@forEachIndexed
                    if (mine.any { it.equals(word, ignoreCase = true) }) return@forEachIndexed
                    add(WordSuggestionCandidate(
                        text = matchCase(typed, word),
                        confidence = freq / 255.0,
                        isEligibleForAutoCommit = i == 0 && autoFinish,
                        sourceProvider = this@LatinLanguageProvider,
                    ))
                }
            }
        }
    }

    private fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
