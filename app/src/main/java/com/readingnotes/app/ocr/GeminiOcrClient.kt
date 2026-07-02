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
 * Strict vertical-Japanese OCR via the Gemini API.
 *
 * The prompt and 768px/JPEG pipeline were validated in Spike 1: reading order,
 * ruby and old-glyph forms transcribe faithfully. Known limitation: the model
 * may confidently substitute rare kanji and fabricate ruby, so the source image
 * must be kept and extracted entries remain editable for proofreading.
 */
class GeminiOcrClient(
    private val apiKey: String,
    private val model: String = "gemini-3-flash-preview",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Runs OCR on a JPEG-encoded page image (already downscaled to ~768px). */
    suspend fun ocrPage(jpegBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", b64)
                        })
                    })
                    put(JSONObject().put("text", PROMPT))
                })
            }))
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) {
                    throw RateLimitException("Gemini API 限流 (429)，请稍后重试")
                }
                throw OcrException("Gemini HTTP ${resp.code}: $text")
            }
            parseText(text)
        }
    }

    private fun parseText(json: String): String {
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates")
            ?: throw OcrException("No candidates in response: $json")
        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        return sb.toString().trim()
    }

    class OcrException(message: String) : Exception(message)
    class RateLimitException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_MODEL = "gemini-3-flash-preview"

        /** Strict prompt; mirrors the validated Spike 1 prompt. */
        val PROMPT = """
            あなたは日本語の縦書き書籍ページの厳密なOCRエンジンです。
            この画像は日本語の小説のページです。縦書き・右から左の段組みです。

            絶対ルール:
            1. 本文を一字一句そのまま忠実に書き起こす。縦書きの読み順（各段は上から下、段は右から左）に従う。
            2. 旧字体・異体字・送り仮名は画像の通り保持し、新字体に変換しない。
            3. 画像に実際に印刷されているルビ（振り仮名）だけを写す。ルビが無い漢字に推測でルビを付けない。
            4. 判読できない文字は推測で別の字に置き換えず 〓 で示す。
            5. 補完・要約・翻訳・修正をしない。
            6. ルビは 漢字《ルビ》 の形式で本文中に挿入する。
            7. 改行の扱い: 組版の都合で段落の途中で折り返された行はつなげて一つの段落にし、途中に改行を入れない。段落の区切り（字下げ・行頭の一字下げ・空行など）だけを、空行一つ（改行二つ）で保持する。
            8. ページ番号は末尾に [非本文: p.数字] の形式のみで出力する（例: [非本文: p.42]）。柱（作品名）は省略する。
            9. 出力は本文テキストのみ。
        """.trimIndent()
    }
}
