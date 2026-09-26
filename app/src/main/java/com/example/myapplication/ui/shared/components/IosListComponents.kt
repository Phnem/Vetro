package com.example.myapplication.ui.shared.components

import com.example.myapplication.ui.shared.theme.BrandOrange
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.iosRowHighlight

/**
 * iOS Inset Grouped List — набор компонентов (гайдбук §5, §8).
 *
 * Правила, заложенные здесь и не подлежащие «доработке ripple'ом»:
 *  • highlight строки — мгновенная заливка + fade-out 180ms, **без Material Ripple** ([iosRowHighlight]).
 *  • углы группы — [SquircleShape] (continuous curvature), radius.md, на группу целиком.
 *  • разделитель — 1dp с inset слева на ширину «icon + gap» ([IosDesign.SeparatorInset]).
 *  • leading icon — цветной squircle-well 29dp с белой иконкой (как Settings.app).
 */

/** Заголовок-капс над группой (iOS grouped list header). */
@Composable
fun IosSectionHeader(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f),
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(
            start = IosDesign.ListHorizontalInset + 4.dp,
            end = IosDesign.ListHorizontalInset,
            bottom = 7.dp,
        ),
    )
}

/** Подпись-футер под группой (пояснительный текст, левое выравнивание). */
@Composable
fun IosSectionFooter(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f),
        fontFamily = SnProFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = modifier.padding(
            start = IosDesign.ListHorizontalInset + 4.dp,
            end = IosDesign.ListHorizontalInset + 4.dp,
            top = 7.dp,
        ),
    )
}

/** Разделитель между строками с левым inset. */
@Composable
fun IosRowDivider(isDark: Boolean, startInset: Dp = IosDesign.SeparatorInset) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(IosDesign.SeparatorThickness)
            .background(IosDesign.separator(isDark)),
    )
}

/**
 * Группа сгруппированного списка: опциональный header/footer и «карточка в карточке» —
 * приподнятый контейнер группы, внутри которого КАЖДАЯ строка живёт в собственной squircle-карточке
 * с зазором. Разделителей нет: границу строки задаёт сама карточка, поэтому список читается
 * плотнее и не выглядит одной сплошной плитой.
 */
@Composable
fun IosListGroup(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    rows: List<@Composable () -> Unit>,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IosDesign.ListHorizontalInset),
    ) {
        if (header != null) IosSectionHeader(header, isDark)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SquircleShape(IosDesign.GroupRadius))
                .background(IosDesign.groupBackground(isDark))
                .padding(IosDesign.GroupPadding),
            verticalArrangement = Arrangement.spacedBy(IosDesign.GroupRowSpacing),
        ) {
            val cardShape = SquircleShape(IosDesign.RadiusMd)
            rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(IosDesign.groupRowBackground(isDark)),
                ) {
                    row()
                }
            }
        }
        if (footer != null) IosSectionFooter(footer, isDark)
    }
}

/** Цветной squircle icon-well (leading), как в Settings.app. */
@Composable
fun IosIconWell(
    icon: ImageVector,
    background: Color,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(IosDesign.ListIconSize)
            .clip(SquircleShape(IosDesign.ListIconRadius, smoothing = 0.55f))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(IosDesign.ListIconGlyph),
        )
    }
}

/** Вариант icon-well с drawable-ресурсом (Hugeicons VectorDrawable), белая пиктограмма. */
@Composable
fun IosIconWell(
    iconRes: Int,
    background: Color,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(IosDesign.ListIconSize)
            .clip(SquircleShape(IosDesign.ListIconRadius, smoothing = 0.55f))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(IosDesign.ListIconGlyph),
        )
    }
}

/**
 * Строка списка. Leading icon-well → title/subtitle → trailing (value + chevron / кастомный контент).
 * Нажатие — мгновенный highlight без ripple; высота ≥ 44dp.
 */
@Composable
fun IosRow(
    title: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    iconBackground: Color = Color.Gray,
    iconTint: Color = Color.Unspecified,
    /**
     * `true` (умолч.) — цветной squircle icon-well (Settings.app-стиль). `false` — плоская
     * силуэтная пиктограмма без подложки, тонированная [iconTint] (умолч. onSurface).
     */
    iconWell: Boolean = true,
    value: String? = null,
    valueColor: Color? = null,
    showChevron: Boolean = false,
    /**
     * Если задан — chevron становится раскрывающим: `false` смотрит вправо (свёрнуто),
     * `true` доворачивается вниз (раскрыто) через мягкую пружину. Используется для
     * inline-раскрывающихся секций (напр. «Для разработчиков»).
     */
    chevronExpanded: Boolean? = null,
    titleColor: Color? = null,
    minHeight: Dp = IosDesign.CardMinHeight,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = remember { SquircleShape(0.dp) } // строка не клипается, но highlight рисует по прямоугольнику
    val interaction = remember { MutableInteractionSource() }
    val rowModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = iosRowHighlight(isDark, shape),
            onClick = onClick,
        )
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowModifier)
            .defaultMinSize(minHeight = minHeight)
            .padding(horizontal = IosDesign.CardHorizontalInset, vertical = IosDesign.CardVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null || icon != null) {
            if (iconWell) {
                val wellTint = if (iconTint == Color.Unspecified) Color.White else iconTint
                if (iconRes != null) IosIconWell(iconRes = iconRes, background = iconBackground, tint = wellTint)
                else IosIconWell(icon = icon!!, background = iconBackground, tint = wellTint)
            } else {
                // Плоский силуэт без подложки — карточка сама себе контейнер, цветной well
                // на ней превращается в «плитку в плитке».
                val plainTint = if (iconTint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else iconTint
                Box(
                    modifier = Modifier.size(IosDesign.CardIconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = plainTint,
                            modifier = Modifier.size(IosDesign.CardIconGlyph),
                        )
                    } else {
                        Icon(
                            imageVector = icon!!,
                            contentDescription = null,
                            tint = plainTint,
                            modifier = Modifier.size(IosDesign.CardIconGlyph),
                        )
                    }
                }
            }
            Spacer(Modifier.width(IosDesign.CardIconTextGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                fontFamily = SnProFamily,
                // Заголовок на карточке — полужирный: он якорь строки, подпись под ним приглушена.
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
        if (chevronExpanded != null) {
            val rotation by animateFloatAsState(
                targetValue = if (chevronExpanded) 90f else 0f,
                animationSpec = MotionTokens.standard(),
                label = "iosRowChevronRotation",
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = IosDesign.chevron(isDark),
                modifier = Modifier
                    .size(IosDesign.CardChevronSize)
                    .graphicsLayer { rotationZ = rotation },
            )
        } else if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = IosDesign.chevron(isDark),
                modifier = Modifier.size(IosDesign.CardChevronSize),
            )
        }
    }
}

/**
 * iOS-сегментированный переключатель: скользящая pill, лёгкая пружина.
 * Пилюля перелетает через [MotionTokens.standard] (интерактивный дефолт iOS).
 */
@Composable
fun IosSegmentedControl(
    optionCount: Int,
    selectedIndex: Int,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
    segment: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val trackColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val pillColor = if (isDark) Color(0xFF636366) else Color.White
    // State, а не делегат: позиция читается в фазе раскладки (offset {}), и полёт пилюли не
    // пересобирает сегменты на каждом кадре.
    val animatedFraction = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = MotionTokens.standard(),
        label = "segmentedPill",
    )
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(SquircleShape(9.dp, smoothing = 0.5f))
            .background(trackColor)
            .padding(2.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val segWidth = maxWidth / optionCount
            // Скользящая pill с мягкой тенью активного сегмента (iOS 26 §4.2).
            val pillShape = SquircleShape(8.dp, smoothing = 0.5f)
            Box(
                modifier = Modifier
                    .width(segWidth)
                    .height(28.dp)
                    .offset {
                        IntOffset((segWidth.toPx() * animatedFraction.value).roundToInt(), 0)
                    }
                    .shadow(elevation = if (isDark) 0.dp else 1.5.dp, shape = pillShape, clip = false)
                    .clip(pillShape)
                    .background(pillColor),
            )
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until optionCount) {
                    Box(
                        modifier = Modifier
                            .width(segWidth)
                            .height(28.dp)
                            .clip(SquircleShape(8.dp, smoothing = 0.5f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(i) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        segment(i, i == selectedIndex)
                    }
                }
            }
        }
    }
}

/**
 * Входная физика попап-диалога (гайдбук §10): «усадка сверху» scale 1.15→1.0 на [MotionTokens.dialogPop],
 * alpha 0→1 (MotionTokens.tweenStandard). Оборачивай контент нативного [androidx.compose.ui.window.Dialog].
 */
@Composable
fun IosDialogAnimatedContent(content: @Composable () -> Unit) {
    val scale = remember { Animatable(1.15f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, MotionTokens.dialogPop()) }
        launch { alpha.animateTo(1f, MotionTokens.tweenStandard()) }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
        },
    ) { content() }
}

/**
 * Bottom-sheet пикер (гайдбук §4.1): заголовок + список опций со squircle-группой и чекмарком
 * у выбранной. Рендерится внутри [IosSheetScaffold] как sheetContent.
 */
@Composable
fun IosPickerSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    optionIcons: List<ImageVector?>? = null,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = IosDesign.ListHorizontalInset)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SquircleShape(IosDesign.RadiusMd))
                .background(IosDesign.rowBackground(isDark)),
        ) {
            options.forEachIndexed { i, opt ->
                if (i > 0) IosRowDivider(isDark, startInset = IosDesign.ListHorizontalInset)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = iosRowHighlight(isDark, remember { SquircleShape(0.dp) }),
                            onClick = { onSelect(i) },
                        )
                        .defaultMinSize(minHeight = 50.dp)
                        .padding(horizontal = IosDesign.ListHorizontalInset, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = optionIcons?.getOrNull(i)
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = opt,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = SnProFamily,
                        fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (i == selectedIndex) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = BrandOrange,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Опция для [IosSelectionSheet]. Пиктограмма — либо [icon] (ImageVector), либо [iconRes] (drawable). */
data class IosSelectionOption(
    val title: String,
    val icon: ImageVector?,
    val accent: Color,
    val subtitle: String? = null,
    val iconRes: Int? = null,
)

/**
 * Лист-выбор в стиле iOS «Set Status»: крупный заголовок + отдельные скруглённые карточки-опции
 * (иконка + заголовок + подпись + радио справа), выбранная подсвечена тоном её акцента; опционально
 * большая кнопка «Готово» снизу. Рендерится внутри bottom sheet как содержимое.
 */
@Composable
fun IosSelectionSheet(
    title: String,
    options: List<IosSelectionOption>,
    selectedIndex: Int,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    doneLabel: String? = null,
    cardCornerRadius: Dp = 20.dp,
    onSelect: (Int) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = (-0.3).sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
        )
        val cardShape = SquircleShape(cardCornerRadius)
        options.forEachIndexed { i, opt ->
            IosSelectionCard(
                index = i,
                option = opt,
                selected = i == selectedIndex,
                isDark = isDark,
                cardShape = cardShape,
                onSelect = onSelect,
            )
        }
        if (doneLabel != null && onDone != null) {
            val doneAccent = options.getOrNull(selectedIndex)?.accent ?: BrandOrange
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(SquircleShape(27.dp))
                    .background(doneAccent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDone,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = doneLabel,
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Карточка-опция листа-выбора с физикой нажатия (гайдбук — «Set Status»):
 *  • **новый** пункт — активная реакция на палец: scale 1.00→0.985 на касании, коммит выбора
 *    «под пальцем» (заливка стартует ~50мс после касания), отпускание → лёгкий отскок 1.015→1.00;
 *  • **предыдущий** пункт — пассивно (без нажатия) теряет выбор за 160мс: акцент→серый фон,
 *    акцент→серая иконка, radio filled→empty.
 */
@Composable
private fun IosSelectionCard(
    index: Int,
    option: IosSelectionOption,
    selected: Boolean,
    isDark: Boolean,
    cardShape: androidx.compose.ui.graphics.Shape,
    onSelect: (Int) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    var everPressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            everPressed = true
            // Коммит на касании: новый пункт получает весь отклик, заливка идёт «под пальцем».
            if (!selected) onSelect(index)
            scale.animateTo(0.985f, tween(80))
        } else if (everPressed) {
            // Отпустили → физический отскок, затем мягкое оседание.
            scale.animateTo(1.015f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f))
        }
    }

    // Активный пункт «наливается» с задержкой 50мс после касания; предыдущий пассивно гаснет за 160мс.
    val normalBg = IosDesign.elevatedCard(isDark)
    val selectedBg = option.accent.copy(alpha = if (isDark) 0.22f else 0.14f)
    val cardBg by animateColorAsState(
        targetValue = if (selected) selectedBg else normalBg,
        animationSpec = if (selected) MotionTokens.tweenStandard(delayMillis = 50) else MotionTokens.tweenFast(),
        label = "selectionCardBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) option.accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = if (selected) MotionTokens.tweenStandard(delayMillis = 50) else MotionTokens.tweenFast(),
        label = "selectionCardContent",
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (selected) option.accent.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        animationSpec = if (selected) MotionTokens.tweenStandard(delayMillis = 50) else MotionTokens.tweenFast(),
        label = "selectionCardSubtitle",
    )
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) MotionTokens.tweenStandard(delayMillis = 50) else MotionTokens.tweenFast(),
        label = "selectionRadioFill",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(cardShape)
            .background(cardBg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onSelect(index) },
            )
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.iconRes != null) {
            Icon(
                painter = painterResource(option.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(26.dp),
            )
        } else if (option.icon != null) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = option.title,
                color = contentColor,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.subtitle != null) {
                Text(
                    text = option.subtitle,
                    color = subtitleColor,
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        IosRadioDot(progress = fill, accent = option.accent, isDark = isDark)
    }
}

/** Радио-точка iOS с плавным заполнением: [progress] 0 (пустое кольцо) → 1 (залито акцентом). */
@Composable
private fun IosRadioDot(progress: Float, accent: Color, isDark: Boolean) {
    val ringColor = (if (isDark) Color.White else Color.Black).copy(alpha = 0.22f * (1f - progress))
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(width = 2.dp, color = ringColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Акцентная заливка вырастает из центра.
        Box(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = progress
                    scaleY = progress
                    alpha = progress
                }
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/**
 * iOS toggle switch: 51×31 трек, 27 thumb, брендовый «on».
 *
 * Плавный эластичный переход (4 ключевых кадра референса): при переключении ползунок
 * скользит вдоль трека и одновременно вытягивается в горизонтальную капсулу в середине
 * пути (`sin(p·π)` — 0 в крайних положениях, максимум в центре), а трек плавно
 * перекрашивается. Всё завязано на один пружинный `progress`, поэтому цвет, сдвиг и
 * растяжение идут абсолютно синхронно.
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onColor: Color = BrandOrange,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    // Единый прогресс 0 (off) → 1 (on): лёгкий overshoot придаёт «резину» движению.
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MotionTokens.menuPop(),
        label = "iosSwitchProgress",
    )
    val p = progress.coerceIn(0f, 1f)

    val offColor = Color(0xFF787880).copy(alpha = 0.32f)
    val trackColor = androidx.compose.ui.graphics.lerp(offColor, onColor, p)

    // Геометрия: трек 51×31, padding 2 → внутренняя зона 47×27, ход центра thumb = 20dp.
    val thumbDiameter = 27.dp
    val travel = 20.dp
    val stretchExtra = 9.dp

    // Растяжение по колоколу: 0 в крайних, максимум в середине хода.
    val stretch = kotlin.math.sin(p * Math.PI.toFloat()).coerceIn(0f, 1f)
    val thumbWidth = thumbDiameter + stretchExtra * stretch
    // Центр ползунка едет по прямой; вытягивание симметрично относительно центра,
    // поэтому капсула не вылезает за края (в крайних точках растяжение = 0).
    val thumbCenterX = thumbDiameter / 2 + travel * progress
    val thumbOffsetX = thumbCenterX - thumbWidth / 2

    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(SquircleShape(15.5.dp, smoothing = 0.4f))
            .background(trackColor)
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onCheckedChange(!checked) },
                    )
                } else Modifier,
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffsetX)
                .size(width = thumbWidth, height = thumbDiameter)
                // corner = высота/2 → капсула остаётся идеально скруглённой при растяжении.
                .clip(SquircleShape(thumbDiameter / 2, smoothing = 0.4f))
                .background(Color.White),
        )
    }
}
