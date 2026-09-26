package com.example.myapplication.media.source

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted local credentials for user-owned playback sources. */
interface PlaybackSourceConfigStore {
    fun webDav(): WebDavConfig?
    fun saveWebDav(config: WebDavConfig)
    fun clearWebDav()
    fun personalServer(provider: PersonalMediaServerProvider): PersonalMediaServerConfig?
    fun savePersonalServer(provider: PersonalMediaServerProvider, config: PersonalMediaServerConfig)
    fun clearPersonalServer(provider: PersonalMediaServerProvider)
    fun account(kind: UserAccountKind): UserAccountConfig? = null
    fun saveAccount(kind: UserAccountKind, config: UserAccountConfig) = Unit
    fun clearAccount(kind: UserAccountKind) = Unit
}

/** Encrypted local implementation; UI depends only on [PlaybackSourceConfigStore]. */
class PlaybackSourceCredentialsStore(context: Context) : PlaybackSourceConfigStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun webDav(): WebDavConfig? {
        val baseUrl = prefs.getString(WEB_DAV_BASE_URL, null) ?: return null
        return WebDavConfig(
            baseUrl = baseUrl,
            rootPath = prefs.getString(WEB_DAV_ROOT, "").orEmpty(),
            username = prefs.getString(WEB_DAV_USERNAME, "").orEmpty(),
            password = prefs.getString(WEB_DAV_PASSWORD, "").orEmpty(),
            downloadAllowed = prefs.getBoolean(WEB_DAV_DOWNLOAD, false),
            allowInsecureHttp = prefs.getBoolean(WEB_DAV_INSECURE_HTTP, false),
        ).takeIf(WebDavConfig::isValid)
    }

    override fun saveWebDav(config: WebDavConfig) {
        require(config.isValid()) { "Invalid WebDAV configuration" }
        prefs.edit()
            .putString(WEB_DAV_BASE_URL, config.baseUrl.trim())
            .putString(WEB_DAV_ROOT, config.rootPath.trim())
            .putString(WEB_DAV_USERNAME, config.username.trim())
            .putString(WEB_DAV_PASSWORD, config.password)
            .putBoolean(WEB_DAV_DOWNLOAD, config.downloadAllowed)
            .putBoolean(WEB_DAV_INSECURE_HTTP, config.allowInsecureHttp)
            .apply()
    }

    override fun clearWebDav() {
        prefs.edit().apply {
            listOf(
                WEB_DAV_BASE_URL,
                WEB_DAV_ROOT,
                WEB_DAV_USERNAME,
                WEB_DAV_PASSWORD,
                WEB_DAV_DOWNLOAD,
                WEB_DAV_INSECURE_HTTP,
            ).forEach(::remove)
        }.apply()
    }

    override fun personalServer(provider: PersonalMediaServerProvider): PersonalMediaServerConfig? {
        val prefix = provider.credentialPrefix
        val baseUrl = prefs.getString("${prefix}_base_url", null) ?: return null
        return PersonalMediaServerConfig(
            baseUrl = baseUrl,
            userId = prefs.getString("${prefix}_user_id", "").orEmpty(),
            accessToken = prefs.getString("${prefix}_access_token", "").orEmpty(),
            downloadAllowed = prefs.getBoolean("${prefix}_download_allowed", false),
            allowInsecureHttp = prefs.getBoolean("${prefix}_allow_insecure_http", false),
        ).takeIf(PersonalMediaServerConfig::isValid)
    }

    override fun savePersonalServer(
        provider: PersonalMediaServerProvider,
        config: PersonalMediaServerConfig,
    ) {
        require(config.isValid()) { "Invalid ${provider.displayName} configuration" }
        val prefix = provider.credentialPrefix
        prefs.edit()
            .putString("${prefix}_base_url", config.baseUrl.trim())
            .putString("${prefix}_user_id", config.userId.trim())
            .putString("${prefix}_access_token", config.accessToken)
            .putBoolean("${prefix}_download_allowed", config.downloadAllowed)
            .putBoolean("${prefix}_allow_insecure_http", config.allowInsecureHttp)
            .apply()
    }

    override fun clearPersonalServer(provider: PersonalMediaServerProvider) {
        val prefix = provider.credentialPrefix
        prefs.edit().apply {
            listOf(
                "${prefix}_base_url",
                "${prefix}_user_id",
                "${prefix}_access_token",
                "${prefix}_download_allowed",
                "${prefix}_allow_insecure_http",
            ).forEach(::remove)
        }.apply()
    }

    override fun account(kind: UserAccountKind): UserAccountConfig? {
        val prefix = kind.credentialPrefix
        val username = prefs.getString("${prefix}_username", null) ?: return null
        return UserAccountConfig(
            username = username,
            password = prefs.getString("${prefix}_password", "").orEmpty(),
            baseUrl = prefs.getString("${prefix}_base_url", "").orEmpty(),
            allowInsecureHttp = prefs.getBoolean("${prefix}_allow_insecure_http", false),
        ).takeIf { it.isValidFor(kind) }
    }

    override fun saveAccount(kind: UserAccountKind, config: UserAccountConfig) {
        require(config.isValidFor(kind)) { "Invalid ${kind.displayName} account" }
        val prefix = kind.credentialPrefix
        prefs.edit()
            .putString("${prefix}_username", config.username.trim())
            .putString("${prefix}_password", config.password)
            .putString("${prefix}_base_url", config.baseUrl.trim())
            .putBoolean("${prefix}_allow_insecure_http", config.allowInsecureHttp)
            .apply()
    }

    override fun clearAccount(kind: UserAccountKind) {
        val prefix = kind.credentialPrefix
        prefs.edit().apply {
            listOf("_username", "_password", "_base_url", "_allow_insecure_http").forEach { remove(prefix + it) }
        }.apply()
    }

    private companion object {
        const val PREF_FILE = "secure_playback_sources"
        const val WEB_DAV_BASE_URL = "webdav_base_url"
        const val WEB_DAV_ROOT = "webdav_root"
        const val WEB_DAV_USERNAME = "webdav_username"
        const val WEB_DAV_PASSWORD = "webdav_password"
        const val WEB_DAV_DOWNLOAD = "webdav_download_allowed"
        const val WEB_DAV_INSECURE_HTTP = "webdav_allow_insecure_http"
    }
}
