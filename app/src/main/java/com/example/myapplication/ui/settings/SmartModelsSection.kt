package com.example.myapplication.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.microml.MicroMlEngine
import com.example.myapplication.microml.contribution.ContributionService
import com.example.myapplication.ui.settings.help.SettingHelpIds
import com.example.myapplication.ui.shared.components.IosListGroup
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.components.SettingHelpBinding
import com.example.myapplication.ui.shared.theme.BrandOrange
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Раздел настроек «Умные модели»: локальный ИИ одним пунктом (один тумблер на все три модели на телефоне)
 * и добровольный сбор данных. Сбор выключен, пока пользователь не нажмёт «ОК» в окне согласия; в сборках
 * без подписи автора (F-Droid, пересборки) строки сбора нет. Что именно уйдёт при следующей отправке,
 * смотрится в описании сбора по удержанию строки ([ContributionPreviewBlock]).
 */
@Composable
internal fun SmartModelsGroup(ru: Boolean, isDark: Boolean) {
    val engine = koinInject<MicroMlEngine>()
    val service = koinInject<ContributionService>()
    val rows = mutableListOf<@Composable () -> Unit>()

    rows += { LocalAiRow(engine, ru, isDark) }
    if (service.isAvailable) {
        rows += { ContributionSwitchRow(service, ru, isDark) }
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

/** Один пункт на все три модели: включён, если включена хотя бы одна; переключение меняет все сразу. */
@Composable
private fun LocalAiRow(engine: MicroMlEngine, ru: Boolean, isDark: Boolean) {
    var on by remember { mutableStateOf(MicroMlEngine.Pack.values().any { engine.isSwitchedOn(it) }) }
    val apply: (Boolean) -> Unit = { value ->
        MicroMlEngine.Pack.values().forEach { engine.setSwitchedOn(it, value) }
        on = value
    }
    IosRow(
        title = if (ru) "Локальный ИИ" else "Local AI",
        subtitle = if (ru) {
            "Распознавание названий, разбор файлов и жанров, рекомендации по вкусу"
        } else {
            "Title matching, files and genres, taste recommendations"
        },
        isDark = isDark,
        icon = Icons.Filled.AutoFixHigh,
        iconBackground = BrandOrange,
        trailing = { IosSwitch(checked = on, onCheckedChange = apply) },
        help = SettingHelpBinding(SettingHelpIds.LOCAL_AI, checked = on, onToggle = apply),
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
        // Описание по удержанию; сам выключатель в нём не дублируется: включение идёт через окно согласия.
        help = SettingHelpBinding(SettingHelpIds.CONTRIBUTION),
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

/**
 * «Что будет отправлено»: точный текст следующей отправки (числа для моделей и записи о книгах). Живёт
 * внутри описания «Помогать улучшать Vetro», а не отдельной строкой настроек.
 */
@Composable
internal fun ContributionPreviewBlock(ru: Boolean) {
    val service = koinInject<ContributionService>()
    var text by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = if (ru) "Что будет отправлено" else "What will be sent",
            color = onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        val shown = text
        if (shown == null) {
            Text(
                text = if (ru) "Показать точный текст следующей отправки" else "Show the exact text of the next upload",
                color = BrandOrange,
                fontSize = 14.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        scope.launch {
                            text = if (!service.isEnabled()) {
                                if (ru) "Сбор выключен: ничего не отправляется." else "Collection is off: nothing is sent."
                            } else {
                                service.preview().ifBlank { if (ru) "Пока нечего отправлять." else "Nothing to send yet." }
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
            )
        } else {
            Text(
                text = shown,
                color = onSurface.copy(alpha = 0.7f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(onSurface.copy(alpha = 0.06f))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState(), flingBehavior = com.example.myapplication.ui.shared.theme.IosScroll.flingBehavior()),
            )
        }
    }
}
