package com.kawaiipet.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Makes a trampoline activity as invisible as possible while still counting as
 * a resumed Activity for AICore / mic foreground checks.
 */
object SoftForegroundWindow {

    fun apply(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)

        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        val lp = window.attributes
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0f
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Stay focusable so the process remains foreground for AICore.
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        window.attributes = lp
    }

    fun finishQuietly(activity: Activity) {
        if (activity.isFinishing) return
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
