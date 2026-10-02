package com.johnb.englishspelling

import kotlin.math.abs

/**
 * Generates plausible wrong spellings of an English word, for the multiple-choice
 * game. Uses the mistakes children actually make: vowel teams (ie/ei, ee/ea…),
 * silent letters, doubled consonants, confusable endings, c/k/ck, ph/f. Each kind
 * of mistake is its own group and the choices are drawn from different groups, so
 * one word doesn't get three doubled-letter variants. Plain vowel swaps and
 * transposed letters only fill in when a word has too few real-mistake variants.
 *
 * Mirrors `distractors()` in the web app (johnboniello.github.io/src/spelling/app.js).
 */
object SpellingVariants {

    private val SWAPS = listOf(
        "ie" to "ei", "ei" to "ie", "ee" to "ea", "ea" to "ee", "ai" to "ay", "ay" to "ai", "ai" to "a",
        "oa" to "o", "ow" to "ou", "ou" to "ow", "oi" to "oy", "oy" to "oi", "au" to "aw", "aw" to "au",
        "oo" to "u", "ew" to "oo", "ue" to "oo", "ph" to "f", "wh" to "w", "kn" to "n", "wr" to "r",
        "mb" to "m", "gh" to "", "tch" to "ch", "dge" to "ge", "ck" to "k", "ck" to "c",
        "ce" to "se", "se" to "ce", "ci" to "si", "cy" to "sy", "qu" to "kw", "x" to "ks"
    )

    private val ENDINGS = listOf(
        "tion" to listOf("shun", "sion", "cion"), "sion" to listOf("tion", "shun"), "ture" to listOf("cher", "chure"),
        "ough" to listOf("uff", "ow", "o"), "ight" to listOf("ite", "it"), "ence" to listOf("ance", "ense"), "ance" to listOf("ence"),
        "ible" to listOf("able"), "able" to listOf("ible"), "ous" to listOf("us", "ious"), "ful" to listOf("full"),
        "ies" to listOf("ys", "eys"), "ly" to listOf("ley", "lee"), "ey" to listOf("y", "ie"), "y" to listOf("ey", "ie", "ee"),
        "le" to listOf("el", "al", "ul"), "el" to listOf("le", "il"), "al" to listOf("le", "el"),
        "er" to listOf("ur", "or", "ar"), "or" to listOf("er"), "ar" to listOf("er"), "ed" to listOf("d", "t"),
        "tch" to listOf("ch"), "dge" to listOf("ge", "j"), "ge" to listOf("dge", "j"), "ch" to listOf("tch"), "es" to listOf("s")
    )

    private const val DOUBLABLE = "bdfglmnprstz"
    private val VOWEL_ALTS = mapOf('a' to "e", 'e' to "i", 'i' to "e", 'o' to "u", 'u' to "o")

    private fun isVowel(c: Char?) = c != null && c in "aeiou"

    fun distractors(word: String, count: Int = 3): List<String> {
        val w = word.trim()
        if (w.length < 2) return fallback(w, count)
        val lower = w.lowercase()

        /** Replaces w[i, i+len) with [rep], keeping a capital first letter capital. */
        fun at(i: Int, len: Int, rep: String): String {
            val r = if (rep.isNotEmpty() && w[i].isUpperCase()) rep.replaceFirstChar { it.uppercaseChar() } else rep
            return w.substring(0, i) + r + w.substring(i + len)
        }

        val team = LinkedHashSet<String>()
        val ending = LinkedHashSet<String>()
        val silent = LinkedHashSet<String>()
        val double = LinkedHashSet<String>()
        val sound = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()

        for ((from, to) in SWAPS) {
            var i = lower.indexOf(from)
            while (i >= 0) {
                team.add(at(i, from.length, to))
                i = lower.indexOf(from, i + 1)
            }
        }

        // Only the longest matching ending ("tion", not also "on").
        ENDINGS.firstOrNull { (suf, _) -> lower.length > suf.length + 1 && lower.endsWith(suf) }?.let { (suf, alts) ->
            for (a in alts) ending.add(at(w.length - suf.length, suf.length, a))
        }

        // Silent e: drop it ("hope" -> "hop"), or add one that isn't there ("bus" -> "buse").
        val last = lower[lower.length - 1]
        val prev = lower[lower.length - 2]
        if (w.length > 3 && last == 'e' && !isVowel(prev)) silent.add(w.dropLast(1))
        if (w.length > 2 && !isVowel(last) && last !in "ywxs" && isVowel(prev)) silent.add(w + "e")

        // Doubled consonants: undouble ("little" -> "litle") or double ("until" -> "untill"),
        // the latter only after a vowel and before a vowel or at the end.
        for (i in w.indices) {
            val c = lower[i]
            if (i + 1 < w.length && c == lower[i + 1] && c in DOUBLABLE) {
                double.add(w.substring(0, i) + w.substring(i + 1))
            } else if (c in DOUBLABLE && i > 0 && isVowel(lower[i - 1]) &&
                (i + 1 == w.length || isVowel(lower[i + 1]))
            ) {
                double.add(w.substring(0, i + 1) + w[i] + w.substring(i + 1))
            }
        }

        // Same sound, other letter: c/k, s/z — skipping digraphs like ch, ck, sh.
        for (i in w.indices) {
            val c = lower[i]
            val n = lower.getOrNull(i + 1)
            val p = lower.getOrNull(i - 1)
            if (c == 'c' && n != 'h' && n != 'k' && (n == null || n !in "eiy")) sound.add(at(i, 1, "k"))
            if (c == 'k' && p != 'c' && !(i == 0 && n == 'n') && (n == null || n !in "eiy")) sound.add(at(i, 1, "c"))
            if (c == 's' && n != 'h' && i > 0 && isVowel(p)) sound.add(at(i, 1, "z"))
        }

        // Weak fallbacks: a vowel swapped for a neighbour, two letters transposed.
        for (i in w.indices) VOWEL_ALTS[lower[i]]?.let { weak.add(at(i, 1, it)) }
        for (i in 0 until w.length - 1) {
            if (lower[i] != lower[i + 1] && w[i] != ' ' && w[i + 1] != ' ') {
                val a = w.toCharArray()
                val t = a[i]; a[i] = a[i + 1]; a[i + 1] = t
                weak.add(String(a))
            }
        }

        val result = mutableListOf<String>()
        val taken = mutableSetOf(lower)
        fun take(s: String): Boolean {
            val ok = s.length >= 2 && abs(s.length - w.length) <= 3 && s.lowercase() !in taken
            if (ok) { taken.add(s.lowercase()); result.add(s) }
            return ok
        }

        // Round-robin over the mistake groups in random order, a random variant from each.
        val pools = listOf(team, ending, silent, double, sound)
            .map { it.shuffled().toMutableList() }
            .filter { it.isNotEmpty() }
            .shuffled()
        while (result.size < count && pools.any { it.isNotEmpty() }) {
            for (g in pools) {
                while (g.isNotEmpty() && !take(g.removeAt(g.size - 1))) { /* skip unusable */ }
                if (result.size >= count) break
            }
        }
        for (s in weak.shuffled()) {
            if (result.size >= count) break
            take(s)
        }
        while (result.size < count) result.add(w + "s".repeat(result.size + 1))
        return result.take(count)
    }

    private fun fallback(w: String, count: Int): List<String> =
        (1..count).map { w + "x".repeat(it) }
}
