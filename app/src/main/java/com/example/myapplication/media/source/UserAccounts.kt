package com.example.myapplication.media.source

/**
 * Учётки пользователя в сторонних сервисах, которые он подключает сам: логин и пароль его
 * собственного аккаунта. Хранятся в том же зашифрованном хранилище, что WebDAV/Jellyfin; пароль
 * никогда не попадает в состояние UI.
 */
enum class UserAccountKind(
    val displayName: String,
    val credentialPrefix: String,
    /** Нужен ли адрес сервера (свой Subsonic) или сервис один (OpenSubtitles). */
    val needsServer: Boolean,
    /** Нужен ли собственный ключ API пользователя (BYOK). */
    val needsApiKey: Boolean = false,
) {
    OPENSUBTITLES("OpenSubtitles", "account_opensubtitles", needsServer = false, needsApiKey = true),
    /** Свой сервер Subsonic / OpenSubsonic (Navidrome, Airsonic, Gonic…) — источник аудиокниг. */
    SUBSONIC("Subsonic", "account_subsonic", needsServer = true),
    /** Свой сервер Audiobookshelf — источник аудиокниг с разметкой глав. */
    AUDIOBOOKSHELF("Audiobookshelf", "account_audiobookshelf", needsServer = true),
}

data class UserAccountConfig(
    val username: String,
    val password: String,
    val baseUrl: String = "",
    val allowInsecureHttp: Boolean = false,
    /** Ключ API сервиса, выданный самому пользователю; хранится так же, как пароль. */
    val apiKey: String = "",
) {
    fun isValidFor(kind: UserAccountKind): Boolean {
        if (username.isBlank() || password.isEmpty()) return false
        if (kind.needsApiKey && apiKey.isBlank()) return false
        if (!kind.needsServer) return true
        val url = baseUrl.trim()
        return url.startsWith("https://") || (allowInsecureHttp && url.startsWith("http://"))
    }
}
