package org.iotsplab.akiba.llm.tool.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff is what the conversation renders, so its numbers have to be the
 * file's numbers — not an index into the hunk.
 */
class FileDiffTest {

    private fun lines(vararg text: String): String = text.joinToString("\n")

    @Test
    fun `a change deep in the file keeps its own line numbers`() {
        val before = lines(
            "line 1", "line 2", "line 3", "line 4", "line 5",
            "line 6", "old line 7", "line 8", "line 9", "line 10",
        )
        val after = before.replace("old line 7", "new line 7")

        val diff = FileDiff.compute(before, after)

        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        val removed = diff.rows.single { it.kind == "-" }
        val added = diff.rows.single { it.kind == "+" }
        assertEquals(7, removed.oldLine)
        assertEquals(null, removed.newLine)
        assertEquals(null, added.oldLine)
        assertEquals(7, added.newLine)
    }

    @Test
    fun `context around a change carries both numbers`() {
        val before = (1..20).joinToString("\n") { "line $it" }
        val after = before.replace("line 10", "line ten")

        val diff = FileDiff.compute(before, after)

        val context = diff.rows.filter { it.kind == " " }
        assertEquals(6, context.size)
        assertTrue(context.all { it.oldLine != null && it.newLine != null })
        // Three lines either side of line 10 means the first context row is line 7.
        assertEquals(7, context.first().oldLine)
        assertEquals(13, context.last().oldLine)
    }

    @Test
    fun `a new file numbers every line from one`() {
        val diff = FileDiff.compute(null, lines("a", "b", "c"))

        assertEquals(3, diff.added)
        assertEquals(0, diff.removed)
        assertEquals(listOf(1, 2, 3), diff.rows.map { it.newLine })
        assertTrue(diff.rows.all { it.kind == "+" })
    }

    @Test
    fun `an insertion reports only the added line`() {
        val before = lines("a", "b", "d")
        val after = lines("a", "b", "c", "d")

        val diff = FileDiff.compute(before, after)

        assertEquals(1, diff.added)
        assertEquals(0, diff.removed)
        assertEquals(3, diff.rows.single { it.kind == "+" }.newLine)
    }
}
