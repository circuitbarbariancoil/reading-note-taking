package com.readingnotes.app.ocr

/**
 * Dispatches OCR requests to the configured provider(s), handling fallback
 * and round-robin rotation.
 */
class OcrDispatcher(private val config: ProviderConfig) {

    /** Tracks the last-used index for round-robin mode. */
    private var lastUsedIndex: Int = config.activeIndex

    /**
     * Run OCR using the configured provider(s).
     * Returns the recognized text.
     * Throws [GeminiOcrClient.OcrException] if all providers fail.
     */
    suspend fun ocrPage(jpegBytes: ByteArray): OcrResult {
        val startIndex = if (config.roundRobin) {
            config.nextIndex(lastUsedIndex) ?: config.activeIndex
        } else {
            config.activeIndex
        }

        var currentIndex = startIndex
        val errors = mutableListOf<String>()

        while (true) {
            val provider = config.providers.getOrNull(currentIndex)
                ?: throw GeminiOcrClient.OcrException("No providers configured")

            if (!provider.enabled || provider.apiKey.isBlank()) {
                val nextIdx = config.nextIndex(currentIndex)
                if (nextIdx == null || nextIdx == startIndex) {
                    throw GeminiOcrClient.OcrException(
                        "No usable providers. Errors: ${errors.joinToString("; ")}"
                    )
                }
                currentIndex = nextIdx
                continue
            }

            try {
                val text = callProvider(provider, jpegBytes)
                lastUsedIndex = currentIndex
                return OcrResult(text = text, providerName = provider.name, model = provider.model)
            } catch (e: Exception) {
                errors.add("${provider.name}: ${e.message?.take(80)}")
                if (!config.fallbackOnError) throw e
                val nextIdx = config.nextIndex(currentIndex)
                if (nextIdx == null || nextIdx == startIndex) {
                    throw GeminiOcrClient.OcrException(
                        "All providers failed: ${errors.joinToString("; ")}"
                    )
                }
                currentIndex = nextIdx
            }
        }
    }

    private suspend fun callProvider(provider: LlmProvider, jpegBytes: ByteArray): String {
        return when (provider.protocol) {
            ApiProtocol.Gemini -> GeminiOcrClient(provider.apiKey, provider.model).ocrPage(jpegBytes)
            ApiProtocol.OpenAI -> OpenAiOcrClient(provider.baseUrl, provider.apiKey, provider.model).ocrPage(jpegBytes)
            ApiProtocol.Claude -> ClaudeOcrClient(provider.baseUrl, provider.apiKey, provider.model).ocrPage(jpegBytes)
        }
    }
}

data class OcrResult(
    val text: String,
    val providerName: String,
    val model: String,
)
