package com.example.myapplication.ui.login

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.myapplication.security.AppLockManager
import com.example.myapplication.security.findFragmentActivity
import com.example.myapplication.ui.searchdetails.AddPillPhase
import com.example.myapplication.ui.searchdetails.AddToCollectionPill
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.mascot.FingerprintMascot
import com.example.myapplication.ui.shared.mininotif.MiniNotif
import com.example.myapplication.ui.shared.mininotif.MiniNotifKind
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import kotlinx.coroutines.delay

/** Пауза на галочке, прежде чем начнётся линзовый переход. */
private const val CHECK_HOLD_MS = 800L

/**
 * Второй шаг после входа: предложение включить вход по биометрии. «Добавить» открывает системное окно
 * отпечатка, после успеха пилюля складывается в галочку и вызывается [onAccepted]; «Не сейчас» —
 * [onSkip]. [dissolve] — ход линзового перехода: пилюля растворяется вместе с ним.
 */
@Composable
fun BiometricOfferScreen(
    ru: Boolean,
    onAccepted: () -> Unit,
    onSkip: () -> Unit,
    dissolve: () -> Float,
) {
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    val ink = if (isDark) Color.White else Color(0xFF1B100D)
    var phase by remember { mutableStateOf(AddPillPhase.Idle) }

    // «Назад» здесь равно отказу: возвращаться на вход незачем, пользователь уже вошёл.
    BackHandler { if (phase == AddPillPhase.Idle) onSkip() }

    LaunchedEffect(phase) {
        if (phase == AddPillPhase.Done) {
            delay(CHECK_HOLD_MS)
            onAccepted()
        }
    }

    Box(Modifier.fillMaxSize()) {
        FlowingVetroBackground(isDark, Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                FingerprintMascot(isDark, Modifier.widthIn(max = 380.dp).fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (ru) "Входить по отпечатку?" else "Sign in with your fingerprint?",
                    color = ink,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (ru) {
                        "Vetro будет открываться по отпечатку или лицу. Это можно изменить в настройках."
                    } else {
                        "Vetro will open with your fingerprint or face. You can change this in Settings."
                    },
                    color = ink.copy(alpha = 0.6f),
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                )
            }
            AddToCollectionPill(
                phase = phase,
                label = if (ru) "Добавить вход по отпечатку" else "Add fingerprint sign-in",
                dissolve = dissolve,
                onClick = {
                    val activity = context.findFragmentActivity() ?: return@AddToCollectionPill
                    phase = AddPillPhase.Working
                    askFingerprint(
                        activity = activity,
                        ru = ru,
                        onSuccess = {
                            val saved = runCatching { AppLockManager.get(activity).authenticated(true) }.isSuccess
                            if (saved) {
                                phase = AddPillPhase.Done
                            } else {
                                phase = AddPillPhase.Idle
                                MiniNotif.show(
                                    activity,
                                    if (ru) "Не удалось сохранить настройку" else "Could not save this setting",
                                    MiniNotifKind.Error,
                                )
                            }
                        },
                        onFailure = { message ->
                            phase = AddPillPhase.Idle
                            if (message != null) MiniNotif.show(activity, message, MiniNotifKind.Error)
                        },
                    )
                },
            )
            Text(
                text = if (ru) "Не сейчас" else "Not now",
                color = ink.copy(alpha = 0.66f),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 16.dp)
                    .clip(CircleShape)
                    .fluidClickable(enabled = phase == AddPillPhase.Idle, onClick = onSkip)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Системное окно отпечатка. [onFailure] с null — пользователь сам закрыл окно, сообщать нечего. */
private fun askFingerprint(
    activity: FragmentActivity,
    ru: Boolean,
    onSuccess: () -> Unit,
    onFailure: (String?) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(code: Int, text: CharSequence) {
                val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                onFailure(if (cancelled) null else text.toString())
            }

            // Окно остаётся открытым и само ждёт следующую попытку.
            override fun onAuthenticationFailed() = Unit
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (ru) "Вход по отпечатку" else "Fingerprint sign-in")
            .setSubtitle(if (ru) "Отпечаток пальца или лицо" else "Fingerprint or face")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .setNegativeButtonText(if (ru) "Отмена" else "Cancel")
            .build(),
    )
}
