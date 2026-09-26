package com.example.myapplication.ui.details

import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.ThumbUp
import com.example.myapplication.network.enrichment.OmdbRatings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Segment
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.network.AnimeDetails
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape

/**
 * Одна карточка секции «Информация»: иконка в круглом колодце + подпись + значение.
 *
 * Карточка существует только если у неё есть значение — пустых плашек «—» в сетке нет: разные
 * источники отдают разный набор полей (Shikimori, например, вообще не знает первоисточник),
 * и половина сетки из прочерков читалась бы как поломка загрузки, а не как «источник не сказал».
 */
data class DetailFact(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * Собирает факты тайтла из локальной записи и подгруженной карточки API.
 * Порядок фиксированный (Рейтинг, Статус, Эпизоды, Формат, Релиз, Источник, Студия) — сетка не
 * должна перетасовываться, когда у соседнего тайтла нет студии.
 */
fun buildDetailFacts(
    anime: Anime,
    details: AnimeDetails?,
    ru: Boolean,
    ratings: OmdbRatings? = null,
): List<DetailFact> {
    val isManga = anime.mediaType == MediaType.MANGA
    val facts = mutableListOf<DetailFact>()

    // Рейтинг идёт первым: это единственный факт, который переехал сюда из верхней строки чипсов
    // (её остальные пункты — дата и число серий — дублировали «Релиз» и «Эпизоды»). Задвинуть его
    // в конец сетки значило бы обесценить сам смысл переноса.
    ratingLabel(anime, details)?.let { label ->
        facts += DetailFact(
            key = "rating",
            icon = Icons.Rounded.Star,
            label = if (ru) "Рейтинг" else "Rating",
            value = label,
        )
    }

    // Оценки критиков (OMDb) — сразу за своим рейтингом: это тот же вопрос «насколько хорошо».
    ratings?.imdbRating?.let { facts += DetailFact("imdb", Icons.Rounded.Star, "IMDb", "%.1f".format(it)) }
    ratings?.rottenTomatoes?.let { facts += DetailFact("rt", Icons.Rounded.ThumbUp, "Rotten Tomatoes", "$it%") }
    ratings?.metacritic?.let { facts += DetailFact("metacritic", Icons.Rounded.Leaderboard, "Metacritic", "$it") }

    val statusCode = normalizeCode(details?.status)
    statusLabel(statusCode, ru)?.let { label ->
        facts += DetailFact(
            key = "status",
            icon = statusIcon(statusCode),
            label = if (ru) "Статус" else "Status",
            value = label,
        )
    }

    val count = details?.episodesTotal?.takeIf { it > 0 }
        ?: anime.episodes.takeIf { it > 0 }
        ?: details?.episodesAired?.takeIf { it > 0 }
    if (count != null) {
        facts += DetailFact(
            key = "count",
            icon = Icons.AutoMirrored.Rounded.Segment,
            label = when {
                isManga && ru -> "Главы"
                isManga -> "Chapters"
                ru -> "Эпизоды"
                else -> "Episodes"
            },
            value = if (isManga) chapterCountText(count, ru) else episodeCountText(count, ru),
        )
    }

    formatLabel(normalizeCode(details?.format) ?: normalizeCode(details?.type), ru)?.let { label ->
        facts += DetailFact(
            key = "format",
            icon = if (isManga) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Tv,
            label = if (ru) "Формат" else "Format",
            value = label,
        )
    }

    releaseLabel(details, ru)?.let { label ->
        facts += DetailFact(
            key = "release",
            icon = Icons.Rounded.CalendarMonth,
            label = if (ru) "Релиз" else "Release",
            value = label,
        )
    }

    sourceMaterialLabel(normalizeCode(details?.sourceMaterial), ru)?.let { label ->
        facts += DetailFact(
            key = "source",
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            label = if (ru) "Источник" else "Source",
            value = label,
        )
    }

    details?.studio?.takeIf { it.isNotBlank() }?.let { studio ->
        facts += DetailFact(
            key = "studio",
            icon = Icons.Rounded.Apartment,
            label = if (ru) "Студия" else "Studio",
            value = studio,
        )
    }

    return facts
}

/** Сетка 2×N. Живёт внутри вертикального скролла, поэтому строки раскладываются вручную. */
@Composable
fun DetailFactGrid(
    facts: List<DetailFact>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FactGridSpacing),
    ) {
        facts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(FactGridSpacing)) {
                row.forEach { fact ->
                    DetailFactCard(fact = fact, isDark = isDark, modifier = Modifier.weight(1f))
                }
                // Нечётный хвост: карточка остаётся своей половины ширины, а не растягивается.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailFactCard(
    fact: DetailFact,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val cardBg = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    val wellBg = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.White
    Row(
        modifier = modifier
            .heightIn(min = FactCardHeight)
            .clip(SquircleShape(FactCardRadius))
            .background(cardBg)
            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(FactWellSize)
                .clip(CircleShape)
                .background(wellBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fact.icon,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = fact.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.06.em,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = fact.value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val FactGridSpacing = 10.dp
private val FactCardHeight = 62.dp
private val FactCardRadius = 26.dp
private val FactWellSize = 44.dp

// ==========================================
// Словари кодов источников → человеческие подписи
// ==========================================

/**
 * Источники пишут одно и то же по-разному: AniList — `NOT_YET_RELEASED`, Jikan — `Not yet aired`,
 * Shikimori — `anons`. Приводим к одному виду ДО сопоставления, чтобы словари были плоскими.
 */
private fun normalizeCode(raw: String?): String? =
    raw?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase()
        ?.replace(' ', '_')
        ?.replace('-', '_')

private fun statusLabel(code: String?, ru: Boolean): String? = when (code) {
    "FINISHED", "FINISHED_AIRING", "RELEASED", "COMPLETE", "COMPLETED" -> if (ru) "Вышел" else "Released"
    "RELEASING", "CURRENTLY_AIRING", "ONGOING", "PUBLISHING" -> if (ru) "Выходит" else "Airing"
    "NOT_YET_RELEASED", "NOT_YET_AIRED", "ANONS", "ANNOUNCED", "UPCOMING" -> if (ru) "Анонс" else "Announced"
    "CANCELLED", "CANCELED", "DISCONTINUED" -> if (ru) "Отменён" else "Cancelled"
    "HIATUS", "PAUSED", "ON_HIATUS" -> if (ru) "Пауза" else "On hiatus"
    else -> null
}

private fun statusIcon(code: String?): ImageVector = when (code) {
    "RELEASING", "CURRENTLY_AIRING", "ONGOING", "PUBLISHING" -> Icons.Rounded.Autorenew
    "NOT_YET_RELEASED", "NOT_YET_AIRED", "ANONS", "ANNOUNCED", "UPCOMING" -> Icons.Rounded.Schedule
    "CANCELLED", "CANCELED", "DISCONTINUED" -> Icons.Rounded.Close
    "HIATUS", "PAUSED", "ON_HIATUS" -> Icons.Rounded.PauseCircleOutline
    else -> Icons.Rounded.Check
}

private fun formatLabel(code: String?, ru: Boolean): String? = when (code) {
    null -> null
    "TV" -> "TV"
    "TV_SHORT", "TV_SPECIAL" -> if (ru) "TV-спешл" else "TV Short"
    "MOVIE" -> if (ru) "Фильм" else "Movie"
    "SPECIAL" -> if (ru) "Спешл" else "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> if (ru) "Клип" else "Music"
    "MANGA" -> if (ru) "Манга" else "Manga"
    "MANHWA" -> if (ru) "Манхва" else "Manhwa"
    "MANHUA" -> if (ru) "Маньхуа" else "Manhua"
    "NOVEL", "LIGHT_NOVEL" -> if (ru) "Ранобэ" else "Light novel"
    "ONE_SHOT" -> if (ru) "Ваншот" else "One-shot"
    // Незнакомый код лучше показать как есть, чем спрятать карточку: «ANIME» — тоже ответ.
    else -> code.lowercase().replaceFirstChar { it.uppercase() }
}

private fun sourceMaterialLabel(code: String?, ru: Boolean): String? = when (code) {
    null -> null
    "ORIGINAL" -> if (ru) "Ориджинал" else "Original"
    "MANGA", "WEB_MANGA", "COMIC" -> if (ru) "Манга" else "Manga"
    "LIGHT_NOVEL" -> if (ru) "Ранобэ" else "Light novel"
    "NOVEL", "WEB_NOVEL" -> if (ru) "Новелла" else "Novel"
    "VISUAL_NOVEL" -> if (ru) "Виз. новелла" else "Visual novel"
    "VIDEO_GAME", "GAME" -> if (ru) "Игра" else "Game"
    "DOUJINSHI" -> if (ru) "Додзинси" else "Doujinshi"
    "ANIME" -> if (ru) "Аниме" else "Anime"
    "LIVE_ACTION" -> if (ru) "Дорама" else "Live action"
    "PICTURE_BOOK" -> if (ru) "Книга" else "Picture book"
    "MULTIMEDIA_PROJECT" -> if (ru) "Мультимедиа" else "Multimedia"
    "OTHER", "UNKNOWN" -> null
    else -> code.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Рейтинг для карточки фактов. Приоритет у оценки источника — ровно как было в прежнем чипсе,
 * который эта карточка заменяет.
 *
 * API отдаёт рейтинг и по 10-балльной, и по 100-балльной шкале, отсюда развилка по `> 10`.
 * Своя оценка форматируется через [RatingScale]: в БД она лежит целым ×10, вручную делить нельзя.
 *
 * Возвращает null, когда рейтинга нет ни там, ни там, — карточка тогда просто не попадает в
 * сетку, как и остальные факты без значения. Показывать «0» нельзя: ноль здесь означал бы
 * «оценка ноль», а не «оценки нет».
 */
private fun ratingLabel(anime: Anime, details: AnimeDetails?): String? {
    val api = details?.rating?.takeIf { it > 0 }
    if (api != null) return if (api > 10) "$api/100" else "$api/10"
    val user = anime.rating.takeIf { it > 0f } ?: return null
    return "${RatingScale.format(user)}/10"
}

private fun releaseLabel(details: AnimeDetails?, ru: Boolean): String? {
    if (details == null) return null
    val season = seasonLabel(normalizeCode(details.season), ru)
    val year = details.seasonYear ?: details.airedOn?.take(4)?.toIntOrNull()
    return when {
        season != null && year != null -> "$season $year"
        year != null -> year.toString()
        else -> details.airedOn?.takeIf { it.isNotBlank() }
    }
}

private fun seasonLabel(code: String?, ru: Boolean): String? = when (code) {
    "WINTER" -> if (ru) "Зима" else "Winter"
    "SPRING" -> if (ru) "Весна" else "Spring"
    "SUMMER" -> if (ru) "Лето" else "Summer"
    "FALL", "AUTUMN" -> if (ru) "Осень" else "Fall"
    else -> null
}

/** «1 серия / 2 серии / 8 серий» — без этого счётчик в карточке выглядит машинным. */
private fun episodeCountText(count: Int, ru: Boolean): String =
    if (ru) "$count ${plural(count, "серия", "серии", "серий")}" else "$count ep."

private fun chapterCountText(count: Int, ru: Boolean): String =
    if (ru) "$count ${plural(count, "глава", "главы", "глав")}" else "$count ch."

private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
