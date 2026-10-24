package com.example.myapplication.ui.secret

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.typeManga
import com.example.myapplication.data.models.typeMovie
import com.example.myapplication.data.models.typeSeries
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.security.SecretFolderAuth
import com.example.myapplication.security.findFragmentActivity
import com.example.myapplication.ui.home.AnimeCardState
import com.example.myapplication.ui.home.OneUiAnimeCard
import com.example.myapplication.ui.navigation.navigateToAddEdit
import com.example.myapplication.ui.navigation.navigateToDetails
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.sin

private const val BULGE_SEGMENTS = 64

/** Цвета секретной папки: те же бренд-зелёный и моно, но в другом ключе, чем оранжевая главная. */
private class SecretPalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val onAccent: Color,
) {
    val accent: Color = BrandOrange

    companion object {
        fun of(dark: Boolean) = if (dark) {
            SecretPalette(
                background = Color(0xFF0C0A09),
                surface = Color(0xFF1A1613),
                ink = Color(0xFFF5F0EC),
                muted = Color(0xFF9C928A),
                onAccent = Color.White,
            )
        } else {
            SecretPalette(
                background = Color(0xFFF5EFEA),
                surface = Color(0xFFFFFFFF),
                ink = Color(0xFF1A1411),
                muted = Color(0xFF6F645C),
                onAccent = Color.White,
            )
        }
    }
}

/** Вытянутая кромка как контур слоя: левый край уходит за экран, правый — «пузырь» у пальца. */
private class SecretBulgeShape(
    private val touchY: Float,
    private val edge: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        path.moveTo(0f, 0f)
        for (i in 0..BULGE_SEGMENTS) {
            val y = size.height * i / BULGE_SEGMENTS
            // Граница задана в экранных координатах, слой сдвинут влево на (ширина − edge).
            val x = SecretBulge.screenX(y, touchY, size.height, edge, size.width) + (size.width - edge)
            path.lineTo(x, y)
        }
        path.lineTo(0f, size.height)
        path.close()
        return Outline.Generic(path)
    }
}

private fun bulgePath(touchY: Float, edge: Float, width: Float, height: Float): Path {
    val path = Path()
    for (i in 0..BULGE_SEGMENTS) {
        val y = height * i / BULGE_SEGMENTS
        val x = SecretBulge.screenX(y, touchY, height, edge, width)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/**
 * Секретная папка поверх рабочей области: вытянутая кромка при жесте, потом три экрана —
 * «засов» (каждый раз при открытии, пока не пройдена биометрия), пустое состояние и основной
 * список. Содержимое не компонуется, пока засов не открыт.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SecretFolderOverlay(
    host: SecretFolderHostState,
    viewModel: SecretFolderViewModel,
    language: AppLanguage,
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val open = viewModel.isOpen
    BackHandler(enabled = open) { host.close() }

    // Приложение ушло в фон — папка закрывается и снова встретит засовом.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) host.closeNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val visible by remember(host) { derivedStateOf { viewModel.isOpen || host.edge > 0.5f } }
    if (!visible) return

    val isDark = isAppInDarkTheme()
    val palette = remember(isDark) { SecretPalette.of(isDark) }
    val ru = language == AppLanguage.RU
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findFragmentActivity() }
    val unlocked = viewModel.unlocked

    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val authenticate: () -> Unit = {
        if (!running && activity != null) {
            message = null
            running = SecretFolderAuth.authenticate(
                activity = activity,
                ru = ru,
                onSuccess = {
                    running = false
                    performHaptic(view, Haptic.Success)
                    viewModel.unlock()
                },
                onFailure = {
                    running = false
                    message = it
                },
            )
        }
    }
    val currentAuthenticate by rememberUpdatedState(authenticate)
    val fullyOpen by remember(host) {
        derivedStateOf { viewModel.isOpen && host.screenWidth > 0f && host.edge >= host.screenWidth * 0.98f }
    }
    // Засов сам просит биометрию, как только папка встала на место.
    LaunchedEffect(fullyOpen, unlocked) {
        if (fullyOpen && !unlocked) currentAuthenticate()
    }

    val boltExit = animateFloatAsState(
        targetValue = if (unlocked) 1f else 0f,
        animationSpec = tween(durationMillis = 340, delayMillis = 220, easing = FastOutSlowInEasing),
        label = "secretBoltExit",
    )
    val showBolt by remember { derivedStateOf { !viewModel.unlocked || boltExit.value < 0.999f } }

    val base = MaterialTheme.colorScheme
    val scheme = remember(base, palette) {
        base.copy(
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surface,
            primary = palette.accent,
            onBackground = palette.ink,
            onSurface = palette.ink,
            secondary = palette.muted,
            onSurfaceVariant = palette.muted,
        )
    }

    val closeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var dragged by remember { mutableStateOf(0f) }

    Box(modifier.fillMaxSize().onSizeChanged { host.updateSize(it.width.toFloat(), it.height.toFloat()) }) {
        // Мягкий шлейф за кромкой: три штриха разной ширины вместо дорогого размытия.
        Canvas(Modifier.fillMaxSize()) {
            val edge = host.edge
            if (edge <= 0.5f || edge >= size.width - 1f) return@Canvas
            val path = bulgePath(host.touchY, edge, size.width, size.height)
            val strength = (edge / (size.width * SecretPullGeometry.MAX_STRETCH_FRACTION)).coerceIn(0f, 1f)
            drawPath(path, Color.Black.copy(alpha = 0.05f * strength), style = Stroke(width = 96.dp.toPx()))
            drawPath(path, Color.Black.copy(alpha = 0.07f * strength), style = Stroke(width = 56.dp.toPx()))
            drawPath(path, Color.Black.copy(alpha = 0.10f * strength), style = Stroke(width = 24.dp.toPx()))
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val width = host.screenWidth
                    val edge = host.edge
                    translationX = edge - width
                    if (edge >= width - 0.5f) {
                        clip = false
                        shape = RectangleShape
                    } else {
                        clip = true
                        shape = SecretBulgeShape(host.touchY, edge)
                    }
                }
                // Папка — верхний слой: страницы под ней касаний не получают.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
                // Потянуть влево — закрыть, как закрывается шторка.
                .pointerInput(open) {
                    if (open) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                if (dragged < -closeThreshold) host.close()
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                dragged += delta
                            },
                        )
                    }
                },
        ) {
            MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    if (unlocked) {
                        SecretContent(
                            viewModel = viewModel,
                            palette = palette,
                            language = language,
                            navController = navController,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onClose = {
                                performHaptic(view, Haptic.Light)
                                host.close()
                            },
                        )
                    }
                    if (showBolt) {
                        SecretBolt(
                            palette = palette,
                            ru = ru,
                            unlocked = unlocked,
                            message = message,
                            running = running,
                            onAuthenticate = authenticate,
                            onClose = {
                                performHaptic(view, Haptic.Light)
                                host.close()
                            },
                            modifier = Modifier.graphicsLayer { translationX = -size.width * boltExit.value },
                        )
                    }
                }
            }
        }

        // Контур кромки поверх слоя: пока тянут, видно, где она проходит.
        Canvas(Modifier.fillMaxSize()) {
            val edge = host.edge
            if (edge <= 0.5f || edge >= size.width - 1f) return@Canvas
            val strength = (edge / (size.width * SecretPullGeometry.MAX_STRETCH_FRACTION)).coerceIn(0f, 1f)
            drawPath(
                bulgePath(host.touchY, edge, size.width, size.height),
                palette.accent.copy(alpha = 0.85f * strength),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

// ——— Экран 1: засов ———

@Composable
private fun SecretBolt(
    palette: SecretPalette,
    ru: Boolean,
    unlocked: Boolean,
    message: String?,
    running: Boolean,
    onAuthenticate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slide by animateFloatAsState(
        targetValue = if (unlocked) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "secretBoltSlide",
    )
    Column(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoltIllustration(palette, slide, Modifier.size(width = 240.dp, height = 132.dp))
        Spacer(Modifier.height(36.dp))
        Text(
            text = if (ru) "Секретная папка" else "Secret folder",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Black),
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message ?: if (ru) "Подтвердите отпечатком пальца или лицом, чтобы отодвинуть засов" else "Confirm with your fingerprint or face to slide the bolt",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = if (running) 0.5f else 1f))
                .clickable(enabled = !running, onClick = onAuthenticate)
                .padding(horizontal = 32.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ru) "Подтвердить" else "Confirm",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
                color = palette.onAccent,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (ru) "Закрыть" else "Close",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily),
            color = palette.muted,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** Засов: планка продета в две скобы; при [slide] = 1 она выезжает из правой. */
@Composable
private fun BoltIllustration(palette: SecretPalette, slide: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val centerY = size.height / 2f
        drawRoundRect(
            color = palette.ink.copy(alpha = 0.06f),
            cornerRadius = CornerRadius(28.dp.toPx()),
        )
        val guideWidth = 34.dp.toPx()
        val guideHeight = 52.dp.toPx()
        val leftX = 30.dp.toPx()
        val rightX = size.width - 30.dp.toPx() - guideWidth
        val guideColor = palette.ink.copy(alpha = 0.38f)
        listOf(leftX, rightX).forEach { x ->
            drawRoundRect(
                color = guideColor,
                topLeft = Offset(x, centerY - guideHeight / 2f),
                size = Size(guideWidth, guideHeight),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(stroke),
            )
        }
        val barHeight = 18.dp.toPx()
        val barLength = 150.dp.toPx()
        val travel = 46.dp.toPx()
        val barLeft = leftX + 10.dp.toPx() - travel * slide
        drawRoundRect(
            color = palette.accent,
            topLeft = Offset(barLeft, centerY - barHeight / 2f),
            size = Size(barLength, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
        val knobCenter = Offset(barLeft + 34.dp.toPx(), centerY)
        drawCircle(palette.background, radius = 11.dp.toPx(), center = knobCenter)
        drawCircle(palette.ink, radius = 5.dp.toPx(), center = knobCenter)
    }
}

// ——— Экраны 2 и 3: основной и пустое состояние ———

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SecretContent(
    viewModel: SecretFolderViewModel,
    palette: SecretPalette,
    language: AppLanguage,
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
) {
    val titles by viewModel.titles.collectAsStateWithLifecycle()
    val ru = language == AppLanguage.RU
    val view = LocalView.current
    val genreRepository: GenreRepository = koinInject()
    val strings = getStrings(language)

    Column(Modifier.fillMaxSize().background(palette.background)) {
        SecretHeader(palette, ru, titles.size, onClose)
        if (titles.isEmpty()) {
            SecretEmptyState(palette, ru, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(titles, key = { it.id }) { anime ->
                    val typeLabel = when (anime.mediaType) {
                        MediaType.ANIME -> strings.typeAnime
                        MediaType.MANGA -> strings.typeManga
                        MediaType.MOVIE -> strings.typeMovie
                        MediaType.SERIES -> strings.typeSeries
                    }
                    val state = remember(anime, language) {
                        secretCardState(anime, language, genreRepository, typeLabel, viewModel.imagePath(anime.imageFileName))
                    }
                    with(sharedTransitionScope) {
                        OneUiAnimeCard(
                            state = state,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onClick = {
                                performHaptic(view, Haptic.Light)
                                navController.navigateToDetails(anime.id)
                            },
                            onEditClick = {
                                performHaptic(view, Haptic.Light)
                                navController.navigateToAddEdit(anime.id)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun secretCardState(
    anime: Anime,
    language: AppLanguage,
    genreRepository: GenreRepository,
    mediaTypeLabel: String,
    imagePath: String?,
): AnimeCardState {
    val title = when (language) {
        AppLanguage.EN -> anime.titleEn?.takeIf { it.isNotBlank() } ?: anime.title
        AppLanguage.RU -> anime.titleRu?.takeIf { it.isNotBlank() } ?: anime.title
    }
    return AnimeCardState(
        id = anime.id,
        title = title,
        rating = anime.rating,
        genres = persistentListOf(
            *anime.tags.take(3)
                .mapNotNull { genreRepository.getLabel(it, language).takeIf { name -> name.isNotBlank() } }
                .toTypedArray(),
        ),
        episodesLabel = if (anime.mediaType == MediaType.MANGA) "${anime.episodes} ch." else "${anime.episodes} eps.",
        imagePath = imagePath,
        mediaTypeLabel = mediaTypeLabel,
        mediaType = anime.mediaType,
        isFavorite = anime.isFavorite,
        language = language,
    )
}

@Composable
private fun SecretHeader(palette: SecretPalette, ru: Boolean, count: Int, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (ru) "Секретная папка" else "Secret folder",
                maxLines = 1,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = SnProFamily,
                    letterSpacing = (-0.5).sp,
                ),
                color = palette.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = titlesCountLabel(count, ru),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                color = palette.accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = if (ru) "Закрыть" else "Close", tint = palette.ink)
        }
    }
}

private fun titlesCountLabel(count: Int, ru: Boolean): String {
    if (!ru) return if (count == 1) "1 title" else "$count titles"
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "тайтлов"
        last == 1 -> "тайтл"
        last in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    return "$count $word"
}

/** Пустая папка: засыпающая папка покачивается, над ней всплывают «z». */
@Composable
private fun SecretEmptyState(palette: SecretPalette, ru: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "secretEmpty")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "secretEmptyBob",
    )
    val rise by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3300, easing = LinearEasing), RepeatMode.Restart),
        label = "secretEmptyRise",
    )
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(150.dp)
                    .graphicsLayer { translationY = (bob - 0.5f) * 14.dp.toPx() },
            ) {
                val stroke = 3.dp.toPx()
                val corner = CornerRadius(18.dp.toPx())
                // Язычок и корпус папки.
                drawRoundRect(
                    color = palette.surface,
                    topLeft = Offset(10.dp.toPx(), 24.dp.toPx()),
                    size = Size(54.dp.toPx(), 30.dp.toPx()),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                drawRoundRect(
                    color = palette.accent,
                    topLeft = Offset(10.dp.toPx(), 24.dp.toPx()),
                    size = Size(54.dp.toPx(), 30.dp.toPx()),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color = palette.surface,
                    topLeft = Offset(10.dp.toPx(), 40.dp.toPx()),
                    size = Size(130.dp.toPx(), 92.dp.toPx()),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = palette.accent,
                    topLeft = Offset(10.dp.toPx(), 40.dp.toPx()),
                    size = Size(130.dp.toPx(), 92.dp.toPx()),
                    cornerRadius = corner,
                    style = Stroke(stroke),
                )
                // Закрытые глаза и маленький рот.
                listOf(52f, 98f).forEach { x ->
                    drawArc(
                        color = palette.ink,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset((x - 9f).dp.toPx(), 74.dp.toPx()),
                        size = Size(18.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                drawCircle(
                    color = palette.ink,
                    radius = 4.dp.toPx(),
                    center = Offset(75.dp.toPx(), 104.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
            repeat(3) { index ->
                val phase = (rise + index / 3f) % 1f
                Text(
                    text = "z",
                    color = palette.accent,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = (14 + 10 * phase).sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                (-26.dp.toPx() - 14.dp.toPx() * phase).toInt(),
                                (36.dp.toPx() - 30.dp.toPx() * phase).toInt(),
                            )
                        }
                        .graphicsLayer { alpha = sin(phase * PI.toFloat()).coerceIn(0f, 1f) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (ru) "Здесь пока пусто" else "Nothing here yet",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (ru) "Добавьте тайтл через меню «⋯» на его странице — в ленте он больше не появится" else "Add a title from the “⋯” menu on its page — it will no longer appear in your feed",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
    }
}
