package com.kawaiipet.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Full-screen dim behind the pet during a usage nudge.
 * The overlay window is FLAG_NOT_TOUCHABLE so taps pass through to the app below.
 */
@Composable
fun OverlayUsageNudgeScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    )
}
