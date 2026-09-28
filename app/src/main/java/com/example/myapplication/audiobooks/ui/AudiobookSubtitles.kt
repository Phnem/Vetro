package com.example.myapplication.audiobooks.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.text.BookAlignmentManager
import com.example.myapplication.audiobooks.text.BookTextState
import com.example.myapplication.audiobooks.text.DeviceSubtitleManager
import com.example.myapplication.audiobooks.text.DeviceSubtitleState
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Что показывать под обложкой: два независимых источника, не смешиваются. */
enum class AudiobookSubtitleMode { OFF, BOOK_TEXT, DEVICE }

/** Выбор субтитров для каждой озвучки: запоминается, книга открывается с тем же выбором. */
class AudiobookSubtitleModes(context: Context) {
    private val prefs = context.getSharedPreferences("audiobook_subtitles", Context.MODE_PRIVATE)
    private val _modes = MutableStateFlow(
        prefs.all.mapNotNull { (k, v) -> runCatching { VariantId(k) to AudiobookSubtitleMode.valueOf(v as String) }.getOrNull() }.toMap(),
    )
    val modes: StateFlow<Map<VariantId, AudiobookSubtitleMode>> = _modes.asStateFlow()

    fun set(variant: VariantId, mode: AudiobookSubtitleMode) {
        if (mode == AudiobookSubtitleMode.OFF) prefs.edit().remove(variant.value).apply() else prefs.edit().putString(variant.value, mode.name).apply()
        _modes.update { if (mode == AudiobookSubtitleMode.OFF) it - variant else it + (variant to mode) }
    }
}

internal data class SubtitleStrings(
    val title: String,
    val off: String,
    val bookText: String,
    val bookTextHint: String,
    val device: String,
    val deviceHint: String,
    val searching: String,
    val notFound: String,
    val mismatch: String,
    val needsModel: (String) -> String,
    val noSpace: String,
    val syncing: (String, Int) -> String,
    val pausedHot: (String, Int) -> String,
    val pausedLater: (String, Int) -> String,
    val ready: (String) -> String,
    val failed: String,
    val deviceNeedsModel: String,
    val deviceWorking: (Int) -> String,
    val devicePaused: (Int) -> String,
    val deviceReady: String,
    val chooseFile: String,
    val searchAgain: String,
    val downloadModel: (Int) -> String,
    val downloading: (Int) -> String,
    val syncingOverlay: String,
    val menuValue: (AudiobookSubtitleMode) -> String,
)

internal fun subtitleStrings(language: AppLanguage): SubtitleStrings = when (language) {
    AppLanguage.RU -> SubtitleStrings(
        title = "Субтитры",
        off = "Выкл",
        bookText = "Текст книги",
        bookTextHint = "Оригинальный текст, синхронный с чтецом",
        device = "Создать на устройстве",
        deviceHint = "Распознать речь (Whisper), если текста книги нет",
        searching = "Ищу текст…",
        notFound = "Текст не найден",
        mismatch = "Текст не совпадает с записью: другой перевод или редакция",
        needsModel = { "Найден: $it · нужна модель распознавания" },
        noSpace = "Мало места на телефоне",
        syncing = { s, p -> "$s · синхронизация $p %" },
        pausedHot = { s, p -> "$s · $p % · пауза: телефон нагрелся" },
        pausedLater = { s, p -> "$s · $p % · дальше — на зарядке" },
        ready = { "$it · готово" },
        failed = "Не получилось — попробуйте ещё раз",
        deviceNeedsModel = "Нужна модель распознавания",
        deviceWorking = { "Распознаю · $it %" },
        devicePaused = { "$it % · остальное — по мере прослушивания" },
        deviceReady = "Готово",
        chooseFile = "Выбрать файл книги",
        searchAgain = "Искать снова",
        downloadModel = { "Скачать модель · $it МБ" },
        downloading = { "Загрузка модели · $it %" },
        syncingOverlay = "Синхронизация текста…",
        menuValue = { when (it) { AudiobookSubtitleMode.OFF -> "Выкл"; AudiobookSubtitleMode.BOOK_TEXT -> "Текст книги"; AudiobookSubtitleMode.DEVICE -> "На устройстве" } },
    )
    AppLanguage.EN -> SubtitleStrings(
        title = "Subtitles",
        off = "Off",
        bookText = "Book text",
        bookTextHint = "The original text, in sync with the narrator",
        device = "Generate on device",
        deviceHint = "Recognise the speech (Whisper) when there is no book text",
        searching = "Looking for the text…",
        notFound = "Text not found",
        mismatch = "The text doesn't match the recording: another translation or edition",
        needsModel = { "Found: $it · a speech model is needed" },
        noSpace = "Not enough storage",
        syncing = { s, p -> "$s · syncing $p%" },
        pausedHot = { s, p -> "$s · $p% · paused: the phone is hot" },
        pausedLater = { s, p -> "$s · $p% · the rest while charging" },
        ready = { "$it · ready" },
        failed = "Something went wrong — try again",
        deviceNeedsModel = "A speech model is needed",
        deviceWorking = { "Recognising · $it%" },
        devicePaused = { "$it% · the rest as you listen" },
        deviceReady = "Ready",
        chooseFile = "Choose the book file",
        searchAgain = "Search again",
        downloadModel = { "Download model · $it MB" },
        downloading = { "Downloading model · $it%" },
        syncingOverlay = "Syncing the text…",
        menuValue = { when (it) { AudiobookSubtitleMode.OFF -> "Off"; AudiobookSubtitleMode.BOOK_TEXT -> "Book text"; AudiobookSubtitleMode.DEVICE -> "On device" } },
    )
}

private val Ink = Color.White
private val Muted = Color.White.copy(alpha = 0.6f)

/** Лист «Субтитры»: Выкл · Текст книги (с состоянием) · Создать на устройстве. */
@UnstableApi
@Composable
internal fun ColumnScope.SubtitlesSheetContent(
    variant: VariantId,
    narration: NarrationId?,
    strings: SubtitleStrings,
    position: () -> Long?,
) {
    val modes: AudiobookSubtitleModes = koinInject()
    val alignment: BookAlignmentManager = koinInject()
    val device: DeviceSubtitleManager = koinInject()
    val models: WhisperModelStore = koinInject()
    val scope = rememberCoroutineScope()
    val mode = modes.modes.collectAsState().value[variant] ?: AudiobookSubtitleMode.OFF
    val bookState = alignment.states.collectAsState().value[variant] ?: BookTextState.Idle
    val deviceState = device.states.collectAsState().value[variant] ?: DeviceSubtitleState.Idle
    val modelStates by models.states.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && narration != null) scope.launch {
            modes.set(variant, AudiobookSubtitleMode.BOOK_TEXT)
            alignment.useFile(variant, narration, uri, position)
        }
    }

    Text(strings.title, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    Spacer(Modifier.height(14.dp))
    OptionRow(strings.off, null, selected = mode == AudiobookSubtitleMode.OFF) { modes.set(variant, AudiobookSubtitleMode.OFF) }
    Spacer(Modifier.height(3.dp))

    // Текст книги — основной источник.
    val bookLine = when (val s = bookState) {
        BookTextState.Idle -> strings.bookTextHint
        BookTextState.Searching -> strings.searching
        BookTextState.NotFound -> strings.notFound
        is BookTextState.Mismatch -> strings.mismatch
        is BookTextState.NeedsModel -> strings.needsModel(s.sourceName)
        BookTextState.NoSpace -> strings.noSpace
        is BookTextState.Syncing -> strings.syncing(s.sourceName, s.percent)
        is BookTextState.Paused -> if (s.hot) strings.pausedHot(s.sourceName, s.percent) else strings.pausedLater(s.sourceName, s.percent)
        is BookTextState.Ready -> strings.ready(s.sourceName)
        BookTextState.Failed -> strings.failed
    }
    OptionRow(strings.bookText, bookLine, selected = mode == AudiobookSubtitleMode.BOOK_TEXT) {
        modes.set(variant, AudiobookSubtitleMode.BOOK_TEXT)
    }
    if (mode == AudiobookSubtitleMode.BOOK_TEXT) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
            if (bookState is BookTextState.NeedsModel) ModelButton(models, modelStates, WhisperModel.BASE, strings)
            if (bookState is BookTextState.NotFound || bookState is BookTextState.Mismatch || bookState is BookTextState.Failed) {
                SmallAction(strings.searchAgain, primary = false) {
                    if (narration != null) scope.launch { alignment.retry(variant, narration, position) }
                }
            }
            SmallAction(strings.chooseFile, primary = bookState is BookTextState.NotFound || bookState is BookTextState.Mismatch) {
                runCatching { picker.launch(arrayOf("application/epub+zip", "application/x-fictionbook+xml", "text/plain", "application/octet-stream", "*/*")) }
            }
        }
    }
    Spacer(Modifier.height(3.dp))

    // Whisper — отдельный пункт, сам не включается.
    val deviceLine = when (val s = deviceState) {
        DeviceSubtitleState.Idle -> strings.deviceHint
        DeviceSubtitleState.NeedsModel -> strings.deviceNeedsModel
        is DeviceSubtitleState.Working -> strings.deviceWorking(s.percent)
        is DeviceSubtitleState.Paused -> strings.devicePaused(s.percent)
        DeviceSubtitleState.Ready -> strings.deviceReady
        DeviceSubtitleState.Failed -> strings.failed
    }
    OptionRow(strings.device, deviceLine, selected = mode == AudiobookSubtitleMode.DEVICE) {
        modes.set(variant, AudiobookSubtitleMode.DEVICE)
    }
    if (mode == AudiobookSubtitleMode.DEVICE && deviceState is DeviceSubtitleState.NeedsModel) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(start = 4.dp)) { ModelButton(models, modelStates, WhisperModel.BASE, strings) }
    }
}

@Composable
private fun ModelButton(models: WhisperModelStore, states: Map<WhisperModel, ModelState>, model: WhisperModel, strings: SubtitleStrings) {
    when (val s = states[model]) {
        is ModelState.Downloading -> SmallAction(strings.downloading(s.percent), primary = false) {}
        ModelState.Verifying -> SmallAction(strings.downloading(100), primary = false) {}
        else -> SmallAction(strings.downloadModel((model.sizeBytes / 1_000_000).toInt()), primary = true) { models.download(model) }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = if (selected) 0.34f else 0.22f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Muted, fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        // Выбранное — оранжевое (правило «активное — оранжевое»).
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (selected) BrandOrange else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) PhIcon(R.drawable.ph_check, 12.dp, Color.White)
        }
    }
}

@Composable
private fun SmallAction(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) BrandOrange else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Реплика под обложкой: предложение текста книги (или распознанная фраза) на текущей позиции.
 * Позиция — по шкале медиа, скорость воспроизведения на поиск реплики не влияет.
 */
@UnstableApi
@Composable
internal fun AudiobookSubtitleLine(
    variant: VariantId,
    state: AudiobookPlayerState,
    strings: SubtitleStrings,
    modifier: Modifier = Modifier,
) {
    val modes: AudiobookSubtitleModes = koinInject()
    val alignment: BookAlignmentManager = koinInject()
    val device: DeviceSubtitleManager = koinInject()
    val mode = modes.modes.collectAsState().value[variant] ?: AudiobookSubtitleMode.OFF
    if (mode == AudiobookSubtitleMode.OFF) return
    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(variant) {
        while (true) {
            state.globalMs()?.let { position.longValue = it }
            delay(200)
        }
    }
    val text: String? = when (mode) {
        AudiobookSubtitleMode.BOOK_TEXT -> {
            val track = alignment.tracks.collectAsState().value[variant]
            val cue = track?.at(position.longValue)
            val syncing = alignment.states.collectAsState().value[variant].let { it is BookTextState.Syncing || it is BookTextState.Searching }
            cue?.text(track.book) ?: strings.syncingOverlay.takeIf { syncing && track?.cues.isNullOrEmpty() }
        }
        AudiobookSubtitleMode.DEVICE -> DeviceSubtitleManager.at(device.cues.collectAsState().value[variant].orEmpty(), position.longValue)?.text
        AudiobookSubtitleMode.OFF -> null
    }
    if (text.isNullOrBlank()) return
    Text(
        text,
        color = Color.White,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
