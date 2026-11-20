package com.example.myapplication.ui.shared.mininotif

/**
 * Тексты плашек хода синхронизации. Отдельным классом, а не в UiStrings: у того 252 поля из 254
 * допустимых (за пределом RELEASE падает с VerifyError в clinit).
 */
internal class SyncMiniNotifStrings(ru: Boolean) {
    val syncing: String = if (ru) "Синхронизация…" else "Syncing…"
    val done: String = if (ru) "Синхронизировано" else "Synced"
}
