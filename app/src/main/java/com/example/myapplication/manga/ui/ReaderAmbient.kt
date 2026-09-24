package com.example.myapplication.manga.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Тон стекла, кромки и иконок дока ридера — по яркости страницы под ним.
 *
 * Идея та же, что была у амбиентного тона в плеере (в v3.3.5 он снят), но источник пикселей
 * другой: плеер снимал кадр с `SurfaceView` через `PixelCopy`, а у ридера сцена — обычная композиция, зато под доком уже лежит `layerBackdrop` со страницами
 * (он же и преломляется стеклом). Его [GraphicsLayer] и читаем — второго снимка городить не нужно.
 *
 * Для манги это не украшение: страницы почти сплошь белые, и зашитые белые иконки на
 * полупрозрачном стекле на них просто исчезали.
 */
@Immutable
data class ReaderAmbient(
    val tint: Color,
    val border: Color,
    val content: Color,
) {
    companion object {
        /** До первого снимка исходим из белой страницы — их в манге подавляющее большинство. */
        val Default = ReaderAmbient(
            tint = LightPageTint,
            border = LightPageBorder,
            content = LightPageContent,
        )
    }
}

/**
 * Снимок берётся не по таймеру, а по [revision] — в отличие от видео страница статична, и
 * опрашивать её раз в секунду значило бы читать один и тот же кадр. Меняется картинка под доком
 * ровно в трёх случаях: сменилась страница, проехала лента вебтуна, включился/выключился хром.
 * Всё это и складывается в [revision] на стороне вызова.
 *
 * [SampleDelayMs] гасит серию снимков во время прокрутки: пока лента едет, revision меняется
 * каждые 1/20 страницы, и без задержки каждый шаг стоил бы одного readback.
 *
 * @param active хром на экране — иначе тонировать нечего и читать слой незачем.
 */
@Composable
fun rememberReaderAmbient(
    layer: GraphicsLayer,
    active: Boolean,
    revision: Any,
): ReaderAmbient {
    // Держим решение, а не яркость: у порога с гистерезисом (см. [contentIsLight]) на границе
    // док иначе мигал бы туда-сюда на каждой странице.
    var pageIsLight by remember { mutableStateOf(true) }
    val buffer = remember { PixelBuffer() }

    LaunchedEffect(active, revision) {
        if (!active) return@LaunchedEffect
        delay(SampleDelayMs)
        val bitmap = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@LaunchedEffect
        val luminance = withContext(Dispatchers.Default) { bitmap.dockBandLuminance(buffer) }
            ?: return@LaunchedEffect
        pageIsLight = contentIsLight(luminance, pageIsLight)
    }

    // Переход намеренно длинный и одинаковый у тона и у иконок: рывок тут читается как дефект
    // отрисовки, а не как реакция на контент.
    val tint by animateColorAsState(
        if (pageIsLight) LightPageTint else DarkPageTint,
        tween(AmbientAnimMs),
        label = "readerDockTint",
    )
    val border by animateColorAsState(
        if (pageIsLight) LightPageBorder else DarkPageBorder,
        tween(AmbientAnimMs),
        label = "readerDockBorder",
    )
    val content by animateColorAsState(
        if (pageIsLight) LightPageContent else DarkPageContent,
        tween(AmbientAnimMs),
        label = "readerDockContent",
    )
    return ReaderAmbient(tint, border, content)
}

/**
 * Перцептивная яркость полосы кадра под доком: верх экрана, правая часть — там, где док и висит.
 * Левый верх занят стрелкой «назад» и названием главы, у них своя тёмная подложка-градиент.
 *
 * Пиксели читаются с прореживанием ([PixelStride]): по площади дока решение принимается одним
 * числом, и каждый второй ряд/столбец на него не влияет, зато вчетверо дешевле.
 */
private fun ImageBitmap.dockBandLuminance(buffer: PixelBuffer): Float? {
    val startX = (width * DockBandLeft).toInt().coerceIn(0, width - 1)
    val bandWidth = (width - startX).coerceAtLeast(1)
    val bandHeight = (height * DockBandBottom).toInt().coerceIn(1, height)
    val pixels = buffer.obtain(bandWidth * bandHeight)
    runCatching {
        readPixels(
            buffer = pixels,
            startX = startX,
            startY = 0,
            width = bandWidth,
            height = bandHeight,
        )
    }.getOrElse { return null }

    var sum = 0L
    var count = 0
    var y = 0
    while (y < bandHeight) {
        var x = 0
        val row = y * bandWidth
        while (x < bandWidth) {
            val p = pixels[row + x]
            // Перцептивная яркость целыми числами: те же коэффициенты 0.299/0.587/0.114 ×1000.
            sum += 299L * ((p shr 16) and 0xFF) +
                587L * ((p shr 8) and 0xFF) +
                114L * (p and 0xFF)
            count++
            x += PixelStride
        }
        y += PixelStride
    }
    if (count == 0) return null
    return sum / (count * 1000f * 255f)
}

/**
 * Переиспользуемый буфер под полосу пикселей: он порядка мегабайта, и выделять его заново на
 * каждый снимок означало бы регулярный мусор ровно на том экране, где памяти и так в обрез
 * (страницы манги — десятки мегабайт битмапов).
 */
private class PixelBuffer {
    private var array = IntArray(0)

    fun obtain(size: Int): IntArray {
        if (array.size < size) array = IntArray(size)
        return array
    }
}

/** Ниже — страница тёмная (нужен светлый док), выше — светлая; между — как было (антимигание). */
private fun contentIsLight(luminance: Float, current: Boolean): Boolean = when {
    luminance > PageLightAbove -> true
    luminance < PageDarkBelow -> false
    else -> current
}

/** Пауза перед снимком: гасит серию readback'ов, пока лента вебтуна ещё едет. */
private const val SampleDelayMs = 320L

/** Длинный переход — пользовательское требование: смена тона не должна читаться как рывок. */
private const val AmbientAnimMs = 700

private const val DockBandLeft = 0.45f
private const val DockBandBottom = 0.16f
private const val PixelStride = 2

private const val PageLightAbove = 0.58f
private const val PageDarkBelow = 0.42f

/*
 * Светлая страница — светлое плотное стекло и ТЁМНЫЕ иконки: на белом развороте тёмный знак
 * читается, светлый исчезает. Тёмная страница — прежний док: еле заметная белая подложка и белые
 * иконки. Те же две пары, что у доков плеера ([LIGHT_GLASS]/[DARK_GLASS] в AmbientDock).
 */
private val LightPageTint = Color(0xFFEFEFEF).copy(alpha = 0.55f)
private val LightPageBorder = Color.Black.copy(alpha = 0.14f)
private val LightPageContent = Color(0xFF101010)

private val DarkPageTint = Color.White.copy(alpha = 0.10f)
private val DarkPageBorder = Color.White.copy(alpha = 0.20f)
private val DarkPageContent = Color.White
