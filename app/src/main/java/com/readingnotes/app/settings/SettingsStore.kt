package com.readingnotes.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.readingnotes.app.model.HighlightPalette
import com.readingnotes.app.ocr.LlmProvider
import com.readingnotes.app.ocr.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Portable settings snapshot for backup/restore (plain JSON, not encrypted). */
@Serializable
data class ExportedSettings(
    val geminiApiKey: String? = null,
    val dropboxCredentialJson: String? = null,
    val bookTitle: String = AppSettings.DEFAULT_BOOK_TITLE,
    val palette: HighlightPalette = HighlightPalette.DEFAULT,
    val apiUsage: ApiUsageStats = ApiUsageStats(),
    val providerApiUsage: Map<String, ProviderUsageStats> = emptyMap(),
    val maxOcrRetries: Int = 3,
    val monthlyApiBudget: Int = 0,
    val providerConfig: ProviderConfig = ProviderConfig(),
)

@Serializable
data class ApiUsageStats(
    val totalCalls: Int = 0,
    val monthCalls: Int = 0,
    val monthKey: String = "",
    val lastCallAt: String? = null,
)

@Serializable
data class ProviderUsageStats(
    val totalCalls: Int = 0,
    val monthCalls: Int = 0,
    val monthKey: String = "",
    val lastCallAt: String? = null,
)

data class AppSettings(
    val geminiApiKey: String? = null,
    val dropboxCredentialJson: String? = null,
    val bookTitle: String = DEFAULT_BOOK_TITLE,
    val palette: HighlightPalette = HighlightPalette.DEFAULT,
    val apiUsage: ApiUsageStats = ApiUsageStats(),
    val providerApiUsage: Map<String, ProviderUsageStats> = emptyMap(),
    val maxOcrRetries: Int = 3,
    val monthlyApiBudget: Int = 0,
    val providerConfig: ProviderConfig = ProviderConfig(),
) {
    val hasGeminiKey: Boolean get() = !geminiApiKey.isNullOrBlank()
    val hasDropboxCredential: Boolean get() = !dropboxCredentialJson.isNullOrBlank()
    val hasAnyProvider: Boolean get() = providerConfig.providers.any { it.apiKey.isNotBlank() }

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

    fun read(): AppSettings {
        val providerConfig = readProviderConfig()
        // Migrate legacy geminiApiKey into providers if providers are empty
        val legacyKey = prefs.getString(KEY_GEMINI_API_KEY, null)
        val effectiveConfig = if (providerConfig.providers.isEmpty() && !legacyKey.isNullOrBlank()) {
            ProviderConfig(
                providers = listOf(LlmProvider.geminiDefault(legacyKey)),
                activeIndex = 0,
                fallbackOnError = true,
            )
        } else {
            providerConfig
        }
        return AppSettings(
            geminiApiKey = legacyKey,
            dropboxCredentialJson = prefs.getString(KEY_DROPBOX_CREDENTIAL_JSON, null),
            bookTitle = prefs.getString(KEY_BOOK_TITLE, AppSettings.DEFAULT_BOOK_TITLE)
                .orEmpty()
                .ifBlank { AppSettings.DEFAULT_BOOK_TITLE },
            palette = readPalette(),
            apiUsage = readApiUsage(),
            providerApiUsage = readProviderApiUsage(),
            maxOcrRetries = prefs.getInt(KEY_MAX_OCR_RETRIES, 3).coerceIn(0, 10),
            monthlyApiBudget = prefs.getInt(KEY_MONTHLY_API_BUDGET, 0).coerceAtLeast(0),
            providerConfig = effectiveConfig,
        )
    }

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

    fun saveApiUsage(stats: ApiUsageStats) {
        prefs.edit()
            .putString(KEY_API_USAGE, json.encodeToString(ApiUsageStats.serializer(), stats))
            .apply()
    }

    @Synchronized
    fun recordApiCall(providerId: String? = null) {
        val current = readApiUsage()
        val monthKey = YearMonth.now(ZoneOffset.UTC).toString()
        val resetMonthCalls = if (current.monthKey == monthKey) current.monthCalls else 0
        val timestamp = Instant.now().toString()
        val updatedUsage = current.copy(
            totalCalls = current.totalCalls + 1,
            monthCalls = resetMonthCalls + 1,
            monthKey = monthKey,
            lastCallAt = timestamp,
        )
        saveApiUsage(updatedUsage)
        if (providerId != null) {
            val providerUsage = readProviderApiUsage().toMutableMap()
            val providerCurrent = providerUsage[providerId] ?: ProviderUsageStats()
            val providerResetMonthCalls = if (providerCurrent.monthKey == monthKey) providerCurrent.monthCalls else 0
            providerUsage[providerId] = providerCurrent.copy(
                totalCalls = providerCurrent.totalCalls + 1,
                monthCalls = providerResetMonthCalls + 1,
                monthKey = monthKey,
                lastCallAt = timestamp,
            )
            saveProviderApiUsage(providerUsage)
        }
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

    fun saveProviderConfig(config: ProviderConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER_CONFIG, json.encodeToString(ProviderConfig.serializer(), config))
            .apply()
    }

    fun saveMaxOcrRetries(value: Int) {
        prefs.edit().putInt(KEY_MAX_OCR_RETRIES, value.coerceIn(0, 10)).apply()
    }

    fun saveMonthlyApiBudget(value: Int) {
        prefs.edit().putInt(KEY_MONTHLY_API_BUDGET, value.coerceAtLeast(0)).apply()
    }

    private fun readProviderConfig(): ProviderConfig {
        val raw = prefs.getString(KEY_PROVIDER_CONFIG, null) ?: return ProviderConfig()
        return runCatching { json.decodeFromString(ProviderConfig.serializer(), raw) }
            .getOrDefault(ProviderConfig())
    }

    private fun readApiUsage(): ApiUsageStats {
        val raw = prefs.getString(KEY_API_USAGE, null) ?: return ApiUsageStats()
        return runCatching { json.decodeFromString(ApiUsageStats.serializer(), raw) }
            .getOrDefault(ApiUsageStats())
    }

    private fun readProviderApiUsage(): Map<String, ProviderUsageStats> {
        val raw = prefs.getString(KEY_PROVIDER_API_USAGE, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), ProviderUsageStats.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    private fun saveProviderApiUsage(stats: Map<String, ProviderUsageStats>) {
        prefs.edit()
            .putString(KEY_PROVIDER_API_USAGE, json.encodeToString(MapSerializer(String.serializer(), ProviderUsageStats.serializer()), stats))
            .apply()
    }

    /** Export all settings as a plain-text JSON string for backup. */
    fun exportSettingsJson(): String {
        val settings = read()
        val exported = ExportedSettings(
            geminiApiKey = settings.geminiApiKey,
            dropboxCredentialJson = settings.dropboxCredentialJson,
            bookTitle = settings.bookTitle,
            palette = settings.palette,
            apiUsage = settings.apiUsage,
            providerApiUsage = settings.providerApiUsage,
            maxOcrRetries = settings.maxOcrRetries,
            monthlyApiBudget = settings.monthlyApiBudget,
            providerConfig = settings.providerConfig,
        )
        return json.encodeToString(ExportedSettings.serializer(), exported)
    }

    /** Import settings from a previously exported JSON string. */
    fun importSettingsJson(jsonStr: String) {
        val imported = json.decodeFromString(ExportedSettings.serializer(), jsonStr)
        imported.geminiApiKey?.let { saveGeminiApiKey(it) }
        imported.dropboxCredentialJson?.let { saveDropboxCredentialJson(it) }
        saveBookTitle(imported.bookTitle)
        savePalette(imported.palette)
        saveApiUsage(imported.apiUsage)
        saveProviderApiUsage(imported.providerApiUsage)
        saveMaxOcrRetries(imported.maxOcrRetries)
        saveMonthlyApiBudget(imported.monthlyApiBudget)
        saveProviderConfig(imported.providerConfig)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val PREFS_NAME = "secure_settings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_DROPBOX_CREDENTIAL_JSON = "dropbox_credential_json"
        private const val KEY_BOOK_TITLE = "book_title"
        private const val KEY_HIGHLIGHT_PALETTE = "highlight_palette"
        private const val KEY_API_USAGE = "api_usage"
        private const val KEY_PROVIDER_API_USAGE = "provider_api_usage"
        private const val KEY_MAX_OCR_RETRIES = "max_ocr_retries"
        private const val KEY_MONTHLY_API_BUDGET = "monthly_api_budget"
        private const val KEY_PROVIDER_CONFIG = "provider_config"
    }
}
