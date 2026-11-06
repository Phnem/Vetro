package com.example.myapplication.audiobooks.ui.home

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.phnem.vetro.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Координаты фактуры переплёта. Обрез, сгиб и блики совмещены с плоскостью печати.
private const val MODEL_WIDTH = 200f
private const val MODEL_HEIGHT = 254f
private const val FACE_LEFT = 36f
private const val FACE_TOP = 50f
private const val FACE_WIDTH = 161f
private const val FACE_HEIGHT = 201f
private const val SLOPE = 0.13f

/**
 * Ширина фактур обычного набора (400 px на всю модель). Крупнее этого книга берёт набор `_hd`
 * (800 px): Android растягивает картинку только билинейно, и большая книга на полке размывалась.
 * Маленькие книги главной остаются на 1x — сжатие 800 px втрое билинейно снова дало бы «пилу».
 */
private const val SD_TEXTURE_WIDTH_PX = 400f
private const val HD_FROM_WIDTH_PX = SD_TEXTURE_WIDTH_PX * 1.1f

/**
 * Android shares the drawable's decoded pixels between all visible books. Decoded on first use, so
 * a screen with only small books never loads the HD set.
 */
@Composable
private fun bindingTexture(resourceId: Int): Lazy<ImageBitmap> {
    val resources = LocalResources.current
    return remember(resources, resourceId) {
        lazy(LazyThreadSafetyMode.NONE) {
            (resources.getDrawable(resourceId, null) as BitmapDrawable).bitmap.asImageBitmap()
        }
    }
}

@Composable
internal fun Book3D(
    coverColor: Color,
    modifier: Modifier = Modifier,
    shadowStrength: Float = 0.5f,
    content: @Composable BoxScope.() -> Unit,
) {
    val silhouetteSd = bindingTexture(R.drawable.book_binding_mask)
    val materialSd = bindingTexture(R.drawable.book_binding_light)
    val silhouetteHd = bindingTexture(R.drawable.book_binding_mask_hd)
    val materialHd = bindingTexture(R.drawable.book_binding_light_hd)
    Box(modifier.drawBehind {
        val radius = size.width * 0.57f
        if (radius > 0f) {
            val center = Offset(size.width * 0.49f, size.height * 0.957f)
            rotate(-7f, center) {
                scale(1f, 0.14f, center) {
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = shadowStrength), Color.Transparent), center, radius), radius, center)
                }
            }
        }
    }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val sx = size.width / MODEL_WIDTH
                    val sy = size.height / MODEL_HEIGHT
                    val pixels = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    val hd = size.width > HD_FROM_WIDTH_PX
                    val material = (if (hd) materialHd else materialSd).value
                    val silhouette = (if (hd) silhouetteHd else silhouetteSd).value
                    val projection = Matrix().apply {
                        this[0, 1] = if (sx > 0f) -SLOPE * sy / sx else 0f
                        this[3, 1] = SLOPE * FACE_LEFT * sy
                    }
                    onDrawWithContent {
                        if (pixels.width > 0 && pixels.height > 0) {
                            drawRect(coverColor)
                            drawImage(material, dstSize = pixels)
                            withTransform({ transform(projection) }) { this@onDrawWithContent.drawContent() }
                            drawImage(silhouette, dstSize = pixels, blendMode = BlendMode.DstIn)
                        }
                    }
                },
        ) {
            Box(Modifier.bookFace().clipToBounds(), content = content)
        }
    }
}

private fun Modifier.bookFace(): Modifier = layout { measurable, constraints ->
    val sx = constraints.maxWidth / MODEL_WIDTH
    val sy = constraints.maxHeight / MODEL_HEIGHT
    val face = measurable.measure(Constraints.fixed((FACE_WIDTH * sx).roundToInt(), (FACE_HEIGHT * sy).roundToInt()))
    layout(constraints.maxWidth, constraints.maxHeight) {
        face.place((FACE_LEFT * sx).roundToInt(), (FACE_TOP * sy).roundToInt())
    }
}

// ---- Цвет крышки по обложке ----

/** Средний цвет обложки по адресу: считается один раз за процесс, обложек на экране немного. */
private val coverTints = LruCache<String, Int>(256)

/** Нейтральная крышка, пока обложка грузится или её цвет ещё не посчитан. */
internal val NeutralCover = Color(0xFF2A2A2A)

/**
 * Цвет крышки под обложку: средний цвет картинки, приглушённый к тёмному, — задняя крышка и края
 * выглядят продолжением самой обложки, как у настоящей книги. [bitmap] приходит из onSuccess
 * загрузчика; до него — [NeutralCover].
 */
@Composable
internal fun rememberCoverTint(key: String?, bitmap: Bitmap?): Color {
    var tint by remember(key) { mutableStateOf(key?.let { coverTints.get(it) }?.let(::Color)) }
    LaunchedEffect(key, bitmap) {
        if (key == null || bitmap == null || tint != null) return@LaunchedEffect
        val argb = withContext(Dispatchers.Default) { averageColor(bitmap) } ?: return@LaunchedEffect
        coverTints.put(key, argb)
        tint = Color(argb)
    }
    val target = tint?.let { lerp(it, Color.Black, 0.18f) } ?: NeutralCover
    return animateColorAsState(target, MotionTokens.tweenStandard(), label = "coverTint").value
}

private fun averageColor(bitmap: Bitmap): Int? = runCatching {
    // HARDWARE-битмап нельзя читать по пикселям — сначала программная копия.
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val soft = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    Bitmap.createScaledBitmap(soft, 1, 1, true).getPixel(0, 0) or (0xFF shl 24)
}.getOrNull()
