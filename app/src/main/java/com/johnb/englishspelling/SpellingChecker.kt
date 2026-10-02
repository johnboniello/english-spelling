package com.johnb.englishspelling

/**
 * Compares the child's spelling to the target word and produces a per-letter
 * alignment (Levenshtein with backtrace) for a colour-coded display.
 *
 * Comparison is case-insensitive but accent-sensitive: "cafe" vs "café" is
 * a mistake on purpose.
 */
object SpellingChecker {

    enum class Kind { MATCH, SUB, MISSING, EXTRA }

    data class Op(val target: Char?, val guess: Char?, val kind: Kind)

    data class Result(
        val correct: Boolean,
        val ops: List<Op>,
        val correctCount: Int,
        val total: Int
    )

    private fun norm(c: Char): Char = Character.toLowerCase(c)

    fun check(targetRaw: String, guessRaw: String): Result {
        val t = targetRaw.trim()
        val g = guessRaw.trim()
        val n = t.length
        val m = g.length

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (norm(t[i - 1]) == norm(g[j - 1])) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        var i = n
        var j = m
        val ops = ArrayList<Op>()
        while (i > 0 || j > 0) {
            val diagCost = if (i > 0 && j > 0 && norm(t[i - 1]) == norm(g[j - 1])) 0 else 1
            when {
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + diagCost -> {
                    ops.add(Op(t[i - 1], g[j - 1], if (diagCost == 0) Kind.MATCH else Kind.SUB))
                    i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    ops.add(Op(t[i - 1], null, Kind.MISSING))
                    i--
                }
                else -> {
                    ops.add(Op(null, g[j - 1], Kind.EXTRA))
                    j--
                }
            }
        }
        ops.reverse()

        val correctCount = ops.count { it.kind == Kind.MATCH }
        val correct = n == m && ops.all { it.kind == Kind.MATCH }
        return Result(correct, ops, correctCount, n)
    }
}
