package com.example.myapplication.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.data.local.AppThemePrefs
import com.example.myapplication.data.models.AppTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.OneUiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import java.util.Locale

/** Opaque app gate; successful system authentication is its only route into protected screens. */
class AppLockActivity : FragmentActivity() {
    private val manager by lazy { AppLockManager.get(this) }
    private lateinit var prompt: BiometricPrompt
    private var ru by mutableStateOf(Locale.getDefault().language == "ru")
    private var theme by mutableStateOf(AppTheme.SYSTEM)
    private var message by mutableStateOf<String?>(null)
    private var running by mutableStateOf(false)
    private var desired: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        desired = if (intent.hasExtra(CHANGE_ENABLED)) intent.getBooleanExtra(CHANGE_ENABLED, false) else null
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                running = false
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                try {
                    manager.authenticated(desired)
                    finish()
                } catch (_: Exception) {
                    message = if (ru) "Не удалось сохранить настройку. Попробуйте ещё раз." else "Could not save this setting. Try again."
                }
            }
            override fun onAuthenticationError(code: Int, text: CharSequence) {
                running = false
                if (desired != null && !manager.locked) finish()
                else message = text.toString()
            }
            override fun onAuthenticationFailed() {
                // The system prompt stays open and handles the next fingerprint/face attempt.
            }
        })
        setContent {
            val dark = when (theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            OneUiTheme(darkTheme = dark) {
                BackHandler { exit() }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Fingerprint, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(if (ru) "Вход в Vetro" else "Unlock Vetro", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(message ?: if (ru) "Подтвердите вход отпечатком пальца или лицом" else "Confirm with your fingerprint or face",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::authenticate, enabled = !running) {
                        Text(if (ru) "Подтвердить" else "Authenticate")
                    }
                    if (BiometricManager.from(this@AppLockActivity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                        TextButton(onClick = ::enroll) { Text(if (ru) "Настроить биометрию" else "Set up biometrics") }
                    }
                    TextButton(onClick = ::exit) { Text(if (ru) "Выйти" else "Exit") }
                }
            }
        }
        lifecycleScope.launch {
            val preferences = runCatching {
                GlobalContext.get().get<DataStore<Preferences>>(named("settings")).data.first()
            }.getOrNull()
            if (preferences != null) {
                ru = AppLanguagePrefs.from(preferences) == AppLanguage.RU
                theme = AppThemePrefs.from(preferences)
            }
            if (savedInstanceState == null) lifecycle.withResumed { authenticate() }
        }
    }

    private fun authenticate() {
        if (running) return
        val availability = BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            message = unavailableMessage(ru)
            return
        }
        running = true
        message = null
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (ru) "Вход в Vetro" else "Unlock Vetro")
            .setSubtitle(if (ru) "Отпечаток пальца или лицо" else "Fingerprint or face")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .setNegativeButtonText(if (ru) "Отмена" else "Cancel")
            .build())
    }

    private fun exit() {
        if (desired != null && !manager.locked) finish() else moveTaskToBack(true)
    }

    private fun enroll() {
        val intent = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_WEAK)
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
        startActivity(intent)
    }

    companion object {
        private const val CHANGE_ENABLED = "change_biometric_lock"
        internal fun changeSetting(context: Context, enabled: Boolean) {
            context.startActivity(Intent(context, AppLockActivity::class.java).putExtra(CHANGE_ENABLED, enabled))
        }
        internal fun unavailableMessage(ru: Boolean) = if (ru)
            "Биометрия недоступна. Настройте отпечаток пальца или лицо в настройках устройства."
        else "Biometrics are unavailable. Set up a fingerprint or face in your device settings."
    }
}
