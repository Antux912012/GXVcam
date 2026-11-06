package org.gxvcam.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Controller providing Niagara Launcher-style tactile ratchet feedback across UI sliders and wheels.
 * Leverages the hardware Linear Resonant Actuator (LRA) vibrator on API 29+ with [VibrationEffect.EFFECT_TICK].
 */
class TactileFeedbackController(
    val context: Context,
    val vibrator: Vibrator?,
    val haptic: HapticFeedback,
) {
    /**
     * Niagara Launcher-style crisp tactile ratchet tick for sliders, scrubbers, and dials.
     */
    fun triggerTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /**
     * Heavier tactile snap/click for stop limits, mode locks, and leveler alignment.
     */
    fun triggerSnap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

@Composable
fun rememberTactileFeedback(): TactileFeedbackController {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    return remember(context, vibrator, haptic) {
        TactileFeedbackController(context, vibrator, haptic)
    }
}
