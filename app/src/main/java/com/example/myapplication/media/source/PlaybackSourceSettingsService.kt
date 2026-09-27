package com.example.myapplication.media.source

import kotlinx.coroutines.CancellationException

enum class PlaybackSourceKind {
    WEBDAV,
    JELLYFIN,
    EMBY,
    OPENSUBTITLES;

    val personalProvider: PersonalMediaServerProvider?
        get() = when (this) {
            JELLYFIN -> PersonalMediaServerProvider.JELLYFIN
            EMBY -> PersonalMediaServerProvider.EMBY
            else -> null
        }

    /** Учётка пользователя (логин + пароль), а не медиатека. */
    val account: UserAccountKind?
        get() = when (this) {
            OPENSUBTITLES -> UserAccountKind.OPENSUBTITLES
            else -> null
        }
}

data class PlaybackSourceConfigurationSummary(
    val kind: PlaybackSourceKind,
    val configured: Boolean,
)

/** Public settings fields only; stored passwords/tokens never cross this boundary. */
data class PlaybackSourcePublicDraft(
    val kind: PlaybackSourceKind,
    val baseUrl: String = "",
    val rootPath: String = "",
    val username: String = "",
    val userId: String = "",
    val hasStoredSecret: Boolean = false,
    /** Сохранён ли ключ API учётки; сам ключ сюда не попадает. */
    val hasStoredApiKey: Boolean = false,
    val downloadAllowed: Boolean = false,
    val allowInsecureHttp: Boolean = false,
)

interface PlaybackSourceConnectionTester {
    suspend fun testWebDav(config: WebDavConfig): Boolean
    suspend fun testPersonalServer(
        provider: PersonalMediaServerProvider,
        config: PersonalMediaServerConfig,
    ): Boolean
    suspend fun testAccount(kind: UserAccountKind, config: UserAccountConfig): Boolean = false
}

interface PlaybackSourceSettingsService {
    fun summaries(): List<PlaybackSourceConfigurationSummary>
    fun draft(kind: PlaybackSourceKind): PlaybackSourcePublicDraft
    fun save(draft: PlaybackSourcePublicDraft, replacementSecret: String, replacementApiKey: String = ""): Boolean
    fun remove(kind: PlaybackSourceKind)
    suspend fun test(draft: PlaybackSourcePublicDraft, replacementSecret: String, replacementApiKey: String = ""): Boolean?
}

class DefaultPlaybackSourceSettingsService(
    private val store: PlaybackSourceConfigStore,
    private val connectionTester: PlaybackSourceConnectionTester,
    /**
     * Учётки, которые сейчас можно подключить. OpenSubtitles без ключа приложения не работает —
     * такой строки в настройках нет вовсе, а не «подключено, но не работает».
     */
    private val availableAccounts: () -> Set<UserAccountKind> = { UserAccountKind.entries.toSet() },
) : PlaybackSourceSettingsService {
    override fun summaries(): List<PlaybackSourceConfigurationSummary> = baseSummaries() +
        PlaybackSourceKind.entries.mapNotNull { kind ->
            val account = kind.account?.takeIf { it in availableAccounts() } ?: return@mapNotNull null
            PlaybackSourceConfigurationSummary(kind, store.account(account) != null)
        }

    private fun baseSummaries(): List<PlaybackSourceConfigurationSummary> = listOf(
        PlaybackSourceConfigurationSummary(PlaybackSourceKind.WEBDAV, store.webDav() != null),
        PlaybackSourceConfigurationSummary(
            PlaybackSourceKind.JELLYFIN,
            store.personalServer(PersonalMediaServerProvider.JELLYFIN) != null,
        ),
        PlaybackSourceConfigurationSummary(
            PlaybackSourceKind.EMBY,
            store.personalServer(PersonalMediaServerProvider.EMBY) != null,
        ),
    )

    override fun draft(kind: PlaybackSourceKind): PlaybackSourcePublicDraft = when (kind) {
        PlaybackSourceKind.WEBDAV -> store.webDav()?.let { config ->
            PlaybackSourcePublicDraft(
                kind = kind,
                baseUrl = config.baseUrl,
                rootPath = config.rootPath,
                username = config.username,
                hasStoredSecret = true,
                downloadAllowed = config.downloadAllowed,
                allowInsecureHttp = config.allowInsecureHttp,
            )
        }
        PlaybackSourceKind.OPENSUBTITLES -> store.account(UserAccountKind.OPENSUBTITLES)?.let { config ->
            PlaybackSourcePublicDraft(
                kind = kind,
                baseUrl = config.baseUrl,
                username = config.username,
                hasStoredSecret = true,
                hasStoredApiKey = config.apiKey.isNotBlank(),
                allowInsecureHttp = config.allowInsecureHttp,
            )
        }
        else -> kind.personalProvider?.let(store::personalServer)?.let { config ->
            PlaybackSourcePublicDraft(
                kind = kind,
                baseUrl = config.baseUrl,
                userId = config.userId,
                hasStoredSecret = true,
                downloadAllowed = config.downloadAllowed,
                allowInsecureHttp = config.allowInsecureHttp,
            )
        }
    } ?: PlaybackSourcePublicDraft(kind)

    override fun save(draft: PlaybackSourcePublicDraft, replacementSecret: String, replacementApiKey: String): Boolean =
        runCatching {
            when (draft.kind) {
                PlaybackSourceKind.WEBDAV -> store.saveWebDav(requireNotNull(webDavConfig(draft, replacementSecret)))
                PlaybackSourceKind.OPENSUBTITLES -> {
                    val account = requireNotNull(draft.kind.account)
                    store.saveAccount(account, requireNotNull(accountConfig(draft, replacementSecret, account, replacementApiKey)))
                }
                else -> {
                    val provider = requireNotNull(draft.kind.personalProvider)
                    store.savePersonalServer(
                        provider,
                        requireNotNull(personalConfig(draft, replacementSecret, provider)),
                    )
                }
            }
        }.isSuccess

    override fun remove(kind: PlaybackSourceKind) {
        when (kind) {
            PlaybackSourceKind.WEBDAV -> store.clearWebDav()
            PlaybackSourceKind.OPENSUBTITLES -> store.clearAccount(requireNotNull(kind.account))
            else -> store.clearPersonalServer(requireNotNull(kind.personalProvider))
        }
    }

    override suspend fun test(
        draft: PlaybackSourcePublicDraft,
        replacementSecret: String,
        replacementApiKey: String,
    ): Boolean? = try {
        when (draft.kind) {
            PlaybackSourceKind.WEBDAV -> webDavConfig(draft, replacementSecret)
                ?.let { connectionTester.testWebDav(it) }
            PlaybackSourceKind.OPENSUBTITLES -> draft.kind.account?.let { account ->
                accountConfig(draft, replacementSecret, account, replacementApiKey)?.let { connectionTester.testAccount(account, it) }
            }
            else -> draft.kind.personalProvider?.let { provider ->
                personalConfig(draft, replacementSecret, provider)
                    ?.let { connectionTester.testPersonalServer(provider, it) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun webDavConfig(
        draft: PlaybackSourcePublicDraft,
        replacementSecret: String,
    ): WebDavConfig? {
        val saved = store.webDav()
        val secret = replacementSecret.ifBlank {
            saved?.password?.takeIf { saved.canReuseSecretFor(draft) }.orEmpty()
        }
        return WebDavConfig(
            baseUrl = draft.baseUrl,
            rootPath = draft.rootPath,
            username = draft.username,
            password = secret,
            downloadAllowed = draft.downloadAllowed,
            allowInsecureHttp = draft.allowInsecureHttp,
        ).takeIf(WebDavConfig::isValid)
    }

    private fun accountConfig(
        draft: PlaybackSourcePublicDraft,
        replacementSecret: String,
        kind: UserAccountKind,
        replacementApiKey: String = "",
    ): UserAccountConfig? {
        val saved = store.account(kind)
        // Сохранённые пароль и ключ переиспользуются только для того же логина на том же сервере.
        val sameScope = saved != null && saved.username == draft.username.trim() && saved.baseUrl == draft.baseUrl.trim()
        val secret = replacementSecret.ifBlank { saved?.password?.takeIf { sameScope }.orEmpty() }
        val apiKey = replacementApiKey.trim().ifBlank { saved?.apiKey?.takeIf { sameScope }.orEmpty() }
        return UserAccountConfig(
            username = draft.username.trim(),
            password = secret,
            baseUrl = draft.baseUrl.trim(),
            allowInsecureHttp = draft.allowInsecureHttp,
            apiKey = apiKey,
        ).takeIf { it.isValidFor(kind) }
    }

    private fun personalConfig(
        draft: PlaybackSourcePublicDraft,
        replacementSecret: String,
        provider: PersonalMediaServerProvider,
    ): PersonalMediaServerConfig? {
        val saved = store.personalServer(provider)
        val secret = replacementSecret.ifBlank {
            saved?.accessToken?.takeIf { saved.canReuseSecretFor(draft, provider) }.orEmpty()
        }
        return PersonalMediaServerConfig(
            baseUrl = draft.baseUrl,
            userId = draft.userId,
            accessToken = secret,
            downloadAllowed = draft.downloadAllowed,
            allowInsecureHttp = draft.allowInsecureHttp,
        ).takeIf(PersonalMediaServerConfig::isValid)
    }
}

private fun WebDavConfig.canReuseSecretFor(draft: PlaybackSourcePublicDraft): Boolean {
    val candidate = copy(
        baseUrl = draft.baseUrl,
        rootPath = draft.rootPath,
        username = draft.username,
    )
    return username == draft.username && runCatching { credentialRef() == candidate.credentialRef() }
        .getOrDefault(false)
}

private fun PersonalMediaServerConfig.canReuseSecretFor(
    draft: PlaybackSourcePublicDraft,
    provider: PersonalMediaServerProvider,
): Boolean {
    val candidate = copy(baseUrl = draft.baseUrl, userId = draft.userId)
    return runCatching { credentialRef(provider) == candidate.credentialRef(provider) }
        .getOrDefault(false)
}
