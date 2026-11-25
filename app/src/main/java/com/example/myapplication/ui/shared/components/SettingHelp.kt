package com.example.myapplication.ui.shared.components

import androidx.compose.ui.composed
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.myapplication.ui.shared.theme.BrandOrange
import kotlinx.coroutines.delay

/**
 * Пояснения к пунктам настроек: удержание на строке открывает шторку с описанием.
 *
 * Строка объявляет себя через [SettingHelpBinding]; контроллер ниже общий на экран. Он же хранит
 * границы строк (чтобы экран мог подсветить нужную и подвести к ней указатель) и состояние подсветки.
 */
class SettingHelpBinding(
    val id: String,
    /** Для переключателей: текущее значение. null — пункт не on/off, вместо «Включить» будет «Перейти». */
    val checked: Boolean? = null,
    val onToggle: ((Boolean) -> Unit)? = null,
    /** Запасной текст для пункта без своего описания: заголовок и подпись самой строки. */
    val autoTitle: String? = null,
    val autoLead: String? = null,
)

/**
 * Включено на экране настроек: строка без своего [SettingHelpBinding] всё равно открывает описание
 * по удержанию, собранное из её заголовка и подписи. В шторках поверх настроек не включено.
 */
val LocalSettingHelpAuto = staticCompositionLocalOf { false }

class SettingHelpRequest(
    val binding: SettingHelpBinding,
    /** Открыть сам пункт («Перейти»); для переключателей не нужен. */
    val onGo: (() -> Unit)?,
    /** Описание открыто обучающим сценарием, а не пальцем пользователя. */
    val fromTutorial: Boolean = false,
)

@Stable
class SettingHelpController {
    /** Последний открытый запрос: остаётся, пока шторка уезжает, иначе её содержимое схлопнулось бы раньше неё. */
    var request by mutableStateOf<SettingHelpRequest?>(null)
        private set
    var sheetOpen by mutableStateOf(false)
        private set

    /** Строка, которая сейчас мигает, и сила её подсветки 0..1. */
    var highlightId by mutableStateOf<String?>(null)
    val highlight = Animatable(0f)

    /** Границы строк в координатах корня окна (заполняют сами строки). */
    val bounds = mutableStateMapOf<String, Rect>()

    fun open(next: SettingHelpRequest) {
        request = next
        sheetOpen = true
    }

    fun close() {
        sheetOpen = false
    }

    /** Плавно мигает подсветкой [times] раз (без стробоскопа: ~1 с на вдох-выдох). */
    suspend fun pulse(id: String, times: Int = 3, reducedMotion: Boolean = false) {
        highlightId = id
        try {
            if (reducedMotion) {
                highlight.snapTo(1f)
                delay(1_200)
            } else {
                repeat(times) {
                    highlight.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                    highlight.animateTo(0f, tween(620, easing = FastOutSlowInEasing))
                }
            }
        } finally {
            highlight.snapTo(0f)
            highlightId = null
        }
    }
}

val LocalSettingHelp = staticCompositionLocalOf<SettingHelpController?> { null }

/** Отдаёт контроллеру границы строки [id] и рисует под ней мигающую подсветку, когда очередь дошла до неё. */
fun Modifier.settingHelpTarget(id: String?, controller: SettingHelpController?): Modifier {
    if (id == null || controller == null) return this
    return this
        .composed {
            androidx.compose.runtime.DisposableEffect(id, controller) { onDispose { controller.bounds.remove(id) } }
            Modifier
        }
        .onGloballyPositioned { controller.bounds[id] = it.boundsInRoot() }
        .drawBehind {
            if (controller.highlightId == id) {
                drawRect(BrandOrange.copy(alpha = 0.22f * controller.highlight.value))
            }
        }
}
