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
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.pow

/**
 * "Smarter tapping": when a tap lands near the border between letter keys, pick the letter that
 * best continues the word being typed. A tap well inside a key always stays that key.
 *
 * Each nearby letter key is scored as (how close the tap is to its centre) x (how likely that
 * letter is next, given the letters already typed in this word). "How likely" comes from the
 * keyboard's own English frequency list: the summed weight of all words that start with the word
 * so far plus that letter. Words are kept sorted with a running total of weights, so any prefix
 * is two binary searches. Everything happens on the phone.
 */
object TouchGuess {
    @Volatile private var words: Array<String> = emptyArray()
    @Volatile private var cumulative: DoubleArray = DoubleArray(0)
    @Volatile private var loading = false

    fun ensureLoaded(context: Context) {
        if (words.isNotEmpty() || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            runCatching {
                val o = JSONObject(app.assets.open("ime/dict/data.json").bufferedReader().use { it.readText() })
                val list = ArrayList<Pair<String, Double>>(o.length())
                for (k in o.keys()) {
                    if (k.all { it in 'a'..'z' || it == '\'' }) {
                        // Frequencies are 0-255 on a log-like scale; spread them back out.
                        list += k to 2.0.pow((o.optInt(k) - 128) / 16.0)
                    }
                }
                list.sortBy { it.first }
                val w = Array(list.size) { list[it].first }
                val c = DoubleArray(list.size + 1)
                for (i in list.indices) c[i + 1] = c[i] + list[i].second
                cumulative = c
                words = w
            }
            loading = false
        }.start()
    }

    /** Summed weight of all words starting with [prefix]. */
    private fun weight(prefix: String): Double {
        val w = words
        val c = cumulative
        if (w.isEmpty()) return 0.0
        val lo = lowerBound(w, prefix)
        val hi = lowerBound(w, prefix + '￿')
        return c[hi] - c[lo]
    }

    private fun lowerBound(a: Array<String>, key: String): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    val isReady: Boolean get() = words.isNotEmpty()

    /**
     * Picks among [candidates] (letter, distance from the tap to that key's centre measured in key
     * widths/heights). [wordSoFar] is the lower-case letters of the current word before the cursor.
     * Returns the chosen letter, or null to leave the plain hit-test result alone.
     */
    fun choose(candidates: List<Pair<Char, Float>>, wordSoFar: String): Char? {
        if (!isReady || candidates.size < 2) return null
        val total = weight(wordSoFar).takeIf { it > 0 } ?: return null
        return candidates.maxByOrNull { (letter, d) ->
            val spatial = exp(-(d * d) / (2 * SIGMA * SIGMA))
            val prior = weight(wordSoFar + letter) / total
            spatial * (prior + FLOOR)
        }?.first
    }

    /** Spread of a tap around the intended key centre, in key sizes. Small, so the language
     *  only decides near the borders. */
    private const val SIGMA = 0.35f
    /** Keeps unusual letters possible: a clean hit on an unlikely letter still wins. */
    private const val FLOOR = 0.03
}
