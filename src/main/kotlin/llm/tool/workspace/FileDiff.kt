package org.iotsplab.akiba.llm.tool.workspace

/**
 * One row of a line diff.
 *
 * @property kind `"+"`, `"-"` or `" "` (unchanged context).
 * @property oldLine position in the file before the write, or null for an added line.
 * @property newLine position in the file after the write, or null for a removed line.
 */
data class DiffLine(
    val kind: String,
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
)

/** A diff plus the counts a collapsed view needs. */
data class DiffResult(
    val rows: List<DiffLine>,
    val added: Int,
    val removed: Int,
    val truncated: Boolean,
)

/**
 * Line diff with file line numbers.
 *
 * The numbers matter more than the shape: an edit deep in a file has to point at
 * the line the reader can go to, so this never renumbers from the top of a hunk.
 * The diff travels with the tool result, which is what the transcript stores, so
 * reopening a conversation renders the same numbers months later.
 */
object FileDiff {

    /** Unchanged lines kept on each side of a change. */
    const val CONTEXT = 3

    /** Rows are for a reader; a hunk longer than this is cut rather than trusted. */
    const val MAX_ROWS = 400

    /** Above this many cell comparisons the middle is reported as replaced. */
    private const val MAX_CELLS = 4_000_000

    fun compute(oldText: String?, newText: String, context: Int = CONTEXT): DiffResult {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)

        var head = 0
        while (head < oldLines.size && head < newLines.size && oldLines[head] == newLines[head]) head++

        var tail = 0
        while (
            tail < oldLines.size - head && tail < newLines.size - head &&
            oldLines[oldLines.size - 1 - tail] == newLines[newLines.size - 1 - tail]
        ) tail++

        val rows = mutableListOf<DiffLine>()
        for (i in 0 until head) rows += DiffLine(" ", oldLines[i], i + 1, i + 1)

        val a = oldLines.subList(head, oldLines.size - tail)
        val b = newLines.subList(head, newLines.size - tail)
        rows += diffMiddle(a, b, head)

        for (k in 0 until tail) {
            val oldIdx = oldLines.size - tail + k
            val newIdx = newLines.size - tail + k
            rows += DiffLine(" ", oldLines[oldIdx], oldIdx + 1, newIdx + 1)
        }

        val added = rows.count { it.kind == "+" }
        val removed = rows.count { it.kind == "-" }
        val withContext = keepContext(rows, context)
        val truncated = withContext.size > MAX_ROWS
        return DiffResult(
            rows = if (truncated) withContext.take(MAX_ROWS) else withContext,
            added = added,
            removed = removed,
            truncated = truncated,
        )
    }

    /** The changed middle: longest common subsequence, bounded by [MAX_CELLS]. */
    private fun diffMiddle(a: List<String>, b: List<String>, offset: Int): List<DiffLine> {
        val rows = mutableListOf<DiffLine>()
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return rows

        if (n == 0 || m == 0 || n.toLong() * m.toLong() > MAX_CELLS) {
            for (i in 0 until n) rows += DiffLine("-", a[i], offset + i + 1, null)
            for (j in 0 until m) rows += DiffLine("+", b[j], null, offset + j + 1)
            return rows
        }

        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }

        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    rows += DiffLine(" ", a[i], offset + i + 1, offset + j + 1)
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    rows += DiffLine("-", a[i], offset + i + 1, null)
                    i++
                }
                else -> {
                    rows += DiffLine("+", b[j], null, offset + j + 1)
                    j++
                }
            }
        }
        while (i < n) {
            rows += DiffLine("-", a[i], offset + i + 1, null)
            i++
        }
        while (j < m) {
            rows += DiffLine("+", b[j], null, offset + j + 1)
            j++
        }
        return rows
    }

    /** Drop unchanged lines that are further than [context] from any change. */
    private fun keepContext(rows: List<DiffLine>, context: Int): List<DiffLine> {
        val keep = BooleanArray(rows.size)
        var any = false
        rows.forEachIndexed { i, row ->
            if (row.kind == " ") return@forEachIndexed
            any = true
            for (k in maxOf(0, i - context)..minOf(rows.size - 1, i + context)) keep[k] = true
        }
        if (!any) return rows.take(minOf(rows.size, context * 2))
        return rows.filterIndexed { i, _ -> keep[i] }
    }

    /** A trailing newline ends a line; it does not start an empty one. */
    private fun splitLines(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val lines = text.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }
}
