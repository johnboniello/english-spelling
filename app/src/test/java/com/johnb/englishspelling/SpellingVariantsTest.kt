package com.johnb.englishspelling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingVariantsTest {

    private val words = listOf(
        "because", "friend", "said", "would", "people", "school", "Wednesday", "beautiful",
        "believe", "receive", "little", "until", "catch", "bridge", "night", "station",
        "happy", "cat", "a", "ice cream", "knee", "bus", "hope", "their"
    )

    @Test
    fun threeDistinctWrongChoicesForEveryWord() {
        repeat(50) {
            for (w in words) {
                val d = SpellingVariants.distractors(w)
                assertEquals(w, 3, d.size)
                assertEquals("duplicates for $w: $d", 3, d.map { it.lowercase() }.toSet().size)
                assertFalse("answer offered as a wrong choice: $w", d.any { it.equals(w, ignoreCase = true) })
            }
        }
    }

    @Test
    fun capitalisedWordsStayCapitalised() {
        repeat(50) {
            for (v in SpellingVariants.distractors("Wednesday")) assertTrue(v, v[0].isUpperCase())
        }
    }

    @Test
    fun noImpossibleDoubles() {
        // "friend" must never become "friennd": doubling only happens after a vowel
        // and before a vowel or at the end of the word.
        repeat(200) {
            assertFalse(SpellingVariants.distractors("friend").contains("friennd"))
            assertFalse(SpellingVariants.distractors("catch").contains("cattch"))
        }
    }

    @Test
    fun printSamples() {
        for (w in words) println(w.padEnd(10) + SpellingVariants.distractors(w).joinToString("  "))
    }
}
