package com.example.myapplication.sync

import com.example.myapplication.AppScope
import com.example.myapplication.network.AppJson
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.network.AniListFlatListEntry
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.anilist.type.MediaListStatus
import com.phnem.vetro.BuildConfig
import com.phnem.vetro.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val TAG = "ExternalListSync"
/** Если два кандидата почти с одинаковым баллом — не связываем (избегаем неверного merge). */
private const val TITLE_MATCH_AMBIGUITY_GAP = 0.04
private const val SHIKI_SEARCH_VALIDATE_LIMIT = 20
private const val PREFS = "external_list_sync_prefs"
private const val KEY_PENDING_SERVICE = "pending_list_service"
private const val KEY_PENDING_ACTION = "pending_list_action"
private const val KEY_MAL_PKCE = "mal_oauth_code_verifier"

/**
 * Онлайн-БД (Shikimori / MAL / AniList): импорт/экспорт/синк → браузер (OAuth) → deep link обратно в приложение.
 * Не использует Dropbox.
 */
class ExternalListSyncCoordinator(
    context: Context,
    private val animeLocalDataSource: AnimeLocalDataSource,
    private val animeRepository: AnimeRepository,
    private val addFromApiUseCase: AddFromApiUseCase,
    private val shikiRateLimiter: TokenBucketRateLimiter,
    private val aniListRemoteDataSource: AniListRemoteDataSource,
    rootHttpClient: OkHttpClient,
    appScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Общая область процесса (AppScope) вместо своей CoroutineScope(SupervisorJob() + IO).
    private val scope: CoroutineScope = appScope
    /** Активный импорт/экспорт/синк списка (отмена с оверлея). */
    private var listSyncJob: Job? = null
    // Общий пул соединений с остальной сетью, свои таймауты.
    private val http = rootHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = AppJson

    data class ExternalListSyncUiState(
        val isRunning: Boolean = false,
        val message: String = "",
        val processed: Int = 0,
        val total: Int = 0
    )

    data class SyncResultSummary(
        val added: Int = 0,
        val updated: Int = 0,
        val pushed: Int = 0,
        val skipped: Int = 0,
        val errors: Int = 0
    )

    private data class RemoteRate(
        val rateId: Int,
        val userId: Int,
        val animeId: Int,
        val titleEn: String,
        val titleRu: String?,
        val episodes: Int,
        val status: String
    )

    private data class ShikiSnapshot(
        val userId: Int,
        val rates: List<RemoteRate>
    )

    private val _syncUiState = MutableStateFlow(ExternalListSyncUiState())
    val syncUiState: StateFlow<ExternalListSyncUiState> = _syncUiState.asStateFlow()

    /** Остановить текущую синхронизацию онлайн-списка и скрыть оверлей. */
    fun cancelListSync() {
        val job = listSyncJob
        listSyncJob = null
        if (job?.isActive == true) {
            job.cancel(CancellationException("user dismissed list sync overlay"))
            toastMain(appContext.getString(R.string.list_sync_cancelled))
        }
        if (_syncUiState.value.isRunning) {
            _syncUiState.value = ExternalListSyncUiState()
        }
    }

    fun startListServiceActionOrAuthorize(activity: Activity, service: ExternalListService, action: ListServiceAction) {
        if (_syncUiState.value.isRunning) {
            toastMain("Дождитесь завершения текущей синхронизации")
            return
        }
        if (service == ExternalListService.MYANIMELIST) return
        if (!hasClientId(service)) {
            toastMain(appContext.getString(R.string.external_list_oauth_opening_registration))
            openOAuthAppRegistrationPage(activity, service)
            return
        }
        setPendingListAction(service, action)
        if (hasToken(service)) {
            listSyncJob?.cancel()
            listSyncJob = scope.launch {
                try {
                    val pending = consumePendingListAction() ?: return@launch
                    executeListServiceAction(pending.first, pending.second)
                } finally {
                    listSyncJob = null
                }
            }
        } else {
            openAuthorizeInBrowser(activity, service)
        }
    }

    /** @return true если это наш OAuth callback. */
    fun handleOAuthRedirect(uri: Uri?): Boolean {
        val u = uri ?: return false
        val service = resolveServiceByRedirect(u) ?: return false
        val code = u.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            val err = u.getQueryParameter("error_description") ?: u.getQueryParameter("error")
            if (err != null) toastMain(err)
            return true
        }
        scope.launch {
            try {
                exchangeAndStoreTokens(service, code)
                toastMain(appContext.getString(R.string.external_list_oauth_success))
                val pending = consumePendingListAction()
                if (pending != null) {
                    listSyncJob?.cancel()
                    listSyncJob = scope.launch {
                        try {
                            executeListServiceAction(pending.first, pending.second)
                        } finally {
                            listSyncJob = null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                toastMain(e.message ?: appContext.getString(R.string.external_list_oauth_error))
            }
        }
        return true
    }

    fun clearAllListServiceTokens() {
        prefs.edit {
            ExternalListService.entries.forEach { svc ->
                remove(tokenKey(svc, "access"))
                remove(tokenKey(svc, "refresh"))
                remove(tokenKey(svc, "expiry"))
            }
        }
    }

    private fun hasClientId(service: ExternalListService): Boolean = when (service) {
        ExternalListService.SHIKIMORI -> BuildConfig.SHIKIMORI_CLIENT_ID.isNotBlank()
        ExternalListService.MYANIMELIST -> BuildConfig.MAL_CLIENT_ID.isNotBlank()
        ExternalListService.ANILIST -> BuildConfig.ANILIST_CLIENT_ID.isNotBlank()
    }

    private fun hasClientSecret(service: ExternalListService): Boolean = when (service) {
        ExternalListService.SHIKIMORI -> BuildConfig.SHIKIMORI_CLIENT_SECRET.isNotBlank()
        ExternalListService.MYANIMELIST -> BuildConfig.MAL_CLIENT_SECRET.isNotBlank()
        ExternalListService.ANILIST -> BuildConfig.ANILIST_CLIENT_SECRET.isNotBlank()
    }

    /** Страница создания OAuth-приложения (когда в сборке ещё нет client id). */
    private fun openOAuthAppRegistrationPage(activity: Activity, service: ExternalListService) {
        val url = when (service) {
            ExternalListService.SHIKIMORI -> "https://shikimori.one/oauth/applications"
            ExternalListService.MYANIMELIST -> "https://myanimelist.net/apiconfig"
            ExternalListService.ANILIST -> "https://anilist.co/settings/developer"
        }
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun hasToken(service: ExternalListService): Boolean =
        prefs.getString(tokenKey(service, "access"), null)?.isNotBlank() == true ||
            prefs.getString(tokenKey(service, "refresh"), null)?.isNotBlank() == true

    private fun redirectUri(service: ExternalListService): String = when (service) {
        ExternalListService.SHIKIMORI ->
            BuildConfig.SHIKIMORI_REDIRECT_URI.ifBlank { "vetro://oauth/shikimori/callback" }
        ExternalListService.MYANIMELIST ->
            BuildConfig.MAL_REDIRECT_URI.ifBlank { "vetro://oauth/myanimelist/callback" }
        ExternalListService.ANILIST ->
            BuildConfig.ANILIST_REDIRECT_URI.ifBlank { "vetro://callback" }
    }

    private fun resolveServiceByRedirect(uri: Uri): ExternalListService? {
        return ExternalListService.entries.firstOrNull { svc ->
            val expected = Uri.parse(redirectUri(svc))
            uri.scheme == expected.scheme &&
                uri.host == expected.host &&
                normalizePath(uri.path) == normalizePath(expected.path)
        }
    }

    private fun normalizePath(path: String?): String = when {
        path.isNullOrBlank() || path == "/" -> ""
        else -> path.trimEnd('/')
    }

    private fun openAuthorizeInBrowser(activity: Activity, service: ExternalListService) {
        val redirect = redirectUri(service)
        val url = when (service) {
            ExternalListService.SHIKIMORI ->
                Uri.parse("https://shikimori.one/oauth/authorize").buildUpon()
                    .appendQueryParameter("client_id", BuildConfig.SHIKIMORI_CLIENT_ID)
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("scope", "user_rates")
                    .build()
                    .toString()
            ExternalListService.MYANIMELIST -> {
                val verifier = newPkceVerifier()
                prefs.edit { putString(KEY_MAL_PKCE, verifier) }
                Uri.parse("https://myanimelist.net/v1/oauth2/authorize").buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", BuildConfig.MAL_CLIENT_ID)
                    // MAL currently supports PKCE "plain": challenge must equal verifier.
                    .appendQueryParameter("code_challenge", verifier)
                    .appendQueryParameter("code_challenge_method", "plain")
                    .appendQueryParameter("redirect_uri", redirect)
                    .build()
                    .toString()
            }
            ExternalListService.ANILIST ->
                Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                    .appendQueryParameter("client_id", BuildConfig.ANILIST_CLIENT_ID)
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("response_type", "code")
                    .build()
                    .toString()
        }
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private suspend fun exchangeAndStoreTokens(service: ExternalListService, code: String) =
        withContext(Dispatchers.IO) {
            if (service == ExternalListService.SHIKIMORI && !hasClientSecret(service)) {
                error(appContext.getString(R.string.external_list_oauth_add_secret_rebuild))
            }
            val redirect = redirectUri(service)
            val req = when (service) {
                ExternalListService.SHIKIMORI -> {
                    val body = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", BuildConfig.SHIKIMORI_CLIENT_ID)
                        .add("client_secret", BuildConfig.SHIKIMORI_CLIENT_SECRET)
                        .add("code", code)
                        .add("redirect_uri", redirect)
                        .build()
                    Request.Builder()
                        .url("https://shikimori.one/oauth/token")
                        .post(body)
                        .header("User-Agent", "Vetro/${BuildConfig.VERSION_NAME}")
                        .build()
                }
                ExternalListService.MYANIMELIST -> {
                    val verifier = prefs.getString(KEY_MAL_PKCE, null)
                        ?: error("MAL PKCE verifier missing")
                    prefs.edit { remove(KEY_MAL_PKCE) }
                    val bodyBuilder = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", BuildConfig.MAL_CLIENT_ID)
                        .add("code", code)
                        .add("redirect_uri", redirect)
                        .add("code_verifier", verifier)
                    if (BuildConfig.MAL_CLIENT_SECRET.isNotBlank()) {
                        bodyBuilder.add("client_secret", BuildConfig.MAL_CLIENT_SECRET)
                    }
                    val body = bodyBuilder.build()
                    Request.Builder()
                        .url("https://myanimelist.net/v1/oauth2/token")
                        .post(body)
                        .build()
                }
                ExternalListService.ANILIST -> {
                    if (BuildConfig.ANILIST_CLIENT_SECRET.isBlank()) {
                        error(appContext.getString(R.string.external_list_oauth_add_secret_rebuild))
                    }
                    val body = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", BuildConfig.ANILIST_CLIENT_ID)
                        .add("client_secret", BuildConfig.ANILIST_CLIENT_SECRET)
                        .add("redirect_uri", redirect)
                        .add("code", code)
                        .build()
                    Request.Builder()
                        .url("https://anilist.co/api/v2/oauth/token")
                        .post(body)
                        .header("Accept", "application/json")
                        .build()
                }
            }
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
                val parsed = runCatching { json.decodeFromString(OAuthTokenJson.serializer(), raw) }
                    .getOrElse { error("Invalid token response: $raw") }
                val exp = parsed.expiresIn?.let { System.currentTimeMillis() + it * 1000L } ?: 0L
                prefs.edit {
                    putString(tokenKey(service, "access"), parsed.accessToken)
                    parsed.refreshToken?.let { putString(tokenKey(service, "refresh"), it) }
                    if (exp > 0L) putLong(tokenKey(service, "expiry"), exp)
                }
            }
        }

    private suspend fun executeListServiceAction(service: ExternalListService, action: ListServiceAction) {
        if (service == ExternalListService.MYANIMELIST) return
        if (service != ExternalListService.SHIKIMORI && service != ExternalListService.ANILIST) {
            toastMain("Для $service логика пока не подключена")
            return
        }
        if (!hasToken(service)) {
            val msg = when (service) {
                ExternalListService.SHIKIMORI -> "Сначала выполните вход в Shikimori"
                ExternalListService.ANILIST -> "Сначала выполните вход в AniList"
                else -> "Сначала выполните вход"
            }
            toastMain(msg)
            return
        }

        _syncUiState.value = ExternalListSyncUiState(isRunning = true, message = "Получение данных с сервера...")
        val summary = try {
            when (service) {
                ExternalListService.SHIKIMORI -> when (action) {
                    ListServiceAction.PULL -> runPullShikimori()
                    ListServiceAction.PUSH -> runPushShikimori()
                    ListServiceAction.SYNC -> runSyncShikimori()
                }
                ExternalListService.ANILIST -> when (action) {
                    ListServiceAction.PULL -> runPullAnilist()
                    ListServiceAction.PUSH -> runPushAnilist()
                    ListServiceAction.SYNC -> runSyncAnilist()
                }
                else -> SyncResultSummary()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "List sync failed", t)
            toastMain("Ошибка синхронизации: ${t.message ?: "unknown"}")
            SyncResultSummary(errors = 1)
        } finally {
            _syncUiState.value = ExternalListSyncUiState()
        }

        toastMain(
            "Успешно. Добавлено: ${summary.added}, Обновлено: ${summary.updated}, " +
                "Отправлено: ${summary.pushed}, Пропущено: ${summary.skipped}, Ошибки: ${summary.errors}"
        )
    }

    private suspend fun runPullShikimori(): SyncResultSummary {
        val snapshot = fetchShikimoriSnapshot()
        val rates = snapshot.rates
        var localList = animeLocalDataSource.getAllAnimeList()
        val usedLocalIds = mutableSetOf<String>()
        val total = rates.size.coerceAtLeast(1)
        var added = 0
        var updated = 0
        var skipped = 0
        var errors = 0

        rates.forEachIndexed { idx, remote ->
            updateProgress("Обработка: ${idx + 1} из ${rates.size}...", idx + 1, total)
            val local = pickBestLocalForRemote(remote, localList, usedLocalIds)
            if (local != null) usedLocalIds.add(local.id)
            if (local == null) {
                val imported = importMissingFromRemote(remote)
                if (imported) {
                    added++
                    localList = animeLocalDataSource.getAllAnimeList()
                } else errors++
            } else {
                if (remote.episodes > local.episodes) {
                    runCatching {
                        animeLocalDataSource.updateAnime(local.copy(episodes = remote.episodes))
                    }.onSuccess { updated++ }
                        .onFailure {
                            errors++
                            Log.w(TAG, "Failed to update local progress for ${local.title}", it)
                        }
                } else {
                    skipped++
                }
            }
        }
        return SyncResultSummary(added = added, updated = updated, skipped = skipped, errors = errors)
    }

    private suspend fun runPushShikimori(): SyncResultSummary {
        val snapshot = fetchShikimoriSnapshot()
        val localList = animeLocalDataSource.getAllAnimeList()
        val usedRemoteRateIds = mutableSetOf<Int>()
        val total = localList.size.coerceAtLeast(1)
        var pushed = 0
        var updated = 0
        var skipped = 0
        var errors = 0

        localList.forEachIndexed { idx, local ->
            updateProgress("Обработка: ${idx + 1} из ${localList.size}...", idx + 1, total)
            val remote = pickBestRemoteForLocal(local, snapshot.rates, usedRemoteRateIds)
            if (remote != null) usedRemoteRateIds.add(remote.rateId)
            if (remote == null) {
                val ok = runCatching {
                    val animeId = findShikimoriAnimeIdByTitle(local.title) ?: return@runCatching false
                    updateMessage("Отправка данных...")
                    createShikiRate(
                        userId = snapshot.userId,
                        animeId = animeId,
                        status = resolveShikiStatus(local),
                        episodes = local.episodes
                    )
                    delay(300)
                    true
                }.getOrElse {
                    Log.w(TAG, "Push create failed for ${local.title}", it)
                    false
                }
                if (ok) pushed++ else errors++
            } else if (local.episodes > remote.episodes) {
                val ok = runCatching {
                    updateMessage("Отправка данных...")
                    patchShikiRate(
                        rateId = remote.rateId,
                        status = resolveShikiStatus(local),
                        episodes = local.episodes
                    )
                    delay(300)
                    true
                }.getOrElse {
                    Log.w(TAG, "Push patch failed for ${local.title}", it)
                    false
                }
                if (ok) updated++ else errors++
            } else {
                skipped++
            }
        }

        return SyncResultSummary(updated = updated, pushed = pushed, skipped = skipped, errors = errors)
    }

    private suspend fun runSyncShikimori(): SyncResultSummary {
        val snapshot = fetchShikimoriSnapshot()
        val remoteRates = snapshot.rates
        var localWorking = animeLocalDataSource.getAllAnimeList()
        val usedLocalIds = mutableSetOf<String>()
        val total = (remoteRates.size + localWorking.size).coerceAtLeast(1)
        var processed = 0
        var added = 0
        var updated = 0
        var pushed = 0
        var skipped = 0
        var errors = 0

        // 1) remote -> local (missing + max progress)
        remoteRates.forEach { remote ->
            processed++
            updateProgress("Обработка: $processed из ${remoteRates.size + localWorking.size}...", processed, total)
            val local = pickBestLocalForRemote(remote, localWorking, usedLocalIds)
            if (local == null) {
                val ok = importMissingFromRemote(remote)
                if (ok) {
                    added++
                    localWorking = animeLocalDataSource.getAllAnimeList()
                } else errors++
            } else {
                usedLocalIds.add(local.id)
                val nextEpisodes = maxOf(local.episodes, remote.episodes)
                if (nextEpisodes > local.episodes) {
                    runCatching {
                        animeLocalDataSource.updateAnime(local.copy(episodes = nextEpisodes))
                    }.onSuccess { updated++ }
                        .onFailure {
                            errors++
                            Log.w(TAG, "Sync update local failed for ${local.title}", it)
                        }
                } else {
                    skipped++
                }
            }
        }

        // 2) local -> remote (missing + max progress)
        val localAfterPull = animeLocalDataSource.getAllAnimeList()
        val remoteAfterPull = fetchShikimoriSnapshot().rates
        val usedRemoteRateIds = mutableSetOf<Int>()
        localAfterPull.forEach { local ->
            processed++
            updateProgress("Обработка: $processed из ${remoteRates.size + localAfterPull.size}...", processed, total)
            val remote = pickBestRemoteForLocal(local, remoteAfterPull, usedRemoteRateIds)
            if (remote != null) usedRemoteRateIds.add(remote.rateId)
            if (remote == null) {
                val ok = runCatching {
                    val animeId = findShikimoriAnimeIdByTitle(local.title) ?: return@runCatching false
                    updateMessage("Отправка данных...")
                    createShikiRate(
                        userId = snapshot.userId,
                        animeId = animeId,
                        status = resolveShikiStatus(local),
                        episodes = local.episodes
                    )
                    delay(300)
                    true
                }.getOrElse {
                    Log.w(TAG, "Sync create remote failed for ${local.title}", it)
                    false
                }
                if (ok) pushed++ else errors++
            } else if (local.episodes > remote.episodes) {
                val ok = runCatching {
                    updateMessage("Отправка данных...")
                    patchShikiRate(
                        rateId = remote.rateId,
                        status = resolveShikiStatus(local),
                        episodes = local.episodes
                    )
                    delay(300)
                    true
                }.getOrElse {
                    Log.w(TAG, "Sync patch remote failed for ${local.title}", it)
                    false
                }
                if (ok) updated++ else errors++
            } else {
                skipped++
            }
        }

        return SyncResultSummary(
            added = added,
            updated = updated,
            pushed = pushed,
            skipped = skipped,
            errors = errors
        )
    }

    private fun anilistAccessToken(): String? =
        prefs.getString(tokenKey(ExternalListService.ANILIST, "access"), null)?.takeIf { it.isNotBlank() }

    private suspend fun fetchAnilistFlatList(token: String): List<AniListFlatListEntry> {
        updateMessage("Получение данных с сервера...")
        val userId = aniListRemoteDataSource.getViewerId(token).getOrElse { throw it }
        return aniListRemoteDataSource.fetchAllAnimeListEntries(token, userId).getOrElse { throw it }
    }

    private fun titleCandidatesAnilist(row: AniListFlatListEntry) =
        listOfNotNull(row.romaji.takeIf { it.isNotBlank() }, row.english.takeIf { it.isNotBlank() })

    private fun pickBestLocalForAnilist(
        row: AniListFlatListEntry,
        locals: List<Anime>,
        usedLocalIds: Set<String>
    ): Anime? {
        val cands = titleCandidatesAnilist(row)
        val scored = locals.asSequence()
            .filter { it.id !in usedLocalIds }
            .mapNotNull { local ->
                val s = TitleMatcher.bestScore(local.title, cands)
                if (s >= TitleMatcher.MATCH_THRESHOLD) local to s else null
            }
            .sortedByDescending { it.second }
            .toList()
        if (scored.isEmpty()) return null
        if (scored.size >= 2 && scored[0].second - scored[1].second < TITLE_MATCH_AMBIGUITY_GAP) return null
        return scored[0].first
    }

    private fun pickBestAnilistRowForLocal(
        local: Anime,
        rows: List<AniListFlatListEntry>,
        usedMediaIds: Set<Int>
    ): AniListFlatListEntry? {
        val scored = rows.asSequence()
            .filter { it.mediaId !in usedMediaIds }
            .mapNotNull { row ->
                val s = TitleMatcher.bestScore(local.title, titleCandidatesAnilist(row))
                if (s >= TitleMatcher.MATCH_THRESHOLD) row to s else null
            }
            .sortedByDescending { it.second }
            .toList()
        if (scored.isEmpty()) return null
        if (scored.size >= 2 && scored[0].second - scored[1].second < TITLE_MATCH_AMBIGUITY_GAP) return null
        return scored[0].first
    }

    private suspend fun importMissingFromAnilist(mediaId: Int, progress: Int): Boolean {
        updateMessage("Получение данных с сервера...")
        val apiResult = aniListRemoteDataSource.mediaByAnilistId(mediaId).getOrNull() ?: return false
        return addFromApiUseCase(apiResult).fold(
            onSuccess = {
                val candidates = listOfNotNull(
                    apiResult.title.takeIf { it.isNotBlank() },
                    apiResult.altTitle?.takeIf { it.isNotBlank() }
                )
                val inserted = animeLocalDataSource.getAllAnimeList()
                    .firstOrNull { local -> TitleMatcher.isMatch(local.title, candidates) }
                if (inserted != null && progress > inserted.episodes) {
                    runCatching { animeLocalDataSource.updateAnime(inserted.copy(episodes = progress)) }
                        .onFailure { e -> Log.w(TAG, "Failed to apply AniList progress for $mediaId", e) }
                }
                true
            },
            onFailure = { e ->
                Log.w(TAG, "Add from AniList API failed for mediaId=$mediaId", e)
                false
            }
        )
    }

    private suspend fun findAnilistMediaIdByTitle(title: String): Int? {
        val results = aniListRemoteDataSource.searchAnime(title, limit = 20).getOrElse {
            Log.w(TAG, "AniList search failed for \"$title\"", it)
            emptyList()
        }
        if (results.isEmpty()) {
            Log.w(TAG, "AniList search empty for \"$title\" (orphan title)")
            return null
        }
        val scored = results.mapNotNull { r ->
            val cands = listOfNotNull(r.title.takeIf { it.isNotBlank() }, r.altTitle?.takeIf { it.isNotBlank() })
            val s = TitleMatcher.bestScore(title, cands)
            if (s >= TitleMatcher.MATCH_THRESHOLD) r to s else null
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) {
            Log.w(TAG, "AniList search: no title match for \"$title\"")
            return null
        }
        if (scored.size >= 2 && scored[0].second - scored[1].second < TITLE_MATCH_AMBIGUITY_GAP) {
            Log.w(TAG, "AniList search ambiguous for \"$title\"")
            return null
        }
        return scored[0].first.externalId?.toIntOrNull()
    }

    private suspend fun resolveAniListStatus(local: Anime): MediaListStatus {
        if (local.episodes <= 0) return MediaListStatus.PLANNING

        val cat = local.categoryType.trim()
        if (cat.equals("MOVIE", ignoreCase = true)) return MediaListStatus.COMPLETED

        val contentType = when (cat.uppercase()) {
            "SERIES" -> AppContentType.SERIES
            else -> AppContentType.ANIME
        }

        val total = runCatching {
            animeRepository.findTotalEpisodes(local.title, local.categoryType, contentType).getOrNull()?.first
        }.getOrNull()?.takeIf { it > 0 }

        return when {
            total != null && local.episodes >= total -> MediaListStatus.COMPLETED
            total != null -> MediaListStatus.CURRENT
            else -> MediaListStatus.COMPLETED
        }
    }

    private suspend fun pushAnilistUpsert(token: String, mediaId: Int, local: Anime): Boolean {
        updateMessage("Отправка данных...")
        val ok = aniListRemoteDataSource.saveMediaListEntry(
            token,
            mediaId,
            resolveAniListStatus(local),
            local.episodes
        ).isSuccess
        delay(700)
        if (!ok) Log.w(TAG, "AniList upsert failed mediaId=$mediaId title=${local.title}")
        return ok
    }

    private suspend fun runPullAnilist(): SyncResultSummary {
        val token = anilistAccessToken() ?: error("AniList token missing")
        val remotes = fetchAnilistFlatList(token)
        var localList = animeLocalDataSource.getAllAnimeList()
        val usedLocalIds = mutableSetOf<String>()
        val total = remotes.size.coerceAtLeast(1)
        var added = 0
        var updated = 0
        var skipped = 0
        var errors = 0

        remotes.forEachIndexed { idx, remote ->
            updateProgress("Обработка: ${idx + 1} из ${remotes.size}...", idx + 1, total)
            val local = pickBestLocalForAnilist(remote, localList, usedLocalIds)
            if (local != null) usedLocalIds.add(local.id)
            if (local == null) {
                if (importMissingFromAnilist(remote.mediaId, remote.progress)) {
                    added++
                    localList = animeLocalDataSource.getAllAnimeList()
                } else errors++
            } else {
                if (remote.progress > local.episodes) {
                    runCatching {
                        animeLocalDataSource.updateAnime(local.copy(episodes = remote.progress))
                    }.onSuccess { updated++ }
                        .onFailure {
                            errors++
                            Log.w(TAG, "Failed to update local from AniList for ${local.title}", it)
                        }
                } else {
                    skipped++
                }
            }
        }
        return SyncResultSummary(added = added, updated = updated, skipped = skipped, errors = errors)
    }

    private suspend fun runPushAnilist(): SyncResultSummary {
        val token = anilistAccessToken() ?: error("AniList token missing")
        val remotes = fetchAnilistFlatList(token)
        val localList = animeLocalDataSource.getAllAnimeList()
        val usedMediaIds = mutableSetOf<Int>()
        val total = localList.size.coerceAtLeast(1)
        var pushed = 0
        var updated = 0
        var skipped = 0
        var errors = 0

        localList.forEachIndexed { idx, local ->
            updateProgress("Обработка: ${idx + 1} из ${localList.size}...", idx + 1, total)
            val remote = pickBestAnilistRowForLocal(local, remotes, usedMediaIds)
            if (remote != null) usedMediaIds.add(remote.mediaId)
            if (remote == null) {
                val mid = findAnilistMediaIdByTitle(local.title)
                if (mid == null) {
                    skipped++
                } else if (pushAnilistUpsert(token, mid, local)) pushed++ else errors++
            } else if (local.episodes > remote.progress) {
                if (pushAnilistUpsert(token, remote.mediaId, local)) updated++ else errors++
            } else {
                skipped++
            }
        }
        return SyncResultSummary(updated = updated, pushed = pushed, skipped = skipped, errors = errors)
    }

    private suspend fun runSyncAnilist(): SyncResultSummary {
        val token = anilistAccessToken() ?: error("AniList token missing")
        val remoteRows = fetchAnilistFlatList(token)
        var localWorking = animeLocalDataSource.getAllAnimeList()
        val usedLocalIds = mutableSetOf<String>()
        val total = (remoteRows.size + localWorking.size).coerceAtLeast(1)
        var processed = 0
        var added = 0
        var updated = 0
        var pushed = 0
        var skipped = 0
        var errors = 0

        remoteRows.forEach { remote ->
            processed++
            updateProgress("Обработка: $processed из ${remoteRows.size + localWorking.size}...", processed, total)
            val local = pickBestLocalForAnilist(remote, localWorking, usedLocalIds)
            if (local == null) {
                if (importMissingFromAnilist(remote.mediaId, remote.progress)) {
                    added++
                    localWorking = animeLocalDataSource.getAllAnimeList()
                } else errors++
            } else {
                usedLocalIds.add(local.id)
                val nextEpisodes = maxOf(local.episodes, remote.progress)
                if (nextEpisodes > local.episodes) {
                    runCatching {
                        animeLocalDataSource.updateAnime(local.copy(episodes = nextEpisodes))
                    }.onSuccess { updated++ }
                        .onFailure {
                            errors++
                            Log.w(TAG, "AniList sync update local failed for ${local.title}", it)
                        }
                } else {
                    skipped++
                }
            }
        }

        val localAfterPull = animeLocalDataSource.getAllAnimeList()
        val remoteAfterPull = fetchAnilistFlatList(token)
        val usedMediaIds = mutableSetOf<Int>()
        localAfterPull.forEach { local ->
            processed++
            updateProgress("Обработка: $processed из ${remoteRows.size + localAfterPull.size}...", processed, total)
            val remote = pickBestAnilistRowForLocal(local, remoteAfterPull, usedMediaIds)
            if (remote != null) usedMediaIds.add(remote.mediaId)
            if (remote == null) {
                val mid = findAnilistMediaIdByTitle(local.title)
                if (mid == null) skipped++
                else if (pushAnilistUpsert(token, mid, local)) pushed++ else errors++
            } else if (local.episodes > remote.progress) {
                if (pushAnilistUpsert(token, remote.mediaId, local)) updated++ else errors++
            } else {
                skipped++
            }
        }

        return SyncResultSummary(
            added = added,
            updated = updated,
            pushed = pushed,
            skipped = skipped,
            errors = errors
        )
    }

    private suspend fun fetchShikimoriSnapshot(): ShikiSnapshot {
        updateMessage("Получение данных с сервера...")
        val token = requireNotNull(prefs.getString(tokenKey(ExternalListService.SHIKIMORI, "access"), null)) {
            "Shikimori token missing"
        }
        val who = shikiRequestJson(
            method = "GET",
            url = "https://shikimori.one/api/users/whoami",
            token = token
        ).jsonObject
        val userId = who["id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("Invalid whoami response: no id")

        val ratesArray = shikiRequestJson(
            method = "GET",
            url = "https://shikimori.one/api/users/$userId/anime_rates?limit=5000",
            token = token
        ).jsonArray

        val rates = ratesArray.mapNotNull { el ->
            runCatching {
                val obj = el.jsonObject
                val anime = obj["anime"]?.jsonObject
                val titleEn = anime?.get("name").jsonContentOrNull().orEmpty()
                val titleRu = anime?.get("russian").jsonContentOrNull()
                val animeId = anime?.get("id").jsonContentOrNull()?.toIntOrNull()
                    ?: obj["target_id"].jsonContentOrNull()?.toIntOrNull()
                    ?: return@runCatching null
                if (titleEn.isBlank() && titleRu.isNullOrBlank()) return@runCatching null
                RemoteRate(
                    rateId = obj["id"].jsonContentOrNull()?.toIntOrNull() ?: return@runCatching null,
                    userId = userId,
                    animeId = animeId,
                    titleEn = titleEn,
                    titleRu = titleRu,
                    episodes = obj["episodes"].jsonContentOrNull()?.toIntOrNull() ?: 0,
                    status = obj["status"].jsonContentOrNull() ?: "watching"
                )
            }.getOrElse {
                Log.w(TAG, "Skip malformed remote rate", it)
                null
            }
        }
        return ShikiSnapshot(userId = userId, rates = rates)
    }

    /**
     * Лучшее соответствие локальной записи удалённому списку; каждый [RemoteRate] с consumption только один раз.
     * При двух кандидатах с почти одинаковым баллом возвращает null (не создаём ложный merge).
     */
    private fun pickBestRemoteForLocal(
        local: Anime,
        remoteRates: List<RemoteRate>,
        usedRateIds: Set<Int>
    ): RemoteRate? {
        val scored = remoteRates.asSequence()
            .filter { it.rateId !in usedRateIds }
            .mapNotNull { remote ->
                val cands = listOfNotNull(remote.titleRu, remote.titleEn)
                val s = TitleMatcher.bestScore(local.title, cands)
                if (s >= TitleMatcher.MATCH_THRESHOLD) remote to s else null
            }
            .sortedByDescending { it.second }
            .toList()
        if (scored.isEmpty()) return null
        if (scored.size >= 2 && scored[0].second - scored[1].second < TITLE_MATCH_AMBIGUITY_GAP) return null
        return scored[0].first
    }

    private fun pickBestLocalForRemote(
        remote: RemoteRate,
        locals: List<Anime>,
        usedLocalIds: Set<String>
    ): Anime? {
        val cands = listOfNotNull(remote.titleRu, remote.titleEn)
        val scored = locals.asSequence()
            .filter { it.id !in usedLocalIds }
            .mapNotNull { local ->
                val s = TitleMatcher.bestScore(local.title, cands)
                if (s >= TitleMatcher.MATCH_THRESHOLD) local to s else null
            }
            .sortedByDescending { it.second }
            .toList()
        if (scored.isEmpty()) return null
        if (scored.size >= 2 && scored[0].second - scored[1].second < TITLE_MATCH_AMBIGUITY_GAP) return null
        return scored[0].first
    }

    private suspend fun importMissingFromRemote(remote: RemoteRate): Boolean {
        val query = remote.titleRu?.takeIf { it.isNotBlank() } ?: remote.titleEn
        updateMessage("Получение данных с сервера...")
        val result = runCatching {
            animeRepository.searchAnimeShikimoriOnly(query, AppLanguage.RU, allowZeroEpisodes = true)
                .getOrElse { emptyList() }
                .firstOrNull()
                ?: animeRepository.searchAnimeShikimoriOnly(query, AppLanguage.EN, allowZeroEpisodes = true)
                    .getOrElse { emptyList() }
                    .firstOrNull()
        }.getOrElse {
            Log.w(TAG, "Search failed for $query", it)
            null
        } ?: return false

        return addFromApiUseCase(result).fold(
            onSuccess = {
                // После добавления подтягиваем прогресс просмотра.
                val inserted = animeLocalDataSource.getAllAnimeList().firstOrNull { local ->
                    TitleMatcher.isMatch(local.title, listOfNotNull(remote.titleRu, remote.titleEn))
                }
                if (inserted != null && remote.episodes > inserted.episodes) {
                    runCatching {
                        animeLocalDataSource.updateAnime(inserted.copy(episodes = remote.episodes))
                    }.onFailure { Log.w(TAG, "Failed to apply remote progress for $query", it) }
                }
                true
            },
            onFailure = {
                Log.w(TAG, "Add from API failed for $query", it)
                false
            }
        )
    }

    private suspend fun findShikimoriAnimeIdByTitle(title: String): Int? {
        val token = requireNotNull(prefs.getString(tokenKey(ExternalListService.SHIKIMORI, "access"), null)) {
            "Shikimori token missing"
        }
        val encodedTitle = Uri.encode(title)
        val arr = shikiRequestJson(
            method = "GET",
            url = "https://shikimori.one/api/animes?search=$encodedTitle&limit=$SHIKI_SEARCH_VALIDATE_LIMIT",
            token = token
        ).jsonArray
        for (el in arr) {
            val obj = el.jsonObject
            val name = obj["name"].jsonContentOrNull().orEmpty()
            val russian = obj["russian"].jsonContentOrNull()
            val id = obj["id"].jsonContentOrNull()?.toIntOrNull() ?: continue
            val cands = listOfNotNull(name.takeIf { it.isNotBlank() }, russian?.takeIf { it.isNotBlank() })
            if (TitleMatcher.isMatch(title, cands)) return id
        }
        Log.w(TAG, "Shikimori search: no result passed title match for \"$title\"")
        return null
    }

    private suspend fun createShikiRate(
        userId: Int,
        animeId: Int,
        status: String,
        episodes: Int
    ) {
        val token = requireNotNull(prefs.getString(tokenKey(ExternalListService.SHIKIMORI, "access"), null)) {
            "Shikimori token missing"
        }
        val body = FormBody.Builder()
            .add("user_rate[user_id]", userId.toString())
            .add("user_rate[target_id]", animeId.toString())
            .add("user_rate[target_type]", "Anime")
            .add("user_rate[status]", status)
            .add("user_rate[episodes]", episodes.toString())
            .build()
        shikiRequestJson(
            method = "POST",
            url = "https://shikimori.one/api/v2/user_rates",
            token = token,
            body = body
        )
    }

    private suspend fun patchShikiRate(
        rateId: Int,
        status: String,
        episodes: Int
    ) {
        val token = requireNotNull(prefs.getString(tokenKey(ExternalListService.SHIKIMORI, "access"), null)) {
            "Shikimori token missing"
        }
        val body = FormBody.Builder()
            .add("user_rate[status]", status)
            .add("user_rate[episodes]", episodes.toString())
            .build()
        shikiRequestJson(
            method = "PATCH",
            url = "https://shikimori.one/api/v2/user_rates/$rateId",
            token = token,
            body = body
        )
    }

    /**
     * Статус списка Shikimori: [planned] / [watching] / [completed].
     * Если из API известно число серий — сравниваем с локальным счётчиком «просмотрено»;
     * иначе при просмотренных сериях > 0 считаем [completed] (локальная БД — как архив просмотренного).
     */
    private suspend fun resolveShikiStatus(local: Anime): String {
        if (local.episodes <= 0) return "planned"

        val cat = local.categoryType.trim()
        if (cat.equals("MOVIE", ignoreCase = true)) return "completed"

        val contentType = when (cat.uppercase()) {
            "SERIES" -> AppContentType.SERIES
            else -> AppContentType.ANIME
        }

        val total = runCatching {
            animeRepository.findTotalEpisodes(local.title, local.categoryType, contentType).getOrNull()?.first
        }.getOrNull()?.takeIf { it > 0 }

        return when {
            total != null && local.episodes >= total -> "completed"
            total != null -> "watching"
            else -> "completed"
        }
    }

    private suspend fun shikiRequestJson(
        method: String,
        url: String,
        token: String,
        body: RequestBody? = null
    ) = withContext(Dispatchers.IO) {
        shikiRateLimiter.acquire()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Vetro/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .method(method, if (method == "GET") null else body)
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            json.parseToJsonElement(raw)
        }
    }

    private fun updateMessage(message: String) {
        val s = _syncUiState.value
        _syncUiState.value = s.copy(message = message)
    }

    private fun updateProgress(message: String, processed: Int, total: Int) {
        _syncUiState.value = ExternalListSyncUiState(
            isRunning = true,
            message = message,
            processed = processed,
            total = total
        )
    }

    private fun setPendingListAction(service: ExternalListService, action: ListServiceAction) {
        prefs.edit {
            putString(KEY_PENDING_SERVICE, service.name)
            putString(KEY_PENDING_ACTION, action.name)
        }
    }

    private fun consumePendingListAction(): Pair<ExternalListService, ListServiceAction>? {
        val sName = prefs.getString(KEY_PENDING_SERVICE, null) ?: return null
        val aName = prefs.getString(KEY_PENDING_ACTION, null) ?: return null
        prefs.edit {
            remove(KEY_PENDING_SERVICE)
            remove(KEY_PENDING_ACTION)
        }
        return try {
            ExternalListService.valueOf(sName) to ListServiceAction.valueOf(aName)
        } catch (_: Exception) {
            Log.w(TAG, "Invalid pending: $sName / $aName")
            null
        }
    }

    private fun tokenKey(service: ExternalListService, part: String): String =
        "${service.name.lowercase()}_$part"

    private fun kotlinx.serialization.json.JsonElement?.jsonContentOrNull(): String? =
        runCatching { this?.jsonPrimitive?.content }.getOrNull()

    private fun toastMain(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun newPkceVerifier(): String {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Serializable
    private data class OAuthTokenJson(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("token_type") val tokenType: String? = null
    )
}
