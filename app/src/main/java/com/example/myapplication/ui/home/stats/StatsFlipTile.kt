package com.example.myapplication.ui.home.stats

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.domain.stats.StatsCardExplanationState
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.phnem.vetro.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ==========================================
// StatsFlipTile — обычная плитка статистики, которая переворачивается «рубашкой»: на обороте —
// описание пункта и комментарий. Пока плитка не попала в зону видимости, её график «лежит на нуле»;
// как только показалась — вырастает (LocalStatsReveal: 0→1 один раз за жизнь плитки).
// ==========================================

/** Прогресс «вырастания» графика плитки, 0..1. Читать только в draw/graphicsLayer-лямбдах. */
internal val LocalStatsReveal = compositionLocalOf<() -> Float> { { 1f } }

/** Шрифт обратной стороны: книжная антиква вместо рубленого — оборот читается как подпись к графику. */
private val BackSerif = FontFamily(Font(R.font.noto_serif_variable, FontWeight.Normal))

@Composable
internal fun StatsFlipTile(
    isDark: Boolean,
    backTitle: String,
    description: String,
    modifier: Modifier = Modifier,
    /** Блок «комментарий» под описанием на обороте; null — только описание. */
    comment: (@Composable ColumnScope.() -> Unit)? = null,
    front: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val tileBg = if (isDark) OverlayThemeTokens.TileBackgroundDark else MaterialTheme.colorScheme.surfaceVariant
    val tileRim = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    val density = LocalDensity.current.density

    var flipped by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = MotionTokens.springSurface(),
        label = "statsFlip",
    )
    // Грань меняется в момент, когда плитка стоит ребром: ширина её уже нулевая, шва не видно.
    val showBack = angle > 90f

    val reveal = remember { Animatable(0f) }
    val view = LocalView.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(Unit) {
        // Опрос раз в ~80 мс до первого показа: проще и надёжнее подписки на скролл (шторка ещё и
        // выезжает снизу, а не только прокручивается). После показа цикл заканчивается.
        while (isActive && reveal.value == 0f) {
            val c = coords
            if (c != null && c.isAttached) {
                val b = c.boundsInWindow()
                if (b.top < view.rootView.height * 0.9f && b.bottom > 0f) {
                    reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
                }
            }
            delay(80)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .animateContentSize(MotionTokens.springSurface())
            .graphicsLayer {
                rotationY = angle
                cameraDistance = 14f * density
            }
            .clip(shape)
            .background(tileBg)
            .glassFill(isDark)
            .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
            .border(1.dp, tileRim, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                flipped = !flipped
            },
    ) {
        if (!showBack) {
            Column(Modifier.padding(16.dp)) {
                CompositionLocalProvider(LocalStatsReveal provides { reveal.value }) {
                    front()
                }
            }
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = (if (isDark) Color.White else Color.Black).copy(alpha = 0.28f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(15.dp),
            )
        } else {
            StatsTileBack(isDark, backTitle, description, comment)
        }
    }
}

@Composable
private fun StatsTileBack(
    isDark: Boolean,
    title: String,
    description: String,
    comment: (@Composable ColumnScope.() -> Unit)?,
) {
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            // Оборот нарисован зеркально вместе со всей плиткой — переворачиваем его обратно.
            .graphicsLayer { rotationY = 180f }
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(BrandOrange))
        Spacer(Modifier.height(12.dp))
        Text(
            text = title.uppercase(),
            style = OverlayThemeTokens.SectionLabel,
            color = muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = description,
            style = TextStyle(
                fontFamily = BackSerif,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.1.sp,
            ),
            color = ink,
        )
        if (comment != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background((if (isDark) Color.White else Color.Black).copy(alpha = 0.08f)))
            Spacer(Modifier.height(14.dp))
            comment()
        }
    }
}

/** Комментарий ИИ к карточке: текст, скелетон пока считается, либо мягкая заглушка. */
@Composable
internal fun StatsAiComment(
    state: StatsCardExplanationState,
    strings: UiStrings,
    isDark: Boolean,
    footerPhrase: String = "",
) {
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = strings.statsAiTitle.uppercase(),
            style = OverlayThemeTokens.SectionLabel,
            color = BrandOrange,
        )
        Spacer(Modifier.height(8.dp))
        val bodyStyle = TextStyle(fontFamily = BackSerif, fontSize = 15.sp, lineHeight = 23.sp)
        when (state) {
            is StatsCardExplanationState.Ready -> Text(state.text, style = bodyStyle, color = ink.copy(alpha = 0.92f))
            is StatsCardExplanationState.Loading -> CommentSkeleton(muted)
            is StatsCardExplanationState.Unavailable ->
                Text(strings.statsAiUnavailable, style = bodyStyle.copy(fontSize = 13.sp, lineHeight = 19.sp), color = muted)
            is StatsCardExplanationState.InsufficientData ->
                Text(strings.statsAiInsufficient, style = bodyStyle.copy(fontSize = 13.sp, lineHeight = 19.sp), color = muted)
        }
        if (footerPhrase.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = footerPhrase,
                style = TextStyle(fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 17.sp),
                color = muted,
            )
        }
    }
}

/** Короткий шиммер вместо спиннера: текст обычно уже готов из кэша, так что виден он редко. */
@Composable
private fun CommentSkeleton(base: Color) {
    val transition = rememberInfiniteTransition(label = "commentSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "commentSkeletonAlpha",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1f, 0.92f, 0.6f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(base.copy(alpha = alpha)),
            )
        }
    }
}

/** Слабое появление не-графического содержимого (цифры, подписи): проявляется и чуть всплывает. */
internal fun Modifier.statsRevealFade(reveal: () -> Float): Modifier = graphicsLayer {
    val p = reveal().coerceIn(0f, 1f)
    alpha = p
    translationY = (1f - p) * 10.dp.toPx()
}
