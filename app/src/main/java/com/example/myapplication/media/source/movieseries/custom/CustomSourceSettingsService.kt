package com.example.myapplication.media.source.movieseries.custom

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One installed source as the settings screen sees it. */
data class CustomSourceSummary(
    val key: String,
    val displayName: String,
    val kindLabel: String,
    val enabled: Boolean,
    val sourceUrl: String?,
)

/** What happened when the user tried to add or refresh a source. */
sealed interface CustomSourceOutcome {
    data class Installed(val summary: CustomSourceSummary) : CustomSourceOutcome
    data class Rejected(val reason: String) : CustomSourceOutcome
    /** Пакет v2 разобран и проверен; установка — после подтверждения пользователем ([confirm]). */
    data class ReviewRequired(val preview: PackagePreview) : CustomSourceOutcome
}

/**
 * Add, refresh, enable, disable and remove user-installed sources.
 *
 * Definitions are always validated before they are stored, so the settings screen can show a real
 * reason instead of the source failing silently during playback much later.
 */
class CustomSourceSettingsService(
    private val store: InstalledSourceStore,
    private val installer: CustomSourceInstaller,
    private val client: HttpClient,
) {
    suspend fun summaries(): List<CustomSourceSummary> =
        store.all().map(InstalledSource::toSummary)

    /** Installs from a definition the user pasted or picked as a file. */
    suspend fun installFromText(text: String, sourceUrl: String? = null): CustomSourceOutcome {
        if (installer.looksLikePackage(text)) {
            val installed = installedFacts(text)
            return when (val parsed = installer.fromPackageJson(text, sourceUrl, installed)) {
                is PackageParse.Rejected -> CustomSourceOutcome.Rejected(parsed.reason)
                is PackageParse.Ready -> CustomSourceOutcome.ReviewRequired(parsed.preview)
            }
        }
        return when (val parsed = installer.fromUnknownJson(text, sourceUrl)) {
            is SourceInstallResult.Rejected -> CustomSourceOutcome.Rejected(parsed.reason)
            is SourceInstallResult.Installed ->
                CustomSourceOutcome.Installed(store.install(parsed.source).toSummary())
        }
    }

    /**
     * Установить просмотренный пакет. Правило обновления проверяется ещё раз: между просмотром и
     * подтверждением могла встать другая версия.
     */
    suspend fun confirm(preview: PackagePreview): CustomSourceOutcome {
        val current = store.all().firstOrNull { it.key == preview.source.key }?.packageFacts()
        com.example.myapplication.media.source.sdk.PackageIntegrity
            .updateProblem(current, preview.version, preview.origin)
            ?.let { return CustomSourceOutcome.Rejected(it) }
        return CustomSourceOutcome.Installed(store.install(preview.source).toSummary())
    }

    private suspend fun installedFacts(text: String): com.example.myapplication.media.source.sdk.InstalledPackageFacts? {
        val id = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["id"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrNull() ?: return null
        return store.all().firstOrNull { it.key == CustomSourceInstaller.packageKey(id) }?.packageFacts()
    }

    /** Installs from a link to the definition. */
    suspend fun installFromUrl(url: String): CustomSourceOutcome {
        val normalized = url.trim()
        val parsed = normalized.toHttpUrlOrNull()
            ?: return CustomSourceOutcome.Rejected("Not a valid link")
        // The definition decides what Vetro will talk to, so it must not arrive over plain HTTP
        // where anyone on the path could rewrite it.
        if (!parsed.isHttps) return CustomSourceOutcome.Rejected("The link must use https")

        val body = try {
            val response = client.get(normalized)
            if (!response.status.isSuccess()) {
                return CustomSourceOutcome.Rejected("Source replied HTTP ${response.status.value}")
            }
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CustomSourceOutcome.Rejected("Could not reach the link")
        }
        return installFromText(body, sourceUrl = normalized)
    }

    /** Re-fetches a source's definition from where it came from. */
    suspend fun refresh(key: String): CustomSourceOutcome {
        val existing = store.all().firstOrNull { it.key == key }
            ?: return CustomSourceOutcome.Rejected("Source is no longer installed")
        val url = existing.sourceUrl
            ?: return CustomSourceOutcome.Rejected("This source was added manually and has no link")
        return installFromUrl(url)
    }

    suspend fun setEnabled(key: String, enabled: Boolean) = store.setEnabled(key, enabled)

    suspend fun remove(key: String) = store.remove(key)
}

private fun InstalledSource.packageFacts(): com.example.myapplication.media.source.sdk.InstalledPackageFacts? =
    (definition as? InstalledSourceDefinition.Package)?.let {
        com.example.myapplication.media.source.sdk.InstalledPackageFacts(it.pkg.version, it.signerKey)
    }

private fun InstalledSource.toSummary(): CustomSourceSummary = CustomSourceSummary(
    key = key,
    displayName = displayName,
    kindLabel = when (definition) {
        is InstalledSourceDefinition.Manifest -> "Vetro"
        is InstalledSourceDefinition.Stremio -> "Stremio"
        is InstalledSourceDefinition.Package -> "Vetro ${definition.pkg.version}"
    },
    enabled = enabled,
    sourceUrl = sourceUrl,
)
