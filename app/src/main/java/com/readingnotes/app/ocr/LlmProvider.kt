package com.readingnotes.app.ocr

import kotlinx.serialization.Serializable

/** Supported API protocol types. */
@Serializable
enum class ApiProtocol {
    Gemini,
    OpenAI,
    Claude,
}

/**
 * A configured LLM provider for OCR.
 *
 * Users can add any number of providers. The app dispatches OCR requests to
 * the active provider (or rotates through them when round-robin is enabled).
 */
@Serializable
data class LlmProvider(
    val id: String,
    val name: String,
    val protocol: ApiProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true,
) {
    companion object {
        fun geminiDefault(apiKey: String = "") = LlmProvider(
            id = "default-gemini",
            name = "Gemini Flash",
            protocol = ApiProtocol.Gemini,
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = apiKey,
            model = "gemini-3-flash-preview",
        )

        fun openAiDefault() = LlmProvider(
            id = "default-openai",
            name = "OpenAI",
            protocol = ApiProtocol.OpenAI,
            baseUrl = "https://api.openai.com",
            apiKey = "",
            model = "gpt-4o",
        )

        fun claudeDefault() = LlmProvider(
            id = "default-claude",
            name = "Claude",
            protocol = ApiProtocol.Claude,
            baseUrl = "https://api.anthropic.com",
            apiKey = "",
            model = "claude-sonnet-4-20250514",
        )
    }
}

/**
 * How the app picks which provider to call.
 */
@Serializable
data class ProviderConfig(
    val providers: List<LlmProvider> = emptyList(),
    val activeIndex: Int = 0,
    val fallbackOnError: Boolean = true,
    val roundRobin: Boolean = false,
) {
    val activeProvider: LlmProvider?
        get() = providers.getOrNull(activeIndex)?.takeIf { it.enabled }

    /** Next provider to try (round-robin or fallback). */
    fun nextIndex(currentIndex: Int): Int? {
        val n = providers.size
        for (offset in 1 until n) {
            val candidate = (currentIndex + offset) % n
            if (providers[candidate].enabled && providers[candidate].apiKey.isNotBlank()) {
                return candidate
            }
        }
        return null
    }
}
