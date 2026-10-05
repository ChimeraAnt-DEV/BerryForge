package dev.chimeraant.berryforge.ai

import dev.chimeraant.berryforge.core.Http
import dev.chimeraant.berryforge.data.settings.SecureStore
import dev.chimeraant.berryforge.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Severity of a review annotation. Drives the gutter marker colour. */
enum class Severity { Error, Warning, Perf, DeadCode, Info }

enum class FindingCategory(val label: String) {
    CompileError("Compile error"),
    CrashRisk("Runtime crash risk"),
    Performance("Performance"),
    DeadCode("Dead code"),
    UnusedImport("Unused import"),
    Style("Style"),
}

/** One annotation the reviewer produced for a specific line range. */
data class ReviewFinding(
    val id: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val severity: Severity,
    val category: FindingCategory,
    val message: String,
    val suggestion: String? = null,
    val confidence: Float = 0.8f,
)

data class ReviewResult(
    val findings: List<ReviewFinding>,
    val summary: String,
    val model: String,
    val tokensUsed: Int? = null,
)

/**
 * Reviews a file against the user's configured OpenAI-compatible endpoint.
 *
 * The API key is read from [SecureStore] at call time and placed only in the
 * Authorization header of a request to the endpoint the user themselves configured.
 * It is never logged, never cached, and never sent anywhere else.
 */
class AiReviewService(
    private val settings: SettingsStore,
    private val secure: SecureStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns null when no key has been configured, so callers can prompt. */
    fun hasKey(): Boolean = !secure.llmApiKey.isNullOrBlank()

    suspend fun endpoint(): String = settings.llmEndpoint.first().trimEnd('/')

    suspend fun model(): String = settings.llmModel.first()

    suspend fun review(
        path: String,
        content: String,
        repoContext: RepoContext,
        diff: String? = null,
    ): Result<ReviewResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = secure.llmApiKey?.takeIf { it.isNotBlank() }
                ?: error("No API key configured. Add one in Settings → AI review.")
            val base = endpoint()
            val modelName = model()

            val userPrompt = buildPrompt(path, content, repoContext, diff)
            val payload = buildJsonObject {
                put("model", modelName)
                put("temperature", 0.1)
                put("stream", false)
                putJsonObject("response_format") { put("type", "json_object") }
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url("$base/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401, 403 -> "The endpoint rejected the API key (${response.code})."
                            429 -> "Rate limited by the provider. Try again shortly."
                            else -> "Review request failed (${response.code}): ${body.take(300)}"
                        },
                    )
                }
                parseResponse(body, path, modelName)
            }
        }
    }

    /** Streams a commit message suggestion. Kept short so it feels instant. */
    suspend fun suggestCommitMessage(
        repo: String,
        changedPaths: List<String>,
        diff: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = secure.llmApiKey?.takeIf { it.isNotBlank() }
                ?: error("No API key configured.")
            val base = endpoint()
            val modelName = model()
            val prompt = buildString {
                appendLine("Write a single conventional-commit subject line for this change.")
                appendLine("Rules: imperative mood, max 72 characters, no trailing period, no body, no quotes.")
                appendLine("Repository: $repo")
                appendLine("Files changed:")
                changedPaths.take(40).forEach { appendLine("  - $it") }
                if (diff.isNotBlank()) {
                    appendLine()
                    appendLine("Diff excerpt:")
                    appendLine(diff.take(4000))
                }
            }
            val payload = buildJsonObject {
                put("model", modelName)
                put("temperature", 0.2)
                put("stream", false)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", "You write concise, accurate Git commit subjects. Reply with the subject line only.")
                    })
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                })
            }.toString()
            val request = Request.Builder()
                .url("$base/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Commit suggestion failed (${response.code})")
                val root = json.parseToJsonElement(body).jsonObject
                root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?.trim()?.trim('"')?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            }
        }
    }

    // ---- Prompt construction ----

    private fun buildPrompt(
        path: String,
        content: String,
        context: RepoContext,
        diff: String?,
    ): String = buildString {
        appendLine("Review the following file from a software repository.")
        appendLine()
        appendLine("File: $path")
        appendLine("Language: ${languageOf(path)}")
        if (context.repo.isNotBlank()) appendLine("Repository: ${context.repo}")
        if (context.branch.isNotBlank()) appendLine("Branch: ${context.branch}")
        if (context.siblingPaths.isNotEmpty()) {
            appendLine("Other files in the repository (for import and symbol context):")
            context.siblingPaths.take(60).forEach { appendLine("  $it") }
        }
        if (diff != null && diff.isNotBlank()) {
            appendLine()
            appendLine("Change under review (unified diff):")
            appendLine(diff.take(8000))
        }
        appendLine()
        appendLine("Full file contents:")
        appendLine("```")
        appendLine(content.take(60000))
        appendLine("```")
        appendLine()
        appendLine("Return JSON only, matching exactly this shape:")
        appendLine(
            """
            {
              "summary": "one or two sentence overview",
              "findings": [
                {
                  "startLine": 12,
                  "endLine": 12,
                  "severity": "error|warning|perf|deadcode|info",
                  "category": "compile_error|crash_risk|performance|dead_code|unused_import|style",
                  "message": "what is wrong and why it matters",
                  "suggestion": "the concrete fix, or null",
                  "confidence": 0.0
                }
              ]
            }
            """.trimIndent(),
        )
        appendLine()
        appendLine("Line numbers must be 1-based and refer to the full file contents above.")
        appendLine("Only report real issues. Do not invent problems. It is correct to return an empty findings array.")
    }

    private fun parseResponse(body: String, path: String, modelName: String): ReviewResult {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: error("The provider returned no completion. Check the model name in Settings.")

        val usage = root["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.content?.toIntOrNull()

        // Providers sometimes wrap JSON in a fenced block even when asked not to.
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = runCatching { json.parseToJsonElement(cleaned).jsonObject }
            .getOrElse { error("The provider did not return valid JSON. Raw reply: ${content.take(300)}") }

        val summary = parsed["summary"]?.jsonPrimitive?.content.orEmpty()
        val findings = parsed["findings"]?.jsonArray?.mapIndexedNotNull { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val start = obj["startLine"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapIndexedNotNull null
            val end = obj["endLine"]?.jsonPrimitive?.content?.toIntOrNull() ?: start
            val severity = when (obj["severity"]?.jsonPrimitive?.content?.lowercase()) {
                "error" -> Severity.Error
                "warning" -> Severity.Warning
                "perf" -> Severity.Perf
                "deadcode" -> Severity.DeadCode
                else -> Severity.Info
            }
            val category = when (obj["category"]?.jsonPrimitive?.content?.lowercase()) {
                "compile_error" -> FindingCategory.CompileError
                "crash_risk" -> FindingCategory.CrashRisk
                "performance" -> FindingCategory.Performance
                "dead_code" -> FindingCategory.DeadCode
                "unused_import" -> FindingCategory.UnusedImport
                else -> FindingCategory.Style
            }
            ReviewFinding(
                id = "$path:$start:$index",
                path = path,
                startLine = start,
                endLine = maxOf(end, start),
                severity = severity,
                category = category,
                message = obj["message"]?.jsonPrimitive?.content.orEmpty(),
                suggestion = obj["suggestion"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() },
                confidence = obj["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.8f,
            )
        } ?: emptyList()

        return ReviewResult(findings = findings, summary = summary, model = modelName, tokensUsed = usage)
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a precise, senior code reviewer embedded in an Android IDE. " +
                "You find compile errors, runtime crash risks, performance problems, dead code and unused imports. " +
                "You never comment on formatting preferences unless asked. " +
                "You always answer with a single JSON object and nothing else."

        fun languageOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "cpp", "cc", "cxx", "h", "hpp" -> "C++"
            "gradle" -> "Gradle"
            "json" -> "JSON"
            "yml", "yaml" -> "YAML"
            "md" -> "Markdown"
            "xml" -> "XML"
            "pro" -> "ProGuard"
            "properties" -> "Properties"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            else -> "Plain text"
        }
    }
}

/** Repository facts handed to the model alongside the file being reviewed. */
data class RepoContext(
    val repo: String = "",
    val branch: String = "",
    val siblingPaths: List<String> = emptyList(),
    val buildFiles: List<String> = emptyList(),
)
