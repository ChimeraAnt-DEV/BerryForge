package dev.chimeraant.berryforge.ai.mecha

import dev.chimeraant.berryforge.ai.AiReviewService

/**
 * Regex toolbox that answers structural questions about a file.
 *
 * This is deliberately not a compiler. It is a set of tight patterns that are right the
 * overwhelming majority of the time and cost microseconds — which is the whole trade: an
 * answer that is always instant and honest about being approximate beats a slow guess.
 */
object LanguageParsers {

    private val FUN_DECL = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)""")
    private val CLASS_DECL = Regex("""\b(class|interface|object|enum\s+class)\s+(\w+)""")
    private val IMPORT = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)""")
    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""")
    private val TODO_MARK = Regex("""\b(TODO|FIXME|HACK|XXX)\b""")
    private val KOTLIN_PROPERTY = Regex("""^\s*(?:val|var)\s+(\w+)""")
    private val JAVA_METHOD = Regex("""^\s*(?:public|private|protected|static|final|synchronized|abstract|native|\s)*[\w<>\[\],.\s]+\s+(\w+)\s*\([^;]*\)\s*\{""")

    /** True when [language] is a C-family/JVM language this toolbox has patterns for. */
    private fun isKotlin(language: String) = language.equals("Kotlin", ignoreCase = true)
    private fun isJava(language: String) = language.equals("Java", ignoreCase = true)

    fun findSymbols(text: String, language: String): List<Symbol> {
        val symbols = mutableListOf<Symbol>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw
            FUN_DECL.find(line)?.let {
                symbols += Symbol(it.groupValues[1], SymbolKind.Function, index, line.trim())
            }
            CLASS_DECL.find(line)?.let {
                val kind = when {
                    it.groupValues[1].startsWith("interface") -> SymbolKind.Interface
                    it.groupValues[1].startsWith("object") -> SymbolKind.Object
                    it.groupValues[1].startsWith("enum") -> SymbolKind.Enum
                    else -> SymbolKind.Class
                }
                symbols += Symbol(it.groupValues[2], kind, index, line.trim())
            }
            if (isKotlin(language)) {
                KOTLIN_PROPERTY.find(line)?.let {
                    symbols += Symbol(it.groupValues[1], SymbolKind.Property, index, line.trim())
                }
            }
            if (isJava(language) && !FUN_DECL.containsMatchIn(line)) {
                JAVA_METHOD.find(line)?.let {
                    symbols += Symbol(it.groupValues[1], SymbolKind.Function, index, line.trim())
                }
            }
        }
        return symbols
    }

    /** Case-insensitive exact-name lookup, first match wins (declaration order). */
    fun findSymbol(text: String, language: String, name: String): Symbol? =
        findSymbols(text, language).firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun findImports(text: String): List<ImportInfo> =
        text.lineSequence().mapIndexedNotNull { index, line ->
            IMPORT.find(line)?.groupValues?.get(1)?.let { ImportInfo(it, index) }
        }.toList()

    fun findPackage(text: String): String? =
        text.lineSequence().firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) }

    /**
     * Imports whose simple name never appears in the file body.
     *
     * The body excludes the import block itself, and matches on word boundaries so
     * `Foo` is not considered used merely because `FooBar` appears somewhere.
     */
    fun findUnusedImports(text: String): List<ImportInfo> {
        val imports = findImports(text)
        if (imports.isEmpty()) return emptyList()
        val body = text.lineSequence()
            .filterNot { IMPORT.containsMatchIn(it) || it.trimStart().startsWith("package ") }
            .joinToString("\n")
        return imports.filter { imp ->
            val short = Regex.escape(imp.simpleName)
            !Regex("""\b$short\b""").containsMatchIn(body)
        }
    }

    fun findTodos(text: String): List<TodoMarker> =
        text.lineSequence().mapIndexedNotNull { index, line ->
            TODO_MARK.find(line)?.let { match ->
                TodoMarker(index, match.groupValues[1], line.trim())
            }
        }.toList()

    fun findLongLines(text: String, maxLength: Int = 120): List<Int> =
        text.lineSequence().mapIndexedNotNull { index, line ->
            if (line.length > maxLength) index else null
        }.toList()

    /**
     * Declarations whose body exceeds [maxLines].
     *
     * Body length is measured from a declaration to the next declaration at the same or
     * shallower indentation, which approximates a function span without a real parser.
     */
    fun findLongFunctions(text: String, language: String, maxLines: Int = 50): List<Symbol> {
        val symbols = findSymbols(text, language).filter { it.kind == SymbolKind.Function }
        val lines = text.lines()
        return symbols.filter { symbol ->
            val startIndent = lines.getOrNull(symbol.lineIndex)?.takeWhile { it == ' ' }?.length ?: 0
            val nextStart = symbols
                .filter { it.lineIndex > symbol.lineIndex }
                .minByOrNull { it.lineIndex }
                ?.lineIndex ?: lines.size
            val nextAtSameLevel = (symbol.lineIndex + 1 until nextStart).firstOrNull { i ->
                val indent = lines[i].takeWhile { it == ' ' }.length
                lines[i].isNotBlank() && indent <= startIndent && i > symbol.lineIndex
            } ?: nextStart
            (nextAtSameLevel - symbol.lineIndex) > maxLines
        }
    }

    /** Line indices (zero-based) where [name] appears outside its own declaration line. */
    fun findUsages(text: String, language: String, name: String): List<Int> {
        val declarationLine = findSymbol(text, language, name)?.lineIndex
        val pattern = Regex("""\b${Regex.escape(name)}\b""")
        return text.lineSequence().mapIndexedNotNull { index, line ->
            if (index != declarationLine && pattern.containsMatchIn(line)) index else null
        }.toList()
    }

    /** Convenience: the language label used everywhere else for a path. */
    fun languageOf(path: String): String = AiReviewService.languageOf(path)
}
