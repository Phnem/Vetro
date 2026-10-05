package com.example.myapplication.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.network.AppLanguage
import com.phnem.vetro.R

/**
 * Состав меню ТТМ.
 *
 * Сюда собрано то, что раньше было размазано по двум докам и настройкам: статистика и
 * синхронизация жили в верхнем доке, кадр и добавление — отдельными страницами пейджера, донат —
 * строкой в настройках. Общее у них одно: это РАЗОВЫЕ действия, а не разделы, по которым ходят
 * туда-сюда. Держать под каждое постоянное гнездо в доке — значит тратить самое ценное место
 * экрана на то, к чему обращаются раз в сессию.
 *
 * Порядок — по убыванию частоты обращения; донат последним намеренно: просьба о деньгах не должна
 * стоять первой в списке.
 */
fun ttmMenuItems(
    language: AppLanguage,
    onStats: () -> Unit,
    onCalendar: () -> Unit,
    onFrame: () -> Unit,
    onSync: () -> Unit,
    onAdd: () -> Unit,
    onDonate: () -> Unit,
): List<TtmMenuItem> {
    val ru = language == AppLanguage.RU
    return listOf(
        TtmMenuItem(
            label = if (ru) "Статистика" else "Stats",
            onClick = onStats,
        ) { tint ->
            Icon(Icons.Rounded.QueryStats, null, Modifier.size(20.dp), tint)
        },
        TtmMenuItem(
            label = if (ru) "Календарь" else "Calendar",
            onClick = onCalendar,
        ) { tint ->
            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(20.dp), tint)
        },
        TtmMenuItem(
            label = if (ru) "Кадр" else "Frame",
            onClick = onFrame,
        ) { tint ->
            // Тот же ассет, что был у раздела: «Кадр» узнаётся именно по нему.
            Icon(painterResource(R.drawable.frame_inspect_24), null, Modifier.size(20.dp), tint)
        },
        TtmMenuItem(
            label = if (ru) "Синхронизация" else "Sync",
            onClick = onSync,
        ) { tint ->
            Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp), tint)
        },
        TtmMenuItem(
            label = if (ru) "Добавить" else "Add",
            onClick = onAdd,
        ) { tint ->
            Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint)
        },
        TtmMenuItem(
            label = if (ru) "Поддержать" else "Donate",
            onClick = onDonate,
        ) { tint ->
            Icon(Icons.Outlined.Favorite, null, Modifier.size(20.dp), tint)
        },
    )
}
