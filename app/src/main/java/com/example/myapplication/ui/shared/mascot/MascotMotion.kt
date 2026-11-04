package com.example.myapplication.ui.shared.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Время и кривые анимаций маскота. Перенесено один в один из одобренного прототипа
 * (.scratch/empty-states-mascot/mascot.html): сцены — чистые функции от времени цикла, без состояния,
 * поэтому кадр в Compose совпадает с кадром из ролика.
 */

internal const val TAU = (2 * PI).toFloat()

internal fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

internal object Ease {
    val io: (Float) -> Float = { x -> if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2 }
    val out: (Float) -> Float = { x -> 1 - (1 - x).pow(3) }
    val inn: (Float) -> Float = { x -> x * x * x }

    /** Перелёт с возвратом (easeOutBack, c1 = 1.9). */
    val back: (Float) -> Float = { x ->
        val c1 = 1.9f
        val c3 = c1 + 1
        1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
    }

    /** Затухающая пружина: перелёт и пара колебаний, к x = 1 ровно в цели. */
    val spring: (Float) -> Float = { x ->
        fun f(u: Float) = 1 - exp(-6.5f * u) * cos(11f * u)
        f(x) / f(1f)
    }
}

/** Ключ анимации: значение [v] к моменту [t]; кривая [e] — у ключа, к которому идём. */
internal class Key(val t: Float, val v: Float, val e: (Float) -> Float = Ease.io)

internal fun k(t: Float, v: Float, e: (Float) -> Float = Ease.io) = Key(t, v, e)

internal fun kf(t: Float, vararg keys: Key): Float {
    if (t <= keys[0].t) return keys[0].v
    for (i in 1 until keys.size) {
        val b = keys[i]
        if (t <= b.t) {
            val a = keys[i - 1]
            val x = if (b.t == a.t) 1f else (t - a.t) / (b.t - a.t)
            return a.v + (b.v - a.v) * b.e(x)
        }
    }
    return keys.last().v
}

/** Огибающая «включено» между [a] и [b] с плавными краями [fade]. */
internal fun env(t: Float, a: Float, b: Float, fade: Float = 0.3f): Float =
    clamp01((t - a) / fade) * clamp01((b - t) / fade)

/** Пружинное появление 0 → 1 с перелётом, начиная с [t0]. */
internal fun pop(t: Float, t0: Float, dur: Float = 0.45f): Float = when {
    t <= t0 -> 0f
    t >= t0 + dur -> 1f
    else -> Ease.back((t - t0) / dur)
}

/** Моргание: в моменты [times] глаза схлопываются за ~0.09 с туда и обратно. */
internal fun blink(t: Float, vararg times: Float): Float {
    val d = 0.09f
    var s = 1f
    for (tb in times) {
        val q = abs(t - tb)
        if (q < d) s = min(s, 0.08f + 0.92f * (q / d))
    }
    return s
}

/** Дыхание с периодом ~2 с, кратным длине цикла [loop] (цикл сходится без шва). */
internal class Breath(t: Float, loop: Float) {
    private val b = sin(TAU * max(1, (loop / 2f).roundToInt()) * t / loop)
    val sy = 1 + 0.013f * b
    val sx = 1 - 0.006f * b
}

/** Остаток, всегда неотрицательный (как ((x % m) + m) % m в прототипе). */
internal fun wrap(x: Float, m: Float): Float = ((x % m) + m) % m

/**
 * Поза маскота. Корпус — псевдо-3D куб: [yaw] (поворот вокруг вертикали, + — лицом вправо),
 * [pitch] (+ — кивок вперёд), [rot] (крен корпуса), [lean] (наклон всего тела от ступней).
 * Руки — гибкие «шланги»: [armL]/[armR] — куда смотрит кисть от плеча, [bendL]/[bendR] — изгиб в «локте»
 * (> 0 — кисть наружу/вверх), [frontR] — правая рука поверх корпуса (у подбородка).
 */
internal data class MascotPose(
    val x: Float = 0f,
    val jump: Float = 0f,
    val rot: Float = 0f,
    val y: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val lean: Float = 0f,
    val armL: Float = 7f,
    val armR: Float = -7f,
    val bendL: Float = 10f,
    val bendR: Float = 10f,
    val legL: Float = 0f,
    val legR: Float = 0f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val eyeS: Float = 1f,
    val eyeSY: Float = 1f,
    val happy: Float = 0f,
    val smile: Float = 1f,
    val sad: Float = 0f,
    val o: Float = 0f,
    val frontR: Float = 0f,
    /** Запаздывание кончиков рук (градусы), считается из скорости позы. */
    val lagL: Float = 0f,
    val lagR: Float = 0f,
)

private const val LAG_GAIN = 0.75f

/** Кончик руки отстаёт от поворота плеча и корпуса; при прыжке руки «болтаются» за телом. */
internal fun MascotPose.withLag(prev: MascotPose): MascotPose {
    val dy = (jump + y) - (prev.jump + prev.y)
    return copy(
        lagL = LAG_GAIN * ((armL - prev.armL) + (rot - prev.rot)) - 1.6f * dy,
        lagR = LAG_GAIN * ((armR - prev.armR) + (rot - prev.rot)) + 1.6f * dy,
    )
}
