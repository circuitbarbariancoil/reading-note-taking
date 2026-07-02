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
 * OCR client for the Anthropic (Claude) Messages API.
 */
class ClaudeOcrClient(
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

        val imageBlock = JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", "image/jpeg")
                put("data", b64)
            })
        }
        val textBlock = JSONObject().apply {
            put("type", "text")
            put("text", GeminiOcrClient.PROMPT)
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().put(imageBlock).put(textBlock))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("messages", JSONArray().put(userMessage))
        }.toString()

        val url = "${baseUrl.trimEnd('/')}/v1/messages"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GeminiOcrClient.OcrException("Claude HTTP ${resp.code}: ${text.take(200)}")
            }
            parseResponse(text)
        }
    }

    private fun parseResponse(json: String): String {
        val root = JSONObject(json)
        val content = root.optJSONArray("content")
            ?: throw GeminiOcrClient.OcrException("No content in response: ${json.take(200)}")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                sb.append(block.getString("text"))
            }
        }
        return sb.toString().trim()
    }
}
