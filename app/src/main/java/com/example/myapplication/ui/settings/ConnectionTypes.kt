package com.example.myapplication.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.media.source.PlaybackSourceKind
import com.example.myapplication.media.source.movieseries.custom.CustomSourceFamily
import com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape

/** Строка на двух языках: панель знает только ru/en, новый язык — это новое поле здесь. */
internal class Localized(val ru: String, val en: String) {
    fun pick(isRu: Boolean): String = if (isRu) ru else en
}

/** Всё, что нужно странице типа подключения: состояние панели, язык и действия. */
internal class ConnectionHost(
    val state: PlaybackSourcesSettingsUiState,
    val ru: Boolean,
    val viewModel: PlaybackSourcesSettingsViewModel,
)

/**
 * Один способ подключить источник = одна карточка на главной панели и одна страница под ней.
 *
 * VETRO-TAIL[connection-type]
 *
 * Новый способ подключения (ещё один формат аддонов, подборки сообщества, локальный индекс) — это
 * один объект [ConnectionType] и одна строка в [ConnectionTypes]: карточка, переход, кнопка «назад» и
 * статус на карточке появятся сами. Страница сама решает, что показывать; общие куски
 * ([SectionLabel], [SourceSummaryRow], список установленных) лежат рядом.
 *
 * Условие для нового типа: он обязан работать по протоколу или описанию (JSON, HTTP), а не
 * подгружать чужой исполняемый код в приложение — так устроены все текущие типы, см.
 * `docs/agents/tails.md`.
 */
internal class ConnectionType(
    val id: String,
    val icon: ImageVector,
    val title: Localized,
    val summary: Localized,
    /** Короткий статус на карточке («Подключено: 2»); null — ничего не показывать. */
    val status: (PlaybackSourcesSettingsUiState, Boolean) -> String?,
    val page: @Composable (ConnectionHost) -> Unit,
)

private val SELF_HOSTED_VIDEO = listOf(PlaybackSourceKind.JELLYFIN, PlaybackSourceKind.EMBY, PlaybackSourceKind.WEBDAV)
private val SELF_HOSTED_AUDIO = listOf(PlaybackSourceKind.SUBSONIC, PlaybackSourceKind.AUDIOBOOKSHELF)

private val SelfHosted = ConnectionType(
    id = "self-hosted",
    icon = Icons.Rounded.Dns,
    title = Localized("Свой сервер", "Your own server"),
    summary = Localized(
        "Jellyfin, Emby, Nextcloud, Navidrome — ваша медиатека дома или в облаке",
        "Jellyfin, Emby, Nextcloud, Navidrome — your library at home or in the cloud",
    ),
    status = { state, ru ->
        val n = state.sources.count { it.configured && it.kind in SELF_HOSTED_VIDEO + SELF_HOSTED_AUDIO }
        if (n > 0) (if (ru) "Подключено: $n" else "Connected: $n") else null
    },
    page = { host -> SelfHostedPage(host) },
)

private val Subtitles = ConnectionType(
    id = "subtitles",
    icon = Icons.Rounded.Subtitles,
    title = Localized("Субтитры", "Subtitles"),
    summary = Localized(
        "Аккаунт OpenSubtitles и распознавание речи на телефоне",
        "An OpenSubtitles account and speech recognition on the phone",
    ),
    status = { state, ru ->
        if (state.sources.any { it.kind == PlaybackSourceKind.OPENSUBTITLES && it.configured }) {
            if (ru) "Подключено" else "Connected"
        } else {
            null
        }
    },
    page = { host -> SubtitlesPage(host) },
)

private val Stremio = ConnectionType(
    id = "stremio",
    icon = Icons.Rounded.Link,
    title = Localized("Аддон Stremio", "Stremio addon"),
    summary = Localized(
        "Ссылка на аддон: Vetro спрашивает его по протоколу, код аддона не запускается",
        "A link to an addon: Vetro asks it over the protocol, the addon's code is never run",
    ),
    status = { state, ru ->
        val n = state.customSources.count { it.family == CustomSourceFamily.STREMIO }
        if (n > 0) (if (ru) "Аддонов: $n" else "Addons: $n") else null
    },
    page = { host -> StremioPage(host) },
)

private val VetroPackage = ConnectionType(
    id = "vetro-package",
    icon = Icons.Rounded.Extension,
    title = Localized("Пакет Vetro", "Vetro package"),
    summary = Localized(
        "Файл .vetro или ссылка: описание источника, который проверяется перед установкой",
        "A .vetro file or link: a source description that is checked before installing",
    ),
    status = { state, ru ->
        val n = state.customSources.count { it.family == CustomSourceFamily.VETRO }
        if (n > 0) (if (ru) "Источников: $n" else "Sources: $n") else null
    },
    page = { host -> VetroPackagePage(host) },
)

/**
 * Карточки главной панели в порядке показа.
 *
 * VETRO-TAIL[connection-type]: новый способ подключения — объект выше и строка здесь.
 */
internal val ConnectionTypes: List<ConnectionType> = listOf(
    SelfHosted,
    Subtitles,
    Stremio,
    VetroPackage,
)

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
    )
}

@Composable
private fun SelfHostedPage(host: ConnectionHost) {
    val ru = host.ru
    Hint(
        if (ru) {
            "Видео и аудио берутся прямо с вашего сервера. Выберите, какой подключить."
        } else {
            "Video and audio come straight from your server. Pick the one to connect."
        },
    )
    SectionLabel(if (ru) "Видео" else "Video")
    SELF_HOSTED_VIDEO.forEach { SelfHostedRow(host, it) }
    SectionLabel(if (ru) "Аудио" else "Audio")
    SELF_HOSTED_AUDIO.forEach { SelfHostedRow(host, it) }
}

@Composable
private fun SelfHostedRow(host: ConnectionHost, kind: PlaybackSourceKind) {
    val summary = host.state.sources.firstOrNull { it.kind == kind } ?: return
    SourceSummaryRow(source = summary, ru = host.ru, onClick = { host.viewModel.openEditor(kind) })
}

@Composable
private fun SubtitlesPage(host: ConnectionHost) {
    val ru = host.ru
    Hint(
        if (ru) {
            "Субтитры можно скачивать с OpenSubtitles или распознавать речь прямо на телефоне."
        } else {
            "Subtitles can be downloaded from OpenSubtitles or recognized from speech on the phone."
        },
    )
    SectionLabel(if (ru) "Аккаунт" else "Account")
    host.state.sources.firstOrNull { it.kind == PlaybackSourceKind.OPENSUBTITLES }?.let { summary ->
        SourceSummaryRow(
            source = summary,
            ru = ru,
            onClick = { host.viewModel.openEditor(PlaybackSourceKind.OPENSUBTITLES) },
        )
    }
    WhisperModelsSection(ru = ru)
}

@Composable
private fun StremioPage(host: ConnectionHost) {
    val ru = host.ru
    val state = host.state
    var link by rememberSaveable { mutableStateOf("") }
    Hint(
        if (ru) {
            "Аддон Stremio — это адрес с файлом manifest.json. Вставьте ссылку на аддон (подойдёт и stremio://…). " +
                "Vetro запрашивает у аддона потоки по протоколу и ничего из его кода не запускает. Нужен https, " +
                "аддоны с торрентами и с настройкой на своём сайте не принимаются."
        } else {
            "A Stremio addon is an address with a manifest.json file. Paste the addon link (stremio://… works too). " +
                "Vetro asks the addon for streams over the protocol and never runs its code. https is required; " +
                "torrent addons and addons that need setup on their own site are refused."
        },
    )
    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        singleLine = true,
        label = { Text(if (ru) "Ссылка на аддон" else "Addon link") },
        isError = state.installError != null,
        modifier = Modifier.fillMaxWidth(),
    )
    state.installError?.let { reason ->
        Text(text = reason, color = MaterialTheme.colorScheme.error, fontFamily = SnProFamily)
    }
    Button(
        onClick = {
            host.viewModel.installStremio(link)
            link = ""
        },
        enabled = !state.isInstalling && link.isNotBlank(),
        shape = SquircleShape(18.dp),
    ) {
        if (state.isInstalling) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
        } else {
            Text(if (ru) "Добавить аддон" else "Add addon", fontFamily = SnProFamily)
        }
    }
    InstalledList(host, CustomSourceFamily.STREMIO)
}

@Composable
private fun VetroPackagePage(host: ConnectionHost) {
    val ru = host.ru
    val state = host.state
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val installer = remember { CustomSourceInstaller() }
    // Ошибка самой кнопки .vetro (не тот файл) — до установщика такой файл не доходит.
    var vetroFileError by rememberSaveable { mutableStateOf<String?>(null) }
    // У .vetro нет MIME-типа, по фильтру «application/json» пикер его бы не показал: берём любой
    // файл и проверяем расширение и содержимое уже после выбора.
    val pickVetroFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = displayName(context, uri)
        if (name != null && !name.endsWith(".vetro", ignoreCase = true) &&
            !name.endsWith(".vetro-source", ignoreCase = true)
        ) {
            vetroFileError = if (ru) "Это не файл .vetro: $name" else "Not a .vetro file: $name"
            return@rememberLauncherForActivityResult
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readNBytesCompat(CustomSourceInstaller.MAX_BUNDLE_BYTES + 1).toString(Charsets.UTF_8)
            }
        }.getOrNull()?.removePrefix("﻿")
        // Здесь — только «это вообще можно импортировать»: источник, подборка или список ссылок. Что
        // именно внутри и годится ли оно, решает установщик — он же называет точную причину отказа.
        vetroFileError = when {
            text.isNullOrBlank() -> if (ru) "Файл не прочитался" else "Could not read the file"
            text.length > CustomSourceInstaller.MAX_BUNDLE_BYTES -> if (ru) "Файл больше 2 МБ" else "The file is larger than 2 MB"
            !installer.looksImportable(text) -> if (ru) {
                "Файл не JSON: ожидается источник { … }, подборка [ … ] или список ссылок, по одной на строку"
            } else {
                "Not a JSON file: expected a source { … }, a bundle [ … ] or a list of links, one per line"
            }
            else -> null
        }
        if (vetroFileError == null && text != null) host.viewModel.installCustomSource(text)
    }
    // Описание из любого файла: читается целиком (он небольшой, лимит проверит установщик).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readNBytesCompat(CustomSourceInstaller.MAX_BUNDLE_BYTES + 1).toString(Charsets.UTF_8)
            }
        }.getOrNull()
        if (!text.isNullOrBlank()) host.viewModel.installCustomSource(text)
    }

    Hint(
        if (ru) {
            "Пакет — это описание источника в одном файле. Перед установкой покажем, что источник умеет и к каким сайтам обращается."
        } else {
            "A package is one file describing a source. Before installing you'll see what it can do and which sites it talks to."
        },
    )
    Button(
        onClick = {
            vetroFileError = null
            pickVetroFile.launch(arrayOf("*/*"))
        },
        enabled = !state.isInstalling,
        shape = SquircleShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (ru) "Импортировать .vetro" else "Import .vetro", fontFamily = SnProFamily)
    }
    vetroFileError?.let { reason ->
        Text(text = reason, color = MaterialTheme.colorScheme.error, fontFamily = SnProFamily)
    }
    val uriHandler = LocalUriHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { runCatching { uriHandler.openUri(COMMUNITY_SOURCES_URL) } },
            shape = SquircleShape(18.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("Community sources", fontFamily = SnProFamily, maxLines = 1)
        }
        OutlinedButton(
            onClick = { runCatching { uriHandler.openUri(SUBMIT_SOURCE_URL) } },
            shape = SquircleShape(18.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (ru) "Предложить источник" else "Submit a source", fontFamily = SnProFamily, maxLines = 1)
        }
    }

    SectionLabel(if (ru) "Добавить по ссылке" else "Add by link")
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = false,
        label = { Text(if (ru) "Ссылка или конфигурация" else "Link or configuration") },
        isError = state.installError != null,
        modifier = Modifier.fillMaxWidth(),
    )
    state.installError?.let { reason ->
        Text(text = reason, color = MaterialTheme.colorScheme.error, fontFamily = SnProFamily)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                host.viewModel.installCustomSource(input)
                input = ""
            },
            enabled = !state.isInstalling && input.isNotBlank(),
            shape = SquircleShape(18.dp),
        ) {
            if (state.isInstalling) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (ru) "Добавить" else "Add", fontFamily = SnProFamily)
            }
        }
        OutlinedButton(
            onClick = { pickFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            enabled = !state.isInstalling,
            shape = SquircleShape(18.dp),
        ) {
            Text(if (ru) "Выбрать файл" else "Pick a file", fontFamily = SnProFamily)
        }
    }
    InstalledList(host, CustomSourceFamily.VETRO)
}

/** Установленные источники одного семейства: включить, обновить, удалить. */
@Composable
private fun InstalledList(host: ConnectionHost, family: CustomSourceFamily) {
    val list = host.state.customSources.filter { it.family == family }
    if (list.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    SectionLabel(if (host.ru) "Подключено" else "Connected")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { source ->
            CustomSourceRow(
                source = source,
                ru = host.ru,
                onToggle = { enabled -> host.viewModel.setCustomSourceEnabled(source.key, enabled) },
                onRefresh = { host.viewModel.refreshCustomSource(source.key) },
                onRemove = { host.viewModel.removeCustomSource(source.key) },
            )
        }
    }
}

/**
 * Пакеты, ждущие подтверждения. Показываются над любой страницей: подборка могла прийти откуда
 * угодно, а ничего не ставится, пока пользователь не нажал «Установить».
 */
@Composable
internal fun PackageReviewSection(host: ConnectionHost) {
    val ru = host.ru
    val state = host.state
    val previews = state.packagePreviews
    if (previews.isEmpty()) return
    if (previews.size > 1) {
        SectionLabel(if (ru) "Пакетов на проверке: ${previews.size}" else "Packages to review: ${previews.size}")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = host.viewModel::confirmAllPackages,
                enabled = !state.isInstalling,
                shape = SquircleShape(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (ru) "Установить все" else "Install all", fontFamily = SnProFamily)
            }
            OutlinedButton(
                onClick = host.viewModel::dismissAllPackages,
                shape = SquircleShape(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (ru) "Отменить все" else "Dismiss all", fontFamily = SnProFamily)
            }
        }
    }
    previews.forEach { preview ->
        PackageReviewCard(
            preview = preview,
            ru = ru,
            onConfirm = { host.viewModel.confirmPackage(preview) },
            onDismiss = { host.viewModel.dismissPackage(preview) },
        )
    }
}
