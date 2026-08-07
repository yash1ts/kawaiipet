package com.kawaiipet.app.ui.screens

import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kawaiipet.app.R
import com.kawaiipet.app.assets.AssetDownloadState
import com.kawaiipet.app.assets.RequiredAssets
import com.kawaiipet.app.ui.components.SlimeSvgImage
import com.kawaiipet.app.ui.theme.SlimeWaterLight
import com.kawaiipet.app.ui.theme.SurfaceLight

@Composable
fun DownloadingAssetsScreen(
    state: AssetDownloadState,
    onRetry: () -> Unit,
) {
    KeepScreenOn(enabled = state !is AssetDownloadState.Failed && state !is AssetDownloadState.Ready)

    val completedIds = when (state) {
        is AssetDownloadState.Downloading -> state.completedIds
        is AssetDownloadState.Installing -> state.completedIds
        is AssetDownloadState.Failed -> state.completedIds
        AssetDownloadState.Ready -> RequiredAssets.ALL.map { it.id }.toSet()
        AssetDownloadState.Checking -> emptySet()
    }
    val currentId = when (state) {
        is AssetDownloadState.Downloading -> state.currentId
        is AssetDownloadState.Installing -> state.currentId
        else -> null
    }
    val progress = when (state) {
        is AssetDownloadState.Downloading -> state.overallFraction
        is AssetDownloadState.Installing -> state.overallFraction
        AssetDownloadState.Ready -> 1f
        else -> null
    }

    val pulse = rememberInfiniteTransition(label = "slimePulse")
    val slimeScale by pulse.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slimeScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SlimeWaterLight.copy(alpha = 0.55f),
                        SurfaceLight,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            SlimeSvgImage(
                modifier = Modifier
                    .size(104.dp)
                    .scale(if (state is AssetDownloadState.Failed) 1f else slimeScale),
                contentDescription = stringResource(R.string.app_name),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.assets_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(28.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = statusHeadline(state),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (state is AssetDownloadState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = statusDetail(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    when (state) {
                        is AssetDownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { progress!!.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = formatBytes(state.bytesDownloaded, state.totalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is AssetDownloadState.Installing -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.assets_installing_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AssetDownloadState.Checking -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        is AssetDownloadState.Failed -> {
                            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.assets_retry))
                            }
                        }
                        AssetDownloadState.Ready -> Unit
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    RequiredAssets.ALL.forEachIndexed { i, asset ->
                        val done = asset.id in completedIds
                        val active = asset.id == currentId
                        AssetRow(
                            name = asset.displayName,
                            done = done,
                            active = active && !done,
                            failed = state is AssetDownloadState.Failed && active && !done,
                        )
                        if (i != RequiredAssets.ALL.lastIndex) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = stringResource(R.string.assets_wifi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AssetRow(
    name: String,
    done: Boolean,
    active: Boolean,
    failed: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val icon = when {
            done -> Icons.Outlined.CheckCircle
            failed -> Icons.Outlined.ErrorOutline
            active -> Icons.Outlined.CloudDownload
            else -> Icons.Outlined.HourglassTop
        }
        val tint = when {
            done -> MaterialTheme.colorScheme.primary
            failed -> MaterialTheme.colorScheme.error
            active -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                done -> MaterialTheme.colorScheme.onSurface
                failed -> MaterialTheme.colorScheme.error
                active -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                done -> stringResource(R.string.assets_row_done)
                failed -> stringResource(R.string.assets_row_failed)
                active -> stringResource(R.string.assets_row_active)
                else -> stringResource(R.string.assets_row_waiting)
            },
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

@Composable
private fun statusHeadline(state: AssetDownloadState): String = when (state) {
    AssetDownloadState.Checking -> stringResource(R.string.assets_checking)
    AssetDownloadState.Ready -> stringResource(R.string.assets_ready)
    is AssetDownloadState.Downloading -> stringResource(
        R.string.assets_downloading_named,
        state.assetName,
        state.assetIndex + 1,
        state.assetCount,
    )
    is AssetDownloadState.Installing -> stringResource(
        R.string.assets_installing_named,
        state.assetName,
    )
    is AssetDownloadState.Failed -> stringResource(R.string.assets_failed_title)
}

@Composable
private fun statusDetail(state: AssetDownloadState): String = when (state) {
    AssetDownloadState.Checking -> stringResource(R.string.assets_checking_detail)
    AssetDownloadState.Ready -> stringResource(R.string.assets_ready)
    is AssetDownloadState.Downloading -> stringResource(R.string.assets_downloading_detail)
    is AssetDownloadState.Installing -> stringResource(R.string.assets_installing_hint)
    is AssetDownloadState.Failed -> state.message
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? android.app.Activity)?.window
        if (enabled && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun formatBytes(downloaded: Long, total: Long?): String {
    fun mb(v: Long) = "%.1f".format(v / (1024f * 1024f))
    return if (total != null && total > 0) {
        "${mb(downloaded)} / ${mb(total)} MB"
    } else {
        "${mb(downloaded)} MB"
    }
}
