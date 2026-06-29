package com.dabber.text

/**
 * Deterministic post-processor for raw ASR output.
 *
 * Pure Kotlin (no Android dependencies) so it can be exercised by fast JVM unit
 * tests. All transformations are order-stable and contain no randomness, so the
 * same input always yields the same output.
 *
 * The cleaner is intentionally conservative: it only touches whole-word fillers,
 * immediately-repeated words, and obvious whitespace/punctuation spacing. It never
 * rewrites or reorders the user's actual words.
 */
object TextCleaner {

    /**
     * Filler interjections to strip when [clean]'s `removeFillers` is enabled.
     *
     * Matched as whole words only (see [FILLER_REGEX]) so a filler embedded in a
     * longer word — e.g. Hebrew "אמא" or English "umbrella" — is left untouched.
     * English entries are matched case-insensitively; Hebrew has no case.
     */
    private val FILLERS = listOf(
        // Hebrew interjections.
        "אהה", "אה", "אם", "אממ", "אהמ", "אמ",
        // English interjections.
        "emm", "em", "um", "uh", "eh",
    )

    /** Characters that must not be preceded by whitespace (e.g. "word ," -> "word,"). */
    private const val PUNCTUATION = ",.!?;:"

    /**
     * Whole-word filler matcher.
     *
     * The lookbehind/lookahead assert that the filler is bounded by non-letter,
     * non-digit characters (Unicode-aware via `\p{L}` / `\p{N}`), which is what
     * makes the match "whole word" for both Hebrew and Latin scripts. Longest
     * alternatives still win because the trailing boundary rejects a partial match.
     */
    private val FILLER_REGEX = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + FILLERS.joinToString("|") + ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Immediately-repeated identical word matcher (case-sensitive backreference).
     *
     * Captures a word and collapses any number of whitespace-separated identical
     * repetitions that follow it, e.g. "שלום שלום שלום" -> "שלום". Punctuation
     * between two words means they are not an "immediate" repeat, so it is not
     * collapsed.
     */
    private val REPEAT_REGEX = Regex(
        "(?<![\\p{L}\\p{N}])([\\p{L}\\p{N}]+)(?:\\s+\\1)+(?![\\p{L}\\p{N}])",
    )

    /** Any run of whitespace (spaces, tabs, newlines). */
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** One or more spaces directly in front of a punctuation mark. */
    private val SPACE_BEFORE_PUNCT_REGEX = Regex(" +([$PUNCTUATION])")

    /**
     * Cleans a single chunk of dictated [text].
     *
     * Pipeline (each step deterministic):
     * 1. Optionally drop whole-word fillers ([removeFillers]).
     * 2. Optionally collapse immediately-repeated identical words ([collapseRepeats]).
     * 3. Always normalise spacing: collapse whitespace runs to one space, drop
     *    spaces before punctuation, and trim the ends.
     *
     * @param text raw recognised text.
     * @param removeFillers strip filler interjections such as "אה"/"um" when true.
     * @param collapseRepeats fold "word word" down to "word" when true.
     * @return the cleaned, naturally-reading text.
     */
    fun clean(
        text: String,
        removeFillers: Boolean = true,
        collapseRepeats: Boolean = true,
    ): String {
        var result = text
        if (removeFillers) {
            result = FILLER_REGEX.replace(result, "")
        }
        if (collapseRepeats) {
            result = REPEAT_REGEX.replace(result) { match -> match.groupValues[1] }
        }
        return normalizeSpacing(result)
    }

    /**
     * Collapses whitespace runs to a single space, removes spaces sitting in front
     * of punctuation, and trims leading/trailing whitespace.
     */
    private fun normalizeSpacing(text: String): String {
        val collapsed = WHITESPACE_REGEX.replace(text, " ")
        val tightened = SPACE_BEFORE_PUNCT_REGEX.replace(collapsed, "$1")
        return tightened.trim()
    }
}
