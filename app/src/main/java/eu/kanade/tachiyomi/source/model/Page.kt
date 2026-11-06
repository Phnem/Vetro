package eu.kanade.tachiyomi.source.model

import android.net.Uri

/** Страница главы: [url] — страница-обёртка у источника, [imageUrl] — сама картинка, если известна. */
class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var uri: Uri? = null,
)
