package com.example.myapplication.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class GithubReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val body: String? = null,
    /** APK релиза: URL совпадает с [downloadUrl], размер — для UI и проверки целостности. */
    val apkAsset: GithubAsset? = null,
    /** Имя файла APK; null, если у релиза нет APK. */
    val apkName: String? = null,
    /** SHA-256 APK в нижнем регистре, если GitHub его сообщил (поле `digest`); иначе null. */
    val sha256: String? = null,
)

/**
 * Разбор ответа `GET /repos/{owner}/{repo}/releases`. Отдельно от сети, чтобы проверять на образцах.
 *
 * Берётся самый свежий опубликованный релиз (черновики API не отдаёт без токена). Среди вложений
 * выбирается именно APK, а не первое попавшееся: в релиз могут положить и контрольные суммы, и
 * исходники.
 */
object GithubReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(response: String): GithubReleaseInfo? {
        val releases = json.parseToJsonElement(response) as? JsonArray ?: return null
        val root = releases.firstOrNull { (it as? JsonObject)?.text("draft") != "true" }
            ?.jsonObject ?: return null
        val tagName = root.text("tag_name").orEmpty()
        if (tagName.isEmpty()) return null
        val htmlUrl = root.text("html_url").orEmpty()
        val body = root.text("body")
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it.text("name").orEmpty().endsWith(".apk", ignoreCase = true) }
        val downloadUrl = apk?.text("browser_download_url").orEmpty()
        val asset = if (downloadUrl.isNotEmpty()) {
            GithubAsset(browserDownloadUrl = downloadUrl, size = (apk?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L)
        } else {
            null
        }
        return GithubReleaseInfo(
            tagName = tagName,
            htmlUrl = htmlUrl,
            downloadUrl = downloadUrl,
            body = body,
            apkAsset = asset,
            apkName = apk?.text("name"),
            sha256 = apk?.text("digest")?.let(::sha256Of),
        )
    }

    /** `digest` приходит как `sha256:<hex>`; другие алгоритмы и мусор не принимаются. */
    fun sha256Of(digest: String): String? {
        if (!digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
