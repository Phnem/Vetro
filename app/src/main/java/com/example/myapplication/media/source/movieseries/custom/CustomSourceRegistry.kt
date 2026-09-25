package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.network.AppJson
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/** Outcome of parsing whatever the user pasted or imported. */
sealed interface SourceInstallResult {
    data class Installed(val source: InstalledSource) : SourceInstallResult
    data class Rejected(val reason: String) : SourceInstallResult
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
        }
}
