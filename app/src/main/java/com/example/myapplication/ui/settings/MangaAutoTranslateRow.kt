package com.example.myapplication.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.manga.translate.Availability
import com.example.myapplication.manga.translate.MangaTranslateSettings
import com.example.myapplication.manga.translate.ModelsState
import com.example.myapplication.manga.translate.UnavailableReason
import com.example.myapplication.manga.translate.mangaTranslateStrings
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.theme.BrandOrange
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Строка "Автоперевод манги". Включить можно только в официальной сборке и при подключённом
 * ИИ-провайдере; иначе переключатель погашен, а под строкой стоит плашка с причиной - и что с этим
 * делать. Модели (~128 МБ) скачиваются при включении, ход загрузки виден в подписи строки.
 */
@Composable
fun MangaAutoTranslateRow(
    language: AppLanguage,
    isDark: Boolean,
    onHaptic: () -> Unit,
    /** Открыть шторку загрузки моделей: при включении и по тапу на строку, пока модели качаются. */
    onOpenModels: () -> Unit,
) {
    val settings: MangaTranslateSettings = koinInject()
    val ui by settings.ui.collectAsStateWithLifecycle()
    val strings = remember(language) { mangaTranslateStrings(language) }
    val scope = rememberCoroutineScope()

    val unavailable = (ui.availability as? Availability.Unavailable)?.reason
    val models = ui.models
    val subtitle = when {
        unavailable != null -> strings.subtitleOff
        ui.enabled && models is ModelsState.Downloading -> strings.downloading((models.fraction * 100).roundToInt())
        ui.enabled && models is ModelsState.Failed -> strings.downloadFailed
        ui.enabled -> strings.subtitleOn
        else -> strings.subtitleOff
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        IosRow(
            title = strings.title,
            subtitle = subtitle,
            isDark = isDark,
            icon = Icons.Filled.Translate,
            iconWell = false,
            onClick = if (ui.enabled && (models is ModelsState.Failed || models is ModelsState.Downloading)) {
                { onHaptic(); onOpenModels() }
            } else {
                null
            },
            trailing = {
                IosSwitch(
                    checked = ui.enabled && unavailable == null,
                    // Погашен, а не спрятан: пользователь должен видеть, что функция есть, и читать причину.
                    modifier = Modifier.alpha(if (unavailable != null) 0.4f else 1f),
                    onCheckedChange = { wanted ->
                        if (unavailable == null) {
                            onHaptic()
                            scope.launch {
                                val applied = settings.setEnabled(wanted)
                                // Модели ещё не на месте: показываем, как идёт загрузка, а не только процент в подписи.
                                if (wanted && applied && settings.ui.value.models !is ModelsState.Ready) onOpenModels()
                            }
                        }
                    },
                )
            },
        )
        val note = when (unavailable) {
            UnavailableReason.UNOFFICIAL_BUILD -> strings.unofficialBuild
            UnavailableReason.NO_AI_KEY -> strings.noAiKey
            null -> if (!ui.enabled && models !is ModelsState.Ready) strings.modelsSize else null
        }
        if (note != null) {
            Notice(text = note, warning = unavailable != null, isDark = isDark)
        }
    }
}

/** Плашка под строкой: оранжевая, если что-то мешает, нейтральная, если просто подсказка. */
@Composable
private fun Notice(text: String, warning: Boolean, isDark: Boolean) {
    val tint = if (warning) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = if (isDark) 0.16f else 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}
