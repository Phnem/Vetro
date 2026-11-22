package com.example.myapplication.ui.shared.mininotif

/**
 * Тексты раскрытых карточек mininotif (заголовок, подзаголовок, тег). Отдельным классом, а не в UiStrings:
 * у того почти исчерпан предел в 254 поля (за ним RELEASE падает с VerifyError).
 */
internal class MiniNotifCardStrings(private val ru: Boolean) {
    private fun t(ruText: String, enText: String): String = if (ru) ruText else enText

    /** Облачная синхронизация закончилась (главная). */
    fun syncDone(titles: Int) = MiniNotifCard(
        title = t("Синхронизировано", "Synced"),
        subtitle = t("Тайтлов в коллекции: $titles", "Titles in your collection: $titles"),
        tag = t("Облако", "Cloud"),
    )

    /** Плеер: первая стадия «ищем», пока видна пилюля. */
    val sourceSearching: String get() = t("Ищем другой источник…", "Looking for another source…")

    /** Плеер: сайт перестал отвечать, продолжаем с другого. */
    fun sourceSwitched(name: String) = MiniNotifCard(
        title = t("Сайт не отвечает", "The site stopped responding"),
        subtitle = t("Продолжаем с «$name»", "Continuing from $name"),
        tag = t("Резерв", "Fallback"),
    )

    /** Плеер: ни один сайт не отдаёт книгу. */
    val noSources: MiniNotifCard
        get() = MiniNotifCard(
            title = t("Источников не осталось", "No sources left"),
            subtitle = t("Ни один сайт сейчас не отдаёт эту книгу", "No site is serving this book right now"),
        )

    /** Добавление и правка: сохранение не удалось. */
    fun saveFailed(message: String) = MiniNotifCard(
        title = t("Не удалось сохранить", "Couldn't save"),
        subtitle = message,
    )

    /** Настройки: биометрия не настроена на устройстве. */
    val biometricsUnavailable: MiniNotifCard
        get() = MiniNotifCard(
            title = t("Биометрия недоступна", "Biometrics unavailable"),
            subtitle = t(
                "Настройте отпечаток пальца или лицо в настройках устройства",
                "Set up a fingerprint or face in your device settings",
            ),
        )

    /** Вход не удался. */
    fun loginFailed(message: String) = MiniNotifCard(
        title = t("Ошибка входа", "Sign-in failed"),
        subtitle = message,
    )

    /** Письмо для сброса пароля отправлено. */
    fun resetSent(email: String) = MiniNotifCard(
        title = t("Письмо отправлено", "Email sent"),
        subtitle = t(
            "Если аккаунт существует, на $email придёт ссылка для сброса пароля",
            "If the account exists, a reset link is on its way to $email",
        ),
    )

    /** Сброс пароля не удался. */
    fun resetFailed(message: String) = MiniNotifCard(
        title = t("Не удалось сбросить пароль", "Couldn't reset the password"),
        subtitle = message,
    )
}
