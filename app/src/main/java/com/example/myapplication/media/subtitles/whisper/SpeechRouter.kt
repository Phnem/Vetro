package com.example.myapplication.media.subtitles.whisper

import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.ai.AiProvider
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** Распознаватель с уже выбранной моделью: на устройстве или в облаке. */
interface Recognizer {
    /** Входит в ключ кэша: разные модели — разные субтитры. */
    val id: String

    /** Звук уходит в сеть: тяжёлую фоновую работу такой распознаватель не делает. */
    val cloud: Boolean

    /** [pcm] — 16 кГц моно; метки времени — от начала [pcm]. [onProgress] — 0..100. */
    suspend fun transcribe(pcm: FloatArray, language: String?, threads: Int, onProgress: (Int) -> Unit = {}): Transcription

    /** Пословно, для выравнивания с текстом книги; не умеет — отдаёт фразы. */
    suspend fun transcribeWords(pcm: FloatArray, language: String?, threads: Int): Transcription
}

internal class OnDeviceRecognizer(private val engine: SpeechEngine, model: WhisperModel, private val file: File) : Recognizer {
    override val id = model.name
    override val cloud = false
    override suspend fun transcribe(pcm: FloatArray, language: String?, threads: Int, onProgress: (Int) -> Unit) =
        engine.transcribe(file, pcm, language, threads, onProgress)
    override suspend fun transcribeWords(pcm: FloatArray, language: String?, threads: Int) =
        engine.transcribeWords(file, pcm, language, threads)
}

private class CloudRecognizer(private val whisper: OpenRouterWhisper) : Recognizer {
    override val id = OpenRouterWhisper.CACHE_ID
    override val cloud = true
    override suspend fun transcribe(pcm: FloatArray, language: String?, threads: Int, onProgress: (Int) -> Unit) =
        whisper.transcribe(pcm, language, words = false, onProgress)
    override suspend fun transcribeWords(pcm: FloatArray, language: String?, threads: Int) =
        whisper.transcribe(pcm, language, words = true) {}
}

/**
 * Кто распознаёт речь. Скачанная модель на устройстве — всегда первой: без сети, бесплатно, звук не
 * уходит с телефона. Модели нет, а в AI Connect подключён ключ OpenRouter — Whisper Large V3 Turbo
 * через OpenRouter. Нет ни того, ни другого — null, и экран предлагает скачать модель.
 */
class SpeechRouter(
    private val models: WhisperModelStore,
    private val onDevice: SpeechEngine,
    private val cloud: OpenRouterWhisper,
) {
    /** Нативный whisper.cpp есть в сборке и загрузился (arm64). */
    val onDeviceAvailable: Boolean get() = onDevice.isAvailable

    /** Ключ OpenRouter подключён — облачное распознавание доступно. */
    val cloudAvailable: Boolean get() = cloud.isAvailable

    /** То же, реактивно: ключ подключили или убрали в AI Connect. */
    val cloudAvailability: Flow<Boolean> get() = cloud.availability

    /** Первая скачанная модель из [order]; нет ни одной — облако; нет и его — null. */
    fun pick(order: List<WhisperModel>): Recognizer? {
        if (onDevice.isAvailable) {
            order.firstNotNullOfOrNull { m -> models.readyFile(m)?.let { OnDeviceRecognizer(onDevice, m, it) } }?.let { return it }
        }
        return if (cloud.isAvailable) CloudRecognizer(cloud) else null
    }

    /** Строго [model] на устройстве — когда пользователь сам выбрал модель. */
    fun onDevice(model: WhisperModel): Recognizer? =
        models.readyFile(model)?.takeIf { onDevice.isAvailable }?.let { OnDeviceRecognizer(onDevice, model, it) }

    fun cloud(): Recognizer? = if (cloud.isAvailable) CloudRecognizer(cloud) else null
}

/**
 * Whisper Large V3 Turbo через OpenRouter (`POST /audio/transcriptions`, звук base64 в JSON) на ключе
 * BYOK из AI Connect. Звук режется на куски по [PIECE_SECONDS]: у провайдеров за OpenRouter тайм-аут
 * 60 с на запрос, а минута WAV — около 2,5 МБ в base64.
 */
class OpenRouterWhisper(
    private val http: HttpClient,
    private val credentials: AiCredentialsStore,
) {
    val isAvailable: Boolean get() = credentials.isConnected(AiProvider.OPENROUTER)
    val availability: Flow<Boolean> = credentials.connectedProviders.map { AiProvider.OPENROUTER in it }

    suspend fun transcribe(pcm: FloatArray, language: String?, words: Boolean, onProgress: (Int) -> Unit): Transcription {
        val key = credentials.getApiKey(AiProvider.OPENROUTER) ?: error("OpenRouter key is not connected")
        val piece = PIECE_SECONDS * WHISPER_SAMPLE_RATE
        val ranges = (0 until pcm.size step piece).map { it until minOf(pcm.size, it + piece) }
        var detected: String? = null
        val cues = ArrayList<SubtitleCue>()
        ranges.forEachIndexed { i, range ->
            val offsetMs = range.first * 1000L / WHISPER_SAMPLE_RATE
            val lengthMs = (range.last + 1 - range.first) * 1000L / WHISPER_SAMPLE_RATE
            // Обрезок в доли секунды провайдеры отвергают как «слишком короткий звук», а речи в нём нет.
            if (lengthMs < MIN_PIECE_MS) return@forEachIndexed
            val result = request(key, wav(pcm, range), language ?: detected, words, lengthMs)
            detected = detected ?: result.language
            result.cues.mapTo(cues) { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) }
            onProgress((i + 1) * 100 / ranges.size)
        }
        return Transcription(detected, cues)
    }

    private suspend fun request(key: String, wav: ByteArray, language: String?, words: Boolean, lengthMs: Long): Transcription {
        val body = buildJsonObject {
            put("model", MODEL)
            putJsonObject("input_audio") {
                put("data", Base64.getEncoder().encodeToString(wav))
                put("format", "wav")
            }
            if (language != null) put("language", language)
            put("response_format", "verbose_json")
            putJsonArray("timestamp_granularities") { add(if (words) "word" else "segment") }
        }
        var attempt = 0
        while (true) {
            val response = http.post(URL) {
                header("Authorization", "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(AppJson.encodeToString(JsonElement.serializer(), body))
            }
            val text = response.bodyAsText()
            val code = response.status.value
            if (code in 200..299) return parse(text, words, lengthMs)
            // Перегрузка и лимиты — короткая пауза и ещё раз; остальное (нет денег, плохой ключ) — сразу ошибка.
            if ((code == 429 || code >= 500) && attempt < RETRIES) {
                attempt++
                val wait = response.headers["Retry-After"]?.trim()?.toDoubleOrNull()?.times(1000)?.toLong() ?: (2_000L * attempt)
                delay(wait.coerceIn(1_000L, 15_000L))
                continue
            }
            error("OpenRouter transcription: HTTP $code")
        }
    }

    companion object {
        const val MODEL = "openai/whisper-large-v3-turbo"
        const val CACHE_ID = "CLOUD_LARGE_V3_TURBO"
        private const val URL = "https://openrouter.ai/api/v1/audio/transcriptions"
        private const val PIECE_SECONDS = 60
        private const val RETRIES = 2
        private const val MIN_PIECE_MS = 500L

        /** 16-битный PCM WAV, 16 кГц моно. */
        internal fun wav(pcm: FloatArray, range: IntRange): ByteArray {
            val samples = range.last + 1 - range.first
            val data = samples * 2
            val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(WHISPER_SAMPLE_RATE).putInt(WHISPER_SAMPLE_RATE * 2).putShort(2).putShort(16)
            out.put("data".toByteArray()).putInt(data)
            for (i in range) out.putShort((pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            return out.array()
        }

        /**
         * `verbose_json`: реплики из `segments` (или слова из `words`), время в секундах. Провайдер
         * без меток отдаёт только `text` — тогда одна реплика на весь кусок.
         */
        internal fun parse(body: String, words: Boolean, lengthMs: Long): Transcription {
            val root = AppJson.parseToJsonElement(body).jsonObject
            fun JsonObject.ms(name: String) = this[name]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
            fun list(name: String, textField: String) = runCatching { root[name]?.jsonArray }.getOrNull().orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val text = o[textField]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SubtitleCue(o.ms("start") ?: return@mapNotNull null, o.ms("end") ?: return@mapNotNull null, text)
            }
            val cues = (if (words) list("words", "word") else emptyList()).ifEmpty { list("segments", "text") }.ifEmpty {
                root["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(SubtitleCue(0, lengthMs, it)) }.orEmpty()
            }
            return Transcription(isoLanguage(root["language"]?.jsonPrimitive?.contentOrNull), cues)
        }

        /** Провайдеры отдают язык то кодом («en»), то названием («english») — нужен код ISO 639-1. */
        internal fun isoLanguage(raw: String?): String? {
            val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            if (value.length == 2) return value
            return Locale.getISOLanguages().firstOrNull {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) == value
            }
        }
    }
}
