package com.example.myapplication.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.phnem.vetro.R
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.getNotificationStrings
import java.util.Locale

/** Id уведомления в шторке (совпадает с `NotificationManager.cancel` из приложения). */
fun animeUpdateNotificationId(animeId: String): Int = animeId.hashCode()

const val ANIME_UPDATES_GROUP = "anime_updates_group"
const val ANIME_UPDATES_SUMMARY_ID = 0x5E71E5

/** Тап по пушу открывает Details тайтла: action + extra читает [MainActivity]. */
const val ACTION_OPEN_ANIME = "com.phnem.vetro.action.OPEN_ANIME"
const val EXTRA_OPEN_ANIME_ID = "com.phnem.vetro.extra.OPEN_ANIME_ID"

/** Абстракция для тестирования и инверсии зависимостей (SOLID). */
interface AnimeNotifier {
    /**
     * Показать пуши «вышла новая серия» одной пачкой. Серии к этому моменту уже
     * проставлены в коллекции (авто-принятие) — уведомление только информирует,
     * кнопок действий у него нет; тап открывает Details тайтла.
     *
     * @param episodeLabels animeId → «S3 E5» (сезон и серия вместо сквозного счёта); у тайтлов
     *   без расклада по сезонам остаётся прежний текст «было → стало».
     */
    fun showUpdateNotifications(
        updates: List<AnimeUpdate>,
        language: AppLanguage,
        episodeLabels: Map<String, String> = emptyMap(),
    )

    /**
     * Убрать из системной шторки ВСЕ пуши обновлений серий (+ групповую сводку).
     * Вызывается, когда приложение на переднем плане: тогда обновления показываются
     * in-app стопкой, а системные уведомления не нужны.
     */
    fun cancelAllUpdateNotifications(animeIds: List<String>)
}

class AnimeNotifierImpl(
    private val context: Context
) : AnimeNotifier {

    private val channelId = "anime_updates_channel"
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val strings = getNotificationStrings(AppLanguage.EN)
        val channel = NotificationChannel(
            channelId,
            strings.notifChannelName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = strings.notifChannelDesc
        }
        manager.createNotificationChannel(channel)
    }

    override fun showUpdateNotifications(
        updates: List<AnimeUpdate>,
        language: AppLanguage,
        episodeLabels: Map<String, String>,
    ) {
        if (updates.isEmpty()) return
        updates.forEach { showOne(it, language, episodeLabels[it.animeId]) }
        // Сводку вешаем только когда детей реально несколько. Снимать её отдельно
        // не пытаемся: cancel() сводки на многих прошивках уносит и все дочерние
        // уведомления разом (из-за этого раньше «пропадали все» после пары действий).
        if (updates.size >= 2) showSummary(updates.size, language)
    }

    private fun showOne(update: AnimeUpdate, language: AppLanguage, episodeLabel: String?) {
        val strings = getNotificationStrings(language)
        val notifId = animeUpdateNotificationId(update.animeId)

        val title = String.format(Locale.getDefault(), strings.notifUpdateTitleFormat, update.title)
        // Строки не в UiStrings: у класса лимит параметров конструктора (см. UiStrings).
        val body = if (episodeLabel != null) {
            when (language) {
                AppLanguage.RU -> "$episodeLabel — отмечено в коллекции"
                AppLanguage.EN -> "$episodeLabel — marked in your collection"
            }
        } else {
            String.format(
                Locale.getDefault(),
                strings.notifUpdateBodyFormat,
                update.currentEpisodes,
                update.newEpisodes
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAnimeIntent(update.animeId, notifId))
            .setGroup(ANIME_UPDATES_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notification)
    }

    private fun showSummary(count: Int, language: AppLanguage) {
        val strings = getNotificationStrings(language)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ANIME_UPDATES_SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(strings.notifChannelName)
            .setContentText(
                String.format(Locale.getDefault(), strings.notifGroupSummaryFormat, count)
            )
            .setContentIntent(pendingIntent)
            .setGroup(ANIME_UPDATES_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(ANIME_UPDATES_SUMMARY_ID, summary)
    }

    /**
     * Тап по плашке → MainActivity с id тайтла. requestCode = notifId: с общим кодом
     * PendingIntent'ы переиспользуются, и все пуши открывали бы один и тот же тайтл.
     */
    private fun openAnimeIntent(animeId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANIME
            putExtra(EXTRA_OPEN_ANIME_ID, animeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun cancelAllUpdateNotifications(animeIds: List<String>) {
        animeIds.forEach { manager.cancel(animeUpdateNotificationId(it)) }
        manager.cancel(ANIME_UPDATES_SUMMARY_ID)
    }
}
