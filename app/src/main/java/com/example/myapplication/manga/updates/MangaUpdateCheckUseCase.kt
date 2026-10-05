package com.example.myapplication.manga.updates

import android.util.Log
import com.example.myapplication.manga.data.MangaBindingStore
import com.example.myapplication.manga.data.MangaChapterCacheStore
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.ja.JaTailResolver
import com.example.myapplication.manga.source.MangaSourceEngine
import com.example.myapplication.manga.translate.MangaTranslateSettings
import kotlinx.coroutines.delay

/**
 * Событие «у привязанной манги вышли новые главы».
 *
 * [latestLabel] — номер самой свежей главы строкой, как его покажет пуш: «Гл. 128». Числом его
 * держать нельзя, у глав бывает дробная нумерация (128.5) и вовсе безномерные экстры.
 */
data class MangaUpdate(
    val animeId: String,
    val title: String,
    val previousChapters: Int,
    val newChapters: Int,
    val latestLabel: String?,
)

/**
 * Главы, которых в [previous] не было.
 *
 * Сравниваем по номеру, а не по ключу главы: один и тот же выпуск приходит от нескольких команд
 * перевода отдельными записями, и появление второго перевода уже вышедшей главы — не новость.
 * Безномерные экстры считаем по ключу: сравнивать их не по чему.
 *
 * Платные главы выброшены: страниц по ним не придёт, и пуш про главу, которую нельзя открыть, —
 * это обещание, которого приложение не сдержит.
 */
internal fun newChapterKeys(
    previous: List<MangaChapter>,
    current: List<MangaChapter>,
    language: String?,
): List<MangaChapter> {
    fun List<MangaChapter>.usable(): List<MangaChapter> = filterNot { it.paid }
        .filter { language == null || it.language == null || it.language == language }

    val before = previous.usable()
    val seenNumbers = before.mapNotNullTo(HashSet()) { it.number }
    val seenKeys = before.mapTo(HashSet()) { it.key }

    return current.usable()
        .filter { chapter ->
            val number = chapter.number
            if (number == null) chapter.key !in seenKeys else number !in seenNumbers
        }
        .distinctBy { it.number ?: it.key }
}

/**
 * Проверка новых глав по подтверждённым привязкам «тайтл ↔ источник манги».
 *
 * Уведомлений о манге до этого не существовало вовсе: фоновый проход знал только про серии, а
 * оглавления обновлялись лишь когда пользователь сам открывал вкладку «Главы». То есть узнать о
 * новой главе можно было, только зайдя и посмотрев.
 *
 * Сравнение идёт с тем же файловым кэшем оглавлений ([MangaChapterCacheStore]), на котором
 * работает экран глав, — отдельного «состояния уведомлений» нет и заводить его незачем: кэш
 * уже описывает «что мы видели в прошлый раз». Побочный эффект приятный — открытая после пуша
 * вкладка «Главы» показывает новые главы сразу, без ожидания сети.
 *
 * Привязка без кэша (её ещё ни разу не открывали) оглавление только ЗАПОЛНЯЕТ: иначе первый же
 * фоновый проход прислал бы пуш «+312 глав» по каждой добавленной манге.
 */
class MangaUpdateCheckUseCase(
    private val bindingStore: MangaBindingStore,
    private val chapterCache: MangaChapterCacheStore,
    private val sourceEngine: MangaSourceEngine,
    private val translateSettings: MangaTranslateSettings,
    private val tailResolver: JaTailResolver,
) {

    suspend fun detect(budget: Int = DEFAULT_BUDGET): List<MangaUpdate> {
        bindingStore.ensureLoaded()
        chapterCache.ensureLoaded()
        // Порядок — по давности последней проверки: карта привязок читается из одного и того же
        // файла в одном и том же порядке, и без этого всё, что дальше 25-й позиции, не
        // проверялось бы никогда.
        val bindings = bindingStore.flow.value.values.sortedBy {
            chapterCache.entry(it.sourceId, it.mangaKey)?.resolvedAt ?: 0L
        }
        if (bindings.isEmpty()) return emptyList()

        val updates = mutableListOf<MangaUpdate>()
        var checked = 0
        for (binding in bindings) {
            if (checked >= budget) break
            checked++
            // Пауза до запроса, а не после находки: без неё 25 привязок уходили бы в источник
            // очередью без единого зазора в самом частом случае — когда нового ничего нет.
            if (checked > 1) delay(ITEM_DELAY_MS)
            val fromSource = runCatching { sourceEngine.chapters(binding.toItem()) }
                .onFailure { Log.w(TAG, "chapters failed for \"${binding.title}\": ${it.message}") }
                .getOrNull()
                .orEmpty()
            if (fromSource.isEmpty()) continue
            // Кэш общий с экраном глав: если там лежит японский хвост, фон его не затирает. Поиск
            // серии здесь не запускаем - только уже найденное совпадение.
            val fetched = if (translateSettings.ui.value.active) {
                tailResolver.withTail(
                    base = fromSource,
                    animeId = binding.animeId,
                    preferredLanguage = binding.preferredLanguage,
                    queries = listOf(binding.title),
                    allowSearch = false,
                )
            } else {
                fromSource
            }

            val cached = chapterCache.entry(binding.sourceId, binding.mangaKey)
            chapterCache.put(binding.sourceId, binding.mangaKey, fetched)
            // Первое знакомство с тайтлом: кэш заполнили, но «новым» здесь является всё, и
            // сообщать об этом нечего.
            val previous = cached?.chapters ?: continue

            val fresh = newChapterKeys(previous, fetched, binding.preferredLanguage)
            if (fresh.isEmpty()) continue
            updates += MangaUpdate(
                animeId = binding.animeId,
                title = binding.title,
                previousChapters = previous.count { !it.paid },
                newChapters = fresh.size,
                // NEGATIVE_INFINITY, а не MIN_VALUE: последнее — наименьшее ПОЛОЖИТЕЛЬНОЕ
                // значение, и безномерная глава обошла бы по нему нулевую.
                latestLabel = fresh.maxByOrNull { it.number ?: Double.NEGATIVE_INFINITY }?.numberLabel,
            )
            Log.i(TAG, "\"${binding.title}\": +${fresh.size} chapter(s)")
        }
        return updates
    }

    private companion object {
        const val TAG = "MangaUpdates"

        /**
         * Сколько привязок опрашивать за проход. Оглавление — один запрос на тайтл, но у
         * источников свои лимиты, и вычерпывать их фоном ради манги, которую пользователь читает
         * раз в неделю, незачем.
         */
        const val DEFAULT_BUDGET = 25
        const val ITEM_DELAY_MS = 250L
    }
}
