package com.example.myapplication.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.myapplication.microml.MicroMlEngine
import com.example.myapplication.microml.contribution.ContributionService
import com.example.myapplication.ui.shared.components.IosListGroup
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.theme.BrandOrange
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Раздел настроек «Умные модели»: три модели на телефоне (каждая своим тумблером) и добровольный сбор данных.
 * Сбор выключен, пока пользователь не нажмёт «ОК» в окне согласия; в сборках без подписи автора (F-Droid, пересборки) строки сбора нет.
 */
@Composable
internal fun SmartModelsGroup(ru: Boolean, isDark: Boolean) {
    val engine = koinInject<MicroMlEngine>()
    val service = koinInject<ContributionService>()
    val rows = mutableListOf<@Composable () -> Unit>()

    MicroMlEngine.Pack.values().forEach { pack ->
        rows += { PackSwitchRow(pack, engine, ru, isDark) }
    }
    if (service.isAvailable) {
        rows += { ContributionSwitchRow(service, ru, isDark) }
        rows += { ContributionPreviewRow(service, ru, isDark) }
    }
    IosListGroup(
        isDark = isDark,
        header = if (ru) "Умные модели" else "Smart models",
        footer = if (ru) {
            "Модели работают на телефоне. Названия из вашей коллекции никуда не отправляются."
        } else {
            "The models run on your phone. Titles from your collection are never sent anywhere."
        },
        rows = rows,
    )
}

@Composable
private fun PackSwitchRow(pack: MicroMlEngine.Pack, engine: MicroMlEngine, ru: Boolean, isDark: Boolean) {
    var on by remember(pack) { mutableStateOf(engine.isSwitchedOn(pack)) }
    IosRow(
        title = when (pack) {
            MicroMlEngine.Pack.IDENTITY -> if (ru) "Распознавание названий" else "Title matching"
            MicroMlEngine.Pack.CATALOG -> if (ru) "Разбор файлов и жанров" else "Files and genres"
            MicroMlEngine.Pack.EXPERIENCE -> if (ru) "Рекомендации по вкусу" else "Taste recommendations"
        },
        subtitle = when (pack) {
            MicroMlEngine.Pack.IDENTITY ->
                if (ru) "Тот же тайтл или нет, порядок вариантов в поиске" else "Same title or not, the order of search candidates"
            MicroMlEngine.Pack.CATALOG ->
                if (ru) "Имена файлов и папок аудиокниг, язык названия, жанры и заглушки вместо обложек" else "Audiobook file and folder names, title language, genres and stock images in place of covers"
            MicroMlEngine.Pack.EXPERIENCE ->
                if (ru) "Связи жанров и порядок рекомендаций, который учится на ваших оценках" else "Genre links and a recommendation order that learns from your ratings"
        },
        isDark = isDark,
        icon = Icons.Filled.AutoFixHigh,
        iconBackground = BrandOrange,
        trailing = {
            IosSwitch(checked = on, onCheckedChange = { value -> engine.setSwitchedOn(pack, value); on = value })
        },
    )
}

@Composable
private fun ContributionSwitchRow(service: ContributionService, ru: Boolean, isDark: Boolean) {
    var enabled by remember { mutableStateOf(service.isEnabled()) }
    var asking by remember { mutableStateOf(false) }
    IosRow(
        title = if (ru) "Помогать улучшать Vetro" else "Help improve Vetro",
        subtitle = if (enabled) {
            if (ru) "Раз в сутки уходят анонимные данные о работе моделей и найденные книги" else "Once a day, anonymous model data and found books are sent"
        } else {
            if (ru) "Выключено: ничего не копится и не отправляется" else "Off: nothing is collected or sent"
        },
        isDark = isDark,
        icon = Icons.Filled.AutoFixHigh,
        iconBackground = BrandOrange,
        trailing = {
            IosSwitch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        asking = true // включится только после «ОК» в окне согласия
                    } else {
                        service.setEnabled(false)
                        enabled = false
                    }
                },
            )
        },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(if (ru) "Сбор данных" else "Data collection") },
            text = {
                Text(
                    if (ru) {
                        "Приложение будет анонимно собирать данные о своей работе и результаты поиска, в том числе книги, " +
                            "найденные на сторонних сайтах, чтобы улучшать работу моделей и каталога. Ваши запросы и коллекция " +
                            "не отправляются. Нажимая «ОК», вы соглашаетесь с этим. Отключить сбор можно в любой момент."
                    } else {
                        "The app will anonymously collect data about how it works and search results, including books found " +
                            "on third-party sites, to improve the models and the catalogue. Your queries and your collection " +
                            "are not sent. By tapping OK you agree to this. You can turn it off at any time."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    service.setEnabled(true)
                    enabled = service.isEnabled()
                    asking = false
                }) { Text(if (ru) "ОК" else "OK") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(if (ru) "Отмена" else "Cancel") } },
        )
    }
}

/** Показывает точный текст следующей отправки: и числа для моделей, и записи о книгах. */
@Composable
private fun ContributionPreviewRow(service: ContributionService, ru: Boolean, isDark: Boolean) {
    var text by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    IosRow(
        title = if (ru) "Что будет отправлено" else "What will be sent",
        subtitle = if (ru) "Точный текст следующей отправки" else "The exact text of the next upload",
        isDark = isDark,
        icon = Icons.Filled.AutoFixHigh,
        iconBackground = BrandOrange,
        showChevron = true,
        onClick = {
            scope.launch {
                text = if (!service.isEnabled()) {
                    if (ru) "Сбор выключен: ничего не отправляется." else "Collection is off: nothing is sent."
                } else {
                    service.preview().ifBlank { if (ru) "Пока нечего отправлять." else "Nothing to send yet." }
                }
            }
        },
    )
    text?.let { shown ->
        AlertDialog(
            onDismissRequest = { text = null },
            confirmButton = { TextButton(onClick = { text = null }) { Text(if (ru) "Закрыть" else "Close") } },
            title = { Text(if (ru) "Следующая отправка" else "Next upload") },
            text = { Text(shown, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}
