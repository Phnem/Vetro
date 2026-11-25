package com.example.myapplication.ui.details.share

import com.example.myapplication.utils.runSuspendCatching
import com.example.myapplication.utils.takeWholeChars
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.regularSeasons
import com.example.myapplication.domain.status.AutoWatchStatusResolver
import com.example.myapplication.media.progress.EpisodePlaybackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Что пользователь меняет в шторке предпросмотра карточки. */
data class WatchingCardOptions(
    /** Минимум данных: без оценки, заметки и текущей серии, вместо них жанры. */
    val minimal: Boolean = false,
    /** Ник на карточке; пустой — вместо него имя приложения. */
    val nick: String = "",
    /** Комментарий пользователя (до [MAX_COMMENT_LENGTH] символов): пузырь-мысль над карточкой; пустой — без пузыря. */
    val comment: String = "",
) {
    companion object {
        const val MAX_COMMENT_LENGTH = 52

        /** Ник влезает в шапку карточки рядом с плашкой типа. */
        const val MAX_NICK_LENGTH = 22
    }
}

/**
 * Собирает данные карточки «Что я сейчас смотрю» из тех же источников, что и прогресс на карточке
 * главной: досмотренные серии, расклад сезонов, счётчик серий, чтение манги. Тема и язык приходят от
 * вызывающего: это настройки приложения, а не системы.
 */
class WatchingCardBuilder(
    private val playback: EpisodePlaybackStore,
    private val seasonEpisodes: SeasonEpisodesStore,
    private val seriesSeasons: SeriesSeasonsStore,
    private val autoStatus: AutoWatchStatusResolver,
) {
    /** Рисует карточку целиком. Постер читается и освобождается внутри, наружу уходит только картинка. */
    suspend fun render(
        anime: Anime,
        title: String,
        ru: Boolean,
        dark: Boolean,
        posterPath: String?,
        status: WatchStatus?,
        description: String?,
        options: WatchingCardOptions,
    ): Bitmap = withContext(Dispatchers.Default) {
        val data = build(anime, title, ru, dark, posterPath, status, description, options)
        val bitmap = WatchingCardRenderer.render(data, full = !options.minimal)
        data.poster?.recycle()
        bitmap
    }

    /**
     * Данные для живого предпросмотра: ник и комментарий подставляет вызывающий через `copy`, а режим данных
     * сюда не входит вовсе: карточка держит оба вида сразу, и предпросмотр плавно переходит между ними.
     * Постер остаётся у данных, пока ими пользуется предпросмотр.
     */
    internal suspend fun prepare(
        anime: Anime,
        title: String,
        ru: Boolean,
        dark: Boolean,
        posterPath: String?,
        status: WatchStatus?,
        description: String?,
    ): ShareCardData = withContext(Dispatchers.Default) {
        build(anime, title, ru, dark, posterPath, status, description, WatchingCardOptions())
    }

    internal suspend fun build(
        anime: Anime,
        title: String,
        ru: Boolean,
        dark: Boolean,
        posterPath: String?,
        status: WatchStatus?,
        description: String?,
        options: WatchingCardOptions,
    ): ShareCardData {
        val isManga = anime.mediaType == MediaType.MANGA
        var progressBase: String? = null
        var fraction: Float? = null
        var currentUnit: String? = null
        if (isManga) {
            val summary = autoStatus.mangaReadingSnapshot()[anime.id]
            if (summary != null && summary.readChapters > 0) {
                val unit = if (ru) "гл." else "ch."
                val total = summary.totalChapters?.takeIf { it > 0 }
                progressBase = if (total != null) "${summary.readChapters} / $total $unit" else "${summary.readChapters} $unit"
                if (total != null) fraction = summary.fraction
                currentUnit = if (ru) "Глава ${summary.readChapters}" else "Chapter ${summary.readChapters}"
            }
        } else {
            val key = playback.furthestEpisodeFlow(listOf(anime.id), completedOnly = true).first()[anime.id]
            if (key != null && key.episode > 0) {
                val layout = (seriesSeasons.flow.first() + seasonEpisodes.flow.first())[anime.id]
                val seasons = layout.regularSeasons()
                val season = seasons.firstOrNull { it.seasonNumber == key.season }
                val seasonTotal = (season?.let { it.totalEpisodes ?: it.episodes }
                    ?: anime.episodes.takeIf { seasons.isEmpty() && key.season == 1 && it > 0 })
                    ?.takeIf { it > 0 }
                val multiSeason = seasons.size > 1 || key.season > 1
                val unit = if (ru) "эп." else "ep."
                val prefix = if (multiSeason) "S${key.season} · " else ""
                progressBase = if (seasonTotal != null) "$prefix${key.episode} / $seasonTotal $unit" else "$prefix${key.episode} $unit"
                seasonTotal?.let { fraction = (key.episode.toFloat() / it).coerceIn(0f, 1f) }
                currentUnit = when {
                    multiSeason && ru -> "С${key.season} Э${key.episode}"
                    multiSeason -> "S${key.season} E${key.episode}"
                    ru -> "Эп. ${key.episode}"
                    else -> "Ep. ${key.episode}"
                }
            }
        }
        // «• 68%» стоит отдельно: в минимуме плашка короче, и предпросмотр вытягивает её плавно.
        val progressSuffix = fraction?.let { " • ${(it * 100f).roundToInt()}%" }
        val typeLabel = when (anime.mediaType) {
            MediaType.ANIME -> if (ru) "Аниме" else "Anime"
            MediaType.MANGA -> if (ru) "Манга" else "Manga"
            MediaType.MOVIE -> if (ru) "Фильм" else "Movie"
            MediaType.SERIES -> if (ru) "Сериал" else "Series"
        }
        val tags = anime.tags.filter { it.isNotBlank() }
        val year = anime.releaseYear?.takeIf { it > 0 }?.toString().orEmpty()
        // Под оранжевой строкой: своя заметка тайтла в «ёлочках», а если её нет, то описание тайтла как в Details.
        val ownNote = anime.comment.trim().takeIf { it.isNotEmpty() }?.let { it.takeWholeChars(240) }
        val note = ownNote ?: cleanDescription(description)
        val rating = anime.rating.takeIf { it > 0f }?.let {
            val shown = if (it % 1f == 0f) it.toInt().toString() else RatingScale.format(it)
            "$shown/10"
        }
        val poster = posterPath?.let { decodePoster(it) }
        return ShareCardData(
            nick = options.nick.trim(),
            typeLabel = typeLabel,
            year = year,
            statusLabel = statusLabel(status, isManga, ru),
            title = title,
            progressText = progressBase,
            progressSuffix = progressSuffix,
            fraction = fraction,
            ratingText = rating,
            currentUnit = currentUnit,
            note = note,
            noteQuoted = ownNote != null,
            comment = options.comment.trim().takeWholeChars(WatchingCardOptions.MAX_COMMENT_LENGTH).ifEmpty { null },
            genres = tags.take(3).joinToString(" • ").ifEmpty { null },
            footerLeft = tags.take(2).joinToString(" • ").ifEmpty { "Vetro Collection" },
            footerRight = addedSince(anime.dateAdded, ru),
            footerLeftMin = "Vetro Collection",
            footerRightMin = year,
            dark = dark,
            poster = poster,
            ambient = WatchingCardRenderer.paletteFor(poster),
        )
    }

    /**
     * Описание из источников приходит с разметкой: HTML-теги, BB-коды Shikimori («[character=1]…[/character]»),
     * пометки об источнике. На карточку идёт чистый текст без переносов, не длиннее 320 символов (дальше
     * текст всё равно обрежется по числу строк).
     */
    private fun cleanDescription(raw: String?): String? {
        val text = raw?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val cleaned = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\[/?[a-zA-Z_]+(=[^\\]]*)?\\]"), "")
            .replace(Regex("\\((Source|Written by)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(Source|Written by)[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }?.let { it.takeWholeChars(320) }
    }

    private fun statusLabel(status: WatchStatus?, manga: Boolean, ru: Boolean): String = when {
        status == null -> if (ru) "В коллекции" else "In collection"
        status == WatchStatus.WATCHING && manga -> if (ru) "Читаю" else "Reading"
        status == WatchStatus.COMPLETED && manga -> if (ru) "Прочитано" else "Completed"
        else -> status.label(ru)
    }

    private fun addedSince(dateAdded: Long, ru: Boolean): String {
        if (dateAdded <= 0L) return ""
        val format = SimpleDateFormat("d MMM yyyy", Locale(if (ru) "ru" else "en"))
        val date = format.format(Date(dateAdded)).replace(".", "")
        return if (ru) "С $date" else "Since $date"
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

/** Действия с готовой карточкой: сохранить PNG в галерею и открыть системное меню «Поделиться». */
object WatchingCardActions {
    /**
     * Кладёт PNG в «Картинки/Vetro». С Android 10 запись идёт через MediaStore без разрешений; на более
     * старых системах нужно разрешение на запись, которого у приложения нет, и вернётся false.
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = "Vetro_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vetro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.isSuccess
    }

    /**
     * Открывает системное меню «Поделиться». Файл лежит в `cache/share/` (путь уже разрешён в
     * FileProvider) и перезаписывается при каждой отправке, так что не копится.
     */
    suspend fun share(context: Context, bitmap: Bitmap, ru: Boolean): Boolean {
        val uri = runSuspendCatching {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "watching_card.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            // Без ClipData часть меню «Поделиться» не получает права на чтение и не показывает превью.
            clipData = android.content.ClipData.newRawUri(null, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, if (ru) "Поделиться карточкой" else "Share card")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
