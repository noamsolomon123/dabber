package com.dabber.bench

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [HebrewWer]. Cover the identity case, a single substitution,
 * niqqud-insensitivity, punctuation-insensitivity, geresh/gershayim folding,
 * mixed Hebrew/English (case folding), insertions/deletions and direct normalization.
 */
class HebrewWerTest {

    private val eps = 1e-9

    @Test
    fun identicalIsZero() {
        assertEquals(0.0, HebrewWer.wer("שלום עולם", "שלום עולם"), eps)
    }

    @Test
    fun oneSubstitutionOfThreeWords() {
        // "שלוש" -> "ארבע" is a single substitution over a 3-word reference.
        assertEquals(1.0 / 3.0, HebrewWer.wer("אחת שתיים שלוש", "אחת שתיים ארבע"), eps)
    }

    @Test
    fun niqqudIsIgnored() {
        // Same words, one fully vocalized (niqqud + shin dot) and one bare -> 0 WER.
        assertEquals(0.0, HebrewWer.wer("שָׁלוֹם עוֹלָם", "שלום עולם"), eps)
    }

    @Test
    fun punctuationIsIgnored() {
        assertEquals(0.0, HebrewWer.wer("שלום, עולם!", "שלום עולם"), eps)
    }

    @Test
    fun gereshAndGershayimFoldToAsciiQuotes() {
        // Hebrew geresh/gershayim must match their ASCII look-alikes.
        assertEquals(0.0, HebrewWer.wer("צה״ל ז׳קט", "צה\"ל ז'קט"), eps)
    }

    @Test
    fun mixedHebrewEnglishIsCaseInsensitive() {
        assertEquals(0.0, HebrewWer.wer("שלום World", "שלום world"), eps)
    }

    @Test
    fun insertionCountsAsOneEdit() {
        // Reference has 2 words; hypothesis adds a third -> 1 insertion / 2 = 0.5.
        assertEquals(0.5, HebrewWer.wer("אחת שתיים", "אחת שתיים שלוש"), eps)
    }

    @Test
    fun completelyWrongIsOne() {
        assertEquals(1.0, HebrewWer.wer("כן", "לא"), eps)
    }

    @Test
    fun normalizeCollapsesAndTrims() {
        assertEquals("שלום עולם", HebrewWer.normalize("  שלום,\t\t  עולם!  "))
    }
}
