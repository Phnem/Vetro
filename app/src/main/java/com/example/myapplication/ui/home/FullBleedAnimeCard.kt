package com.example.myapplication.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.ui.shared.components.WebLinkChipStack
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.getRatingColor
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import java.io.File

/**
 * Экспериментальная карточка (dev-флаг `FULL_BLEED_CARDS`): обложка на всю площадь, текст —
 * поверх затемнения слева, как у баннеров-подборок.
 *
 * Без постера сбоку освобождается ширина, поэтому раскладка другая: веб-ссылки поднялись в
 * верхний ряд к рейтингу, прогресс сезона делит нижний ряд с кнопкой Edit, а название стало
 * крупнее. Карточка всегда тёмная по содержимому — текст белый в обеих темах, читаемость держит
 * скрим, а не цвет поверхности.
 */
private val FullBleedCardHeight = 192.dp
private val FullBleedCardShape = RoundedCornerShape(24.dp)
private val FullBleedBase = Color(0xFF1C1C1C)

/** Тень под текстом поверх картинки: на светлых участках обложки скрима мало. */
private val TextOnImageShadow = Shadow(
    color = Color.Black.copy(alpha = 0.55f),
    offset = Offset(0f, 1f),
    blurRadius = 6f,
)

/** Слева тёмная зона под текст, справа обложка видна почти без затемнения. */
private val SideScrim = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.86f),
    0.45f to Color.Black.copy(alpha = 0.58f),
    0.8f to Color.Black.copy(alpha = 0.12f),
    1f to Color.Transparent,
)

/** Снизу — под ряд с прогрессом и кнопкой, который тянется на всю ширину. */
private val BottomScrim = Brush.verticalGradient(
    0.5f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.6f),
)

/** Кадрирование портретной обложки в широкую карточку: лица обычно в верхней трети. */
private val CoverAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.35f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullBleedAnimeCardBody(
    state: AnimeCardState,
    modifier: Modifier,
    posterModifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onEditClick: (() -> Unit)?,
) {
    val isDark = isAppInDarkTheme()
    val borderBrush = if (state.isFavorite) {
        Brush.linearGradient(
            0f to OverlayThemeTokens.FavoriteGold,
            0.45f to OverlayThemeTokens.FavoriteGold.copy(alpha = 0.45f),
            1f to Color.Transparent,
        )
    } else {
        SolidColor(Color.White.copy(alpha = 0.12f))
    }
    val textSecondary = Color.White.copy(alpha = 0.78f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FullBleedCardHeight)
            .then(
                if (onClick != null) {
                    Modifier.fluidClickable(scaleDown = 0.975f, onLongClick = onLongClick, onClick = onClick)
                } else Modifier
            )
            .shadow(
                elevation = if (isDark) 0.dp else 4.dp,
                shape = FullBleedCardShape,
                spotColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.12f),
            )
            .clip(FullBleedCardShape)
            .background(FullBleedBase)
    ) {
        // Обложка — тот же shared element, что постер классической карточки: переход в Details
        // морфит её из полной карточки в постер. clip после posterModifier, чтобы скругление
        // ехало вместе с картинкой в оверлее перехода.
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(posterModifier)
                .clip(FullBleedCardShape)
                .background(FullBleedBase),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = state.title.take(1).uppercase(),
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.08f),
                fontFamily = SnProFamily,
                modifier = Modifier.padding(end = 28.dp),
            )
            state.imagePath?.let { imgPath ->
                val context = LocalContext.current
                // Без явного size: Coil берёт размер из ограничений, то есть по ширине карточки.
                val request = remember(imgPath) {
                    ImageRequest.Builder(context)
                        .data(File(imgPath))
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = state.title,
                    contentScale = ContentScale.Crop,
                    alignment = CoverAlignment,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.matchParentSize().background(SideScrim))
        Box(Modifier.matchParentSize().background(BottomScrim))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaTypePill(
                    mediaType = state.mediaType,
                    label = state.mediaTypeLabel,
                    isDark = isDark,
                    // Вымпел избранного занимает угол (30dp) — пилюля встаёт правее него.
                    modifier = Modifier.padding(start = if (state.isFavorite) 18.dp else 0.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    WebLinkChipStack(
                        links = state.webLinks,
                        language = state.language,
                        chipSize = 24.dp,
                        ringColor = Color.Black,
                    )
                }
                if (state.rating > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "★ ${RatingScale.format(state.rating)}",
                            color = getRatingColor(state.rating),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = SnProFamily,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = state.title,
                style = TextStyle(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 21.sp,
                    lineHeight = 24.sp,
                    shadow = TextOnImageShadow,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            val en = state.titleEn?.takeIf { it.isNotBlank() && !it.equals(state.title, ignoreCase = true) }
            if (en != null) {
                Text(
                    text = en,
                    style = TextStyle(fontFamily = SnProFamily, fontSize = 12.sp, shadow = TextOnImageShadow),
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(top = 2.dp),
                )
            }

            if (state.genres.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxLines = 1,
                ) {
                    state.genres.forEach { genre ->
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = genre,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Medium,
                                fontFamily = SnProFamily,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    val airing = state.airing
                    if (airing != null) {
                        AiringProgressSection(
                            airing = airing,
                            language = state.language,
                            labelColor = textSecondary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    } else {
                        Text(
                            text = state.episodesLabel,
                            style = TextStyle(
                                fontFamily = SnProFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                shadow = TextOnImageShadow,
                            ),
                            color = textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = { onEditClick?.invoke() },
                    enabled = onEditClick != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.18f),
                        disabledContentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Edit",
                            fontWeight = FontWeight.Bold,
                            fontFamily = SnProFamily,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // Рамка поверх обложки, иначе картинка перекрывает её по краю.
        Box(
            Modifier
                .matchParentSize()
                .border(BorderStroke(1.dp, borderBrush), FullBleedCardShape)
        )
        if (state.isFavorite) {
            FavoriteCornerChip(modifier = Modifier.align(Alignment.TopStart))
        }
    }
}
