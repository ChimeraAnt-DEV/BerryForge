package dev.chimeraant.berryforge.ai.mecha

import dev.chimeraant.berryforge.ai.ReviewFinding
import dev.chimeraant.berryforge.ai.Severity

/**
 * Everything a Mecha handler is allowed to look at.
 *
 * Handlers are pure functions of this value: they cannot touch the editor, the network
 * or the filesystem. That is what makes every reply deterministic, instant and honest —
 * if the answer is not in here, Mecha says so rather than guessing.
 */
data class MechaContext(
    val path: String,
    val text: String,
    val originalText: String = text,
    val cursorLine: Int = 1,
    val findings: List<ReviewFinding> = emptyList(),
    /** Remembered so "explain it" / "fix that one" resolve to the finding last discussed. */
    val lastFindingIndex: Int? = null,
    /** Remembered so "usages of that" resolves to the symbol last looked up. */
    val lastSymbol: String? = null,
)

/** A parsed declaration. Produced by [LanguageParsers], never by the model. */
data class Symbol(
    val name: String,
    val kind: SymbolKind,
    /** Zero-based line index of the declaration. */
    val lineIndex: Int,
    val signature: String,
)

enum class SymbolKind(val label: String) {
    Function("function"),
    Class("class"),
    Interface("interface"),
    Object("object"),
    Enum("enum"),
    Property("property"),
}

/** One import statement and where it sits. */
data class ImportInfo(val path: String, val lineIndex: Int) {
    val simpleName: String get() = path.substringAfterLast('.')
}

/** A TODO/FIXME/HACK/XXX marker and its surrounding text. */
data class TodoMarker(val lineIndex: Int, val marker: String, val text: String)

/** Which findings a fix/explain command refers to. */
data class FindingSelector(
    val index: Int? = null,
    val severity: Severity? = null,
    /** True for "all", "all errors", "everything". */
    val all: Boolean = false,
) {
    companion object {
        val All = FindingSelector(all = true)
    }
}

/** The named command Mecha understood. Anything else becomes [Unknown]. */
sealed interface MechaIntent {
    data object Greeting : MechaIntent
    data object Help : MechaIntent
    data object Clear : MechaIntent
    data object Undo : MechaIntent

    data object FileInfo : MechaIntent
    data object Stats : MechaIntent
    data object ListImports : MechaIntent
    data object PackageName : MechaIntent
    data class ListSymbols(val kind: SymbolKind?) : MechaIntent

    data class GotoLine(val line: Int) : MechaIntent
    data class FindSymbol(val name: String) : MechaIntent
    data class FindUsages(val name: String) : MechaIntent
    data class Search(val query: String) : MechaIntent

    data class ListFindings(val severity: Severity?) : MechaIntent
    data class ExplainFindings(val selector: FindingSelector) : MechaIntent
    data class FixFindings(val selector: FindingSelector) : MechaIntent
    data object NextFinding : MechaIntent
    data object PreviousFinding : MechaIntent

    data object FindUnusedImports : MechaIntent
    data object FindTodos : MechaIntent
    data object FindLongLines : MechaIntent
    data object FindLongFunctions : MechaIntent

    data class Unknown(val raw: String) : MechaIntent
}

/** A described edit. The handler never performs it; the editor does, so handlers stay pure. */
data class MechaFixAction(
    val startLine: Int,
    val endLine: Int,
    val replacement: String,
)

/** A handler's answer: text plus, optionally, edits the editor should apply. */
data class MechaReply(
    val text: String,
    val actions: List<MechaFixAction> = emptyList(),
) {
    companion object {
        fun notFound(what: String) = MechaReply("I can't find a $what in this file.")

        fun unknown(raw: String) = MechaReply(
            buildString {
                appendLine("I didn't catch that.")
                appendLine()
                appendLine("Try one of:")
                appendLine("• explain finding 1")
                appendLine("• list errors")
                appendLine("• fix all errors")
                appendLine("• find myFunction")
                appendLine("• unused imports")
                appendLine("• goto line 42")
                appendLine("• help")
            }.trim(),
        )
    }
}
