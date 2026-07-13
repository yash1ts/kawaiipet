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
import com.kawaiipet.app.pet.PetAnimationController
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

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

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
        if (intent?.action == ACTION_TRIGGER_AI) {
            if (::petViewModel.isInitialized) {
                petViewModel.onPetTapped()
            } else {
                Log.w(TAG, "TRIGGER_AI before ViewModel ready")
            }
        }
        return START_STICKY
    }

    private fun createPetViewModel(): PetViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PetViewModel(
                    applicationContext,
                    conversationManager,
                    audioPipeline,
                    preferenceManager,
                    modelManager,
                    animationController,
                    uiFeedback,
                ) as T
        }
        return ViewModelProvider(lifecycleOwner, factory)[PetViewModel::class.java]
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
                    AiTriggerActivity.finishIfShowing()
                }
                wasBusy = busy
            }
        }

        petOverlayView?.post { syncChromePosition() }
        chromeOverlayView?.viewTreeObserver?.addOnGlobalLayoutListener(chromePositionListener)
    }

    private fun launchAiTriggerActivity() {
        if (AiTriggerActivity.isShowing()) return
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

        private const val TAG = "PetOverlayService"
        private const val NOTIFICATION_ID = 1
        private const val CLOSE_STRIP_HEIGHT_DP = 140f
        private const val CHROME_PET_GAP_DP = 40f
    }
}
