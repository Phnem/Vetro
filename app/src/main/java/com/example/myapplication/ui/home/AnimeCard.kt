package com.example.myapplication.ui.home

import com.example.myapplication.ui.shared.theme.BrandLightGray
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.LocalFullBleedCards
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.LightBorder
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import com.phnem.vetro.R
import com.example.myapplication.ui.shared.theme.getRatingColor
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import java.io.File

/**
 * Что показывает бар на карточке:
 *  • [AIRING] — выход серий сезона («S5 4 / 14 ep.»), фиолетовый;
 *  • [WATCHING] — просмотр пользователя внутри сезона («S3 6 / 12 ep.»), брендовый оранжевый;
 *  • [READING] — чтение манги по главам («12 / 60 ch.»), брендовый оранжевый.
 */
enum class CardProgressKind { AIRING, WATCHING, READING }

/** Прогресс на карточке — выход сезона, просмотр либо чтение, см. [CardProgressKind]. */
@Immutable
data class AiringCardInfo(
    /** null — номер сезона неизвестен (источник без графа франшизы, тайтл без расклада) или манга. */
    val seasonNumber: Int?,
    val airedEpisodes: Int,
    /** null — число серий сезона ещё не анонсировано (бар не рисуем, только текст). */
    val totalEpisodes: Int?,
    val kind: CardProgressKind = CardProgressKind.AIRING,
    /** Сколько новых глав вышло с последнего чтения; 0 — метку не показываем. */
    val newItems: Int = 0,
)

@Immutable
data class AnimeCardState(
    val id: String,
    val title: String,
    /** Английское название (дубляж); показывается второй строкой, если отличается от [title]. */
    val titleEn: String? = null,
    /** 10-балльная шкала, 0 = не оценено. */
    val rating: Float,
    val genres: PersistentList<String>,
    /** Подпись внизу карточки: «S3 E12» по раскладу сезонов, иначе «12 eps.» / «60 ch.». */
    val episodesLabel: String,
    val imagePath: String?,
    val mediaTypeLabel: String,
    /** Тип контента — иконка в круге бейджа поверх постера. */
    val mediaType: MediaType = MediaType.ANIME,
    /** Избранное: карточка поднята в начало списка и обведена рамкой, чтобы это было видно. */
    val isFavorite: Boolean = false,
    /** Найденные прямые ссылки по одобренным сайтам (для языка [language]). */
    val webLinks: List<com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink> = emptyList(),
    val language: com.example.myapplication.network.AppLanguage = com.example.myapplication.network.AppLanguage.EN,
    /** Не-null = тайтл «в процессе»: прямо сейчас выходит сезон. */
    val airing: AiringCardInfo? = null,
)

/** Цвет заполнения бара прогресса сезона (фиолетовый, как на референсе). */
private val AiringBarColor = Color(0xFF6C4BF4)

/**
 * Секция «в процессе»: слева подпись, справа «S3 2 / 12 ep.», ниже — бар прогресса
 * выходящего сезона. Если источники не знают, сколько серий будет всего, оцениваем
 * по курам (кратно 12): вышло ≤12 → «n / 12», 13–24 → «n / 24», дальше 36, 48 и т.д.
 */
@Composable
internal fun AiringProgressSection(
    airing: AiringCardInfo,
    language: com.example.myapplication.network.AppLanguage,
    labelColor: Color,
    trackColor: Color,
) {
    val label = when (language) {
        com.example.myapplication.network.AppLanguage.RU -> "Прогресс"
        com.example.myapplication.network.AppLanguage.EN -> "Progress"
    }
    // Оценка по курам — приём для сезонов аниме: у манги число глав либо известно из оглавления,
    // либо неизвестно вовсе, и выдумывать знаменатель нельзя.
    val total = airing.totalEpisodes?.takeIf { it > 0 }
        ?: courEstimatedTotal(airing.airedEpisodes).takeIf { airing.kind != CardProgressKind.READING }
    val unit = if (airing.kind == CardProgressKind.READING) "ch." else "ep."
    val counter = buildString {
        // И выход, и просмотр считаются внутри сезона — номер сезона пишется всегда, когда известен.
        airing.seasonNumber?.let { append("S").append(it).append(" ") }
        append(airing.airedEpisodes)
        if (total != null) append(" / ").append(total)
        append(" ").append(unit)
    }
    val barColor = when (airing.kind) {
        CardProgressKind.AIRING -> AiringBarColor
        CardProgressKind.WATCHING, CardProgressKind.READING -> BrandOrangeBright
    }
    // Компактно: секция живёт внутри фиксированных 180dp карточки, каждый dp на счету.
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SnProFamily,
                    color = labelColor,
                )
                // Метка новых глав — брендовым оранжевым, рядом с подписью прогресса. Своего
                // индикатора «вышло новое» у карточек нет вовсе (у аниме это пуш-стопка сверху),
                // поэтому берём самую компактную форму из спеки.
                if (airing.newItems > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(CircleShape)
                            .background(BrandOrangeBright)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "+${airing.newItems}",
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SnProFamily,
                            color = Color.White,
                        )
                    }
                }
            }
            Text(
                text = counter,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SnProFamily,
                color = labelColor,
            )
        }
        // Знаменатель неизвестен (у манги — оглавление ещё не загружено): бар рисовать не от чего,
        // остаётся один счётчик прочитанного.
        if (total != null) {
            val fraction = (airing.airedEpisodes.toFloat() / total).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(barColor)
                )
            }
        }
    }
}

/** Оценка серий сезона по курам: ближайшее кратное 12 сверху (минимум 12). */
private fun courEstimatedTotal(aired: Int): Int {
    val cours = ((aired - 1).coerceAtLeast(0) / 12) + 1
    return cours * 12
}

/**
 * Карточка в списке: тело [AnimeCardBody] плюс модификаторы shared-element для перехода
 * в Details. Копия карточки в контекстном меню (долгое удержание) рисует ТО ЖЕ тело, но без
 * этих модификаторов — ключ shared-element уже занят карточкой в списке, второй узел с тем же
 * ключом Compose не разрешает.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.OneUiAnimeCard(
    state: AnimeCardState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    AnimeCardBody(
        state = state,
        modifier = modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "anime_${state.id}_bounds"),
            animatedVisibilityScope = animatedVisibilityScope,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(24.dp))
        ),
        posterModifier = Modifier
            .sharedElement(
                sharedContentState = rememberSharedContentState(key = "anime_${state.id}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
            .skipToLookaheadSize(),
        onClick = onClick,
        onLongClick = onLongClick,
        onEditClick = onEditClick,
    )
}

/**
 * Тело карточки без привязки к shared-element и к списку.
 *
 * @param onClick null — карточка не реагирует на нажатия (копия в оверлее контекстного меню).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeCardBody(
    state: AnimeCardState,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
) {
    if (LocalFullBleedCards.current) {
        FullBleedAnimeCardBody(state, modifier, posterModifier, onClick, onLongClick, onEditClick)
        return
    }
    val isDark = isAppInDarkTheme()
    // Рамка избранного — затухающая: золотая в левом верхнем углу и уходящая в прозрачность к
    // правому нижнему. Роль «это избранное» теперь несёт угловой чипс со звездой, поэтому рамке
    // не нужно быть толстой меткой — хватает тонкого golden edge, и карточка не выглядит
    // обведённой в рамку.
    val borderBrush = when {
        state.isFavorite -> Brush.linearGradient(
            0f to OverlayThemeTokens.FavoriteGold,
            0.45f to OverlayThemeTokens.FavoriteGold.copy(alpha = 0.45f),
            1f to Color.Transparent,
        )
        isDark -> SolidColor(Color.White.copy(alpha = 0.15f))
        else -> SolidColor(LightBorder)
    }
    val borderWidth = 1.dp
    val cardBg = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface
    val cardShadowColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.08f)
    val subtitleColor = if (isDark) BrandLightGray else Color(0xFF8E8E93)
    val chipBg = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .then(
                if (onClick != null) {
                    Modifier.fluidClickable(
                        scaleDown = 0.975f,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .shadow(
                // В тёмной теме тень — чёрное по фону #000000: её не видно, а RenderThread
                // строил её для каждой карточки на экране. Узел оставляем, высоту обнуляем.
                elevation = if (isDark) 0.dp else 4.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = cardShadowColor
            )
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg)
            .border(
                BorderStroke(borderWidth, borderBrush),
                RoundedCornerShape(24.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(0.7f)
                    .then(posterModifier)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF2C2C2C) else Color(0xFFE8E8E8)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.title.take(1).uppercase(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isDark) Color.White.copy(alpha = 0.3f)
                    else Color.Black.copy(alpha = 0.15f),
                    fontFamily = SnProFamily
                )
                state.imagePath?.let { imgPath ->
                    val context = LocalContext.current
                    val request = remember(imgPath) {
                        ImageRequest.Builder(context)
                            .data(File(imgPath))
                            .size(Size(280, 400))
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = state.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MediaTypePill(
                    mediaType = state.mediaType,
                    label = state.mediaTypeLabel,
                    isDark = isDark,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // Угол карточки у избранного занят золотым вымпелом (до 18dp внутрь
                        // постера) — пилюля встаёт справа от него, на той же высоте.
                        .padding(start = if (state.isFavorite) 22.dp else 6.dp, top = 6.dp, end = 6.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                lineHeight = 22.sp,
                                fontFamily = SnProFamily
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val en = state.titleEn?.takeIf {
                            it.isNotBlank() && !it.equals(state.title, ignoreCase = true)
                        }
                        if (en != null) {
                            Text(
                                text = en,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = SnProFamily,
                                    fontSize = 12.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = subtitleColor,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (state.rating > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isDark) Color.Black
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                        ) {
                            Text(
                                text = "★ ${RatingScale.format(state.rating)}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = getRatingColor(state.rating),
                                    fontFamily = SnProFamily
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (state.genres.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        // С прогресс-секцией второй ряд жанров скрываем (только визуально:
                        // сами теги остаются в данных/сортировке) — иначе низ карточки не влезает.
                        maxLines = if (state.airing != null) 1 else 2,
                    ) {
                        state.genres.forEach { genre ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = genre,
                                    fontSize = 12.sp,
                                    color = subtitleColor,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = SnProFamily
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                state.airing?.let { airing ->
                    AiringProgressSection(
                        airing = airing,
                        language = state.language,
                        labelColor = subtitleColor,
                        trackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.episodesLabel,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontFamily = SnProFamily
                        ),
                        color = subtitleColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Стопка сайтов занимает гибкую середину ряда и сама сокращается до «+N»,
                    // если места мало — кнопку Details не двигает никогда.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        com.example.myapplication.ui.shared.components.WebLinkChipStack(
                            links = state.webLinks,
                            language = state.language,
                            ringColor = cardBg,
                        )
                    }
                    FilledTonalButton(
                        onClick = { onEditClick?.invoke() },
                        enabled = onEditClick != null,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 0.dp
                        ),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when (state.language) {
                                    com.example.myapplication.network.AppLanguage.RU -> "Edit"
                                    com.example.myapplication.network.AppLanguage.EN -> "Edit"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = SnProFamily,
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // Последним в Box — значит поверх постера, как на референсе. Внешний clip карточки
        // (RoundedCornerShape(24.dp)) срезает чипсу верхний левый угол ровно по краю карточки,
        // поэтому он садится в угол заподлицо, а не висит квадратом поверх скругления.
        if (state.isFavorite) {
            FavoriteCornerChip(modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** Иконка типа контента для бейджа на постере (Phosphor, fill — читается на 12dp). */
private fun MediaType.pillIcon(): Int = when (this) {
    MediaType.ANIME -> R.drawable.ph_sparkle_fill
    MediaType.MANGA -> R.drawable.ph_book_open_text_fill
    MediaType.MOVIE -> R.drawable.ph_film_slate_fill
    MediaType.SERIES -> R.drawable.ph_television_simple_fill
}

/**
 * Бейдж типа контента — по принципу капсулы мини-плеера аудиокниг: круг с картинкой слева,
 * дальше текст в той же капсуле. В круге — иконка типа (белый круг и чёрная иконка в тёмной теме,
 * наоборот — в светлой), в капсуле — название типа.
 */
@Composable
internal fun MediaTypePill(
    mediaType: MediaType,
    label: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
) {
    val circle = if (isDark) Color.White else Color.Black
    val onCircle = if (isDark) Color.Black else Color.White
    val capsule = if (isDark) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.9f)
    val text = if (isDark) Color.White else Color.Black
    Row(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(capsule)
            .padding(start = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(height - 4.dp)
                .clip(CircleShape)
                .background(circle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(mediaType.pillIcon()),
                contentDescription = null,
                tint = onCircle,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        // Строка обязана поместиться в капсулу фиксированной высоты при любом масштабе шрифта
        // (как в капсуле мини-плеера): масштаб в крошечной капсуле ограничен.
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.1f)),
        ) {
            Text(
                text = label,
                color = text,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SnProFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Угловой чипс избранного — золотой «вымпел» со звездой в левом верхнем углу карточки.
 *
 * Вместе с затухающей рамкой заменяет прежнюю толстую обводку: рамка даёт золотой край, чипс —
 * однозначную метку. Скругления подобраны под карточку: верхний левый угол совпадает с её
 * радиусом, нижний правый скруглён мягче, отчего форма читается вымпелом, а не плашкой.
 */
@Composable
internal fun FavoriteCornerChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(
                RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 0.dp,
                    bottomEnd = 14.dp,
                    bottomStart = 0.dp,
                ),
            )
            .background(OverlayThemeTokens.FavoriteGold),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = OverlayThemeTokens.OnFavoriteGold,
            modifier = Modifier
                // Звезда смещена к внешнему углу — в центре квадрата она визуально «падает»
                // от скруглённого угла вправо-вниз.
                .offset(x = (-1).dp, y = (-1).dp)
                .size(15.dp),
        )
    }
}
