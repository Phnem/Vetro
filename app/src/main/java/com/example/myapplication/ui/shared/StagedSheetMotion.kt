package com.example.myapplication.ui.shared

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.abs

/**
 * Откуда раскрывается панель — прямоугольник кнопки в координатах корня.
 *
 * Состояние общее, потому что кнопка и панель живут в разных ветках дерева: кнопка сидит в доке,
 * панель — в оверлейном слое поверх всего экрана. Тянуть прямоугольник параметрами через их общего
 * предка значит протащить временный флаг через десяток сигнатур.
 */
@Stable
class StagedMorphOriginState {
    var rect: Rect? by mutableStateOf(null)
        internal set

    internal fun report(value: Rect) {
        rect = value
    }
}

val LocalStagedMorphOrigin = compositionLocalOf { StagedMorphOriginState() }

/**
 * Запомнить свои границы как точку, из которой раскроется панель.
 *
 * Границы пишутся при каждом изменении раскладки, а не по клику: к моменту клика кнопка уже может
 * уезжать (док прячется), и снимок «в момент нажатия» дал бы точку, из которой ничего не выходило.
 */
@Composable
fun Modifier.stagedMorphOrigin(): Modifier {
    val state = LocalStagedMorphOrigin.current
    return this.onGloballyPositioned { state.report(it.boundsInRoot()) }
}

/**
 * Каким путём объект идёт от кнопки к своей конечной форме.
 *
 * Промежуточная форма — приём для панели, которая раскрывается В ЦЕНТР экрана: она даёт объекту
 * узнаваемую «сборку» перед разворотом. Но у меню, пришитого к своей кнопке, та же сборка читается
 * как заминка: объект сначала топчется на месте, потом рывком уходит вверх. Поэтому путь —
 * параметр, а не свойство механизма.
 */
enum class MorphPath {
    /** Через промежуточную форму: кнопка собирается, затем разводится в панель. */
    STAGED,

    /** Напрямую: габариты едут к цели одной пружиной, без промежуточной остановки. */
    DIRECT,
}

/**
 * Физика двухфазного морфа «кнопка → промежуточная форма → панель».
 *
 * Числа — пересчёт измеренного эталона из дизайн-документа проекта (покадровый разбор морфа
 * тулбара в сетку, 60 fps). Документ отдельно оговаривает: это **пример для адаптации**, а не
 * универсальный пресет, и подогнанные константы не выдают за настройки исходного автора. Здесь они
 * пересчитаны из пары (ζ, T) в термины Compose по определению затухающего осциллятора:
 *
 * ```text
 * ω  = 2π / T
 * k  = ω² · m      (m = 1)
 * ζ  = c / (2·√(k·m))
 * ```
 *
 * Главное, что переносится из эталона, — не значения, а форма движения:
 *
 * * **Фаза 1 сжимает ширину и растит высоту.** Кнопка не «раздувается» в панель равномерно: она
 *   сначала собирается в промежуточную форму. Равномерный Scale из общего центра эту траекторию
 *   воспроизвести не может — поэтому оси анимируются независимо.
 * * **Фаза 2 разводит ширину и высоту с РАЗНОЙ динамикой** и с перелётом: по ширине перелёт около
 *   13% пути «промежуточная форма → цель», по высоте около 7%. Проценты именно от ПУТИ, а не от
 *   конечного размера: общий множитель 1.13 на итоговые габариты заметно раздул бы панель и
 *   исказил текст.
 * * **Фаза 2 стартует из состояния и СКОРОСТИ фазы 1**, а не из покоя. В Compose это даётся даром:
 *   `Animatable` при смене цели продолжает с текущей скорости — лишь бы не пересоздавать его.
 */
private object MorphSprings {

    /** Пружина из периода [periodSeconds] и коэффициента затухания [damping]. */
    private fun <T> from(periodSeconds: Float, damping: Float): SpringSpec<T> {
        val omega = (2.0 * PI / periodSeconds).toFloat()
        return spring(dampingRatio = damping, stiffness = omega * omega)
    }

    /**
     * Сбор в промежуточную форму: по эталону 83–100 мс. Затухание критическое — на промежуточной
     * форме отскок читался бы как отдельное «второе» движение, которого в эталоне нет.
     */
    val Hub: SpringSpec<Float> = from(periodSeconds = 0.18f, damping = 1f)

    /**
     * Раскрытие после промежуточной формы: T≈455 мс.
     *
     * Затухание назначено НЕ по целевому перелёту напрямую. Пружина ведёт ОБЩИЙ прогресс
     * от нуля до единицы, а геометрию второй фазы строит его хвост — отрезок [HUB_PROGRESS]…1,
     * растянутый на весь путь «промежуточная форма → цель». Перелёт на этом отрезке увеличивается
     * в `1 / (1 - HUB_PROGRESS)` ≈ 1.85 раза. Чтобы на ГАБАРИТАХ получились обещанные 13% пути,
     * сама пружина обязана перелетать вдвое меньше — отсюда ζ≈0.65, а не 0.53.
     */
    val ExpandWidth: SpringSpec<Float> = from(periodSeconds = 0.455f, damping = 0.65f)

    /** Раскрытие по высоте: ζ≈0.63, T≈420 мс — перелёт вдвое меньше, чем по ширине. */
    val ExpandHeight: SpringSpec<Float> = from(periodSeconds = 0.450f, damping = 0.63f)

    /**
     * Прямое раскрытие ([MorphPath.DIRECT]).
     *
     * Затухание заметно выше, чем у двухфазного пути: там перелёт — кульминация движения, к
     * которой объект приходит уже собранным, здесь же он летит от кнопки к цели без остановок, и
     * крупный отскок в конце такого перелёта читается не как упругость, а как промах.
     */
    val Direct: SpringSpec<Float> = from(periodSeconds = 0.40f, damping = 0.78f)

    /**
     * Закрытие. Быстрее входа и без отскока: уходящий объект обязан освободить дорогу, а не
     * покачаться на прощание.
     */
    val Collapse: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 900f)
}

/** Состояние морфа: что рисовать прямо сейчас. */
@Stable
class StagedMorphState internal constructor() {
    /** 0 — кнопка, 1 — панель. Ведёт геометрию оболочки. */
    var shell by mutableStateOf(0f)
        internal set

    /**
     * Прозрачность содержимого. Отдельной дорожкой от геометрии: если ждать хвоста пружины,
     * читать панель можно будет только к концу анимации.
     */
    var contentAlpha by mutableStateOf(0f)
        internal set

    /** Раскрылась ли оболочка настолько, чтобы содержимое в неё поместилось. */
    val contentFits: Boolean get() = shell > CONTENT_REVEAL_AT

    internal companion object {
        /**
         * Доля раскрытия, после которой показывается содержимое.
         *
         * Раньше этого порога текст пришлось бы вжимать в оболочку, которая его не вмещает, —
         * документ прямо запрещает так делать: буквы превращаются в нарезку.
         */
        const val CONTENT_REVEAL_AT = 0.42f
    }
}

/**
 * Прогон двухфазного морфа.
 *
 * Возвращает состояние, по которому панель строит свою геометрию. Сама геометрия остаётся за
 * вызывающим: у панели свои границы, отступы и форма, и зашивать их сюда значило бы сделать
 * компонент одноразовым.
 *
 * При отключённой анимации (системная настройка «Длительность анимации: выкл», раздел
 * «Для разработчиков» или спец-возможности) фазы пропускаются: документ требует в этом режиме
 * показывать конечную раскладку сразу, а не проигрывать её быстрее.
 */
@Composable
fun rememberStagedMorph(
    expanded: Boolean,
    path: MorphPath = MorphPath.STAGED,
): StagedMorphState {
    val state = remember { StagedMorphState() }
    val shell = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val context = LocalContext.current
    val animationsOff = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    androidx.compose.runtime.LaunchedEffect(expanded, animationsOff) {
        if (animationsOff) {
            shell.snapTo(if (expanded) 1f else 0f)
            alpha.snapTo(if (expanded) 1f else 0f)
            state.shell = shell.value
            state.contentAlpha = alpha.value
            return@LaunchedEffect
        }
        if (expanded) {
            when (path) {
                MorphPath.STAGED -> {
                    // Фаза 1: сбор в промежуточную форму. Отдельной целью, а не долей общего
                    // пути, — иначе фазы сольются в одно монотонное раздувание.
                    shell.animateTo(HUB_PROGRESS, MorphSprings.Hub) { state.shell = value }
                    // Фаза 2 продолжает с той же скоростью: Animatable её не теряет.
                    shell.animateTo(1f, MorphSprings.ExpandWidth) { state.shell = value }
                }

                MorphPath.DIRECT -> shell.animateTo(1f, MorphSprings.Direct) { state.shell = value }
            }
        } else {
            shell.animateTo(0f, MorphSprings.Collapse) { state.shell = value }
        }
    }

    androidx.compose.runtime.LaunchedEffect(expanded, animationsOff) {
        if (animationsOff) return@LaunchedEffect
        // Свет — не пространство: у прозрачности своя, более быстрая дорожка, и она не ждёт,
        // пока оболочка доколеблется до своих последних процентов.
        alpha.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = if (expanded) 420f else 1400f),
        ) { state.contentAlpha = value }
    }

    return state
}

/**
 * Доля пути, на которой оболочка считается собранной в промежуточную форму.
 *
 * По эталону промежуточная форма примерно вдвое уже цели и вдвое ниже — то есть примерно середина
 * пути по площади, но НЕ середина по каждой из осей. Разводит оси уже вызывающий: здесь хранится
 * только момент переключения фаз.
 */
private const val HUB_PROGRESS = 0.46f

/**
 * Геометрия оболочки в момент [progress].
 *
 * Ширина и высота считаются по РАЗНЫМ кривым, и именно в этом смысл всего приёма: на первой фазе
 * ширина идёт от кнопки к промежуточной форме СЖИМАЯСЬ (если кнопка шире), а высота растёт.
 *
 * Сверху прогресс НЕ подрезается, и это принципиально. Пружина раскрытия недодемпфирована —
 * она обязана перелететь цель и вернуться. Обрезка по единице прятала сам перелёт (геометрия
 * стояла на цели, пока пружина гуляла выше) и показывала только возврат: панель замирала
 * раскрытой, разово поджималась на процент и снова раскрывалась. Со стороны это читалось не как
 * упругость, а как подвисший кадр.
 *
 * @param progress значение [StagedMorphState.shell]
 * @param origin прямоугольник кнопки; `null` — кнопку не измерили, и морфу не из чего выходить
 * @param target конечный прямоугольник панели
 * @param path каким путём объект идёт к цели
 */
fun stagedMorphBounds(
    progress: Float,
    origin: Rect?,
    target: Rect,
    path: MorphPath = MorphPath.STAGED,
): Rect {
    if (origin == null) return target
    val p = progress.coerceAtLeast(0f)
    if (path == MorphPath.DIRECT) return lerpRect(origin, target, p)
    val hub = Rect(
        left = origin.center.x - target.width * HUB_WIDTH_FRACTION / 2f,
        top = origin.center.y - target.height * HUB_HEIGHT_FRACTION / 2f,
        right = origin.center.x + target.width * HUB_WIDTH_FRACTION / 2f,
        bottom = origin.center.y + target.height * HUB_HEIGHT_FRACTION / 2f,
    )
    return if (p <= HUB_PROGRESS) {
        val t = if (HUB_PROGRESS == 0f) 1f else p / HUB_PROGRESS
        lerpRect(origin, hub, t)
    } else {
        val t = (p - HUB_PROGRESS) / (1f - HUB_PROGRESS)
        lerpRect(hub, target, t)
    }
}

/** Пропорции промежуточной формы относительно цели — по измеренному эталону. */
private const val HUB_WIDTH_FRACTION = 0.53f
private const val HUB_HEIGHT_FRACTION = 0.58f

private fun lerpRect(from: Rect, to: Rect, t: Float): Rect = Rect(
    left = from.left + (to.left - from.left) * t,
    top = from.top + (to.top - from.top) * t,
    right = from.right + (to.right - from.right) * t,
    bottom = from.bottom + (to.bottom - from.bottom) * t,
)

/**
 * Равномерный масштаб содержимого, пока оболочка мала.
 *
 * Именно равномерный: растягивать текст разными коэффициентами по осям нельзя — буквы поедут.
 * Ниже порога показа содержимое всё равно прозрачно, поэтому масштаб там не важен.
 */
fun stagedMorphContentScale(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return 1f
    val t = ((p - StagedMorphState.CONTENT_REVEAL_AT) / (1f - StagedMorphState.CONTENT_REVEAL_AT))
        .coerceIn(0f, 1f)
    return CONTENT_MIN_SCALE + (1f - CONTENT_MIN_SCALE) * t
}

/** Насколько сжато содержимое в момент появления. Мягко, чтобы не читалось как второй зум. */
private const val CONTENT_MIN_SCALE = 0.94f

/** Скруглениe оболочки ведётся от её текущих габаритов, а не берётся постоянным. */
fun stagedMorphCornerRadius(bounds: Rect, targetRadiusPx: Float, originRadiusPx: Float): Float {
    val half = minOf(bounds.width, bounds.height) / 2f
    // Пилюля не может быть скруглена сильнее, чем на половину своей короткой стороны: иначе на
    // промежуточной форме угол «перекручивается» и край дрожит.
    return minOf(maxOf(targetRadiusPx, minOf(originRadiusPx, half)), half).let { r ->
        if (abs(r) < 0.5f) 0f else r
    }
}
