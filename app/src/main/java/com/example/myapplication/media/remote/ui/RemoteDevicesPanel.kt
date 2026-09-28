package com.example.myapplication.media.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.localplayer.ui.consumeEverything
import com.example.myapplication.localplayer.ui.playerIsRu
import com.example.myapplication.media.remote.RemoteConnection
import com.example.myapplication.media.remote.RemoteDevice
import com.example.myapplication.media.remote.RemoteDeviceKind
import com.example.myapplication.media.remote.RemoteError
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.phnem.vetro.R
import kotlinx.coroutines.delay

/** Состояние кнопки «Воспроизвести на…» в доке плеера. */
enum class RemoteButtonState { IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR }

private val RowShape = SquircleCornerShape(18.dp, 18.dp, 18.dp, 18.dp)

/**
 * Лист «Воспроизвести на» поверх плеера — в языке меню плеера (затемнение, белые контуры,
 * оранжевое — активное). Сначала «Этот телефон», подключённое устройство — первым среди ТВ,
 * найденные появляются по одному без перерисовки списка. Протокол — мелкой подписью.
 */
@Composable
fun RemoteDevicesPanel(
    state: MutableTransitionState<Boolean>,
    devices: List<RemoteDevice>,
    connection: RemoteConnection,
    hasLocalNetwork: Boolean,
    /** Последнее устройство, если оно сейчас в сети, — подсказка первой строкой. */
    suggested: RemoteDevice?,
    onPhone: () -> Unit,
    onDevice: (RemoteDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val ru = playerIsRu()
    BackHandler(enabled = state.targetState) { onDismiss() }
    // «Поиск…» — только первые секунды: дальше честно «не найдено», а не вечный индикатор.
    var searchExpired by remember { mutableStateOf(false) }
    LaunchedEffect(state.targetState) {
        searchExpired = false
        if (state.targetState) { delay(SEARCH_WINDOW_MS); searchExpired = true }
    }
    val connectedId = (connection as? RemoteConnection.Connected)?.device?.id
    val connectingId = (connection as? RemoteConnection.Connecting)?.device?.id
    val ordered = remember(devices, connectedId, suggested) {
        devices.sortedWith(compareByDescending<RemoteDevice> { it.id == connectedId }.thenByDescending { it.id == suggested?.id })
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(MotionTokens.menuPop()),
            exit = fadeOut(MotionTokens.sheetDismissForced()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { consumeEverything() }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            )
        }
        AnimatedVisibility(
            visibleState = state,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(MotionTokens.menuPop()) + slideInVertically(MotionTokens.sheetOffset) { -it / 8 },
            exit = fadeOut(MotionTokens.sheetDismissForced()) + slideOutVertically { -it / 12 },
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 62.dp, end = 12.dp, start = 12.dp)
                    .widthIn(max = 380.dp)
                    // Тапы внутри панели не закрывают её (скрим под ней).
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Text(
                    text = if (ru) "Воспроизвести на" else "Play on",
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "phone") {
                        DeviceRow(
                            icon = R.drawable.ph_device_mobile,
                            title = if (ru) "Этот телефон" else "This phone",
                            subtitle = if (ru) "Текущее устройство" else "Current device",
                            active = connectedId == null && connectingId == null,
                            busy = false,
                            onClick = onPhone,
                        )
                    }
                    items(ordered, key = { it.id }) { device ->
                        val isConnected = device.id == connectedId
                        val isConnecting = device.id == connectingId
                        DeviceRow(
                            icon = iconFor(device.kind),
                            title = device.name,
                            subtitle = when {
                                isConnecting -> if (ru) "Подключение…" else "Connecting…"
                                isConnected -> (if (ru) "Подключено · " else "Connected · ") + device.primary.protocol.label
                                device.id == suggested?.id -> (if (ru) "Последнее · " else "Last used · ") + device.primary.protocol.label
                                else -> device.primary.protocol.label
                            },
                            active = isConnected,
                            busy = isConnecting,
                            onClick = { onDevice(device) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item(key = "status") {
                        StatusBlock(
                            ru = ru,
                            hasLocalNetwork = hasLocalNetwork,
                            empty = devices.isEmpty(),
                            searching = !searchExpired,
                            error = (connection as? RemoteConnection.Failed)?.let { it.error to it.device?.name },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    icon: Int,
    title: String,
    subtitle: String,
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (active) BrandOrangeBright else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = if (active) 1f else 0.55f), RowShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "$title, $subtitle"
                stateDescription = if (active) "active" else ""
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (active) BrandOrangeBright else Color.White,
                fontFamily = SnProFamily,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = if (busy) 0.9f else 0.62f),
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(BrandOrangeBright))
        }
    }
}

@Composable
private fun StatusBlock(
    ru: Boolean,
    hasLocalNetwork: Boolean,
    empty: Boolean,
    searching: Boolean,
    error: Pair<RemoteError, String?>?,
) {
    val (title, hint) = when {
        error != null -> errorText(error.first, error.second, ru) to null
        !hasLocalNetwork -> (if (ru) "Нет локальной сети" else "No local network") to
            (if (ru) "Подключите телефон к той же сети Wi‑Fi, что и телевизор. Мобильный интернет для показа на ТВ не подходит."
            else "Connect the phone to the same Wi‑Fi as the TV. Mobile data can't reach a TV.")
        empty && searching -> (if (ru) "Поиск устройств…" else "Looking for devices…") to null
        empty -> (if (ru) "Устройства не найдены" else "No devices found") to
            (if (ru) "Убедитесь, что телефон и телевизор подключены к одной сети, а телевизор включён."
            else "Make sure the phone and the TV are on the same network and the TV is on.")
        searching -> (if (ru) "Поиск устройств…" else "Looking for devices…") to null
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!hasLocalNetwork && error == null) {
                Icon(painterResource(R.drawable.ph_wifi_slash), null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                color = if (error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.85f),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = Color.White.copy(alpha = 0.6f), fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

internal fun errorText(error: RemoteError, device: String?, ru: Boolean): String {
    val name = device?.let { "«$it»" } ?: if (ru) "устройство" else "the device"
    return when (error) {
        RemoteError.NoLocalNetwork -> if (ru) "Нет локальной сети — подключитесь к Wi‑Fi телевизора" else "No local network — join the TV's Wi‑Fi"
        RemoteError.UnsupportedFormat -> if (ru) "Этот телевизор не поддерживает формат видео" else "This TV doesn't support the video format"
        RemoteError.DeviceUnreachable -> if (ru) "Не удалось подключиться к $name. Проверьте, что оно включено" else "Couldn't connect to $name. Make sure it's on"
        RemoteError.DeviceGone -> if (ru) "$name пропало из сети" else "$name left the network"
        RemoteError.StreamFailed -> if (ru) "Телевизор не смог открыть видео" else "The TV couldn't open the video"
        RemoteError.PlayServicesMissing -> if (ru) "Google Cast недоступен на этом телефоне" else "Google Cast isn't available on this phone"
        is RemoteError.Other -> error.message
    }
}

private fun iconFor(kind: RemoteDeviceKind): Int = when (kind) {
    RemoteDeviceKind.TV -> R.drawable.ph_television_simple
    RemoteDeviceKind.TV_BOX -> R.drawable.ph_hard_drive
    RemoteDeviceKind.CAST_DONGLE -> R.drawable.ph_screencast
    RemoteDeviceKind.SPEAKER -> R.drawable.ph_speaker_high
    RemoteDeviceKind.RENDERER -> R.drawable.ph_monitor_play
}

private const val SEARCH_WINDOW_MS = 10_000L
