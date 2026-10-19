package com.example.myapplication.update

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.data.models.AppUpdatePersistedKind
import com.example.myapplication.data.models.AppUpdateSnapshot
import com.example.myapplication.data.repository.AppUpdateRepository
import com.example.myapplication.domain.settings.AppReleaseVersionComparer
import com.phnem.vetro.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Обновление приложения целиком: проверка релиза, загрузка, проверка файла, установка.
 *
 * Живёт в приложении, а не в экране. Раньше всё это сидело во `ViewModel` настроек: загрузка и опрос
 * прогресса умирали вместе с экраном, приёмник завершения терялся при повороте, а само состояние
 * собиралось из трёх мест. Теперь единственный источник правды - [state]; окно только показывает его,
 * а фоновая задача ([AppUpdateWorker]) и приёмник результата установки ([InstallResultReceiver])
 * двигают его же.
 *
 * Работает только при [UpdateMode.Auto] (подпись ключом автора); иначе состояние навсегда
 * [UpdateState.Unavailable], а все действия - пустые.
 */
class AppUpdateManager(
    private val context: Context,
    private val policy: UpdatePolicy,
    private val repository: AppUpdateRepository,
    private val downloader: UpdateDownloader,
    private val verifier: ApkVerifier,
    private val installer: UpdateInstaller,
    private val notifier: UpdateNotifier,
    private val settingsDataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val localVersion: String = packageVersionName(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val mode: UpdateMode get() = policy.mode

    /** Версия установленного приложения - для показа и сравнения. */
    val installedVersion: String get() = localVersion

    private val _state = MutableStateFlow<UpdateState>(
        if (policy.isAuto) UpdateState.Idle else UpdateState.Unavailable,
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Показывать ли окно при запуске: есть что сделать пользователю (поставить скачанное, разрешить
     * установку, скачать вручную) и этот релиз он ещё не закрывал.
     */
    val startupOffer: Flow<Boolean> = combine(state, repository.appUpdateSnapshot) { s, snap ->
        policy.isAuto && s.offeredRelease() != null &&
            snap.latestTag != null && snap.latestTag != snap.startupDismissedTag
    }.distinctUntilChanged()

    /**
     * Держать ли открытым уже показанное окно: пока с релизом что-то происходит (грузится, ставится,
     * не вышло), оно остаётся на экране, а не пропадает, едва пользователь нажал «Обновить».
     */
    val startupOfferKeep: Flow<Boolean> = combine(state, repository.appUpdateSnapshot) { s, snap ->
        policy.isAuto && s.activeRelease() != null &&
            snap.latestTag != null && snap.latestTag != snap.startupDismissedTag
    }.distinctUntilChanged()

    private val downloadMutex = Mutex()
    private var downloadJob: Job? = null
    private var backgroundRefresh: Job? = null

    /** Сторож установки: «Устанавливаем» не должно висеть вечно, если система не ответила. */
    private var installWatchdog: Job? = null

    init {
        if (policy.isAuto) scope.launch { applySnapshot(repository.appUpdateSnapshot.first()) }
    }

    // ---- проверка ----

    /**
     * Спросить GitHub о релизе. [force] = false укладывается в интервал между проверками, [force] = true
     * (кнопка «Проверить сейчас») спрашивает всегда и показывает [UpdateState.Checking].
     * @return false, если связаться не удалось.
     */
    suspend fun check(force: Boolean, maxAgeMs: Long? = null): Boolean {
        if (!policy.isAuto) return false
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing || busy is UpdateState.Checking) return true
        if (force) _state.value = UpdateState.Checking
        val ok = if (maxAgeMs != null) {
            repository.refreshAppUpdate(force, localVersion, maxAgeMs)
        } else {
            repository.refreshAppUpdate(force, localVersion)
        }
        if (!ok) {
            _state.value = UpdateState.Failed(null, UpdateFailure.CHECK)
            return false
        }
        applySnapshot(repository.appUpdateSnapshot.first())
        return true
    }

    /**
     * Проверка при запуске приложения и возвращении в него: тихая, с загрузкой по Wi‑Fi. Срок давности
     * короткий ([LAUNCH_CHECK_MAX_AGE_MS]): общий четырёхчасовой прятал свежий релиз, вышедший после
     * прошлой проверки, и пользователю приходилось искать его вручную.
     */
    fun refreshInBackground() {
        if (!policy.isAuto || backgroundRefresh?.isActive == true) return
        backgroundRefresh = scope.launch {
            check(force = false, maxAgeMs = LAUNCH_CHECK_MAX_AGE_MS)
            autoDownloadIfAllowed()
        }
    }

    /** Кнопка «Проверить сейчас». */
    fun checkNow() {
        if (!policy.isAuto) return
        scope.launch {
            check(force = true)
            autoDownloadIfAllowed()
        }
    }

    // ---- загрузка ----

    /** Качать самим можно, если сеть не тарифицируется; по мобильной сети это решает пользователь кнопкой. */
    private suspend fun autoDownloadIfAllowed() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        if (isUnmetered()) runDownload(release)
    }

    /** Скачать по просьбе пользователя, по любой сети. */
    fun download() {
        if (!policy.isAuto || downloadJob?.isActive == true) return
        val release = _state.value.downloadableRelease() ?: return
        downloadJob = scope.launch { runDownload(release) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    private suspend fun runDownload(release: UpdateRelease) {
        downloadMutex.withLock {
            val ready = _state.value
            if (ready is UpdateState.Ready && ready.release.tag == release.tag) return
            var lastAt = clock()
            var lastBytes = 0L
            var speed = 0L
            _state.value = UpdateState.Downloading(release, 0, release.sizeBytes, 0)
            try {
                val file = downloader.download(release) { done, total ->
                    val now = clock()
                    val span = now - lastAt
                    if (span >= SPEED_WINDOW_MS) {
                        val instant = (done - lastBytes) * 1000 / span
                        speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                        lastAt = now
                        lastBytes = done
                    }
                    _state.value = UpdateState.Downloading(release, done, total, speed)
                }
                when (val verdict = verifier.verify(file)) {
                    ApkVerdict.Ok -> {
                        downloader.cleanExcept(release.tag)
                        _state.value = UpdateState.Ready(release, file)
                    }
                    is ApkVerdict.Rejected -> {
                        file.delete()
                        _state.value = UpdateState.Failed(release, UpdateFailure.VERIFY, verdict.reason)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: UpdateIntegrityException) {
                _state.value = UpdateState.Failed(release, UpdateFailure.VERIFY, e.message)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed(release, UpdateFailure.DOWNLOAD, e.message)
            }
        }
    }

    // ---- установка ----

    /** Передать скачанное системе. Без разрешения не ставит, а переводит в [UpdateState.NeedsPermission]. */
    fun install() {
        if (!policy.isAuto) return
        val (release, file) = installable() ?: return
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(release, file)
            return
        }
        _state.value = UpdateState.Installing(release)
        Log.i(TAG, "install ${release.tag}: ${file.length()} bytes")
        installWatchdog?.cancel()
        installWatchdog = scope.launch {
            // Копия 33 МБ в сессию установщика — не на главном потоке, откуда вызывает кнопка.
            val committed = withContext(Dispatchers.IO) { installer.install(file) }
            if (!committed) {
                Log.w(TAG, "install ${release.tag}: the session could not be committed")
                _state.value = UpdateState.Failed(release, UpdateFailure.INSTALL, "session")
                return@launch
            }
            // Система отвечает за секунды: окном подтверждения или итогом. Нет ответа — сессия застряла
            // (HyperOS на Xiaomi 13 после commit молча замирает: ни окна, ни результата). Бросаем её и
            // отдаём файл обычному системному установщику; не вышло и это — ошибка с кнопкой «Повторить».
            delay(RESULT_TIMEOUT_MS)
            if (_state.value is UpdateState.Installing) {
                Log.w(TAG, "install ${release.tag}: no answer from the system in ${RESULT_TIMEOUT_MS / 1000} s")
                installer.abandonOwnSessions()
                _state.value = if (installer.openWithSystemInstaller(file)) {
                    UpdateState.Ready(release, file)
                } else {
                    UpdateState.Failed(release, UpdateFailure.INSTALL, "no-result")
                }
            }
        }
    }

    /** Пользователь вернулся со страницы разрешения установки. */
    fun onReturnedFromInstallSettings() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (installer.canInstall()) install() else _state.value = s
    }

    private fun installable(): Pair<UpdateRelease, java.io.File>? = when (val s = _state.value) {
        is UpdateState.Ready -> s.release to s.file
        is UpdateState.NeedsPermission -> s.release to s.file
        is UpdateState.Failed -> s.release?.let { r -> downloader.downloaded(r.tag)?.let { r to it } }
        else -> null
    }

    /** Итог от системы: пришёл в [InstallResultReceiver]. */
    fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        Log.i(TAG, "install result: status=$status message=$message confirm=${confirm != null}")
        scope.launch {
            installWatchdog?.cancel()
            val strings = updateStrings(AppLanguagePrefs.current(settingsDataStore))
            val release = when (val s = _state.value) {
                is UpdateState.Installing -> s.release
                is UpdateState.Ready -> s.release
                is UpdateState.NeedsPermission -> s.release
                is UpdateState.Failed -> s.release
                else -> null
            }
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (confirm == null) {
                        // Просят подтвердить, но окна не дали: подтверждать нечего, ждать бессмысленно.
                        _state.value = UpdateState.Failed(release, UpdateFailure.INSTALL, "no-confirm")
                        return@launch
                    }
                    // Система просит подтверждения: на виду открываем её окно, в фоне (или если окно не
                    // открылось) зовём уведомлением.
                    val opened = isAppInForeground() &&
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Log.w(TAG, "the system confirmation window did not open", it) }
                            .isSuccess
                    if (!opened) notifier.showReady(strings, release?.tag.orEmpty(), confirm)
                    // Пользователь решает в системном окне; если ответа нет долго, возвращаем «Установить».
                    installWatchdog = scope.launch {
                        delay(CONFIRM_TIMEOUT_MS)
                        if (_state.value is UpdateState.Installing) {
                            Log.w(TAG, "no decision in the system window, back to Ready")
                            val file = release?.let { downloader.downloaded(it.tag) }
                            _state.value = if (release != null && file != null) {
                                UpdateState.Ready(release, file)
                            } else {
                                UpdateState.Failed(release, UpdateFailure.INSTALL, "confirm-timeout")
                            }
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    downloader.cleanExcept(null)
                    notifier.cancel()
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    // Передумал в системном окне: файл на месте, можно поставить позже.
                    val file = release?.let { downloader.downloaded(it.tag) }
                    _state.value = if (release != null && file != null) UpdateState.Ready(release, file) else UpdateState.Idle
                }
                else -> _state.value = UpdateState.Failed(release, UpdateFailure.INSTALL, message)
            }
        }
    }

    // ---- фоновый проход ----

    /**
     * Один проход воркера: проверить, скачать по неплатной сети и попытаться поставить. Если система
     * просит подтверждения или не разрешено ставить, пользователю уходит уведомление.
     */
    suspend fun runBackgroundCycle() {
        if (!policy.isAuto) return
        check(force = false)
        val available = (_state.value as? UpdateState.Available)?.release
        if (available != null && isUnmetered()) runDownload(available)
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(ready.release, ready.file)
            if (!isAppInForeground()) {
                notifier.showNeedsPermission(updateStrings(AppLanguagePrefs.current(settingsDataStore)), ready.release.tag)
            }
            return
        }
        // На виду ничего не ставим: приложение перезапустилось бы под пальцем. Окно предложит само.
        if (!isAppInForeground()) install()
    }

    /** Окно закрыли: этот релиз при запуске больше не предлагаем. */
    fun dismissStartupOffer() {
        scope.launch { repository.dismissStartupOverlayForCurrentRelease() }
    }

    // ---- вспомогательное ----

    private fun applySnapshot(snap: AppUpdateSnapshot) {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        _state.value = when (snap.persistedKind) {
            AppUpdatePersistedKind.UPDATE_AVAILABLE -> {
                val release = snap.toRelease()
                // Снимок мог быть сделан до обновления самого приложения: тогда «новое» - это уже мы.
                if (release == null || !AppReleaseVersionComparer.isRemoteSemanticallyNewer(localVersion, release.tag)) {
                    downloader.cleanExcept(null)
                    UpdateState.UpToDate(snap.lastSuccessfulCheckEpochMs)
                } else {
                    val file = downloader.downloaded(release.tag)
                    if (file != null && verifier.verify(file) == ApkVerdict.Ok) {
                        UpdateState.Ready(release, file)
                    } else {
                        file?.delete()
                        UpdateState.Available(release)
                    }
                }
            }
            AppUpdatePersistedKind.NO_UPDATE -> UpdateState.UpToDate(snap.lastSuccessfulCheckEpochMs)
            AppUpdatePersistedKind.ERROR -> UpdateState.Failed(null, UpdateFailure.CHECK)
            AppUpdatePersistedKind.IDLE -> UpdateState.Idle
        }
    }

    private fun AppUpdateSnapshot.toRelease(): UpdateRelease? {
        val tag = latestTag?.takeIf { it.isNotBlank() } ?: return null
        val url = latestDownloadUrl?.takeIf { it.isNotBlank() } ?: return null
        return UpdateRelease(
            tag = tag,
            htmlUrl = latestHtmlUrl.orEmpty(),
            downloadUrl = url,
            sizeBytes = latestApkSizeBytes ?: 0L,
            sha256 = latestSha256,
            changelogMarkdown = updateChangelogMarkdown,
        )
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** Открыто ли приложение (передний план) - без лишней зависимости lifecycle-process. */
    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    companion object {
        private const val TAG = "AppUpdate"
        private const val SPEED_WINDOW_MS = 600L

        /** Не старше этого прошлая проверка, чтобы запуск или возврат в приложение не ходил в GitHub зря. */
        const val LAUNCH_CHECK_MAX_AGE_MS = 10L * 60L * 1000L

        /** Сколько ждём ответа системы после передачи пакета. */
        private const val RESULT_TIMEOUT_MS = 45_000L

        /** Сколько ждём решения пользователя в системном окне подтверждения. */
        private const val CONFIRM_TIMEOUT_MS = 180_000L
    }
}

/** Версия установленного пакета: то, что видит система, а не константа сборки (так её можно подменить при проверке). */
private fun packageVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        .orEmpty().ifBlank { BuildConfig.VERSION_NAME.orEmpty().ifBlank { "1.0.0" } }

/** Релиз, о котором стоит сказать пользователю при запуске, или null, если делать нечего. */
internal fun UpdateState.offeredRelease(): UpdateRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Ready -> release
    is UpdateState.NeedsPermission -> release
    else -> null
}

/** Релиз, с которым сейчас что-то связано: предложен, грузится, ставится или не вышло. */
internal fun UpdateState.activeRelease(): UpdateRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Ready -> release
    is UpdateState.NeedsPermission -> release
    is UpdateState.Installing -> release
    is UpdateState.Failed -> release
    else -> null
}

/** Релиз, который можно (пере)скачать: есть новый, а файла нет или он не прошёл проверку. */
internal fun UpdateState.downloadableRelease(): UpdateRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Failed -> release?.takeIf { reason == UpdateFailure.DOWNLOAD || reason == UpdateFailure.VERIFY }
    else -> null
}
