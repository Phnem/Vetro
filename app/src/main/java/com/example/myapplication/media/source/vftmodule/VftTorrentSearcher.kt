package com.example.myapplication.media.source.vftmodule

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.vftmodule.clients.JackettClient
import com.example.myapplication.media.source.vftmodule.scrapers.NyaaScraper
import com.example.myapplication.media.source.vftmodule.scrapers.PirateBayScraper
import com.example.myapplication.media.source.vftmodule.scrapers.RutorScraper
import com.example.myapplication.media.source.vftmodule.scrapers.RutrackerScraper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Агрегатор параллельного поиска торрент-раздач по всем настроенным источникам:
 * Jackett, Rutor, RuTracker, Nyaa, The Pirate Bay.
 * Оценивает и ранжирует результаты по релевантности, качеству и наличию оригинальной озвучки.
 */
class VftTorrentSearcher(
    private val prefs: VftPreferences,
    private val rutorScraper: RutorScraper,
    private val rutrackerScraper: RutrackerScraper,
    private val nyaaScraper: NyaaScraper,
    private val pirateBayScraper: PirateBayScraper,
    private val jackettClient: JackettClient,
) {
    /**
     * Выполняет параллельный поиск раздач по всем активным трекерам.
     */
    suspend fun search(query: VftSearchQuery): List<VftTorrentItem> = coroutineScope {
        val searchTasks = mutableListOf<suspend () -> List<VftTorrentItem>>()

        val ruQuery = query.titleRu ?: query.query
        val enQuery = query.titleEn ?: query.query

        // 1. Jackett (если включён в настройках)
        if (prefs.isJackettEnabled) {
            searchTasks.add {
                val q = if (query.preferOriginalAudio && query.titleEn != null) enQuery else ruQuery
                jackettClient.search(q)
            }
        }

        // 2. Rutor (поиск по русскому названию или оригиналу)
        if (prefs.isRutorEnabled) {
            searchTasks.add {
                rutorScraper.search(ruQuery)
            }
            if (query.titleEn != null && query.titleEn != ruQuery && query.preferOriginalAudio) {
                searchTasks.add {
                    rutorScraper.search(enQuery)
                }
            }
        }

        // 3. RuTracker
        if (prefs.isRutrackerEnabled) {
            searchTasks.add {
                rutrackerScraper.search(ruQuery)
            }
        }

        // 4. The Pirate Bay (идеально для оригинального звука / английских релизов)
        if (prefs.isPirateBayEnabled) {
            searchTasks.add {
                pirateBayScraper.search(enQuery)
            }
        }

        // 5. Nyaa.si (для аниме или японских тайтлов)
        if (prefs.isNyaaEnabled) {
            searchTasks.add {
                val animeQuery = query.titleEn ?: ruQuery
                nyaaScraper.search(animeQuery)
            }
        }

        // Запуск параллельного сбора результатов
        val deferredList = searchTasks.map { task ->
            async {
                runCatching { task() }.getOrDefault(emptyList())
            }
        }

        val allResults = deferredList.awaitAll().flatten()

        // Дедупликация по infoHash с сохранением раздачи с наибольшим числом сидов
        val uniqueByHash = LinkedHashMap<String, VftTorrentItem>()
        for (item in allResults) {
            val hash = item.infoHash.lowercase()
            val existing = uniqueByHash[hash]
            if (existing == null || item.seeders > existing.seeders) {
                uniqueByHash[hash] = item
            }
        }

        // Ранжирование по score из VftReleaseParser
        val scoredList: List<Pair<VftTorrentItem, Int>> = uniqueByHash.values.map { item: VftTorrentItem ->
            val score = VftReleaseParser.score(query, item)
            Pair(item, score)
        }

        scoredList
            .sortedWith(
                compareByDescending<Pair<VftTorrentItem, Int>> { it.second }
                    .thenByDescending { it.first.seeders }
            )
            .map { it.first }
    }
}
