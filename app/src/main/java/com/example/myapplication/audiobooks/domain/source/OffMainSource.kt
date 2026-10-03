package com.example.myapplication.audiobooks.domain.source

import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сетевой запрос источник и так делает на IO, а вот разбор HTML/JSON идёт в потоке вызывающего: из
 * `viewModelScope` или `rememberCoroutineScope` это главный поток. Поиск по десятку сайтов сразу
 * разбирал страницы подряд на нём — спиннер «Озвучки» замирал, а интерфейс подвисал. Обёртка уводит
 * всю работу источника с главного потока; сами источники не меняются.
 */
class OffMainSource(private val delegate: AudiobookSource) : AudiobookSource by delegate {
    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> =
        withContext(Dispatchers.Default) { delegate.search(query, page) }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        withContext(Dispatchers.Default) { delegate.details(ref) }

    override suspend fun shelf(id: String, page: Int): SourceResult<List<SourceBook>> =
        withContext(Dispatchers.Default) { delegate.shelf(id, page) }

    override suspend fun refresh(variant: VariantId): MediaManifest =
        withContext(Dispatchers.Default) { delegate.refresh(variant) }
}
