package com.example.myapplication.ui.addedit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import com.example.myapplication.ui.shared.icons.HeroCheck
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.CommentMorphingContainer(
    comment: String,
    commentMode: CommentMode,
    addCommentLabel: String,
    commentPlaceholder: String,
    onModeChange: (CommentMode) -> Unit,
    onSaveComment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember(comment) { mutableStateOf(comment) }
    val focusRequester = remember { FocusRequester() }
    val isDark = isAppInDarkTheme()

    BackHandler(enabled = commentMode == CommentMode.Editing) {
        onSaveComment(inputText)
    }

    AnimatedContent(
        targetState = commentMode,
        label = "CommentContainerTransform",
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        modifier = modifier.fillMaxWidth()
    ) { mode ->
        when (mode) {
            CommentMode.AddButton -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sharedBounds(
                            rememberSharedContentState(key = "comment_bounds"),
                            animatedVisibilityScope = this@AnimatedContent,
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(50))
                        )
                        .fillMaxWidth()
                        .height(56.dp)
                        .addEditMenuTileShadow(isDark, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .clickable { onModeChange(CommentMode.Editing) }
                        .background(Color.White)
                ) {
                    Text(
                        text = addCommentLabel,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            CommentMode.Editing -> {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .imePadding()
                        .padding(bottom = 72.dp)
                        .sharedBounds(
                            rememberSharedContentState(key = "comment_bounds"),
                            animatedVisibilityScope = this@AnimatedContent,
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(24.dp))
                        )
                        .fillMaxWidth()
                        .addEditMenuTileShadow(isDark, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(0.5.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = Color.Black
                        ),
                        cursorBrush = SolidColor(Color.Black),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { onSaveComment(inputText) }
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (inputText.isEmpty()) {
                                    Text(
                                        commentPlaceholder,
                                        color = Color.Black.copy(alpha = 0.4f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    IconButton(
                        onClick = { onSaveComment(inputText) },
                        modifier = Modifier
                            .size(48.dp)
                            .addEditMenuTileShadow(isDark, CircleShape)
                            .clip(CircleShape)
                            .background(Color.White)
                    ) {
                        Icon(
                            imageVector = HeroCheck,
                            contentDescription = "Save",
                            tint = Color.Black
                        )
                    }
                }
            }

            CommentMode.Saved -> {
                Box(
                    modifier = Modifier
                        .sharedBounds(
                            rememberSharedContentState(key = "comment_bounds"),
                            animatedVisibilityScope = this@AnimatedContent,
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp))
                        )
                        .fillMaxWidth()
                        .addEditMenuTileShadow(isDark, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onModeChange(CommentMode.Editing) }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        .padding(16.dp)
                ) {
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
