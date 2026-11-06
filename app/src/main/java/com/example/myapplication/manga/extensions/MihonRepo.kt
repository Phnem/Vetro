package com.example.myapplication.manga.extensions

/**
 * Репозиторий расширений Mihon, из которого Vetro ставит источники манги.
 *
 * Сейчас один — Keiyoushi (тот же, что у самого Mihon). Индекс — JSON-эквивалент `index.pb`
 * (схема: keiyoushi/extensions-lib/index/index.proto); APK подписаны ключом репозитория, и
 * загрузчик пускает в процесс только APK с этим отпечатком сертификата.
 *
 * VETRO-TAIL[mihon-repo]: второй репозиторий — ещё один [MihonRepo] с адресами индекса и
 * отпечатком; каталог и установщик уже работают с полями этого объекта, а не с константами.
 */
internal data class MihonRepo(
    val name: String,
    /** Адреса индекса по порядку: основной и зеркало (raw GitHub режут некоторые провайдеры). */
    val indexUrls: List<String>,
    /** SHA-256 сертификата, которым подписаны APK репозитория (`repo.json` → signingKeyFingerprint). */
    val signingFingerprint: String,
) {
    companion object {
        val Keiyoushi = MihonRepo(
            name = "Keiyoushi",
            indexUrls = listOf(
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json",
                "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.json",
            ),
            signingFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        )

        /**
         * Версии API расширений, которые хост в пакете `eu.kanade.tachiyomi` реализует. Расширение
         * с другим `extensionLib` не грузим: классы не сойдутся, и оно упадёт уже на поиске.
         */
        val SupportedLibs = setOf("1.6")

        /**
         * Рекомендованные расширения — то, что ставит одна кнопка: крупные общие каталоги языка.
         * Остальной каталог доступен в списке. MangaDex сюда не входит: он встроен в Vetro.
         */
        val Recommended: Map<String, List<String>> = mapOf(
            "ru" to listOf(
                "eu.kanade.tachiyomi.extension.ru.mangalib",
                "eu.kanade.tachiyomi.extension.ru.readmanga",
                "eu.kanade.tachiyomi.extension.ru.mintmanga",
                "eu.kanade.tachiyomi.extension.ru.senkuro",
                "eu.kanade.tachiyomi.extension.ru.mangabuff",
                "eu.kanade.tachiyomi.extension.ru.desu",
                "eu.kanade.tachiyomi.extension.ru.inkstory",
                "eu.kanade.tachiyomi.extension.ru.mangachan",
                "eu.kanade.tachiyomi.extension.ru.seimanga",
                "eu.kanade.tachiyomi.extension.ru.comx",
                "eu.kanade.tachiyomi.extension.ru.astramanga",
                "eu.kanade.tachiyomi.extension.ru.usagi",
                "eu.kanade.tachiyomi.extension.ru.mangapoisk",
            ),
            "en" to listOf(
                "eu.kanade.tachiyomi.extension.en.asurascans",
                "eu.kanade.tachiyomi.extension.en.weebcentral",
                "eu.kanade.tachiyomi.extension.en.mangakakalot",
                "eu.kanade.tachiyomi.extension.en.manganelo",
                "eu.kanade.tachiyomi.extension.en.flamecomics",
                "eu.kanade.tachiyomi.extension.all.mangafire",
                "eu.kanade.tachiyomi.extension.all.comicklive",
                "eu.kanade.tachiyomi.extension.all.mangaplus",
                "eu.kanade.tachiyomi.extension.all.webtoons",
            ),
        )
    }
}
