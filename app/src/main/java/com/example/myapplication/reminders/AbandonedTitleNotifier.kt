package com.example.myapplication.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.notifications.ACTION_OPEN_ANIME
import com.example.myapplication.notifications.EXTRA_OPEN_ANIME_ID
import com.phnem.vetro.R

/**
 * Тихое напоминание «вы давно не смотрели»: отдельный канал с низкой важностью (без звука и без
 * всплывающей плашки), чтобы его можно было выключить в системных настройках, не трогая пуши о
 * новых сериях. Тап открывает Details тайтла.
 */
class AbandonedTitleNotifier(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet reminders to continue titles you left unfinished"
            },
        )
    }

    /** false = показать не удалось (уведомления выключены), и историю писать не нужно. */
    fun show(candidate: AbandonedCandidate, language: AppLanguage): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val strings = ReminderStrings(language == AppLanguage.RU)
        val notifId = notificationId(candidate.animeId)
        val body = strings.body(candidate)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(strings.title(candidate))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent(candidate.animeId, notifId))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Через неделю напоминание само уходит из шторки: оно не должно копиться.
            .setTimeoutAfter(WEEK_MS)
            .build()
        manager.notify(notifId, notification)
        return true
    }

    private fun openIntent(animeId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANIME
            putExtra(EXTRA_OPEN_ANIME_ID, animeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Свой диапазон id, чтобы не пересекаться с пушами о сериях (там просто hashCode). */
    private fun notificationId(animeId: String): Int = animeId.hashCode() xor ID_SALT

    private companion object {
        const val CHANNEL_ID = "abandoned_titles_channel"
        const val ID_SALT = 0x0ABA4D01
        const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

/** Тексты напоминания. Отдельным классом: UiStrings на пределе параметров конструктора. */
internal class ReminderStrings(private val ru: Boolean) {
    fun title(c: AbandonedCandidate): String = when {
        ru && c.unit == ReminderUnit.CHAPTERS -> "Давно не читали «${c.title}»"
        ru -> "Давно не смотрели «${c.title}»"
        c.unit == ReminderUnit.CHAPTERS -> "It's been a while since you read “${c.title}”"
        else -> "It's been a while since you watched “${c.title}”"
    }

    fun body(c: AbandonedCandidate): String {
        val weeks = (c.idleDays / 7).coerceAtLeast(3)
        val where = when {
            c.unit == ReminderUnit.CHAPTERS -> if (ru) "Прочитано глав: ${c.doneCount}" else "Chapters read: ${c.doneCount}"
            c.position != null -> if (ru) "Остановились на ${c.position}" else "You left off at ${c.position}"
            else -> if (ru) "Просмотрено серий: ${c.doneCount}" else "Episodes watched: ${c.doneCount}"
        }
        return if (ru) "$where, ${weeksRu(weeks)} назад. Продолжим?" else "$where, $weeks weeks ago. Continue?"
    }

    private fun weeksRu(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "недель"
            n % 10 == 1 -> "неделю"
            n % 10 in 2..4 -> "недели"
            else -> "недель"
        }
        return "$n $word"
    }
}
