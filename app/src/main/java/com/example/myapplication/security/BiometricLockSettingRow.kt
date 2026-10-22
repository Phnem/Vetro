package com.example.myapplication.security

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSwitch

@Composable
fun BiometricLockSettingRow(ru: Boolean, isDark: Boolean) {
    val context = LocalContext.current
    val manager = remember(context) { AppLockManager.get(context) }
    val enabled by manager.enabled.collectAsStateWithLifecycle()
    val change: (Boolean) -> Unit = { checked ->
        if (BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            AppLockActivity.changeSetting(context, checked)
        } else {
            Toast.makeText(context, AppLockActivity.unavailableMessage(ru), Toast.LENGTH_LONG).show()
        }
    }
    IosRow(title = if (ru) "Вход по биометрии" else "Biometric app lock",
        subtitle = if (ru) "Отпечаток пальца или лицо при входе в приложение" else "Fingerprint or face when opening the app",
        isDark = isDark, icon = Icons.Rounded.Fingerprint, iconWell = false,
        onClick = { change(!enabled) },
        trailing = { IosSwitch(checked = enabled, onCheckedChange = change) })
}
