package com.example.myapplication.worker

import com.example.myapplication.data.local.AppLanguagePrefs
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.updates.EpisodeUpdateCheckCoordinator
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.manga.updates.MangaUpdateCheckUseCase
import com.example.myapplication.notifications.AnimeNotifier
import com.example.myapplication.notifications.MangaNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

class AnimeUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val notifier: AnimeNotifier by inject()
    private val episodeUpdateCheckCoordinator: EpisodeUpdateCheckCoordinator by inject()
    private val seasonEpisodesResolver: com.example.myapplication.domain.seasons.SeasonEpisodesResolver by inject()
    private val seasonCatchUp: com.example.myapplication.domain.seasons.SeasonCatchUp by inject()
    private val seasonEpisodeLocator: com.example.myapplication.domain.seasons.SeasonEpisodeLocator by inject()
    private val mangaNotifier: MangaNotifier by inject()
    private val mangaUpdateCheck: MangaUpdateCheckUseCase by inject()
    private val settingsDataStore: DataStore<Preferences> by inject(named("settings"))


    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val language = AppLanguagePrefs.current(settingsDataStore)
            val newlyDetected = episodeUpdateCheckCoordinator.detectAndStore(language, catchUpSeasons = false)
            // Догоняем расклад сезонов ДО уведомления: пуш «вышла 10-я серия 4-го сезона»,
            // открывающий тайтл с одним сезоном, — это ровно та ручная работа («Найти ещё»),
            // которую пользователь делал после каждого пуша. Ошибки здесь не отменяют пуш:
            // узнать о серии важнее, чем увидеть её сразу в списке.
            runCatching { seasonCatchUp.catchUpAll(newlyDetected.map { it.animeId }) }
                .onFailure { it.printStackTrace() }
            // Системные пуши шлём ТОЛЬКО когда приложение в фоне/закрыто. Если оно
            // открыто — обновления уже показываются in-app стопкой, дублировать шторкой
            // не нужно (иначе уведомление «мигает» при каждом взаимодействии).
            if (newlyDetected.isNotEmpty() && !isAppInForeground()) {
                // Подписи «S3 E5» — после догона расклада выше, чтобы сезон был актуальным.
                val labels = runCatching { seasonEpisodeLocator.labelsFor(newlyDetected) }.getOrElse { emptyMap() }
                notifier.showUpdateNotifications(newlyDetected, language, labels)
            }
            // Новые главы привязанной манги. Проверка СЪЕДАЕТ состояние: сравнив оглавление с
            // кэшем, она тут же кладёт свежее — то есть «новым» это уже не будет никогда. Поэтому
            // на переднем плане её не запускаем вовсе, а не глушим один только пуш: иначе
            // обновления, выпавшие на открытое приложение, пропадали бы молча и насовсем.
            // Пользователю в этот момент ничего не теряется — вкладка «Главы» у него перед
            // глазами и грузит оглавление сама.
            if (!isAppInForeground()) {
                runCatching {
                    val chapters = mangaUpdateCheck.detect()
                    if (chapters.isNotEmpty()) {
                        mangaNotifier.showChapterNotifications(chapters, language)
                    }
                }.onFailure { it.printStackTrace() }
            }
            // Серии по сезонам: дорезолвливаем протухшие записи тем же фоновым проходом.
            // Ошибки не роняют воркер — проверка новых серий важнее.
            runCatching { seasonEpisodesResolver.refreshStale() }
                .onFailure { it.printStackTrace() }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    /** Открыто ли приложение (передний план) — без доп. зависимости lifecycle-process. */
    private fun isAppInForeground(): Boolean {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        return state.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}
