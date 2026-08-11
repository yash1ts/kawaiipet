package com.kawaiipet.app.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kawaiipet.app.pet.PetAnimationController
import com.kawaiipet.app.pet.PetViewModel
import com.kawaiipet.app.ui.theme.KawaiiPetTheme
import com.kawaiipet.app.util.UiFeedback

/** Match widest overlay chrome (e.g. [TextInputOverlay]). */
private val OverlayChromeMaxWidth = 240.dp

/** Max height for bubble / listening UI above the pet. */
private val OverlayChromeMaxHeight = 220.dp

/** Space between the bottom of subtitle/bubble chrome and the bottom of the chrome window (above pet). */
private val OverlayChromeBottomInset = 10.dp

/**
 * Bubble visibility must not depend on stale [responseText] after [OverlayState.Idle] (separate
 * StateFlows / frame ordering). Show only when we have real reply text.
 */
private fun bubbleVisible(state: OverlayState, responseText: String): Boolean {
    if (responseText.isBlank()) return false
    return when (state) {
        is OverlayState.Speaking, is OverlayState.Processing -> true
        else -> false
    }
}

/** Small composable tree for the pet-only overlay window (fixed size, does not grow with state). */
@Composable
fun OverlayPetWindowContent(
    animationController: PetAnimationController,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onPetDragStart: () -> Unit = {},
    onPetDragEnd: () -> Unit = {},
    onDismiss: () -> Unit
) {
    KawaiiPetTheme(dynamicColor = false) {
        PetOverlay(
            animationController = animationController,
            onTap = onTap,
            onDrag = onDrag,
            onDragStart = onPetDragStart,
            onDragEnd = onPetDragEnd,
            onDoubleTap = onDismiss
        )
    }
}

/** Separate overlay window: bubbles, listening line, text input — sized to content only. */
@Composable
fun OverlayChromeWindowContent(
    petViewModel: PetViewModel,
    uiFeedback: UiFeedback,
    onRequestFocus: (Boolean) -> Unit
) {
    val state by petViewModel.overlayState.collectAsState()
    val responseText by petViewModel.currentResponse.collectAsState()
    val listeningSubtitle by petViewModel.listeningSubtitle.collectAsState()

    KawaiiPetTheme(dynamicColor = false) {
        Column(
            modifier = Modifier
                .widthIn(max = OverlayChromeMaxWidth)
                .heightIn(max = OverlayChromeMaxHeight)
                .padding(bottom = OverlayChromeBottomInset),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = state is OverlayState.Listening || state is OverlayState.PreparingVoice,
                enter = fadeIn(animationSpec = tween(120)) + slideInVertically(animationSpec = tween(180)) { -it },
                exit = fadeOut(animationSpec = tween(100)) + slideOutVertically(animationSpec = tween(140)) { -it }
            ) {
                ListeningSubtitle(
                    text = if (listeningSubtitle.isBlank()) {
                        if (state is OverlayState.PreparingVoice) "Loading voice model…" else "Listening…"
                    } else listeningSubtitle,
                    isPlaceholder = listeningSubtitle.isBlank(),
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .padding(bottom = 6.dp)
                )
            }

            // No AnimatedVisibility: exit animation recomposed with Idle + empty text, ChatBubble
            // early-returned, which broke exit and left the bubble stuck on screen.
            if (bubbleVisible(state, responseText)) {
                ChatBubble(
                    text = responseText,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(bottom = 6.dp)
                )
            }

            AnimatedVisibility(
                visible = state is OverlayState.TextInput,
                enter = fadeIn(animationSpec = tween(120)) + slideInVertically(animationSpec = tween(180)) { it },
                exit = fadeOut(animationSpec = tween(100)) + slideOutVertically(animationSpec = tween(140)) { it }
            ) {
                TextInputOverlay(
                    uiFeedback = uiFeedback,
                    onSubmit = { text ->
                        onRequestFocus(false)
                        petViewModel.onTextSubmitted(text)
                    },
                    onDismiss = {
                        uiFeedback.click()
                        onRequestFocus(false)
                        petViewModel.dismissTextInput()
                    }
                )
            }
        }
    }
}
