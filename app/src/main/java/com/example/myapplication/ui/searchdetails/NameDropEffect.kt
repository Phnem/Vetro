package com.example.myapplication.ui.searchdetails

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin

// Порт NameDrop-перехода из iOS (референс — WebGL-шейдер из NameDrop_WebGL_Shader): экран сначала
// «втягивается» к верхнему краю и мягко мутнеет (фаза 1), затем от верхнего края идёт оптическая
// волна-мениск: линза смещает пиксели вдоль фронта, красный и синий каналы расходятся, фронт
// мутнеет сильнее всего и подсвечен переливающимся розово-голубым (фаза 2). Пик размытия приходится
// на стык фаз — в этот момент содержимое экрана подменяется, и волна проявляет уже новое.
//
// На Android 13+ это AGSL RuntimeShader поверх всего слоя. На 12 и ниже шейдера нет: остаётся
// «втягивание» с размытием (31+) или одним масштабом.

internal object NameDropTiming {
    const val PHASE1_MS = 750f
    const val PHASE2_MS = 1200f
    const val TOTAL_MS = PHASE1_MS + PHASE2_MS

    /** Момент подмены содержимого: размытие на пике, глаз не успевает поймать шов. */
    const val SWAP_MS = PHASE1_MS + 10f

    const val MAX_RADIUS = 1.24f
    const val WAVE_WIDTH = 0.27f
}

/** Часы перехода: одно значение — миллисекунды от старта; всё остальное считается из него. */
@Stable
class NameDropState {
    val time = Animatable(0f)

    var running by mutableStateOf(false)
        private set

    /** 0..1: растворение кнопки «Добавить» (по HTML-эталону — с 640-й по 940-ю мс). */
    val dissolve: Float
        get() = if (!running) 0f else ((time.value - 640f) / 300f).coerceIn(0f, 1f)

    /** Проигрывает переход; [onPeak] вызывается один раз на пике размытия, до начала волны. */
    suspend fun play(onPeak: () -> Unit) {
        running = true
        try {
            time.snapTo(0f)
            time.animateTo(NameDropTiming.SWAP_MS, tween(NameDropTiming.SWAP_MS.toInt(), easing = LinearEasing))
            onPeak()
            // Часы стоят, пока новый экран не соберётся и не нарисуется: иначе тяжёлый первый кадр
            // (главная после входа) съедал почти всю волну, и под ней нечего было проявлять.
            repeat(3) { withFrameNanos { } }
            val rest = NameDropTiming.TOTAL_MS - NameDropTiming.SWAP_MS
            time.animateTo(NameDropTiming.TOTAL_MS, tween(rest.toInt(), easing = LinearEasing))
        } finally {
            running = false
        }
    }
}

internal class NameDropFrame(
    val phase1: Float,
    val phase2Raw: Float,
    val waveRadius: Float,
    /** «Втягивание» экрана: нарастает в фазе 1 и гаснет в первой трети фазы 2. */
    val pull: Float,
)

private fun easeInOutCubic(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - Math.pow((-2.0 * x + 2.0), 3.0).toFloat() / 2f

private fun easeOutQuint(x: Float): Float = 1f - Math.pow((1.0 - x), 5.0).toFloat()

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun nameDropFrame(timeMs: Float): NameDropFrame {
    val rawP1 = (timeMs / NameDropTiming.PHASE1_MS).coerceIn(0f, 1f)
    val phase1 = easeInOutCubic(rawP1)
    val rawP2 = if (timeMs <= NameDropTiming.PHASE1_MS) 0f
    else ((timeMs - NameDropTiming.PHASE1_MS) / NameDropTiming.PHASE2_MS).coerceIn(0f, 1f)
    val phase2Eased = if (rawP2 == 0f) 0f else 0.72f * easeOutQuint(rawP2) + 0.28f * rawP2
    val pull = sin(phase1 * PI.toFloat() / 2f) * (1f - smoothstep(0f, 0.38f, rawP2))
    return NameDropFrame(phase1, rawP2, phase2Eased * NameDropTiming.MAX_RADIUS, pull)
}

/** Накладывает на слой NameDrop-эффект, пока [state] проигрывается; вне перехода слой не трогает. */
@Composable
fun Modifier.nameDropEffect(state: NameDropState): Modifier {
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) createShader() else null }
    return this.graphicsLayer {
        if (!state.running) {
            renderEffect = null
            scaleX = 1f
            scaleY = 1f
            return@graphicsLayer
        }
        val f = nameDropFrame(state.time.value)
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            renderEffect = shader.effect(size.width, size.height, f)
        } else {
            val s = 1f - 0.026f * f.pull
            scaleX = s
            scaleY = s
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val r = f.pull * 14f
                renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createShader(): NameDropShader = NameDropShader()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class NameDropShader {
    private val shader = RuntimeShader(NAME_DROP_AGSL)

    fun effect(width: Float, height: Float, f: NameDropFrame): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("origin", 0.5f, 0.025f)
        shader.setFloatUniform("phase1", f.phase1)
        shader.setFloatUniform("phase2", f.phase2Raw)
        shader.setFloatUniform("waveRadius", f.waveRadius)
        shader.setFloatUniform("waveWidth", NameDropTiming.WAVE_WIDTH)
        shader.setFloatUniform("lensStrength", 1f)
        shader.setFloatUniform("blurPx", 30f * (width / 1080f))
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

private const val NAME_DROP_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float2 origin;
uniform float phase1;
uniform float phase2;
uniform float waveRadius;
uniform float waveWidth;
uniform float lensStrength;
uniform float blurPx;

float3 fetch(float2 uv) {
    return content.eval(clamp(uv * resolution, float2(0.5), resolution - 0.5)).rgb;
}

// Один проход вместо пирамиды FBO: 12 отсчётов по спирали с шумовым поворотом на пиксель.
float3 blurred(float2 uv, float b) {
    if (b <= 0.002) return fetch(uv);
    float2 p = uv * resolution;
    float rad = b * blurPx;
    float jitter = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) * 6.2831853;
    float3 acc = fetch(uv);
    float wsum = 1.0;
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float r = sqrt((fi + 0.5) / 12.0);
        float a = fi * 2.3999632 + jitter;
        float2 o = float2(cos(a), sin(a)) * r * rad;
        float w = 1.0 - 0.4 * r;
        acc += content.eval(clamp(p + o, float2(0.5), resolution - 0.5)).rgb * w;
        wsum += w;
    }
    return acc / wsum;
}

half4 main(float2 fragCoord) {
    float aspect = resolution.x / resolution.y;
    float2 uiUV = fragCoord / resolution;

    float2 delta = float2((uiUV.x - origin.x) * aspect, uiUV.y - origin.y);
    float dist = length(delta);
    float2 dir = dist > 0.0001 ? delta / dist : float2(0.0, 1.0);

    // Фаза 1: магнитное сжатие 1.0 -> 0.974 и мягкое размытие.
    float pullEnvelope = sin(phase1 * 1.5707963) * (1.0 - smoothstep(0.0, 0.38, phase2));
    float targetScale = mix(1.0, 0.974, pullEnvelope);
    float2 pivot = mix(float2(0.5, 0.44), origin, 0.24);
    float2 uv = (uiUV - pivot) / targetScale + pivot;

    float originProximity = exp(-dist * 2.5);
    uv -= dir * (0.008 * pullEnvelope * originProximity * lensStrength);

    // Фаза 2: гауссова линза-мениск вдоль фронта волны.
    float waveEnvelope = smoothstep(0.0, 0.08, phase2) * (1.0 - smoothstep(0.75, 1.0, phase2));
    float signedDist = dist - waveRadius;
    float normDist = signedDist / waveWidth;

    float bell = exp(-normDist * normDist);
    float lensGradient = -2.0 * normDist * bell;

    float trailNorm = (signedDist + waveWidth * 0.95) / (waveWidth * 1.35);
    float trailBell = exp(-trailNorm * trailNorm) * (1.0 - smoothstep(-0.08, 0.05, signedDist));
    float trailGradient = -2.0 * trailNorm * trailBell * 0.24;

    float displacement = (lensGradient * 0.028 + trailGradient * 0.016) * waveEnvelope * lensStrength;
    float dispersion = (bell * 0.0055 + trailBell * 0.002) * waveEnvelope * lensStrength;

    float2 uvBlue = uv - dir * (displacement + dispersion * 1.2);
    float2 uvGreen = uv - dir * displacement;
    float2 uvRed = uv - dir * (displacement - dispersion * 1.25);

    float radialSoftness = mix(0.55, 1.15, originProximity);
    float phase1Blur = pullEnvelope * 0.46 * radialSoftness * lensStrength;
    float crestBlur = (bell * 0.52 + trailBell * 0.18) * waveEnvelope * lensStrength;
    float totalBlur = clamp(phase1Blur + crestBlur, 0.0, 0.86);

    // Зелёный канал несёт размытие; красный и синий — резкие отсчёты со сдвигом, растворяющиеся в нём.
    float3 base = blurred(uvGreen, totalBlur);
    float mixK = clamp(totalBlur * 3.0, 0.0, 1.0);
    float colR = mix(fetch(uvRed).r, base.r, mixK);
    float colB = mix(fetch(uvBlue).b, base.b, mixK);
    float3 color = float3(colR, base.g, colB);

    // Свечение NameDrop: аура у верхнего края + переливающийся гребень волны.
    float topEdgeGlow = exp(-uiUV.y * 9.0) * exp(-pow((uiUV.x - 0.5) * 2.1, 2.0));
    float magneticAura = pullEnvelope * 0.68 + (1.0 - smoothstep(0.0, 0.26, phase2)) * step(0.001, phase2) * 0.42;
    float3 pinkWhiteCore = mix(float3(0.98, 0.44, 0.78), float3(1.0, 0.96, 0.99), clamp(topEdgeGlow * 1.35, 0.0, 1.0));
    color += pinkWhiteCore * topEdgeGlow * magneticAura * 0.72;

    float crestSheen = (bell + trailBell * 0.35) * waveEnvelope;
    float3 coolToMagenta = mix(float3(0.38, 0.78, 1.0), float3(0.92, 0.46, 0.96), smoothstep(-0.75, 0.15, normDist));
    float3 crestTint = mix(coolToMagenta, float3(1.0, 0.93, 0.97), smoothstep(0.15, 0.85, normDist));
    color += crestTint * crestSheen * 0.21 * lensStrength;

    // Кромка экрана подсвечивается, когда по ней проходит волна.
    float edgeDist = min(min(fragCoord.x, resolution.x - fragCoord.x), min(fragCoord.y, resolution.y - fragCoord.y)) / resolution.x;
    float bezelBand = 1.0 - smoothstep(0.0, 0.018, edgeDist);
    float topBezelBoost = exp(-uiUV.y * 5.5) * pullEnvelope * 0.92;
    float waveBezelHit = bell * waveEnvelope * 1.1;
    float rimGlow = clamp(topBezelBoost + waveBezelHit, 0.0, 1.0) * bezelBand;
    float3 rimColor = mix(float3(0.96, 0.54, 0.85), float3(0.68, 0.88, 1.0), uiUV.y);
    color += rimColor * rimGlow * 1.3;

    return half4(half3(clamp(color, 0.0, 1.0)), 1.0);
}
"""
