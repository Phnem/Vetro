package com.example.myapplication.manga.translate

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.myapplication.data.ai.AiCredentialsStore
import com.phnem.vetro.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/** Почему автоперевод включить нельзя. Каждой причине - своя плашка в настройках. */
enum class UnavailableReason {
    /** Сборка подписана не ключом автора (F-Droid и другие пересборки). */
    UNOFFICIAL_BUILD,

    /** Нет ни одного подключённого ИИ-провайдера: перевод идёт через ключ пользователя. */
    NO_AI_KEY,
}

sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: UnavailableReason) : Availability
}

/**
 * Подпись приложения: сборка, подписанная ключом автора, - GitHub-релиз, Obtainium, Komi Store.
 * F-Droid собирает и подписывает APK своим ключом, поэтому отпечаток другой и функция там
 * выключена. Это правило политики дистрибуции, а не защита: пересборка из исходников с подменой
 * списка ниже его обходит, и препятствовать этому в открытом проекте бессмысленно.
 */
class OfficialBuild(private val context: Context) {

    val isOfficial: Boolean by lazy { BuildConfig.DEBUG || matches(signingFingerprints(), OFFICIAL_SHA256) }

    @Suppress("DEPRECATION")
    private fun signingFingerprints(): List<String> {
        val packageManager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            // Текущие подписанты APK; при нескольких проверяются все.
            info.signingInfo?.apkContentsSigners ?: return emptyList()
        } else {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
    }

    companion object {
        /**
         * SHA-256 сертификата, которым подписаны релизы автора (CN=Evgeniy Idelchik). Публичное
         * значение: его же показывает `apksigner verify --print-certs` на любом релизном APK.
         * Несколько записей - на случай смены ключа: старый и новый должны жить рядом.
         */
        val OFFICIAL_SHA256: Set<String> = setOf(
            "e80ab80fc11c626633fb96cad097522b07712e13cc7086b5755d22e023943894",
        )

        /** Сборка официальная, если подпись есть и ВСЕ подписанты - наши. */
        fun matches(fingerprints: Collection<String>, official: Set<String>): Boolean =
            fingerprints.isNotEmpty() && fingerprints.all { it.lowercase() in official }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Можно ли вообще включить автоперевод: официальная сборка и подключённый BYOK. Порядок причин -
 * по важности для пользователя: с неофициальной сборкой ключ ничего не изменит.
 */
class AutoTranslateGate(
    private val officialBuild: OfficialBuild,
    private val credentials: AiCredentialsStore,
) {
    fun availability(): Availability = when {
        !officialBuild.isOfficial -> Availability.Unavailable(UnavailableReason.UNOFFICIAL_BUILD)
        credentials.getAllConnectedProviders().isEmpty() -> Availability.Unavailable(UnavailableReason.NO_AI_KEY)
        else -> Availability.Available
    }

    /** Меняется при подключении и отключении ключей. */
    val availabilityFlow: Flow<Availability> = credentials.connectedProviders.map { availability() }
}
