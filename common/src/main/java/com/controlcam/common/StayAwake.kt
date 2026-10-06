package com.controlcam.common

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object StayAwake {
    fun applyWindow(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
    }

    fun immersive(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** @return true when the system settings screen was opened. */
    fun askBatteryExemption(activity: Activity): Boolean {
        val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(activity.packageName)) return false
        val prefs = activity.getSharedPreferences("stayawake", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return false
        prefs.edit().putBoolean("asked", true).apply()
        return try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Keeps the screen lit for as long as the activity is in front.
 * The lock is released when the activity stops, then grabbed again every minute
 * in case a phone manufacturer drops it.
 */
class ScreenAnchor(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    @Suppress("DEPRECATION")
    private val lock = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "controlcam:screen"
        ).apply { setReferenceCounted(false) }

    private val tick = Runnable { if (active) start() }

    @SuppressLint("WakelockTimeout")
    fun start() {
        active = true
        StayAwake.applyWindow(activity)
        if (!lock.isHeld) lock.acquire()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 60_000)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(tick)
        if (lock.isHeld) lock.release()
    }
}
