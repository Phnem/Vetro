package com.example.myapplication.data.models

/**
 * Persisted snapshot of the last GitHub release check ([com.example.myapplication.data.repository.AppUpdateRepository]).
 */
enum class AppUpdatePersistedKind {
    /** Ещё не было ни одной удачной проверки. */
    IDLE,
    NO_UPDATE,
    UPDATE_AVAILABLE,
    ERROR,
}

data class AppUpdateSnapshot(
    val persistedKind: AppUpdatePersistedKind,
    val latestTag: String?,
    val latestDownloadUrl: String?,
    val latestHtmlUrl: String?,
    val latestApkSizeBytes: Long?,
    /** SHA-256 APK последнего релиза, если GitHub его сообщил. */
    val latestSha256: String?,
    val updateChangelogMarkdown: String?,
    val lastSuccessfulCheckEpochMs: Long,
    val startupDismissedTag: String?,
) {
    val startupOverlayEligible: Boolean
        get() = persistedKind == AppUpdatePersistedKind.UPDATE_AVAILABLE &&
            !latestTag.isNullOrBlank() &&
            latestTag != startupDismissedTag
}
