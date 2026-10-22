package dev.chimeraant.berryforge.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the editor's Format action.
 *
 * The action previously showed a "Formatting" toast and changed nothing. It now runs
 * [CodeFormatter], so its behaviour is pinned here: a formatter that silently corrupts
 * code is worse than no formatter, and every rule below has to hold in every language.
 */
class CodeFormatterTest {

    @Test
    fun `trailing whitespace is removed`() {
        assertEquals("val x = 1\n", CodeFormatter.tidy("val x = 1   \n"))
    }

    @Test
    fun `whitespace-only lines become empty`() {
        assertEquals("a\n\nb\n", CodeFormatter.tidy("a\n   \nb\n"))
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        assertEquals("a\n\nb\n", CodeFormatter.tidy("a\n\n\n\n\nb\n"))
    }

    @Test
    fun `leading tabs expand to the tab width`() {
        assertEquals("    val x = 1\n", CodeFormatter.tidy("\tval x = 1\n", tabWidth = 4))
        assertEquals("  val x = 1\n", CodeFormatter.tidy("\tval x = 1\n", tabWidth = 2))
    }

    @Test
    fun `a tab inside a line is left alone`() {
        // Changing a tab that is not indentation could alter a string literal.
        assertEquals("val s = \"a\tb\"\n", CodeFormatter.tidy("val s = \"a\tb\"\n"))
    }

    @Test
    fun `windows line endings are normalised`() {
        assertEquals("a\nb\n", CodeFormatter.tidy("a\r\nb\r\n"))
    }

    @Test
    fun `old mac line endings are normalised`() {
        assertEquals("a\nb\n", CodeFormatter.tidy("a\rb\r"))
    }

    @Test
    fun `exactly one trailing newline is left`() {
        assertEquals("a\n", CodeFormatter.tidy("a"))
        assertEquals("a\n", CodeFormatter.tidy("a\n"))
        assertEquals("a\n", CodeFormatter.tidy("a\n\n\n"))
    }

    @Test
    fun `an empty file stays empty`() {
        assertEquals("", CodeFormatter.tidy(""))
        assertEquals("", CodeFormatter.tidy("\n\n\n"))
        assertEquals("", CodeFormatter.tidy("   \n  \n"))
    }

    @Test
    fun `interior blank lines are preserved`() {
        assertEquals("fun a() {}\n\nfun b() {}\n", CodeFormatter.tidy("fun a() {}\n\nfun b() {}\n"))
    }

    @Test
    fun `isTidy is true for already clean text`() {
        assertTrue(CodeFormatter.isTidy("val x = 1\n", 4))
    }

    @Test
    fun `isTidy is false when there is work to do`() {
        assertFalse(CodeFormatter.isTidy("val x = 1   \n", 4))
        assertFalse(CodeFormatter.isTidy("val x = 1", 4))
    }

    @Test
    fun `tidy is idempotent`() {
        val messy = "fun a() {   \n\t\tval x = 1\n\n\n\nfun b() {}\t\n"
        val once = CodeFormatter.tidy(messy, 4)
        assertEquals("a second pass must change nothing", once, CodeFormatter.tidy(once, 4))
    }

    @Test
    fun `an out-of-range tab width is clamped rather than trusted`() {
        // A zero or negative width would otherwise delete the indentation entirely.
        assertTrue(CodeFormatter.tidy("\tval x = 1\n", tabWidth = 0).startsWith(" "))
        assertTrue(CodeFormatter.tidy("\tval x = 1\n", tabWidth = -5).startsWith(" "))
        assertTrue(CodeFormatter.tidy("\tval x = 1\n", tabWidth = 999).startsWith(" "))
    }

    @Test
    fun `blank lines at the end are removed`() {
        assertEquals("a\n", CodeFormatter.tidy("a\n\n   \n\n"))
    }
}
