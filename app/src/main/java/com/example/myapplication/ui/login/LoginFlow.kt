package com.example.myapplication.ui.login

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import com.example.myapplication.security.AppLockManager

/** false — вернуть прежний экран входа: `WelcomeScreen.kt` не тронут, меняется только эта строка. */
const val NEW_LOGIN_SCREEN = true

/**
 * Предложение входа по биометрии после первого входа в аккаунт. Метка «ждёт показа» ставится
 * перед запуском входа и переживает пересоздание активности и процесса: OAuth уходит в браузер и
 * возвращается ссылкой, и приложение к этому моменту может быть уже выгружено.
 */
object BiometricOffer {
    private const val PREFS = "auth_prefs"
    private const val KEY = "pending_biometric_offer"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markPending(context: Context) = prefs(context).edit().putBoolean(KEY, true).apply()

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    fun isPending(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    /** Есть что предложить: биометрия настроена на телефоне и замок приложения ещё не включён. */
    fun canOffer(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS &&
            !AppLockManager.get(context).enabled.value
}
