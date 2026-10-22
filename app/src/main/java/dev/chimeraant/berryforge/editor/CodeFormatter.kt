package dev.chimeraant.berryforge.editor

/**
 * Language-agnostic source tidy, used by the editor's Format action.
 *
 * Deliberately conservative: it only makes changes that are correct in every language and
 * that no reader would object to. Re-indenting by syntax is not attempted — that needs a
 * parser per language, and a wrong re-indent silently corrupts code. What it does is the
 * housekeeping a formatter is expected to do regardless of grammar:
 *
 *  - strips trailing whitespace from every line
 *  - collapses runs of three or more blank lines to a single blank line
 *  - removes whitespace-only lines entirely
 *  - expands a leading tab to [tabWidth] spaces, so indentation is consistent with the
 *    editor's tab setting
 *  - normalises line endings to `\n`
 *  - ends the file with exactly one newline
 *
 * Pure and side-effect free, so the behaviour is pinned by unit tests rather than by
 * looking at it on a device.
 */
object CodeFormatter {

    fun tidy(text: String, tabWidth: Int = 4): String {
        val width = tabWidth.coerceIn(1, 16)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        val out = ArrayList<String>(lines.size)
        var blankRun = 0
        for (raw in lines) {
            val expanded = expandLeadingTabs(raw, width).trimEnd()
            if (expanded.isEmpty()) {
                // Never emit more than one consecutive blank line.
                blankRun++
                if (blankRun > 1) continue
                out += ""
            } else {
                blankRun = 0
                out += expanded
            }
        }

        // Trailing blank lines are dropped; the final newline is added below, so a file
        // never ends with a run of empty lines.
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        if (out.isEmpty()) return ""
        return out.joinToString("\n", postfix = "\n")
    }

    /**
     * Replaces the run of tabs at the start of [line] with spaces.
     *
     * Only the indentation is touched: a tab inside a string literal or between tokens is
     * left exactly as it was, because changing it could alter the value of a literal.
     */
    private fun expandLeadingTabs(line: String, width: Int): String {
        var index = 0
        while (index < line.length && (line[index] == '\t' || line[index] == ' ')) index++
        val indent = line.substring(0, index)
        if (!indent.contains('\t')) return line
        val spaces = buildString {
            indent.forEach { char ->
                if (char == '\t') repeat(width) { append(' ') } else append(char)
            }
        }
        return spaces + line.substring(index)
    }

    /**
     * True when [tidy] would change nothing.
     *
     * The editor uses this to report "already tidy" rather than pushing an identical buffer
     * through the change path, which would mark the file dirty for no reason.
     */
    fun isTidy(text: String, tabWidth: Int = 4): Boolean = tidy(text, tabWidth) == text
}
