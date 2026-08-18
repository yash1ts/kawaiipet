package com.kawaiipet.app.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.kawaiipet.app.assets.RequiredAssets
import com.kawaiipet.app.audio.DefaultVoiceModels
import com.kawaiipet.app.audio.ModelManager
import com.kawaiipet.app.overlay.PetOverlayService
import com.kawaiipet.app.ui.MainActivity
import java.io.File

/**
 * Starts the overlay pet from a user-initiated path (shortcut, QS tile, etc.).
 *
 * Android allows starting a [foreground service](https://developer.android.com/develop/background-work/services/foreground-services)
 * after a direct user action. Runtime permissions (mic, notifications) still require an activity,
 * so in that case this opens [MainActivity] with [ACTION_START_PET].
 */
object PetLauncher {
    const val ACTION_START_PET = "com.kawaiipet.app.action.START_PET"

    fun startPetFromExternalTrigger(context: Context) {
        val app = context.applicationContext
        when {
            !assetsReady(app) -> {
                app.startActivity(
                    Intent(app, MainActivity::class.java).apply {
                        action = ACTION_START_PET
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            }
            !PermissionHelper.hasOverlayPermission(app) -> {
                // Route through MainActivity so the in-app overlay rationale
                // (system AlertDialog) is shown before Settings.
                app.startActivity(
                    Intent(app, MainActivity::class.java).apply {
                        action = ACTION_START_PET
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            }
            !PermissionHelper.hasMicrophonePermission(app) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionHelper.hasNotificationPermission(app)) -> {
                app.startActivity(
                    Intent(app, MainActivity::class.java).apply {
                        action = ACTION_START_PET
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            }
            else -> {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, PetOverlayService::class.java),
                )
            }
        }
    }

    private fun assetsReady(context: Context): Boolean {
        val models = ModelManager(context)
        val llmOk = models.isModelDownloaded(RequiredAssets.LLM_MODEL_ID) &&
            File(
                models.getModelDir(RequiredAssets.LLM_MODEL_ID),
                RequiredAssets.LLM_FILE_NAME,
            ).isFile
        return llmOk &&
            models.isModelDownloaded(DefaultVoiceModels.STT_MODEL_ID) &&
            models.isModelDownloaded(DefaultVoiceModels.TTS_MODEL_ID)
    }
}
