package com.example.myapplication.ui.shared

import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Размытие фона под модальным листом: радиус постоянный, анимируется непрозрачность размытой копии.
 *
 * Спека движения запрещает анимировать радиус блюра: каждый кадр такой анимации — новый проход
 * размытия с другим ядром. Здесь размытая копия с фиксированным радиусом просто проявляется поверх
 * чёткой (SRC_OVER с альфой [amount]), визуально — то же «наплывание» блюра.
 *
 * Ставится в `graphicsLayer { renderEffect = … }` постоянного слоя: слой не добавляется и не
 * убирается из цепочки (над layerBackdrop это ломало стекло), а меняется только свойство.
 *
 * До API 31 размытия в платформе нет — как и у `Modifier.blur`, эффект просто не применяется.
 */
fun fadedBlurEffect(radiusPx: Float, amount: Float): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || amount <= 0.01f || radiusPx <= 0f) return null
    val blurred = android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    if (amount >= 0.99f) return blurred.asComposeRenderEffect()
    val fade = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, amount) })
    val fadedBlur = android.graphics.RenderEffect.createColorFilterEffect(fade, blurred)
    val sharp = android.graphics.RenderEffect.createOffsetEffect(0f, 0f)
    return android.graphics.RenderEffect
        .createBlendModeEffect(sharp, fadedBlur, BlendMode.SRC_OVER)
        .asComposeRenderEffect()
}
