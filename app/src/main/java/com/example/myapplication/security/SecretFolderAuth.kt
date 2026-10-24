package com.example.myapplication.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Биометрия для секретной папки: тот же системный промпт, что у входа в приложение
 * ([AppLockActivity]), но без отдельного экрана — он показывается поверх «засова».
 */
object SecretFolderAuth {

    /** Запускает системный промпт. false — биометрия недоступна, причина уже ушла в [onFailure]. */
    fun authenticate(
        activity: FragmentActivity,
        ru: Boolean,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ): Boolean {
        if (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
            onFailure(AppLockActivity.unavailableMessage(ru))
            return false
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(code: Int, text: CharSequence) {
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED
                    onFailure(if (cancelled) (if (ru) "Вход отменён" else "Sign-in cancelled") else text.toString())
                }

                // Промпт остаётся открытым и сам ждёт следующую попытку.
                override fun onAuthenticationFailed() = Unit
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (ru) "Секретная папка" else "Secret folder")
                .setSubtitle(if (ru) "Отпечаток пальца или лицо" else "Fingerprint or face")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setNegativeButtonText(if (ru) "Отмена" else "Cancel")
                .build(),
        )
        return true
    }
}

/** Activity, в которой живёт Compose-контент: промпту нужен именно FragmentActivity. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
