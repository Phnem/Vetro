package com.example.myapplication.ui.login

import com.example.myapplication.ui.shared.centeredContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.AuthProviderIcon
import com.example.myapplication.ui.shared.components.SpringyOutlinedField
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.mascot.LoginMascot
import com.example.myapplication.ui.shared.mascot.PeekAspect
import com.example.myapplication.ui.shared.mascot.PeekSeatFraction
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.WelcomeStrings
import com.phnem.vetro.R

/**
 * Экран входа: на буквах названия сидит маскот, внизу три пилюли — Google, GitHub, почта. Почта
 * раскрывает форму на месте пилюль; пока в фокусе пароль, маскот закрывает глаза ладонями.
 */
@Composable
fun LoginScreen(
    strings: WelcomeStrings,
    ru: Boolean,
    onGoogle: () -> Unit,
    onGithub: () -> Unit,
    onEmail: (String, String) -> Unit,
    onForgotPassword: (String) -> Unit,
    onNeedEmail: () -> Unit,
    onGuest: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    var emailMode by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val ink = if (isDark) Color.White else Color(0xFF1B100D)

    fun leaveEmail() {
        focus.clearFocus()
        passwordFocused = false
        emailMode = false
    }
    BackHandler(enabled = emailMode) { leaveEmail() }

    Box(Modifier.fillMaxSize()) {
        FlowingVetroBackground(isDark, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val full = maxHeight
            val top by animateDpAsState(if (emailMode) 0.dp else 40.dp, label = "loginTop")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .centeredContent(480.dp)
                    .heightIn(min = full)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(top))
                    TitleWithMascot(covered = emailMode && passwordFocused, isDark = isDark, compact = emailMode)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = strings.appSubtitle,
                        color = ink.copy(alpha = 0.62f),
                        fontFamily = SnProFamily,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                AnimatedContent(
                    targetState = emailMode,
                    transitionSpec = {
                        ((fadeIn() + slideInVertically { it / 6 }) togetherWith fadeOut()) using SizeTransform(clip = false)
                    },
                    label = "loginActions",
                ) { form ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!form) {
                            ProviderPill(
                                text = strings.signInGoogle,
                                container = Color.White,
                                content = Color(0xFF1F1F1F),
                                outline = Color.Black.copy(alpha = 0.08f),
                                onClick = onGoogle,
                                icon = { AuthProviderIcon(R.drawable.ic_google, Modifier.size(20.dp), isDarkTheme = false) },
                            )
                            ProviderPill(
                                text = strings.signInGithub,
                                container = Color(0xFF1F2328),
                                content = Color.White,
                                outline = Color.White.copy(alpha = 0.14f),
                                onClick = onGithub,
                                icon = { AuthProviderIcon(R.drawable.ic_github, Modifier.size(20.dp), isDarkTheme = true) },
                            )
                            ProviderPill(
                                text = if (ru) "Войти по электронной почте" else "Sign in with email",
                                container = BrandOrange,
                                content = Color.White,
                                outline = Color.Transparent,
                                onClick = { emailMode = true },
                                icon = { Icon(Icons.Rounded.Mail, null, Modifier.size(20.dp), tint = Color.White) },
                            )
                            TextAction(strings.continueGuest, ink.copy(alpha = 0.72f), onGuest)
                            Text(
                                text = strings.privacyNote,
                                color = ink.copy(alpha = 0.45f),
                                fontFamily = SnProFamily,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            val field = if (isDark) Color(0xFF140B09) else Color.White
                            val muted = ink.copy(alpha = 0.55f)
                            SpringyOutlinedField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(strings.emailLabel, color = muted) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                leadingIcon = { Icon(Icons.Rounded.Mail, null, tint = muted) },
                                containerColor = field,
                                contentColor = ink,
                                mutedColor = muted,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                            )
                            SpringyOutlinedField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(strings.passwordLabel, color = muted) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = muted) },
                                containerColor = field,
                                contentColor = ink,
                                mutedColor = muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { passwordFocused = it.hasFocus },
                                shape = RoundedCornerShape(18.dp),
                            )
                            val ready = email.isNotBlank() && password.isNotBlank()
                            ProviderPill(
                                text = strings.signInButton,
                                container = if (ready) BrandOrange else ink.copy(alpha = 0.14f),
                                content = if (ready) Color.White else ink.copy(alpha = 0.45f),
                                outline = Color.Transparent,
                                enabled = ready,
                                onClick = {
                                    focus.clearFocus()
                                    onEmail(email, password)
                                },
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextAction(if (ru) "Другие способы" else "Other options", ink.copy(alpha = 0.72f)) { leaveEmail() }
                                TextAction(strings.forgotPassword, BrandOrange) {
                                    if (email.isBlank()) onNeedEmail() else onForgotPassword(email)
                                }
                            }
                            Text(
                                text = strings.oauthPasswordHint,
                                color = ink.copy(alpha = 0.45f),
                                fontFamily = SnProFamily,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Цвета заголовка: чернильный на светлом фоне, жжёный оранжевый на тёмном. */
private val MidnightInk = Color(0xFF04061B)
private val BurntOrange = Color(0xFFFF7100)

private const val TitleTop = "VETRO"
private const val TitleBottom = "COLLECTION"

/** Крупное название и маскот, сидящий на верхней кромке букв (ноги свисают поверх них). */
@Composable
private fun TitleWithMascot(covered: Boolean, isDark: Boolean, compact: Boolean) {
    val mascotWidth: Dp by animateDpAsState(if (compact) 200.dp else 244.dp, label = "loginMascot")
    val titleFraction by animateFloatAsState(if (compact) 0.78f else 1f, label = "loginTitle")
    val mascotHeight = mascotWidth / PeekAspect
    // Сиденье — линия внутри холста маскота; всё, что ниже неё (ноги), ложится поверх букв.
    // Холст названия начинается точно по верху прописных, поэтому корпус садится прямо на них.
    val overlap = mascotHeight * (1f - PeekSeatFraction) - 4.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(-overlap),
    ) {
        // Сдвиг вправо: маскот сидит на конце слова, и свешенные ноги не закрывают «O».
        LoginMascot(
            covered = covered,
            isDark = isDark,
            modifier = Modifier.width(mascotWidth).offset(x = mascotWidth * 0.34f).zIndex(1f),
        )
        HeavyTitle(
            color = if (isDark) BurntOrange else MidnightInk,
            modifier = Modifier.fillMaxWidth(titleFraction),
        )
    }
}

/**
 * Две строки капсом, жирные и плотные: кегль подбирается так, чтобы длинная строка заняла всю
 * ширину. Рисуется напрямую по глифам — верх букв совпадает с верхом холста, а межстрочный
 * просвет не зависит от метрик шрифта.
 */
@Composable
private fun HeavyTitle(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.russo_one)
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val metrics = remember(widthPx) {
            paint.textSize = 100f
            val size = 100f * widthPx / paint.measureText(TitleBottom)
            paint.textSize = size
            val bounds = android.graphics.Rect()
            paint.getTextBounds(TitleTop, 0, TitleTop.length, bounds)
            size to -bounds.top.toFloat()
        }
        val (textSize, capHeight) = metrics
        val gap = capHeight * 0.18f
        val height = with(LocalDensity.current) { (capHeight * 2f + gap + capHeight * 0.06f).toDp() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Vetro Collection" },
        ) {
            paint.textSize = textSize
            paint.color = color.toArgb()
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(TitleTop, size.width / 2f, capHeight, paint)
                canvas.nativeCanvas.drawText(TitleBottom, size.width / 2f, capHeight * 2f + gap, paint)
            }
        }
    }
}

@Composable
private fun ProviderPill(
    text: String,
    container: Color,
    content: Color,
    outline: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .fluidClickable(scaleDown = 0.96f, enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, outline, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        icon?.invoke()
        Text(
            text = text,
            color = content,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(CircleShape)
            .fluidClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
