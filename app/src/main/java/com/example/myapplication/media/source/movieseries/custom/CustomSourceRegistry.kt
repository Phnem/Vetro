package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.network.AppJson
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.sdk.InstalledPackageFacts
import com.example.myapplication.media.source.sdk.PackageIntegrity
import com.example.myapplication.media.source.sdk.PackageMediaType
import com.example.myapplication.media.source.sdk.PackageOrigin
import com.example.myapplication.media.source.sdk.PackageStreamingProvider
import com.example.myapplication.media.source.sdk.PackageValidation
import com.example.myapplication.media.source.sdk.ProviderPackage
import com.example.myapplication.media.source.sdk.ProviderPackageRuntime
import com.example.myapplication.media.source.sdk.ProviderPackageValidator
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Outcome of parsing whatever the user pasted or imported. */
sealed interface SourceInstallResult {
    data class Installed(val source: InstalledSource) : SourceInstallResult
    data class Rejected(val reason: String) : SourceInstallResult
}

/** Что увидит пользователь перед установкой пакета v2: что он умеет, куда ходит, кем подписан. */
data class PackagePreview(
    val id: String,
    val name: String,
    val version: String,
    val author: String?,
    val mediaTypes: Set<PackageMediaType>,
    val capabilities: Set<com.example.myapplication.media.source.sdk.PackageCapability>,
    val hosts: List<String>,
    val needsKey: Boolean,
    val origin: PackageOrigin,
    val sha256: String,
    /** Установленная версия, если это обновление. */
    val replacesVersion: String?,
    val source: InstalledSource,
)

sealed interface PackageParse {
    data class Ready(val preview: PackagePreview) : PackageParse
    data class Rejected(val reason: String) : PackageParse
}

/**
 * Turns pasted text into an installable source.
 *
 * Both formats are validated before anything is stored, so an unusable definition is refused at the
 * point the user can still see why, rather than failing silently during playback later.
 */
class CustomSourceInstaller(
    private val json: Json = AppJson,
) {
    /** [sourceUrl] is where the definition was fetched from, used to refresh it later. */
    fun fromManifestJson(text: String, sourceUrl: String? = null): SourceInstallResult {
        val manifest = runCatching { json.decodeFromString<VetroSourceManifest>(text) }
            .getOrElse { return SourceInstallResult.Rejected("Not a valid Vetro manifest") }

        return when (val validation = ManifestValidator.validate(manifest)) {
            is ManifestValidation.Invalid -> SourceInstallResult.Rejected(validation.reason)
            is ManifestValidation.Valid -> SourceInstallResult.Installed(
                InstalledSource(
                    key = "manifest:${manifest.id}",
                    displayName = manifest.name,
                    definition = InstalledSourceDefinition.Manifest(validation.manifest),
                    sourceUrl = sourceUrl,
                )
            )
        }
    }

    fun fromStremioManifest(baseUrl: String, text: String): SourceInstallResult {
        val manifest = runCatching { json.decodeFromString<StremioManifest>(text) }
            .getOrElse { return SourceInstallResult.Rejected("Not a valid Stremio manifest") }

        return when (val result = StremioImporter.import(baseUrl, manifest)) {
            is StremioImport.Invalid -> SourceInstallResult.Rejected(result.reason)
            is StremioImport.Valid -> SourceInstallResult.Installed(
                InstalledSource(
                    key = "stremio:${manifest.id}",
                    displayName = manifest.name,
                    definition = InstalledSourceDefinition.Stremio(result.addon.baseUrl, manifest),
                    sourceUrl = baseUrl.trimEnd('/') + "/manifest.json",
                )
            )
        }
    }

    fun looksLikeJsonObject(text: String): Boolean =
        runCatching { json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

    /** Пакет v2 опознаётся по полю `format` верхнего уровня. */
    fun looksLikePackage(text: String): Boolean = runCatching {
        (json.parseToJsonElement(text) as? JsonObject)?.get("format")?.let { (it as? JsonPrimitive)?.intOrNull } != null
    }.getOrDefault(false)

    /**
     * Пакет v2: размер → разбор → строгая проверка → подпись → правило обновления → отпечаток. Ничего
     * не сохраняет: установка — только после того, как пользователь увидел [PackagePreview].
     */
    fun fromPackageJson(
        text: String,
        sourceUrl: String? = null,
        installed: InstalledPackageFacts? = null,
    ): PackageParse {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PACKAGE_BYTES) return PackageParse.Rejected("The package is larger than 256 KB")
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return PackageParse.Rejected("Not a JSON object")
        val pkg = runCatching { json.decodeFromJsonElement(ProviderPackage.serializer(), root) }
            .getOrElse { return PackageParse.Rejected("Not a valid Vetro provider package") }
        val valid = when (val v = ProviderPackageValidator.validate(pkg)) {
            is PackageValidation.Invalid -> return PackageParse.Rejected(v.reason)
            is PackageValidation.Valid -> v.pkg
        }
        val (origin, signatureProblem) = PackageIntegrity.verify(root, valid.signature)
        if (origin == null) return PackageParse.Rejected(signatureProblem ?: "Invalid signature")
        PackageIntegrity.updateProblem(installed, valid.version, origin)?.let { return PackageParse.Rejected(it) }
        val sha = PackageIntegrity.sha256(bytes)
        val signer = (origin as? PackageOrigin.Signed)?.publicKey
        return PackageParse.Ready(
            PackagePreview(
                id = valid.id,
                name = valid.name,
                version = valid.version,
                author = valid.author,
                mediaTypes = valid.mediaTypes,
                capabilities = valid.capabilities,
                hosts = valid.allowedHosts,
                needsKey = valid.auth != null,
                origin = origin,
                sha256 = sha,
                replacesVersion = installed?.version,
                source = InstalledSource(
                    key = packageKey(valid.id),
                    displayName = valid.name,
                    definition = InstalledSourceDefinition.Package(valid, sha, signer),
                    sourceUrl = sourceUrl,
                ),
            ),
        )
    }

    /** Picks the format from the payload rather than making the user declare it. */
    fun fromUnknownJson(text: String, sourceUrl: String? = null): SourceInstallResult {
        val asVetro = fromManifestJson(text, sourceUrl)
        if (asVetro is SourceInstallResult.Installed) return asVetro
        val base = sourceUrl?.removeSuffix("/manifest.json")
            ?: return asVetro
        val asStremio = fromStremioManifest(base, text)
        // Report whichever parser got further rather than a generic failure.
        return if (asStremio is SourceInstallResult.Installed) asStremio else asVetro
    }

    companion object {
        const val MAX_PACKAGE_BYTES = 256 * 1024

        fun packageKey(id: String) = "package:$id"
    }
}

/**
 * Builds live providers from the sources the user installed.
 *
 * Disabled sources produce no provider at all, so a source switched off in settings cannot reach the
 * network even by accident.
 */
class CustomSourceRegistry(
    private val store: InstalledSourceStore,
    private val client: HttpClient,
    private val secretProvider: suspend (String) -> String? = { null },
    /** Клиент пакетов v2 — без автоматических редиректов: их выполняет песочница, только в белый список. */
    private val packageClient: HttpClient = client,
) {
    suspend fun providers(): List<MovieSeriesStreamingProvider> {
        return store.all()
            .filter(InstalledSource::enabled)
            .mapNotNull(::providerFor)
    }

    private fun providerFor(source: InstalledSource): MovieSeriesStreamingProvider? =
        when (val definition = source.definition) {
            is InstalledSourceDefinition.Manifest -> CustomSourceProvider(
                manifest = definition.manifest,
                client = client,
                secretProvider = { secretProvider(source.key) },
            )

            is InstalledSourceDefinition.Stremio ->
                when (val imported = StremioImporter.import(definition.baseUrl, definition.manifest)) {
                    // Re-validated on every build: a definition stored by an older version must not
                    // bypass a rule added since.
                    is StremioImport.Valid -> StremioAddonProvider(imported.addon, client)
                    is StremioImport.Invalid -> null
                }

            // Перепроверяется при каждой сборке: пакет, сохранённый старой версией, не обойдёт новое правило.
            is InstalledSourceDefinition.Package -> when (val v = ProviderPackageValidator.validate(definition.pkg)) {
                is PackageValidation.Valid -> PackageStreamingProvider(
                    ProviderPackageRuntime(v.pkg, packageClient, secret = { secretProvider(source.key) }),
                )
                is PackageValidation.Invalid -> null
            }
        }
}
