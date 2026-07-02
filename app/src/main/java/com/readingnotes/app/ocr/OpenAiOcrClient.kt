package com.readingnotes.app.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OCR client using the OpenAI-compatible chat completions API.
 * Works with OpenAI, OpenRouter, Together, Groq, local Ollama, etc.
 */
class OpenAiOcrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun ocrPage(jpegBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)

        val imageContent = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$b64")
            })
        }
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", GeminiOcrClient.PROMPT)
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().put(imageContent).put(textContent))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(userMessage))
            put("max_tokens", 4096)
        }.toString()

        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GeminiOcrClient.OcrException("OpenAI HTTP ${resp.code}: ${text.take(200)}")
            }
            parseResponse(text)
        }
    }

    private fun parseResponse(json: String): String {
        val root = JSONObject(json)
        val choices = root.optJSONArray("choices")
            ?: throw GeminiOcrClient.OcrException("No choices in response: ${json.take(200)}")
        return choices.getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}
