package com.example.myapplication.ui.shared

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.random.Random

/**
 * Матовое стекло Vetro — материал, а не полупрозрачный прямоугольник.
 *
 * В проекте уже есть [adaptiveGlassBackdrop] — «жидкое» стекло с почти нулевым размытием и сильной
 * линзой: контент под ним преломляется и читается насквозь. Это правильный материал для дока над
 * списком обложек, но неправильный для плашки, поверх которой нужно читать текст: буквы ложатся на
 * не размытую картинку и спорят с ней.
 *
 * Здесь собран второй материал — матовый. Он диффузный: размывает то, что под ним, вместо того
 * чтобы это увеличивать. Слои и их порядок взяты из дизайн-документа проекта (multilayer frosted
 * glass) и переложены на Android:
 *
 * | № | Слой дизайн-документа        | Чем сделан здесь                                |
 * |---|------------------------------|-------------------------------------------------|
 * | 1 | живой бэкдроп                | `drawBackdrop(backdrop)` — реальная запись сцены |
 * | 2 | локальное размытие           | `blur()`, клип по форме поверхности              |
 * | 3 | насыщенность/контраст        | `colorControls(contrast, saturation)`            |
 * | 4 | нейтральная подложка         | `onDrawSurface` — заливка тинтом                 |
 * | 5 | микрозерно 1–3%              | `onDrawFront` — кэшированная плитка шума         |
 * | 6 | кант в один физический пиксель | `onDrawFront` — обводка толщиной 1 px          |
 * | 7 | внутренняя подсветка         | `highlight = Highlight(...)`                     |
 * | 8 | тень (широкая, слабая)       | `shadow = Shadow(...)`                           |
 *
 * Два правила из документа, которые легко нарушить по невнимательности:
 *
 * * **Размытие идёт ДО тинта.** Поэтому тинт рисуется в `onDrawSurface` — уже поверх обработанного
 *   бэкдропа, а не примешивается к нему фоном.
 * * **Стеклу нужна информация под собой.** Матовая поверхность над сплошной заливкой выглядит
 *   ровно как непрозрачный прямоугольник, сколько ни крути радиус размытия. Ставить такую
 *   поверхность имеет смысл только над реальным контентом — списком, обложками, кадром.
 *
 * Микрозерно намеренно ахроматическое и очень слабое: его задача — убрать полосатость (banding) на
 * градиенте размытия, а не создать «текстуру». Видимое зерно — это уже декорация.
 */
@Immutable
data class FrostedMaterial(
    /** Радиус локального размытия. Диффузия, ради которой материал и существует. */
    val blur: Dp,
    /** Насыщенность бэкдропа: цвет под стеклом должен остаться живым, а не вылинять. */
    val saturation: Float,
    /** Контраст: лёгкий подъём, иначе размытие делает подложку плоской. */
    val contrast: Float,
    /** Нейтральная подложка поверх размытия — она и даёт «матовость» и контраст для текста. */
    val tint: Color,
    /** Альфа микрозерна, 0.01–0.03. Больше — уже видимая грязь. */
    val noiseAlpha: Float,
    /** Цвет канта; толщина всегда один физический пиксель. */
    val rim: Color,
    /** Внутренняя подсветка по верхней кромке. */
    val highlight: Highlight?,
    /** Широкая тень с низкой альфой — отделяет поверхность от плоскости под ней. */
    val shadow: Shadow?,
    /**
     * Заливка на случай, когда размытия в системе нет (см. [frostedGlass]).
     *
     * Не «примерно тот же цвет, только непрозрачнее»: документ требует осознанный запасной
     * материал, а не имитацию стекла серым прямоугольником.
     */
    val fallbackFill: Color,
)

/**
 * Готовые материалы под конкретные поверхности.
 *
 * Отдельные значения на каждую роль, а не один «glass» с параметрами по месту: россыпь разовых
 * альф по компонентам — первое, что превращает материал в кашу.
 */
object FrostedMaterials {

    /**
     * Плашка уведомления поверх списка.
     *
     * Размытие сильное: под плашкой едут обложки, и текст поверх неразмытой картинки не читается.
     * Тинт плотнее, чем у дока, по той же причине — на плашке живёт основной текст.
     */
    @Composable
    fun notification(): FrostedMaterial {
        val isDark = isAppInDarkTheme()
        return remember(isDark) {
            if (isDark) {
                FrostedMaterial(
                    blur = 24.dp,
                    saturation = 1.18f,
                    contrast = 1.05f,
                    // Нейтральный тёмный: без синего и без коричневого увода — палитра проекта
                    // держится на чёрном с оранжевым акцентом, и голубоватое стекло ей чужое.
                    tint = Color(0xFF121212).copy(alpha = 0.62f),
                    noiseAlpha = 0.022f,
                    rim = Color.White.copy(alpha = 0.16f),
                    highlight = Highlight(width = 0.75.dp, alpha = 0.5f),
                    shadow = Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.22f)),
                    fallbackFill = Color(0xFF1C1C1E),
                )
            } else {
                FrostedMaterial(
                    blur = 24.dp,
                    saturation = 1.16f,
                    contrast = 1.04f,
                    tint = Color.White.copy(alpha = 0.66f),
                    noiseAlpha = 0.016f,
                    rim = Color.White.copy(alpha = 0.85f),
                    highlight = Highlight(width = 0.75.dp, alpha = 0.7f),
                    shadow = Shadow(radius = 26.dp, color = Color.Black.copy(alpha = 0.12f)),
                    fallbackFill = Color.White,
                )
            }
        }
    }

    /**
     * Карточка, лежащая ПОД верхней в стопке уведомлений.
     *
     * Цепочка модификаторов у неё обязана совпадать с [notification]: когда верхнюю смахивают,
     * следующая становится верхней, и подмена набора узлов на живом компоненте обнуляет запись
     * бэкдропа — стекло схлопывается в плоскую заливку. Поэтому не «другой модификатор», а другой
     * МАТЕРИАЛ: размытия нет (его всё равно не видно под верхней карточкой и оно стоило бы
     * впустую), подложка почти непрозрачная.
     *
     * Непрозрачность здесь — не экономия, а требование читаемости: сквозь полупрозрачную стопку
     * просвечивал текст нижних карточек и превращался в кашу.
     */
    @Composable
    fun stackedNotification(): FrostedMaterial {
        val base = notification()
        val isDark = isAppInDarkTheme()
        return remember(base, isDark) {
            base.copy(
                blur = 0.dp,
                saturation = 1f,
                contrast = 1f,
                tint = if (isDark) Color(0xFF1C1C1E) else Color.White,
                noiseAlpha = 0f,
                highlight = null,
                // Тень задней карточки лежит под верхней и не видна, а слой под неё стоил бы
                // на каждом кадре драга стопки.
                shadow = null,
            )
        }
    }

    /**
     * Капсула дока и отдельная круглая кнопка рядом с ней.
     *
     * Тинт легче, чем у плашки: на доке нет текста, который надо вытягивать, зато есть иконки, и
     * плотная подложка убила бы ощущение стекла над движущимся списком.
     */
    /**
     * Кнопки и плашки поверх обложки аудиокниги: плеер, мини-плеер, карточка «Продолжить»
     * (spec/07 `playerControl`, UNIVERSAL_GLASS_SPEC `material.regular`). Под ними всегда арт, поэтому
     * размытие полное; тинт темнее дока — на кнопках белые иконки, им нужен контраст на светлой обложке.
     * Тема не влияет: подложка — картинка, а не фон приложения.
     */
    @Composable
    fun playerControl(): FrostedMaterial = remember {
        FrostedMaterial(
            blur = 24.dp,
            saturation = 1.16f,
            contrast = 1.05f,
            tint = Color(0xFF141414).copy(alpha = 0.42f),
            noiseAlpha = 0.020f,
            rim = Color.White.copy(alpha = 0.16f),
            highlight = Highlight(width = 0.75.dp, alpha = 0.5f),
            shadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.22f)),
            fallbackFill = Color(0xE6202020),
        )
    }

    @Composable
    fun dock(): FrostedMaterial {
        val isDark = isAppInDarkTheme()
        return remember(isDark) {
            if (isDark) {
                FrostedMaterial(
                    blur = 20.dp,
                    saturation = 1.20f,
                    contrast = 1.06f,
                    tint = Color(0xFF141414).copy(alpha = 0.48f),
                    noiseAlpha = 0.020f,
                    rim = Color.White.copy(alpha = 0.14f),
                    highlight = Highlight(width = 0.75.dp, alpha = 0.55f),
                    shadow = Shadow(radius = 30.dp, color = Color.Black.copy(alpha = 0.26f)),
                    fallbackFill = Color(0xFF1A1A1A),
                )
            } else {
                FrostedMaterial(
                    blur = 20.dp,
                    saturation = 1.18f,
                    contrast = 1.05f,
                    tint = Color.White.copy(alpha = 0.54f),
                    noiseAlpha = 0.014f,
                    rim = Color.White.copy(alpha = 0.9f),
                    highlight = Highlight(width = 0.75.dp, alpha = 0.75f),
                    shadow = Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.14f)),
                    fallbackFill = Color(0xFFF2F2F4),
                )
            }
        }
    }
}

/**
 * Есть ли в системе локальное размытие.
 *
 * `RenderEffect.createBlurEffect` появился в Android 12. Ниже размытие не подделываем: документ
 * прямо запрещает выдавать полупрозрачный серый прямоугольник за стекло, и осознанный плотный
 * материал честнее.
 */
private val SystemBlurAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Нанести материал на поверхность формы [shape].
 *
 * Модификатор не добавляет и не убирает узлы по ходу жизни компонента: ветка выбирается один раз
 * по версии системы. В этой кодовой базе структурная смена цепочки модификаторов над потребителем
 * `layerBackdrop` обнуляет запись стекла — держим цепочку постоянной.
 */
@Composable
fun Modifier.frostedGlass(
    backdrop: Backdrop,
    shape: Shape,
    material: FrostedMaterial,
): Modifier {
    val density = LocalDensity.current
    val blurPx = with(density) { material.blur.toPx() }
    // Кант ровно в один физический пиксель, а не в 1 dp: на плотном экране 1 dp — это три-четыре
    // пикселя, и «волосок» превращается в жирную рамку.
    val hairline = with(density) { 1f.toDp() }
    val noiseBrush = rememberNoiseBrush()
    val source = rememberPinnableBackdrop(backdrop)

    // Цепочка запоминается: `drawBackdrop` каждый раз создаёт новый ShapeProvider без `equals`, и
    // любая рекомпозиция вызывающего пересобирала бы RenderEffect и перерисовывала тень.
    val body = remember(source, shape, material, blurPx, noiseBrush) {
        if (SystemBlurAvailable) {
            Modifier.drawBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {
                    // Порядок важен: сначала размываем, потом правим цвет. Обратный порядок
                    // размазывает уже усиленную насыщенность и даёт грязный ореол.
                    blur(blurPx)
                    colorControls(
                        contrast = material.contrast,
                        saturation = material.saturation,
                    )
                },
                highlight = { material.highlight },
                shadow = { material.shadow },
                // Тинт — поверх обработанного бэкдропа, а не примешан к нему фоном: «размытие до
                // тинта» из документа на практике означает именно это.
                onDrawSurface = { drawRect(material.tint) },
                onDrawFront = { drawNoise(noiseBrush, material.noiseAlpha) },
            )
        } else {
            Modifier
                .background(material.fallbackFill, shape)
                .drawWithContent {
                    drawContent()
                    drawNoise(noiseBrush, material.noiseAlpha)
                }
        }
    }

    // Кант отдельным узлом: `border` сам строит обводку по контуру формы, поэтому капсула и
    // скруглённый прямоугольник обводятся правильно без ручной геометрии на каждую из них.
    return this
        .then(body)
        .border(hairline, material.rim, shape)
}

/**
 * Стеклянная кнопка (круглая «назад», кнопка действия, шапка): в новом интерфейсе — матовый
 * материал дока, как у всех стеклянных поверхностей рядом; в классическом — прежний «жидкий»
 * рецепт вызывающего, [classic] целиком (бэкдроп, линза, блик, кант).
 *
 * Развилка по режиму статична на время жизни экрана: режим меняется только пересозданием
 * активити, поэтому набор узлов над бэкдропом на живом экране не меняется.
 */
@Composable
fun Modifier.glassControl(
    backdrop: Backdrop,
    shape: Shape,
    classic: Modifier.() -> Modifier,
): Modifier =
    if (LocalModernUi.current) {
        this.frostedGlass(backdrop = backdrop, shape = shape, material = FrostedMaterials.dock())
    } else {
        this.classic()
    }

private fun DrawScope.drawNoise(noiseBrush: ShaderBrush, alpha: Float) {
    if (alpha > 0f) drawRect(brush = noiseBrush, alpha = alpha)
}

/**
 * Плитка микрозерна.
 *
 * Считается один раз на процесс и переиспользуется всеми поверхностями: документ отдельно требует
 * не пересобирать текстуру шума на каждый кадр. Зерно ахроматическое — цветной шум на нейтральной
 * подложке читается как дефект картинки.
 */
@Composable
private fun rememberNoiseBrush(): ShaderBrush = remember {
    ShaderBrush(
        ImageShader(
            image = NoiseTile,
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated,
        ),
    )
}

private val NoiseTile: ImageBitmap by lazy {
    val size = 128
    // Фиксированное зерно: текстура обязана быть одинаковой между запусками, иначе одна и та же
    // поверхность на скриншотах слегка разная и сравнивать их нельзя.
    val random = Random(0x5E7B0)
    val pixels = IntArray(size * size) {
        val v = 128 + random.nextInt(-64, 64)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.asImageBitmap()
}
