package com.example.myapplication.ui.addedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.File
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily

@Composable
fun AddEditSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = SnProFamily,
            letterSpacing = 1.6.sp,
            color = AddEditColors.SectionLabel
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.AddEditCoverPhotoSlot(
    imageUri: Uri?,
    imageFilePath: String?,
    placeholderTitle: String,
    placeholderSubtitle: String,
    placeholderButtonLabel: String,
    animeId: String?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val hasImage = imageUri != null || imageFilePath != null
    val isDark = isAppInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val coverBrush = if (isDark) {
        CoverGradientBrush
    } else {
        Brush.linearGradient(
            colors = listOf(
                scheme.surfaceVariant,
                lerp(scheme.primaryContainer, scheme.surfaceVariant, 0.5f)
            ),
            start = Offset.Zero,
            end = Offset(0f, Float.POSITIVE_INFINITY)
        )
    }
    val placeholderBg = if (isDark) {
        scheme.surface.copy(alpha = 0.38f)
    } else {
        scheme.surfaceVariant
    }
    val corner = 28.dp
    val shape = RoundedCornerShape(corner)
    val dashColor = scheme.outline.copy(alpha = if (isDark) 0.45f else 0.55f)
    val slotModifier = modifier
        .fillMaxWidth(0.5f)
        .aspectRatio(9f / 16f)
        .addEditMenuTileShadow(isDark, shape)
        .clip(shape)
        .then(
            if (hasImage) Modifier.background(coverBrush)
            else Modifier.coverPlaceholderBackgroundAndDash(
                fillColor = placeholderBg,
                dashColor = dashColor,
                cornerRadius = corner
            )
        )
        .clickable(onClick = onClick)

    Box(
        modifier = slotModifier,
        contentAlignment = Alignment.Center
    ) {
        val imageModifier = if (animeId != null) Modifier.sharedElement(
            rememberSharedContentState(key = "anime_${animeId}"),
            animatedVisibilityScope = animatedVisibilityScope
        ) else Modifier

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(imageModifier)
                .clip(shape)
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (imageFilePath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(File(imageFilePath))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (!hasImage) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .addEditMenuTileShadow(isDark, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.85f)
                            else scheme.surfaceContainerHighest.copy(alpha = 0.65f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = placeholderTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = placeholderSubtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = SnProFamily,
                        lineHeight = 18.sp
                    ),
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(22.dp))
                val addCoverShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .addEditMenuTileShadow(isDark, addCoverShape)
                        .clip(addCoverShape)
                ) {
                    Surface(
                        shape = addCoverShape,
                        color = if (isDark) scheme.surfaceContainerHigh
                        else scheme.surfaceContainerHighest,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = scheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = placeholderButtonLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = SnProFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.coverPlaceholderBackgroundAndDash(
    fillColor: Color,
    dashColor: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 5.dp,
    gapLength: Dp = 4.dp
): Modifier = drawBehind {
    val rPx = cornerRadius.toPx()
    val cr = CornerRadius(rPx, rPx)
    drawRoundRect(color = fillColor, cornerRadius = cr)
    val w = strokeWidth.toPx()
    val inset = w / 2f
    drawRoundRect(
        color = dashColor,
        topLeft = Offset(inset, inset),
        size = Size(size.width - w, size.height - w),
        cornerRadius = cr,
        style = Stroke(
            width = w,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dashLength.toPx(), gapLength.toPx()),
                0f
            )
        )
    )
}

@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val isDark = isAppInDarkTheme()
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = if (isDark) AddEditColors.PillBackground else AddEditColors.PillBackgroundLight
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) BrandOrange.copy(alpha = 0.5f) else Color.Transparent,
        label = "pillBorder"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontFamily = SnProFamily,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(BrandOrange),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .addEditMenuTileShadow(isDark, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(bgColor)
                    .then(
                        if (borderColor != Color.Transparent)
                            Modifier.background(Color.Transparent)
                        else Modifier
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontFamily = SnProFamily,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingIcon()
                }
            }
        }
    )
}

@Composable
fun PillTextFieldWithCopy(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = 4
) {
    val ctx = LocalContext.current

    PillTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        singleLine = singleLine,
        maxLines = maxLines,
        trailingIcon = if (value.isNotEmpty()) {
            {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            val cm =
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("", value))
                            @Suppress("DEPRECATION")
                            android.widget.Toast
                                .makeText(ctx, "Copied!", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                )
            }
        } else null
    )
}

@Composable
fun AddEditEpisodeQuickSelect(
    selectedEpisodes: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val suggestions = listOf("12", "13", "24", "36", "48")

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        suggestions.forEach { ep ->
            val isSelected = ep == selectedEpisodes
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .addEditMenuTileShadow(isDark, CircleShape)
                        .then(
                            if (isSelected) Modifier.neonGlow(
                                color = AddEditColors.QuickSelectGlow,
                                radius = 14.dp,
                                alpha = 0.6f
                            ) else Modifier
                        )
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    if (isDark) AddEditColors.QuickSelectActiveBg
                                    else scheme.surfaceVariant
                                )
                            } else {
                                Modifier
                                    .background(
                                        if (isDark) Color.Transparent
                                        else scheme.surfaceContainerHighest
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isDark) Color.White.copy(alpha = 0.35f)
                                        else scheme.outline.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                            }
                        )
                        .clickable { onSelect(ep) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ep,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = SnProFamily,
                            color = if (isDark) {
                                if (isSelected) Color.White else Color.White.copy(alpha = 0.85f)
                            } else {
                                if (isSelected) scheme.onSurface else scheme.onSurfaceVariant
                            }
                        )
                    )
                }
            }
        }
    }
}
