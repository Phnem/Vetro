package com.example.myapplication.ui.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.support.SupportReminderManager
import com.example.myapplication.ui.shared.BusyUi
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.support.SupportOption
import com.example.myapplication.ui.shared.mascot.MascotIllustration
import com.example.myapplication.ui.shared.mascot.MascotScene
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.getSupportStrings

/**
 * Содержимое шторки «Поддержать Vetro» (внутри общего [com.example.myapplication.ui.shared.components.IosSheetScaffold]):
 * маскот AnimD, короткий заголовок и текст, основная кнопка — донат, вторичная — звезда на GitHub (только пока
 * её не нажимали). Закрывается свайпом вниз, «назад», тапом по фону или «Не сейчас».
 *
 * Анимация AnimD стартует вместе с появлением шторки и останавливается, когда шторка уходит из композиции;
 * при системном «Отключить анимации» — статичный кадр.
 */
@Composable
fun SupportReminderSheet(
    language: AppLanguage,
    options: List<SupportOption>,
    onDonate: () -> Unit,
    onGithub: () -> Unit,
    onLater: () -> Unit,
) {
    val strings = remember(language) { getSupportStrings(language) }
    val donate = SupportOption.DONATE in options
    val github = SupportOption.GITHUB in options
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop)
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MascotIllustration(MascotScene.Coffee, Modifier.size(220.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = strings.title,
                fontFamily = SnProFamily,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (github) strings.body else strings.bodyDonateOnly,
                fontFamily = SnProFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (donate) FilledButton(strings.donate, BrandOrange, Color.White, onDonate)
            if (github) {
                // Вторичная: при донате рядом — спокойная заливка в цвет темы; одна — основная.
                if (donate) {
                    val dark = isAppInDarkTheme()
                    FilledButton(
                        strings.github,
                        if (dark) Color.White.copy(alpha = 0.1f) else Color(0xFF1B1D21).copy(alpha = 0.07f),
                        MaterialTheme.colorScheme.onSurface,
                        onGithub,
                    )
                } else {
                    FilledButton(strings.github, BrandOrange, Color.White, onGithub)
                }
            }
            TextButton(onClick = onLater) {
                Text(strings.later, fontFamily = SnProFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Через сколько после наступления спокойного момента пробовать показ: переходы и анимации успевают закончиться. */
private const val SETTLE_MS = 1_500L

/**
 * Единая проверка «спокойного момента» для напоминания: главный экран, никаких шторок/диалогов/развёрнутого
 * плеера ([BusyUi]), клавиатура скрыта, активити на переднем плане (видео, читалка и запросы разрешений —
 * отдельные окна поверх неё), и всё это держится [SETTLE_MS]. Ни опроса, ни обработчиков кликов: эффект
 * перезапускается только при смене этих условий (вернулся на главную, закрыл шторку, убрал клавиатуру).
 * Если порог достигнут в неподходящий момент, напоминание просто ждёт следующего спокойного.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportReminderTrigger(manager: SupportReminderManager, atHome: Boolean, blocked: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val imeVisible = WindowInsets.isImeVisible
    val offer by manager.offer.collectAsState()
    val calm = atHome && !blocked && !BusyUi.anyBusy && !imeVisible && offer == null &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val calmNow by rememberUpdatedState(calm)
    LaunchedEffect(calm) {
        if (!calm) return@LaunchedEffect
        delay(SETTLE_MS)
        manager.tryShow(safeContext = calmNow)
    }
}

/** Открывает внешнюю ссылку; нет обработчика для https — пробуем браузер по умолчанию. */
fun openSupportLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                    .setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun FilledButton(text: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
    }
}
