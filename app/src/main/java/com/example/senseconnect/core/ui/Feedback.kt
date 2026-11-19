package com.example.senseconnect.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.example.senseconnect.SenseConnectApp

/** Haptic and motion helpers that honour the user's Haptic Feedback and Reduce Motion settings. */
object Feedback {

    private fun settings(context: Context) =
        (context.applicationContext as SenseConnectApp).container.settings.current

    fun tap(view: View) = perform(view, HapticFeedbackConstants.VIRTUAL_KEY)

    fun confirm(view: View) = perform(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun reject(view: View) = perform(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    fun longPress(view: View) = perform(view, HapticFeedbackConstants.LONG_PRESS)

    fun tick(view: View) = perform(view, HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(view: View, constant: Int) {
        if (settings(view.context).hapticFeedback) view.performHapticFeedback(constant)
    }

    /** True when decorative animation should run (in-app Reduce Motion off and system animations on). */
    fun motionEnabled(context: Context): Boolean {
        if (settings(context).reduceMotion) return false
        val systemScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        return systemScale > 0f && ValueAnimator.areAnimatorsEnabled()
    }

    /** Fades a view in (or simply shows it when motion is reduced). */
    fun reveal(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.visibility = View.VISIBLE
        if (motionEnabled(view.context)) {
            view.alpha = 0f
            view.translationY = 12f
            view.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            view.alpha = 1f
        }
    }
}
