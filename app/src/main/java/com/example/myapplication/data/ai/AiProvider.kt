package com.example.myapplication.data.ai

import androidx.annotation.DrawableRes
import com.phnem.vetro.R

/**
 * Как ключ передаётся провайдеру при HTTP-запросе.
 */
enum class AiAuthScheme {
    /** `Authorization: Bearer <key>` — OpenAI-совместимые и Cohere. */
    BEARER,

    /** `x-api-key: <key>` (+ `anthropic-version`) — Anthropic. */
    X_API_KEY,

    /**
     * `x-goog-api-key: <key>` — Google Gemini. Раньше ключ шёл в query (`?key=`) и вместе с URL
     * попадал в логи HTTP-клиента; заголовок Gemini принимает так же.
     */
    GOOG_API_KEY_HEADER,
}

/**
 * Форма API для запроса и парсинга ответа. Влияет на тело chat-запроса
 * и на путь «дешёвого» пинга (список моделей) для гонки хостов.
 */
enum class AiApiStyle {
    /** `POST /chat/completions`, `GET /models` — OpenAI, DeepSeek, Groq, OpenRouter. */
    OPENAI,

    /** `POST /messages`, `GET /models` — Anthropic. */
    ANTHROPIC,

    /** `POST /models/{model}:generateContent`, `GET /models` — Gemini. */
    GEMINI,

    /** `POST /chat`, `GET /models` — Cohere v2. */
    COHERE,
}

/**
 * Единый реестр AI-провайдеров для BYOK ("AI Connect").
 *
 * Метаданные централизованы здесь: детекция по префиксу ключа ([AiKeyDetector]),
 * валидация/чат ([com.example.myapplication.data.ai.AiLlmEndpoint]), гонка хостов и
 * шифрованное хранение/sync ключей опираются на эти поля.
 *
 * @property id стабильный строковый идентификатор — ключ в хранилище и колонка `provider`
 *   в Supabase. Не переименовывать после релиза.
 * @property validKeyPrefixes префиксы ключа для локальной детекции. Пусто => префикса нет
 *   (детектится валидацией/фоллбэком, напр. Cohere).
 * @property baseUrl корень API без завершающего слэша.
 * @property chatPath путь chat-эндпоинта относительно [baseUrl].
 * @property modelsPath «дешёвый» путь для проверки работоспособности/латентности (список моделей).
 * @property defaultModel модель по умолчанию для текстовых запросов от лица провайдера.
 * @property supportsVision умеет ли [defaultModel] принимать изображения (мультимодальность).
 *   Используется Visual Search: подходят только vision-capable провайдеры.
 * @property visionModel модель для image-запросов (если отличается от [defaultModel]); null => [defaultModel].
 * @property apiKeyUrl страница, где пользователь берёт ключ ("Get API Key").
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val validKeyPrefixes: List<String>,
    @DrawableRes val iconRes: Int,
    val authScheme: AiAuthScheme,
    val apiStyle: AiApiStyle,
    val baseUrl: String,
    val chatPath: String,
    val modelsPath: String,
    val defaultModel: String,
    val supportsVision: Boolean,
    val apiKeyUrl: String,
    val visionModel: String? = null,
) {
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        validKeyPrefixes = listOf("sk-"),
        iconRes = R.drawable.ic_ai_openai,
        authScheme = AiAuthScheme.BEARER,
        apiStyle = AiApiStyle.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        defaultModel = "gpt-5-mini",
        supportsVision = true,
        apiKeyUrl = "https://platform.openai.com/api-keys",
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        validKeyPrefixes = listOf("sk-ant-"),
        iconRes = R.drawable.ic_ai_claude,
        authScheme = AiAuthScheme.X_API_KEY,
        apiStyle = AiApiStyle.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        chatPath = "/messages",
        modelsPath = "/models",
        defaultModel = "claude-haiku-4-5",
        supportsVision = true,
        apiKeyUrl = "https://console.anthropic.com/settings/keys",
    ),
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
        validKeyPrefixes = listOf("AIza", "AQ."),
        iconRes = R.drawable.ic_ai_gemini,
        authScheme = AiAuthScheme.GOOG_API_KEY_HEADER,
        apiStyle = AiApiStyle.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        chatPath = "/models",
        modelsPath = "/models",
        defaultModel = "gemini-3.1-flash-lite",
        supportsVision = true,
        apiKeyUrl = "https://aistudio.google.com/app/apikey",
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        validKeyPrefixes = listOf("sk-"),
        iconRes = R.drawable.ic_ai_deepseek,
        authScheme = AiAuthScheme.BEARER,
        apiStyle = AiApiStyle.OPENAI,
        baseUrl = "https://api.deepseek.com",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        defaultModel = "deepseek-v4-flash",
        supportsVision = false,
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        validKeyPrefixes = listOf("gsk_"),
        iconRes = R.drawable.ic_ai_groq,
        authScheme = AiAuthScheme.BEARER,
        apiStyle = AiApiStyle.OPENAI,
        baseUrl = "https://api.groq.com/openai/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        // Llama 4 Scout — мультимодальная (текст+картинки): одна модель на все запросы.
        defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        supportsVision = true,
        apiKeyUrl = "https://console.groq.com/keys",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        validKeyPrefixes = listOf("sk-or-"),
        iconRes = R.drawable.ic_ai_openrouter,
        authScheme = AiAuthScheme.BEARER,
        apiStyle = AiApiStyle.OPENAI,
        baseUrl = "https://openrouter.ai/api/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        defaultModel = "openai/gpt-5-mini",
        supportsVision = true,
        apiKeyUrl = "https://openrouter.ai/keys",
    ),
    COHERE(
        id = "cohere",
        displayName = "Cohere",
        validKeyPrefixes = emptyList(),
        iconRes = R.drawable.ic_ai_cohere,
        authScheme = AiAuthScheme.BEARER,
        apiStyle = AiApiStyle.COHERE,
        baseUrl = "https://api.cohere.com/v2",
        chatPath = "/chat",
        modelsPath = "/models",
        // Command A Vision — мультимодальная (текст+картинки): одна модель на все запросы.
        defaultModel = "command-a-vision-07-2025",
        supportsVision = true,
        apiKeyUrl = "https://dashboard.cohere.com/api-keys",
    ),
    ;

    /** Модель для мультимодальных (image) запросов. */
    val effectiveVisionModel: String get() = visionModel ?: defaultModel

    companion object {
        /** Провайдер по стабильному [id], либо null. */
        fun fromId(id: String?): AiProvider? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        /** Провайдеры, чьи модели принимают изображения — кандидаты для Visual Search. */
        val visionCapable: List<AiProvider> get() = entries.filter { it.supportsVision }
    }
}
