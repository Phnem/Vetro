package com.example.myapplication.manga.translate

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.chaptersForLanguage
import com.example.myapplication.manga.domain.sortedForReading

/**
 * Какие главы показывать, когда включён автоперевод.
 *
 * Правило, как у человека: на выбранном языке читаем перевод, а где его ещё нет, берём оригинал и
 * переводим сами. "Королевство": на русском 890 глав, у источника 900, последние десять -
 * японские. Список получается из 890 русских глав и десяти японских, и только эти десять
 * переводятся на лету.
 *
 * Чистая логика без Android - см. ChapterTranslationPlanTest.
 */
object ChapterTranslationPlan {

    /**
     * С каких языков умеем переводить. Японский читает manga-ocr на телефоне; английский читает
     * модель с картинками из подключённого ИИ-провайдера. Порядок - предпочтение: оригинал важнее
     * чужого перевода, когда одна и та же глава есть на обоих языках.
     */
    val SOURCE_LANGUAGES: Set<String> = linkedSetOf("ja", "en")

    /**
     * Оглавление для списка глав и карточки. Без автоперевода - прежнее правило
     * ([chaptersForLanguage]). С ним к главам выбранного языка добавляются главы-оригиналы с
     * номерами, которых на этом языке нет.
     *
     * Если на выбранном языке у тайтла нет НИ ОДНОЙ главы, действует прежнее правило ("показать
     * всё"): гадать, что человек хотел увидеть вместо перевода, мы не берёмся.
     */
    fun chapters(chapters: List<MangaChapter>, preferredLanguage: String?, autoTranslate: Boolean): List<MangaChapter> {
        val baseline = chaptersForLanguage(chapters, preferredLanguage)
        if (!autoTranslate || preferredLanguage == null) return baseline

        val native = chapters.filter { it.language == preferredLanguage }
        if (native.isEmpty()) return baseline

        val covered = native.mapNotNull { it.number }.toSet()
        val originals = chapters
            .filter { it.language in SOURCE_LANGUAGES && it.language != preferredLanguage && !it.paid }
            .filter { chapter -> chapter.number != null && chapter.number !in covered }
            .groupBy { it.number }
            // Одну главу могли выложить на разных языках и несколько раз: оригинал важнее перевода,
            // из равных берём самую свежую публикацию.
            .map { (_, versions) ->
                versions.sortedWith(
                    compareBy<MangaChapter> { SOURCE_LANGUAGES.indexOf(it.language) }.thenByDescending { it.publishedAt },
                ).first()
            }
        return (native + originals).sortedForReading()
    }

    /** Эту главу при открытии нужно переводить: она на другом языке, который мы читаем. */
    fun needsTranslation(chapter: MangaChapter, preferredLanguage: String?, autoTranslate: Boolean): Boolean =
        autoTranslate &&
            preferredLanguage != null &&
            chapter.language != null &&
            chapter.language != preferredLanguage &&
            chapter.language in SOURCE_LANGUAGES
}
