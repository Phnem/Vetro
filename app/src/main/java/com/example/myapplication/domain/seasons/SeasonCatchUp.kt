package com.example.myapplication.domain.seasons

import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Чего расклад сезонов не знает о том, что уже вышло.
 *
 * Обе величины — «насколько отстал», а не «как надо»: точное число придёт от резолвера, здесь
 * достаточно факта расхождения.
 */
data class SeasonCoverageGap(
    /** Сезона нет в раскладе вовсе (пуш пришёл про 3-й, а строк две). */
    val missingSeason: Int? = null,
    /** Серий в раскладе меньше, чем уже вышло. */
    val missingEpisodes: Int = 0,
) {
    override fun toString(): String =
        "gap(season=${missingSeason ?: "-"}, episodes=$missingEpisodes)"
}

/**
 * Сверка сохранённого расклада с тем, что о тайтле знает проверка новых серий.
 *
 * Пуш «вышла 5-я серия 3-го сезона» и список из двух сезонов — прямое противоречие, и заметить
 * его можно ровно здесь: номер выходящего сезона и число вышедших серий считает
 * [com.example.myapplication.updates.BatchEpisodeCheckUseCase] и кладёт в [AiringProgress], а
 * суммарный счётчик франшизы лежит в самой записи коллекции.
 *
 * Спецвыпуски из сверки исключены: они вне сезонной нумерации, и их серии не входят в счётчик,
 * по которому считают прогресс.
 *
 * @param franchiseEpisodes счётчик серий записи коллекции — у тайтлов, где пользователь смотрит
 *   франшизу целиком, это сумма по всем сезонам. Меньше суммы расклада он быть может (запись
 *   ведётся по одному сезону), больше — только если расклад отстал.
 */
fun seasonCoverageGap(
    seasons: List<SeasonInfo>,
    airingSeason: Int?,
    airedEpisodes: Int?,
    franchiseEpisodes: Int?,
): SeasonCoverageGap? {
    val regular = seasons.filterNot { it.isSpecial }
    // Пустой расклад — это «ещё не резолвили», а не «отстал». Первый резолв делают
    // SeasonEpisodesResolver.ensureResolved (открытие Details) и refreshStale (фон), и дублировать
    // их здесь дорогим опросом источников просмотра незачем.
    if (regular.isEmpty()) return null
    val known = regular.associateBy { it.seasonNumber }

    val missingSeason = airingSeason
        ?.takeIf { it > 0 }
        ?.takeIf { it !in known }

    val seasonShortfall = when {
        airingSeason == null || airedEpisodes == null -> 0
        missingSeason != null -> airedEpisodes
        else -> (airedEpisodes - (known[airingSeason]?.episodes ?: 0)).coerceAtLeast(0)
    }
    val franchiseShortfall = franchiseEpisodes
        ?.takeIf { it > 0 }
        ?.let { total -> (total - regular.sumOf { season -> season.episodes }).coerceAtLeast(0) }
        ?: 0

    val missingEpisodes = maxOf(seasonShortfall, franchiseShortfall)
    if (missingSeason == null && missingEpisodes == 0) return null
    return SeasonCoverageGap(missingSeason = missingSeason, missingEpisodes = missingEpisodes)
}

/**
 * Догоняет расклад сезонов, когда он отстал от уже вышедшего.
 *
 * Пользователю приходил пуш о новой серии, он открывал тайтл — и видел один сезон, потому что
 * расклад собран давно и живёт по TTL. Дальше приходилось вручную жать «Найти ещё», и так после
 * каждого пуша. Теперь противоречие ловится само: сначала в фоне, сразу за проверкой серий (то
 * есть ещё до того, как пуш прочитан), и повторно при открытии Details — на случай, если фоновый
 * проход до этого тайтла не добежал.
 *
 * Каскад повторяет общий принцип резолва — от дешёвого к дорогому:
 *  1. Перерезолв по каталогу ([SeasonEpisodesResolver]) — чистые API, без разбора HTML. Чаще
 *     всего этого хватает: AniList просто успел завести новый сезон.
 *  2. Если пробел остался — опрос источников ПРОСМОТРА ([StreamingSeasonDiscovery]) тем же путём,
 *     что кнопка «Найти ещё». Дорого, поэтому только по доказанному расхождению.
 *
 * Результат обоих шагов пишется в [SeasonEpisodesStore] (файловый, атомарный) — найденное
 * переживает перезапуск и следующий пуш, а не теряется, как раньше.
 */
class SeasonCatchUp(
    private val localDataSource: AnimeLocalDataSource,
    private val store: SeasonEpisodesStore,
    private val resolver: SeasonEpisodesResolver,
    private val discovery: StreamingSeasonDiscovery,
) {

    /**
     * Когда по тайтлу последний раз пытались догнать расклад — см. [COOLDOWN_MILLIS].
     *
     * Конкурентная карта не для красоты: сюда заходят одновременно фоновый воркер (`catchUpAll`) и
     * открытие Details (`catchUp`) — разные корутины на разных потоках, и обычный `HashMap`
     * на resize из двух потоков ломается насовсем.
     */
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    /**
     * Проверить один тайтл и, если расклад отстал, догнать его.
     *
     * @return true, если расклад был обновлён.
     */
    suspend fun catchUp(animeId: String): Boolean = withContext(Dispatchers.IO) {
        store.ensureLoaded()
        val anime = localDataSource.getAnimeById(animeId) ?: return@withContext false
        val airing = runCatching { localDataSource.getAiringProgressSnapshot()[animeId] }.getOrNull()
        // Открыл пользователь: ждать шесть часов после неудачной фоновой попытки нельзя — именно
        // так список и оставался коротким до ручного «Найти ещё».
        catchUpInternal(anime, airing, knownAiredEpisodes(), USER_COOLDOWN_MILLIS)
    }

    /**
     * Фоновый проход по тайтлам, у которых проверка серий только что нашла обновление.
     *
     * Бюджет — по числу тайтлов, а не запросов: у каждого свой каскад, и ограничивать надо именно
     * количество тайтлов, иначе один многосезонник съест весь проход.
     */
    suspend fun catchUpAll(
        animeIds: Collection<String>,
        budget: Int = DEFAULT_BUDGET,
    ) = withContext(Dispatchers.IO) {
        if (animeIds.isEmpty()) return@withContext
        store.ensureLoaded()
        val airing = runCatching { localDataSource.getAiringProgressSnapshot() }
            .getOrElse { emptyMap() }
        val aired = knownAiredEpisodes()
        var used = 0
        for (id in animeIds) {
            if (used >= budget) break
            val anime = localDataSource.getAnimeById(id) ?: continue
            if (catchUpInternal(anime, airing[id], aired, COOLDOWN_MILLIS)) used++
        }
    }

    /**
     * Сколько серий уже вышло по данным проверки серий: непрочитанное уведомление и отклонённое
     * (прочитанное) — оба в той же шкале, что счётчик записи. Счётчик записи сам не растёт, пока
     * пользователь не отметил серии, поэтому без этого уведомление «191 → 192» и расклад на 191
     * противоречием не считались.
     */
    private fun knownAiredEpisodes(): Map<String, Int> {
        val pending = runCatching { localDataSource.getUpdates() }.getOrElse { emptyList() }
            .associate { it.animeId to it.newEpisodes }
        val acknowledged = runCatching { localDataSource.getIgnoredMap() }.getOrElse { emptyMap() }
        return (pending.keys + acknowledged.keys).associateWith { id ->
            maxOf(pending[id] ?: 0, acknowledged[id] ?: 0)
        }
    }

    private suspend fun catchUpInternal(
        anime: Anime,
        airing: AiringProgress?,
        aired: Map<String, Int>,
        cooldownMillis: Long,
    ): Boolean {
        if (anime.mediaType != MediaType.ANIME) return false
        val now = System.currentTimeMillis()
        // Пауза по записи: догон уже прогоняли недавно, и второй заход за тем же ответом только
        // выжжет лимиты источников. Переживает перезапуск процесса — в отличие от карты ниже.
        val lastRun = store.entryFor(anime.id)?.lastCatchUpAt ?: 0L
        if (lastRun > 0L && now - lastRun < cooldownMillis) return false

        val franchiseEpisodes = maxOf(anime.episodes, aired[anime.id] ?: 0)
        val gap = gapFor(anime, airing, franchiseEpisodes) ?: return false
        // Карта — от двух ОДНОВРЕМЕННЫХ каскадов по одному тайтлу: воркер и открытые Details
        // успевают пройти проверку выше оба, пока первый из них ещё ходит по сети и ничего не
        // записал. Ставим отметку до каскада: сорвавшийся на середине не должен уйти в цикл.
        val previous = lastAttempt.put(anime.id, now)
        if (previous != null && now - previous < cooldownMillis) return false
        store.markCatchUp(anime.id, now)
        Log.i(TAG, "\"${anime.title}\": season layout is behind — $gap")

        // Шаг 1: каталог. Дёшево и закрывает самый частый случай — сезон уже заведён в AniList,
        // а наша запись просто не протухла по TTL.
        runCatching { resolver.resolve(anime) }
            .onFailure { Log.w(TAG, "catch-up resolve failed for \"${anime.title}\": ${it.message}") }
            .getOrNull()
            ?.let { store.put(it) }
        if (gapFor(anime, airing, franchiseEpisodes) == null) {
            Log.i(TAG, "\"${anime.title}\": closed by the catalogue")
            return true
        }

        // Шаг 2: источники просмотра. Тот же путь, что кнопка «Найти ещё», — но нажимать её
        // пользователю больше не надо.
        val outcome = runCatching { discovery.discover(anime.id, forceRefresh = true) }
            .onFailure { Log.w(TAG, "catch-up discovery failed for \"${anime.title}\": ${it.message}") }
            .getOrNull()
        val closed = gapFor(anime, airing, franchiseEpisodes) == null
        Log.i(TAG, "\"${anime.title}\": discovery=$outcome closed=$closed")
        return closed ||
            outcome is StreamingSeasonDiscovery.Outcome.Updated ||
            outcome is StreamingSeasonDiscovery.Outcome.Refreshed
    }

    private fun gapFor(anime: Anime, airing: AiringProgress?, franchiseEpisodes: Int): SeasonCoverageGap? =
        seasonCoverageGap(
            seasons = store.entryFor(anime.id)?.seasons.orEmpty(),
            airingSeason = airing?.seasonNumber,
            airedEpisodes = airing?.airedEpisodes,
            franchiseEpisodes = franchiseEpisodes,
        )

    private companion object {
        const val TAG = "SeasonCatchUp"
        const val DEFAULT_BUDGET = 8

        /**
         * Пауза между попытками по одному тайтлу.
         *
         * Счётчик серий записи пользователь ведёт руками и может завысить — тогда пробел «есть»
         * всегда, и без паузы каждое открытие Details запускало бы полный опрос источников впустую.
         * Тот же смысл у проверки возраста записи: перестроенный десять минут назад расклад
         * перестраивать снова бессмысленно.
         */
        const val COOLDOWN_MILLIS = 6L * 60 * 60 * 1000

        /** Пауза, когда тайтл открыл пользователь: он видит расхождение прямо сейчас. */
        const val USER_COOLDOWN_MILLIS = 30L * 60 * 1000
    }
}
