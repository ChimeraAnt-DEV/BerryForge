package dev.chimeraant.berryforge.ai.mecha

import dev.chimeraant.berryforge.ai.Severity

/**
 * Turns a line of user text into a named [MechaIntent].
 *
 * Priority-ordered keyword and regex matching — no model, no ambiguity, no latency. The
 * order matters: cheap exact matches (greeting, help) are tested before broad patterns,
 * and finding commands are tested before symbol lookup so "show errors" is not read as a
 * request for a symbol called "errors".
 */
object MechaBrain {

    private val GREETINGS = setOf("hi", "hello", "hey", "yo", "sup", "hi mecha", "hello mecha", "hey mecha")
    private val HELP = setOf("help", "?", "what can you do", "what can you do?", "commands", "what can you do for me")
    private val CLEAR = setOf("clear", "reset", "start over", "clear chat")

    private val FIX = Regex("""(?:fix|apply|resolve|repair)(?:\s+(.*))?$""")
    private val EXPLAIN = Regex("""(?:explain|what(?:'s| is) wrong with|describe|tell me about)(?:\s+(.*))?$""")

    private val LIST_ERRORS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?errors?$""")
    private val LIST_WARNINGS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?warnings?$""")
    private val LIST_ALL = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?(?:all\s+)?findings?$""")
    private val LIST_PROBLEMS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?problems?$""")

    private val GOTO = Regex("""^(?:(?:goto|go to|jump to|open)\s+)?(?:line\s+)?(\d+)$""")
    private val USAGES = Regex("""^(?:usages? of|where is|where's|find usages of|references to)\s+([A-Za-z_]\w*)(?:\s+used)?$""")
    // Symbol lookup is the broadest catch-all, so its noun must not be a command keyword.
    private val FIND_SYM = Regex(
        """^(?:show|find|goto|go to|jump to|where is|where's|what is|what's)\s+([A-Za-z_]\w*)$""",
    )
    private val SEARCH = Regex("""^(?:search|find|grep)\s+(?:for\s+)?["']?(.+?)["']?$""")

    private val UNUSED_IMPORTS = Regex("""^(?:find\s+|show\s+|list\s+)?unused\s+imports?$""")
    private val TODOS = Regex("""^(?:find\s+|show\s+|list\s+)?(?:todos?|fixmes?|markers?)$""")
    private val LONG_LINES = Regex("""^(?:find\s+|show\s+|list\s+)?long\s+lines?$""")
    private val LONG_FUNCTIONS = Regex("""^(?:find\s+|show\s+|list\s+)?(?:long|big|large)\s+(?:functions?|methods?)$""")

    private val IMPORTS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?imports?$""")
    private val PACKAGE = Regex("""^(?:what(?:'s| is)?\s+(?:the\s+)?package|show\s+package|package)$""")
    private val STATS = Regex("""^(?:stats|statistics|count lines|line count|how many lines)$""")
    private val FILE_INFO = Regex(
        """^(?:what(?:'s| is) this file|what is this|describe this file|file info|about this file|summarize(?: this file)?)$""",
    )
    private val LIST_FUNCTIONS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?(?:functions?|methods?)$""")
    private val LIST_CLASSES = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?(?:classes?|types?)$""")
    private val LIST_SYMBOLS = Regex("""^(?:list|show|what are|get)?\s*(?:the\s+)?symbols?$""")

    fun classify(raw: String, ctx: MechaContext): MechaIntent {
        val t = raw.trim().lowercase().removeSuffix(".").removeSuffix("!").trim()
        if (t.isEmpty()) return MechaIntent.Unknown(raw)

        return when {
            t in GREETINGS -> MechaIntent.Greeting
            t in HELP -> MechaIntent.Help
            t in CLEAR -> MechaIntent.Clear
            t == "undo" || t == "revert" -> MechaIntent.Undo

            t == "next" || t == "next finding" || t == "next error" -> MechaIntent.NextFinding
            t == "previous" || t == "prev" || t == "previous finding" -> MechaIntent.PreviousFinding

            UNUSED_IMPORTS.matches(t) -> MechaIntent.FindUnusedImports
            LONG_FUNCTIONS.matches(t) -> MechaIntent.FindLongFunctions
            LONG_LINES.matches(t) -> MechaIntent.FindLongLines
            TODOS.matches(t) -> MechaIntent.FindTodos

            LIST_ERRORS.matches(t) -> MechaIntent.ListFindings(Severity.Error)
            LIST_WARNINGS.matches(t) -> MechaIntent.ListFindings(Severity.Warning)
            LIST_ALL.matches(t) -> MechaIntent.ListFindings(null)
            LIST_PROBLEMS.matches(t) -> MechaIntent.ListFindings(null)

            // List/describe commands come before the broad symbol rule so "show functions"
            // is a listing, not a request for a symbol called "functions".
            IMPORTS.matches(t) -> MechaIntent.ListImports
            PACKAGE.matches(t) -> MechaIntent.PackageName
            STATS.matches(t) -> MechaIntent.Stats
            FILE_INFO.matches(t) -> MechaIntent.FileInfo
            LIST_FUNCTIONS.matches(t) -> MechaIntent.ListSymbols(SymbolKind.Function)
            LIST_CLASSES.matches(t) -> MechaIntent.ListSymbols(SymbolKind.Class)
            LIST_SYMBOLS.matches(t) -> MechaIntent.ListSymbols(null)

            // toIntOrNull, not toInt: "goto line 99999999999" overflows Int and toInt
            // would throw NumberFormatException straight out of classify(), taking the
            // editor down. An out-of-range line falls through to Unknown instead.
            GOTO.matches(t) -> GOTO.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { MechaIntent.GotoLine(it) }
                ?: MechaIntent.Unknown(raw)

            FIX.matches(t) -> MechaIntent.FixFindings(parseSelector(t, ctx))
            EXPLAIN.matches(t) -> MechaIntent.ExplainFindings(parseSelector(t, ctx))

            USAGES.matches(t) -> MechaIntent.FindUsages(resolveName(t, USAGES, ctx))
            FIND_SYM.matches(t) -> MechaIntent.FindSymbol(resolveName(t, FIND_SYM, ctx))

            SEARCH.matches(t) -> SEARCH.find(t)?.groupValues?.getOrNull(1)?.trim()
                ?.let { MechaIntent.Search(it) }
                ?: MechaIntent.Unknown(raw)

            else -> MechaIntent.Unknown(raw)
        }
    }

    /**
     * Works out which findings a "fix"/"explain" command refers to.
     *
     * Handles "fix all errors", "fix finding 2", "fix the first one", "explain it",
     * "fix all", and a bare "fix". Pronoun and bare forms resolve to the finding last
     * discussed — this single helper is what gives Mecha pronoun understanding.
     */
    fun parseSelector(text: String, ctx: MechaContext): FindingSelector {
        val tail = (FIX.find(text) ?: EXPLAIN.find(text))?.groupValues?.getOrNull(1).orEmpty().trim()
        val severity = when {
            Regex("""\berrors?\b""").containsMatchIn(tail) -> Severity.Error
            Regex("""\bwarnings?\b""").containsMatchIn(tail) -> Severity.Warning
            else -> null
        }
        if (Regex("""\ball\b""").containsMatchIn(tail)) return FindingSelector(severity = severity, all = true)
        if (tail.contains("everything") || tail.contains("them")) return FindingSelector(severity = severity, all = true)

        Regex("""(?:finding\s+)?(\d+)""").find(tail)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return FindingSelector(index = it, severity = severity)
        }
        val ordinal = Regex("""\bthe\s+(first|last|next|previous|second|third)\b""").find(tail)
            ?: Regex("""^(first|last|next|previous|second|third)$""").find(tail)
        val index = when (ordinal?.groupValues?.getOrNull(1)) {
            "first" -> 1
            "second" -> 2
            "third" -> 3
            "last" -> ctx.findings.size.coerceAtLeast(1)
            "next" -> ((ctx.lastFindingIndex ?: 0) + 1).coerceAtMost(ctx.findings.size.coerceAtLeast(1))
            "previous" -> ((ctx.lastFindingIndex ?: 2) - 1).coerceAtLeast(1)
            else -> null
        }
        if (index != null) return FindingSelector(index = index, severity = severity)

        // Bare verb, pronoun, or a bare severity -> the finding last discussed (or the
        // first of the requested severity).
        if (tail.isBlank() || tail == "it" || tail == "that") {
            return FindingSelector(index = ctx.lastFindingIndex, severity = severity)
        }
        return FindingSelector(index = null, severity = severity)
    }

    /** The severity implied by a fix/explain tail, for callers that only need that. */
    fun severityOf(text: String): Severity? {
        val tail = (FIX.find(text) ?: EXPLAIN.find(text))?.groupValues?.getOrNull(1).orEmpty()
        return when {
            Regex("""\berrors?\b""").containsMatchIn(tail) -> Severity.Error
            Regex("""\bwarnings?\b""").containsMatchIn(tail) -> Severity.Warning
            else -> null
        }
    }

    /** Pulls the symbol name from a match, falling back to the last symbol discussed. */
    private fun resolveName(text: String, pattern: Regex, ctx: MechaContext): String {
        val name = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
        return name.ifBlank { ctx.lastSymbol.orEmpty() }
    }
}
