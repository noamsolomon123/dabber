package com.dabber.bench

import java.text.Normalizer

/**
 * Hebrew-aware Word Error Rate (WER) used to score benchmark transcriptions.
 *
 * WER is the classic ASR accuracy metric: the word-level edit distance between a
 * reference and a hypothesis, normalized by the reference length. Lower is better;
 * `0.0` means a perfect (post-normalization) match.
 *
 * Hebrew needs care: niqqud (vowel points) and cantillation marks are combining
 * characters that whisper.cpp may or may not emit, and geresh/gershayim have both a
 * dedicated Unicode code point and an ASCII look-alike. [normalize] folds all of
 * these away so scoring reflects real word errors, not orthographic noise.
 */
object HebrewWer {

    /** Inclusive range of Hebrew combining marks: niqqud + cantillation (te'amim). */
    private const val MARK_START = '֑'
    private const val MARK_END = 'ׇ'

    private const val GERESH = '׳'      // ׳ Hebrew punctuation geresh
    private const val GERSHAYIM = '״'   // ״ Hebrew punctuation gershayim

    /**
     * Anything that is NOT a latin letter, digit, base Hebrew letter (`U+05D0..U+05EA`,
     * which already includes the five final forms), apostrophe, quote or whitespace.
     */
    private val DISALLOWED = Regex("[^a-z0-9\\u05D0-\\u05EA'\"\\s]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Canonicalizes [s] for comparison:
     * 1. Unicode NFKD (splits presentation forms / precomposed niqqud into base + marks);
     * 2. drops Hebrew combining marks (`U+0591..U+05C7`);
     * 3. folds geresh `׳`→`'` and gershayim `״`→`"`;
     * 4. lower-cases (affects latin only);
     * 5. replaces every remaining disallowed character with a space;
     * 6. collapses whitespace runs to a single space and trims.
     */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)

        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                ch in MARK_START..MARK_END -> Unit // strip niqqud / cantillation
                ch == GERESH -> sb.append('\'')
                ch == GERSHAYIM -> sb.append('"')
                else -> sb.append(ch)
            }
        }

        return sb.toString()
            .lowercase()
            .replace(DISALLOWED, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /**
     * Word-level WER between [ref] and [hyp].
     *
     * Both are [normalize]d, split on whitespace into word lists, and compared with a
     * word-level Levenshtein edit distance. The result is `distance / max(1, refWords)`,
     * so identical inputs yield `0.0` and a fully wrong transcript yields `~1.0` (or
     * higher when the hypothesis is much longer than the reference).
     */
    fun wer(ref: String, hyp: String): Double {
        val refWords = normalize(ref).split(' ').filter { it.isNotEmpty() }
        val hypWords = normalize(hyp).split(' ').filter { it.isNotEmpty() }
        val distance = levenshtein(refWords, hypWords)
        return distance.toDouble() / maxOf(1, refWords.size)
    }

    /** Levenshtein edit distance over word lists, using two rolling O(m) rows. */
    private fun levenshtein(a: List<String>, b: List<String>): Int {
        val n = a.size
        val m = b.size
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // deletion
                    curr[j - 1] + 1,    // insertion
                    prev[j - 1] + cost, // substitution / match
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }
}
