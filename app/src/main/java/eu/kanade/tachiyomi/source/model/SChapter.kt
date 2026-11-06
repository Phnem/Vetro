package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

@Suppress("PropertyName")
interface SChapter {
    var url: String
    var name: String
    var chapter_number: Float
    var scanlator: String?
    var date_upload: Long
    var memo: JsonObject

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}

@Suppress("PropertyName")
internal class SChapterImpl : SChapter {
    override var url: String = ""
    override var name: String = ""
    /** -1 — номер не распознан (как в Mihon). */
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = JsonObject(emptyMap())
}
