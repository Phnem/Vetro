package com.example.myapplication.ui.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Общие токены панелей-оверлеев (синхронизация, сортировка). */
internal object OverlayThemeTokens {
    val PanelWidth = 340.dp
    val PanelCornerRadius = 28.dp
    // Затемнение фона за шторками — основной источник контраста «лист vs страница»
    // (панель elevated-цветом чуть светлее затемнённого фона, как в iOS).
    // iOS затемняет по-разному: в тёмной теме сильно (фон почти уходит в чёрный),
    // в светлой — деликатно (~0.2–0.35), иначе выглядит «грязно».
    const val ScrimAlpha = 0.6f
    fun scrimAlpha(isDark: Boolean): Float = if (isDark) 0.6f else 0.35f
    val PanelPaddingTop = 88.dp
    val PanelPaddingEnd = 12.dp
    val CardOuterPaddingTop = 10.dp
    val CardOuterPaddingEnd = 6.dp
    val PanelInnerPaddingStart = 18.dp
    val PanelInnerPaddingTop = 30.dp
    val PanelInnerPaddingEnd = 22.dp
    val PanelInnerPaddingBottom = 18.dp
    val CardElevation = 16.dp

    val RimDark = Color.White.copy(alpha = 0.09f)
    val LabelMutedDark = BrandLightGray
    /** Главный акцент оверлеев — яркий брендовый оранжевый (#F16001). Имя легаси. */
    val IconSyncBlue = BrandOrangeBright
    /** Алиас акцента синх-панели (не путать с мягкими токенами кнопки сортировки). */
    val AccentSyncBlue: Color get() = IconSyncBlue
    /** Статус/статистика — тёплый песочный из брендового градиента. Имя легаси. */
    val IconSignalGreen = BrandOrange
    /** Аккаунт — светлый персиковый оттенок бренд-оранжевого. Имя легаси. */
    val IconAccountYellow = Color(0xFFFFB067)
    val OnSyncBlueButton = Color.White
    /** Destructive — глубокий брендовый красный, осветлённый для тёмного фона. */
    val LogoutIconTint = Color(0xFFE5382B)

    val TileCornerRadius = 20.dp
    val MainTileCornerRadius = 22.dp
    val IconBoxCorner = 10.dp
    val GridSpacing = 10.dp
    val SortTileMinHeight = 90.dp
    val ApplyButtonCornerRadius = 14.dp

    val TileBackgroundDark = Color(0xFF171717)
    val TileIconBgDark = Color.Black.copy(alpha = 0.35f)

    /** Базовый radial glow на тёмных плитках оверлея. */
    const val TileGlowAlphaDark = 0.15f

    /** В светлой теме glow отключен, читаемость обеспечивается рамкой и мягкой тенью. */
    const val TileGlowAlphaLight = 0f

    /** Мягкая тень под плитками в светлой теме. */
    val LightTileShadowElevation = 8.dp

    /**
     * Плитки сеток внутри светлых оверлеев (сортировка, уведомления, статистика):
     * мягкая тень с [clip] и приглушёнными цветами — без «полос» при анимации открытия панели.
     */
    val SortOverlayGridLightShadowElevation = 3.dp

    /**
     * Тень для карточек внутри полупрозрачного листа (обновление и т.п.): слабее, чем у плиток сетки,
     * чтобы не было «грязного» ореола на кремовом фоне.
     */
    val UpdateSheetNestedShadowElevation = 3.dp

    /** Тень целого модального листа (облако, контакт): чуть сильнее вложенных плашек. */
    val SettingsDialogPanelShadowElevation = 5.dp

    /** Мягкая тень под карточками/оверлеями в светлой теме. */
    val LightCardShadowElevation = 8.dp

    /** Кнопка «Применить»: тёмная подложка + приглушённый текст без «кислотных» акцентов. */
    val ApplyButtonContainerDark = Color(0xFF262626)
    val ApplyButtonLabelSoft = BrandLightGray

    // ============================================================
    // Glass edge / fill — параметры стеклянных панелей
    // ============================================================
    /** Ширина внутренней обводки-блика для эффекта объёма стекла. */
    val GlassEdgeWidth = 1.dp

    /** Опорная альфа для glass-edge на тёмной теме (верх/левый угол блика). */
    const val GlassEdgeAlphaDark = 0.22f

    /** В light-theme внутренний glass-edge отключаем: остаётся рамка + внешняя тень. */
    const val GlassEdgeAlphaLight = 0f

    /** Финальная альфа в направлении угасания градиента (тёмная тема). */
    const val GlassEdgeFadeAlphaDark = 0.04f

    /** Финальная альфа в направлении угасания градиента (светлая). */
    const val GlassEdgeFadeAlphaLight = 0f

    /** Лёгкий вертикальный «тонировочный» fill поверх panelBg для имитации толщины стекла. */
    const val GlassFillTopAlphaDark = 0.06f
    const val GlassFillBottomAlphaDark = 0.0f
    /** В light-theme убираем внутренний «налив», чтобы не было тени/пятна внутри плитки. */
    const val GlassFillTopAlphaLight = 0f
    const val GlassFillBottomAlphaLight = 0.0f

    // ============================================================
    // Неоновые акценты — общая палитра для иконок/метрик/индикаторов
    // ============================================================
    /** Главный яркий акцент (синхронизация, эпизоды) — бренд-оранжевый #F16001. */
    val AccentNeonBlue: Color = IconSyncBlue
    /** Песочный (статус, статистика) — #D9C3AB из брендового градиента. */
    val AccentNeonGreen: Color = IconSignalGreen
    /** Персиковый (рейтинги, аккаунт). */
    val AccentNeonYellow: Color = IconAccountYellow
    /** Фирменный оранжевый (контент, акценты) — #E85002. */
    val AccentNeonOrange = BrandOrange
    /** Нейтральный светло-серый (метрики/декор) — #A7A7A7. */
    val AccentNeonPurple = BrandLightGray
    /** Тёплый коралловый тинт бренд-оранжевого (favorites, ошибки в инфо-плитках). */
    val AccentNeonPink = Color(0xFFEF7B54)
    /** Кнопка подтверждения «В избранное» в шите списка — брендовый песочный. */
    val FavoriteConfirmGold = BrandOrange
    val OnFavoriteConfirmGold = Color(0xFF2B2014)

    /**
     * Золотой цвет избранного — **единственный** источник для всех признаков избранного:
     * рамки карточки, углового чипса и заливки swipe-to-favorites.
     *
     * До этого цвет жил в двух несвязанных местах — токеном `FavoriteCardBorder`
     * (персиковый [IconAccountYellow]) и литералом `Color(0xFFFFD600)` в фоне свайпа. Они
     * разъехались, и рамка не совпадала с тем цветом, которым пользователь только что смахнул
     * карточку в избранное. Новые признаки избранного брать отсюда, не заводя литералов.
     *
     * Отдельная роль, а не бренд-оранжевый: оранжевым на карточке уже нарисованы прогресс
     * просмотра и кнопка Edit, третьим оранжевым элементом признак избранного сливался бы с
     * ними вместо того, чтобы выделять.
     */
    val FavoriteGold = Color(0xFFFFD600)

    /** Контраст поверх [FavoriteGold] — звезда в чипсе. Золотой светлый, белое на нём пропадает. */
    val OnFavoriteGold = Color(0xFF1A1400)

    /** Рамка избранной карточки в списке коллекции. Сохранена как имя роли, значение — [FavoriteGold]. */
    val FavoriteCardBorder: Color = FavoriteGold
    /** Алый (logout, destructive). */
    val AccentNeonRed: Color = LogoutIconTint

    /**
     * Поверхность выпадающего меню в меню серий (тёмная тема) — #1C1C1E при alpha 0.96.
     *
     * Не [IosDesign.level2Surface]: тот в тёмной теме даёт `Color.Black` с alpha 0.75, а фон
     * приложения — чистый чёрный (`DarkBackground` = #000000). На таком фоне полупрозрачный
     * чёрный неотличим от самого фона, и меню читается дырой, а не поверхностью. Отдельная
     * роль, а не правка общего материала: level2Surface — материал уровня 2 из гайдбука (§3.2),
     * и переопределять его семантику ради одного экрана нельзя.
     *
     * Прежнее значение (непрозрачный #333333) пользователь отверг по референсу: средне-серая
     * плашка выпадала из тёмного экрана. Оттенок на ступень выше чёрного отделяет меню от фона,
     * а оставшихся 4% прозрачности хватает, чтобы под ним угадывалось содержимое, — «прозрачность
     * совсем чуть чуть».
     */
    val EpisodeMenuSurfaceDark = Color(0xFF1C1C1E).copy(alpha = 0.96f)

    // ============================================================
    // Акценты меню сортировки и меню категорий
    // ============================================================
    /**
     * Насыщенные акценты пунктов меню сортировки и плиток категорий — **разные цвета**, а не
     * оттенки одного.
     *
     * Раньше эти два меню шли как «брендовый оранжевый + нейтралы»: серый #A7A7A7 и #8A8A8E,
     * бежевый #D9C3AB. Рядом с #E85002 нейтралы читались не как другой пункт, а как выключенный,
     * — из-за этого «по названию» и «по жанрам» выглядели блёклыми.
     *
     * Первая попытка чинила это рядом оттенков оранжевого; пользователь её отклонил (решение D-6
     * в MASTER_PLAN): нужен брендовый оранжевый плюс **столь же насыщенные другие** цвета. Набор
     * взят из системной палитры iOS — весь UI построен на iOS-языке ([IosDesign]), и эти оттенки
     * заведомо читаются и на чистом чёрном фоне приложения, и на светлом.
     *
     * Внимание: [AccentYellow] визуально того же семейства, что золотой избранного
     * ([FavoriteGold] = #FFD600). Роли разные, но в одном экране они соседствуют — если это
     * начнёт путать, разводить надо один из двух, а не оба.
     */
    val AccentOrange = BrandOrange
    val AccentBlue = Color(0xFF0A84FF)
    val AccentYellow = Color(0xFFFFD60A)
    val AccentPurple = Color(0xFFBF5AF2)
    val AccentGreen = Color(0xFF30D158)

    // ============================================================
    // Типографика метрик — отдельные роли поверх MaterialTheme
    // ============================================================
    /** Большое жирное число — крупная метрика (totalAnime, totalEpisodes и т.п.). */
    val MetricValue: TextStyle = TextStyle(
        fontFamily = SnProFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    )

    /** Метка под крупной метрикой — мелкий и приглушённый caps-style. */
    val MetricLabel: TextStyle = TextStyle(
        fontFamily = SnProFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    )

    /** Заголовок секции внутри стеклянной панели (uppercase caps). */
    val SectionLabel: TextStyle = TextStyle(
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp
    )

    /** Альфа для приглушённых caption-меток поверх стекла. */
    const val LabelMutedAlpha = 0.55f
}
