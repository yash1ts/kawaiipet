package com.kawaiipet.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.llm.NanoAiState
import com.kawaiipet.app.overlay.PetOverlayService
import com.kawaiipet.app.ui.HomeViewModel
import com.kawaiipet.app.ui.StartPetRequestViewModel
import com.kawaiipet.app.ui.components.SlimeSvgImage
import com.kawaiipet.app.ui.navigation.Routes
import com.kawaiipet.app.util.PermissionHelper
import com.kawaiipet.app.util.Analytics

@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as ComponentActivity

    fun feedbackTap() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        view.playSoundEffect(SoundEffectConstants.CLICK)
    }
    val startPetRequestViewModel: StartPetRequestViewModel = hiltViewModel(activity)
    val startPetRequested by startPetRequestViewModel.startPetRequested.collectAsStateWithLifecycle()
    val nanoState by homeViewModel.nanoState.collectAsStateWithLifecycle()
    val nanoReady = nanoState is NanoAiState.Available
    var hasOverlay by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    var hasMic by remember { mutableStateOf(PermissionHelper.hasMicrophonePermission(context)) }
    var hasNotif by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(context)) }
    var pendingStartPet by remember { mutableStateOf(false) }
    var micDeniedAfterPrompt by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == false) {
            micDeniedAfterPrompt = true
        }
        hasMic = PermissionHelper.hasMicrophonePermission(context)
        hasNotif = PermissionHelper.hasNotificationPermission(context)
        if (pendingStartPet && hasOverlay && hasMic && homeViewModel.canStartPetWithNano()) {
            pendingStartPet = false
            Analytics.capture(event = "pet started")
            context.startForegroundService(Intent(context, PetOverlayService::class.java))
        } else if (pendingStartPet && !hasMic) {
            pendingStartPet = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = PermissionHelper.hasOverlayPermission(context)
                hasMic = PermissionHelper.hasMicrophonePermission(context)
                hasNotif = PermissionHelper.hasNotificationPermission(context)
                homeViewModel.refreshNanoStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun permissionsToRequest(): Array<String> = buildList {
        if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun tryStartPet() {
        when {
            !nanoReady -> return
            !hasOverlay -> context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
            !hasMic || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) -> {
                val need = permissionsToRequest()
                if (need.isEmpty()) {
                    Analytics.capture(event = "pet started")
                    context.startForegroundService(Intent(context, PetOverlayService::class.java))
                    return
                }
                pendingStartPet = true
                permissionLauncher.launch(need)
            }
            else -> {
                Analytics.capture(event = "pet started")
                context.startForegroundService(Intent(context, PetOverlayService::class.java))
            }
        }
    }

    LaunchedEffect(startPetRequested) {
        if (startPetRequested) {
            tryStartPet()
            startPetRequestViewModel.consumeStartPetRequest()
        }
    }

    val showMicOpenSettings = hasOverlay && !hasMic && micDeniedAfterPrompt &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SlimeSvgImage(
                modifier = Modifier.size(112.dp),
                contentDescription = stringResource(R.string.app_name),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "KawaiiPet",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Your AI companion, always by your side",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            NanoAiStatusSection(
                nanoState = nanoState,
                onDownload = {
                    feedbackTap()
                    homeViewModel.downloadNanoModel()
                },
                onRefresh = {
                    feedbackTap()
                    homeViewModel.refreshNanoStatus()
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    feedbackTap()
                    tryStartPet()
                },
                enabled = nanoReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    SlimeSvgImage(modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = when {
                        !nanoReady -> stringResource(R.string.nano_start_pet_disabled)
                        !hasOverlay -> "Grant Overlay Permission"
                        !hasMic || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) ->
                            "Grant Mic & Notifications"
                        else -> "Start Pet"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (!hasOverlay) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Overlay permission is required to show your pet on screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.overlay_restricted_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            feedbackTap()
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(context.getString(R.string.overlay_restricted_settings_help_url)),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.overlay_restricted_settings_help_learn_more))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            feedbackTap()
                            context.startActivity(PermissionHelper.createAppDetailsIntent(context))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.overlay_open_app_info))
                    }
                }
            }

            if (hasOverlay && !hasMic) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mic_permission_rationale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            if (showMicOpenSettings) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mic_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        feedbackTap()
                        context.startActivity(PermissionHelper.createAppDetailsIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(
                onClick = {
                    feedbackTap()
                    navController.navigate(Routes.CUSTOMIZE)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.customize_title))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    feedbackTap()
                    navController.navigate(Routes.SETTINGS)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.settings_title))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    feedbackTap()
                    navController.navigate(Routes.MEMORY)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("Pet Memory")
            }

        }
    }
}

@Composable
private fun NanoAiStatusSection(
    nanoState: NanoAiState,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
) {
    val statusText = when (nanoState) {
        NanoAiState.Checking -> stringResource(R.string.nano_status_checking)
        NanoAiState.Available -> stringResource(R.string.nano_status_ready)
        NanoAiState.Downloadable -> stringResource(R.string.nano_status_downloadable)
        NanoAiState.Downloading -> stringResource(R.string.nano_status_downloading)
        is NanoAiState.DownloadProgress -> stringResource(
            R.string.nano_status_download_progress,
            nanoState.bytesDownloaded,
        )
        NanoAiState.Unavailable -> stringResource(R.string.nano_status_unavailable)
        is NanoAiState.Failed -> stringResource(R.string.nano_status_failed, nanoState.message)
    }
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = when (nanoState) {
            NanoAiState.Available -> MaterialTheme.colorScheme.primary
            NanoAiState.Unavailable, is NanoAiState.Failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    when (nanoState) {
        NanoAiState.Downloadable -> {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nano_download_action))
            }
        }
        NanoAiState.Unavailable, is NanoAiState.Failed -> {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nano_refresh_status))
            }
        }
        else -> Unit
    }
}
