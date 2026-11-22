package com.example.myapplication.ui.details.share

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.components.LetterLimitField
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.SnProFamily

/**
 * Шторка «Комментарий к карточке»: поле с пределом из референса «Letter limit» (кольцо, счётчик, лишние
 * символы высыпаются буквами), клавиатура поднимается сразу. Предел [WatchingCardOptions.MAX_COMMENT_LENGTH]
 * считает все символы, включая пробелы и знаки препинания. Пустой комментарий убирает пузырь с карточки.
 *
 * @param initial уже введённый комментарий: поле открывается с ним, чтобы его можно было поправить.
 * @param onSubmit вызывается с обрезанным по краям текстом; сохранение и закрытие шторки — на вызывающем.
 */
@Composable
fun WatchingCardCommentSheet(
    visible: Boolean,
    initial: String,
    ru: Boolean,
    isDark: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    WatchingCardTextSheet(
        visible = visible,
        initial = initial,
        title = if (ru) "Комментарий к карточке" else "Card comment",
        hint = if (ru) "До ${WatchingCardOptions.MAX_COMMENT_LENGTH} символов — появится пузырём над карточкой"
        else "Up to ${WatchingCardOptions.MAX_COMMENT_LENGTH} characters — shown as a bubble above the card",
        placeholder = if (ru) "Что вы думаете об этом тайтле?" else "What do you think about it?",
        limit = WatchingCardOptions.MAX_COMMENT_LENGTH,
        ru = ru,
        isDark = isDark,
        onSubmit = onSubmit,
        onDismiss = onDismiss,
    )
}

/**
 * Шторка «Ник на карточке»: то же поле с пределом, что у комментария, чтобы ввод в «Карточке для друзей»
 * вёл себя одинаково. Отдельная шторка, а не поле внутри предпросмотра: клавиатура не перекрывает поле,
 * а картинка не сжимается под неё.
 */
@Composable
fun WatchingCardNickSheet(
    visible: Boolean,
    initial: String,
    ru: Boolean,
    isDark: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    WatchingCardTextSheet(
        visible = visible,
        initial = initial,
        title = if (ru) "Ник на карточке" else "Nickname on the card",
        hint = if (ru) "Стоит рядом с логотипом. Пусто — подпишется «Vetro Collection»"
        else "Shown next to the logo. Empty — it says “Vetro Collection”",
        placeholder = if (ru) "Как вас подписать?" else "What should it say?",
        limit = WatchingCardOptions.MAX_NICK_LENGTH,
        ru = ru,
        isDark = isDark,
        onSubmit = onSubmit,
        onDismiss = onDismiss,
    )
}

@Composable
private fun WatchingCardTextSheet(
    visible: Boolean,
    initial: String,
    title: String,
    hint: String,
    placeholder: String,
    limit: Int,
    ru: Boolean,
    isDark: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        var text by remember { mutableStateOf(initial) }
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 4.dp),
                fontFamily = SnProFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                modifier = Modifier.padding(horizontal = 4.dp),
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            LetterLimitField(
                value = text,
                // Enter в этом поле — «Готово»: перенос строки ни в пузыре, ни в нике не нужен.
                onValueChange = { next -> if (next.contains('\n')) onSubmit(text.trim()) else text = next },
                limit = limit,
                placeholder = placeholder,
                actionLabel = if (ru) "Готово" else "Done",
                onAction = { onSubmit(text.trim()) },
                remainingDescription = { left -> if (ru) "осталось $left символов" else "$left characters left" },
                autoFocus = true,
                pileSpace = 36.dp,
            )
        }
    }
}
