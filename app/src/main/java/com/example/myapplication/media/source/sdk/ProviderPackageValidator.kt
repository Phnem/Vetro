package com.example.myapplication.media.source.sdk

import com.example.myapplication.media.source.movieseries.custom.AuthKind

sealed interface PackageValidation {
    data class Valid(val pkg: ProviderPackage) : PackageValidation
    data class Invalid(val reason: String) : PackageValidation
}

/**
 * Строгая проверка пакета до установки. Пакет приходит из интернета, поэтому любое неясное правило —
 * чужая уязвимость: всё, что не разрешено явно, отклоняется с понятной причиной.
 */
object ProviderPackageValidator {

    private val SAFE_ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    private val SEMVER = Regex("""\d{1,4}\.\d{1,4}\.\d{1,4}""")
    private val HOST = Regex("""[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+|localhost""")
    private val PLACEHOLDER = Regex("""\{([A-Za-z]+)\}""")

    const val MAX_HOSTS = 16
    const val MAX_TEMPLATE = 512

    private val ID_PLACEHOLDERS = mapOf(
        "tmdbId" to ExternalIdKind.TMDB,
        "imdbId" to ExternalIdKind.IMDB,
        "anilistId" to ExternalIdKind.ANILIST,
        "malId" to ExternalIdKind.MAL,
        "kinopoiskId" to ExternalIdKind.KINOPOISK,
        "shikimoriId" to ExternalIdKind.SHIKIMORI,
    )

    /** Какие подстановки допустимы в каждой операции; всё прочее — ошибка. */
    private val SEARCH_PLACEHOLDERS = ID_PLACEHOLDERS.keys + "query"
    private val UNITS_PLACEHOLDERS = setOf("titleId")
    private val STREAMS_PLACEHOLDERS = ID_PLACEHOLDERS.keys + setOf("unitId", "season", "episode")
    private val PAGES_PLACEHOLDERS = setOf("unitId")

    fun validate(pkg: ProviderPackage): PackageValidation =
        fail(pkg)?.let { PackageValidation.Invalid(it) } ?: PackageValidation.Valid(pkg)

    private fun fail(p: ProviderPackage): String? {
        if (p.format != PACKAGE_FORMAT) return "Unsupported package format ${p.format}"
        if (p.sdk.min > p.sdk.max) return "sdk.min is greater than sdk.max"
        if (PROVIDER_SDK_VERSION !in p.sdk.min..p.sdk.max) {
            return "Package needs provider SDK ${p.sdk.min}..${p.sdk.max}, this app has $PROVIDER_SDK_VERSION"
        }
        if (!p.id.matches(SAFE_ID)) return "Invalid package id"
        if (!p.version.matches(SEMVER)) return "version must look like 1.2.3"
        if (p.name.isBlank() || p.name.length > 64) return "Package name must be 1..64 characters"
        p.homepage?.let { if (!it.startsWith("https://")) return "homepage must use https" }
        if (p.mediaTypes.isEmpty()) return "Package declares no media types"
        if (p.capabilities.isEmpty()) return "Package declares no capabilities"

        hostsProblem(p)?.let { return it }
        p.auth?.let { auth ->
            if (auth.name.isBlank() || auth.name.any { it == '\r' || it == '\n' || it == ':' }) {
                return "Invalid auth parameter name"
            }
            if (auth.kind == AuthKind.QUERY && p.allowedHosts.any { !isPrivateHost(it) }) {
                return "Query-parameter auth is not allowed for a public host"
            }
        }
        if (p.rateLimit.perSecond <= 0.0 || p.rateLimit.perSecond > 10.0) return "rateLimit.perSecond must be in (0, 10]"
        if (p.rateLimit.burst < 1.0 || p.rateLimit.burst > 20.0) return "rateLimit.burst must be in [1, 20]"
        p.signature?.let { if (it.alg != "ed25519") return "Only ed25519 signatures are supported" }

        operationsProblem(p)?.let { return it }
        return null
    }

    private fun hostsProblem(p: ProviderPackage): String? {
        if (p.allowedHosts.isEmpty()) return "allowedHosts must list every host the package talks to"
        if (p.allowedHosts.size > MAX_HOSTS) return "Too many allowed hosts"
        p.allowedHosts.forEach { host ->
            if (host != host.lowercase()) return "Host must be lowercase: $host"
            if (!host.matches(HOST)) return "Invalid host: $host"
            if (p.allowInsecureHttp && !isPrivateHost(host)) {
                return "Plain http is only allowed for a local address, not $host"
            }
        }
        return null
    }

    private fun operationsProblem(p: ProviderPackage): String? {
        val caps = p.capabilities
        val ops = p.operations
        val search = ops.search
        val units = ops.units
        val streams = ops.streams
        val pages = ops.pages

        // Операция без возможности — недекларированное поведение; возможность без операции — пустое обещание.
        val searches = PackageCapability.SEARCH in caps || PackageCapability.SEARCH_BY_EXTERNAL_ID in caps
        if (searches != (search != null)) return "search operation and SEARCH capability must go together"
        if ((PackageCapability.UNITS in caps) != (units != null)) return "units operation and UNITS capability must go together"
        val needsStreams = PackageCapability.STREAMS in caps || PackageCapability.AUDIO in caps
        if (needsStreams != (streams != null)) return "streams operation and STREAMS/AUDIO capability must go together"
        if ((PackageCapability.PAGES in caps) != (pages != null)) return "pages operation and PAGES capability must go together"
        if (PackageCapability.PAGES in caps && PackageMediaType.MANGA !in p.mediaTypes) return "PAGES requires the MANGA media type"
        if (PackageCapability.AUDIO in caps && PackageMediaType.AUDIOBOOK !in p.mediaTypes) return "AUDIO requires the AUDIOBOOK media type"
        if (PackageCapability.SUBTITLES in caps && streams?.subtitles == null) return "SUBTITLES is declared but streams map no subtitles"
        if (PackageCapability.VARIANTS in caps && streams?.audioLanguage == null && streams?.subtitleLanguage == null) {
            return "VARIANTS is declared but streams map no audio or subtitle language"
        }

        search?.let { op ->
            templateProblem(op.request, SEARCH_PLACEHOLDERS, p)?.let { return it }
            pointersProblem(listOf(op.items, op.id, op.title) + listOfNotNull(op.year, op.type) + op.externalIds.values)?.let { return it }
            val used = placeholders(op.request.url)
            if (PackageCapability.SEARCH in caps && "query" !in used) return "SEARCH needs {query} in the search request"
            if (PackageCapability.SEARCH_BY_EXTERNAL_ID in caps) {
                if (op.externalIds.isEmpty()) return "SEARCH_BY_EXTERNAL_ID needs externalIds pointers in search results"
                if (!op.externalIds.keys.all { it in p.externalIds }) return "search maps an external id the package does not declare"
            }
        }
        units?.let { op ->
            templateProblem(op.request, UNITS_PLACEHOLDERS, p)?.let { return it }
            if ("titleId" !in placeholders(op.request.url)) return "units request must use {titleId}"
            pointersProblem(listOf(op.items, op.id, op.number) + listOfNotNull(op.season, op.title))?.let { return it }
        }
        streams?.let { op ->
            templateProblem(op.request, STREAMS_PLACEHOLDERS, p)?.let { return it }
            val used = placeholders(op.request.url)
            if ("unitId" in used && PackageCapability.UNITS !in caps) return "{unitId} in streams requires UNITS"
            if ("unitId" !in used && used.none { it in ID_PLACEHOLDERS }) {
                return "streams request must address a unit or an external id"
            }
            val subtitlePointers = op.subtitles?.let { listOf(it.items, it.url, it.language) + listOfNotNull(it.format) }.orEmpty()
            pointersProblem(
                listOf(op.items, op.url) + listOfNotNull(op.label, op.resolution, op.kind, op.audioLanguage, op.subtitleLanguage, op.downloadAllowed) +
                    subtitlePointers,
            )?.let { return it }
        }
        pages?.let { op ->
            templateProblem(op.request, PAGES_PLACEHOLDERS, p)?.let { return it }
            if ("unitId" !in placeholders(op.request.url)) return "pages request must use {unitId}"
            pointersProblem(listOf(op.items, op.url))?.let { return it }
        }
        return null
    }

    private fun templateProblem(request: PackageRequest, allowed: Set<String>, p: ProviderPackage): String? {
        val t = request.url
        if (t.length > MAX_TEMPLATE) return "Request template is too long"
        if (request.method.uppercase() !in setOf("GET", "POST")) return "Only GET and POST requests are allowed"
        val scheme = when {
            t.startsWith("https://") -> "https://"
            t.startsWith("http://") && p.allowInsecureHttp -> "http://"
            else -> return "Request must be an absolute https URL: $t"
        }
        val rest = t.removePrefix(scheme)
        val authority = rest.substringBefore('/')
        // Хост — буквальный: подстановка в хост увела бы запрос куда угодно, оставаясь «настроенным» источником.
        if (authority.contains('{')) return "Placeholders are not allowed in the host: $t"
        if (authority.contains('@')) return "Request must not embed credentials: $t"
        if (!rest.contains('/')) return "Request must have a path: $t"
        if (t.contains("..")) return "Request must not contain '..': $t"
        if (t.contains('#')) return "Request must not contain a fragment: $t"
        val host = authority.substringBefore(':')
        if (host !in p.allowedHosts) return "Request host $host is not in allowedHosts"
        placeholders(t).forEach { name ->
            if (name !in allowed) return "Placeholder {$name} is not allowed here"
            ID_PLACEHOLDERS[name]?.let { kind ->
                if (kind !in p.externalIds) return "{$name} needs \"${kind.name.lowercase()}\" in externalIds"
            }
        }
        return null
    }

    private fun pointersProblem(pointers: List<String>): String? =
        pointers.firstOrNull { !it.startsWith("/") }?.let { "JSON pointer must start with '/': $it" }

    internal fun placeholders(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    internal fun isPrivateHost(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".local") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host == "127.0.0.1" ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
}
