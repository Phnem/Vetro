package com.example.myapplication.audiobooks.data.remote

/**
 * Личные медиатеки пользователя (свой сервер). Их записи и ссылки с параметрами входа не должны
 * покидать телефон: ни в облачный синк, ни в общий каталог.
 */
object PrivateSources {
    val IDS: Set<String> = setOf("audiobookshelf", "subsonic")

    /** Обложка Audiobookshelf (`token=`) или Subsonic (`u`, `t`, `s`): ссылка открывает доступ к серверу. */
    fun carriesCredentials(url: String): Boolean {
        val names = url.substringAfter('?', "").split('&').map { it.substringBefore('=') }.toSet()
        return (url.contains("/api/items/") && "token" in names) ||
            (url.contains("/rest/") && names.containsAll(listOf("u", "t", "s")))
    }
}
