package com.example.myapplication.microml.contribution

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.enrichment.TitleCandidate
import com.example.myapplication.domain.titleidentityintegration.toTitleIdentity
import com.example.myapplication.manga.translate.OfficialBuild
import com.example.myapplication.microml.MicroMlEngine
import com.example.myapplication.microml.MicroMlEngine.Pack
import com.example.myapplication.microml.MicroRecord
import com.example.myapplication.micromlintegration.MicroMlBridge.toMicroRecord
import com.phnem.vetro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Добровольный сбор таблиц для дообучения моделей MicroML. Работает только если одновременно:
 * сборка официальная (подписана ключом автора — F-Droid и пересборки не участвуют), в сборке задан адрес
 * коллектора и пользователь сам включил переключатель. Названия из коллекции сюда не попадают ни при каких условиях.
 */
class ContributionService(
    private val context: Context,
    private val store: ContributionStore,
    private val engine: MicroMlEngine,
    private val http: OkHttpClient,
    private val officialBuild: OfficialBuild,
    private val collectorUrl: String = BuildConfig.MICROML_COLLECTOR_URL,
) {
    enum class Result { SENT, SKIPPED, RETRY }

    init {
        // Жанры, которые не узнал ни прежний алгоритм, ни модель, попадают в очередь через это место.
        engine.genreSink = ::recordGenre
        ContributionScheduler.sync(context, isEnabled())
    }

    /** Функция вообще доступна в этой сборке (иначе строки в настройках не показываются). */
    val isAvailable: Boolean get() = collectorUrl.startsWith("https://") && officialBuild.isOfficial

    fun isEnabled(): Boolean = isAvailable && store.isEnabled()

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled && isAvailable)
        ContributionScheduler.sync(context, isEnabled())
    }

    private fun recordGenre(raw: String) {
        if (isEnabled()) store.addGenre(raw)
    }

    /**
     * VETRO-TAIL[microml-label-source]: новый источник честной метки вызывает этот метод из места, где пользователь
     * принял решение (см. docs/agents/tails.md).
     *
     * Метка пары от действия пользователя. Заголовки превращаются в вектор сходства и тут же забываются;
     * в очередь попадает только вектор, версия пакета, тип пары и источник метки.
     */
    suspend fun recordPair(a: MicroRecord, b: MicroRecord, label: Int, source: String) {
        if (!isEnabled()) return
        val model = engine.identity() ?: return
        withContext(Dispatchers.Default) {
            val features = engine.guarded(Pack.IDENTITY) { model.features(model.prepare(a), model.prepare(b)) } ?: return@withContext
            store.addPair(PairEntry(model.version, source, label, listOf(a.kind.ordinal, b.kind.ordinal), features.toList()))
        }
    }

    /** Пользователь выбрал верный вариант в «Источник неверен»: он — «та же сущность», несколько других показанных — «разные». */
    suspend fun recordPickerChoice(anime: Anime, chosen: TitleCandidate, shown: List<TitleCandidate>) {
        if (!isEnabled()) return
        val own = anime.toTitleIdentity()
        recordPair(own.toMicroRecord(), chosen.toTitleIdentity(own.kind).toMicroRecord(), 1, "picker_choice")
        shown.filter { it.key != chosen.key }.take(3).forEach { other ->
            recordPair(own.toMicroRecord(), other.toTitleIdentity(own.kind).toMicroRecord(), 0, "picker_choice")
        }
    }

    /** «Ни один из них» / «Источник неверен»: привязанное каталожное название — не та сущность. */
    suspend fun recordRejected(anime: Anime) {
        if (!isEnabled()) return
        val bound = anime.titleEn?.takeIf { it.isNotBlank() && !it.equals(anime.title, ignoreCase = true) } ?: return
        val own = anime.toTitleIdentity()
        recordPair(own.copy(titles = listOfNotNull(anime.title, anime.titleRu)).toMicroRecord(),
            own.copy(titles = listOf(bound)).toMicroRecord(), 0, "source_rejected")
    }

    private val sentCounters = HashMap<String, Long>()

    private suspend fun taste(): List<TasteEntry> {
        val version = engine.experience()?.version ?: return emptyList()
        return listOf("ANIME", "MOVIE", "TV").mapNotNull { type ->
            val exported = engine.tasteRanker(type)?.exportWeights() ?: return@mapNotNull null
            TasteEntry(type, version, exported.second, exported.first)
        }
    }

    private class Built(val batch: JsonObject, val pairCount: Int, val genres: List<String>)

    private suspend fun build(): Built? {
        val install = store.installId() ?: return null
        val pairs = store.pairs().take(ContributionSchema.MAX_PAIRS)
        val genres = store.genres().mapNotNull(ContributionSchema::genre).distinct().take(ContributionSchema.MAX_GENRES)
        val current = engine.shadowCounts()
        val delta = current.mapValues { (key, value) -> value - (sentCounters[key] ?: 0L) }
        val packs = engine.status().filter { it.state == MicroMlEngine.PackState.READY && it.version != null }
            .associate { it.pack.key to it.version!! }
        return Built(ContributionBatch.build(install, packs, taste(), pairs, genres, delta), pairs.size, genres)
    }

    /** Ровно тот текст, который был бы отправлен сейчас: показывается пользователю в настройках. */
    suspend fun preview(): String {
        if (!isEnabled()) return ""
        return build()?.let { ContributionBatch.preview(it.batch) }.orEmpty()
    }

    /** Одна пачка в сутки. 4xx — пачку отбросить (иначе она будет повторяться), 5xx и сеть — повторить позже. */
    suspend fun upload(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext Result.SKIPPED
        if (!force && store.lastUploadDay() == System.currentTimeMillis() / ContributionStore.DAY_MS) return@withContext Result.SKIPPED
        val built = build() ?: return@withContext Result.SKIPPED
        if (ContributionBatch.isEmpty(built.batch)) { store.markUploaded(); return@withContext Result.SKIPPED }
        val request = Request.Builder()
            .url(collectorUrl.trimEnd('/') + "/v1/batch")
            .post(ContributionBatch.compact(built.batch).toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code in 400..499 -> {
                        store.removeSent(built.pairCount, built.genres.toSet())
                        store.markUploaded()
                        if (response.isSuccessful) engine.shadowCounts().forEach { (key, value) -> sentCounters[key] = value }
                        Result.SENT
                    }
                    else -> Result.RETRY
                }
            }
        }.getOrDefault(Result.RETRY)
    }
}

object ContributionScheduler {
    private const val WORK = "MicroMlContribution"

    /** Включено — раз в сутки с разбросом по времени, только при сети и не на разряженной батарее; выключено — задача снимается. */
    fun sync(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<ContributionWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Random.nextLong(1, 7), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class ContributionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val service: ContributionService by inject()

    override suspend fun doWork(): ListenableWorker.Result = when (service.upload()) {
        ContributionService.Result.RETRY -> ListenableWorker.Result.retry()
        else -> ListenableWorker.Result.success()
    }
}
