package com.example.myapplication.data.local

import android.util.Log
import com.example.myapplication.network.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Карта `String → V` в JSON-файле: общий механизм файловых хранилищ (сезоны, ссылки, манга,
 * куки, здоровье провайдеров…). Формат на диске — тот же, что писали хранилища раньше:
 * JSON-объект, запись через временный файл и rename.
 *
 * Файл читается один раз, лениво ([ensureLoaded]); дальше карта живёт в [flow]. Изменения — через
 * [update] под мьютексом: файл переписывается, только если карта изменилась. Для пачек есть
 * [updateInMemory] + [flush]: несколько правок — одна запись файла.
 */
class JsonMapFileStore<V>(
    private val file: File,
    valueSerializer: KSerializer<V>,
    private val tag: String,
    private val json: Json = AppJson,
    private val logError: (String, Throwable) -> Unit = { message, error -> Log.w(tag, message, error) },
) {
    private val serializer = MapSerializer(String.serializer(), valueSerializer)
    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    @Volatile
    private var dirty = false

    private val state = MutableStateFlow<Map<String, V>>(emptyMap())

    /** Текущая карта. До [ensureLoaded] — пустая. */
    val flow: StateFlow<Map<String, V>> = state.asStateFlow()

    val value: Map<String, V> get() = state.value

    operator fun get(key: String): V? = state.value[key]

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            state.value = withContext(Dispatchers.IO) { read() }
            loaded = true
        }
    }

    /** Меняет карту и сразу пишет файл (если карта изменилась). Возвращает новую карту. */
    suspend fun update(transform: (Map<String, V>) -> Map<String, V>): Map<String, V> {
        ensureLoaded()
        return mutex.withLock {
            val old = state.value
            val new = transform(old)
            if (new != old) {
                state.value = new
                write(new)
            } else if (dirty) {
                write(new)
            }
            new
        }
    }

    /** Меняет карту без записи на диск; записать — [flush]. */
    suspend fun updateInMemory(transform: (Map<String, V>) -> Map<String, V>) {
        ensureLoaded()
        mutex.withLock {
            val old = state.value
            val new = transform(old)
            if (new != old) {
                state.value = new
                dirty = true
            }
        }
    }

    /** Пишет накопленные [updateInMemory] одной записью файла. */
    suspend fun flush() {
        mutex.withLock {
            if (dirty) write(state.value)
        }
    }

    private fun read(): Map<String, V> = runCatching {
        if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
    }.getOrElse {
        logError("Failed to read ${file.name}", it)
        emptyMap()
    }

    private suspend fun write(map: Map<String, V>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            dirty = false
        }.onFailure { logError("Failed to write ${file.name}", it) }
    }
}
