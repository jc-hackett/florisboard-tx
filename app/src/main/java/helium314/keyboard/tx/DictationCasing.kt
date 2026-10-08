// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: fits a dictation to the text already before the cursor. Dictating into the middle of a
// sentence shouldn't start with a capital ("I went to the Store" style), and needs a space after the
// previous word. Only a mid-sentence flag ever goes to the server; the text itself stays on the phone.
package helium314.keyboard.tx

object DictationCasing {
    /** How much text before the cursor is looked at. */
    const val CONTEXT_CHARS = 200

    private val SENTENCE_END = setOf('.', '?', '!', '…')
    /** Closing quotes / brackets that may follow a sentence end: "Done." or (yes!) */
    private val CLOSERS = setOf('"', '\'', ')', ']', '}', '”', '’', '»')
    private val I_WORDS = setOf("I", "I'm", "I'll", "I've", "I'd", "I’m", "I’ll", "I’ve", "I’d")
    /** Punctuation a dictation may start with that must stick to the previous word (no space before). */
    private val NO_SPACE_BEFORE = setOf('.', ',', '?', '!', ';', ':', '…', ')', ']', '}', '%', '\'', '’', '”')

    /**
     * True when [before] (text before the cursor; null = unknown) ends inside a sentence: its last
     * non-space character is not a sentence end, and there is no line break after it.
     * Empty field, start of field, a new line, or after . ? ! … → a new sentence.
     */
    fun isMidSentence(before: String?): Boolean {
        if (before.isNullOrEmpty()) return false
        var i = before.length - 1
        while (i >= 0 && before[i].isWhitespace()) {
            if (before[i] == '\n' || before[i] == '\r') return false
            i--
        }
        if (i < 0) return false
        // skip closing quotes / brackets after a sentence end
        var j = i
        while (j >= 0 && before[j] in CLOSERS) j--
        if (j >= 0 && before[j] in SENTENCE_END) return false
        return true
    }

    /**
     * [text] as it should be typed after [before]: first letter lower-cased mid-sentence (unless "I"-words,
     * ALL CAPS, or a word the user's lists spell with a capital), and a single space in front when the
     * previous character isn't whitespace and the dictation doesn't start with punctuation.
     */
    fun fit(text: String, before: String?, knownWords: Collection<String>): String {
        var t = text
        if (isMidSentence(before)) t = lowerFirst(t, knownWords)
        if (!before.isNullOrEmpty()) {
            val prev = before.last()
            if (prev.isWhitespace()) {
                t = t.trimStart(' ', '\t') // don't double spaces
            } else {
                val first = t.firstOrNull()
                if (first != null && !first.isWhitespace() && first !in NO_SPACE_BEFORE) t = " $t"
            }
        }
        return t
    }

    private fun lowerFirst(text: String, knownWords: Collection<String>): String {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return text
        val end = text.indexOfFirst(start) { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val rawWord = text.substring(start, end)
        val word = rawWord.trimEnd { !it.isLetterOrDigit() }.trimStart { !it.isLetterOrDigit() }
        if (word.isEmpty() || !word[0].isUpperCase()) return text
        if (word in I_WORDS || word.trimEnd('.', ',') == "I") return text
        val letters = word.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return text // acronym: NHS, LCSW
        if (knownWords.any { it.isNotEmpty() && it[0].isUpperCase() && startsWithWord(it, word) }) return text
        val at = text.indexOf(word[0], start)
        return text.substring(0, at) + word[0].lowercaseChar() + text.substring(at + 1)
    }

    /** [entry] (a list line, maybe several words like "New York") begins with [word]. */
    private fun startsWithWord(entry: String, word: String): Boolean {
        val firstWord = entry.trim().split(' ', '\t').first().trimEnd { !it.isLetterOrDigit() }
        return firstWord == word
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (k in from until length) if (predicate(this[k])) return k
        return -1
    }
}
