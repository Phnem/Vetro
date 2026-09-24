package com.example.myapplication.audiobooks.ui

import com.example.myapplication.network.AppLanguage

/** Audiobook copy stays outside the already large UiStrings constructor. */
data class AudiobookStrings(
    val books: String,
    val emptyLibrary: String,
    val continueListening: String,
    val shelves: String,
    val myLibrary: String,
    val chooseFolder: String,
    val folderTooGeneral: String,
    val folderAccessLost: String,
    val play: String,
    val playbackStarted: String,
    val collapse: String,
    val playbackSpeed: String,
    val chapterProgress: String,
    val bookProgress: String,
    val pause: String,
    val chapters: String,
    val narrator: String,
    val duration: String,
    val rewind: String,
    val forward: String,
    val sleepTimer: String,
    val offline: String,
    val sourceUnavailable: String,
    val restrictedByRightsHolder: String,
    val chooseNarration: String,
)

private val russianAudiobookStrings = AudiobookStrings(
    books = "Книги",
    emptyLibrary = "Раздел ещё не наполнен",
    continueListening = "Продолжить слушать",
    shelves = "Полки",
    myLibrary = "Моя библиотека",
    chooseFolder = "Выбрать папку",
    folderTooGeneral = "Выберите отдельную папку с аудиокнигами, а не корень хранилища.",
    folderAccessLost = "Доступ к папке потерян. Выберите её снова.",
    play = "Слушать",
    playbackStarted = "Воспроизведение запущено",
    collapse = "Свернуть",
    playbackSpeed = "Скорость",
    chapterProgress = "Прогресс главы",
    bookProgress = "книги",
    pause = "Пауза",
    chapters = "Главы",
    narrator = "Чтец",
    duration = "Длительность",
    rewind = "Назад",
    forward = "Вперёд",
    sleepTimer = "Таймер сна",
    offline = "Без сети",
    sourceUnavailable = "Источник недоступен",
    restrictedByRightsHolder = "Недоступно по просьбе правообладателя",
    chooseNarration = "Выбрать озвучку",
)

private val englishAudiobookStrings = AudiobookStrings(
    books = "Books",
    emptyLibrary = "Nothing here yet",
    continueListening = "Continue listening",
    shelves = "Shelves",
    myLibrary = "My library",
    chooseFolder = "Choose folder",
    folderTooGeneral = "Choose a dedicated audiobook folder, not a storage root.",
    folderAccessLost = "Folder access was lost. Choose it again.",
    play = "Listen",
    playbackStarted = "Playback started",
    collapse = "Collapse",
    playbackSpeed = "Speed",
    chapterProgress = "Chapter progress",
    bookProgress = "of book",
    pause = "Pause",
    chapters = "Chapters",
    narrator = "Narrator",
    duration = "Duration",
    rewind = "Back",
    forward = "Forward",
    sleepTimer = "Sleep timer",
    offline = "Offline",
    sourceUnavailable = "Source unavailable",
    restrictedByRightsHolder = "Unavailable at the rights holder’s request",
    chooseNarration = "Choose narration",
)

fun getAudiobookStrings(language: AppLanguage): AudiobookStrings = when (language) {
    AppLanguage.RU -> russianAudiobookStrings
    AppLanguage.EN -> englishAudiobookStrings
}
