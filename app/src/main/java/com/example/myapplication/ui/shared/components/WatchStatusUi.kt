package com.example.myapplication.ui.shared.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.StatusSuccess

/**
 * Акцент статуса — только из брендовой палитры (оранжевый / зелёный / нейтральный): «идёт» — оранжевый,
 * «завершено» — зелёный, остальное — приглушённый нейтральный [neutral].
 */
fun WatchStatus.accent(neutral: Color): Color = when (this) {
    WatchStatus.WATCHING, WatchStatus.REWATCHING -> BrandOrange
    WatchStatus.COMPLETED -> StatusSuccess
    WatchStatus.PLANNED, WatchStatus.ON_HOLD, WatchStatus.DROPPED, WatchStatus.NOT_STARTED -> neutral
}

fun WatchStatus.icon(): ImageVector = when (this) {
    WatchStatus.PLANNED -> Icons.Rounded.Bookmark
    WatchStatus.WATCHING -> Icons.Rounded.PlayArrow
    WatchStatus.COMPLETED -> Icons.Rounded.CheckCircle
    WatchStatus.REWATCHING -> Icons.Rounded.Replay
    WatchStatus.ON_HOLD -> Icons.Rounded.Pause
    WatchStatus.DROPPED -> Icons.Rounded.Block
    WatchStatus.NOT_STARTED -> Icons.Rounded.RadioButtonUnchecked
}
