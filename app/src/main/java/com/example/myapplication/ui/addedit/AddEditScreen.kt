package com.example.myapplication.ui.addedit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.getAddEditCommentStrings
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.rating.RatingOverlayHost
import com.example.myapplication.ui.shared.components.rating.RatingSliderState
import com.example.myapplication.ui.shared.components.rating.RatingTrackMode
import com.example.myapplication.ui.shared.components.rating.RatingTrackWidget
import com.example.myapplication.ui.shared.InertialCollisionState
import com.example.myapplication.ui.shared.inertialCollision
import com.example.myapplication.ui.shared.icons.AddEditSaveFabCheck
import com.example.myapplication.ui.shared.rememberInertialCollisionState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
private fun AnimatedFormRow(
    index: Int,
    collisionState: InertialCollisionState,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.inertialCollision(
            state = collisionState,
            index = index,
            baseMultiplier = 4.5f
        )
    ) {
        content()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AddEditScreen(
    navController: NavController,
    viewModel: AddEditViewModel,
    animeId: String?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    /**
     * Что делать после успешного сохранения. По умолчанию (маршрут) — уйти назад по стеку.
     * Страница рабочей области стеком не управляет и передаёт сюда своё «перелистнуть на Главную».
     */
    onSaved: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val strings = getStrings(currentLanguage)
    val commentStrings = getAddEditCommentStrings(currentLanguage)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        viewModel.onEvent(AddEditEvent.OnImageUriChanged(it))
    }
    val ctx = LocalContext.current
    val view = LocalView.current
    val textC = MaterialTheme.colorScheme.onBackground
    val collisionState = rememberInertialCollisionState()

    // Состояние рейтинг-слайдера hoisted на уровень экрана: им пользуются и компактный
    // трек в форме (владелец жеста), и полноэкранный бабл-оверлей поверх всего.
    val ratingRu = currentLanguage == AppLanguage.RU
    val ratingSliderState = remember { RatingSliderState(uiState.rating) }
    LaunchedEffect(uiState.rating) { ratingSliderState.syncFromExternal(uiState.rating) }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is AddEditEffect.NavigateBack -> onSaved?.invoke() ?: run { navController.popBackStack() }
                is AddEditEffect.ShowError -> android.widget.Toast.makeText(
                    ctx, effect.message, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(animeId) {
        viewModel.loadAnime(animeId)
        collisionState.triggerCollision(
            impactForce = 55f,
            stiffness = 200f,
            dampingRatio = 0.45f
        )
    }

    val bg = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(bg)
        drawContent()
    }

    // Новое добавление открывается окном из кнопки: форма идёт своей дорожкой (§5 спеки
    // движения) — проявляется, когда оболочка почти раскрылась, и гаснет первой при закрытии.
    // Редактирование из карточки морфит по-своему, его не трогаем.
    val windowContentAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = MotionTokens.EaseEnterMillis,
                    delayMillis = MotionTokens.WindowContentRevealDelayMillis,
                    easing = MotionTokens.EaseEnter,
                )
            } else {
                tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)
            }
        },
        label = "addEditContentAlpha",
    ) { state -> if (animeId != null || state == EnterExitState.Visible) 1f else 0f }

    with(sharedTransitionScope) {
        val sharedModifier = if (animeId == null) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "fab_container"),
                animatedVisibilityScope = animatedVisibilityScope,
                // Оболочка (фон бэкдропа) видна с первого кадра; уходя, гаснет, пока
                // схлопывается в кнопку.
                enter = EnterTransition.None,
                exit = fadeOut(
                    tween(
                        durationMillis = WINDOW_SHELL_FADE_MS,
                        delayMillis = MotionTokens.EaseExitMillis,
                        easing = MotionTokens.EaseExit,
                    ),
                ),
                // Открытие: окно — цель, и пружина берётся с его стороны.
                boundsTransform = { _, _ -> MotionTokens.largeSurfaceEnterBounds },
                // Remeasure, а не scaleToBounds: внутри лежит layerBackdrop, и его запись при
                // масштабировании в overlay — та же связка, что роняла Inspect в SIGSEGV.
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(32.dp))
            )
        } else {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "anime_${animeId}_bounds"),
                animatedVisibilityScope = animatedVisibilityScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(32.dp))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val fabAlpha by animateFloatAsState(
                targetValue = if (uiState.isValid && !uiState.isLoading) 1f else 0.5f,
                animationSpec = MotionTokens.standard(),
                label = "fabAlpha"
            )
            val fabIconTint =
                if (isAppInDarkTheme()) Color.White
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            val isDark = isAppInDarkTheme()

            val contentTopInset =
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(sharedModifier)
                        .clip(RoundedCornerShape(32.dp))
                        // Оболочка морфа. Фон бэкдропа (`drawRect(bg)`) уходит только в запись
                        // для стекла, а на экране окно красил внешний Box — ВНЕ shared bounds.
                        // При закрытии он гас вместе с формой, и в кнопку схлопывалась пустота.
                        .background(bg)
                        .layerBackdrop(backdrop)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = windowContentAlpha }
                    ) {
                        val scrollState = rememberScrollState()

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp)
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(contentTopInset))

                    // ── Cover photo ──
                    AnimatedFormRow(index = 0, collisionState = collisionState) {
                        Spacer(Modifier.height(8.dp))
                    }
                    AnimatedFormRow(index = 1, collisionState = collisionState) {
                        AddEditCoverPhotoSlot(
                            imageUri = uiState.imageUri,
                            imageFilePath = uiState.imageFilePath,
                            placeholderTitle = strings.addEditCoverPlaceholderTitle,
                            placeholderSubtitle = strings.addEditCoverPlaceholderSubtitle,
                            placeholderButtonLabel = strings.addEditCoverPlaceholderButton,
                            animeId = animeId,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onClick = {
                                performHaptic(view, "light")
                                launcher.launch("image/*")
                            }
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── Title ──
                    AnimatedFormRow(index = 2, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionTitle)
                    }
                    AnimatedFormRow(index = 3, collisionState = collisionState) {
                        PillTextFieldWithCopy(
                            value = uiState.title,
                            onValueChange = {
                                viewModel.onEvent(AddEditEvent.OnTitleChanged(it))
                            },
                            placeholder = strings.addEditTitlePlaceholder,
                            singleLine = false,
                            maxLines = 4
                        )
                    }

                    // ── Вариация названия (английская версия) ──
                    AnimatedFormRow(index = 3, collisionState = collisionState) {
                        if (uiState.showTitleEn) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                AddEditSectionLabel(
                                    if (currentLanguage == AppLanguage.RU) "АНГЛИЙСКОЕ НАЗВАНИЕ" else "ENGLISH TITLE"
                                )
                                PillTextFieldWithCopy(
                                    value = uiState.titleEn,
                                    onValueChange = {
                                        viewModel.onEvent(AddEditEvent.OnTitleEnChanged(it))
                                    },
                                    placeholder = if (currentLanguage == AppLanguage.RU)
                                        "Английская версия названия" else "English version of the title",
                                    singleLine = false,
                                    maxLines = 4
                                )
                            }
                        } else {
                            TextButton(onClick = { viewModel.onEvent(AddEditEvent.OnToggleTitleEn) }) {
                                Text(
                                    text = if (currentLanguage == AppLanguage.RU)
                                        "+ Добавить вариацию" else "+ Add variation"
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Episodes ──
                    AnimatedFormRow(index = 4, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionEpisodes)
                    }
                    AnimatedFormRow(index = 5, collisionState = collisionState) {
                        PillTextField(
                            value = uiState.episodes,
                            onValueChange = {
                                viewModel.onEvent(AddEditEvent.OnEpisodesChanged(it))
                            },
                            placeholder = strings.addEditEpisodesPlaceholder,
                            keyboardType = KeyboardType.Number
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Quick select ──
                    AnimatedFormRow(index = 6, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionQuickSelect)
                    }
                    AnimatedFormRow(index = 7, collisionState = collisionState) {
                        AddEditEpisodeQuickSelect(
                            selectedEpisodes = uiState.episodes,
                            onSelect = { selectedEp ->
                                performHaptic(view, "light")
                                viewModel.onEvent(AddEditEvent.OnEpisodesChanged(selectedEp))
                            }
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── Format category ──
                    AnimatedFormRow(index = 8, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionFormat)
                    }
                    AnimatedFormRow(index = 9, collisionState = collisionState) {
                        AddEditFormatCategorySection(
                            strings = strings,
                            currentLanguage = currentLanguage,
                            selectedTags = uiState.selectedTags,
                            activeCategory = uiState.categoryType,
                            onTagToggle = { tag, categoryType ->
                                val currentTags = uiState.selectedTags.toMutableList()
                                if (currentTags.contains(tag)) {
                                    currentTags.remove(tag)
                                    if (currentTags.isEmpty()) {
                                        viewModel.onEvent(
                                            AddEditEvent.OnTagsChanged(emptyList(), "")
                                        )
                                    } else {
                                        viewModel.onEvent(
                                            AddEditEvent.OnTagsChanged(
                                                currentTags,
                                                uiState.categoryType
                                            )
                                        )
                                    }
                                } else {
                                    val categoryMatches = uiState.categoryType.isEmpty() ||
                                            uiState.categoryType.equals(
                                                categoryType,
                                                ignoreCase = true
                                            )
                                    if (currentTags.size < 5 && categoryMatches) {
                                        currentTags.add(tag)
                                        viewModel.onEvent(
                                            AddEditEvent.OnTagsChanged(currentTags, categoryType)
                                        )
                                    }
                                }
                                performHaptic(view, "light")
                            }
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── Rating ──
                    AnimatedFormRow(index = 10, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionRating)
                    }
                    AnimatedFormRow(index = 11, collisionState = collisionState) {
                        RatingTrackWidget(
                            state = ratingSliderState,
                            mode = RatingTrackMode.Compact,
                            ru = ratingRu,
                            onCommitted = { newRate ->
                                viewModel.onEvent(AddEditEvent.OnRatingChanged(newRate))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    ratingSliderState.anchorBoundsInRoot = it.boundsInRoot()
                                },
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // ── Comment ──
                    AnimatedFormRow(index = 12, collisionState = collisionState) {
                        AddEditSectionLabel(strings.addEditSectionComment)
                    }
                    AnimatedFormRow(index = 13, collisionState = collisionState) {
                        CommentMorphingContainer(
                            state = uiState,
                            addCommentLabel = commentStrings.addButton,
                            commentPlaceholder = commentStrings.placeholder,
                            onModeChange = {
                                viewModel.onEvent(AddEditEvent.OnCommentModeChanged(it))
                            },
                            onSaveComment = {
                                viewModel.onEvent(AddEditEvent.OnSaveComment(it))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(120.dp))
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(48.dp)
                            .addEditMenuTileShadow(isDark, CircleShape)
                            .clip(CircleShape)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { CircleShape },
                                effects = {
                                    vibrancy()
                                    blur(12f.dp.toPx())
                                    lens(8f.dp.toPx(), 40f.dp.toPx())
                                }
                            )
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    performHaptic(view, "light")
                                    navController.popBackStack()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textC,
                            modifier = if (animeId == null) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "fab_icon"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            } else {
                                Modifier
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 220.dp)
                            .height(48.dp)
                            .addEditMenuTileShadow(isDark, RoundedCornerShape(100.dp))
                            .clip(RoundedCornerShape(100.dp))
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(100.dp) },
                                effects = {
                                    vibrancy()
                                    blur(12f.dp.toPx())
                                    lens(8f.dp.toPx(), 40f.dp.toPx())
                                }
                            )
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (animeId == null) strings.addTitle else strings.editTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = textC,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 24.dp, bottom = 24.dp)
                        .inertialCollision(collisionState, index = 20, baseMultiplier = 2.5f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .addEditMenuTileShadow(isDark, CircleShape)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { CircleShape },
                                effects = {
                                    vibrancy()
                                    blur(28f.dp.toPx())
                                    lens(16f.dp.toPx(), 48f.dp.toPx())
                                },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.12f)) }
                            )
                            .clip(CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .clickable {
                                if (uiState.isLoading) return@clickable
                                performHaptic(view, "success")
                                if (uiState.isValid) {
                                    viewModel.onEvent(AddEditEvent.OnSave)
                                } else {
                                    android.widget.Toast.makeText(
                                        ctx,
                                        strings.enterTitleToast,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            AddEditSaveFabCheck,
                            contentDescription = "Save",
                            tint = fabIconTint,
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer { alpha = fabAlpha }
                        )
                    }
                }

                // Полноэкранный бабл рейтинга — последний ребёнок корневого Box:
                // рисуется поверх формы и плавающих кнопок, вне клипа sharedBounds-слоя.
                RatingOverlayHost(
                    state = ratingSliderState,
                    ru = ratingRu,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Оболочка окна гаснет, пока схлопывается в кнопку. */
private const val WINDOW_SHELL_FADE_MS = 200
