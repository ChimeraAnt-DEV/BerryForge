package dev.chimeraant.berryforge.data.diff

/**
 * Minimal line diff producing added/removed counts plus unified hunks.
 * Deliberately small: the editor needs counts for the gutter and hunks for the sheet,
 * not a full Myers implementation.
 */
object DiffEngine {

    data class Result(val added: Int, val removed: Int, val hunks: List<Hunk>)

    data class Hunk(val oldStart: Int, val newStart: Int, val lines: List<Line>)

    data class Line(val kind: Kind, val text: String, val oldNo: Int?, val newNo: Int?) {
        enum class Kind { Context, Add, Remove }
    }

    fun compute(before: String, after: String): Result {
        val a = before.lines()
        val b = after.lines()
        val lcs = longestCommonSubsequence(a, b)

        val lines = mutableListOf<Line>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        lcs.forEach { (ai, bi) ->
            while (i < ai) {
                lines += Line(Line.Kind.Remove, a[i], i + 1, null)
                removed++
                i++
            }
            while (j < bi) {
                lines += Line(Line.Kind.Add, b[j], null, j + 1)
                added++
                j++
            }
            lines += Line(Line.Kind.Context, a[ai], ai + 1, bi + 1)
            i = ai + 1
            j = bi + 1
        }
        while (i < a.size) {
            lines += Line(Line.Kind.Remove, a[i], i + 1, null)
            removed++
            i++
        }
        while (j < b.size) {
            lines += Line(Line.Kind.Add, b[j], null, j + 1)
            added++
            j++
        }

        return Result(added, removed, groupIntoHunks(lines, context = 3))
    }

    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        // Guard against pathological inputs on a phone: fall back to a naive
        // whole-file replacement rather than allocating an enormous table.
        if (n.toLong() * m.toLong() > 4_000_000L) {
            val pairs = mutableListOf<Pair<Int, Int>>()
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (a[i] == b[j]) {
                    pairs += i to j
                    i++
                    j++
                } else {
                    break
                }
            }
            return pairs
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) {
            for (y in m - 1 downTo 0) {
                dp[x][y] = if (a[x] == b[y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
            }
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                a[x] == b[y] -> {
                    result += x to y
                    x++
                    y++
                }
                dp[x + 1][y] >= dp[x][y + 1] -> x++
                else -> y++
            }
        }
        return result
    }

    private fun groupIntoHunks(lines: List<Line>, context: Int): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].kind == Line.Kind.Context) {
                index++
                continue
            }
            var start = (index - context).coerceAtLeast(0)
            var end = index
            while (end < lines.size) {
                if (lines[end].kind != Line.Kind.Context) {
                    end++
                    continue
                }
                // Look ahead: if another change is within 2*context lines, keep going.
                var lookahead = end
                var gap = 0
                while (lookahead < lines.size && lines[lookahead].kind == Line.Kind.Context) {
                    lookahead++
                    gap++
                }
                if (gap <= context * 2 && lookahead < lines.size) {
                    end = lookahead
                } else {
                    break
                }
            }
            val sliceEnd = (end + context).coerceAtMost(lines.size)
            val slice = lines.subList(start, sliceEnd)
            val oldStart = slice.firstOrNull { it.oldNo != null }?.oldNo ?: 1
            val newStart = slice.firstOrNull { it.newNo != null }?.newNo ?: 1
            hunks += Hunk(oldStart = oldStart, newStart = newStart, lines = slice.toList())
            index = sliceEnd
        }
        return hunks
    }
}
