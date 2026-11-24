package com.example.myapplication.ui.searchdetails

import com.example.myapplication.network.ApiSearchResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Результат поиска, по которому открыт экран «Детали из поиска». Маршрут навигации несёт только
 * ключ: сам [ApiSearchResult] — большой объект со списками, класть его в аргументы незачем.
 * После убийства процесса холдер пуст, и экран просто закрывается.
 */
object SearchDetailsHolder {
    private val items = ConcurrentHashMap<String, ApiSearchResult>()

    fun put(result: ApiSearchResult): String {
        val key = "${result.source}_${result.externalId ?: result.title}"
        items[key] = result
        return key
    }

    fun get(key: String): ApiSearchResult? = items[key]
}
