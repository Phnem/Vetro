package com.example.myapplication.ui.details.share

import android.graphics.Bitmap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.audiobooks.ui.home.OrangeButton
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.mininotif.MiniNotif
import com.example.myapplication.ui.shared.mininotif.MiniNotifKind
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

private val NickKey = stringPreferencesKey("share_card_nick")

/**
 * Шторка предпросмотра карточки «Что я сейчас смотрю»: показывает готовую картинку (тема и язык из
 * настроек приложения), даёт поменять ник и режим данных, сохранить PNG в галерею или поделиться.
 */
@Composable
fun WatchingCardSheet(
    visible: Boolean,
    anime: Anime,
    title: String,
    ru: Boolean,
    isDark: Boolean,
    posterPath: String?,
    status: WatchStatus?,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return

    val builder: WatchingCardBuilder = koinInject()
    val settings: DataStore<Preferences> = koinInject(named("settings"))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        var nick by remember { mutableStateOf("") }
        var nickLoaded by remember { mutableStateOf(false) }
        var minimal by remember { mutableStateOf(false) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var busy by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            nick = settings.data.first()[NickKey].orEmpty()
            nickLoaded = true
        }
        // Перерисовка при смене ника и режима; набор ника сглаживаем паузой, первый кадр без задержки.
        LaunchedEffect(nickLoaded, nick, minimal) {
            if (!nickLoaded) return@LaunchedEffect
            if (bitmap != null) delay(250)
            bitmap = builder.render(anime, title, ru, isDark, posterPath, status, WatchingCardOptions(minimal, nick))
            settings.edit { it[NickKey] = nick.trim() }
        }

        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                if (ru) "Карточка для друзей" else "Share card",
                fontFamily = SnProFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (ru) "Тема и язык — из настроек приложения" else "Theme and language follow the app settings",
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) {
                val shown = bitmap
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = if (ru) "Предпросмотр карточки" else "Card preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)),
                    )
                } else {
                    CircularProgressIndicator(color = BrandOrange, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val fieldBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                BasicTextField(
                    value = nick,
                    onValueChange = { nick = it.take(22) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = SnProFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(BrandOrange),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(fieldBg)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                        ) {
                            if (nick.isEmpty()) {
                                Text(
                                    if (ru) "Ник на карточке" else "Nickname on the card",
                                    fontFamily = SnProFamily,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (minimal) (if (ru) "Минимум данных" else "Minimal") else (if (ru) "Полные данные" else "Full data"),
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (minimal) BrandOrange else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (minimal) BrandOrange.copy(alpha = 0.16f) else fieldBg)
                        .clickable { minimal = !minimal }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OrangeButton(
                    text = if (ru) "Скачать PNG" else "Save PNG",
                    modifier = Modifier.weight(1f),
                    enabled = bitmap != null && !busy,
                ) {
                    val card = bitmap ?: return@OrangeButton
                    scope.launch {
                        busy = true
                        val ok = WatchingCardActions.saveToGallery(context, card)
                        MiniNotif.show(
                            context,
                            when {
                                ok && ru -> "Сохранено в Картинки/Vetro"
                                ok -> "Saved to Pictures/Vetro"
                                ru -> "Не удалось сохранить"
                                else -> "Couldn't save"
                            },
                            if (ok) MiniNotifKind.Success else MiniNotifKind.Error,
                        )
                        busy = false
                    }
                }
                Text(
                    if (ru) "Поделиться" else "Share",
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (bitmap != null) 0.08f else 0.04f))
                        .clickable(enabled = bitmap != null && !busy) {
                            val card = bitmap ?: return@clickable
                            scope.launch {
                                if (!WatchingCardActions.share(context, card, ru)) {
                                    MiniNotif.show(
                                        context,
                                        if (ru) "Не удалось подготовить файл" else "Couldn't prepare the file",
                                        MiniNotifKind.Error,
                                    )
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
