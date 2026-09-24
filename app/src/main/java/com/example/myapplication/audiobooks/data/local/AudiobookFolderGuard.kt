package com.example.myapplication.audiobooks.data.local

import android.net.Uri

/** A chosen audiobook shelf may be named Audiobooks, but a general storage root is rejected. */
internal object AudiobookFolderGuard {
    fun isAllowed(treeUri: Uri): Boolean {
        if (treeUri.scheme == "file") return true // app-owned test/import path
        if (treeUri.scheme != "content") return false
        val decoded = Uri.decode(treeUri.toString())
        val treeId = decoded.substringAfter("/tree/", "").substringBefore("/document/")
        val relativePath = treeId.substringAfter(':', "").trim('/')
        if (relativePath.isBlank()) return false
        val lower = relativePath.lowercase()
        if (lower.startsWith("android/")) return false
        return lower !in GENERAL_ROOTS
    }

    private val GENERAL_ROOTS = setOf(
        "download", "downloads", "documents", "dcim", "pictures", "movies", "music",
        "media", "podcasts", "ringtones", "alarms", "notifications", "recordings", "screenshots",
    )
}
