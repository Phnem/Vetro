package com.example.myapplication.ui.details.share

import com.example.myapplication.utils.takeWholeChars
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
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
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

private val NickKey = stringPreferencesKey("share_card_nick")

/**
 * Шторка предпросмотра карточки «Что я сейчас смотрю»: показывает готовую картинку (тема и язык из
 * настроек приложения), даёт сменить ник, объём данных и показ комментария, сохранить PNG в галерею или
 * поделиться. Ник вводится в отдельной шторке с полем из референса «Letter limit» ([WatchingCardNickSheet]):
 * поле внутри предпросмотра перекрывала бы клавиатура.
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
    /** Описание тайтла из Details: идёт на карточку, если у тайтла нет своей заметки. */
    description: String?,
    /** Комментарий пользователя для пузыря над карточкой; пустой — без пузыря и без кнопки показа. */
    comment: String,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return

    val builder: WatchingCardBuilder = koinInject()
    val settings: DataStore<Preferences> = koinInject(named("settings"))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nick by remember { mutableStateOf("") }
    var nickLoaded by remember { mutableStateOf(false) }
    var showNickSheet by remember { mutableStateOf(false) }
    // Режим данных и показ комментария живут, пока открыта шторка: карточку собирают под конкретную отправку.
    var minimal by remember { mutableStateOf(false) }
    var showComment by remember { mutableStateOf(true) }
    var base by remember { mutableStateOf<ShareCardData?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        nick = settings.data.first()[NickKey].orEmpty()
        nickLoaded = true
    }
    // Данные собираются один раз: режим данных и показ комментария карточка переживает сама, на пружинах,
    // а ник и текст комментария подставляются поверх без повторного чтения постера.
    LaunchedEffect(anime, title, ru, isDark, posterPath, status, description) {
        base = builder.prepare(anime, title, ru, isDark, posterPath, status, description)
    }
    val previewData = remember(base, nickLoaded, nick, comment) {
        base?.takeIf { nickLoaded }?.copy(
            nick = nick.trim(),
            comment = comment.trim().takeWholeChars(WatchingCardOptions.MAX_COMMENT_LENGTH).ifEmpty { null },
        )
    }
    val bubbleShown = showComment && comment.isNotBlank()

    // Итоговая картинка для PNG и отправки рисуется тем же кодом, что и предпросмотр, в устоявшейся позе.
    suspend fun finalCard(): android.graphics.Bitmap = builder.render(
        anime, title, ru, isDark, posterPath, status, description,
        WatchingCardOptions(minimal = minimal, nick = nick, comment = if (showComment) comment else ""),
    )

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp)
                .navigationBarsPadding()
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
            CardPreview(
                data = previewData,
                full = !minimal,
                showBubble = bubbleShown,
                description = if (ru) "Предпросмотр карточки" else "Card preview",
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NickPill(
                    nick = nick,
                    placeholder = if (ru) "Ник на карточке" else "Nickname on the card",
                    description = if (ru) "Изменить ник" else "Edit nickname",
                    onClick = { showNickSheet = true },
                    modifier = Modifier.weight(1f),
                )
                // Включено — полные данные (оранжевая кнопка), выключено — минимум.
                RoundIconToggle(
                    on = !minimal,
                    onChange = { full -> minimal = !full },
                    description = if (minimal) {
                        if (ru) "Минимум данных. Нажмите, чтобы показать все" else "Minimal data. Tap to show everything"
                    } else {
                        if (ru) "Полные данные. Нажмите, чтобы оставить минимум" else "Full data. Tap to keep the minimum"
                    },
                    modifier = Modifier.padding(start = 10.dp),
                    draw = { progress, tint -> drawDetailLevelIcon(progress, tint) },
                )
                // Кнопки показа комментария нет, пока комментария нет: включать нечего.
                AnimatedVisibility(
                    visible = comment.isNotBlank(),
                    enter = expandHorizontally(MotionTokens.springObject()) + fadeIn(MotionTokens.springObject()),
                    exit = shrinkHorizontally(MotionTokens.springExit()) + fadeOut(MotionTokens.springExit()),
                ) {
                    RoundIconToggle(
                        on = showComment,
                        onChange = { showComment = it },
                        description = if (showComment) {
                            if (ru) "Комментарий на карточке. Нажмите, чтобы убрать" else "Comment on the card. Tap to hide"
                        } else {
                            if (ru) "Комментарий скрыт. Нажмите, чтобы показать" else "Comment hidden. Tap to show"
                        },
                        modifier = Modifier.padding(start = 10.dp),
                        draw = { progress, tint -> drawCommentIcon(progress, tint) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OrangeButton(
                    text = if (ru) "Скачать PNG" else "Save PNG",
                    modifier = Modifier.weight(1f),
                    enabled = previewData != null && !busy,
                ) {
                    scope.launch {
                        busy = true
                        val ok = WatchingCardActions.saveToGallery(context, finalCard())
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
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (previewData != null) 0.08f else 0.04f))
                        .clickable(enabled = previewData != null && !busy) {
                            scope.launch {
                                busy = true
                                if (!WatchingCardActions.share(context, finalCard(), ru)) {
                                    MiniNotif.show(
                                        context,
                                        if (ru) "Не удалось подготовить файл" else "Couldn't prepare the file",
                                        MiniNotifKind.Error,
                                    )
                                }
                                busy = false
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }

    // Лежит поверх шторки предпросмотра: объявлена позже, значит и рисуется выше.
    WatchingCardNickSheet(
        visible = showNickSheet,
        initial = nick,
        ru = ru,
        isDark = isDark,
        onSubmit = { text ->
            nick = text.takeWholeChars(WatchingCardOptions.MAX_NICK_LENGTH)
            showNickSheet = false
            scope.launch { settings.edit { it[NickKey] = nick.trim() } }
        },
        onDismiss = { showNickSheet = false },
    )
}
