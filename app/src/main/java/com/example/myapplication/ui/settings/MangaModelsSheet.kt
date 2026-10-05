package com.example.myapplication.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.manga.translate.DownloadFormat
import com.example.myapplication.manga.translate.MangaTranslateSettings
import com.example.myapplication.manga.translate.MangaTranslateStrings
import com.example.myapplication.manga.translate.ModelsState
import com.example.myapplication.manga.translate.TranslationModels
import com.example.myapplication.manga.translate.mangaTranslateStrings
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.StatusSuccess
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private enum class Phase { Preparing, Downloading, Ready, Failed }

private enum class StepState { Waiting, Active, Done }

/** Сколько шторка показывает "Всё готово", прежде чем закрыться сама. */
private const val AUTO_CLOSE_MILLIS = 1500L

/**
 * Шторка загрузки моделей автоперевода: кольцо хода, два шага (поиск реплик, чтение текста),
 * размер, скорость и оставшееся время. Загрузка идёт в приложении, а не в шторке: закрытие шторки её
 * не прерывает, "Отменить загрузку" прерывает и выключает функцию.
 */
@Composable
fun MangaModelsSheet(language: AppLanguage, onDismiss: () -> Unit) {
    val settings: MangaTranslateSettings = koinInject()
    val ui by settings.ui.collectAsStateWithLifecycle()
    val strings = remember(language) { mangaTranslateStrings(language) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val models = ui.models

    val phase = when (models) {
        is ModelsState.Downloading -> Phase.Downloading
        ModelsState.Ready -> Phase.Ready
        is ModelsState.Failed -> Phase.Failed
        ModelsState.NotInstalled -> Phase.Preparing
    }
    val downloading = models as? ModelsState.Downloading

    // Ход при ошибке не хранится в состоянии: кольцо замирает на последнем показанном значении.
    var lastFraction by remember { mutableStateOf(0f) }
    if (downloading != null) lastFraction = downloading.fraction
    val fraction = when (phase) {
        Phase.Ready -> 1f
        Phase.Preparing -> 0f
        Phase.Downloading -> downloading?.fraction ?: 0f
        Phase.Failed -> lastFraction
    }

    // Закрываемся сами только если загрузку прошли у нас на глазах; открытая "по тапу" готовая шторка остаётся.
    var sawDownload by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == Phase.Downloading) sawDownload = true
        if (phase == Phase.Ready && sawDownload) {
            performHaptic(view, Haptic.Success)
            delay(AUTO_CLOSE_MILLIS)
            onDismiss()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                fadeIn(MotionTokens.easeEnter(durationMillis = 220)) togetherWith fadeOut(MotionTokens.easeExit())
            },
            label = "sheetHeader",
        ) { p ->
            val (title, body) = when (p) {
                Phase.Ready -> strings.doneTitle to strings.doneBody
                Phase.Failed -> strings.failedTitle to strings.failedBody
                else -> strings.sheetTitle to strings.sheetBody
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    fontFamily = SnProFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    fontFamily = SnProFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        DownloadRing(fraction = fraction, phase = phase, modifier = Modifier.padding(vertical = 6.dp))

        Text(
            text = statsLine(strings, language, phase, downloading),
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 18.dp),
        )

        val doneBytes = (fraction * TranslationModels.TOTAL_BYTES).toLong()
        val (detector, ocr) = TranslationModels.stepFractions(doneBytes)
        StepsCard(
            strings = strings,
            language = language,
            detector = stepState(detector, previousDone = true, phase = phase) to detector,
            ocr = stepState(ocr, previousDone = detector >= 1f, phase = phase) to ocr,
        )

        Crossfade(
            targetState = phase,
            animationSpec = MotionTokens.tweenStandard(),
            label = "sheetActions",
        ) { p ->
            Actions(
                phase = p,
                strings = strings,
                onHide = onDismiss,
                onCancel = { scope.launch { settings.cancelAndDisable(); onDismiss() } },
                onRetry = { settings.retryDownload() },
            )
        }
    }
}

private fun stepState(fraction: Float, previousDone: Boolean, phase: Phase): StepState = when {
    phase == Phase.Ready || fraction >= 1f -> StepState.Done
    phase == Phase.Downloading && previousDone -> StepState.Active
    else -> StepState.Waiting
}

private fun statsLine(
    strings: MangaTranslateStrings,
    language: AppLanguage,
    phase: Phase,
    downloading: ModelsState.Downloading?,
): String {
    val comma = language == AppLanguage.RU
    val total = DownloadFormat.megabytes(TranslationModels.TOTAL_BYTES, comma)
    return when (phase) {
        Phase.Preparing -> strings.sheetPreparing
        Phase.Ready -> "$total ${strings.megabyte}"
        Phase.Failed -> ""
        Phase.Downloading -> {
            val d = downloading ?: return strings.sheetPreparing
            buildList {
                add(strings.sizeOf(DownloadFormat.megabytes(d.doneBytes, comma), "$total ${strings.megabyte}"))
                if (d.bytesPerSecond > 0) add("${DownloadFormat.megabytes(d.bytesPerSecond, comma)} ${strings.perSecond}")
                d.etaSeconds?.let { add(strings.eta(it)) }
            }.joinToString("  ·  ")
        }
    }
}

/**
 * Кольцо хода. Дуга догоняет значение мягким твином, по ней бежит блик, пока идёт загрузка; в конце
 * кольцо становится зелёным, делает короткий "толчок", и внутри рисуется галочка. При ошибке дуга
 * замирает там, где остановилась.
 */
@Composable
private fun DownloadRing(fraction: Float, phase: Phase, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(MotionTokens.DurationEmphasizedMillis, easing = LinearOutSlowInEasing),
        label = "ringProgress",
    )
    val ringColor by animateColorAsState(
        targetValue = if (phase == Phase.Ready) StatusSuccess else BrandOrange,
        animationSpec = MotionTokens.tweenEmphasized(),
        label = "ringColor",
    )
    val glint = rememberInfiniteTransition(label = "glint").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart),
        label = "glintPos",
    )
    val bump = remember { Animatable(1f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == Phase.Ready) {
            bump.animateTo(1.07f, MotionTokens.springPressIn())
            bump.animateTo(1f, MotionTokens.springSnappy())
        } else {
            bump.snapTo(1f)
        }
    }
    LaunchedEffect(phase) {
        if (phase == Phase.Ready) {
            check.animateTo(1f, tween(420, delayMillis = 120, easing = MotionTokens.EaseEnter))
        } else {
            check.snapTo(0f)
        }
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Box(
        modifier = modifier
            .size(156.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(156.dp)
                .graphicsLayer {
                    scaleX = bump.value
                    scaleY = bump.value
                },
        ) {
            val stroke = 11.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            val sweep = 360f * progress
            if (sweep > 0.5f) {
                drawArc(ringColor, -90f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (phase == Phase.Downloading && sweep > 30f) {
                    val start = -90f + (sweep - 18f) * glint.value
                    drawArc(
                        Color.White.copy(alpha = 0.38f), start, 18f, false, topLeft, arc,
                        style = Stroke(stroke * 0.55f, cap = StrokeCap.Round),
                    )
                }
            }
            val c = check.value
            if (c > 0f) {
                val tick = Path().apply {
                    moveTo(size.width * 0.31f, size.height * 0.53f)
                    lineTo(size.width * 0.45f, size.height * 0.67f)
                    lineTo(size.width * 0.71f, size.height * 0.37f)
                }
                val measure = PathMeasure().apply { setPath(tick, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * c, part, true)
                drawPath(
                    part, StatusSuccess,
                    style = Stroke(stroke * 0.62f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Crossfade(targetState = phase, animationSpec = MotionTokens.tweenFast(), label = "ringCenter") { p ->
            when (p) {
                Phase.Preparing, Phase.Downloading -> Text(
                    text = "${(fraction * 100).roundToInt()}%",
                    fontFamily = SnProFamily,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Phase.Failed -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = BrandOrange,
                    modifier = Modifier.size(46.dp),
                )
                Phase.Ready -> Spacer(Modifier.size(1.dp)) // галочку рисует сам Canvas
            }
        }
    }
}

@Composable
private fun StepsCard(
    strings: MangaTranslateStrings,
    language: AppLanguage,
    detector: Pair<StepState, Float>,
    ocr: Pair<StepState, Float>,
) {
    val comma = language == AppLanguage.RU
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        StepRow(
            title = strings.stepDetector,
            size = "${DownloadFormat.megabytes(TranslationModels.DETECTOR_BYTES, comma)} ${strings.megabyte}",
            state = detector.first,
            fraction = detector.second,
        )
        StepRow(
            title = strings.stepOcr,
            size = "${DownloadFormat.megabytes(TranslationModels.TOTAL_BYTES - TranslationModels.DETECTOR_BYTES, comma)} ${strings.megabyte}",
            state = ocr.first,
            fraction = ocr.second,
        )
    }
}

@Composable
private fun StepRow(title: String, size: String, state: StepState, fraction: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(MotionTokens.tweenFast()) + scaleIn(MotionTokens.springSnappy(), initialScale = 0.6f)) togetherWith
                    fadeOut(MotionTokens.tweenFast())
            },
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
            label = "stepIndicator",
        ) { s ->
            when (s) {
                StepState.Done -> Box(
                    Modifier.size(22.dp).clip(CircleShape).background(StatusSuccess),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                StepState.Active -> CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(22.dp),
                    color = BrandOrange,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    strokeWidth = 2.5.dp,
                )
                StepState.Waiting -> Box(
                    Modifier
                        .size(22.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontFamily = SnProFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = size,
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Actions(
    phase: Phase,
    strings: MangaTranslateStrings,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (phase) {
            Phase.Preparing, Phase.Downloading -> {
                PrimaryButton(strings.hide, BrandOrange, onHide)
                Text(
                    text = strings.hideHint,
                    fontFamily = SnProFamily,
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onCancel) {
                    Text(strings.cancelDownload, fontFamily = SnProFamily, color = muted)
                }
            }
            Phase.Failed -> {
                PrimaryButton(strings.retry, BrandOrange, onRetry)
                TextButton(onClick = onHide) {
                    Text(strings.close, fontFamily = SnProFamily, color = muted)
                }
            }
            Phase.Ready -> PrimaryButton(strings.done, StatusSuccess, onHide)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(text, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
    }
}
