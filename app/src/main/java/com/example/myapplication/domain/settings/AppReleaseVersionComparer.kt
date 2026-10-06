package com.example.myapplication.domain.settings

/**
 * Compares GitHub tag / app [versionName] strings by the numbers after "v" only: a release is
 * newer when its major.minor.patch is greater. The suffix (Beta, Stable, ...) never decides, because
 * every release raises the number. The single exception is **Alpha**: a remote tag carrying it is
 * ignored and never offered.
 */
object AppReleaseVersionComparer {

    fun isRemoteSemanticallyNewer(localRaw: String, remoteRaw: String): Boolean = runCatching {
        val local = ParsedVersion.parse(localRaw) ?: return@runCatching false
        val remote = ParsedVersion.parse(remoteRaw) ?: return@runCatching false
        if (remote.isAlpha) return@runCatching false
        compare(remote.core, local.core) > 0
    }.getOrElse { false }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val major = a.first.compareTo(b.first)
        if (major != 0) return major
        val minor = a.second.compareTo(b.second)
        if (minor != 0) return minor
        return a.third.compareTo(b.third)
    }

    private data class ParsedVersion(
        val core: Triple<Int, Int, Int>,
        val isAlpha: Boolean,
    ) {
        companion object {
            fun parse(raw: String): ParsedVersion? {
                // Префикс снимается без учёта регистра: versionName долго был «V3.3.4-Beta», теги — «v3.3.4-Beta»,
                // и заглавная «V» превращала мажорную цифру в 0, отчего любой релиз казался новее.
                val clean = raw.trim().removePrefix("v").removePrefix("V").trim().ifBlank { return null }
                val dashParts = clean.split('-', limit = 2)
                val numbers = dashParts[0].trim().split('.').map { segment -> segment.toIntOrNull() ?: 0 }
                val suffix = dashParts.getOrElse(1) { "" }.lowercase()
                return ParsedVersion(
                    core = Triple(
                        numbers.getOrElse(0) { 0 },
                        numbers.getOrElse(1) { 0 },
                        numbers.getOrElse(2) { 0 },
                    ),
                    isAlpha = suffix.startsWith("alpha"),
                )
            }
        }
    }
}
