package dev.chimeraant.berryforge.data.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the line diff used by the commit sheet and by session revert. */
class DiffEngineTest {

    @Test
    fun identicalTextProducesNoChanges() {
        val text = "a\nb\nc"
        val result = DiffEngine.compute(text, text)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertTrue(result.hunks.isEmpty())
    }

    @Test
    fun addedLineIsCounted() {
        val result = DiffEngine.compute("a\nb", "a\nb\nc")
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun removedLineIsCounted() {
        val result = DiffEngine.compute("a\nb\nc", "a\nc")
        assertEquals(0, result.added)
        assertEquals(1, result.removed)
    }

    @Test
    fun changedLineCountsAsOneOfEach() {
        val result = DiffEngine.compute("a\nb\nc", "a\nB\nc")
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
    }

    @Test
    fun addingToAnEmptyFileCountsEveryLine() {
        val result = DiffEngine.compute("", "x\ny")
        assertEquals(2, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun deletingEverythingCountsEveryLine() {
        val result = DiffEngine.compute("x\ny", "")
        assertEquals(0, result.added)
        assertEquals(2, result.removed)
    }

    @Test
    fun hunkLinesCarryCorrectPrefixes() {
        val result = DiffEngine.compute("a\nb\nc", "a\nB\nc")
        val kinds = result.hunks.flatMap { it.lines }.map { it.kind }
        assertTrue(kinds.contains(DiffEngine.Line.Kind.Remove))
        assertTrue(kinds.contains(DiffEngine.Line.Kind.Add))
        assertTrue(kinds.contains(DiffEngine.Line.Kind.Context))
    }

    @Test
    fun hunkLineNumbersAreOneBased() {
        val result = DiffEngine.compute("a\nb\nc", "a\nB\nc")
        val removed = result.hunks.flatMap { it.lines }
            .first { it.kind == DiffEngine.Line.Kind.Remove }
        assertEquals(2, removed.oldNo)
        val added = result.hunks.flatMap { it.lines }
            .first { it.kind == DiffEngine.Line.Kind.Add }
        assertEquals(2, added.newNo)
    }

    @Test
    fun distantChangesProduceSeparateHunks() {
        // Build the lines explicitly: a naive string replace of "line2" would also
        // match "line20".."line29" and change far more than intended.
        val before = (1..40).joinToString("\n") { "line$it" }
        val after = (1..40).joinToString("\n") { n ->
            when (n) {
                2 -> "CHANGED2"
                38 -> "CHANGED38"
                else -> "line$n"
            }
        }
        val result = DiffEngine.compute(before, after)
        assertEquals(2, result.added)
        assertEquals(2, result.removed)
        assertTrue("expected two hunks, got ${result.hunks.size}", result.hunks.size >= 2)
    }

    @Test
    fun veryLargeInputsDoNotExplode() {
        // The engine falls back to a prefix-only diff above a size threshold rather than
        // allocating an enormous LCS table on a phone.
        val big = (1..3000).joinToString("\n") { "l$it" }
        val result = DiffEngine.compute(big, big + "\nextra")
        assertTrue(result.added >= 1)
    }
}
