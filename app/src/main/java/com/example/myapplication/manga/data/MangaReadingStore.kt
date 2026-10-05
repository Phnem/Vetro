package com.example.myapplication.manga.data

import com.example.myapplication.network.AppStoreJson
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class ChapterReadingProgress(
    /** Текущая страница, 0-based: по ней ридер продолжает чтение. */
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    /**
     * Липкая отметка: ставится при открытии последней страницы и не снимается перечитыванием
     * с начала — иначе список глав терял бы галочки от одного случайного возврата назад.
     */
    val read: Boolean = false,
    /**
     * Докрутка внутри страницы [pageIndex], 0..1 от её высоты — только для вебтун-ленты, где одна
     * «страница» бывает в несколько экранов и постраничной точности не хватает.
     *
     * `null` = не задано: постраничный режим сюда ничего не пишет, и старые записи (до появления
     * поля) читаются как «с начала страницы».
     */
    val scrollOffsetFraction: Float? = null,
    val updatedAt: Long = 0L,
    /**
     * Сколько раз главу перечитывали после прочтения: вернулись к началу спустя
     * [REREAD_GAP_MS]. Считается на устройстве, в облако не уходит.
     */
    val rereads: Int = 0,
) {
    val fraction: Float
        get() = if (pageCount > 0) ((pageIndex + 1).toFloat() / pageCount).coerceIn(0f, 1f) else 0f
}

/** Перерыв после прочтения, за которым возврат к началу главы считается перечитыванием. */
private const val REREAD_GAP_MS = 30L * 60_000L

/**
 * Как листается ридер. Настройка **на тайтл**: в одной коллекции соседствуют вебтуны и обычная
 * манга, и один глобальный переключатель заставлял бы менять режим при каждом открытии.
 */
@Serializable
enum class MangaReaderMode {
    /** Постранично, свайпом влево/вправо — обычная манга. */
    Paged,

    /** Непрерывная вертикальная лента — вебтуны и длинные полосы. */
    Webtoon,
}

/**
 * Направление листания страниц — отдельное от [MangaReaderMode] измерение: осмысленно только в
 * [MangaReaderMode.Paged], вертикальной ленте направление не нужно.
 */
@Serializable
enum class PageDirection {
    /** «Классика»: справа налево, как в японской манге. */
    Rtl,

    /** «Комикс»: слева направо, как в западных комиксах и манхве в переводе. */
    Ltr,
}

@Serializable
private data class ReadingEntry(val chapterKey: String, val value: ChapterReadingProgress)

@Serializable
private data class ReadingSnapshot(
    val entries: List<ReadingEntry> = emptyList(),
    /** `null` = per-title значение не выбрано, берём глобальный легаси-дефолт. */
    val mode: MangaReaderMode? = null,
    /** `null` = направление не выбрано, берём [DEFAULT_DIRECTION]. */
    val direction: PageDirection? = null,
    /** Обрезка белых полей страницы. `null` = не выбрано, обрезки нет. */
    val cropBorders: Boolean? = null,
) {
    fun progress(): Map<String, ChapterReadingProgress> = entries.associate { it.chapterKey to it.value }

    fun withProgress(value: Map<String, ChapterReadingProgress>): ReadingSnapshot = copy(
        entries = value.entries.sortedBy { it.key }.map { ReadingEntry(it.key, it.value) },
    )
}

/**
 * Дефолт направления: автодетект невозможен — в модели тайтла нет ни страны, ни языка оригинала,
 * так что берём самый частый для манги вариант, а дальше читатель переключит вручную.
 */
private val DEFAULT_DIRECTION = PageDirection.Rtl

/**
 * Прогресс чтения по главам, одним компактным JSON-снимком на тайтл в общем settings DataStore —
 * тем же приёмом, что [com.example.myapplication.media.progress.EpisodePlaybackStore]: список глав
 * подписывается на один Flow и получает разом все отметки.
 */
class MangaReadingStore(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = AppStoreJson
    private val writeMutex = Mutex()

    fun progressFlow(animeId: String): Flow<Map<String, ChapterReadingProgress>> {
        val key = progressKey(animeId)
        return dataStore.data.map { decode(it[key]) }.distinctUntilChanged()
    }

    /**
     * Прогресс сразу по списку тайтлов — для главного экрана, где карточек десятки.
     *
     * Читаем снимок Preferences ОДИН раз и спрашиваем по нему нужные ключи, а не заводим по
     * [progressFlow] на каждый тайтл (тем же приёмом, что `EpisodePlaybackStore.furthestEpisodeFlow`).
     * Ключ хранения — хэш от animeId, поэтому перечислить тайтлы по снимку нельзя: список
     * приходит снаружи.
     */
    fun progressFlow(animeIds: List<String>): Flow<Map<String, Map<String, ChapterReadingProgress>>> {
        if (animeIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyMap())
        val keys = animeIds.associateWith { progressKey(it) }
        return dataStore.data.map { preferences ->
            buildMap {
                for ((animeId, key) in keys) {
                    val progress = decode(preferences[key])
                    if (progress.isNotEmpty()) put(animeId, progress)
                }
            }
        }.distinctUntilChanged()
    }

    fun chapterFlow(animeId: String, chapterKey: String): Flow<ChapterReadingProgress?> =
        progressFlow(animeId).map { it[chapterKey] }.distinctUntilChanged()

    /** Разовое чтение — ридеру нужна стартовая страница на момент открытия, а не подписка. */
    suspend fun chapterProgress(animeId: String, chapterKey: String): ChapterReadingProgress? =
        decode(dataStore.data.first()[progressKey(animeId)])[chapterKey]

    /**
     * Режим тайтла: сначала per-title снимок, иначе старая глобальная настройка, иначе Paged.
     * Легаси-ключ читается (но больше не пишется), чтобы тайтлы, открытые до разделения настройки,
     * не перескочили на другой режим.
     */
    fun readerModeFlow(animeId: String): Flow<MangaReaderMode> {
        val key = progressKey(animeId)
        return dataStore.data
            .map { preferences -> decodeSnapshot(preferences[key]).mode ?: legacyMode(preferences) }
            .distinctUntilChanged()
    }

    suspend fun setReaderMode(animeId: String, mode: MangaReaderMode) {
        updateSnapshot(animeId) { it.copy(mode = mode) }
    }

    fun directionFlow(animeId: String): Flow<PageDirection> {
        val key = progressKey(animeId)
        return dataStore.data
            .map { preferences -> decodeSnapshot(preferences[key]).direction ?: DEFAULT_DIRECTION }
            .distinctUntilChanged()
    }

    suspend fun setDirection(animeId: String, direction: PageDirection) {
        updateSnapshot(animeId) { it.copy(direction = direction) }
    }

    /**
     * Обрезка полей — тоже на тайтл: у одного сканлейта поля белые и широкие, у другого страницы
     * уже обрезаны, и глобальный тумблер пришлось бы дёргать при каждом переключении тайтла.
     */
    fun cropBordersFlow(animeId: String): Flow<Boolean> {
        val key = progressKey(animeId)
        return dataStore.data
            .map { preferences -> decodeSnapshot(preferences[key]).cropBorders ?: false }
            .distinctUntilChanged()
    }

    suspend fun setCropBorders(animeId: String, enabled: Boolean) {
        updateSnapshot(animeId) { it.copy(cropBorders = enabled) }
    }

    suspend fun saveProgress(
        animeId: String,
        chapterKey: String,
        pageIndex: Int,
        pageCount: Int,
        /** Докрутка внутри страницы; `null` из постраничного режима — там её не бывает. */
        scrollOffsetFraction: Float? = null,
    ) {
        if (pageCount <= 0) return
        val page = pageIndex.coerceIn(0, pageCount - 1)
        updateSnapshot(animeId) { snapshot ->
            val current = snapshot.progress().toMutableMap()
            val previous = current[chapterKey]
            val now = System.currentTimeMillis()
            // Односложные главы (1–2 страницы) не различают «начало» и «конец» — возврат в них не ловим.
            val restarted = previous != null && previous.read && pageCount > 2 && page <= 1 &&
                now - previous.updatedAt >= REREAD_GAP_MS
            current[chapterKey] = ChapterReadingProgress(
                pageIndex = page,
                pageCount = pageCount,
                read = previous?.read == true || page >= pageCount - 1,
                scrollOffsetFraction = scrollOffsetFraction?.coerceIn(0f, 1f),
                updatedAt = now,
                rereads = (previous?.rereads ?: 0) + if (restarted) 1 else 0,
            )
            snapshot.withProgress(current)
        }
    }

    /** Явная отметка «прочитано/не прочитано» из списка глав. */
    suspend fun setRead(animeId: String, chapterKey: String, read: Boolean, pageCount: Int) {
        updateSnapshot(animeId) { snapshot ->
            val current = snapshot.progress().toMutableMap()
            val count = pageCount.takeIf { it > 0 } ?: current[chapterKey]?.pageCount ?: 0
            if (read) {
                current[chapterKey] = ChapterReadingProgress(
                    pageIndex = (count - 1).coerceAtLeast(0),
                    pageCount = count,
                    read = true,
                    updatedAt = System.currentTimeMillis(),
                    rereads = current[chapterKey]?.rereads ?: 0,
                )
            } else {
                current.remove(chapterKey)
            }
            snapshot.withProgress(current)
        }
    }

    /**
     * Отметить прочитанными сразу несколько глав — главы до самой дальней прочитанной
     * (`chaptersToMarkRead`).
     *
     * Одним обновлением снимка, а не циклом по [setRead]: на тайтле в девяносто глав это была бы
     * сотня записей в DataStore подряд, каждая со своей сериализацией и своим коллектором.
     *
     * Уже существующие записи не трогаются: у главы, которую пользователь читал, есть своя
     * страница и время, и затирать их пересчитанными «с конца» нельзя.
     */
    suspend fun markReadBulk(animeId: String, chapterKeys: Collection<String>) {
        if (chapterKeys.isEmpty()) return
        updateSnapshot(animeId) { snapshot ->
            val current = snapshot.progress().toMutableMap()
            // Время последнего чтения тайтла, а не «сейчас». По нему считается признак «вышли
            // новые главы»: проставь мы текущую метку, сегодняшняя дата оказалась бы позже даты
            // выхода любой главы, и метка новых глав погасла бы у всей коллекции разом.
            val stamp = current.values
                .filter { it.read }
                .maxOfOrNull { it.updatedAt }
                ?: System.currentTimeMillis()
            for (key in chapterKeys) {
                val existing = current[key]
                if (existing?.read == true) continue
                val count = existing?.pageCount ?: 0
                current[key] = ChapterReadingProgress(
                    pageIndex = (count - 1).coerceAtLeast(0),
                    pageCount = count,
                    read = true,
                    updatedAt = existing?.updatedAt ?: stamp,
                )
            }
            snapshot.withProgress(current)
        }
    }

    /**
     * Разовый снимок прогресса по списку тайтлов — для push в облако. Ключ хранения — хэш от
     * animeId, поэтому список id приходит снаружи (из коллекции): по снимку Preferences тайтлы
     * не перечислить. Настройки ридера (режим/направление/обрезка) в синк не едут — они про
     * конкретный экран устройства, а не про прогресс.
     */
    suspend fun snapshotAll(animeIds: List<String>): Map<String, Map<String, ChapterReadingProgress>> {
        if (animeIds.isEmpty()) return emptyMap()
        val preferences = dataStore.data.first()
        return buildMap {
            for (animeId in animeIds) {
                val progress = decode(preferences[progressKey(animeId)])
                if (progress.isNotEmpty()) put(animeId, progress)
            }
        }
    }

    /**
     * Применение облачных записей поверх локальных: последняя запись выигрывает по `updatedAt`.
     * Отметка `read` при этом липкая и на merge — она не снимается более новой записью с
     * `read = false` (перечитывание с начала не должно гасить галочку на другом устройстве).
     *
     * @return сколько записей реально применено.
     */
    suspend fun mergeRemote(remote: Map<String, Map<String, ChapterReadingProgress>>): Int {
        if (remote.isEmpty()) return 0
        var applied = 0
        writeMutex.withLock {
            dataStore.edit { preferences ->
                for ((animeId, incoming) in remote) {
                    val preferenceKey = progressKey(animeId)
                    val snapshot = decodeSnapshot(preferences[preferenceKey])
                    val current = snapshot.progress().toMutableMap()
                    var changed = false
                    for ((chapterKey, value) in incoming) {
                        val local = current[chapterKey]
                        if (local != null && local.updatedAt >= value.updatedAt) continue
                        current[chapterKey] = value.copy(
                            read = value.read || local?.read == true,
                            rereads = maxOf(value.rereads, local?.rereads ?: 0),
                        )
                        changed = true
                        applied++
                    }
                    if (changed) {
                        preferences[preferenceKey] = json.encodeToString(snapshot.withProgress(current))
                    }
                }
            }
        }
        return applied
    }

    /**
     * Единственная точка записи снимка: прогресс и настройки ридера лежат в одном JSON, поэтому
     * любая правка обязана читать снимок целиком — иначе сохранение страницы стирало бы режим.
     */
    private suspend fun updateSnapshot(animeId: String, transform: (ReadingSnapshot) -> ReadingSnapshot) {
        val preferenceKey = progressKey(animeId)
        writeMutex.withLock {
            dataStore.edit { preferences ->
                val snapshot = decodeSnapshot(preferences[preferenceKey])
                preferences[preferenceKey] = json.encodeToString(transform(snapshot))
            }
        }
    }

    /**
     * Дефолт режима: вертикальная лента.
     *
     * Раньше здесь был `Paged` плюс автодетект по пропорциям первой страницы. Автодетект убран
     * (он решал за читателя и иногда решал не так), а дефолтом стал тот режим, которым читают:
     * вертикальный свайп. Старый глобальный ключ по-прежнему уважается — тайтлы, открытые до
     * разделения настройки на per-title, не должны перескочить на другой режим.
     */
    private fun legacyMode(preferences: Preferences): MangaReaderMode =
        when (preferences[READER_MODE_KEY]) {
            MangaReaderMode.Paged.name -> MangaReaderMode.Paged
            else -> MangaReaderMode.Webtoon
        }

    private fun decodeSnapshot(raw: String?): ReadingSnapshot {
        if (raw.isNullOrBlank()) return ReadingSnapshot()
        return runCatching { json.decodeFromString<ReadingSnapshot>(raw) }.getOrElse { ReadingSnapshot() }
    }

    private fun decode(raw: String?): Map<String, ChapterReadingProgress> = decodeSnapshot(raw).progress()

    private fun progressKey(animeId: String) =
        stringPreferencesKey("manga_progress_${stableSuffix(animeId)}")

    private companion object {
        /**
         * Легаси-настройка «режим на всё приложение». Только чтение: она остаётся дефолтом для
         * тайтлов, у которых per-title режим ещё не выбран, поэтому удалять ключ нельзя.
         */
        val READER_MODE_KEY = stringPreferencesKey("manga_reader_mode")
    }

    private fun stableSuffix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
}
