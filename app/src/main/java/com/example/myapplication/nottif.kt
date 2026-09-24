package com.example.myapplication

import com.example.myapplication.ui.shared.theme.IosScroll
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateFormat
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.UiStrings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.navigationBarsPadding
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.iosSheetContainer
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.DarkBackground
import com.example.myapplication.ui.shared.theme.OverlayGlassPanel
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.example.myapplication.ui.shared.theme.softPlateShadowForLightSheet
import com.example.myapplication.utils.performHaptic
import com.example.myapplication.sync.ExternalListService
import com.example.myapplication.sync.ExternalListSyncCoordinator
import com.example.myapplication.sync.ListServiceAction
import com.example.myapplication.network.AppLanguage
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Activity для OAuth: [LocalActivity] в overlay часто null — обходим через цепочку Context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Как у плиток Sort/Genres: тёмный «стеклянный» квадрат или светлая подложка под иконку. */
@Composable
internal fun nottifOverlayIconBoxBg(isDark: Boolean): Color =
    if (isDark) OverlayThemeTokens.TileIconBgDark
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)

@Composable
internal fun nottifOverlayCircleControlBg(isDark: Boolean): Color =
    if (isDark) Color.Black.copy(alpha = 0.28f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)

/** Пилюля метрик: без рамки AssistChip, полностью скруглённый контейнер. */
@Composable
internal fun SyncMetricPill(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier.semantics { this.contentDescription = contentDescription }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
fun NotificationSyncOverlay(
    syncCoordinator: com.example.myapplication.sync.supabase.SupabaseSyncCoordinator,
    authRepository: com.example.myapplication.sync.supabase.AuthRepository,
    visibleState: MutableTransitionState<Boolean>,
    strings: UiStrings,
    syncReport: SyncReport,
    updates: List<AnimeUpdate>,
    isCheckingUpdates: Boolean,
    currentLanguage: AppLanguage,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBlockingChildDialogChange: (Boolean) -> Unit = {}
) {
    val isSyncing by syncCoordinator.isSyncing.collectAsStateWithLifecycle()
    val isUserSignedIn by authRepository.isUserSignedIn.collectAsStateWithLifecycle(initialValue = false)
    val syncState = if (isSyncing) SyncState.SYNCING else SyncState.IDLE
    val syncMode = SyncMode.AUTO
    val hasToken = isUserSignedIn

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showMalPlaceholderDialog by remember { mutableStateOf(false) }
    val hostActivity = LocalActivity.current ?: context.findActivity()
    val listSyncCoordinator: ExternalListSyncCoordinator = koinInject()
    val listSyncUi by listSyncCoordinator.syncUiState.collectAsStateWithLifecycle()

    val isDark = isAppInDarkTheme()
    val panelBg = if (isDark) DarkBackground else MaterialTheme.colorScheme.surface
    val cardBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val darkTileBase = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val rim = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    val onCard = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    /** Непрозрачный фон диалогов поверх размытого задника. */
    val dialogSurface = panelBg
    val serviceActionButtonColors = ButtonDefaults.textButtonColors(
        contentColor = onCard,
        disabledContentColor = onCard.copy(alpha = 0.38f)
    )

    val lastSyncTs = remember(syncState, visibleState.currentState, visibleState.targetState) {
        context.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)
    }
    val timeStr = remember(lastSyncTs) {
        if (lastSyncTs == 0L) "--:--"
        else DateFormat.getTimeFormat(context).format(lastSyncTs)
    }
    val datePart = remember(lastSyncTs, strings, currentLanguage) {
        formatSyncDateLine(lastSyncTs, strings, currentLanguage)
    }
    val statusWord = remember(syncState, strings) { syncStatusWord(strings, syncState) }
    val detailLine = "$datePart • $statusWord"

    val blockingChildDialogOpen =
        showLogoutDialog || showMalPlaceholderDialog
    LaunchedEffect(blockingChildDialogOpen) {
        onBlockingChildDialogChange(blockingChildDialogOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onBlockingChildDialogChange(false) }
    }

    BackHandler { onDismiss() }
    val swipe = rememberIosSheetSwipe { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Шторка выезжает снизу и уходит вниз; физика и хореография — в MotionBottomSheet.
        MotionBottomSheet(
            visibleState = visibleState,
            onDismiss = onDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                    ) {
                        // —— Header (Close button only) —— //
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = {
                                    performHaptic(view, "light")
                                    onDismiss()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.nottifCloseCd,
                                    tint = muted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        val badgeText = when (syncMode) {
                            SyncMode.AUTO -> strings.nottifBadgeAutomated
                            SyncMode.MANUAL -> strings.nottifBadgeManual
                        }
                        val syncingCloud = syncState == SyncState.SYNCING
                        val lastSyncAccent =
                            if (isDark) OverlayThemeTokens.AccentNeonBlue
                            else MaterialTheme.colorScheme.primary

                        NottifSyncServiceGrid(
                            isDark = isDark,
                            strings = strings,
                            ru = currentLanguage == AppLanguage.RU,
                            badgeText = badgeText,
                            timeStr = timeStr,
                            datePart = datePart,
                            statusWord = statusWord,
                            detailLine = detailLine,
                            syncingCloud = syncingCloud,
                            isCheckingUpdates = isCheckingUpdates,
                            hasToken = hasToken,
                            syncState = syncState,
                            onSyncNow = {
                                performHaptic(view, "light")
                                scope.launch { syncCoordinator.syncNow() }
                            },
                            onCheckUpdates = {
                                performHaptic(view, "light")
                                onCheckUpdates()
                            },
                            onLogoutClick = {
                                performHaptic(view, "light")
                                showLogoutDialog = true
                            },
                            onServiceAction = { service, action ->
                                if (listSyncUi.isRunning) return@NottifSyncServiceGrid
                                performHaptic(view, "light")
                                if (service == ExternalListService.MYANIMELIST) {
                                    showMalPlaceholderDialog = true
                                } else {
                                    val listAction = when (action) {
                                        SyncCardAction.PULL -> ListServiceAction.PULL
                                        SyncCardAction.PUSH -> ListServiceAction.PUSH
                                        SyncCardAction.SYNC -> ListServiceAction.SYNC
                                    }
                                    hostActivity?.let {
                                        listSyncCoordinator.startListServiceActionOrAuthorize(it, service, listAction)
                                    }
                                }
                            }
                        )

                        // Обновления серий больше не рендерятся здесь: они показываются
                        // iOS-стопкой сверху главного экрана ([EpisodeUpdateStack] в HomeScreen).
                        Spacer(Modifier.height(12.dp))
                    }
            }
        }

        if (showMalPlaceholderDialog) {
            AlertDialog(
                onDismissRequest = { showMalPlaceholderDialog = false },
                containerColor = dialogSurface,
                titleContentColor = onCard,
                textContentColor = muted,
                title = { Text(strings.nottifServiceMal, color = onCard) },
                text = {
                    Text(
                        text = strings.malSyncPlaceholderBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showMalPlaceholderDialog = false },
                        colors = serviceActionButtonColors
                    ) { Text(strings.statsOk) }
                }
            )
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                containerColor = dialogSurface,
                titleContentColor = onCard,
                textContentColor = muted,
                title = { Text(strings.nottifLogoutConfirmTitle, color = onCard) },
                text = { Text(strings.nottifLogoutConfirmBody, color = muted) },
                confirmButton = {
                    Button(
                        onClick = {
                            showLogoutDialog = false
                            onLogout()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC10801))
                    ) { Text(strings.deleteConfirm) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showLogoutDialog = false },
                        colors = serviceActionButtonColors
                    ) { Text(strings.cancel) }
                }
            )
        }

    }
}

/**
 * Заголовок панели уведомлений: крупный bold-title + приглушённый подзаголовок + close button.
 * Декомпозирован в Спринте 3, чтобы не раздувать тело [NotificationSyncOverlay].
 */
@Composable
private fun NottifPanelHeader(
    title: String,
    subtitle: String,
    closeCd: String,
    onCard: Color,
    muted: Color,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onCard,
                fontSize = 22.sp,
                letterSpacing = (-0.2).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = muted
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = closeCd,
                tint = muted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatSyncDateLine(
    ts: Long,
    strings: UiStrings,
    language: AppLanguage
): String {
    if (ts == 0L) return strings.never
    val cal = Calendar.getInstance()
    val nowDay = cal.get(Calendar.DAY_OF_YEAR)
    val nowYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = ts
    val d = cal.get(Calendar.DAY_OF_YEAR)
    val y = cal.get(Calendar.YEAR)
    return if (d == nowDay && y == nowYear) {
        strings.nottifDateToday
    } else {
        val locale = when (language) {
            AppLanguage.RU -> java.util.Locale.forLanguageTag("ru")
            AppLanguage.EN -> Locale.ENGLISH
        }
        SimpleDateFormat("MMM d", locale).format(Date(ts))
            .replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString() }
    }
}

@Composable
internal fun NottifStatusPill(
    text: String,
    accent: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    state: SyncState,
    isCheckingUpdates: Boolean,
) {
    val shape = RoundedCornerShape(12.dp)
    val statusColor = when {
        isCheckingUpdates || state == SyncState.SYNCING ->
            if (isDark) accent else MaterialTheme.colorScheme.primary
        state == SyncState.DONE || state == SyncState.IDLE ->
            if (isDark) OverlayThemeTokens.AccentNeonGreen else MaterialTheme.colorScheme.onSurface
        else ->
            if (isDark) OverlayThemeTokens.AccentNeonRed else MaterialTheme.colorScheme.error
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                isCheckingUpdates || state == SyncState.SYNCING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor,
                    )
                }
                state == SyncState.DONE || state == SyncState.IDLE -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (isDark) {
        val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)
        val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
        Surface(
            modifier = modifier,
            shape = shape,
            color = background,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier
                .softPlateShadowForLightSheet(isDark = false, shape = shape, elevation = 2.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface, shape),
        ) {
            content()
        }
    }
}

private fun syncStatusWord(strings: UiStrings, state: SyncState): String = when (state) {
    SyncState.DONE, SyncState.IDLE -> strings.nottifLastSyncSuccess
    SyncState.SYNCING -> strings.nottifLastSyncSyncing
    SyncState.ERROR -> strings.nottifLastSyncError
    SyncState.AUTH_REQUIRED -> strings.nottifLastSyncNeedsLogin
    SyncState.CONFLICT -> strings.nottifLastSyncConflict
}
