package com.example.myapplication.manga.translate

import com.example.myapplication.network.AppLanguage

/**
 * Тексты автоперевода манги. Отдельно от UiStrings: у него уже ~250 полей, а предел JVM на
 * параметры конструктора - 255.
 */
data class MangaTranslateStrings(
    val title: String,
    val subtitleOff: String,
    val subtitleOn: String,
    val unofficialBuild: String,
    val noAiKey: String,
    val downloading: (percent: Int) -> String,
    val downloadFailed: String,
    val modelsSize: String,
    val readerTranslating: String,
    val readerFailed: String,
    val readerNoKey: String,
    val readerModels: String,
    val readerRateLimited: String,
    val readerRetry: String,
    val chapterBadge: String,
    // Шторка загрузки моделей.
    val sheetTitle: String,
    val sheetBody: String,
    val sheetPreparing: String,
    val stepDetector: String,
    val stepOcr: String,
    val hide: String,
    val hideHint: String,
    val cancelDownload: String,
    val retry: String,
    val doneTitle: String,
    val doneBody: String,
    val done: String,
    val failedTitle: String,
    val failedBody: String,
    val close: String,
    /** "42 из 128 МБ" */
    val sizeOf: (done: String, total: String) -> String,
    /** Единица размера и скорости: "МБ", "МБ/с". */
    val megabyte: String,
    val perSecond: String,
    /** Оставшееся время: секунды всего. */
    val eta: (seconds: Long) -> String,
)

fun mangaTranslateStrings(language: AppLanguage): MangaTranslateStrings =
    if (language == AppLanguage.RU) RU else EN

private val RU = MangaTranslateStrings(
    title = "Автоперевод манги",
    subtitleOff = "Главы на японском и английском переводятся прямо в ридере",
    subtitleOn = "Включён: главы без перевода читаются на вашем языке",
    unofficialBuild = "Недоступно в этой сборке. Автоперевод работает только в официальных релизах с GitHub, подписанных ключом автора. В версии из F-Droid он выключен.",
    noAiKey = "Нужен подключённый ИИ-провайдер. Добавьте свой ключ в AI Connect, и пункт станет доступен.",
    downloading = { "Загрузка моделей… $it%" },
    downloadFailed = "Не удалось скачать модели. Нажмите, чтобы повторить.",
    modelsSize = "Модели распознавания (≈128 МБ) скачаются один раз при включении.",
    readerTranslating = "ИИ переводит страницу…",
    readerFailed = "Не получилось перевести страницу",
    readerNoKey = "Подключите ИИ-ключ, чтобы переводить главы",
    readerModels = "Модели перевода ещё не скачаны",
    readerRateLimited = "ИИ-провайдер просит подождать",
    readerRetry = "Повторить",
    chapterBadge = "ИИ-перевод",
    sheetTitle = "Модели автоперевода",
    sheetBody = "Два небольших ИИ-модуля работают прямо на телефоне: находят реплики на странице и читают японский текст. Японские страницы не покидают телефон, наружу идёт только текст реплик. Английские читает ваш ИИ-провайдер, поэтому такая страница уходит ему картинкой.",
    sheetPreparing = "Подготовка…",
    stepDetector = "Поиск реплик на странице",
    stepOcr = "Чтение японского текста",
    hide = "Скрыть",
    hideHint = "Загрузка продолжится в фоне",
    cancelDownload = "Отменить загрузку",
    retry = "Повторить",
    doneTitle = "Всё готово",
    doneBody = "Автоперевод включён: главы без вашего языка переведутся прямо в ридере.",
    done = "Готово",
    failedTitle = "Не удалось скачать",
    failedBody = "Проверьте соединение и повторите. Уже скачанные файлы сохранены.",
    close = "Закрыть",
    sizeOf = { done, total -> "$done из $total" },
    megabyte = "МБ",
    perSecond = "МБ/с",
    eta = { seconds ->
        when {
            seconds < 5 -> "почти готово"
            seconds < 60 -> "≈ $seconds с"
            else -> "≈ ${seconds / 60} мин ${seconds % 60} с"
        }
    },
)

private val EN = MangaTranslateStrings(
    title = "Manga auto-translation",
    subtitleOff = "Japanese and English chapters are translated right in the reader",
    subtitleOn = "On: chapters without a translation are read in your language",
    unofficialBuild = "Not available in this build. Auto-translation works only in the official GitHub releases signed with the author's key. It is switched off in the F-Droid version.",
    noAiKey = "Needs a connected AI provider. Add your own key in AI Connect and this option unlocks.",
    downloading = { "Downloading models… $it%" },
    downloadFailed = "Could not download the models. Tap to retry.",
    modelsSize = "Recognition models (about 128 MB) download once when you switch this on.",
    readerTranslating = "AI is translating the page…",
    readerFailed = "Could not translate this page",
    readerNoKey = "Connect an AI key to translate chapters",
    readerModels = "Translation models are not downloaded yet",
    readerRateLimited = "The AI provider asks to wait",
    readerRetry = "Retry",
    chapterBadge = "AI translation",
    sheetTitle = "Auto-translation models",
    sheetBody = "Two small AI modules run right on your phone: one finds speech on the page, the other reads Japanese text. Japanese pages never leave the device; only the text of the lines goes out. English pages are read by your AI provider, so such a page is sent to it as an image.",
    sheetPreparing = "Getting ready…",
    stepDetector = "Finding speech on the page",
    stepOcr = "Reading Japanese text",
    hide = "Hide",
    hideHint = "The download keeps going in the background",
    cancelDownload = "Cancel download",
    retry = "Retry",
    doneTitle = "All set",
    doneBody = "Auto-translation is on: chapters without your language are translated right in the reader.",
    done = "Done",
    failedTitle = "Download failed",
    failedBody = "Check your connection and retry. Files already downloaded are kept.",
    close = "Close",
    sizeOf = { done, total -> "$done of $total" },
    megabyte = "MB",
    perSecond = "MB/s",
    eta = { seconds ->
        when {
            seconds < 5 -> "almost done"
            seconds < 60 -> "≈ ${seconds}s"
            else -> "≈ ${seconds / 60}m ${seconds % 60}s"
        }
    },
)
