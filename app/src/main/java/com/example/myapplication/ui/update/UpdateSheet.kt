package com.example.myapplication.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.manga.translate.DownloadFormat
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.ProgressRing
import com.example.myapplication.ui.shared.components.RingTone
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.StatusSuccess
import com.example.myapplication.update.ApkVerifier
import com.example.myapplication.update.AppUpdateManager
import com.example.myapplication.update.UpdateFailure
import com.example.myapplication.update.UpdateMode
import com.example.myapplication.update.UpdateRelease
import com.example.myapplication.update.UpdateState
import com.example.myapplication.update.UpdateStrings
import com.example.myapplication.update.updateStrings
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Страница приложения в F-Droid: куда вести тех, у кого встроенное обновление выключено подписью. */
const val FDROID_PAGE_URL = "https://phnem.github.io/Vetro-Studio/collection"

/** Что нарисовать в центре кольца. */
private sealed interface Center {
    data class Percent(val value: Int) : Center
    data class Glyph(val icon: ImageVector, val tint: Color) : Center
    data object Nothing : Center
}

private data class Face(
    val title: String,
    val body: String,
    val tone: RingTone,
    val fraction: Float?,
    val center: Center,
)

/**
 * Окно обновления. Одно и то же окно служит и предложением при запуске ([manual] = false), и экраном
 * «Проверка обновлений» из настроек ([manual] = true): во втором случае оно ещё и проверяет релиз само,
 * показывает версии, время проверки и сведения о подписи - для тех, кто хочет видеть, что происходит.
 *
 * Состояние берётся из [AppUpdateManager], а не из экрана: окно можно закрыть посреди загрузки,
 * загрузка продолжится.
 */
@Composable
fun UpdateSheet(language: AppLanguage, manual: Boolean, onDismiss: () -> Unit) {
    val manager: AppUpdateManager = koinInject()
    val verifier: ApkVerifier = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()
    val strings = remember(language) { updateStrings(language) }
    val context = LocalContext.current
    val view = LocalView.current
    val comma = language == AppLanguage.RU

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.onReturnedFromInstallSettings()
    }
    LaunchedEffect(manual) { if (manual) manager.checkNow() }

    val face = faceFor(state, strings, manager.installedVersion, comma)
    val release = releaseOf(state)
    val checkedAt = (state as? UpdateState.UpToDate)?.checkedAtMillis

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.88f).dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop)
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(
            targetState = face.title to face.body,
            transitionSpec = {
                fadeIn(MotionTokens.easeEnter(durationMillis = 220)) togetherWith fadeOut(MotionTokens.easeExit())
            },
            label = "updateHeader",
        ) { (title, body) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    fontFamily = SnProFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    fontFamily = SnProFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        ProgressRing(fraction = face.fraction, tone = face.tone, modifier = Modifier.padding(vertical = 6.dp)) {
            // Переход по ВИДУ содержимого, а не по значению: иначе при каждом новом проценте цифры накладывались бы друг на друга.
            Crossfade(targetState = face.center::class, animationSpec = MotionTokens.tweenFast(), label = "ringCenter") {
                when (val c = face.center) {
                    is Center.Percent -> Text(
                        text = "${c.value}%",
                        fontFamily = SnProFamily,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is Center.Glyph -> Icon(c.icon, contentDescription = null, tint = c.tint, modifier = Modifier.size(46.dp))
                    Center.Nothing -> Spacer(Modifier.size(1.dp))
                }
            }
        }

        (state as? UpdateState.Downloading)?.let { d ->
            Text(
                text = progressLine(d, strings, comma),
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (manual || release != null) {
            VersionsCard(
                strings = strings,
                installed = manager.installedVersion,
                latest = release?.tag,
                checkedAt = checkedAt,
            )
        }
        if (release != null) WhatsNew(strings = strings, markdown = release.changelogMarkdown)
        if (manual && manager.mode == UpdateMode.Auto) SecurityCard(strings = strings, fingerprint = verifier.installedFingerprints().firstOrNull())

        Crossfade(targetState = state::class, animationSpec = MotionTokens.tweenStandard(), label = "updateActions") {
            Actions(
                state = state,
                strings = strings,
                onDismiss = onDismiss,
                onUpdate = { performHaptic(view, Haptic.Light); manager.download() },
                onInstall = { performHaptic(view, Haptic.Light); manager.install() },
                onCancel = { manager.cancelDownload() },
                onCheck = { performHaptic(view, Haptic.Light); manager.checkNow() },
                onAllow = {
                    performHaptic(view, Haptic.Light)
                    permissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                },
                onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
        }
    }
}

private fun releaseOf(state: UpdateState): UpdateRelease? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Ready -> state.release
    is UpdateState.NeedsPermission -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}

private fun faceFor(state: UpdateState, s: UpdateStrings, installed: String, comma: Boolean): Face = when (state) {
    UpdateState.Unavailable -> Face(
        s.unavailableTitle, s.unavailableBody, RingTone.Active, 1f,
        Center.Glyph(Icons.Filled.Shield, BrandOrange),
    )
    UpdateState.Idle, UpdateState.Checking -> Face(s.checkingTitle, s.checkingBody, RingTone.Active, null, Center.Nothing)
    is UpdateState.UpToDate -> Face(
        s.upToDateTitle, s.upToDateBody(installed), RingTone.Success, 1f,
        Center.Glyph(Icons.Filled.Check, StatusSuccess),
    )
    is UpdateState.Available -> Face(
        s.availableTitle,
        s.availableBody(state.release.tag, sizeLabel(state.release.sizeBytes, s, comma)),
        RingTone.Active, 0f,
        Center.Glyph(Icons.Filled.ArrowDownward, BrandOrange),
    )
    is UpdateState.Downloading -> Face(
        s.downloadingTitle, s.downloadingHint, RingTone.Active, state.fraction,
        Center.Percent((state.fraction * 100).roundToInt()),
    )
    is UpdateState.Ready -> Face(
        s.readyTitle, s.readyBody(state.release.tag), RingTone.Success, 1f,
        Center.Glyph(Icons.Filled.Check, StatusSuccess),
    )
    is UpdateState.NeedsPermission -> Face(
        s.permissionTitle, s.permissionBody, RingTone.Active, 1f,
        Center.Glyph(Icons.Filled.Shield, BrandOrange),
    )
    is UpdateState.Installing -> Face(s.installingTitle, s.installingBody, RingTone.Active, null, Center.Nothing)
    is UpdateState.Failed -> {
        val (title, body) = when (state.reason) {
            UpdateFailure.CHECK -> s.checkFailedTitle to s.checkFailedBody
            UpdateFailure.DOWNLOAD -> s.downloadFailedTitle to s.downloadFailedBody
            UpdateFailure.VERIFY -> s.verifyFailedTitle to s.verifyFailedBody
            UpdateFailure.INSTALL -> s.installFailedTitle to s.installFailedBody
        }
        Face(title, body, RingTone.Error, 1f, Center.Glyph(Icons.Filled.ErrorOutline, BrandOrange))
    }
}

private fun sizeLabel(bytes: Long, s: UpdateStrings, comma: Boolean): String =
    if (bytes > 0) "${DownloadFormat.megabytes(bytes, comma)} ${s.megabyte}" else "—"

private fun progressLine(d: UpdateState.Downloading, s: UpdateStrings, comma: Boolean): String = buildString {
    append(DownloadFormat.megabytes(d.doneBytes, comma))
    if (d.totalBytes > 0) append(" / ").append(DownloadFormat.megabytes(d.totalBytes, comma))
    append(' ').append(s.megabyte)
    if (d.bytesPerSecond > 0) append("  ·  ").append(DownloadFormat.megabytes(d.bytesPerSecond, comma)).append(' ').append(s.perSecond)
}

@Composable
private fun VersionsCard(strings: UpdateStrings, installed: String, latest: String?, checkedAt: Long?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VersionColumn(strings.installedLabel, installed, Modifier.weight(1f))
            if (latest != null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                )
                VersionColumn(strings.latestLabel, latest, Modifier.weight(1f), accent = BrandOrange)
            }
        }
        checkedAt?.let {
            Text(
                text = "${strings.lastCheckLabel}: ${relativeTime(strings, it)}",
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VersionColumn(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier) {
        Text(label, fontFamily = SnProFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontFamily = SnProFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private fun relativeTime(strings: UpdateStrings, millis: Long): String {
    if (millis <= 0L) return strings.neverChecked
    val minutes = ((System.currentTimeMillis() - millis) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        minutes < 1 -> strings.justNow
        minutes < 60 -> strings.minutesAgo(minutes)
        minutes < 60 * 24 -> strings.hoursAgo(minutes / 60)
        else -> strings.daysAgo(minutes / (60 * 24))
    }
}

@Composable
private fun WhatsNew(strings: UpdateStrings, markdown: String?) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val view = LocalView.current
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        h3 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        h4 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        h5 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        h6 = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodySmall,
        paragraph = MaterialTheme.typography.bodySmall,
        bullet = MaterialTheme.typography.bodySmall,
        ordered = MaterialTheme.typography.bodySmall,
        list = MaterialTheme.typography.bodySmall,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .animateContentSize(MotionTokens.tweenStandard()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    performHaptic(view, Haptic.Light)
                    expanded = !expanded
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.whatsNew,
                modifier = Modifier.weight(1f),
                fontFamily = SnProFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                if (!markdown.isNullOrBlank()) {
                    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                        Markdown(content = markdown, typography = typography, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(
                        text = strings.whatsNewEmpty,
                        fontFamily = SnProFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityCard(strings: UpdateStrings, fingerprint: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(strings.securityTitle, fontFamily = SnProFamily, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(strings.signedByAuthor, fontFamily = SnProFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = strings.securityBody,
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fingerprint?.let {
            Text(
                text = "${strings.signatureLabel}: ${it.chunked(4).take(4).joinToString(" ")}…${it.takeLast(4)}",
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Actions(
    state: UpdateState,
    strings: UpdateStrings,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onAllow: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state) {
            UpdateState.Unavailable -> {
                PrimaryButton(strings.openFdroid, BrandOrange) { onOpen(FDROID_PAGE_URL) }
                GhostButton(strings.close, muted, onDismiss)
            }
            UpdateState.Idle, UpdateState.Checking, is UpdateState.Installing -> GhostButton(strings.close, muted, onDismiss)
            is UpdateState.UpToDate -> {
                PrimaryButton(strings.checkNow, BrandOrange, onCheck)
                GhostButton(strings.close, muted, onDismiss)
            }
            is UpdateState.Available -> {
                PrimaryButton(strings.updateNow, BrandOrange, onUpdate)
                GhostButton(strings.later, muted, onDismiss)
            }
            is UpdateState.Downloading -> {
                PrimaryButton(strings.close, BrandOrange, onDismiss)
                GhostButton(strings.cancelDownload, muted, onCancel)
            }
            is UpdateState.Ready -> {
                PrimaryButton(strings.installNow, StatusSuccess, onInstall)
                GhostButton(strings.later, muted, onDismiss)
            }
            is UpdateState.NeedsPermission -> {
                PrimaryButton(strings.allowInstalls, BrandOrange, onAllow)
                GhostButton(strings.later, muted, onDismiss)
            }
            is UpdateState.Failed -> {
                val retry: () -> Unit = when (state.reason) {
                    UpdateFailure.CHECK -> onCheck
                    UpdateFailure.INSTALL -> onInstall
                    else -> onUpdate
                }
                PrimaryButton(strings.retry, BrandOrange, retry)
                state.release?.htmlUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    GhostButton(strings.openReleasePage, muted) { onOpen(url) }
                }
                GhostButton(strings.close, muted, onDismiss)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(text, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, fontFamily = SnProFamily, color = color)
    }
}
