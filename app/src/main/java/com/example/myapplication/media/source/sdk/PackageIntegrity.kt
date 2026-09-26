package com.example.myapplication.media.source.sdk

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Чем подтверждено происхождение пакета. */
sealed interface PackageOrigin {
    /** Подписан; [publicKey] — base64 ключа автора. */
    data class Signed(val publicKey: String) : PackageOrigin
    data object Unsigned : PackageOrigin
}

/** Что известно об уже установленной версии — для проверки обновления. */
data class InstalledPackageFacts(val version: String, val signerKey: String?)

/**
 * Целостность пакета: SHA-256 исходных байтов (фиксируется при импорте), подпись автора и правило
 * обновления. Ключ автора запоминается при первом импорте (trust on first use): обновление, подписанное
 * другим ключом или без подписи, не принимается — чужой файл с тем же id не подменит источник.
 */
object PackageIntegrity {

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Канонический вид для подписи: объект без `signature`, ключи по алфавиту на всех уровнях, без
     * пробелов. Автор подписывает ровно эти байты, приложение проверяет их же — порядок полей и
     * форматирование файла на подпись не влияют.
     */
    fun canonical(root: JsonObject): String = buildString { write(JsonObject(root - "signature")) }

    /** null — подпись верна или её нет; иначе причина отказа. */
    fun verify(root: JsonObject, signature: PackageSignature?): Pair<PackageOrigin?, String?> {
        signature ?: return PackageOrigin.Unsigned to null
        val key = runCatching { Base64.getDecoder().decode(signature.publicKey) }.getOrNull()
        val sig = runCatching { Base64.getDecoder().decode(signature.value) }.getOrNull()
        if (key == null || key.size != 32) return null to "Signature public key must be 32 bytes of base64"
        if (sig == null || sig.size != 64) return null to "Signature must be 64 bytes of base64"
        val ok = runCatching {
            Ed25519Verify(key).verify(sig, canonical(root).toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false)
        return if (ok) PackageOrigin.Signed(signature.publicKey) to null else null to "Signature does not match the package"
    }

    /** null — обновление допустимо; иначе причина отказа. */
    fun updateProblem(installed: InstalledPackageFacts?, candidateVersion: String, origin: PackageOrigin): String? {
        installed ?: return null
        installed.signerKey?.let { pinned ->
            if (origin !is PackageOrigin.Signed) return "The installed package is signed; an unsigned update is refused"
            if (origin.publicKey != pinned) return "The update is signed by a different key"
        }
        if (compareVersions(candidateVersion, installed.version) < 0) {
            return "Version $candidateVersion is older than the installed ${installed.version}"
        }
        return null
    }

    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun StringBuilder.write(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) append(',')
                    append(JsonPrimitive(k).toString()).append(':')
                    write(e.getValue(k))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    write(v)
                }
                append(']')
            }
            JsonNull -> append("null")
            is JsonPrimitive -> append(e.toString())
        }
    }
}
