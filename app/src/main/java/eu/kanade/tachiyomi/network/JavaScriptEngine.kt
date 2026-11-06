package eu.kanade.tachiyomi.network

import android.content.Context

/**
 * JS-движок для расширений, которые расшифровывают страницы скриптом сайта.
 *
 * VETRO-TAIL[mihon-js]: Mihon кладёт сюда QuickJS (`quickjs-android`, нативная библиотека ~1 МБ на
 * ABI). Пока движка нет, такие источники падают на этом вызове с понятной ошибкой; остальные
 * расширения работают. Подключить — зависимость quickjs + реализация evaluate.
 */
class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun <T> evaluate(script: String): T =
        throw UnsupportedOperationException("JavaScript engine is not available in Vetro yet")
}
