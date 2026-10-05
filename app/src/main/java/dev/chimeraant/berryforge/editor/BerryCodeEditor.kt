package dev.chimeraant.berryforge.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor

/** Snapshot of everything the editor needs to render one file. */
data class EditorConfig(
    val path: String,
    val text: String,
    val fontSize: Int,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
    val tabWidth: Int,
    val readOnly: Boolean = false,
    /**
     * 1-based line numbers carrying a review annotation, mapped to the marker colour.
     * Rendered by [FindingsSpine] alongside the editor, since Sora draws squiggles but
     * not per-line gutter glyphs.
     */
    val gutterMarks: Map<Int, androidx.compose.ui.graphics.Color> = emptyMap(),
    /** Total line count, used to position spine marks proportionally. */
    val lineCount: Int = 0,
)

/**
 * Compose wrapper around Sora's [CodeEditor].
 *
 * The editor view is created once and reconfigured on change rather than recreated, so
 * typing never rebuilds the native view. Callbacks are held in [rememberUpdatedState] so
 * the view keeps a stable listener while Compose recomposes around it.
 */
@Composable
fun BerryCodeEditor(
    config: EditorConfig,
    modifier: Modifier = Modifier,
    onContentChange: (String) -> Unit = {},
    onSelectionChange: (line: Int, column: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onContentChange)
    val currentOnSelection by rememberUpdatedState(onSelectionChange)

    val editor = remember {
        CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            isHighlightCurrentLine = true
            isHighlightCurrentBlock = true
            setLineSpacing(1.25f, 1.1f)
            setPinLineNumber(false)
            isUndoEnabled = true
            setCursorBlinkPeriod(600)
            isCursorAnimationEnabled = true
        }
    }

    // Language + theme, applied whenever the file changes.
    LaunchedEffect(config.path) {
        runCatching {
            editor.setEditorLanguage(BerryEditorTheme.languageFor(context, config.path))
        }
    }
    LaunchedEffect(Unit) {
        runCatching { editor.colorScheme = BerryEditorTheme.colorScheme(context) }
    }

    // Cheap properties: applied on every relevant change.
    LaunchedEffect(config.fontSize) {
        runCatching { editor.setTextSize(config.fontSize.toFloat()) }
    }
    LaunchedEffect(config.wordWrap) {
        runCatching { editor.isWordwrap = config.wordWrap }
    }
    LaunchedEffect(config.lineNumbers) {
        runCatching { editor.setLineNumberEnabled(config.lineNumbers) }
    }
    LaunchedEffect(config.tabWidth) {
        runCatching { editor.setTabWidth(config.tabWidth) }
    }
    LaunchedEffect(config.readOnly) {
        runCatching { editor.isEditable = !config.readOnly }
    }

    // Only push text in when it differs, otherwise the cursor jumps on every keystroke.
    LaunchedEffect(config.path, config.text) {
        runCatching {
            if (editor.text.toString() != config.text) {
                val line = editor.cursor?.leftLine ?: 0
                val column = editor.cursor?.leftColumn ?: 0
                editor.setText(config.text)
                val safeLine = line.coerceAtMost((editor.text.lineCount - 1).coerceAtLeast(0))
                editor.setSelection(safeLine, column.coerceAtMost(editor.text.getColumnCount(safeLine)))
            }
        }
    }

    DisposableEffect(editor) {
        val contentSubscription = editor.subscribeEvent(
            io.github.rosemoe.sora.event.ContentChangeEvent::class.java,
        ) { _, _ ->
            currentOnChange(editor.text.toString())
        }
        val selectionSubscription = editor.subscribeEvent(
            io.github.rosemoe.sora.event.SelectionChangeEvent::class.java,
        ) { event, _ ->
            val cursor = event.left
            currentOnSelection(cursor.line, cursor.column)
        }
        onDispose {
            runCatching { contentSubscription.unsubscribe() }
            runCatching { selectionSubscription.unsubscribe() }
            runCatching { editor.release() }
        }
    }

    AndroidView(
        factory = { editor },
        modifier = modifier,
        update = { /* configured through the effects above */ },
    )
}

/**
 * Severity spine drawn down the right edge of the editor.
 *
 * Sora renders diagnostic squiggles inside the text but has no per-line gutter glyph,
 * so review findings get their own proportional markers here: the file is mapped onto
 * the available height and each annotated line gets a coloured tick. Tapping opens the
 * full review sheet.
 */
@Composable
fun FindingsSpine(
    marks: Map<Int, androidx.compose.ui.graphics.Color>,
    lineCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    if (marks.isEmpty() || lineCount <= 0) return
    androidx.compose.foundation.Canvas(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        val height = size.height
        val tickHeight = 3.dp.toPx()
        marks.forEach { (line, color) ->
            val fraction = ((line - 1).toFloat() / lineCount).coerceIn(0f, 1f)
            val y = fraction * height
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, y - tickHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width, tickHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tickHeight / 2),
            )
        }
    }
}

/** Imperative handle so the screen can drive find/replace and goto-line. */
class EditorHandle internal constructor(private val editor: CodeEditor) {
    fun undo() = runCatching { editor.undo() }
    fun redo() = runCatching { editor.redo() }
    fun search(query: String, caseSensitive: Boolean = false) = runCatching {
        editor.searcher.search(
            query,
            io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(caseSensitive, false),
        )
    }
    fun nextMatch() = runCatching { editor.searcher.gotoNext() }
    fun previousMatch() = runCatching { editor.searcher.gotoPrevious() }
    fun replaceAll(replacement: String) = runCatching { editor.searcher.replaceAll(replacement) }
    fun stopSearch() = runCatching { editor.searcher.stopSearch() }
    fun gotoLine(line: Int) = runCatching {
        val target = (line - 1).coerceIn(0, (editor.text.lineCount - 1).coerceAtLeast(0))
        editor.setSelection(target, 0)
        editor.ensurePositionVisible(target, 0)
    }
    fun format() = runCatching { editor.formatCodeAsync() }
}
