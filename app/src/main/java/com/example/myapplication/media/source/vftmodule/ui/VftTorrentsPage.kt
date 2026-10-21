package com.example.myapplication.media.source.vftmodule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.media.source.vftmodule.VftPreferences
import com.example.myapplication.media.source.vftmodule.clients.JackettClient
import com.example.myapplication.media.source.vftmodule.clients.TorrServerClient
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun VftTorrentsPage(ru: Boolean) {
    val context = LocalContext.current
    val prefs: VftPreferences = koinInject()
    val torrServerClient: TorrServerClient = koinInject()
    val jackettClient: JackettClient = koinInject()
    val scope = rememberCoroutineScope()

    var moduleEnabled by remember { mutableStateOf(prefs.isModuleEnabled) }
    var preferOriginal by remember { mutableStateOf(prefs.preferOriginalAudio) }
    var useEmbedded by remember { mutableStateOf(prefs.useEmbeddedEngine) }

    var rutorEnabled by remember { mutableStateOf(prefs.isRutorEnabled) }
    var rutrackerEnabled by remember { mutableStateOf(prefs.isRutrackerEnabled) }
    var tpbEnabled by remember { mutableStateOf(prefs.isPirateBayEnabled) }
    var nyaaEnabled by remember { mutableStateOf(prefs.isNyaaEnabled) }

    var torrServerEnabled by remember { mutableStateOf(prefs.isTorrServerEnabled) }
    var torrServerUrl by remember { mutableStateOf(prefs.torrServerUrl) }
    var torrServerStatus by remember { mutableStateOf<String?>(null) }

    var jackettEnabled by remember { mutableStateOf(prefs.isJackettEnabled) }
    var jackettUrl by remember { mutableStateOf(prefs.jackettUrl) }
    var jackettKey by remember { mutableStateOf(prefs.jackettApiKey) }
    var jackettStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Общий переключатель модуля
        SettingToggleRow(
            title = if (ru) "Включить VFT Торренты" else "Enable VFT Torrents",
            subtitle = if (ru) "Поиск и онлайн-воспроизведение раздач" else "Search and stream torrent releases",
            checked = moduleEnabled,
            onCheckedChange = {
                moduleEnabled = it
                prefs.isModuleEnabled = it
            },
        )

        SettingToggleRow(
            title = if (ru) "Предпочитать оригинальную озвучку" else "Prefer original audio",
            subtitle = if (ru) "Приоритет раздачам с оригинальной английской/японской дорожкой" else "Boost score for releases with original English/Japanese tracks",
            checked = preferOriginal,
            onCheckedChange = {
                preferOriginal = it
                prefs.preferOriginalAudio = it
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (ru) "Торрент-трекеры" else "Torrent Trackers",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
        )

        SettingToggleRow(
            title = "Rutor",
            subtitle = if (ru) "Фильмы и сериалы (многоголосый дубляж + оригинал)" else "Movies and TV series (multi-audio & original)",
            checked = rutorEnabled,
            onCheckedChange = {
                rutorEnabled = it
                prefs.isRutorEnabled = it
            },
        )

        SettingToggleRow(
            title = "RuTracker",
            subtitle = if (ru) "Крупнейший русскоязычный трекер" else "Largest Russian tracker",
            checked = rutrackerEnabled,
            onCheckedChange = {
                rutrackerEnabled = it
                prefs.isRutrackerEnabled = it
            },
        )

        SettingToggleRow(
            title = "The Pirate Bay (apibay)",
            subtitle = if (ru) "Мировые релизы на английском языке" else "Global English releases & original sound",
            checked = tpbEnabled,
            onCheckedChange = {
                tpbEnabled = it
                prefs.isPirateBayEnabled = it
            },
        )

        SettingToggleRow(
            title = "Nyaa.si",
            subtitle = if (ru) "Аниме (японский звук, английские и русские субтитры)" else "Anime releases (raw, subs, dual-audio)",
            checked = nyaaEnabled,
            onCheckedChange = {
                nyaaEnabled = it
                prefs.isNyaaEnabled = it
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "TorrServer (MatriX)",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
        )

        SettingToggleRow(
            title = if (ru) "Стриминг через TorrServer" else "Stream via TorrServer",
            subtitle = if (ru) "Локальный или удалённый сервер TorrServer" else "Local or LAN TorrServer instance",
            checked = torrServerEnabled,
            onCheckedChange = {
                torrServerEnabled = it
                prefs.isTorrServerEnabled = it
            },
        )

        if (torrServerEnabled) {
            OutlinedTextField(
                value = torrServerUrl,
                onValueChange = {
                    torrServerUrl = it
                    prefs.torrServerUrl = it
                },
                label = { Text(if (ru) "Адрес TorrServer" else "TorrServer URL") },
                shape = SquircleShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val alive = torrServerClient.isAlive()
                            torrServerStatus = if (alive) {
                                if (ru) "Подключено!" else "Connected!"
                            } else {
                                if (ru) "Ошибка соединения" else "Connection failed"
                            }
                        }
                    },
                    shape = SquircleShape(12.dp),
                ) {
                    Text(if (ru) "Проверить статус" else "Test Connection")
                }

                torrServerStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.contains("!") || status.contains("Подключено")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Jackett",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
        )

        SettingToggleRow(
            title = if (ru) "Поиск через Jackett" else "Search via Jackett",
            subtitle = if (ru) "Агрегатор трекеров (Torznab API)" else "Multi-tracker aggregator (Torznab API)",
            checked = jackettEnabled,
            onCheckedChange = {
                jackettEnabled = it
                prefs.isJackettEnabled = it
            },
        )

        if (jackettEnabled) {
            OutlinedTextField(
                value = jackettUrl,
                onValueChange = {
                    jackettUrl = it
                    prefs.jackettUrl = it
                },
                label = { Text(if (ru) "Адрес Jackett" else "Jackett URL") },
                shape = SquircleShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = jackettKey,
                onValueChange = {
                    jackettKey = it
                    prefs.jackettApiKey = it
                },
                label = { Text(if (ru) "API-ключ Jackett" else "Jackett API Key") },
                shape = SquircleShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val alive = jackettClient.isAvailable()
                            jackettStatus = if (alive) {
                                if (ru) "Подключено!" else "Connected!"
                            } else {
                                if (ru) "Ошибка соединения" else "Connection failed"
                            }
                        }
                    },
                    shape = SquircleShape(12.dp),
                ) {
                    Text(if (ru) "Проверить Jackett" else "Test Jackett")
                }

                jackettStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.contains("!") || status.contains("Подключено")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (ru) "Встроенный торрент-движок" else "Embedded Torrent Engine",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
        )

        SettingToggleRow(
            title = if (ru) "Локальный стриминг (libtorrent)" else "Local streaming (libtorrent)",
            subtitle = if (ru) "Воспроизведение без сторонних программ через встроенный движок приложения" else "Stream directly within Vetro without external apps",
            checked = useEmbedded,
            onCheckedChange = {
                useEmbedded = it
                prefs.useEmbeddedEngine = it
            },
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
