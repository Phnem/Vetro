package com.example.myapplication.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.data.models.AppUpdatePersistedKind
import com.example.myapplication.data.models.AppUpdateSnapshot
import com.example.myapplication.domain.settings.AppReleaseVersionComparer
import com.phnem.vetro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val KEY_KIND = stringPreferencesKey("app_update_persisted_kind")
private val KEY_LATEST_TAG = stringPreferencesKey("app_update_latest_tag")
private val KEY_DOWNLOAD_URL = stringPreferencesKey("app_update_download_url")
private val KEY_HTML_URL = stringPreferencesKey("app_update_html_url")
private val KEY_APK_SIZE = longPreferencesKey("app_update_apk_size_bytes")
private val KEY_SHA256 = stringPreferencesKey("app_update_latest_sha256")
private val KEY_CHANGELOG_MD = stringPreferencesKey("app_update_changelog_markdown")
private val KEY_LAST_CHECK_MS = longPreferencesKey("app_update_last_success_check_ms")
private val KEY_STARTUP_DISMISSED_TAG = stringPreferencesKey("app_update_startup_sheet_dismissed_tag")

private const val AUTO_CHECK_THROTTLE_MS = 4L * 60L * 60L * 1000L

class AppUpdateRepository(
    private val settingsDataStore: DataStore<Preferences>,
    private val animeRepository: AnimeRepository,
) {

    val appUpdateSnapshot: Flow<AppUpdateSnapshot> = settingsDataStore.data
        .map { prefs -> prefs.toSnapshot() }
        .distinctUntilChanged()

    suspend fun refreshAppUpdate(force: Boolean, localVersion: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val prefs = settingsDataStore.data.first()
        val last = prefs[KEY_LAST_CHECK_MS] ?: 0L
        if (!force && last > 0L && (now - last) < AUTO_CHECK_THROTTLE_MS) {
            return@withContext true
        }

        val localVer = localVersion.ifBlank { "1.0.0" }

        var success = false
        animeRepository.checkGithubUpdate(
            owner = BuildConfig.GITHUB_OWNER,
            repo = BuildConfig.GITHUB_REPO,
        ).fold(
            onSuccess = { release ->
                settingsDataStore.edit { ed ->
                    if (release != null) {
                        val newer =
                            AppReleaseVersionComparer.isRemoteSemanticallyNewer(localVer, release.tagName)
                        ed[KEY_KIND] = if (newer) {
                            AppUpdatePersistedKind.UPDATE_AVAILABLE.name
                        } else {
                            AppUpdatePersistedKind.NO_UPDATE.name
                        }
                        ed[KEY_LATEST_TAG] = release.tagName
                        ed[KEY_DOWNLOAD_URL] = release.downloadUrl
                        ed[KEY_HTML_URL] = release.htmlUrl
                        ed[KEY_APK_SIZE] = release.apkAsset?.size?.takeIf { s -> s > 0L } ?: 0L
                        release.sha256?.let { sha -> ed[KEY_SHA256] = sha } ?: ed.remove(KEY_SHA256)
                        release.body?.let { body -> ed[KEY_CHANGELOG_MD] = body }
                            ?: ed.remove(KEY_CHANGELOG_MD)
                        ed[KEY_LAST_CHECK_MS] = now
                    } else {
                        ed[KEY_KIND] = AppUpdatePersistedKind.NO_UPDATE.name
                        ed.remove(KEY_LATEST_TAG)
                        ed.remove(KEY_DOWNLOAD_URL)
                        ed.remove(KEY_HTML_URL)
                        ed[KEY_APK_SIZE] = 0L
                        ed.remove(KEY_SHA256)
                        ed.remove(KEY_CHANGELOG_MD)
                        ed[KEY_LAST_CHECK_MS] = now
                    }
                }
                success = true
            },
            onFailure = {
                settingsDataStore.edit { ed ->
                    ed[KEY_KIND] = AppUpdatePersistedKind.ERROR.name
                }
                success = false
            },
        )
        success
    }

    suspend fun dismissStartupOverlayForCurrentRelease() {
        val snap = settingsDataStore.data.first().toSnapshot()
        val tag = snap.latestTag ?: return
        settingsDataStore.edit { it[KEY_STARTUP_DISMISSED_TAG] = tag }
    }

    private fun Preferences.toSnapshot(): AppUpdateSnapshot {
        val kindName = this[KEY_KIND]
        val kind = runCatching {
            kindName?.let { AppUpdatePersistedKind.valueOf(it) }
        }.getOrNull() ?: AppUpdatePersistedKind.IDLE
        val size = this[KEY_APK_SIZE] ?: 0L
        return AppUpdateSnapshot(
            persistedKind = kind,
            latestTag = this[KEY_LATEST_TAG],
            latestDownloadUrl = this[KEY_DOWNLOAD_URL],
            latestHtmlUrl = this[KEY_HTML_URL],
            latestApkSizeBytes = size.takeIf { it > 0L },
            latestSha256 = this[KEY_SHA256],
            updateChangelogMarkdown = this[KEY_CHANGELOG_MD],
            lastSuccessfulCheckEpochMs = this[KEY_LAST_CHECK_MS] ?: 0L,
            startupDismissedTag = this[KEY_STARTUP_DISMISSED_TAG],
        )
    }
}
