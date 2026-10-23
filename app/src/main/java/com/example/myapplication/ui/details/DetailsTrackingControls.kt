package com.example.myapplication.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.media.progress.TitleTrackingPreferences
import com.example.myapplication.ui.calendar.MonthGrid
import com.example.myapplication.ui.calendar.MonthHeader
import com.example.myapplication.ui.calendar.WeekdayHeader
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SnProFamily
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val watchDateFormat = DateTimeFormatter.ofPattern("dd.MM.yy")

internal fun watchDatesLabel(preferences: TitleTrackingPreferences): String {
    val start = preferences.startedOn
    val finish = preferences.finishedOn
    if (!preferences.manualDates && finish == null) return "Auto"
    return if (start == null && finish == null) {
        if (preferences.manualDates) "Manual" else "Auto"
    } else "${start?.format(watchDateFormat) ?: "…"}–${finish?.format(watchDateFormat) ?: "…"}"
}

@Composable
internal fun TrackingChip(label: String, description: String, icon: ImageVector, isDark: Boolean, onClick: () -> Unit) {
    val accent = if (isDark) Color.White.copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.66f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = if (isDark) 0.20f else 0.13f))
            .semantics { contentDescription = "$description: $label" }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold), color = accent)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun TrackingModeContent(rating: Boolean, manual: Boolean, ru: Boolean, isDark: Boolean, onPick: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (rating) { if (ru) "Пользовательский рейтинг" else "User rating" }
            else { if (ru) "Даты просмотра" else "Watch dates" },
            fontFamily = SnProFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ModeRow(
            title = if (rating) "Auto rating" else "Auto",
            hint = if (rating) { if (ru) "Текущая общая оценка тайтла" else "Your existing title rating" }
            else { if (ru) "Первое открытие первого и финального эпизода" else "First opening of the first and final episode" },
            selected = !manual, isDark = isDark, onClick = { onPick(false) },
        )
        ModeRow(
            title = if (rating) "Manual rating" else if (ru) "Ручной ввод" else "Manual",
            hint = if (rating) { if (ru) "Среднее всех оценённых эпизодов" else "Average of all rated episodes" }
            else { if (ru) "Выбрать начало и окончание в календаре" else "Choose start and finish in the calendar" },
            selected = manual, isDark = isDark, onClick = { onPick(true) },
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ModeRow(title: String, hint: String, selected: Boolean, isDark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (selected) BrandOrangeBright.copy(alpha = if (isDark) 0.20f else 0.13f) else IosDesign.elevatedCard(isDark))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontFamily = SnProFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = if (selected) BrandOrangeBright else MaterialTheme.colorScheme.onSurface)
            Text(hint, fontFamily = SnProFamily, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = BrandOrangeBright, modifier = Modifier.padding(start = 8.dp).size(22.dp))
    }
}

@Composable
internal fun ColumnScope.WatchDatesContent(
    state: DetailsPickerState.WatchDates,
    ru: Boolean,
    isDark: Boolean,
    onSave: (LocalDate, LocalDate?) -> Unit,
) {
    var startDay by rememberSaveable(state) { mutableStateOf(state.start?.toEpochDay()) }
    var finishDay by rememberSaveable(state) { mutableStateOf(state.finish?.toEpochDay()) }
    var choosingFinish by rememberSaveable(state) { mutableStateOf(false) }
    val start = startDay?.let(LocalDate::ofEpochDay)
    val finish = finishDay?.let(LocalDate::ofEpochDay)
    val today = remember { LocalDate.now() }
    var monthValue by rememberSaveable(state) { mutableStateOf(YearMonth.from(start ?: today).toString()) }
    val month = YearMonth.parse(monthValue)
    val locale = if (ru) Locale.forLanguageTag("ru") else Locale.ENGLISH
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val invalid = start != null && finish != null && finish.isBefore(start)

    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth()
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (ru) "Даты просмотра" else "Watch dates", fontFamily = SnProFamily, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, color = ink)
        Text(if (ru) "Выберите начало, затем окончание. Если ещё смотрите, оставьте окончание пустым."
            else "Choose the start, then the finish. Leave the finish empty while watching.",
            fontFamily = SnProFamily, fontSize = 13.sp, color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(if (ru) "Начало" else "Start", start, !choosingFinish, Modifier.weight(1f)) {
                choosingFinish = false
                start?.let { monthValue = YearMonth.from(it).toString() }
            }
            DateField(if (ru) "Окончание" else "Finish", finish, choosingFinish, Modifier.weight(1f)) {
                choosingFinish = true
                finish?.let { monthValue = YearMonth.from(it).toString() }
            }
        }
        MonthHeader(month, locale, ink, isDark,
            onPrev = { monthValue = month.minusMonths(1).toString() },
            onNext = { monthValue = month.plusMonths(1).toString() })
        WeekdayHeader(locale, muted)
        MonthGrid(
            month = month, today = today, selected = if (choosingFinish) finish else start,
            byDate = emptyMap(), imagePath = { null }, isDark = isDark,
            rangeStart = start, rangeEnd = finish,
            onSelect = { date ->
                if (choosingFinish) finishDay = date.toEpochDay() else {
                    startDay = date.toEpochDay()
                    choosingFinish = true
                }
            },
        )
        if (finish != null) TextButton(onClick = { finishDay = null; choosingFinish = true }) {
            Text(if (ru) "Ещё смотрю — убрать окончание" else "Still watching — clear finish", color = BrandOrangeBright)
        }
        if (invalid) Text(if (ru) "Дата окончания не может быть раньше начала" else "Finish date cannot precede the start date",
            color = MaterialTheme.colorScheme.error, fontFamily = SnProFamily, fontSize = 13.sp)
        Button(
            onClick = { start?.let { onSave(it, finish) } }, enabled = start != null && !invalid,
            modifier = Modifier.fillMaxWidth(), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrangeBright, contentColor = Color.White),
        ) { Text(if (ru) "Сохранить" else "Save", fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp))
        .background(if (selected) BrandOrangeBright.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp)) {
        Text(label, fontFamily = SnProFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(date?.format(watchDateFormat) ?: "—", fontFamily = SnProFamily, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}
