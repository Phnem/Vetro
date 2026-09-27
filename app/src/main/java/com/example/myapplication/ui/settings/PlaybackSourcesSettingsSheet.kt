package com.example.myapplication.ui.settings

import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import org.koin.compose.koinInject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller
import com.example.myapplication.media.source.movieseries.custom.PackagePreview
import com.example.myapplication.media.source.sdk.PackageCapability
import com.example.myapplication.media.source.sdk.PackageMediaType
import com.example.myapplication.media.source.sdk.PackageOrigin
import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.media.source.PlaybackSourceConfigurationSummary
import com.example.myapplication.media.source.PlaybackSourceKind
import com.example.myapplication.media.source.movieseries.custom.CustomSourceSummary
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import org.koin.androidx.compose.koinViewModel

@Composable
fun PlaybackSourcesSettingsSheet(
    language: AppLanguage,
    onDismiss: () -> Unit,
    viewModel: PlaybackSourcesSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.loadCustomSources()
        onDispose { viewModel.closeEditor() }
    }
    val ru = language == AppLanguage.RU
    val editor = state.editor
    // Цвет содержимого задаём явно. Хост листа его не объявляет, а умолчание `LocalContentColor` —
    // чёрный: на тёмной теме заголовок и названия источников сливались с фоном. Полагаться на то,
    // что цвет «придёт сверху», нельзя — сюда он не приходит.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editor != null) {
                TextButton(onClick = viewModel::closeEditor) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(if (ru) "Назад" else "Back", fontFamily = SnProFamily)
                }
            }
            Text(
                text = if (ru) "Источники видео" else "Video sources",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                viewModel.closeEditor()
                onDismiss()
            }) {
                Text(if (ru) "Готово" else "Done", fontFamily = SnProFamily)
            }
        }

        if (editor == null) {
            Text(
                text = if (ru) {
                    "Подключите свою медиатеку. Пароли и токены хранятся только в зашифрованном хранилище устройства."
                } else {
                    "Connect your own media library. Passwords and tokens stay in encrypted device storage."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
            )
            state.sources.forEach { source ->
                SourceSummaryRow(
                    source = source,
                    ru = ru,
                    onClick = { viewModel.openEditor(source.kind) },
                )
            }

            CustomSourcesSection(
                sources = state.customSources,
                isInstalling = state.isInstalling,
                installError = state.installError,
                preview = state.packagePreview,
                ru = ru,
                onInstall = viewModel::installCustomSource,
                onConfirmPackage = viewModel::confirmPackage,
                onDismissPackage = viewModel::dismissPackage,
                onToggle = viewModel::setCustomSourceEnabled,
                onRefresh = viewModel::refreshCustomSource,
                onRemove = viewModel::removeCustomSource,
            )

            WhisperModelsSection(ru = ru)
        } else {
            SourceEditor(
                editor = editor,
                configured = state.sources.firstOrNull { it.kind == editor.kind }?.configured == true,
                isTesting = state.isTesting,
                ru = ru,
                onUpdate = viewModel::updateEditor,
                onTest = viewModel::testEditorConnection,
                onSave = viewModel::saveEditor,
                onRemove = { viewModel.remove(editor.kind) },
            )
        }

        state.message?.let { message ->
            Text(
                text = message.text(ru),
                color = if (message == PlaybackSourceSettingsMessage.CONNECTION_FAILED ||
                    message == PlaybackSourceSettingsMessage.INVALID_CONFIGURATION ||
                    message == PlaybackSourceSettingsMessage.SECRET_REQUIRED
                ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontFamily = SnProFamily,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    }
}

@Composable
private fun SourceSummaryRow(
    source: PlaybackSourceConfigurationSummary,
    ru: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = SquircleShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(source.kind.title, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
                Text(
                    if (source.configured) {
                        if (ru) "Подключено" else "Configured"
                    } else {
                        if (ru) "Не настроено" else "Not configured"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SourceEditor(
    editor: PlaybackSourceEditorState,
    configured: Boolean,
    isTesting: Boolean,
    ru: Boolean,
    onUpdate: ((PlaybackSourceEditorState) -> PlaybackSourceEditorState) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Boolean,
    onRemove: () -> Unit,
) {
    Text(
        editor.kind.title,
        style = MaterialTheme.typography.titleLarge,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
    )
    val account = editor.kind.account
    if (account == PlaybackSourceKind.OPENSUBTITLES.account) {
        Text(
            if (ru) {
                "Свой ключ API и аккаунт opensubtitles.com (ключ — в разделе API consumers на сайте). Субтитры скачиваются в счёт суточного лимита аккаунта; скачанные хранятся на устройстве и повторно лимит не тратят."
            } else {
                "Your own API key and opensubtitles.com account (the key is under API consumers on the site). Downloads count against the account's daily limit; downloaded files stay on the device and are not charged again."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = SnProFamily,
        )
    }
    if (account == null || account.needsServer) {
        OutlinedTextField(
            value = editor.baseUrl,
            onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (account != null) {
        OutlinedTextField(
            value = editor.username,
            onValueChange = { value -> onUpdate { it.copy(username = value) } },
            label = { Text(if (ru) "Логин" else "Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (editor.kind == PlaybackSourceKind.WEBDAV) {
        OutlinedTextField(
            value = editor.rootPath,
            onValueChange = { value -> onUpdate { it.copy(rootPath = value) } },
            label = { Text(if (ru) "Папка медиатеки" else "Library folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editor.username,
            onValueChange = { value -> onUpdate { it.copy(username = value) } },
            label = { Text(if (ru) "Имя пользователя" else "Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = editor.userId,
            onValueChange = { value -> onUpdate { it.copy(userId = value) } },
            label = { Text("User ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = editor.secret,
        onValueChange = { value -> onUpdate { it.copy(secret = value) } },
        label = {
            Text(
                when {
                    account != null -> if (ru) "Пароль" else "Password"
                    editor.kind == PlaybackSourceKind.WEBDAV -> if (ru) "Пароль приложения" else "App password"
                    else -> "Access token"
                }
            )
        },
        placeholder = {
            if (editor.hasStoredSecret) Text(if (ru) "Сохранён — оставьте пустым" else "Saved — leave blank")
        },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (account?.needsApiKey == true) {
        OutlinedTextField(
            value = editor.apiKey,
            onValueChange = { value -> onUpdate { it.copy(apiKey = value) } },
            label = { Text(if (ru) "Ключ API" else "API key") },
            placeholder = {
                if (editor.hasStoredApiKey) Text(if (ru) "Сохранён — оставьте пустым" else "Saved — leave blank")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (account == null) {
        ToggleRow(
            title = if (ru) "Разрешить скачивание" else "Allow downloads",
            checked = editor.downloadAllowed,
            onCheckedChange = { value -> onUpdate { it.copy(downloadAllowed = value) } },
        )
    }
    if (account == null || account.needsServer) {
        ToggleRow(
            title = if (ru) "Разрешить небезопасный HTTP" else "Allow insecure HTTP",
            checked = editor.allowInsecureHttp,
            onCheckedChange = { value -> onUpdate { it.copy(allowInsecureHttp = value) } },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = onTest, enabled = !isTesting, modifier = Modifier.weight(1f)) {
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
            } else {
                Text(if (ru) "Проверить" else "Test", fontFamily = SnProFamily)
            }
        }
        Button(onClick = { onSave() }, modifier = Modifier.weight(1f)) {
            Text(if (ru) "Сохранить" else "Save", fontFamily = SnProFamily)
        }
    }
    if (configured) {
        OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (ru) "Удалить подключение" else "Remove connection",
                color = MaterialTheme.colorScheme.error,
                fontFamily = SnProFamily,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontFamily = SnProFamily)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Add and manage sources the user installed themselves.
 *
 * One field takes either a link or a pasted definition: making the user first declare which one they
 * have is a question the app can answer by looking at the text.
 */
@Composable
private fun CustomSourcesSection(
    sources: List<CustomSourceSummary>,
    isInstalling: Boolean,
    installError: String?,
    preview: PackagePreview?,
    ru: Boolean,
    onInstall: (String) -> Unit,
    onConfirmPackage: () -> Unit,
    onDismissPackage: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // Пакет из файла: читается целиком (он небольшой, лимит проверит установщик) и идёт тем же путём.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readNBytesCompat(CustomSourceInstaller.MAX_PACKAGE_BYTES + 1).toString(Charsets.UTF_8)
            }
        }.getOrNull()
        if (!text.isNullOrBlank()) onInstall(text)
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = if (ru) "Добавить источник" else "Add a source",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = if (ru) {
            "Вставьте ссылку на конфигурацию источника или сам файл конфигурации, либо выберите файл. " +
                "Поддерживаются пакет Vetro, манифест Vetro и аддон Stremio."
        } else {
            "Paste a link to a source configuration or the configuration itself, or pick a file. " +
                "Vetro packages, Vetro manifests and Stremio addons are supported."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = false,
        label = { Text(if (ru) "Ссылка или конфигурация" else "Link or configuration") },
        isError = installError != null,
        modifier = Modifier.fillMaxWidth(),
    )
    installError?.let { reason ->
        Text(
            text = reason,
            color = MaterialTheme.colorScheme.error,
            fontFamily = SnProFamily,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                onInstall(input)
                input = ""
            },
            enabled = !isInstalling && input.isNotBlank(),
            shape = SquircleShape(18.dp),
        ) {
            if (isInstalling) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (ru) "Добавить" else "Add", fontFamily = SnProFamily)
            }
        }
        OutlinedButton(
            onClick = { pickFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            enabled = !isInstalling,
            shape = SquircleShape(18.dp),
        ) {
            Text(if (ru) "Выбрать файл" else "Pick a file", fontFamily = SnProFamily)
        }
    }
    preview?.let { PackageReviewCard(it, ru, onConfirm = onConfirmPackage, onDismiss = onDismissPackage) }

    if (sources.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (ru) "Мои источники" else "My sources",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
        )
        sources.forEach { source ->
            CustomSourceRow(
                source = source,
                ru = ru,
                onToggle = { enabled -> onToggle(source.key, enabled) },
                onRefresh = { onRefresh(source.key) },
                onRemove = { onRemove(source.key) },
            )
        }
    }
}

/**
 * Модели Whisper на устройстве: сколько места занимают и удаление. Скачивание — из меню «Субтитры»
 * плеера, где понятно, зачем модель нужна; здесь — только то, что уже лежит на телефоне.
 */
@Composable
private fun WhisperModelsSection(ru: Boolean) {
    val store = koinInject<WhisperModelStore>()
    val states by store.states.collectAsStateWithLifecycle()
    val present = WhisperModel.entries.filter { states[it] !is ModelState.Absent && states[it] != null }
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (ru) "Субтитры на устройстве" else "On-device subtitles",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = if (ru) {
            "Whisper распознаёт речь прямо на телефоне, звук никуда не отправляется. Модель скачивается из меню «Субтитры» в плеере."
        } else {
            "Whisper recognizes speech on the phone; audio never leaves it. The model is downloaded from the Subtitles menu in the player."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
    )
    if (present.isEmpty()) {
        Text(if (ru) "Модели не скачаны" else "No models downloaded", fontFamily = SnProFamily)
        return
    }
    present.forEach { model ->
        val state = states[model]
        Surface(shape = SquircleShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (ru) model.titleRu else model.titleEn) + " · " + "%.0f".format(model.sizeBytes / 1_000_000.0) + (if (ru) " МБ" else " MB"),
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when (state) {
                            is ModelState.Downloading -> (if (ru) "Загрузка " else "Downloading ") + "${state.percent}%"
                            ModelState.Verifying -> if (ru) "Проверка файла" else "Checking the file"
                            is ModelState.Failed -> if (ru) "Загрузка не завершена" else "Download incomplete"
                            else -> if (ru) "Готова" else "Ready"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                    )
                }
                TextButton(onClick = { store.delete(model) }) {
                    Text(if (ru) "Удалить" else "Delete", color = MaterialTheme.colorScheme.error, fontFamily = SnProFamily)
                }
            }
        }
    }
}

/**
 * Экран возможностей пакета перед установкой: что умеет, с какими хостами говорит, нужен ли ключ,
 * кем подписан. Ничего не установлено, пока пользователь не нажал «Установить».
 */
@Composable
private fun PackageReviewCard(
    preview: PackagePreview,
    ru: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = SquircleShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${preview.name} ${preview.version}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            )
            preview.replacesVersion?.let {
                Text(if (ru) "Обновление с версии $it" else "Update from version $it", color = muted, fontFamily = SnProFamily)
            }
            preview.author?.let { ReviewLine(if (ru) "Автор" else "Author", it) }
            ReviewLine(if (ru) "Контент" else "Content", preview.mediaTypes.joinToString { mediaTypeLabel(it, ru) })
            ReviewLine(if (ru) "Умеет" else "Can", preview.capabilities.joinToString { capabilityLabel(it, ru) })
            ReviewLine(if (ru) "Обращается к" else "Talks to", preview.hosts.joinToString())
            ReviewLine(
                if (ru) "Ключ" else "Key",
                if (preview.needsKey) (if (ru) "нужен ваш ключ" else "needs your key") else (if (ru) "не нужен" else "not needed"),
            )
            val signed = preview.origin as? PackageOrigin.Signed
            ReviewLine(
                if (ru) "Подпись" else "Signature",
                if (signed != null) {
                    (if (ru) "ключ " else "key ") + signed.publicKey.take(12) + "…"
                } else {
                    if (ru) "нет — обновления не будут проверяться по ключу автора" else "none — updates cannot be checked against an author key"
                },
            )
            ReviewLine("SHA-256", preview.sha256.take(16) + "…")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConfirm, shape = SquircleShape(18.dp)) {
                    Text(if (ru) "Установить" else "Install", fontFamily = SnProFamily)
                }
                TextButton(onClick = onDismiss) {
                    Text(if (ru) "Отмена" else "Cancel", fontFamily = SnProFamily)
                }
            }
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = SnProFamily,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value, fontFamily = SnProFamily, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun mediaTypeLabel(type: PackageMediaType, ru: Boolean): String = when (type) {
    PackageMediaType.MOVIE -> if (ru) "фильмы" else "movies"
    PackageMediaType.SERIES -> if (ru) "сериалы" else "series"
    PackageMediaType.ANIME -> if (ru) "аниме" else "anime"
    PackageMediaType.MANGA -> if (ru) "манга" else "manga"
    PackageMediaType.AUDIOBOOK -> if (ru) "аудиокниги" else "audiobooks"
}

private fun capabilityLabel(capability: PackageCapability, ru: Boolean): String = when (capability) {
    PackageCapability.SEARCH -> if (ru) "поиск по названию" else "title search"
    PackageCapability.SEARCH_BY_EXTERNAL_ID -> if (ru) "поиск по id" else "id lookup"
    PackageCapability.UNITS -> if (ru) "серии и главы" else "episodes and chapters"
    PackageCapability.STREAMS -> if (ru) "видео" else "video"
    PackageCapability.SUBTITLES -> if (ru) "субтитры" else "subtitles"
    PackageCapability.VARIANTS -> if (ru) "озвучки" else "audio variants"
    PackageCapability.PAGES -> if (ru) "страницы манги" else "manga pages"
    PackageCapability.AUDIO -> if (ru) "аудио" else "audio"
    PackageCapability.DOWNLOAD -> if (ru) "скачивание" else "downloads"
}

/** `InputStream.readNBytes` появился только в Java 11 / API 33. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun CustomSourceRow(
    source: CustomSourceSummary,
    ru: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(shape = SquircleShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.displayName, fontFamily = SnProFamily, fontWeight = FontWeight.Medium)
                    Text(
                        text = source.kindLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                    )
                }
                // A disabled source builds no provider at all, so it cannot reach the network.
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.sourceUrl != null) {
                    OutlinedButton(onClick = onRefresh, shape = SquircleShape(18.dp)) {
                        Text(if (ru) "Обновить" else "Refresh", fontFamily = SnProFamily)
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(
                        text = if (ru) "Удалить" else "Remove",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = SnProFamily,
                    )
                }
            }
        }
    }
}

private val PlaybackSourceKind.title: String
    get() = when (this) {
        PlaybackSourceKind.WEBDAV -> "WebDAV / Nextcloud"
        PlaybackSourceKind.JELLYFIN -> "Jellyfin"
        PlaybackSourceKind.EMBY -> "Emby"
        PlaybackSourceKind.OPENSUBTITLES -> "OpenSubtitles"
    }

private fun PlaybackSourceSettingsMessage.text(ru: Boolean): String = when (this) {
    PlaybackSourceSettingsMessage.SAVED -> if (ru) "Настройки сохранены" else "Settings saved"
    PlaybackSourceSettingsMessage.REMOVED -> if (ru) "Подключение удалено" else "Connection removed"
    PlaybackSourceSettingsMessage.CONNECTION_OK -> if (ru) "Соединение работает" else "Connection works"
    PlaybackSourceSettingsMessage.CONNECTION_FAILED -> if (ru) "Не удалось подключиться" else "Connection failed"
    PlaybackSourceSettingsMessage.SECRET_REQUIRED ->
        if (ru) "Введите новый пароль или токен для этого сервера" else "Enter a new password or token for this server"
    PlaybackSourceSettingsMessage.INVALID_CONFIGURATION ->
        if (ru) "Проверьте адрес и обязательные поля" else "Check the address and required fields"
    PlaybackSourceSettingsMessage.CUSTOM_SOURCE_INSTALLED ->
        if (ru) "Источник добавлен" else "Source added"
    PlaybackSourceSettingsMessage.CUSTOM_SOURCE_REMOVED ->
        if (ru) "Источник удалён" else "Source removed"
    // The specific reason lives in `installError` and is shown next to the field, so this line only
    // has to say that nothing was saved.
    PlaybackSourceSettingsMessage.CUSTOM_SOURCE_REJECTED ->
        if (ru) "Источник не добавлен" else "Source was not added"
}
