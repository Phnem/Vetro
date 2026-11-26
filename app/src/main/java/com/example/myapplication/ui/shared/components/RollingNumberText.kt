package com.example.myapplication.ui.shared.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

private val digitRuns = Regex("\\d+|\\D+")

@Composable
internal fun RollingNumberText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
    semanticLabel: String? = null,
) {
    if (text.none { it.isDigit() }) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
            style = style,
        )
        return
    }

    val runs = remember(text) { digitRuns.findAll(text).map { it.value }.toList() }
    val resolvedStyle = style.merge(
        TextStyle(color = color, fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight, lineHeight = lineHeight),
    )
    Row(
        modifier.clipToBounds().clearAndSetSemantics {
            if (semanticLabel != null) contentDescription = semanticLabel
            else this.text = AnnotatedString(text)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        runs.forEachIndexed { runIndex, run ->
            key(runIndex) {
                if (run.first().isDigit()) {
                    run.forEachIndexed { index, digit ->
                        key(run.lastIndex - index) {
                            AnimatedContent(
                                targetState = digit,
                                transitionSpec = {
                                    val forward = when {
                                        initialState == '9' && targetState == '0' -> true
                                        initialState == '0' && targetState == '9' -> false
                                        else -> targetState > initialState
                                    }
                                    val direction = if (forward) 1 else -1
                                    (slideInVertically(tween(220)) { it * direction } togetherWith
                                        slideOutVertically(tween(220)) { -it * direction })
                                        .using(SizeTransform(clip = true))
                                },
                                label = "Rolling digit",
                            ) { shown ->
                                Text(shown.toString(), style = resolvedStyle.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                            }
                        }
                    }
                } else {
                    Text(run, style = resolvedStyle, maxLines = 1, overflow = overflow)
                }
            }
        }
    }
}
