package com.example.myapplication.media.subtitles

import com.example.myapplication.localplayer.ui.SubtitleOption
import com.example.myapplication.media.subtitles.whisper.ModelFailure
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.WhisperLanguage
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperProgress
import com.example.myapplication.media.subtitles.whisper.WhisperStatus
import java.util.Locale

/** Раздел меню «Субтитры». */
enum class SubtitlePage { ROOT, EMBEDDED, OPENSUBTITLES, WHISPER }

data class OpenSubtitlesUi(
    /** Учётка с ключом добавлена в «Источниках». */
    val configured: Boolean,
    val offers: List<SubtitleOffer>,
    val searching: Boolean,
)

data class WhisperUi(
    /** Нативный движок есть в сборке. */
    val engineAvailable: Boolean,
    val model: WhisperModel,
    val modelState: ModelState,
    /** Модель по силам устройства: если выбрана тяжелее — показываем лёгкую рядом. */
    val recommended: WhisperModel,
    val language: WhisperLanguage,
    val progress: WhisperProgress?,
    /** Субтитры Whisper сейчас на экране. */
    val showing: Boolean,
    /** Длительность видео известна — можно планировать распознавание. */
    val durationKnown: Boolean,
)

/** Что сделать по пункту меню; кодируется в [SubtitleOption.action]. */
sealed interface SubtitleMenuAction {
    data class Open(val page: SubtitlePage) : SubtitleMenuAction
    data class DownloadModel(val model: WhisperModel) : SubtitleMenuAction
    data object CancelDownload : SubtitleMenuAction
    data object Generate : SubtitleMenuAction
    data object Stop : SubtitleMenuAction
    data object Show : SubtitleMenuAction
    data object CycleLanguage : SubtitleMenuAction
    data object DeleteModel : SubtitleMenuAction
    /** Только подпись — нажатие ничего не делает. */
    data object None : SubtitleMenuAction

    companion object {
        fun decode(action: String?): SubtitleMenuAction? = when {
            action == null -> null
            action.startsWith("page:") -> Open(SubtitlePage.valueOf(action.removePrefix("page:")))
            action.startsWith("download:") -> DownloadModel(WhisperModel.valueOf(action.removePrefix("download:")))
            action == "cancel" -> CancelDownload
            action == "generate" -> Generate
            action == "stop" -> Stop
            action == "show" -> Show
            action == "language" -> CycleLanguage
            action == "delete" -> DeleteModel
            else -> None
        }
    }
}

/**
 * Меню «Субтитры»: корень — «Выкл», «Встроенные», «OpenSubtitles», «Whisper»; OpenSubtitles и Whisper
 * есть всегда, даже без встроенных дорожек. [embedded] — встроенные дорожки со своим выбором.
 */
fun subtitleMenu(
    page: SubtitlePage,
    embedded: List<SubtitleOption>,
    openSubtitles: OpenSubtitlesUi,
    whisper: WhisperUi,
    ru: Boolean,
): List<SubtitleOption> {
    fun section(id: String, label: String, target: SubtitlePage, selected: Boolean = false) =
        SubtitleOption(id, label, selected, keepsMenuOpen = true, action = "page:${target.name}")
    fun info(id: String, label: String) = SubtitleOption(id, label, false, keepsMenuOpen = true, action = "info")
    fun act(id: String, label: String, action: String, keepOpen: Boolean = true, selected: Boolean = false) =
        SubtitleOption(id, label, selected, keepsMenuOpen = keepOpen, action = action)
    val back = section("back", if (ru) "‹ Назад" else "‹ Back", SubtitlePage.ROOT)

    return when (page) {
        SubtitlePage.ROOT -> buildList {
            val embeddedOn = embedded.any { it.isSelected }
            add(SubtitleOption("off", if (ru) "Выкл" else "Off", !embeddedOn && !whisper.showing, isOff = true))
            if (embedded.isNotEmpty()) {
                add(section("embedded", (if (ru) "Встроенные" else "Built-in") + " · ${embedded.size}", SubtitlePage.EMBEDDED, embeddedOn))
            }
            add(section("os", "OpenSubtitles", SubtitlePage.OPENSUBTITLES))
            add(section("whisper", if (ru) "Создать на устройстве" else "Create on device", SubtitlePage.WHISPER, whisper.showing))
        }
        SubtitlePage.EMBEDDED -> listOf(back) + embedded
        SubtitlePage.OPENSUBTITLES -> buildList {
            add(back)
            when {
                !openSubtitles.configured -> add(info("os-key", if (ru) "Добавьте свой ключ в «Источниках»" else "Add your key in Sources"))
                openSubtitles.searching -> add(info("os-search", if (ru) "Поиск…" else "Searching…"))
                openSubtitles.offers.isEmpty() -> add(info("os-none", if (ru) "Ничего не найдено" else "Nothing found"))
                else -> openSubtitles.offers.forEach { offer ->
                    add(SubtitleOption("offer:${offer.fileId}", subtitleLabel(offer), false, externalKey = offer.fileId.toString()))
                }
            }
        }
        SubtitlePage.WHISPER -> buildList {
            add(back)
            if (!whisper.engineAvailable) {
                add(info("w-engine", if (ru) "Недоступно в этой сборке" else "Not available in this build"))
                return@buildList
            }
            when (val state = whisper.modelState) {
                ModelState.Absent, is ModelState.Failed -> {
                    if (state is ModelState.Failed) add(info("w-failed", failureText(state.reason, ru)))
                    add(act("w-download", downloadLabel(whisper.model, ru), "download:${whisper.model.name}"))
                    if (whisper.recommended != whisper.model) {
                        add(info("w-weak", if (ru) "Для этого телефона лучше лёгкая модель" else "A lighter model suits this phone"))
                        add(act("w-download-light", downloadLabel(whisper.recommended, ru), "download:${whisper.recommended.name}"))
                    }
                }
                is ModelState.Downloading -> {
                    add(info("w-progress", (if (ru) "Загрузка модели " else "Downloading model ") + "${state.percent}%"))
                    add(act("w-cancel", if (ru) "Отменить загрузку" else "Cancel download", "cancel"))
                }
                ModelState.Verifying -> add(info("w-verify", if (ru) "Проверка файла модели…" else "Checking the model file…"))
                is ModelState.Ready -> {
                    val progress = whisper.progress
                    val running = progress?.status == WhisperStatus.RUNNING
                    if (progress != null && progress.cues.isNotEmpty()) {
                        add(act("w-show", if (ru) "Показать субтитры" else "Show subtitles", "show", keepOpen = false, selected = whisper.showing))
                    }
                    if (running) {
                        add(info("w-running", (if (ru) "Распознано " else "Recognized ") + percent(progress!!) + "%"))
                        add(act("w-stop", if (ru) "Остановить" else "Stop", "stop"))
                    } else if (progress?.status != WhisperStatus.DONE && whisper.durationKnown) {
                        val resume = progress != null && progress.coveredMs > 0
                        add(act("w-generate", if (resume) (if (ru) "Продолжить распознавание" else "Continue") else (if (ru) "Сгенерировать субтитры" else "Generate subtitles"), "generate", keepOpen = false))
                    }
                    if (progress?.slowerThanRealTime == true && whisper.recommended != whisper.model) {
                        add(info("w-slow", if (ru) "Медленнее видео — попробуйте лёгкую модель" else "Slower than the video — try a lighter model"))
                    }
                    add(act("w-language", (if (ru) "Язык: " else "Language: ") + languageLabel(whisper.language, ru), "language"))
                    add(act("w-delete", if (ru) "Удалить модель" else "Delete model", "delete"))
                }
            }
        }
    }
}

private fun percent(p: WhisperProgress): Int = if (p.durationMs > 0) (p.coveredMs * 100 / p.durationMs).toInt().coerceIn(0, 100) else 0

private fun downloadLabel(model: WhisperModel, ru: Boolean): String {
    val mb = String.format(Locale.ROOT, "%.0f", model.sizeBytes / 1_000_000.0)
    val name = if (ru) model.titleRu else model.titleEn
    return if (ru) "Скачать модель · $name · $mb МБ" else "Download model · $name · $mb MB"
}

private fun languageLabel(language: WhisperLanguage, ru: Boolean): String = when (language) {
    WhisperLanguage.AUTO -> if (ru) "определить" else "auto"
    WhisperLanguage.RU -> if (ru) "русский" else "Russian"
    WhisperLanguage.EN -> if (ru) "английский" else "English"
}

private fun failureText(reason: ModelFailure, ru: Boolean): String = when (reason) {
    ModelFailure.NO_SPACE -> if (ru) "Не хватает места на устройстве" else "Not enough storage"
    ModelFailure.NETWORK -> if (ru) "Загрузка прервалась — можно продолжить" else "Download interrupted — you can resume"
    ModelFailure.CORRUPTED -> if (ru) "Файл модели повреждён — скачайте заново" else "The model file is corrupted — download again"
}
