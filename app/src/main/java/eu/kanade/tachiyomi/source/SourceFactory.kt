package eu.kanade.tachiyomi.source

/** Расширение, которое отдаёт несколько источников (обычно один сайт на разных языках). */
interface SourceFactory {
    fun createSources(): List<Source>
}
