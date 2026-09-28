package com.example.myapplication.audiobooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.chapters.ChapterRecoveryManager
import com.example.myapplication.audiobooks.chapters.RecoveredChapterStore
import com.example.myapplication.audiobooks.chapters.RecoveryState
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Автовосстановление глав в листе «Главы». Показывается, только когда есть о чём говорить: разметка
 * бедная (одна глава на книгу, главы по часу и дольше), поиск идёт или уже есть результат.
 */
@UnstableApi
@Composable
internal fun ChapterRecoveryPanel(variant: VariantId, timeline: BookTimeline, strings: PlayerStrings) {
    val manager: ChapterRecoveryManager = koinInject()
    val store: RecoveredChapterStore = koinInject()
    val scope = rememberCoroutineScope()
    val states by manager.states.collectAsState()
    val saved by store.flow.collectAsState()
    // Файл предложений читается лениво — без этого прошлый результат не виден до первого действия.
    androidx.compose.runtime.LaunchedEffect(Unit) { store.get(variant) }
    val state = states[variant] ?: RecoveryState.Idle
    val entry = saved[variant.value]

    val poor = timeline.totalMs?.let { total ->
        val count = timeline.chapters.size.coerceAtLeast(1)
        total >= 8 * 60_000L && (count == 1 || total / count > 60 * 60_000L)
    } ?: (timeline.chapters.size <= 1)
    if (state == RecoveryState.Idle && (entry == null || entry.declined) && !poor) return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when {
            state is RecoveryState.Running || state is RecoveryState.Paused -> {
                val line = when (state) {
                    is RecoveryState.Paused -> if (state.reason == RecoveryState.PauseReason.HEAT) strings.recoveryPausedHeat else strings.recoveryPausedBattery
                    is RecoveryState.Running -> when (state.stage) {
                        RecoveryState.Stage.METADATA -> strings.recoveryReading
                        RecoveryState.Stage.SILENCE -> strings.recoverySilence((state.progress * 100).toInt())
                        RecoveryState.Stage.SPEECH -> strings.recoverySpeech((state.progress * 100).toInt())
                    }
                    else -> ""
                }
                Title(line)
                if (state is RecoveryState.Running) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).height(3.dp).background(BrandOrange),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Actions { Action(strings.stop, primary = false) { manager.cancel(variant) } }
            }
            state is RecoveryState.Failed -> {
                Title(if (state.reason == RecoveryState.FailReason.NEEDS_WIFI) strings.recoveryNeedsWifi else strings.recoveryFailed)
                Spacer(Modifier.height(12.dp))
                Actions { Action(strings.retry, primary = true) { scope.launch { manager.restart(variant, strings.russian) } } }
            }
            entry != null && entry.chapters.isEmpty() -> {
                Title(strings.recoveryNothing)
                Spacer(Modifier.height(12.dp))
                Actions {
                    Action(strings.retry, primary = false) { scope.launch { manager.restart(variant, strings.russian) } }
                }
            }
            entry != null && !entry.applied && !entry.declined -> {
                Title(strings.recoveryFound(entry.chapters.size))
                if (entry.confirmedCount in 2 until entry.chapters.size) {
                    Spacer(Modifier.height(4.dp))
                    Hint(strings.recoveryConfirmed(entry.confirmedCount - 1))
                }
                Spacer(Modifier.height(12.dp))
                Actions {
                    Action(strings.apply, primary = true) { scope.launch { manager.apply(variant) } }
                    Action(strings.notNow, primary = false) { scope.launch { manager.decline(variant) } }
                }
            }
            entry != null && entry.applied -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(R.drawable.ph_check, 16.dp, BrandOrange)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.recoveryApplied, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Action(strings.restoreChapters, primary = false) { scope.launch { manager.decline(variant) } }
                }
            }
            else -> {
                Title(strings.findChapters)
                Spacer(Modifier.height(4.dp))
                Hint(strings.findChaptersHint)
                Spacer(Modifier.height(12.dp))
                Actions { Action(strings.findChapters, primary = true) { scope.launch { manager.restart(variant, strings.russian) } } }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Title(text: String) {
    Text(text, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Главная кнопка — оранжевая (активное действие), второстепенная — стеклянно-белая. */
@Composable
private fun Action(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) BrandOrange else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
