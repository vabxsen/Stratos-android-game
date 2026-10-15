package com.vabxsen.stratos

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light tap on every drop, a firmer click on perfect drops, and an error buzz on game over. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    val available: Boolean = vibrator?.hasVibrator() == true
    var enabled = true

    fun tap() = vibrate(predefined(VibrationEffect.EFFECT_TICK, 14, 140))

    fun perfect() = vibrate(predefined(VibrationEffect.EFFECT_CLICK, 24, 220))

    fun over() {
        val timings = longArrayOf(0, 45, 70, 90)
        vibrate(
            if (vibrator?.hasAmplitudeControl() == true) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 170), -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
        )
    }

    private fun predefined(effect: Int, fallbackMs: Long, fallbackAmp: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(effect)
        } else {
            VibrationEffect.createOneShot(fallbackMs, fallbackAmp)
        }

    private fun vibrate(effect: VibrationEffect) {
        if (!enabled || !available) return
        try {
            vibrator?.vibrate(effect)
        } catch (_: RuntimeException) {
            // Some OEM builds throw when vibration is restricted; haptics are optional.
        }
    }
}
