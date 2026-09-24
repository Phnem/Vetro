package com.example.myapplication.ui.workspace

// ==========================================
// «Наезд» между страницами рабочей области (TICKET-03, решение D8).
//
// Пейджер по умолчанию везёт обе страницы синхронно — это читается как лента, а не как переход.
// Здесь роль страницы задаёт ЗНАК смещения: страница с бо́льшим индексом всегда наезжает на
// соседку слева, поэтому вперёд соседка уходит вглубь, а назад из-под уезжающей страницы
// всплывает предыдущая. Одно правило на оба направления.
//
// Функция чистая (никакого Compose) и покрыта таблицей значений в WorkspacePageTransformTest.
// ==========================================

/** Что именно рисовать для страницы при заданном смещении. Значения — сырые, без Compose. */
data class PageTransform(
    val scale: Float,
    /**
     * Добавка к ходу пейджера, в долях ширины страницы. Пейджер уже сдвинул страницу на
     * `offset` ширины; итоговое смещение на экране = `offset + translationXFraction`.
     */
    val translationXFraction: Float,
    val dimAlpha: Float,
    val cornerDp: Float,
    val shadowAlpha: Float,
)

/**
 * Положение страницы на экране в долях её ширины: 0 — по центру, +1 — целиком справа за краем,
 * −1 — слева.
 *
 * Знак дроби ВЫЧИТАЕТСЯ: `currentPageOffsetFraction` растёт, когда пейджер уехал вперёд, то есть
 * страницы поехали ВЛЕВО. Со сложением обе видимые страницы попадали в одну ветку правил, и
 * наезда не было видно вовсе — ровно этот баг и ловит `WorkspacePageOffsetTest`.
 */
fun workspacePageOffset(page: Int, currentPage: Int, currentPageOffsetFraction: Float): Float =
    (page - currentPage) - currentPageOffsetFraction

/** Насколько уходящая страница углубляется в самом конце хода. */
private const val SUNKEN_SCALE = 0.94f

/** Доля собственного хода, которую сохраняет уходящая страница (параллакс ~25 %). */
private const val PARALLAX_KEPT = 0.25f

/** Затемнение уходящей страницы в конце хода: глубже — значит темнее. */
private const val SUNKEN_DIM = 0.28f

/** На какой доле хода от правого края тень наезжающей страницы набирает полную силу. */
private const val SHADOW_FADE_IN = 0.15f

/** Скругление углов уходящей страницы в конце хода, dp. */
private const val SUNKEN_CORNER_DP = 16f

/**
 * Трансформация страницы по её положению на экране (см. [workspacePageOffset]).
 *
 * - `offset >= 0` — страница наезжает: полный ход за пальцем, тень по ведущему краю, сверху.
 * - `offset < 0` — страница уходит под неё: масштаб, притухание, скругление и четверть хода.
 */
fun workspacePageTransform(offset: Float): PageTransform {
    val clamped = offset.coerceIn(-1f, 1f)
    if (clamped >= 0f) {
        return PageTransform(
            scale = 1f,
            translationXFraction = 0f,
            dimAlpha = 0f,
            cornerDp = 0f,
            // Тень ровно на время движения: у осевшей страницы её нет, иначе на стыке страниц
            // остаётся тёмная полоса в покое. Это касается ОБОИХ краёв хода: тень рисуется слева
            // от ведущего края, и у соседней страницы, ждущей за правым краем экрана (offset = 1),
            // она легла бы полосой поверх текущей. Поэтому на первых долях хода она нарастает.
            shadowAlpha = clamped * ((1f - clamped) / SHADOW_FADE_IN).coerceIn(0f, 1f),
        )
    }
    val depth = -clamped
    return PageTransform(
        scale = 1f + (SUNKEN_SCALE - 1f) * depth,
        // Гасим три четверти хода, который уже применил пейджер: на экране остаётся 25 %.
        translationXFraction = depth * (1f - PARALLAX_KEPT),
        dimAlpha = SUNKEN_DIM * depth,
        cornerDp = SUNKEN_CORNER_DP * depth,
        shadowAlpha = 0f,
    )
}
