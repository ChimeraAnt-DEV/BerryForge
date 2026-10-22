package dev.chimeraant.berryforge.ai.mecha

import dev.chimeraant.berryforge.ai.ReviewFinding
import dev.chimeraant.berryforge.ai.Severity

/**
 * Executes a [MechaIntent] against a [MechaContext] and returns a [MechaReply].
 *
 * Every function here is pure: it reads only the context and returns text (plus, for
 * fixes, a list of described edits the caller applies). No I/O, no editor access, no
 * model. That is why the whole assistant is unit-testable and why it can never be wrong
 * about what a file contains — it only ever reports what it parsed.
 */
object MechaHandlers {

    fun execute(intent: MechaIntent, ctx: MechaContext): MechaReply = when (intent) {
        MechaIntent.Greeting -> greeting(ctx)
        MechaIntent.Help -> help()
        MechaIntent.Clear -> MechaReply("Cleared.")
        MechaIntent.Undo -> MechaReply("Reverting the last change.")

        MechaIntent.FileInfo -> fileInfo(ctx)
        MechaIntent.Stats -> stats(ctx)
        MechaIntent.ListImports -> listImports(ctx)
        MechaIntent.PackageName -> packageName(ctx)
        is MechaIntent.ListSymbols -> listSymbols(ctx, intent.kind)

        is MechaIntent.GotoLine -> MechaReply("Jumping to line ${intent.line}.")
        is MechaIntent.FindSymbol -> findSymbol(ctx, intent.name)
        is MechaIntent.FindUsages -> findUsages(ctx, intent.name)
        is MechaIntent.Search -> search(ctx, intent.query)

        is MechaIntent.ListFindings -> listFindings(ctx, intent.severity)
        is MechaIntent.ExplainFindings -> explainFindings(ctx, intent.selector)
        is MechaIntent.FixFindings -> fixFindings(ctx, intent.selector)
        MechaIntent.NextFinding -> cycleFinding(ctx, forward = true)
        MechaIntent.PreviousFinding -> cycleFinding(ctx, forward = false)

        MechaIntent.FindUnusedImports -> unusedImports(ctx)
        MechaIntent.FindTodos -> todos(ctx)
        MechaIntent.FindLongLines -> longLines(ctx)
        MechaIntent.FindLongFunctions -> longFunctions(ctx)

        is MechaIntent.Unknown -> MechaReply.unknown(intent.raw)
    }

    // ---- Meta ----

    private fun greeting(ctx: MechaContext): MechaReply = MechaReply(greetingText(ctx))

    /** The opening line shown when the sheet first opens, grounded in the file's real state. */
    fun greetingText(ctx: MechaContext): String =
        "Hi — I'm Mecha, your code assistant for this file. I answer instantly and I only " +
            "tell you what's really here. ${priorLine(ctx)}"

    private fun help(): MechaReply = MechaReply(
        buildString {
            appendLine("I answer structural questions about this file and act on review findings.")
            appendLine()
            appendLine("File facts: what is this file · stats · list imports · what package")
            appendLine("Symbols: show functions · list classes · find myFunction · usages of myFunction")
            appendLine("Findings: list errors · list warnings · explain finding 1 · fix finding 2")
            appendLine("Bulk: fix all errors · explain all warnings")
            appendLine("Smells: unused imports · find todos · long lines · long functions")
            appendLine("Navigation: goto line 42 · search foo · next · previous")
            appendLine("Other: undo · clear")
        }.trim(),
    )

    /** The one-line state summary used to greet the user when the sheet opens. */
    fun priorLine(ctx: MechaContext): String {
        val errors = ctx.findings.count { it.severity == Severity.Error }
        val warnings = ctx.findings.count { it.severity == Severity.Warning }
        return when {
            ctx.findings.isEmpty() ->
                "I don't see any review findings for this file yet — ask me for stats or a symbol."
            errors > 0 && warnings > 0 ->
                "This file has $errors error${plural(errors)} and $warnings warning${plural(warnings)}. " +
                    "Try \"explain finding 1\" or \"fix all errors\"."
            errors > 0 ->
                "This file has $errors error${plural(errors)}. Try \"explain finding 1\" or \"fix all errors\"."
            warnings > 0 ->
                "This file has $warnings warning${plural(warnings)}. Try \"list warnings\" or \"explain finding 1\"."
            else ->
                "This file has ${ctx.findings.size} finding${plural(ctx.findings.size)}. Try \"list findings\"."
        }
    }

    // ---- File facts ----

    private fun fileInfo(ctx: MechaContext): MechaReply {
        val language = LanguageParsers.languageOf(ctx.path)
        val lines = ctx.text.lines().size
        val imports = LanguageParsers.findImports(ctx.text).size
        val symbols = LanguageParsers.findSymbols(ctx.text, language).size
        val findings = ctx.findings.size
        return MechaReply(
            buildString {
                appendLine("`${ctx.path.substringAfterLast('/')}`")
                appendLine("• Language: $language")
                appendLine("• Lines: $lines")
                appendLine("• Imports: $imports")
                appendLine("• Symbols: $symbols")
                appendLine("• Review findings: $findings")
                LanguageParsers.findPackage(ctx.text)?.let { appendLine("• Package: $it") }
            }.trim(),
        )
    }

    private fun stats(ctx: MechaContext): MechaReply {
        val lines = ctx.text.lines()
        val nonBlank = lines.count { it.isNotBlank() }
        val comment = lines.count { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        val chars = ctx.text.length
        return MechaReply(
            buildString {
                appendLine("Stats for `${ctx.path.substringAfterLast('/')}`:")
                appendLine("• Lines: ${lines.size} (${nonBlank} non-blank)")
                appendLine("• Comment lines: $comment")
                appendLine("• Characters: $chars")
                appendLine("• Findings: ${ctx.findings.size}")
            }.trim(),
        )
    }

    private fun listImports(ctx: MechaContext): MechaReply {
        val imports = LanguageParsers.findImports(ctx.text)
        if (imports.isEmpty()) return MechaReply("This file has no imports.")
        return MechaReply(
            buildString {
                appendLine("${imports.size} import${plural(imports.size)}:")
                imports.forEach { appendLine("• line ${it.lineIndex + 1}: ${it.path}") }
            }.trim(),
        )
    }

    private fun packageName(ctx: MechaContext): MechaReply {
        val pkg = LanguageParsers.findPackage(ctx.text)
            ?: return MechaReply("This file has no package declaration.")
        return MechaReply("This file is in package `$pkg`.")
    }

    private fun listSymbols(ctx: MechaContext, kind: SymbolKind?): MechaReply {
        val language = LanguageParsers.languageOf(ctx.path)
        val symbols = LanguageParsers.findSymbols(ctx.text, language)
            .filter { kind == null || it.kind == kind }
        if (symbols.isEmpty()) {
            return MechaReply("I can't find any ${kind?.label ?: "symbols"} in this file.")
        }
        return MechaReply(
            buildString {
                appendLine("${symbols.size} ${kind?.label ?: "symbol"}${plural(symbols.size)}:")
                symbols.forEach { appendLine("• line ${it.lineIndex + 1}: ${it.kind.label} `${it.name}`") }
            }.trim(),
        )
    }

    // ---- Symbols ----

    private fun findSymbol(ctx: MechaContext, name: String): MechaReply {
        if (name.isBlank()) return MechaReply("Which symbol should I look for?")
        val language = LanguageParsers.languageOf(ctx.path)
        val symbol = LanguageParsers.findSymbol(ctx.text, language, name)
            ?: return MechaReply("I can't find a declaration for `$name` in this file.")
        val body = ctx.text.lines().drop(symbol.lineIndex).take(12).joinToString("\n")
        return MechaReply(
            "`$name` is a ${symbol.kind.label} defined on line ${symbol.lineIndex + 1}:\n\n```\n$body\n```",
        )
    }

    private fun findUsages(ctx: MechaContext, name: String): MechaReply {
        if (name.isBlank()) return MechaReply("Which symbol should I look for?")
        val language = LanguageParsers.languageOf(ctx.path)
        val usages = LanguageParsers.findUsages(ctx.text, language, name)
        if (usages.isEmpty()) return MechaReply("`$name` is not used anywhere else in this file.")
        return MechaReply(
            buildString {
                appendLine("`$name` is used on ${usages.size} other line${plural(usages.size)}:")
                usages.forEach { appendLine("• line ${it + 1}") }
            }.trim(),
        )
    }

    private fun search(ctx: MechaContext, query: String): MechaReply {
        if (query.isBlank()) return MechaReply("What should I search for?")
        val hits = ctx.text.lines().mapIndexedNotNull { index, line ->
            if (line.contains(query, ignoreCase = true)) index to line.trim() else null
        }
        if (hits.isEmpty()) return MechaReply("No matches for \"$query\" in this file.")
        return MechaReply(
            buildString {
                appendLine("${hits.size} match${plural(hits.size)} for \"$query\":")
                hits.take(20).forEach { (index, line) ->
                    appendLine("• line ${index + 1}: ${line.take(100)}")
                }
                if (hits.size > 20) appendLine("…and ${hits.size - 20} more.")
            }.trim(),
        )
    }

    // ---- Findings ----

    private fun listFindings(ctx: MechaContext, severity: Severity?): MechaReply {
        val findings = ctx.findings.filter { severity == null || it.severity == severity }
        if (findings.isEmpty()) {
            val what = severity?.name?.lowercase()?.plus("s") ?: "findings"
            return MechaReply("There are no $what for this file.")
        }
        return MechaReply(
            buildString {
                appendLine("${findings.size} ${severity?.name?.lowercase() ?: "finding"}${plural(findings.size)}:")
                findings.forEachIndexed { i, f ->
                    appendLine("• ${i + 1}. line ${f.startLine} — ${f.severity.name.lowercase()}: ${f.message.take(90)}")
                }
            }.trim(),
        )
    }

    private fun explainFindings(ctx: MechaContext, selector: FindingSelector): MechaReply {
        val selected = select(ctx, selector)
        if (selected.isEmpty()) return noFindings(selector)
        return MechaReply(selected.mapIndexed { i, f -> explainOne(f, selected.size > 1, i + 1) }.joinToString("\n\n"))
    }

    private fun explainOne(finding: ReviewFinding, numbered: Boolean, index: Int): String {
        val where = if (finding.startLine == finding.endLine) {
            "line ${finding.startLine}"
        } else {
            "lines ${finding.startLine}–${finding.endLine}"
        }
        val body = when (finding.severity) {
            Severity.Error -> "This won't compile. ${finding.message}"
            Severity.Warning -> "This can fail at runtime. ${finding.message}"
            Severity.Perf -> "This is slower than it needs to be. ${finding.message}"
            Severity.DeadCode -> "This is never used. ${finding.message}"
            Severity.Info -> finding.message
        }
        val head = if (numbered) "${index}. " else ""
        val fix = finding.suggestion?.let { "\n\nSuggested fix:\n$it" } ?: ""
        return "$head$where (${finding.category.label}): $body$fix"
    }

    private fun fixFindings(ctx: MechaContext, selector: FindingSelector): MechaReply {
        val selected = select(ctx, selector)
        if (selected.isEmpty()) return noFindings(selector)
        // mapNotNull rather than filter + `!!`: the non-null assertion was correct only
        // because of the filter immediately above it, which is exactly the kind of
        // invariant that breaks silently on a later edit. A suggestion that is null (or
        // blank) is dropped here instead.
        val actions = selected.mapNotNull { finding ->
            val suggestion = finding.suggestion?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MechaFixAction(finding.startLine, finding.endLine, suggestion.trimEnd())
        }
        if (actions.isEmpty()) {
            return MechaReply("I found ${selected.size} finding${plural(selected.size)}, but none came with a suggested fix.")
        }
        val skipped = selected.size - actions.size
        val tail = if (skipped > 0) " ($skipped had no suggestion.)" else ""
        return MechaReply(
            text = "Applying ${actions.size} fix${plural(actions.size)}. Tap Undo to revert.$tail",
            actions = actions,
        )
    }

    private fun cycleFinding(ctx: MechaContext, forward: Boolean): MechaReply {
        if (ctx.findings.isEmpty()) return MechaReply("There are no findings to move through.")
        val current = ctx.lastFindingIndex ?: if (forward) 0 else 2
        val next = if (forward) {
            (current + 1).coerceAtMost(ctx.findings.size)
        } else {
            (current - 1).coerceAtLeast(1)
        }
        val finding = ctx.findings.getOrNull(next - 1) ?: ctx.findings.first()
        return MechaReply(explainOne(finding, numbered = false, index = next))
    }

    private fun select(ctx: MechaContext, selector: FindingSelector): List<ReviewFinding> {
        val bySeverity = ctx.findings.filter { selector.severity == null || it.severity == selector.severity }
        if (selector.all) return bySeverity
        selector.index?.let { index ->
            // Prefer the Nth of the requested severity when a severity was named.
            if (selector.severity != null) return bySeverity.getOrNull(index - 1)?.let { listOf(it) } ?: emptyList()
            return ctx.findings.getOrNull(index - 1)?.let { listOf(it) } ?: emptyList()
        }
        // No index: a severity alone means all of them; otherwise the last discussed.
        if (selector.severity != null) return bySeverity
        val last = ctx.lastFindingIndex ?: return ctx.findings.firstOrNull()?.let { listOf(it) } ?: emptyList()
        return ctx.findings.getOrNull(last - 1)?.let { listOf(it) } ?: emptyList()
    }

    private fun noFindings(selector: FindingSelector): MechaReply = when {
        selector.severity != null ->
            MechaReply("There are no ${selector.severity.name.lowercase()}s for this file.")
        selector.index != null ->
            MechaReply("There is no finding ${selector.index} in this file.")
        else -> MechaReply("There are no findings for this file.")
    }

    // ---- Code smells ----

    private fun unusedImports(ctx: MechaContext): MechaReply {
        val unused = LanguageParsers.findUnusedImports(ctx.text)
        if (unused.isEmpty()) return MechaReply("Every import in this file looks used.")
        return MechaReply(
            buildString {
                appendLine("${unused.size} unused import${plural(unused.size)}:")
                unused.forEach { appendLine("• line ${it.lineIndex + 1}: ${it.path}") }
            }.trim(),
        )
    }

    private fun todos(ctx: MechaContext): MechaReply {
        val markers = LanguageParsers.findTodos(ctx.text)
        if (markers.isEmpty()) return MechaReply("No TODO/FIXME/HACK/XXX markers in this file.")
        return MechaReply(
            buildString {
                appendLine("${markers.size} marker${plural(markers.size)}:")
                markers.forEach { appendLine("• line ${it.lineIndex + 1}: ${it.text.take(100)}") }
            }.trim(),
        )
    }

    private fun longLines(ctx: MechaContext): MechaReply {
        val lines = LanguageParsers.findLongLines(ctx.text)
        if (lines.isEmpty()) return MechaReply("No lines longer than 120 characters.")
        return MechaReply(
            buildString {
                appendLine("${lines.size} long line${plural(lines.size)} (>120 chars):")
                lines.take(20).forEach { appendLine("• line ${it + 1}") }
            }.trim(),
        )
    }

    private fun longFunctions(ctx: MechaContext): MechaReply {
        val language = LanguageParsers.languageOf(ctx.path)
        val functions = LanguageParsers.findLongFunctions(ctx.text, language)
        if (functions.isEmpty()) return MechaReply("No functions longer than 50 lines.")
        return MechaReply(
            buildString {
                appendLine("${functions.size} long function${plural(functions.size)} (>50 lines):")
                functions.forEach { appendLine("• `${it.name}` starts on line ${it.lineIndex + 1}") }
            }.trim(),
        )
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}
