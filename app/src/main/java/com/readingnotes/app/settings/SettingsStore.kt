package com.readingnotes.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.readingnotes.app.model.HighlightPalette
import kotlinx.serialization.json.Json

data class AppSettings(
    val geminiApiKey: String? = null,
    val dropboxCredentialJson: String? = null,
    val bookTitle: String = DEFAULT_BOOK_TITLE,
    val palette: HighlightPalette = HighlightPalette.DEFAULT,
) {
    val hasGeminiKey: Boolean get() = !geminiApiKey.isNullOrBlank()
    val hasDropboxCredential: Boolean get() = !dropboxCredentialJson.isNullOrBlank()

    companion object {
        const val DEFAULT_BOOK_TITLE = "未命名"
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): AppSettings = AppSettings(
        geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, null),
        dropboxCredentialJson = prefs.getString(KEY_DROPBOX_CREDENTIAL_JSON, null),
        bookTitle = prefs.getString(KEY_BOOK_TITLE, AppSettings.DEFAULT_BOOK_TITLE)
            .orEmpty()
            .ifBlank { AppSettings.DEFAULT_BOOK_TITLE },
        palette = readPalette(),
    )

    private fun readPalette(): HighlightPalette {
        val raw = prefs.getString(KEY_HIGHLIGHT_PALETTE, null) ?: return HighlightPalette.DEFAULT
        return runCatching { json.decodeFromString(HighlightPalette.serializer(), raw) }
            .getOrDefault(HighlightPalette.DEFAULT)
    }

    fun savePalette(palette: HighlightPalette) {
        prefs.edit()
            .putString(KEY_HIGHLIGHT_PALETTE, json.encodeToString(HighlightPalette.serializer(), palette))
            .apply()
    }

    fun saveGeminiApiKey(value: String) {
        val trimmed = value.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_GEMINI_API_KEY) else putString(KEY_GEMINI_API_KEY, trimmed)
        }.apply()
    }

    fun saveDropboxCredentialJson(value: String) {
        prefs.edit().putString(KEY_DROPBOX_CREDENTIAL_JSON, value).apply()
    }

    fun clearDropboxCredential() {
        prefs.edit().remove(KEY_DROPBOX_CREDENTIAL_JSON).apply()
    }

    fun saveBookTitle(value: String) {
        val trimmed = value.trim()
        prefs.edit().putString(
            KEY_BOOK_TITLE,
            if (trimmed.isEmpty()) AppSettings.DEFAULT_BOOK_TITLE else trimmed,
        ).apply()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val PREFS_NAME = "secure_settings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_DROPBOX_CREDENTIAL_JSON = "dropbox_credential_json"
        private const val KEY_BOOK_TITLE = "book_title"
        private const val KEY_HIGHLIGHT_PALETTE = "highlight_palette"
    }
}
