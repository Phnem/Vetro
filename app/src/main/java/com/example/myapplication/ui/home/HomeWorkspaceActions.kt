package com.example.myapplication.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.HeroiconsRectangleStack
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.ui.shared.stagedMorphOrigin
import com.example.myapplication.ui.shared.theme.BrandBlue
import com.example.myapplication.ui.shared.theme.BrandRed

/** Что делает средняя кнопка верхнего дока. */
enum class TopDockMiddleAction {
    /** Старая навигация: панель подключения и обновлений. */
    SYNC_PANEL,

    /** Рабочая область: статистика (панель подключения переехала в настройки). */
    STATS,
}

/**
 * Sort + notifications + media-type filter actions for workspace header and glass dock.
 *
 * В рабочей области ([middleAction] = [TopDockMiddleAction.STATS]) средняя кнопка открывает
 * статистику: панель подключения оттуда переехала отдельным пунктом в настройки, а статистика
 * осталась шторкой и ей нужна точка входа.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSortNotificationActions(
    strings: UiStrings,
    filterSelectedTags: List<String>,
    updatesCount: Int,
    onOpenSort: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMediaTypeFilter: () -> Unit,
    dockButtonBackground: Color,
    useDockSizing: Boolean,
    modifier: Modifier = Modifier,
    middleAction: TopDockMiddleAction = TopDockMiddleAction.SYNC_PANEL,
    onOpenStats: () -> Unit = {},
    /**
     * Показывать ли среднюю кнопку.
     *
     * В объединённом режиме её содержимое (статистика и панель синхронизации) уехало в меню
     * нижнего дока, и верхнему доку остаются ровно две вещи: сортировка и тип контента. Держать
     * третью кнопку, дублирующую пункт меню, значит предлагать два пути к одному экрану.
     */
    showMiddleAction: Boolean = true,
) {
    // Иконки дока — ярче/белее: чистый белый на полной непрозрачности в тёмной теме.
    val iconTint = if (isAppInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sortModifier = if (useDockSizing) {
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(dockButtonBackground)
        } else {
            Modifier
        }
        IconButton(
            onClick = onOpenSort,
            modifier = sortModifier,
        ) {
            val icon = if (filterSelectedTags.isNotEmpty()) Icons.Outlined.FilterList else Icons.AutoMirrored.Filled.Sort
            val tint = if (filterSelectedTags.isNotEmpty()) BrandBlue else iconTint
            Icon(icon, contentDescription = strings.cdSort, tint = tint)
        }
        val notifModifier = if (useDockSizing) {
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(dockButtonBackground)
        } else {
            Modifier
        }
        if (showMiddleAction) {
        BadgedBox(
            modifier = notifModifier.then(
                // Морф панели идёт ИЗ этой кнопки (см. StagedSheetMotion.kt). В рабочей области
                // нижний док переключает страницы, поэтому единственная точка входа в
                // статистику — здесь, и расти панель обязана отсюда.
                if (middleAction == TopDockMiddleAction.STATS) {
                    Modifier.stagedMorphOrigin()
                } else {
                    Modifier
                },
            ),
            badge = {
                // Счётчик обновлений относится к панели подключения; у статистики его нет.
                if (middleAction == TopDockMiddleAction.SYNC_PANEL && updatesCount > 0) {
                    Badge(
                        containerColor = BrandRed,
                        contentColor = Color.White,
                    ) {
                        Text(
                            text = if (updatesCount > 99) "99+" else updatesCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            },
        ) {
            IconButton(
                onClick = when (middleAction) {
                    TopDockMiddleAction.SYNC_PANEL -> onOpenNotifications
                    TopDockMiddleAction.STATS -> onOpenStats
                },
                modifier = if (useDockSizing) Modifier.fillMaxSize() else Modifier,
            ) {
                Icon(
                    imageVector = when (middleAction) {
                        TopDockMiddleAction.SYNC_PANEL -> HeroiconsRectangleStack
                        TopDockMiddleAction.STATS -> Icons.Rounded.QueryStats
                    },
                    contentDescription = when (middleAction) {
                        TopDockMiddleAction.SYNC_PANEL -> strings.cdNotifications
                        TopDockMiddleAction.STATS -> strings.statsTitle
                    },
                    tint = iconTint,
                )
            }
        }
        }
        val settingsModifier = if (useDockSizing) {
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(dockButtonBackground)
        } else {
            Modifier
        }
        IconButton(
            onClick = onOpenMediaTypeFilter,
            modifier = settingsModifier,
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = strings.contentTypeTitle,
                tint = iconTint,
            )
        }
    }
}
