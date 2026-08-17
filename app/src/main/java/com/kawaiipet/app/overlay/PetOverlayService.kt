package com.kawaiipet.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kawaiipet.app.KawaiiPetApplication
import com.kawaiipet.app.R
import com.kawaiipet.app.audio.AudioPipeline
import com.kawaiipet.app.audio.ModelManager
import com.kawaiipet.app.llm.ConversationManager
import com.kawaiipet.app.llm.LlmEngineWarmup
import com.kawaiipet.app.pet.PetAnimationController
import com.kawaiipet.app.pet.PetBrain
import com.kawaiipet.app.pet.PetViewModel
import com.kawaiipet.app.ui.AiTriggerActivity
import com.kawaiipet.app.ui.MainActivity
import com.kawaiipet.app.util.Analytics
import com.kawaiipet.app.util.PreferenceManager
import com.kawaiipet.app.util.UiFeedback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Floating pet overlay — restored Lottie Compose visuals with AiTrigger trampoline for AICore.
 */
@AndroidEntryPoint
class PetOverlayService : Service() {

    @Inject lateinit var conversationManager: ConversationManager
    @Inject lateinit var petBrain: PetBrain
    @Inject lateinit var llmEngineWarmup: LlmEngineWarmup
    @Inject lateinit var audioPipeline: AudioPipeline
    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var preferenceManager: PreferenceManager
    @Inject lateinit var animationController: PetAnimationController
    @Inject lateinit var uiFeedback: UiFeedback

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var petOverlayView: ComposeView? = null
    private var chromeOverlayView: ComposeView? = null
    private val lifecycleOwner = OverlayLifecycleOwner()
    private lateinit var petViewModel: PetViewModel
    private lateinit var petLayoutParams: WindowManager.LayoutParams
    private lateinit var chromeLayoutParams: WindowManager.LayoutParams
    private val petScreenLoc = IntArray(2)
    private val chromePositionListener = ViewTreeObserver.OnGlobalLayoutListener { syncChromePosition() }
    private var closeDragHintView: ComposeView? = null
    private var usageNudgeScrimView: ComposeView? = null
    /** True while a usage-nudge scrim is up; cleared when speak ends or user taps scrim. */
    private var usageNudgeAttentionActive = false
    /** True after a successful mic FGS promote (requires foreground-eligible start). */
    private var microphoneForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        // Fresh short-term chat + sticky LiteRT KV for this overlay session,
        // then immediately re-warm the engine/sticky so the first tap is hot.
        serviceScope.launch {
            conversationManager.clearConversationFully()
            Log.d(TAG, "Short-term memory cleared for new pet session")
            llmEngineWarmup.startWarmup("overlay_start")
        }

        serviceScope.launch(Dispatchers.IO) {
            modelManager.installBundledModelsIfNeeded()
            val sttId = preferenceManager.getSttModelId()
            val ttsId = preferenceManager.getTtsModelId()
            val loadStt = sttId.isNotBlank() && modelManager.isModelDownloaded(sttId)
            val loadTts = ttsId.isNotBlank() && modelManager.isModelDownloaded(ttsId)
            withContext(Dispatchers.Main.immediate) {
                audioPipeline.schedulePetVoiceModelPrepare(
                    scope = serviceScope,
                    sttId = sttId,
                    ttsId = ttsId,
                    loadStt = loadStt,
                    loadTts = loadTts,
                )
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        petViewModel = createPetViewModel()
        attachOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_AI -> {
                // Mic FGS is only eligible after a user-visible activity (AiTriggerActivity).
                promoteToMicrophoneForeground()
                if (::petViewModel.isInitialized) {
                    petViewModel.onPetTapped()
                } else {
                    Log.w(TAG, "TRIGGER_AI before ViewModel ready")
                }
            }
            ACTION_USAGE_NUDGE -> {
                val appLabel = intent.getStringExtra(EXTRA_NUDGE_APP_LABEL)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "that app" }
                val minutes = intent.getIntExtra(EXTRA_NUDGE_MINUTES, 0).coerceAtLeast(1)
                val message = intent.getStringExtra(EXTRA_NUDGE_MESSAGE)
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        "You've been on $appLabel for about $minutes minutes — want a quick break with me?"
                    }
                serviceScope.launch {
                    presentUsageNudgeAttention()
                    // Fresh overlay may still be loading TTS — wait briefly then speak.
                    delay(800)
                    if (::petViewModel.isInitialized) {
                        petViewModel.speakProactive(message)
                    } else {
                        Log.w(TAG, "USAGE_NUDGE before ViewModel ready")
                    }
                }
            }
        }
        // Do not clear mid-session chat on every non-trigger start (e.g. Start Pet while
        // overlay is already running). Fresh session is opened in onCreate.
        return START_STICKY
    }

    private fun createPetViewModel(): PetViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PetViewModel(petBrain) as T
        }
        return ViewModelProvider(lifecycleOwner, factory)[PetViewModel::class.java]
    }

    /**
     * Overlay may be started from the background (usage nudge). Android 14+ rejects
     * microphone FGS from a background start, so boot without mic and promote later.
     */
    private fun startAsForeground() {
        startForegroundWithTypes(includeMicrophone = false)
    }

    private fun promoteToMicrophoneForeground() {
        if (microphoneForegroundActive) return
        runCatching {
            startForegroundWithTypes(includeMicrophone = true)
            microphoneForegroundActive = true
        }.onFailure { e ->
            Log.e(TAG, "Could not promote overlay FGS to microphone", e)
        }
    }

    private fun startForegroundWithTypes(includeMicrophone: Boolean) {
        val notification = buildNotification()
        val types = foregroundServiceTypes(includeMicrophone)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypes(includeMicrophone: Boolean): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (includeMicrophone) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            types
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (includeMicrophone) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            types
        } else {
            0
        }
    }

    private fun overlayFlags(focusable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (focusable) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

    private fun attachOverlay() {
        petLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        chromeLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        petOverlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                OverlayPetWindowContent(
                    animationController = animationController,
                    onTap = { launchAiTriggerActivity() },
                    onDrag = { dx, dy ->
                        petLayoutParams.x += dx.toInt()
                        petLayoutParams.y += dy.toInt()
                        windowManager.updateViewLayout(this, petLayoutParams)
                        post { syncChromePosition() }
                    },
                    onPetDragStart = { showCloseDragHint() },
                    onPetDragEnd = {
                        tryDismissIfReleasedOverCloseHint()
                        hideCloseDragHint()
                    },
                    onDismiss = {
                        uiFeedback.click()
                        stopSelf()
                    },
                )
            }
        }

        chromeOverlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                OverlayChromeWindowContent(
                    petViewModel = petViewModel,
                    uiFeedback = uiFeedback,
                    onRequestFocus = { focusable -> setChromeFocusable(focusable) },
                )
            }
        }

        windowManager.addView(petOverlayView, petLayoutParams)
        chromeOverlayView?.visibility = View.GONE
        windowManager.addView(chromeOverlayView, chromeLayoutParams)

        serviceScope.launch {
            var wasBusy = false
            petViewModel.overlayState.collect { state ->
                updateChromeWindowVisibility(state)
                val busy = state !is OverlayState.Idle
                if (wasBusy && !busy) {
                    // Conversation session ended (listen timeout, tap-while-listening, or dismiss).
                    AiTriggerActivity.finishIfShowing()
                    if (usageNudgeAttentionActive) {
                        hideUsageNudgeScrim()
                    }
                }
                wasBusy = busy
            }
        }

        petOverlayView?.post { syncChromePosition() }
        chromeOverlayView?.viewTreeObserver?.addOnGlobalLayoutListener(chromePositionListener)
    }

    private fun presentUsageNudgeAttention() {
        usageNudgeAttentionActive = true
        uiFeedback.usageNudgeAttention()
        showUsageNudgeScrim()
        centerPetOnScreen()
    }

    private fun showUsageNudgeScrim() {
        if (usageNudgeScrimView != null) return
        if (!::windowManager.isInitialized) return
        val scrim = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                OverlayUsageNudgeScrim(onDismiss = { hideUsageNudgeScrim() })
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            // Add under pet/chrome by inserting then re-stacking the pet layers on top.
            windowManager.addView(scrim, params)
            usageNudgeScrimView = scrim
            restackPetAboveScrim()
        } catch (e: Exception) {
            Log.w(TAG, "show usage nudge scrim", e)
            usageNudgeScrimView = null
        }
    }

    private fun restackPetAboveScrim() {
        val pet = petOverlayView
        val chrome = chromeOverlayView
        if (pet != null && ::petLayoutParams.isInitialized) {
            try {
                windowManager.removeView(pet)
                windowManager.addView(pet, petLayoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "restack pet above scrim", e)
            }
        }
        if (chrome != null && ::chromeLayoutParams.isInitialized) {
            try {
                windowManager.removeView(chrome)
                windowManager.addView(chrome, chromeLayoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "restack chrome above scrim", e)
            }
        }
    }

    private fun hideUsageNudgeScrim() {
        usageNudgeAttentionActive = false
        usageNudgeScrimView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "remove usage nudge scrim", e)
            }
            usageNudgeScrimView = null
        }
    }

    private fun centerPetOnScreen() {
        val pet = petOverlayView ?: return
        if (!::petLayoutParams.isInitialized) return
        pet.post {
            val w = if (pet.width > 0) pet.width else pet.measuredWidth
            val h = if (pet.height > 0) pet.height else pet.measuredHeight
            if (w <= 0 || h <= 0) {
                pet.post { centerPetOnScreen() }
                return@post
            }
            val sw = screenWidthPx()
            val sh = screenHeightPx()
            petLayoutParams.x = ((sw - w) / 2).coerceAtLeast(0)
            petLayoutParams.y = ((sh - h) / 2).coerceAtLeast(0)
            try {
                windowManager.updateViewLayout(pet, petLayoutParams)
                syncChromePosition()
            } catch (e: Exception) {
                Log.w(TAG, "center pet on screen", e)
            }
        }
    }

    private fun launchAiTriggerActivity() {
        // If the trampoline is already up (e.g. stuck after a failed turn), re-fire the
        // trigger instead of swallowing the tap.
        if (AiTriggerActivity.isShowing()) {
            startService(
                Intent(this, PetOverlayService::class.java).apply {
                    action = ACTION_TRIGGER_AI
                },
            )
            return
        }
        startActivity(
            Intent(this, AiTriggerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
            },
        )
    }

    private fun chromeWindowShowsUi(state: OverlayState): Boolean = when (state) {
        is OverlayState.Idle, is OverlayState.Minimized -> false
        else -> true
    }

    private fun updateChromeWindowVisibility(state: OverlayState) {
        val chrome = chromeOverlayView ?: return
        val show = chromeWindowShowsUi(state)
        val targetVis = if (show) View.VISIBLE else View.GONE
        if (chrome.visibility != targetVis) {
            chrome.visibility = targetVis
        }
        if (show) {
            chrome.post { syncChromePosition() }
        }
    }

    private fun syncChromePosition() {
        val pet = petOverlayView ?: return
        val chrome = chromeOverlayView ?: return
        if (chrome.visibility != View.VISIBLE) return
        if (pet.width <= 0 || pet.height <= 0) return

        pet.getLocationOnScreen(petScreenLoc)
        val gapPx = (CHROME_PET_GAP_DP * resources.displayMetrics.density).roundToInt()
        val newX = petScreenLoc[0] + (pet.width - chrome.width) / 2
        val newY = petScreenLoc[1] - gapPx - chrome.height

        chromeLayoutParams.x = newX
        chromeLayoutParams.y = newY
        try {
            windowManager.updateViewLayout(chrome, chromeLayoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "sync chrome overlay position", e)
        }
    }

    private fun closeStripHeightPx(): Int =
        (CLOSE_STRIP_HEIGHT_DP * resources.displayMetrics.density).roundToInt()

    private fun screenHeightPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels
        }

    private fun screenWidthPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels
        }

    private fun showCloseDragHint() {
        if (closeDragHintView != null) return
        val heightPx = closeStripHeightPx()
        val hint = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { OverlayCloseDragHint() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        windowManager.addView(hint, params)
        closeDragHintView = hint
    }

    private fun hideCloseDragHint() {
        closeDragHintView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "remove close drag hint", e)
            }
            closeDragHintView = null
        }
    }

    private fun tryDismissIfReleasedOverCloseHint() {
        val main = petOverlayView ?: return
        val loc = IntArray(2)
        main.getLocationOnScreen(loc)
        val contentBottom = loc[1] + main.height
        val sh = screenHeightPx()
        val zoneTop = sh - closeStripHeightPx()
        if (contentBottom >= zoneTop) {
            stopSelf()
        }
    }

    private fun setChromeFocusable(focusable: Boolean) {
        chromeLayoutParams.flags = overlayFlags(focusable)
        chromeOverlayView?.let { windowManager.updateViewLayout(it, chromeLayoutParams) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Analytics.capture(event = "pet stopped")
        serviceJob.cancel()
        AiTriggerActivity.finishIfShowing()
        if (::petViewModel.isInitialized) {
            petViewModel.cleanup()
        }
        hideCloseDragHint()
        hideUsageNudgeScrim()
        chromeOverlayView?.viewTreeObserver?.let { obs ->
            if (obs.isAlive) {
                obs.removeOnGlobalLayoutListener(chromePositionListener)
            }
        }
        chromeOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        chromeOverlayView = null
        petOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        petOverlayView = null
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, KawaiiPetApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_TRIGGER_AI = "com.kawaiipet.app.action.TRIGGER_AI"
        const val ACTION_USAGE_NUDGE = "com.kawaiipet.app.action.USAGE_NUDGE"
        const val EXTRA_NUDGE_MESSAGE = "nudge_message"
        const val EXTRA_NUDGE_APP_LABEL = "nudge_app_label"
        const val EXTRA_NUDGE_MINUTES = "nudge_minutes"

        private const val TAG = "PetOverlayService"
        private const val NOTIFICATION_ID = 1
        private const val CLOSE_STRIP_HEIGHT_DP = 140f
        private const val CHROME_PET_GAP_DP = 40f
    }
}
