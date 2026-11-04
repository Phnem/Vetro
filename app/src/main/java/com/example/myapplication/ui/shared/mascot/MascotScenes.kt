package com.example.myapplication.ui.shared.mascot

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Сцены пустых состояний (по раскадровке пользователя). Сцена — чистая функция от времени цикла:
 * поза маскота + декор вокруг. Холст сцены — 300 × 300 единиц, маскот стоит в (150 + offsetX, 262) в масштабе 0.72.
 */
enum class MascotScene {
    /** Ошибка, источник не ответил: чешет затылок, над головой «?». */
    Question,

    /** Ждём данных / новых серий: рука у подбородка, облачко с бегущими точками. */
    Think,

    /** Ничего не выходит, в этот день пусто: пожимает плечами. */
    Shrug,

    /** Поиск без результатов: водит лупой, поднимает её — пусто. */
    Search,

    /** Пустая папка / полка / загрузки: коробка открывается, внутри ничего. */
    Empty,

    /** Пустая коллекция при первом запуске: машет, сердечко. */
    Hello,
}

internal abstract class SceneSpec(val loop: Float, val blob: Int, val offsetX: Float = 0f, val still: Float) {
    abstract fun pose(t: Float): MascotPose
    open fun DrawScope.under(t: Float, c: MascotPalette) {}
    open fun DrawScope.over(t: Float, c: MascotPalette) {}

    /** Предмет в правой руке (система кисти). */
    open fun DrawScope.handR(t: Float, c: MascotPalette) {}
}

/** Короткие «искры» вокруг эмоции: [lines] — x1, y1, x2, y2 подряд; вспыхивают масштабом от точки (ox, oy). */
private fun DrawScope.ticks(lines: FloatArray, op: Float, s: Float, ox: Float, oy: Float) {
    if (op <= 0.001f) return
    val color = MascotOrange.copy(alpha = op)
    at(ox, oy, sx = s) {
        at(-ox, -oy) {
            for (i in lines.indices step 4) line(color, lines[i], lines[i + 1], lines[i + 2], lines[i + 3], 4f)
        }
    }
}

private val dashed = Stroke(
    width = 4f,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
)

internal fun MascotScene.spec(): SceneSpec = when (this) {
    MascotScene.Question -> QuestionScene
    MascotScene.Think -> ThinkScene
    MascotScene.Shrug -> ShrugScene
    MascotScene.Search -> SearchScene
    MascotScene.Empty -> EmptyBoxScene
    MascotScene.Hello -> HelloScene
}

/* 1 · «Вопрос» */
private object QuestionScene : SceneSpec(loop = 6f, blob = 1, still = 2.0f) {
    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val up = kf(t, k(0.6f, 0f), k(1.1f, 1f, Ease.back), k(3.2f, 1f), k(3.8f, 0f))
        val scratch = env(t, 1.15f, 2.4f, 0.15f) * sin(TAU * 5 * t)
        val hmm = kf(t, k(2.3f, 0f), k(2.6f, 1f, Ease.out), k(3.2f, 1f), k(3.7f, 0f))
        val look = kf(t, k(4.3f, 0f), k(4.6f, 1f, Ease.out), k(5.2f, 1f), k(5.6f, 0f))
        return MascotPose(
            yaw = 20 * up - 26 * hmm - 16 * look + 4 * sin(TAU * t / loop),
            pitch = -10 * up + 8 * hmm,
            lean = -3 * up + 4 * hmm,
            rot = -4 * up + 7 * hmm + 0.8f * scratch,
            armR = -7 - 150 * up + 3 * scratch,
            bendR = 10 + 55 * up + 24 * scratch,
            armL = 7 + 3 * up,
            lookX = 3 * up - 8 * hmm - 5 * look,
            lookY = -4 * up + 2 * hmm,
            eyeSY = blink(t, 0.3f, 4.95f) * (1 - 0.18f * hmm),
            sy = b.sy, sx = b.sx, y = -2 * up,
        )
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val q = pop(t, 1.0f, 0.5f) * kf(t, k(3.5f, 1f), k(3.9f, 0f, Ease.inn))
        val wobble = if (t > 1f) 7 * sin(TAU * 1.5f * (t - 1)) * exp(-1.6f * max(0f, t - 1.3f)) else 0f
        val qRot = -20 * (1 - pop(t, 1.0f, 0.5f)) + wobble
        if (q > 0.001f) {
            val color = MascotOrange.copy(alpha = clamp01(q * 1.4f))
            at(252f, 70 - 8 * q, rot = qRot, sx = q) {
                drawPath(MascotPaths.question, color, style = stroke(9f))
                drawCircle(color, 5.5f, Offset(3f, 31f))
            }
        }
        val tk = pop(t, 1.05f, 0.35f) * kf(t, k(1.5f, 1f), k(1.8f, 0f))
        ticks(floatArrayOf(70f, 84f, 82f, 92f, 92f, 62f, 96f, 76f, 112f, 52f, 112f, 66f), clamp01(tk), 0.6f + 0.4f * tk, 100f, 90f)
    }
}

/* 2 · «Думает» */
private object ThinkScene : SceneSpec(loop = 6f, blob = 2, still = 2.5f) {
    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val kk = kf(t, k(0.5f, 0f), k(1.1f, 1f), k(4.4f, 1f), k(5.0f, 0f))
        val sway = env(t, 1.1f, 4.4f, 0.4f) * sin(TAU * 2 * t / loop * 3)
        val tap = env(t, 1.3f, 4.3f, 0.2f) * max(0f, sin(TAU * 1.5f * t))
        return MascotPose(
            yaw = 24 * kk + 3 * sway + 4 * sin(TAU * t / loop) * (1 - kk),
            pitch = -12 * kk,
            lean = 4 * kk,
            rot = 9 * kk + 1.2f * sway,
            lookX = 7 * kk, lookY = -6 * kk,
            o = kk, smile = 1 - kk,
            armR = -7 + 72 * kk + 5 * tap, bendR = 10 - 80 * kk, armL = 7 - 2 * kk,
            frontR = kf(t, k(0.75f, 0f), k(1.0f, 1f), k(4.4f, 1f), k(4.65f, 0f)),
            eyeSY = blink(t, 0.25f, 2.7f, 5.6f),
            sy = b.sy, sx = b.sx,
        )
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val out = kf(t, k(4.35f, 1f), k(4.5f, 1.12f, Ease.out), k(4.75f, 0f, Ease.inn))
        val s1 = pop(t, 0.95f, 0.3f) * out
        val s2 = pop(t, 1.1f, 0.3f) * out
        val cl = pop(t, 1.25f, 0.5f) * out
        val bob = 2 * sin(TAU * 3 * t / loop)
        fun dot(i: Int): Float {
            val ph = wrap(t - 1.6f - i * 0.25f, 1.1f) / 1.1f
            return 0.25f + 0.75f * sin(PI.toFloat() * clamp01(ph * 1.6f)).pow(2)
        }
        if (s2 > 0.001f) {
            val center = Offset(193f, 93f + bob * 0.6f)
            drawCircle(c.bubble, 3.6f * s2, center)
            drawCircle(MascotOrange, 3.6f * s2, center, style = Stroke(2.8f))
        }
        if (s1 > 0.001f) {
            val center = Offset(201f, 80f + bob * 0.8f)
            drawCircle(c.bubble, 6.4f * s1, center)
            drawCircle(MascotOrange, 6.4f * s1, center, style = Stroke(3.2f))
        }
        if (cl > 0.001f) {
            at(222f, 56f + bob, sx = 0.8f * cl) {
                drawPath(MascotPaths.bubble, c.bubble)
                drawPath(MascotPaths.bubble, MascotOrange, style = stroke(5f))
                for (i in 0..2) {
                    val d = dot(i)
                    drawCircle(MascotOrange.copy(alpha = d), 5.5f, Offset(-22f + 22f * i, -12 - 3 * max(0f, d - 0.6f)))
                }
            }
        }
    }
}

/* 3 · «Пожимает плечами» */
private object ShrugScene : SceneSpec(loop = 6f, blob = 0, still = 2.0f) {
    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val ant = kf(t, k(0.6f, 0f), k(0.85f, 1f, Ease.out), k(1.0f, 0f, Ease.inn))
        val s = kf(t, k(0.85f, 0f), k(1.3f, 1f, Ease.spring), k(3.2f, 1f), k(3.9f, 0f))
        val dip = kf(t, k(2.4f, 0f), k(2.6f, 1f, Ease.out), k(2.95f, 0f, Ease.back))
        val look = kf(t, k(4.6f, 0f), k(4.9f, 1f, Ease.out), k(5.2f, 1f), k(5.5f, -1f), k(5.8f, 0f))
        return MascotPose(
            yaw = 14 * look - 6 * dip + 4 * sin(TAU * t / loop),
            pitch = 10 * ant - 5 * s + 12 * dip,
            lean = -2 * s,
            y = 4 * ant + 3 * s - 3 * dip,
            sy = b.sy - 0.05f * ant - 0.03f * s + 0.03f * dip,
            sx = b.sx + 0.03f * ant + 0.02f * s,
            armL = 7 - 5 * ant + 67 * s - 14 * dip,
            armR = -7 + 5 * ant - 67 * s + 14 * dip,
            bendL = 10 + 55 * s - 35 * dip,
            bendR = 10 + 55 * s - 35 * dip,
            rot = -3 * s, lookY = 2 * s, lookX = 6 * look,
            eyeSY = blink(t, 0.3f, 4.2f) * (1 - 0.12f * s),
        )
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val tk = pop(t, 1.0f, 0.35f) * kf(t, k(1.7f, 1f), k(2.0f, 0f))
        val tk2 = pop(t, 2.55f, 0.3f) * kf(t, k(3.0f, 1f), k(3.3f, 0f))
        val m = max(tk, tk2)
        ticks(floatArrayOf(246f, 76f, 254f, 90f, 260f, 96f, 276f, 104f, 248f, 114f, 264f, 116f), clamp01(m), 0.6f + 0.4f * m, 246f, 110f)
        ticks(floatArrayOf(54f, 76f, 46f, 90f, 40f, 96f, 24f, 104f, 52f, 114f, 36f, 116f), clamp01(m), 0.6f + 0.4f * m, 54f, 110f)
    }
}

/* 4 · «Ищет» */
private object SearchScene : SceneSpec(loop = 8f, blob = 1, offsetX = -24f, still = 1.0f) {
    private fun sweep(t: Float) = sin(TAU * 5 * t / loop) // 5 взмахов на цикл: шов в t = 0 и t = L совпадает
    private fun se(t: Float) = kf(t, k(0f, 1f), k(3.3f, 1f), k(3.7f, 0f), k(6.4f, 0f), k(7.0f, 1f), k(8f, 1f))

    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val sw = sweep(t)
        val se = se(t)
        val lift = kf(t, k(3.5f, 0f), k(4.0f, 1f, Ease.back), k(5.3f, 1f), k(5.9f, 0f))
        val face = kf(t, k(4.6f, 0f), k(4.9f, 1f, Ease.out), k(5.7f, 1f), k(6.0f, 0f))
        val sh = kf(t, k(5.3f, 0f), k(5.6f, 1f, Ease.back), k(6.2f, 1f), k(6.6f, 0f))
        val base = 1 - lift
        return MascotPose(
            yaw = (26 + 9 * sw * se) * base + 22 * lift * (1 - face) - 10 * sh,
            pitch = 16 * base - 8 * lift + 6 * sh,
            lean = (3 + 2 * sw * se) * base - 2 * sh,
            rot = 3 * base * (1 - sh) + 1.5f * sw * se - 4 * sh,
            armR = -46 * base - 118 * lift + 14 * sw * se,
            armL = 14 * base + 8 * lift + 44 * sh,
            bendR = 10 + 22 * base + 14 * sw * se + 25 * lift,
            bendL = 10 + 35 * sh,
            lookX = (6 + 3 * sw * se) * base + 9 * lift * (1 - face),
            lookY = 8 * base - 3 * lift * (1 - face),
            eyeS = 1 + 0.16f * pop(t, 3.75f, 0.35f) * kf(t, k(4.3f, 1f), k(4.7f, 0f)),
            o = face * (1 - sh), smile = 1 - face * (1 - sh),
            legL = -3 * base + 2.5f * max(0f, sw) * se,
            legR = 4 * base - 2.5f * max(0f, -sw) * se,
            x = 5 * sw * se, y = 2 * sh,
            eyeSY = blink(t, 4.45f, 7.4f),
            sy = b.sy, sx = b.sx,
        )
    }

    override fun DrawScope.under(t: Float, c: MascotPalette) {
        val se = se(t)
        fun coin(i: Int, cx: Float, cy: Float, rx: Float, ry: Float, op: Float) {
            val ph = (sin(TAU * 5 * t / loop - i * 1.1f) + 1) / 2
            ellipse(MascotOrange.copy(alpha = clamp01(op * (0.55f + 0.45f * ph * se + 0.45f * (1 - se)))), cx, cy, rx, ry)
        }
        coin(0, 196f, 270f, 13.6f, 4.4f, 1f)
        coin(1, 230f, 263f, 10.4f, 3.6f, 1f)
        coin(2, 260f, 273f, 8f, 3f, 1f)
        coin(3, 172f, 265f, 6.4f, 2.4f, 0.7f)
    }

    override fun DrawScope.handR(t: Float, c: MascotPalette) {
        drawMagnifier(c, glint = 0.4f + 0.6f * sin(TAU * 4 * t / loop).pow(2))
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val tk = pop(t, 3.85f, 0.3f) * kf(t, k(4.4f, 1f), k(4.7f, 0f))
        ticks(floatArrayOf(240f, 62f, 246f, 76f, 262f, 56f, 262f, 70f, 284f, 64f, 278f, 76f), clamp01(tk), 0.6f + 0.4f * tk, 262f, 76f)
    }
}

/* 5 · «Пусто…» */
private object EmptyBoxScene : SceneSpec(loop = 8f, blob = 2, offsetX = -30f, still = 4.6f) {
    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val lean = kf(t, k(0.8f, 0f), k(1.4f, 1f), k(3.2f, 1f), k(3.6f, 0f))
        val peek = kf(t, k(1.9f, 0f), k(2.5f, 1f), k(3.2f, 1f), k(3.6f, 0f))
        val hop = kf(t, k(1.6f, 0f), k(1.78f, 1f, Ease.out), k(2.0f, 0f, Ease.inn))
        val sad = kf(t, k(3.3f, 0f), k(3.9f, 1f), k(5.9f, 1f), k(6.6f, 0f))
        val sigh = env(t, 4.1f, 5.6f, 0.5f) * sin(PI.toFloat() * clamp01((t - 4.1f) / 1.5f))
        val oo = kf(t, k(1.0f, 0f), k(1.3f, 1f), k(2.8f, 1f), k(3.1f, 0f))
        return MascotPose(
            yaw = 26 * lean + 10 * peek - 16 * sad + 4 * sin(TAU * t / loop),
            pitch = 14 * peek + 20 * sad - 6 * hop,
            lean = 4 * lean + 4 * peek - 2 * sad,
            x = 4 * lean + 6 * peek, jump = -10 * hop,
            rot = 6 * lean + 5 * peek,
            lookX = 6 * (1 - sad) + 3 * peek, lookY = 4 + 5 * peek + 5 * sad,
            eyeS = 1 + 0.12f * kf(t, k(1.5f, 0f), k(1.75f, 1f, Ease.back), k(2.6f, 1f), k(2.9f, 0f)) - 0.08f * sad,
            o = oo, smile = (1 - sad) * (1 - oo), sad = sad,
            y = 6 * sad - 2 * sigh,
            sy = b.sy - 0.05f * sad + 0.03f * sigh, sx = b.sx + 0.03f * sad,
            armL = 7 - 5 * sad + 6 * hop, armR = -7 + 5 * sad - 6 * hop,
            bendL = 10 - 26 * sad, bendR = 10 - 26 * sad,
            eyeSY = blink(t, 0.4f, 2.98f, 3.2f, 7.2f),
        )
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val wob = env(t, 0.8f, 1.6f, 0.1f) * 4 * sin(TAU * 6 * t) * (1 - clamp01((t - 0.8f) / 0.8f) * 0.5f)
        val lid = kf(t, k(1.55f, 1f), k(1.75f, -1.3f, Ease.out), k(1.95f, -0.95f), k(2.1f, -1f), k(5.9f, -1f), k(6.4f, 1f, Ease.back))
        val sp = pop(t, 1.6f, 0.3f) * kf(t, k(1.9f, 1f), k(2.3f, 0f))
        val dust = clamp01((t - 1.58f) / 0.6f)
        val dustOp = if (t > 1.58f && t < 2.2f) 0.9f * (1 - dust) else 0f
        val gloom = clamp01((t - 3.7f) / 0.5f) * kf(t, k(5.9f, 1f), k(6.4f, 0f))
        val gy = -12 * (1 - Ease.out(clamp01((t - 3.7f) / 0.6f)))
        at(244f, 266f, sx = 0.55f) {
            at(0f, 0f, rot = wob) {
                drawPath(MascotPaths.boxBack, c.paperDeep)
                drawPath(MascotPaths.boxBack, MascotOrange, style = dashed)
                drawRoundRect(c.paper, Offset(-55f, -62f), Size(110f, 62f), CornerRadius(5f))
                drawRoundRect(MascotOrange, Offset(-55f, -62f), Size(110f, 62f), CornerRadius(5f), style = dashed)
                at(0f, -84f, sx = 1f, sy = lid) {
                    drawPath(MascotPaths.boxLid, c.paper)
                    drawPath(MascotPaths.boxLid, MascotOrange, style = dashed)
                }
            }
            if (sp > 0.001f) {
                val color = MascotOrange.copy(alpha = clamp01(sp))
                at(0f, -120f, sx = sp) {
                    at(0f, 120f) {
                        line(color, 0f, -124f, 0f, -146f, 5f)
                        line(color, -32f, -114f, -46f, -132f, 5f)
                        line(color, 32f, -114f, 46f, -132f, 5f)
                    }
                }
            }
            if (dustOp > 0f) {
                val color = c.shadow.copy(alpha = c.shadow.alpha * dustOp)
                drawCircle(color, 10 * (0.6f + dust), Offset(-62 - 14 * dust, -6f))
                drawCircle(color, 6 * (0.6f + dust), Offset(-76 - 18 * dust, -16f))
                drawCircle(color, 10 * (0.6f + dust), Offset(62 + 14 * dust, -6f))
                drawCircle(color, 6 * (0.6f + dust), Offset(76 + 18 * dust, -16f))
            }
        }
        if (gloom > 0.001f) {
            val color = MascotOrange.copy(alpha = gloom)
            at(0f, gy) {
                line(color, 178f, 40f, 178f, 64f, 4f)
                line(color, 190f, 34f, 190f, 66f, 4f)
                line(color, 202f, 42f, 202f, 62f, 4f)
            }
        }
    }
}

/* 6 · «Привет» */
private object HelloScene : SceneSpec(loop = 6f, blob = 0, still = 1.5f) {
    override fun pose(t: Float): MascotPose {
        val b = Breath(t, loop)
        val ant = kf(t, k(0.2f, 0f), k(0.42f, 1f, Ease.out), k(0.55f, 0f, Ease.inn))
        val hop = kf(t, k(0.42f, 0f), k(0.62f, 1f, Ease.out), k(0.86f, 0f, Ease.inn), k(0.98f, 0.25f, Ease.out), k(1.08f, 0f, Ease.inn))
        val up = kf(t, k(0.35f, 0f), k(0.75f, 1f, Ease.back), k(3.3f, 1f), k(4.0f, 0f))
        val w = env(t, 0.75f, 3.3f, 0.25f) * sin(TAU * 2.4f * (t - 0.75f))
        val happy = kf(t, k(0.45f, 0f), k(0.6f, 1f), k(3.7f, 1f), k(3.95f, 0f))
        return MascotPose(
            yaw = -16 * up + 5 * w + 4 * sin(TAU * t / loop),
            pitch = -8 * up + 6 * ant,
            lean = -3 * up + 2 * w,
            y = 4 * ant,
            sy = b.sy - 0.06f * ant + 0.04f * hop, sx = b.sx + 0.04f * ant - 0.02f * hop,
            jump = -16 * hop,
            armR = -7 - 140 * up + 6 * w, bendR = 10 + 20 * up + 38 * w, armL = 7 + 10 * hop - 2 * up,
            rot = 4 * up + 2 * w, happy = happy, eyeSY = blink(t, 4.6f), lookX = 2 * up,
            legL = -5 * hop, legR = 5 * hop,
        )
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        val h = pop(t, 0.6f, 0.45f) * kf(t, k(2.9f, 1f), k(3.4f, 0f, Ease.inn))
        if (h > 0.001f) {
            val hy = 60 - 14 * clamp01((t - 0.6f) / 2.8f)
            val hs = h * (1 + 0.1f * sin(TAU * 2.5f * t))
            at(240f, hy, rot = 6 * sin(TAU * 1.2f * t), sx = hs) {
                drawPath(MascotPaths.heart, MascotOrange.copy(alpha = clamp01(h * 1.3f)))
            }
        }
        val tk = pop(t, 0.8f, 0.3f) * kf(t, k(1.6f, 1f), k(1.9f, 0f))
        ticks(floatArrayOf(232f, 92f, 238f, 104f, 252f, 98f, 262f, 106f, 216f, 84f, 218f, 96f), clamp01(tk), 0.6f + 0.4f * tk, 238f, 100f)
    }
}

/**
 * Сцена целиком в единицах 300 × 300: пятно, тень, маскот, декор.
 * [enter] ∈ [0..1] — появление (пятно и персонаж подрастают на пружине); прозрачность ставит вызывающий.
 */
internal fun DrawScope.drawScene(spec: SceneSpec, time: Float, enter: Float, c: MascotPalette) {
    val t = wrap(time, spec.loop)
    val pose = spec.pose(t).withLag(spec.pose(wrap(t - 0.06f, spec.loop)))
    val e = if (enter >= 1f) 1f else Ease.back(clamp01(enter))
    val blobS = 0.92f + 0.08f * e + 0.012f * sin(TAU * 3 * t / spec.loop)
    at(150f, 150f, sx = blobS) { at(-150f, -150f) { drawPath(MascotPaths.blobs[spec.blob], c.blob) } }
    at(150f, 264f, sx = 0.85f + 0.15f * e) {
        at(-150f, -264f) {
            val gx = 150f + spec.offsetX + pose.x * 0.72f
            val gk = 1 - clamp01(-pose.jump / 60f) * 0.35f
            ellipse(c.shadow, gx, 264f, 82f * gk, 9f * gk)
            with(spec) { under(t, c) }
            at(gx, 262f + pose.jump * 0.72f, sx = 0.72f) {
                drawMascot(pose, c, handR = { with(spec) { handR(t, c) } })
            }
            with(spec) { over(t, c) }
        }
    }
}
