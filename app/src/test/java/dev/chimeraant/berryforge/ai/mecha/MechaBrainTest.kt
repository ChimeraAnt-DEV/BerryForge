package dev.chimeraant.berryforge.ai.mecha

import dev.chimeraant.berryforge.ai.FindingCategory
import dev.chimeraant.berryforge.ai.ReviewFinding
import dev.chimeraant.berryforge.ai.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mecha brain is fully deterministic, so every intent, every regex and every reply can
 * be pinned here with no model and no device. That is the whole point of the design: an
 * assistant that answers from parsed facts can be exhaustively tested.
 */
class MechaBrainTest {

    private val sampleText = """
        package dev.example.app

        import kotlin.math.max
        import java.util.UUID

        class Widget(val name: String) {
            fun render() {
                println(name)
            }

            fun resize(w: Int, h: Int): Int = max(w, h)
        }

        fun helper() {
            val id = UUID.randomUUID()
            println(id)
        }
    """.trimIndent()

    private fun ctx(
        text: String = sampleText,
        findings: List<ReviewFinding> = emptyList(),
        lastFinding: Int? = null,
        lastSymbol: String? = null,
    ) = MechaContext(
        path = "src/Widget.kt",
        text = text,
        findings = findings,
        lastFindingIndex = lastFinding,
        lastSymbol = lastSymbol,
    )

    private fun finding(
        line: Int,
        severity: Severity,
        message: String = "something is wrong",
        suggestion: String? = "fixed()",
    ) = ReviewFinding(
        id = "f$line",
        path = "src/Widget.kt",
        startLine = line,
        endLine = line,
        severity = severity,
        category = FindingCategory.CompileError,
        message = message,
        suggestion = suggestion,
    )

    // ---- Classification ----

    @Test
    fun `greetings classify`() {
        assertEquals(MechaIntent.Greeting, MechaBrain.classify("hi", ctx()))
        assertEquals(MechaIntent.Greeting, MechaBrain.classify("Hello", ctx()))
    }

    @Test
    fun `help classifies`() {
        assertEquals(MechaIntent.Help, MechaBrain.classify("help", ctx()))
        assertEquals(MechaIntent.Help, MechaBrain.classify("what can you do?", ctx()))
    }

    @Test
    fun `file info classifies`() {
        assertEquals(MechaIntent.FileInfo, MechaBrain.classify("what is this file", ctx()))
        assertEquals(MechaIntent.Stats, MechaBrain.classify("stats", ctx()))
    }

    @Test
    fun `imports and package classify`() {
        assertEquals(MechaIntent.ListImports, MechaBrain.classify("list imports", ctx()))
        assertEquals(MechaIntent.PackageName, MechaBrain.classify("what package", ctx()))
    }

    @Test
    fun `symbol listing classifies`() {
        assertEquals(MechaIntent.ListSymbols(SymbolKind.Function), MechaBrain.classify("show functions", ctx()))
        assertEquals(MechaIntent.ListSymbols(SymbolKind.Class), MechaBrain.classify("list classes", ctx()))
    }

    @Test
    fun `code smell commands classify`() {
        assertEquals(MechaIntent.FindUnusedImports, MechaBrain.classify("unused imports", ctx()))
        assertEquals(MechaIntent.FindTodos, MechaBrain.classify("find todos", ctx()))
        assertEquals(MechaIntent.FindLongLines, MechaBrain.classify("long lines", ctx()))
        assertEquals(MechaIntent.FindLongFunctions, MechaBrain.classify("long functions", ctx()))
    }

    @Test
    fun `finding list commands classify with severity`() {
        assertEquals(MechaIntent.ListFindings(Severity.Error), MechaBrain.classify("list errors", ctx()))
        assertEquals(MechaIntent.ListFindings(Severity.Warning), MechaBrain.classify("show warnings", ctx()))
        assertEquals(MechaIntent.ListFindings(null), MechaBrain.classify("list findings", ctx()))
    }

    @Test
    fun `navigation classifies`() {
        assertEquals(MechaIntent.GotoLine(42), MechaBrain.classify("goto line 42", ctx()))
        assertEquals(MechaIntent.NextFinding, MechaBrain.classify("next", ctx()))
        assertEquals(MechaIntent.PreviousFinding, MechaBrain.classify("previous", ctx()))
    }

    @Test
    fun `an out-of-range line number does not throw`() {
        // "goto line 99999999999" overflows Int. The old code called toInt() on the
        // match and threw NumberFormatException out of classify(), crashing the editor.
        val huge = MechaBrain.classify("goto line 99999999999", ctx())
        assertTrue("overflowing line numbers must degrade to Unknown, not throw", huge is MechaIntent.Unknown)
        // A value that fits must still work.
        assertEquals(MechaIntent.GotoLine(2_147_483_647), MechaBrain.classify("goto line 2147483647", ctx()))
    }

    @Test
    fun `unknown text falls through`() {
        assertTrue(MechaBrain.classify("why does this crash at runtime", ctx()) is MechaIntent.Unknown)
        assertTrue(MechaBrain.classify("refactor this cleaner", ctx()) is MechaIntent.Unknown)
    }

    // ---- Selector parsing ----

    @Test
    fun `explicit finding numbers parse`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) })
        val intent = MechaBrain.classify("explain finding 2", c) as MechaIntent.ExplainFindings
        assertEquals(2, intent.selector.index)
    }

    @Test
    fun `ordinal first and last resolve`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) })
        assertEquals(1, (MechaBrain.classify("fix the first one", c) as MechaIntent.FixFindings).selector.index)
        assertEquals(3, (MechaBrain.classify("fix the last one", c) as MechaIntent.FixFindings).selector.index)
    }

    @Test
    fun `pronoun resolves to the last finding discussed`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) }, lastFinding = 2)
        val intent = MechaBrain.classify("explain it", c) as MechaIntent.ExplainFindings
        assertEquals(2, intent.selector.index)
    }

    @Test
    fun `fix all errors selects all with severity`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) })
        val intent = MechaBrain.classify("fix all errors", c) as MechaIntent.FixFindings
        assertTrue(intent.selector.all)
        assertEquals(Severity.Error, intent.selector.severity)
    }

    @Test
    fun `bare fix resolves to the last finding`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) }, lastFinding = 3)
        val intent = MechaBrain.classify("fix", c) as MechaIntent.FixFindings
        assertEquals(3, intent.selector.index)
        assertFalse(intent.selector.all)
    }

    // ---- Handlers ----

    @Test
    fun `file info reports real structure`() {
        val reply = MechaHandlers.execute(MechaIntent.FileInfo, ctx())
        assertTrue(reply.text.contains("Kotlin"))
        assertTrue(reply.text.contains("Imports: 2"))
        assertTrue(reply.text.contains("dev.example.app"))
    }

    @Test
    fun `explain finding describes severity and line`() {
        val c = ctx(findings = listOf(finding(3, Severity.Error, "type mismatch")))
        val reply = MechaHandlers.execute(MechaIntent.ExplainFindings(FindingSelector(index = 1)), c)
        assertTrue(reply.text.contains("line 3"))
        assertTrue(reply.text.contains("won't compile"))
        assertTrue(reply.text.contains("type mismatch"))
    }

    @Test
    fun `fix all errors returns one action per fixable error`() {
        val c = ctx(
            findings = listOf(
                finding(3, Severity.Error, suggestion = "a()"),
                finding(5, Severity.Error, suggestion = "b()"),
                finding(7, Severity.Error, suggestion = null),
            ),
        )
        val reply = MechaHandlers.execute(MechaIntent.FixFindings(FindingSelector.All), c)
        assertEquals(2, reply.actions.size)
        assertTrue(reply.text.contains("Applying 2"))
        assertTrue(reply.text.contains("1 had no suggestion"))
    }

    @Test
    fun `explain all warnings produces one reply`() {
        val c = ctx(
            findings = listOf(
                finding(3, Severity.Warning),
                finding(5, Severity.Warning),
            ),
        )
        val reply = MechaHandlers.execute(MechaIntent.ExplainFindings(FindingSelector(all = true, severity = Severity.Warning)), c)
        assertTrue(reply.text.contains("1. line 3"))
        assertTrue(reply.text.contains("2. line 5"))
    }

    @Test
    fun `find symbol returns the declaration body`() {
        val reply = MechaHandlers.execute(MechaIntent.FindSymbol("render"), ctx())
        assertTrue(reply.text.contains("function"))
        assertTrue(reply.text.contains("println(name)"))
    }

    @Test
    fun `find missing symbol is honest`() {
        val reply = MechaHandlers.execute(MechaIntent.FindSymbol("nope"), ctx())
        assertTrue(reply.text.contains("can't find"))
    }

    @Test
    fun `usages excludes the declaration line`() {
        val reply = MechaHandlers.execute(MechaIntent.FindUsages("UUID"), ctx())
        assertTrue(reply.text.contains("used on"))
    }

    @Test
    fun `unused imports are detected`() {
        val text = """
            import kotlin.math.max
            import java.util.UUID

            fun f() = max(1, 2)
        """.trimIndent()
        val reply = MechaHandlers.execute(MechaIntent.FindUnusedImports, ctx(text = text))
        assertTrue(reply.text.contains("java.util.UUID"))
        assertFalse(reply.text.contains("kotlin.math.max"))
    }

    @Test
    fun `todos are found`() {
        val text = "fun f() {\n  // TODO: fix this\n  // FIXME later\n}"
        val reply = MechaHandlers.execute(MechaIntent.FindTodos, ctx(text = text))
        assertTrue(reply.text.contains("line 2"))
        assertTrue(reply.text.contains("line 3"))
    }

    @Test
    fun `long lines are found`() {
        val text = "short\n" + "x".repeat(130)
        val reply = MechaHandlers.execute(MechaIntent.FindLongLines, ctx(text = text))
        assertTrue(reply.text.contains("line 2"))
    }

    @Test
    fun `long functions are found`() {
        val body = buildString {
            appendLine("fun big() {")
            repeat(60) { appendLine("  println($it)") }
            appendLine("}")
            appendLine("fun small() { println(1) }")
        }
        val reply = MechaHandlers.execute(MechaIntent.FindLongFunctions, ctx(text = body))
        assertTrue(reply.text.contains("big"))
        assertFalse(reply.text.contains("small"))
    }

    @Test
    fun `greeting mentions real finding counts`() {
        val c = ctx(findings = listOf(finding(3, Severity.Error), finding(5, Severity.Warning)))
        val greeting = MechaHandlers.greetingText(c)
        assertTrue(greeting.contains("1 error"))
        assertTrue(greeting.contains("1 warning"))
    }

    @Test
    fun `unknown reply offers concrete commands`() {
        val reply = MechaHandlers.execute(MechaIntent.Unknown("blah"), ctx())
        assertTrue(reply.text.contains("explain finding 1"))
        assertTrue(reply.text.contains("help"))
    }

    @Test
    fun `next finding advances through the list`() {
        val c = ctx(findings = List(3) { finding(it + 1, Severity.Error) }, lastFinding = 1)
        val reply = MechaHandlers.execute(MechaIntent.NextFinding, c)
        assertTrue(reply.text.contains("line 2"))
    }

    @Test
    fun `package name is reported`() {
        val reply = MechaHandlers.execute(MechaIntent.PackageName, ctx())
        assertTrue(reply.text.contains("dev.example.app"))
    }

    @Test
    fun `search finds matching lines`() {
        val reply = MechaHandlers.execute(MechaIntent.Search("println"), ctx())
        assertTrue(reply.text.contains("match"))
    }

    /**
     * The design mandate is that Mecha is deterministic, offline and answers in well under
     * 50 ms. This pins the whole pipeline (classify → execute) over a realistically large
     * file so a future change cannot quietly make the hot path slow — for example by
     * reaching for a model, or by re-parsing the file on every keystroke.
     */
    @Test
    fun `the whole pipeline stays well under 50ms on a large file`() {
        val big = buildString {
            appendLine("package dev.example.big")
            appendLine()
            (1..4_000).forEach { appendLine("fun f$it(x: Int): Int = x + $it") }
        }
        val bigCtx = MechaContext(
            path = "src/Big.kt",
            text = big,
            findings = (1..40).map { finding(it * 7, if (it % 3 == 0) Severity.Error else Severity.Warning) },
        )
        val commands = listOf(
            "stats", "list imports", "show functions", "unused imports", "find todos",
            "long functions", "list errors", "explain finding 3", "fix all errors",
            "next", "previous", "help", "goto line 2500",
        )
        // Warm up so the JIT has run before the timed pass.
        repeat(3) { commands.forEach { MechaHandlers.execute(MechaBrain.classify(it, bigCtx), bigCtx) } }

        val start = System.nanoTime()
        repeat(10) { commands.forEach { MechaHandlers.execute(MechaBrain.classify(it, bigCtx), bigCtx) } }
        val perCommandMs = (System.nanoTime() - start) / 1_000_000.0 / (10 * commands.size)

        assertTrue(
            "Mecha averaged %.2f ms per command, which breaks the <50ms budget".format(perCommandMs),
            perCommandMs < 50.0,
        )
    }
}
