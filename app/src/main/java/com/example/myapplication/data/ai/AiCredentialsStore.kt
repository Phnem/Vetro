package com.example.myapplication.data.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Локальное шифрованное хранилище API-ключей для всех [AiProvider] ("AI Connect").
 *
 * Один ключ на провайдера (per-provider), поверх [EncryptedSharedPreferences]
 * (AES256-SIV для имён, AES256-GCM для значений) — как в
 * [com.example.myapplication.data.local.EncryptedGeminiApiKeyRepository], но обобщённо.
 *
 * [connectedProviders] реактивно отражает набор провайдеров с непустым ключом —
 * для отрисовки списка «плашек» в шторке AI Connect.
 */
class AiCredentialsStore(
    context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _connectedProviders = MutableStateFlow(loadConnected())

    /** Провайдеры с сохранённым ключом (в порядке объявления enum). */
    val connectedProviders: StateFlow<List<AiProvider>> = _connectedProviders.asStateFlow()

    init {
        migrateLegacyGeminiKey(context)
    }

    fun getApiKey(provider: AiProvider): String? =
        prefs.getString(keyOf(provider), null)?.takeIf { it.isNotBlank() }

    fun getApiKey(providerId: String): String? =
        AiProvider.fromId(providerId)?.let { getApiKey(it) }

    /** true, если у провайдера есть непустой ключ. */
    fun isConnected(provider: AiProvider): Boolean = !getApiKey(provider).isNullOrBlank()

    /** Все провайдеры с сохранённым ключом (снапшот). */
    fun getAllConnectedProviders(): List<AiProvider> = _connectedProviders.value

    suspend fun saveApiKey(provider: AiProvider, apiKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = apiKey.trim()
                require(normalized.isNotEmpty()) { "Empty API key" }
                prefs.edit().putString(keyOf(provider), normalized).apply()
                refreshConnected()
            }
        }

    suspend fun clearApiKey(provider: AiProvider): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                prefs.edit().remove(keyOf(provider)).apply()
                refreshConnected()
            }
        }

    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            prefs.edit().apply {
                AiProvider.entries.forEach { remove(keyOf(it)) }
            }.apply()
            refreshConnected()
        }
    }

    /**
     * Быстрая (без сети) проверка формата: префикс из реестра, либо непустая строка
     * для провайдеров без известного префикса (Cohere). Не гарантирует работоспособность —
     * это делает сетевая валидация в [AiLlmEndpoint].
     */
    fun isFormatPlausible(provider: AiProvider, apiKey: String): Boolean {
        val key = apiKey.trim()
        if (key.isEmpty()) return false
        val prefixes = provider.validKeyPrefixes
        return prefixes.isEmpty() || prefixes.any { key.startsWith(it) }
    }

    /**
     * Одноразовый перенос старого ключа Visual Search (Gemini BYOK) в центральное хранилище.
     * Если Gemini уже подключён в AI Connect — ничего не делаем; иначе читаем legacy-файл
     * `secure_gemini_prefs` и переносим ключ под провайдера [AiProvider.GEMINI].
     */
    private fun migrateLegacyGeminiKey(context: Context) {
        // Флаг в обычных настройках: без него у всех, кто не подключал Gemini, КАЖДОЕ создание
        // хранилища открывало второе шифрованное хранилище (инициализация Tink-ключей) впустую.
        val flags = context.getSharedPreferences(MIGRATION_FLAGS_FILE, Context.MODE_PRIVATE)
        if (flags.getBoolean(LEGACY_GEMINI_CHECKED, false)) return
        try {
            migrateLegacyGeminiKeyOnce(context)
        } finally {
            flags.edit().putBoolean(LEGACY_GEMINI_CHECKED, true).apply()
        }
    }

    private fun migrateLegacyGeminiKeyOnce(context: Context) {
        if (!getApiKey(AiProvider.GEMINI).isNullOrBlank()) return
        val legacy = runCatching {
            val legacyPrefs = EncryptedSharedPreferences.create(
                context,
                LEGACY_GEMINI_PREF_FILE,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            legacyPrefs.getString(LEGACY_GEMINI_KEY, null)
        }.getOrNull()?.trim()
        if (!legacy.isNullOrEmpty()) {
            prefs.edit().putString(keyOf(AiProvider.GEMINI), legacy).apply()
            refreshConnected()
        }
    }

    private fun refreshConnected() {
        _connectedProviders.value = loadConnected()
    }

    private fun loadConnected(): List<AiProvider> =
        AiProvider.entries.filter { !prefs.getString(keyOf(it), null).isNullOrBlank() }

    private fun keyOf(provider: AiProvider): String = "$KEY_PREFIX${provider.id}"

    private companion object {
        const val PREF_FILE = "secure_ai_keys"
        const val KEY_PREFIX = "ai_key_"
        const val LEGACY_GEMINI_PREF_FILE = "secure_gemini_prefs"
        const val MIGRATION_FLAGS_FILE = "ai_credentials_migration"
        const val LEGACY_GEMINI_CHECKED = "legacy_gemini_checked_v1"
        const val LEGACY_GEMINI_KEY = "gemini_api_key"
    }
}
