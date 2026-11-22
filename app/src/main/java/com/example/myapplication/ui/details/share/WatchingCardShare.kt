package com.example.myapplication.ui.details.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.regularSeasons
import com.example.myapplication.domain.status.AutoWatchStatusResolver
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.ui.shared.mininotif.MiniNotif
import com.example.myapplication.ui.shared.mininotif.MiniNotifKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Собирает данные карточки «Что я сейчас смотрю» из тех же источников, что и прогресс на карточке
 * главной: досмотренные серии, расклад сезонов, счётчик серий, чтение манги.
 */
class WatchingCardBuilder(
    private val playback: EpisodePlaybackStore,
    private val seasonEpisodes: SeasonEpisodesStore,
    private val seriesSeasons: SeriesSeasonsStore,
    private val autoStatus: AutoWatchStatusResolver,
) {
    internal suspend fun build(anime: Anime, title: String, ru: Boolean, posterPath: String?): ShareCardData {
        val isManga = anime.mediaType == MediaType.MANGA
        var position: String? = null
        var total: String? = null
        var fraction: Float? = null
        if (isManga) {
            val summary = autoStatus.mangaReadingSnapshot()[anime.id]
            if (summary != null && summary.readChapters > 0) {
                position = if (ru) "Глава ${summary.readChapters}" else "Chapter ${summary.readChapters}"
                summary.totalChapters?.takeIf { it > 0 }?.let {
                    total = if (ru) "из $it" else "of $it"
                    fraction = summary.fraction
                }
            }
        } else {
            val key = playback.furthestEpisodeFlow(listOf(anime.id), completedOnly = true).first()[anime.id]
            if (key != null && key.episode > 0) {
                val layout = (seriesSeasons.flow.first() + seasonEpisodes.flow.first())[anime.id]
                val seasons = layout.regularSeasons()
                val season = seasons.firstOrNull { it.seasonNumber == key.season }
                val seasonTotal = season?.let { it.totalEpisodes ?: it.episodes }
                    ?: anime.episodes.takeIf { seasons.isEmpty() && key.season == 1 && it > 0 }
                val multiSeason = seasons.size > 1 || key.season > 1
                position = when {
                    multiSeason -> "S${key.season} · E${key.episode}"
                    ru -> "Серия ${key.episode}"
                    else -> "Episode ${key.episode}"
                }
                seasonTotal?.let {
                    total = if (ru) "из $it" else "of $it"
                    fraction = (key.episode.toFloat() / it).coerceIn(0f, 1f)
                }
            }
        }
        val finished = anime.watchStatus == WatchStatus.COMPLETED
        val kindLabel = when {
            isManga && finished -> if (ru) "ПРОЧИТАНО" else "FINISHED"
            isManga -> if (ru) "СЕЙЧАС ЧИТАЮ" else "NOW READING"
            finished -> if (ru) "ПРОСМОТРЕНО" else "WATCHED"
            else -> if (ru) "СЕЙЧАС СМОТРЮ" else "NOW WATCHING"
        }
        val typeLabel = when (anime.mediaType) {
            MediaType.ANIME -> if (ru) "Аниме" else "Anime"
            MediaType.MANGA -> if (ru) "Манга" else "Manga"
            MediaType.MOVIE -> if (ru) "Фильм" else "Movie"
            MediaType.SERIES -> if (ru) "Сериал" else "Series"
        }
        return ShareCardData(
            title = title,
            kindLabel = kindLabel,
            typeLabel = typeLabel,
            position = position,
            positionTotal = total,
            fraction = fraction,
            rating = anime.rating,
            genres = anime.tags.take(3),
            poster = posterPath?.let { decodePoster(it) },
        )
    }

    /** Постер не больше 1600 px по длинной стороне: карточке больше не нужно, а память дорога. */
    private fun decodePoster(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/**
 * Рисует карточку и открывает системное меню «Поделиться». Файл лежит в `cache/share/` (путь уже
 * разрешён в FileProvider) и перезаписывается при каждой отправке, так что не копится.
 */
suspend fun shareWatchingCard(
    context: Context,
    builder: WatchingCardBuilder,
    anime: Anime,
    title: String,
    ru: Boolean,
    posterPath: String?,
) {
    val uri = runCatching {
        withContext(Dispatchers.Default) {
            val data = builder.build(anime, title, ru, posterPath)
            val bitmap = WatchingCardRenderer.render(data)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "watching_card.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            data.poster?.recycle()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }.getOrElse {
        MiniNotif.show(
            context,
            if (ru) "Не удалось собрать карточку" else "Couldn't build the card",
            MiniNotifKind.Error,
        )
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, if (ru) "Поделиться карточкой" else "Share card"))
}
