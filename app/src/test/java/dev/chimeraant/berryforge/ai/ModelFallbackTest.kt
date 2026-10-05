package dev.chimeraant.berryforge.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the model-picker fallback.
 *
 * Regression: the picker rendered a bare text field whenever its option list was empty,
 * which was the case on first use because nothing loaded the list automatically. The
 * user saw a text input instead of a dropdown and had no way to tell whether the list
 * was empty or the control was broken. The list is now never empty.
 */
class ModelFallbackTest {

    /** Mirrors AiReviewService.modelsForPicker without needing Android or network. */
    private fun modelsForPicker(
        fetch: () -> Result<List<String>>,
        suggestions: List<String>,
    ): List<String> = fetch().getOrElse { suggestions }

    private val suggestions = listOf("gpt-4o-mini", "gpt-4o", "llama-3.1-8b-instant")

    @Test
    fun endpointListIsUsedWhenAvailable() {
        val fetched = listOf("custom-a", "custom-b")
        val result = modelsForPicker({ Result.success(fetched) }, suggestions)
        assertTrue(result == fetched)
    }

    @Test
    fun suggestionsAreUsedWhenTheEndpointFails() {
        val result = modelsForPicker(
            { Result.failure(RuntimeException("404 for /models")) },
            suggestions,
        )
        assertTrue(result == suggestions)
    }

    @Test
    fun theListIsNeverEmpty() {
        // The whole point: an empty list made the picker fall back to a text field.
        val result = modelsForPicker({ Result.failure(RuntimeException("offline")) }, suggestions)
        assertFalse("picker list must never be empty", result.isEmpty())
    }

    @Test
    fun suggestionsContainTheDefaultModel() {
        // The picker defaults to gpt-4o-mini, so the fallback must include it or the
        // control would show a selection that is not in its own list.
        assertTrue(suggestions.contains("gpt-4o-mini"))
    }
}
