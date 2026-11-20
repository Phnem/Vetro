package com.example.myapplication.ui.settings.help

/** Идентификаторы пунктов, у которых есть пояснение. Строка настроек и текст связаны только по ним. */
object SettingHelpIds {
    const val AVAILABILITY_THRESHOLD = "availability_threshold"
    const val PLAYBACK_SOURCES = "playback_sources"
    const val LANGUAGE = "language"
    const val THEME = "theme"
    const val BIOMETRIC = "biometric"
    const val CLOUD = "cloud"
    const val SYNC = "sync"
    const val AI_CONNECT = "ai_connect"
    const val AUTO_SKIP = "auto_skip"
    const val AUTO_NEXT = "auto_next"
    const val CONTACT = "contact"
    const val UPDATE = "update"
    const val DONATION = "donation"
    const val LOCAL_AI = "local_ai"
    const val CONTRIBUTION = "contribution"
    const val DEV_SECTION = "dev_section"
    const val DEV_AI = "dev_ai"
    const val DEV_MIRROR_DB = "dev_mirror_db"
    const val DEV_HIDE_SHARE = "dev_hide_share"
    const val DEV_ADAPTIVE_GLASS = "dev_adaptive_glass"
    const val DEV_FULL_COVER = "dev_full_cover"
    const val DEV_MINI_NOTIF = "dev_mini_notif"
    const val DEV_EXPERIMENTAL_GRID = "dev_experimental_grid"
    const val DEV_LEGACY_SPLASH = "dev_legacy_splash"
    const val DEV_FPS = "dev_fps"
    const val DEV_EXPORT_LOGS = "dev_export_logs"
    const val DEV_EXPORT_PDF = "dev_export_pdf"
    const val DEV_IMPORT_DB = "dev_import_db"
}

/** Один абзац пояснения: короткий заголовок и текст под ним. */
class HelpPoint(val heading: String, val body: String)

class SettingHelpText(
    val title: String,
    /** Одна фраза о том, что вообще делает пункт. */
    val lead: String,
    val points: List<HelpPoint> = emptyList(),
    /** Приписка внизу: чем пункт НЕ является или что будет при выключении. */
    val footnote: String? = null,
)

fun settingHelpText(id: String, ru: Boolean): SettingHelpText? = when (id) {
    SettingHelpIds.AVAILABILITY_THRESHOLD -> if (ru) {
        SettingHelpText(
            title = "Порог доступности",
            lead = "Присылает уведомление о новой серии только тогда, когда её уже можно нормально смотреть.",
            points = listOf(
                HelpPoint(
                    "Как это работает",
                    "Когда у отслеживаемого тайтла выходит серия, приложение проверяет, у скольких студий озвучки она уже есть и сколько времени прошло с выхода.",
                ),
                HelpPoint(
                    "Когда уведомление ждёт",
                    "Если озвучек меньше 4 и с выхода прошло не больше 2 дней, уведомления нет: серию, скорее всего, ещё не успели озвучить.",
                ),
                HelpPoint(
                    "Когда оно приходит",
                    "Как только озвучек стало 4 и больше или прошло больше 2 дней. Хватит любого из двух условий. Пока серия ждёт, проверка повторяется каждые 6 часов.",
                ),
            ),
            footnote = "Выключено — уведомление приходит сразу, как только серия вышла.",
        )
    } else {
        SettingHelpText(
            title = "Availability threshold",
            lead = "Sends the new-episode notification only once the episode is actually watchable.",
            points = listOf(
                HelpPoint(
                    "How it works",
                    "When a tracked title releases an episode, the app checks how many dubbing studios already have it and how long ago it came out.",
                ),
                HelpPoint(
                    "When the notification waits",
                    "With fewer than 4 dubs and at most 2 days since release there is no notification: the episode has most likely not been dubbed yet.",
                ),
                HelpPoint(
                    "When it arrives",
                    "As soon as there are 4 or more dubs, or more than 2 days have passed. Either one is enough. While an episode waits, the check repeats every 6 hours.",
                ),
            ),
            footnote = "When off, the notification arrives as soon as the episode is out.",
        )
    }

    SettingHelpIds.PLAYBACK_SOURCES -> if (ru) {
        SettingHelpText(
            title = "Дополнительные источники",
            lead = "Подключает ваши собственные серверы с видео: WebDAV, Jellyfin и Emby.",
            points = listOf(
                HelpPoint("Зачем", "Серии с вашего сервера появляются в плеере рядом с обычными источниками и могут стать запасными, если те не отвечают."),
                HelpPoint("Что нужно", "Адрес сервера и данные для входа. Они хранятся на устройстве."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Additional sources",
            lead = "Connects your own video servers: WebDAV, Jellyfin and Emby.",
            points = listOf(
                HelpPoint("Why", "Episodes from your server show up in the player next to the usual sources and can serve as a fallback when those are silent."),
                HelpPoint("What you need", "The server address and sign-in details. They are stored on this device."),
            ),
        )
    }

    SettingHelpIds.LANGUAGE -> if (ru) {
        SettingHelpText(
            title = "Язык",
            lead = "Язык интерфейса и названий тайтлов.",
            points = listOf(HelpPoint("Что меняется", "Подписи, подсказки и то, какое название показывается у тайтла: русское или английское.")),
        )
    } else {
        SettingHelpText(
            title = "Language",
            lead = "The language of the interface and of title names.",
            points = listOf(HelpPoint("What changes", "Labels, hints and which name a title shows: Russian or English.")),
        )
    }

    SettingHelpIds.THEME -> if (ru) {
        SettingHelpText(
            title = "Тема",
            lead = "Светлое или тёмное оформление.",
            points = listOf(HelpPoint("Системная", "Тема следует за настройкой телефона и переключается вместе с ней.")),
        )
    } else {
        SettingHelpText(
            title = "Theme",
            lead = "Light or dark appearance.",
            points = listOf(HelpPoint("System", "The theme follows the phone setting and switches with it.")),
        )
    }

    SettingHelpIds.BIOMETRIC -> if (ru) {
        SettingHelpText(
            title = "Вход по биометрии",
            lead = "Просит отпечаток пальца или лицо при открытии приложения.",
            points = listOf(HelpPoint("Зачем", "Чужой человек с вашим телефоном не увидит коллекцию, пока не пройдёт проверку.")),
            footnote = "Нужна настроенная на телефоне биометрия.",
        )
    } else {
        SettingHelpText(
            title = "Biometric app lock",
            lead = "Asks for a fingerprint or face when the app opens.",
            points = listOf(HelpPoint("Why", "Someone holding your phone cannot see the collection until they pass the check.")),
            footnote = "Requires biometrics to be set up on the phone.",
        )
    }

    SettingHelpIds.CLOUD -> if (ru) {
        SettingHelpText(
            title = "Аккаунт и облако",
            lead = "Вход в аккаунт и резервная копия коллекции в облаке.",
            points = listOf(HelpPoint("Что даёт", "Коллекция переживает смену телефона и синхронизируется между устройствами.")),
        )
    } else {
        SettingHelpText(
            title = "Account and cloud",
            lead = "Sign-in and a cloud backup of your collection.",
            points = listOf(HelpPoint("What it gives", "The collection survives a phone change and syncs between devices.")),
        )
    }

    SettingHelpIds.SYNC -> if (ru) {
        SettingHelpText(
            title = "Синхронизация",
            lead = "Подключённые сервисы, списки и проверка новых серий в одном окне.",
            points = listOf(HelpPoint("Что внутри", "Shikimori, AniList и MyAnimeList, а также ручной запуск проверки новых серий.")),
        )
    } else {
        SettingHelpText(
            title = "Sync",
            lead = "Connected services, lists and the new-episode check in one window.",
            points = listOf(HelpPoint("What's inside", "Shikimori, AniList and MyAnimeList, plus a manual new-episode check.")),
        )
    }

    SettingHelpIds.AI_CONNECT -> if (ru) {
        SettingHelpText(
            title = "Подключение ИИ",
            lead = "Свой ключ нейросети для умных функций приложения.",
            points = listOf(
                HelpPoint("Зачем", "Ключ нужен функциям, которые что-то объясняют или переводят: дубляж названий, пояснения в статистике и другим."),
                HelpPoint("Безопасность", "Ключ хранится на устройстве и уходит только своему сервису."),
            ),
        )
    } else {
        SettingHelpText(
            title = "AI connection",
            lead = "Your own neural-network key for the app's smart features.",
            points = listOf(
                HelpPoint("Why", "The key is used by features that explain or translate: title dubbing, statistics explanations and others."),
                HelpPoint("Safety", "The key is stored on the device and is sent only to its own service."),
            ),
        )
    }

    SettingHelpIds.AUTO_SKIP -> if (ru) {
        SettingHelpText(
            title = "Автопропуск",
            lead = "Сам перематывает опенинги, эндинги и рекапы.",
            points = listOf(HelpPoint("Откуда метки", "Границы сегментов берутся из открытых баз. Если метки у серии нет, пропуск не сработает.")),
        )
    } else {
        SettingHelpText(
            title = "Auto skip",
            lead = "Skips openings, endings and recaps by itself.",
            points = listOf(HelpPoint("Where marks come from", "Segment bounds come from open databases. If an episode has no marks, skipping will not trigger.")),
        )
    }

    SettingHelpIds.AUTO_NEXT -> if (ru) {
        SettingHelpText(
            title = "Автопереход",
            lead = "Включает следующую серию, когда текущая закончилась.",
            points = listOf(HelpPoint("Если не нужно", "Выключите, и плеер остановится в конце серии.")),
        )
    } else {
        SettingHelpText(
            title = "Auto next",
            lead = "Starts the next episode when the current one ends.",
            points = listOf(HelpPoint("If you don't want it", "Turn it off and the player stops at the end of an episode.")),
        )
    }

    SettingHelpIds.CONTACT -> if (ru) {
        SettingHelpText(
            title = "Связь",
            lead = "Способы написать автору: ошибки, идеи, вопросы.",
            points = listOf(HelpPoint("Что приложить", "Лучше всего описать, что нажимали и что пошло не так. Логи можно выгрузить в разделе для разработчиков.")),
        )
    } else {
        SettingHelpText(
            title = "Contact",
            lead = "Ways to reach the author: bugs, ideas, questions.",
            points = listOf(HelpPoint("What to include", "Describe what you tapped and what went wrong. Logs can be exported in the developer section.")),
        )
    }

    SettingHelpIds.UPDATE -> if (ru) {
        SettingHelpText(
            title = "Обновление",
            lead = "Проверяет, есть ли новая версия приложения, и ставит её.",
            points = listOf(HelpPoint("Как ставится", "Файл скачивается и проверяется, затем система предлагает установить его.")),
        )
    } else {
        SettingHelpText(
            title = "Update",
            lead = "Checks for a new app version and installs it.",
            points = listOf(HelpPoint("How it installs", "The file is downloaded and verified, then the system offers to install it.")),
        )
    }

    SettingHelpIds.DONATION -> if (ru) {
        SettingHelpText(
            title = "Поддержать проект",
            lead = "Ссылка, по которой можно поддержать разработку.",
            points = listOf(HelpPoint("Это необязательно", "Все функции приложения доступны и без поддержки.")),
        )
    } else {
        SettingHelpText(
            title = "Support the project",
            lead = "A link to support the development.",
            points = listOf(HelpPoint("Optional", "Every feature of the app works without it.")),
        )
    }

    SettingHelpIds.LOCAL_AI -> if (ru) {
        SettingHelpText(
            title = "Локальный ИИ",
            lead = "Три небольшие модели прямо на телефоне помогают поиску, каталогу и рекомендациям.",
            points = listOf(
                HelpPoint("Распознавание названий", "Решает, тот же ли это тайтл, и расставляет варианты в поиске по вероятности."),
                HelpPoint("Разбор файлов и жанров", "Читает имена файлов и папок аудиокниг, определяет язык названия и жанры, узнаёт заглушки вместо обложек."),
                HelpPoint("Рекомендации по вкусу", "Связи жанров и порядок рекомендаций; учится на ваших оценках."),
            ),
            footnote = "Модели работают на телефоне, названия из вашей коллекции никуда не отправляются. Один выключатель включает или выключает все три сразу; без моделей работают обычные правила Vetro.",
        )
    } else {
        SettingHelpText(
            title = "Local AI",
            lead = "Three small models on your phone help search, the catalogue and recommendations.",
            points = listOf(
                HelpPoint("Title matching", "Decides whether it is the same title and orders search candidates by likelihood."),
                HelpPoint("Files and genres", "Reads audiobook file and folder names, detects the title language and genres, and recognises stock images in place of covers."),
                HelpPoint("Taste recommendations", "Genre links and a recommendation order that learns from your ratings."),
            ),
            footnote = "The models run on your phone and titles from your collection are never sent anywhere. One switch turns all three on or off; without the models Vetro uses its standard rules.",
        )
    }

    SettingHelpIds.CONTRIBUTION -> if (ru) {
        SettingHelpText(
            title = "Помогать улучшать Vetro",
            lead = "Раз в сутки уходят анонимные данные о работе моделей и найденные книги, но только если вы сами согласились.",
            points = listOf(
                HelpPoint("Что отправляется", "Числа о работе моделей и записи о книгах, найденных на сторонних сайтах."),
                HelpPoint("Что не отправляется", "Ваши запросы и названия из коллекции."),
                HelpPoint("Как включить", "Переключатель выключен, пока вы не нажмёте «ОК» в окне согласия. Выключить можно в любой момент."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Help improve Vetro",
            lead = "Once a day, anonymous data about how the models work and the books found are sent, but only if you agreed.",
            points = listOf(
                HelpPoint("What is sent", "Numbers about how the models work and records of books found on third-party sites."),
                HelpPoint("What is not sent", "Your queries and the titles from your collection."),
                HelpPoint("How to turn on", "The switch stays off until you tap OK in the consent window. You can turn it off at any time."),
            ),
        )
    }

    SettingHelpIds.DEV_SECTION -> if (ru) {
        SettingHelpText(
            title = "Для разработчиков",
            lead = "Экспериментальные переключатели и инструменты диагностики.",
            points = listOf(
                HelpPoint("Для кого", "Раздел для автора и тестировщиков: часть переключателей меняет вид приложения, часть пишет файлы или выгружает данные."),
                HelpPoint("Безопасно ли", "Ничего из этого не нужно для обычной работы; всё можно вернуть обратно тем же переключателем."),
            ),
        )
    } else {
        SettingHelpText(
            title = "For developers",
            lead = "Experimental switches and diagnostic tools.",
            points = listOf(
                HelpPoint("Who it is for", "A section for the author and testers: some switches change how the app looks, some write files or export data."),
                HelpPoint("Is it safe", "None of it is needed for normal use, and each change can be reverted with the same switch."),
            ),
        )
    }

    SettingHelpIds.DEV_AI -> if (ru) {
        SettingHelpText(
            title = "Локальный ИИ",
            lead = "Состояние трёх моделей и возврат к предыдущей версии каждой.",
            points = listOf(
                HelpPoint("Что показано", "Готова ли модель, выключена ли, нет ли пакета, и счётчики теневых наблюдений. Названия из коллекции не хранятся."),
                HelpPoint("Предыдущая версия", "Если новая версия модели ведёт себя странно, можно вернуть прежнюю. Если её нет, работают обычные правила Vetro."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Local AI",
            lead = "The state of the three models and a switch back to the previous version of each.",
            points = listOf(
                HelpPoint("What is shown", "Whether a model is ready, off or missing a pack, and the shadow observation counters. Titles from your collection are not stored."),
                HelpPoint("Previous version", "If a new model version misbehaves, the earlier one can be restored. Without one, the standard rules of Vetro are used."),
            ),
        )
    }

    SettingHelpIds.DEV_MIRROR_DB -> if (ru) {
        SettingHelpText(
            title = "Зеркалировать внутреннюю БД",
            lead = "После каждого изменения базы её копия кладётся в папку приложения.",
            points = listOf(
                HelpPoint("Зачем", "Копию удобно забрать с телефона и посмотреть на компьютере. Для обычной работы не нужно."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Mirror internal database",
            lead = "After each change the database is copied to the app folder.",
            points = listOf(
                HelpPoint("Why", "The copy is easy to pull from the phone and inspect on a computer. Not needed for normal use."),
            ),
        )
    }

    SettingHelpIds.DEV_HIDE_SHARE -> if (ru) {
        SettingHelpText(
            title = "Скрыть кнопку Share",
            lead = "Убирает кнопку экспорта из угла экрана настроек.",
            points = listOf(
                HelpPoint("Когда нужно", "Если кнопка мешает на скриншотах или записи экрана."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Hide Share button",
            lead = "Removes the share action from the corner of the settings screen.",
            points = listOf(
                HelpPoint("When to use", "When the button gets in the way of screenshots or screen recordings."),
            ),
        )
    }

    SettingHelpIds.DEV_ADAPTIVE_GLASS -> if (ru) {
        SettingHelpText(
            title = "Adaptive glass при скролле",
            lead = "Во время прокрутки списка снижает размытие и линзу, чтобы разгрузить видеочип.",
            points = listOf(
                HelpPoint("Для сравнения", "Выключите, чтобы увидеть полное стекло на прокрутке и сравнить плавность."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Adaptive glass on scroll",
            lead = "Reduces blur and lens while a list scrolls, to ease the GPU.",
            points = listOf(
                HelpPoint("For comparison", "Turn it off to see the full glass while scrolling and compare smoothness."),
            ),
        )
    }

    SettingHelpIds.DEV_FULL_COVER -> if (ru) {
        SettingHelpText(
            title = "Карточки с обложкой",
            lead = "Обложка занимает всю карточку, текст лежит поверх затемнения.",
            points = listOf(
                HelpPoint("Если выключить", "Вернётся классическая карточка с постером сбоку."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Full-cover cards",
            lead = "The cover fills the whole card and the text sits on top of a dark overlay.",
            points = listOf(
                HelpPoint("If you turn it off", "The classic card with the poster on the side comes back."),
            ),
        )
    }

    SettingHelpIds.DEV_MINI_NOTIF -> if (ru) {
        SettingHelpText(
            title = "Мини-уведомления",
            lead = "Короткие сообщения и ход синхронизации показываются плашкой снизу, как в Dynamic Island.",
            points = listOf(
                HelpPoint("Если выключить", "Сообщения снова идут системными тостами, как раньше."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Mini notifications",
            lead = "Short messages and sync progress appear as a bottom pill that morphs like Dynamic Island.",
            points = listOf(
                HelpPoint("If you turn it off", "Messages go back to system toasts as before."),
            ),
        )
    }

    SettingHelpIds.DEV_EXPERIMENTAL_GRID -> if (ru) {
        SettingHelpText(
            title = "Экспериментальная сетка",
            lead = "Избранное показывается горизонтальной лентой над коллекцией.",
            points = listOf(
                HelpPoint("Если выключить", "Избранное лежит в общем списке, поднятое наверх."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Experimental grid",
            lead = "Favorites appear as a horizontal strip above the collection.",
            points = listOf(
                HelpPoint("If you turn it off", "Favorites stay in the main list, raised to the top."),
            ),
        )
    }

    SettingHelpIds.DEV_LEGACY_SPLASH -> if (ru) {
        SettingHelpText(
            title = "Старая заставка старта",
            lead = "Вместо анимации логотипа показывается прежнее «переливание».",
            points = listOf(
                HelpPoint("Когда сработает", "Применяется при следующем запуске приложения."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Legacy startup splash",
            lead = "Shows the earlier wave instead of the logo animation.",
            points = listOf(
                HelpPoint("When it applies", "On the next launch of the app."),
            ),
        )
    }

    SettingHelpIds.DEV_FPS -> if (ru) {
        SettingHelpText(
            title = "Диагностика производительности (FPS)",
            lead = "Поверх всех экранов показывается плашка с частотой кадров.",
            points = listOf(
                HelpPoint("Зачем", "Помогает заметить подтормаживания при разработке. Для обычной работы не нужна."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Performance diagnostics (FPS)",
            lead = "Shows an FPS badge above every screen.",
            points = listOf(
                HelpPoint("Why", "Helps spot stutter during development. Not needed for normal use."),
            ),
        )
    }

    SettingHelpIds.DEV_EXPORT_LOGS -> if (ru) {
        SettingHelpText(
            title = "Логи",
            lead = "Собирает журнал приложения и открывает системное меню «Поделиться».",
            points = listOf(
                HelpPoint("Что внутри", "Технический журнал работы приложения. Прикладывайте его, когда пишете автору об ошибке."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Logs",
            lead = "Collects the app log and opens the system share sheet.",
            points = listOf(
                HelpPoint("What is inside", "The technical log of the app. Attach it when you write to the author about a bug."),
            ),
        )
    }

    SettingHelpIds.DEV_EXPORT_PDF -> if (ru) {
        SettingHelpText(
            title = "Экспорт в PDF",
            lead = "Собирает таблицу коллекции в PDF-файл.",
            points = listOf(
                HelpPoint("Что в таблице", "Название, число серий и рейтинг каждого тайтла."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Export to PDF",
            lead = "Builds a table of the collection as a PDF file.",
            points = listOf(
                HelpPoint("What is in the table", "The title, episode count and rating of each entry."),
            ),
        )
    }

    SettingHelpIds.DEV_IMPORT_DB -> if (ru) {
        SettingHelpText(
            title = "Импорт базы",
            lead = "Выбранный файл .db добавляет в коллекцию недостающие записи.",
            points = listOf(
                HelpPoint("Что не пропадёт", "Существующие записи не меняются: добавляются только те, которых ещё нет."),
            ),
        )
    } else {
        SettingHelpText(
            title = "Import database",
            lead = "Adds the missing entries from a chosen .db file to the collection.",
            points = listOf(
                HelpPoint("What stays safe", "Existing entries are not changed: only the ones that are not there yet are added."),
            ),
        )
    }

    else -> null
}
