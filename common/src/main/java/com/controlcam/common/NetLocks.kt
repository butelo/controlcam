package com.controlcam.common

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

class NetLocks(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val multicast = wifi.createMulticastLock("controlcam-multicast").apply {
        setReferenceCounted(false)
    }

    @Suppress("DEPRECATION")
    private val wifiLock = wifi.createWifiLock(wifiMode(), "controlcam-wifi").apply {
        setReferenceCounted(false)
    }

    private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "controlcam:cpu").apply {
        setReferenceCounted(false)
    }

    @SuppressLint("WakelockTimeout")
    fun acquire() {
        runCatching { if (!multicast.isHeld) multicast.acquire() }
            .onFailure { Log.w(TAG, "multicast lock", it) }
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
            .onFailure { Log.w(TAG, "wifi lock", it) }
        runCatching { if (!wakeLock.isHeld) wakeLock.acquire() }
            .onFailure { Log.w(TAG, "wake lock", it) }
    }

    fun release() {
        runCatching { if (multicast.isHeld) multicast.release() }
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    private fun wifiMode(): Int {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }

    companion object {
        private const val TAG = "ControlCamLocks"
    }
}
