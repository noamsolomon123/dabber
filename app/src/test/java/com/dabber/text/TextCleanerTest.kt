package com.dabber.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [TextCleaner]. Covers filler removal (Hebrew + English),
 * the "filler inside a longer word is kept" rule, immediate-repeat collapsing,
 * whitespace/punctuation normalisation, mixed-script input, and flag toggles.
 */
class TextCleanerTest {

    @Test
    fun removesHebrewFillerWords() {
        assertEquals("שלום עולם", TextCleaner.clean("שלום אה עולם"))
    }

    @Test
    fun removesEnglishFillerWordsCaseInsensitively() {
        assertEquals("hello world", TextCleaner.clean("um hello UH world Eh"))
    }

    @Test
    fun keepsHebrewFillerWhenItIsInsideALongerWord() {
        // "אמא" begins with the filler "אמ" but must not be stripped.
        assertEquals("אמא יקרה", TextCleaner.clean("אמא יקרה"))
    }

    @Test
    fun keepsEnglishFillerWhenItIsInsideALongerWord() {
        // "umbrella"/"them" embed "um"/"em" but are real words.
        assertEquals("umbrella them", TextCleaner.clean("umbrella them"))
    }

    @Test
    fun collapsesImmediatelyRepeatedHebrewWord() {
        assertEquals("שלום", TextCleaner.clean("שלום שלום"))
    }

    @Test
    fun collapsesThreeOrMoreRepeatsToOne() {
        assertEquals("כן", TextCleaner.clean("כן כן כן"))
    }

    @Test
    fun collapsesWhitespaceRunsToSingleSpace() {
        assertEquals("שלום עולם", TextCleaner.clean("שלום\t\t   עולם"))
    }

    @Test
    fun removesSpacesBeforePunctuation() {
        assertEquals("שלום, עולם!", TextCleaner.clean("שלום , עולם !"))
    }

    @Test
    fun handlesMixedHebrewEnglishFillersAndRepeats() {
        // "um" stripped, then "שלום שלום" and "world world" collapsed.
        assertEquals("שלום world", TextCleaner.clean("um שלום שלום world world"))
    }

    @Test
    fun doesNotRemoveFillersWhenFlagIsOff() {
        // Two distinct fillers so the repeat-collapse pass does not interfere.
        assertEquals("um uh", TextCleaner.clean("um   uh", removeFillers = false))
    }

    @Test
    fun doesNotCollapseRepeatsWhenFlagIsOff() {
        assertEquals("שלום שלום", TextCleaner.clean("שלום שלום", collapseRepeats = false))
    }

    @Test
    fun doesNotCollapseWordsSeparatedByPunctuation() {
        // A comma between identical words means it is not an immediate repeat.
        assertEquals("שלום, שלום", TextCleaner.clean("שלום , שלום"))
    }
}
