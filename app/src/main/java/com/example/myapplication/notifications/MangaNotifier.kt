package com.example.myapplication.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.manga.updates.MangaUpdate
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.getNotificationStrings
import com.phnem.vetro.R
import java.util.Locale

/**
 * Id пуша о главах. Солью разведён с [animeUpdateNotificationId]: тайтл, у которого есть и серии,
 * и привязанная манга, иначе заменял бы одно уведомление другим.
 */
fun mangaUpdateNotificationId(animeId: String): Int = animeId.hashCode() xor MANGA_ID_SALT

private const val MANGA_ID_SALT = 0x4D41
const val MANGA_UPDATES_GROUP = "manga_updates_group"
const val MANGA_UPDATES_SUMMARY_ID = 0x5E72E5

/** Пуши «вышла новая глава». Тап открывает Details тайтла — там же живёт вкладка «Главы». */
interface MangaNotifier {
    fun showChapterNotifications(updates: List<MangaUpdate>, language: AppLanguage)
}

/**
 * Канал у манги отдельный от серий.
 *
 * Не косметика: каналом управляет пользователь, и «хочу знать о главах, но не о каждой серии»
 * (или наоборот) — обычное желание, которое одним общим каналом не выразить.
 */
class MangaNotifierImpl(
    private val context: Context,
) : MangaNotifier {

    private val channelId = "manga_updates_channel"
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val strings = getNotificationStrings(AppLanguage.EN)
        val channel = NotificationChannel(
            channelId,
            strings.notifMangaChannelName,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = strings.notifMangaChannelDesc }
        manager.createNotificationChannel(channel)
    }

    override fun showChapterNotifications(updates: List<MangaUpdate>, language: AppLanguage) {
        if (updates.isEmpty()) return
        updates.forEach { showOne(it, language) }
        // Сводка — только когда детей реально несколько, ровно как у серий: снятие сводки на
        // многих прошивках уносит и дочерние уведомления разом.
        if (updates.size >= 2) showSummary(updates.size, language)
    }

    private fun showOne(update: MangaUpdate, language: AppLanguage) {
        val strings = getNotificationStrings(language)
        val locale = Locale.getDefault()
        val notifId = mangaUpdateNotificationId(update.animeId)

        val title = String.format(locale, strings.notifMangaTitleFormat, update.title)
        val latest = update.latestLabel
        val body = if (latest != null) {
            String.format(locale, strings.notifMangaBodyFormat, update.newChapters, latest)
        } else {
            String.format(locale, strings.notifMangaBodyCountFormat, update.newChapters)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAnimeIntent(update.animeId, notifId))
            .setGroup(MANGA_UPDATES_GROUP)
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
            context,
            MANGA_UPDATES_SUMMARY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(strings.notifMangaChannelName)
            .setContentText(
                String.format(Locale.getDefault(), strings.notifMangaGroupSummaryFormat, count),
            )
            .setContentIntent(pendingIntent)
            .setGroup(MANGA_UPDATES_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(MANGA_UPDATES_SUMMARY_ID, summary)
    }

    /** requestCode = notifId: с общим кодом все пуши открывали бы один и тот же тайтл. */
    private fun openAnimeIntent(animeId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANIME
            putExtra(EXTRA_OPEN_ANIME_ID, animeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
