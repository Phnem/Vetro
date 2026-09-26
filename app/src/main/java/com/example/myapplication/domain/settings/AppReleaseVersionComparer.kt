package com.example.myapplication.domain.settings

/**
 * Compares GitHub tag / app [versionName] strings so that **Stable**, empty suffix, and **release**
 * align with "released" builds, while **debug** / **dev** / **alpha** / **beta** / **rc** behave as
 * local/pre-release: same **major.minor.patch** ⇒ no upgrade banner (plan §2).
 */
object AppReleaseVersionComparer {

    private val releaseSynonyms = setOf("", "stable", "release")

    private val prereleasePrefixes = listOf(
        "debug", "dev", "alpha", "beta", "rc", "snapshot", "preview", "internal",
    )

    fun isRemoteSemanticallyNewer(localRaw: String, remoteRaw: String): Boolean = runCatching {
        val local = ParsedVersion.parse(localRaw) ?: return@runCatching false
        val remote = ParsedVersion.parse(remoteRaw) ?: return@runCatching false

        val coreCmp = compareCoreRemoteMinusLocal(remote.core, local.core)
        when {
            coreCmp > 0 -> true
            coreCmp < 0 -> false
            else -> shouldOfferUpdateSameTriple(local.suffixRaw, remote.suffixRaw)
        }
    }.getOrElse { false }

    private fun shouldOfferUpdateSameTriple(localSuffixRaw: String, remoteSuffixRaw: String): Boolean {
        val lt = classifySuffixTier(localSuffixRaw)
        val rt = classifySuffixTier(remoteSuffixRaw)
        if (lt == SuffixTier.Release && rt == SuffixTier.Release) return false
        if ((lt == SuffixTier.Release && rt == SuffixTier.Prerelease) ||
            (lt == SuffixTier.Prerelease && rt == SuffixTier.Release)
        ) {
            return false
        }
        if (lt == SuffixTier.Prerelease && rt == SuffixTier.Prerelease) return false

        if (lt != SuffixTier.Other || rt != SuffixTier.Other) return false
        val r = remoteSuffixRaw.lowercase()
        val l = localSuffixRaw.lowercase()
        return r > l
    }

    private fun classifySuffixTier(suffixRaw: String): SuffixTier {
        val s = suffixRaw.lowercase().trim()
        if (releaseSynonyms.contains(s)) return SuffixTier.Release
        if (prereleasePrefixes.any { p ->
                s == p || s.startsWith("$p.") || s.startsWith("$p-") || (p.length > 2 && s.startsWith(p))
            }
        ) {
            return SuffixTier.Prerelease
        }
        return if (s.isEmpty()) SuffixTier.Release else SuffixTier.Other
    }

    private enum class SuffixTier { Release, Prerelease, Other }

    private fun compareCoreRemoteMinusLocal(
        remote: Triple<Int, Int, Int>,
        local: Triple<Int, Int, Int>,
    ): Int {
        val rMajor = remote.first - local.first
        if (rMajor != 0) return rMajor
        val rMinor = remote.second - local.second
        if (rMinor != 0) return rMinor
        return remote.third - local.third
    }

    private data class ParsedVersion(
        val core: Triple<Int, Int, Int>,
        val suffixRaw: String,
    ) {
        companion object {
            fun parse(raw: String): ParsedVersion? {
                // Префикс снимается без учёта регистра: versionName долго был «V3.3.4-Beta», теги — «v3.3.4-Beta»,
                // и заглавная «V» превращала мажорную цифру в 0, отчего любой релиз казался новее.
                val clean = raw.trim().removePrefix("v").removePrefix("V").trim().ifBlank { return null }
                val dashParts = clean.split('-', limit = 2)
                val corePart = dashParts[0].trim()
                val suffixRaw = dashParts.getOrElse(1) { "" }.trim()
                val numbers = corePart.split('.').map { segment -> segment.toIntOrNull() ?: 0 }
                val major = numbers.getOrElse(0) { 0 }
                val minor = numbers.getOrElse(1) { 0 }
                val patch = numbers.getOrElse(2) { 0 }
                return ParsedVersion(Triple(major, minor, patch), suffixRaw)
            }
        }
    }
}
