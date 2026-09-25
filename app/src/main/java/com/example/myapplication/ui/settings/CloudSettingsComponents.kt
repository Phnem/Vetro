package com.example.myapplication.ui.settings

import com.example.myapplication.ui.shared.theme.BrandDeepRed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class CloudStrings(
    val title: String = "Синхронизация с облаком",
    val subtitle: String = "",
    val syncNow: String = "Синх.",
    val lastSync: String = "Последняя синхронизация:",
    val neverSynced: String = "Никогда",
    val logout: String = "Выйти"
)

@Composable
fun CloudSettingsSection(
    strings: CloudStrings,
    lastSyncTime: Long,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    onLogout: () -> Unit
) {
    val isDark = isAppInDarkTheme()
    val hasSynced = lastSyncTime > 0
    val statusValue = if (hasSynced) {
        DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(lastSyncTime))
    } else {
        strings.neverSynced
    }

    val tileShape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val lastSyncOutline =
        if (isDark) Color.White.copy(alpha = 0.07f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)

    val syncBrush = if (isDark) {
        Brush.horizontalGradient(
            colors = listOf(
                OverlayThemeTokens.AccentSyncBlue,
                BrandDeepRed
            )
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                OverlayThemeTokens.AccentSyncBlue.copy(alpha = 0.92f),
                BrandDeepRed
            )
        )
    }

    val muted =
        if (isDark) OverlayThemeTokens.LabelMutedDark
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = strings.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) OverlayThemeTokens.AccentSyncBlue else MaterialTheme.colorScheme.primary,
            fontFamily = SnProFamily,
            modifier = Modifier.padding(bottom = if (strings.subtitle.isBlank()) 10.dp else 4.dp)
        )
        if (strings.subtitle.isNotBlank()) {
            Text(
                text = strings.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(tileShape)
                .border(1.dp, lastSyncOutline, tileShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.lastSync,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = muted
            )
            Text(
                text = statusValue,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                fontFamily = SnProFamily,
                color = when {
                    hasSynced -> MaterialTheme.colorScheme.onSurface
                    else -> OverlayThemeTokens.LogoutIconTint
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(OverlayThemeTokens.GridSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isDark) OverlayThemeTokens.RimDark
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = OverlayThemeTokens.LogoutIconTint
                )
            ) {
                Text(strings.logout, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onSyncClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (!isSyncing) Modifier.background(syncBrush, CircleShape) else Modifier
                    ),
                enabled = !isSyncing,
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    disabledElevation = 0.dp
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = OverlayThemeTokens.OnSyncBlueButton,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = OverlayThemeTokens.AccentSyncBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(strings.syncNow, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
