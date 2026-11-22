package com.example.myapplication.reminders

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSwitch
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Выключатель напоминаний о заброшенных тайтлах. Самодельная строка, как у биометрии: своё состояние. */
@Composable
fun AbandonedRemindersSettingRow(ru: Boolean, isDark: Boolean) {
    val store: AbandonedReminderStore = koinInject()
    val scope = rememberCoroutineScope()
    val enabled by store.enabled.collectAsStateWithLifecycle(initialValue = true)
    val change: (Boolean) -> Unit = { on -> scope.launch { store.setEnabled(on) } }
    IosRow(
        title = if (ru) "Напоминать о недосмотренном" else "Remind me about unfinished titles",
        subtitle = if (ru) {
            "Тихое уведомление, если тайтл не открывали больше трёх недель"
        } else {
            "A quiet notification if you haven't touched a title for over three weeks"
        },
        isDark = isDark,
        icon = Icons.Rounded.NotificationsActive,
        iconWell = false,
        onClick = { change(!enabled) },
        trailing = { IosSwitch(checked = enabled, onCheckedChange = change) },
    )
}
