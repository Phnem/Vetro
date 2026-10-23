package com.example.myapplication.localplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SheetStyle
import com.example.myapplication.ui.shared.theme.SnProFamily

/** VAP's audio panel layout (VapSheet + PanelHeader/PanelRow), adapted to Collection's
 * existing MotionBottomSheet. Both embedded tracks and provider dubs use the same selection
 * callback as before; the sheet never touches the playback position or stream resolution. */
@Composable
internal fun PlayerAudioSheet(
    state: MutableTransitionState<Boolean>,
    tracks: List<AudioTrackOption>,
    ru: Boolean,
    onSelect: (AudioTrackOption) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val swipe = rememberIosSheetSwipe(onDismiss)
    val listState = rememberLazyListState(tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val limit = (maxHeight - statusTop - SheetStyle.FloatBottom - 24.dp).coerceAtLeast(160.dp)
        MotionBottomSheet(
            visibleState = state,
            onDismiss = onDismiss,
            isDark = true,
            containerColor = Color(0xFF17130F),
            modifier = Modifier.pointerInput(Unit) { consumeEverything() },
            panelModifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = limit),
            swipe = swipe,
        ) {
            GrabberHandle(isDark = true, modifier = swipe.handleModifier)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (ru) "Озвучка" else "Audio", color = Color.White, fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.defaultMinSize(minHeight = 40.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (ru) "Закрыть" else "Close", color = Color.White, fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                flingBehavior = IosScroll.flingBehavior(),
            ) {
                if (tracks.isEmpty()) item {
                    Text(if (ru) "Озвучки недоступны" else "No audio tracks available",
                        color = Color.White.copy(alpha = 0.6f), fontFamily = SnProFamily, fontSize = 13.sp)
                }
                items(tracks, key = { it.id }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = if (track.isSelected) 0.34f else 0.22f))
                            .selectable(selected = track.isSelected, role = Role.RadioButton) {
                                onSelect(track)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(track.label, color = Color.White, fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (track.isSelected) {
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(BrandOrangeBright))
                        }
                    }
                }
            }
        }
    }
}
