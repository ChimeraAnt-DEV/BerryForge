package dev.chimeraant.berryforge.editor

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import dev.chimeraant.berryforge.ui.design.BerryColors
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Editor theme + grammar bootstrap for Sora Editor.
 *
 * The TextMate grammars live in `assets/textmate` and are registered once per process.
 * The theme is BerryForge's own: the editor background matches the app base colour so
 * there is no seam between the chrome and the code surface.
 */
object BerryEditorTheme {

    @Volatile
    private var initialised = false

    /**
     * Grammar files bundled with the app, keyed by file extension.
     * Kotlin, Java, C/C++, Gradle, JSON, YAML, Markdown and XML are covered; anything
     * else falls back to plain text.
     */
    private val languageSpecs: List<LanguageSpec> = listOf(
        LanguageSpec("kotlin", "source.kotlin", listOf("kt", "kts", "gradle"),
            "textmate/kotlin/syntaxes/Kotlin.tmLanguage",
            "textmate/kotlin/language-configuration.json"),
        LanguageSpec("java", "source.java", listOf("java"),
            "textmate/java/syntaxes/java.tmLanguage.json",
            "textmate/java/language-configuration.json"),
        LanguageSpec("xml", "text.xml", listOf("xml"),
            "textmate/xml/syntaxes/xml.tmLanguage.json",
            "textmate/xml/language-configuration.json"),
        LanguageSpec("markdown", "text.html.markdown", listOf("md"),
            "textmate/markdown/syntaxes/markdown.tmLanguage.json",
            "textmate/markdown/language-configuration.json"),
        LanguageSpec("javascript", "source.js", listOf("js", "json"),
            "textmate/javascript/syntaxes/JavaScript.tmLanguage.json",
            "textmate/javascript/language-configuration.json"),
        LanguageSpec("html", "text.html.basic", listOf("html"),
            "textmate/html/syntaxes/html.tmLanguage.json",
            "textmate/html/language-configuration.json"),
    )

    private val extensionToScope: Map<String, String> = buildMap {
        languageSpecs.forEach { spec -> spec.extensions.forEach { put(it, spec.scope) } }
    }

    /**
     * Registers the asset resolver, grammars and the BerryForge theme.
     * Safe to call repeatedly; the work happens once.
     */
    fun ensureInitialised(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val appContext = context.applicationContext
            val assets = appContext.assets

            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

            val definitions = languageSpecs.map { spec ->
                DefaultGrammarDefinition.withLanguageConfiguration(
                    IGrammarSource.fromInputStream(
                        assets.open(spec.grammarPath),
                        spec.grammarPath,
                        Charsets.UTF_8,
                    ),
                    spec.configPath,
                    spec.name,
                    spec.scope,
                )
            }
            runCatching { GrammarRegistry.getInstance().loadGrammars(definitions) }

            runCatching {
                val themeModel = ThemeModel(
                    IThemeSource.fromInputStream(
                        assets.open(THEME_PATH),
                        THEME_PATH,
                        Charsets.UTF_8,
                    ),
                    "berryforge-dark",
                ).apply { isDark = true }
                themeModel.load()
                ThemeRegistry.getInstance().loadTheme(themeModel, true)
                ThemeRegistry.getInstance().setTheme(themeModel)
            }

            initialised = true
        }
    }

    /** Resolves the language for a path, or an empty language for unsupported types. */
    fun languageFor(context: Context, path: String): Language {
        ensureInitialised(context)
        val extension = path.substringAfterLast('.', "").lowercase()
        val scope = extensionToScope[extension] ?: return EmptyLanguage()
        return runCatching { TextMateLanguage.create(scope, true) }.getOrElse { EmptyLanguage() }
    }

    /** Colour scheme matched to the BerryForge palette. */
    fun colorScheme(context: Context): EditorColorScheme {
        ensureInitialised(context)
        val scheme = runCatching { TextMateColorScheme.create(ThemeRegistry.getInstance()) }
            .getOrElse { EditorColorScheme() }
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, BerryColors.Base.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, BerryColors.Base.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER, BerryColors.TextDisabled.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, BerryColors.TextSecondary.toArgb())
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, BerryColors.OutlineSubtle.toArgb())
        scheme.setColor(EditorColorScheme.CURRENT_LINE, BerryColors.Surface2.toArgb())
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, BerryColors.Edit.copy(alpha = 0.34f).toArgb())
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, BerryColors.TextPrimary.toArgb())
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, BerryColors.Ai.copy(alpha = 0.3f).toArgb())
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, BerryColors.Edit.toArgb())
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, BerryColors.Edit.copy(alpha = 0.12f).toArgb())
        scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, BerryColors.Surface3.toArgb())
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, BerryColors.TextPrimary.toArgb())
        scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, BerryColors.TextTertiary.toArgb())
        scheme.setColor(EditorColorScheme.PROBLEM_ERROR, BerryColors.Danger.toArgb())
        scheme.setColor(EditorColorScheme.PROBLEM_WARNING, BerryColors.Warning.toArgb())
        scheme.setColor(EditorColorScheme.PROBLEM_TYPO, BerryColors.Ai.toArgb())
        return scheme
    }

    private const val THEME_PATH = "textmate/themes/berryforge.json"

    private data class LanguageSpec(
        val name: String,
        val scope: String,
        val extensions: List<String>,
        val grammarPath: String,
        val configPath: String,
    )
}

/** Reads a bundled asset fully into a string. */
internal fun readAsset(context: Context, path: String): String =
    runCatching {
        context.assets.open(path).use { stream ->
            java.io.InputStreamReader(stream, Charsets.UTF_8).readText()
        }
    }.getOrDefault("")

