package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.VetroMangaSource

/**
 * Все японские источники одним списком. Они НЕ показываются в выборе источника тайтла: пользователь
 * читает там, где привык (Remanga, MangaDex), а японская цепочка сама добавляет главы, которых на его
 * языке ещё нет ([JaTailResolver]).
 */
object JapaneseSources {

    fun create(http: JaHttp): List<VetroMangaSource> =
        GigaSites.ALL.map { GigaViewerSource(it, http) } +
            ComicWalkerSource(http) +
            GanganOnlineSource(http) +
            YanMagaSource(http)
}
