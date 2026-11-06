package com.example.myapplication.manga.extensions

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.manga.source.MangaSourceEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Живая проверка расширений Mihon на устройстве (сеть обязательна): каталог Keiyoushi → установка
 * с проверкой подписи → загрузка APK на ART → поиск «Solo Leveling» → главы → страницы. Плюс
 * поиск движка целиком — то, что видит выбор источника.
 *
 * Запуск только на тестовой сборке `.perf` и с
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, иначе раннер снесёт приложение.
 */
@RunWith(AndroidJUnit4::class)
class MihonExtensionsDeviceTest {

    private val koin get() = GlobalContext.get()

    @Test
    fun catalogInstallSearchChaptersPages() = runBlocking {
        val manager = koin.get<MangaExtensionManager>()
        val catalog = manager.catalog(forceRefresh = true)
        Log.i(TAG, "catalog: ${catalog.size} extensions")
        assertTrue("catalog is empty", catalog.isNotEmpty())

        val wanted = listOf(
            "eu.kanade.tachiyomi.extension.ru.inkstory",
            "eu.kanade.tachiyomi.extension.ru.mangalib",
            "eu.kanade.tachiyomi.extension.ru.senkuro",
            "eu.kanade.tachiyomi.extension.ru.readmanga",
        )
        val installed = wanted.mapNotNull { pkg -> catalog.firstOrNull { it.packageName == pkg } }
            .mapNotNull { extension ->
                val result = manager.install(extension)
                Log.i(TAG, "install ${extension.name}: ${result.exceptionOrNull() ?: "ok, ${result.getOrNull()?.sources?.map { it.name }}"}")
                result.getOrNull()
            }
        assertTrue("no extension installed", installed.isNotEmpty())

        var readable = 0
        for (source in installed.flatMap { it.sources }) {
            if (probe(source, listOf("Solo Leveling", "Поднятие уровня в одиночку"))) readable++
        }
        Log.i(TAG, "sources with readable Solo Leveling pages: $readable")

        val engine = koin.get<MangaSourceEngine>()
        val results = engine.searchAll("Solo Leveling")
        results.forEach { group ->
            Log.i(TAG, "engine: ${group.sourceName} -> ${group.items.take(3).map { it.title }}")
        }
        assertTrue("engine sees no extension source", results.any { it.sourceId.value.startsWith(MihonSourceAdapter.ID_PREFIX) })
        assertTrue("no extension source returned readable pages", readable > 0)
    }

    /** Поиск → первая манга → главы → страницы первой бесплатной главы. true — страницы получены. */
    private suspend fun probe(source: VetroMangaSource, queries: List<String>): Boolean {
        for (query in queries) {
            val outcome = withTimeoutOrNull(60_000) {
                runCatching {
                    val hits = source.search(query, 1).items
                    Log.i(TAG, "${source.name} search '$query': ${hits.size} ${hits.take(3).map { it.title }}")
                    val manga = hits.firstOrNull() ?: return@runCatching false
                    val chapters = source.chapters(manga)
                    Log.i(TAG, "${source.name} '${manga.title}': ${chapters.size} chapters, paid ${chapters.count { it.paid }}")
                    val chapter = chapters.firstOrNull { !it.paid } ?: return@runCatching false
                    val pages = source.pages(chapter)
                    Log.i(TAG, "${source.name} ch ${chapter.numberLabel}: ${pages.size} pages, first ${pages.firstOrNull()?.url?.take(80)} headers ${pages.firstOrNull()?.headers?.keys}")
                    pages.isNotEmpty()
                }.onFailure { Log.w(TAG, "${source.name} '$query' failed", it) }.getOrDefault(false)
            }
            if (outcome == true) return true
        }
        return false
    }

    private companion object {
        const val TAG = "VetroExtTest"
    }
}
