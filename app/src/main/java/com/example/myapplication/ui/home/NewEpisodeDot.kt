package com.example.myapplication.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.network.AppLanguage

@Composable
internal fun NewEpisodeDot(language: AppLanguage, modifier: Modifier = Modifier) {
    val label = if (language == AppLanguage.RU) "Вышла новая серия" else "New episode"
    Box(
        modifier.size(13.dp)
            .background(Color(0xFFFF453A), CircleShape)
            .border(1.5.dp, Color.White, CircleShape)
            .semantics { contentDescription = label },
    )
}
