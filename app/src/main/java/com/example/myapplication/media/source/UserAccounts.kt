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
) {
    OPENSUBTITLES("OpenSubtitles", "account_opensubtitles", needsServer = false),
}

data class UserAccountConfig(
    val username: String,
    val password: String,
    val baseUrl: String = "",
    val allowInsecureHttp: Boolean = false,
) {
    fun isValidFor(kind: UserAccountKind): Boolean {
        if (username.isBlank() || password.isEmpty()) return false
        if (!kind.needsServer) return true
        val url = baseUrl.trim()
        return url.startsWith("https://") || (allowInsecureHttp && url.startsWith("http://"))
    }
}
