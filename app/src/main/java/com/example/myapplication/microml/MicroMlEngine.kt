package com.example.myapplication.microml

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MicroML Engine — три независимых пакета локальных моделей поверх общего энкодера названий.
 *
 * Правила, которые здесь зашиты и не обходятся вызывающим кодом:
 *  - модели только оценивают; удалять, объединять и перезаписывать данные они не могут;
 *  - по умолчанию всё работает в теневом режиме: результат считается и попадает в журнал наблюдений,
 *    а решает прежний алгоритм. Действовать разрешает только явный флаг [Feature];
 *  - нет пакета, повреждён файл, не та версия или модель упала — вызывающий получает null и идёт по старому пути;
 *  - у каждого пакета свой выключатель и свой откат на предыдущую версию.
 * Загрузка идёт на IO, названия из коллекции никуда не отправляются и в журнал не пишутся.
 */
class MicroMlEngine(context: Context) {
    enum class Pack(val key: String) { IDENTITY("identity"), CATALOG("catalog"), EXPERIENCE("experience") }

    /** Сценарии, в которых модели разрешено влиять на результат. Пока флаг выключен — только наблюдение. */
    enum class Feature(val pack: Pack, val key: String) {
        IDENTITY_SEARCH_RERANK(Pack.IDENTITY, "identity.search_rerank"),
        CATALOG_GENRE_FALLBACK(Pack.CATALOG, "catalog.genre_fallback"),
        EXPERIENCE_GENRE_SMOOTHING(Pack.EXPERIENCE, "experience.genre_smoothing"),
        EXPERIENCE_TASTE_RANK(Pack.EXPERIENCE, "experience.taste_rank"),
    }

    enum class PackState { READY, DISABLED, MISSING, SUSPENDED }
    data class PackStatus(val pack: Pack, val state: PackState, val version: String?, val rolledBack: Boolean)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("microml", Context.MODE_PRIVATE)
    private val source = MicroAssetSource { path -> app.assets.open(path) }
    private val mutex = Mutex()
    private val loaded = HashMap<String, Any>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val observations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Куда отдавать жанровые строки источников, которые не узнал ни прежний алгоритм, ни модель.
     * Пусто, пока не включён добровольный сбор таблиц (microml/contribution).
     */
    @Volatile var genreSink: ((String) -> Unit)? = null
    private val rankers = HashMap<String, TasteRanker>()
    private val controls = MutableStateFlow(0)
    val controlRevision = controls.asStateFlow()

    /** По умолчанию модель выключена: она включается тумблером в настройках («Умные модели»). */
    fun isPackEnabled(pack: Pack): Boolean = prefs.getBoolean("pack.${pack.key}.enabled", false)
    fun setPackEnabled(pack: Pack, enabled: Boolean) {
        prefs.edit().putBoolean("pack.${pack.key}.enabled", enabled).apply()
        controls.update { it + 1 }
    }

    /**
     * Тумблер пакета в настройках: включает пакет и все его сценарии разом (порядок поиска, жанры, рекомендации) или выключает всё.
     * Отдельные сценарии и откат остаются в разделе «Разработчик» для тонкой настройки.
     */
    fun isSwitchedOn(pack: Pack): Boolean = isPackEnabled(pack) && Feature.values().filter { it.pack == pack }.all(::isRequested)
    fun setSwitchedOn(pack: Pack, on: Boolean) {
        val editor = prefs.edit().putBoolean("pack.${pack.key}.enabled", on)
        Feature.values().filter { it.pack == pack }.forEach { editor.putBoolean("feature.${it.key}", on) }
        editor.apply()
        controls.update { it + 1 }
    }

    /** Откат: пакет берётся из папки previous; если её нет в сборке — пакет просто не загрузится, и работает прежний алгоритм. */
    fun isRolledBack(pack: Pack): Boolean = prefs.getBoolean("pack.${pack.key}.rollback", false)
    fun setRolledBack(pack: Pack, rolledBack: Boolean) {
        prefs.edit().putBoolean("pack.${pack.key}.rollback", rolledBack).apply()
        controls.update { it + 1 }
    }

    fun isActive(feature: Feature): Boolean = isUsable(feature.pack) && isRequested(feature)
    fun isRequested(feature: Feature): Boolean = prefs.getBoolean("feature.${feature.key}", false)
    fun setActive(feature: Feature, active: Boolean) { prefs.edit().putBoolean("feature.${feature.key}", active).apply() }

    private fun isUsable(pack: Pack): Boolean = isPackEnabled(pack) && (failures[root(pack)]?.get() ?: 0) < MAX_FAILURES
    private fun root(pack: Pack): String = "microml/${pack.key}" + if (isRolledBack(pack)) "/previous" else ""

    private suspend fun <T : Any> load(pack: Pack, build: (String) -> T?): T? {
        if (!isUsable(pack)) return null
        val key = root(pack)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isUsable(pack) || key != root(pack)) return@withLock null
                val cached = loaded[key]
                if (cached === MISSING) return@withLock null
                @Suppress("UNCHECKED_CAST")
                if (cached != null) return@withLock cached as T
                val result = attempt { build(key) } ?: MISSING
                loaded[key] = result
                @Suppress("UNCHECKED_CAST")
                if (result === MISSING) null else result as T
            }
        }
    }

    // Called under the package loader's mutex. Previous packs may carry their own encoder.
    private fun coreFor(packRoot: String): CorePack? {
        fun at(path: String): CorePack? = loaded.getOrPut(path) {
            attempt { MicroPacks.loadCore(source, path) } ?: MISSING
        } as? CorePack
        return if (packRoot.endsWith("/previous")) at("$packRoot/core") ?: at("microml/core") else at("microml/core")
    }

    suspend fun identity(): IdentityModel? = load(Pack.IDENTITY) { root ->
        coreFor(root)?.let { MicroPacks.loadIdentity(source, it, root) }
    }
    suspend fun catalog(): CatalogModels? = load(Pack.CATALOG) { root ->
        coreFor(root)?.let { MicroPacks.loadCatalog(source, it, root) }
    }
    suspend fun experience(): ExperienceModels? = load(Pack.EXPERIENCE) { root -> ExperienceModels.load(source, root) }

    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Выполняет расчёт модели, не давая её ошибке дойти до приложения.
     * После [MAX_FAILURES] сбоев пакет приостанавливается до перезапуска процесса.
     */
    fun <T> guarded(pack: Pack, block: () -> T): T? {
        if (!isUsable(pack)) return null
        val key = root(pack)
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (failures.getOrPut(key) { AtomicInteger() }.incrementAndGet() == MAX_FAILURES) controls.update { it + 1 }
            observe("${pack.key}:failure")
            null
        }
    }

    /** Счётчик теневого журнала. Ключ — только код события, без пользовательских данных. */
    fun observe(key: String, count: Long = 1) { observations.getOrPut(key) { AtomicLong() }.addAndGet(count) }
    fun shadowCounts(): Map<String, Long> = observations.mapValues { it.value.get() }

    suspend fun status(): List<PackStatus> = Pack.values().map { pack ->
        val version = when (pack) {
            Pack.IDENTITY -> identity()?.version
            Pack.CATALOG -> catalog()?.version
            Pack.EXPERIENCE -> experience()?.version
        }
        val state = when {
            !isPackEnabled(pack) -> PackState.DISABLED
            (failures[root(pack)]?.get() ?: 0) >= MAX_FAILURES -> PackState.SUSPENDED
            version == null -> PackState.MISSING
            else -> PackState.READY
        }
        PackStatus(pack, state, version, isRolledBack(pack))
    }

    private fun tasteFile(key: String) = AtomicFile(File(app.filesDir, "microml/taste-$key.json"))
    private fun tasteKey(type: String, version: String) = "${type.lowercase()}-${version.replace(Regex("[^a-zA-Z0-9.-]"), "_")}"

    /** Обучаемый ранжировщик для типа контента (ANIME, MOVIE, TV). Состояние живёт в файле приложения. */
    suspend fun tasteRanker(type: String): TasteRanker? {
        val models = experience() ?: return null
        return withContext(Dispatchers.IO) {
            synchronized(rankers) {
                val key = tasteKey(type, models.version)
                rankers[key] ?: run {
                    val state = attempt { ExperienceModels.decodeState(tasteFile(key).readFully().toString(Charsets.UTF_8)) } ?: TasteState()
                    models.newRanker(state)?.also { rankers[key] = it }
                }
            }
        }
    }

    suspend fun saveTaste(ranker: TasteRanker) = withContext(Dispatchers.IO) {
        synchronized(rankers) {
            val key = rankers.entries.firstOrNull { it.value === ranker }?.key ?: return@synchronized
            attempt {
                val file = tasteFile(key)
                val bytes = ExperienceModels.encodeState(ranker.state()).toByteArray()
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }
            Unit
        }
    }

    /** Сбрасывает выученное состояние: возврат к исходным весам рекомендаций. */
    suspend fun resetTaste() = withContext(Dispatchers.IO) {
        synchronized(rankers) {
            rankers.clear()
            File(app.filesDir, "microml").listFiles()?.filter { it.name.startsWith("taste-") }?.forEach { it.delete() }
            Unit
        }
    }

    companion object {
        const val MAX_FAILURES = 3
        private val MISSING = Any()
    }
}
