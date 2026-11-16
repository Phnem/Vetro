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

    else -> null
}
