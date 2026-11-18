package com.example.myapplication.mediacatalog

import android.content.Context
import android.util.Log
import com.example.myapplication.data.local.VetroLinks
import com.example.myapplication.data.models.Anime
import com.example.myapplication.micromlintegration.StockCoverDetector
import com.example.myapplication.microml.MicroMlEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Сохраняет обложку, пришедшую байтами из каталога Vetro (AVIF), как файл тайтла: через временный файл и обычный путь сохранения
 * картинки (здесь заглушки не отсеиваются: обложки каталога проверены при сборке).
 */
class CoverBytesStore(
    private val cacheDir: java.io.File,
    private val images: com.example.myapplication.data.repository.ImageStorageRepository,
) {
    suspend fun save(bytes: ByteArray, animeId: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tmp = java.io.File.createTempFile("vetro_cover_", ".avif", cacheDir)
        try {
            tmp.writeBytes(bytes)
            images.saveImage(android.net.Uri.fromFile(tmp).toString(), animeId).getOrNull()
        } finally {
            tmp.delete()
        }
    }
}

/** Какие файлы обложек уже проверены на заглушку: проверять один и тот же файл дважды незачем (имена файлов неизменяемы). */
interface CheckedCovers {
    fun isChecked(name: String): Boolean
    fun markChecked(names: Collection<String>)
}

class PrefsCheckedCovers(context: Context) : CheckedCovers {
    private val prefs = context.applicationContext.getSharedPreferences("stock_cover", Context.MODE_PRIVATE)
    private val known = HashSet(prefs.getStringSet(KEY, emptySet()).orEmpty())

    @Synchronized override fun isChecked(name: String) = name in known

    @Synchronized override fun markChecked(names: Collection<String>) {
        if (known.addAll(names)) prefs.edit().putStringSet(KEY, HashSet(known)).apply()
    }

    private companion object { const val KEY = "ok" }
}

/**
 * Заглушки вместо обложек в уже сохранённой коллекции: у Shikimori это девочка с поднятой рукой и «404 not found».
 * Файл, который модель Catalog признала заглушкой, считается отсутствующей обложкой: сначала берётся обложка из каталога Vetro
 * (по привязанному Vetro id или после нового поиска), а если там её нет, картинка убирается, и тайтл попадает в обычный поиск обложки.
 * Если каталог недоступен, ничего не трогается и файл остаётся непроверенным до следующего раза.
 */
class StockCoverRepair(
    private val library: VetroLibrary,
    private val links: VetroLinks,
    private val catalog: VetroCatalog,
    private val linker: VetroLinkService,
    private val isStockFile: suspend (path: String) -> Boolean,
    private val storeCover: suspend (bytes: ByteArray, animeId: String) -> String?,
    private val checked: CheckedCovers,
) {
    data class RepairResult(val stock: Int, val replaced: Int, val dropped: Int, val postponed: Int)

    suspend fun run(): RepairResult {
        val stock = ArrayList<Pair<Anime, String>>()
        val fine = ArrayList<String>()
        for (anime in library.items()) {
            val name = anime.imageFileName ?: continue
            if (checked.isChecked(name)) continue
            val path = library.coverFile(anime) ?: continue
            if (isStockFile(path)) stock += anime to name else fine += name
        }
        checked.markChecked(fine)

        val rows = links.all().associateBy { it.animeId }
        var replaced = 0
        var dropped = 0
        var postponed = 0
        for ((anime, name) in stock) {
            val lookup = hitFor(anime, rows[anime.id]?.vetroId)
            if (lookup.isFailure) { postponed++; continue }
            val bytes = lookup.getOrNull()?.let { catalog.downloadCover(it) }
            if (bytes != null && bytes.isFailure) { postponed++; continue }
            val newName = bytes?.getOrNull()?.let { storeCover(it, anime.id) }
            if (newName != null && library.replaceCover(anime.id, name, newName)) {
                replaced++
                checked.markChecked(listOf(newName))
            } else if (library.dropCover(anime.id, name)) {
                dropped++
            }
        }
        return RepairResult(stock.size, replaced, dropped, postponed)
    }

    /** Запись каталога тайтла: по привязанному Vetro id, иначе поиск заново. success(null) — в каталоге его нет. */
    private suspend fun hitFor(anime: Anime, vetroId: String?): Result<VetroHit?> {
        if (vetroId != null) {
            val rows = catalog.byIds(listOf(vetroId))
            if (rows.isFailure) return Result.failure(rows.exceptionOrNull() ?: IllegalStateException("catalogue unavailable"))
            rows.getOrNull()?.firstOrNull { it.id == vetroId }?.let { return Result.success(it) }
        }
        return linker.resolve(anime)
    }
}

/**
 * Запускает проверку заглушек, когда приложение готово: после первой привязки к каталогу и при каждом переключении «умных моделей»
 * (так проверка начинается сразу, как только пользователь включил Catalog).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockCoverRepairRunner(
    private val repair: StockCoverRepair,
    private val detector: StockCoverDetector,
    private val engine: MicroMlEngine,
    private val gate: VetroLinkGate,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            engine.controlRevision.collectLatest {
                try {
                    if (!detector.isEnabled()) return@collectLatest
                    gate.state.first { !it.running }
                    delay(SETTLE_MS)
                    val result = repair.run()
                    if (result.stock > 0) Log.i(TAG, "Stock covers: $result")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stock cover repair failed: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 3_000L
        const val TAG = "StockCoverRepair"
    }
}
