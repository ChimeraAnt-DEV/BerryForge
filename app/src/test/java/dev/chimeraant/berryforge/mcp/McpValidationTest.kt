package dev.chimeraant.berryforge.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the argument validation every MCP tool goes through.
 *
 * These cover a shipped vulnerability: `repo` was never validated, so a value like
 * `../../..` resolved outside the workspace and into other app-private directories.
 */
class McpValidationTest {

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("$label was accepted but should have been rejected")
        } catch (expected: McpToolException) {
            // expected
        }
    }

    // ---- repo shape ----

    @Test
    fun traversalRepoIsRejected() {
        assertRejected("repo = ../..") { McpValidation.splitRepo("../..") }
        assertRejected("repo = a/b/../..") { McpValidation.splitRepo("a/b/../..") }
        assertRejected("repo = owner/..") { McpValidation.splitRepo("owner/..") }
    }

    @Test
    fun absoluteRepoIsRejected() {
        // Regression: the first version trimmed the leading slash, turning "/etc/passwd"
        // into a plausible-looking "etc/passwd".
        assertRejected("repo = /etc/passwd") { McpValidation.splitRepo("/etc/passwd") }
    }

    @Test
    fun backslashRepoIsRejected() {
        assertRejected("repo with backslash") { McpValidation.splitRepo("owner\\repo") }
    }

    @Test
    fun malformedRepoIsRejected() {
        assertRejected("repo = owner") { McpValidation.splitRepo("owner") }
        assertRejected("repo = a/b/c") { McpValidation.splitRepo("a/b/c") }
        assertRejected("repo = empty") { McpValidation.splitRepo("") }
    }

    @Test
    fun legalRepoFormsAreAccepted() {
        assertEquals("ChimeraAnt-DEV" to "BerryForge", McpValidation.splitRepo("ChimeraAnt-DEV/BerryForge"))
        assertEquals(
            "ChimeraAnt-DEV" to "BerryForge",
            McpValidation.splitRepo("https://github.com/ChimeraAnt-DEV/BerryForge.git"),
        )
        assertEquals("a" to "b", McpValidation.splitRepo(" a/b "))
    }

    // ---- path shape ----

    @Test
    fun traversalPathIsRejected() {
        assertRejected("../../../databases/x") { McpValidation.validatePath("../../../databases/x") }
        assertRejected("a/../../b") { McpValidation.validatePath("a/../../b") }
        assertRejected("..") { McpValidation.validatePath("..") }
    }

    @Test
    fun absolutePathIsRejected() {
        assertRejected("/etc/passwd") { McpValidation.validatePath("/etc/passwd") }
        assertRejected("\\\\server\\share") { McpValidation.validatePath("\\\\server\\share") }
    }

    @Test
    fun driveLetterPathIsRejected() {
        assertRejected("C:\\\\x") { McpValidation.validatePath("C:\\x") }
    }

    @Test
    fun controlCharactersAreRejected() {
        assertRejected("NUL byte") { McpValidation.validatePath("a\u0000b") }
        assertRejected("newline") { McpValidation.validatePath("a\nb") }
    }

    @Test
    fun blankPathIsRejected() {
        assertRejected("blank") { McpValidation.validatePath("   ") }
    }

    @Test
    fun normalPathsAreAcceptedAndNormalised() {
        assertEquals("app/src/Main.kt", McpValidation.validatePath("app/src/Main.kt"))
        assertEquals("app/Main.kt", McpValidation.validatePath("./app/Main.kt"))
        assertEquals("a/b", McpValidation.validatePath("a//b"))
    }

    @Test
    fun overlongPathIsRejected() {
        assertRejected("very long path") { McpValidation.validatePath("a/".repeat(2000) + "x") }
    }

    // ---- containment ----

    @Test
    fun containmentRejectsPathsOutsideTheRoot() {
        val root = java.io.File("/tmp/berryforge-test/owner/repo").apply { mkdirs() }
        try {
            McpValidation.requireInside(root, java.io.File(root, "app/Main.kt"))
        } catch (unexpected: McpToolException) {
            fail("a path inside the root should be accepted")
        }
        assertRejected("sibling repo") {
            McpValidation.requireInside(root, java.io.File("/tmp/berryforge-test/owner/other/x"))
        }
        assertRejected("parent traversal") {
            McpValidation.requireInside(root, java.io.File(root, "../../databases/x"))
        }
    }
}
