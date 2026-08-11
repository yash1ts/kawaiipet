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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kawaiipet.app.R
import com.kawaiipet.app.overlay.PetOverlayService
import com.kawaiipet.app.ui.HomeViewModel
import com.kawaiipet.app.ui.StartPetRequestViewModel
import com.kawaiipet.app.ui.components.SlimeSvgImage
import com.kawaiipet.app.ui.navigation.Routes
import com.kawaiipet.app.util.Analytics
import com.kawaiipet.app.util.PermissionHelper

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
    var hasOverlay by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    var hasMic by remember { mutableStateOf(PermissionHelper.hasMicrophonePermission(context)) }
    var hasNotif by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(context)) }
    var pendingStartPet by remember { mutableStateOf(false) }
    var micDeniedAfterPrompt by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        hasOverlay = PermissionHelper.hasOverlayPermission(context)
        hasMic = PermissionHelper.hasMicrophonePermission(context)
        hasNotif = PermissionHelper.hasNotificationPermission(context)
    }

    fun permissionsToRequest(): Array<String> = buildList {
        if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun canLaunchPetService(): Boolean {
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotif
        return hasOverlay && hasMic && notifOk && homeViewModel.canStartPet()
    }

    fun startPetService() {
        Analytics.capture(event = "pet started")
        context.startForegroundService(Intent(context, PetOverlayService::class.java))
        startPetRequestViewModel.consumeStartPetRequest()
    }

    /**
     * Returns true only when the pet service was started (so pending external
     * start requests can be consumed safely).
     */
    fun tryStartPet(): Boolean {
        refreshPermissions()
        return when {
            !hasOverlay -> {
                context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
                false
            }
            !hasMic || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) -> {
                val need = permissionsToRequest()
                if (need.isEmpty()) {
                    startPetService()
                    true
                } else {
                    pendingStartPet = true
                    false
                }
            }
            else -> {
                startPetService()
                true
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == false) {
            micDeniedAfterPrompt = true
        }
        refreshPermissions()
        if (pendingStartPet) {
            pendingStartPet = false
            if (canLaunchPetService()) {
                startPetService()
            }
        }
    }

    // Launch the runtime permission sheet when tryStartPet queued it.
    LaunchedEffect(pendingStartPet) {
        if (!pendingStartPet) return@LaunchedEffect
        val need = permissionsToRequest()
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need)
        } else {
            pendingStartPet = false
            if (canLaunchPetService()) startPetService()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Summarize the last pet session into long-term memory when Home is shown.
    LaunchedEffect(Unit) {
        homeViewModel.flushSessionMemory()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                homeViewModel.flushSessionMemory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // External Start Pet (shortcut/tile): nudge once, then finish when permissions catch up.
    // Never consume the request until the service actually starts.
    var externalNudgeDone by remember { mutableStateOf(false) }
    LaunchedEffect(startPetRequested) {
        if (startPetRequested && !externalNudgeDone) {
            externalNudgeDone = true
            tryStartPet()
        }
        if (!startPetRequested) {
            externalNudgeDone = false
        }
    }
    LaunchedEffect(startPetRequested, hasOverlay, hasMic, hasNotif) {
        if (startPetRequested && canLaunchPetService()) {
            startPetService()
        } else if (
            startPetRequested &&
            hasOverlay &&
            permissionsToRequest().isNotEmpty() &&
            !pendingStartPet
        ) {
            // Overlay just granted — continue with mic/notifications.
            pendingStartPet = true
        }
    }

    val showMicOpenSettings = hasOverlay && !hasMic && micDeniedAfterPrompt &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    val primaryLabel = when {
        !hasOverlay -> stringResource(R.string.home_grant_overlay)
        !hasMic || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) ->
            stringResource(R.string.home_grant_mic_notif)
        else -> stringResource(R.string.home_start_pet)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            SlimeSvgImage(
                modifier = Modifier.size(112.dp),
                contentDescription = stringResource(R.string.app_name),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.home_models_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    feedbackTap()
                    tryStartPet()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
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
                    text = primaryLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (!hasOverlay) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.home_overlay_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.overlay_restricted_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
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
                    OutlinedButton(
                        onClick = {
                            feedbackTap()
                            context.startActivity(PermissionHelper.createAppDetailsIntent(context))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.overlay_open_app_info))
                    }
                }
            }

            if (hasOverlay && !hasMic) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.mic_permission_rationale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (showMicOpenSettings) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mic_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        feedbackTap()
                        context.startActivity(PermissionHelper.createAppDetailsIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.home_memory))
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
