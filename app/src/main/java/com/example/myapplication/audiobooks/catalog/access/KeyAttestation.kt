package com.example.myapplication.audiobooks.catalog.access

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * Аттестация ключа Android (docs/adr/0003): телефон создаёт ключ в аппаратном хранилище и выдаёт цепочку сертификатов, подписанную
 * корнем Google. В листе цепочки записаны имя пакета, SHA-256 подписи APK и уровень защиты; `challenge` от сервера доказывает свежесть.
 * Сервер так проверяет, что запрос идёт от настоящего приложения, а не от скрипта, который достал адрес API из кода.
 */
internal object KeyAttestation {
    private const val ALIAS = "vetro_catalog_attestation"
    private const val MAX_CHALLENGE = 128

    /** Цепочка DER-сертификатов, лист первым. Бросает исключение, если у устройства нет аппаратной аттестации. */
    fun chain(challenge: ByteArray, preferStrongBox: Boolean = false): List<ByteArray> {
        require(challenge.size in 1..MAX_CHALLENGE) { "challenge must be 1..$MAX_CHALLENGE bytes" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(ALIAS)
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { generate(challenge, strongBox = true) }.onSuccess { return read(store) }
            store.deleteEntry(ALIAS)
        }
        generate(challenge, strongBox = false)
        return read(store)
    }

    private fun generate(challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun read(store: KeyStore): List<ByteArray> {
        val chain = store.getCertificateChain(ALIAS) ?: error("no certificate chain: attestation is not available")
        check(chain.size >= 2) { "chain has no attestation certificates (software keystore)" }
        return chain.map { it.encoded }
    }
}
