package com.controlcam.camera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.controlcam.common.ScreenAnchor
import com.controlcam.common.StayAwake

class MainActivity : AppCompatActivity() {
    private val screen by lazy { ScreenAnchor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var service: CameraService? = null
    private var bound = false
    private var alive = false
    private var askedPermissions = false

    private lateinit var preview: PreviewView
    private lateinit var gridOverlay: View
    private lateinit var flashOverlay: View
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var flashToggle: TextView
    private lateinit var ip: TextView
    private lateinit var status: TextView

    private val startFallback = Runnable { if (alive && !bound) ensureService() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (readyToStart()) {
            if (alive) armSession()
        } else {
            showDenied()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!alive) return
            val svc = (binder as CameraService.LocalBinder).service
            service = svc
            svc.hudListener = { hud -> showHud(hud) }
            svc.onShutter = { flash() }
            val rotation = preview.display?.rotation ?: Surface.ROTATION_0
            svc.attachPreview(preview.surfaceProvider, rotation)
            flashToggle.text = svc.flashLabel()
            showHud(svc.hud)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StayAwake.applyWindow(this)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        gridOverlay = findViewById(R.id.gridOverlay)
        flashOverlay = findViewById(R.id.flashOverlay)
        topBar = findViewById(R.id.topBar)
        bottomPanel = findViewById(R.id.bottomPanel)
        flashToggle = findViewById(R.id.flashToggle)
        ip = findViewById(R.id.ip)
        status = findViewById(R.id.status)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        findViewById<View>(R.id.flashToggle).setOnClickListener {
            val svc = service ?: return@setOnClickListener
            flashToggle.text = svc.cycleFlash()
        }
        findViewById<View>(R.id.flipCamera).setOnClickListener {
            service?.flip() ?: toastStarting()
        }
        findViewById<View>(R.id.testShot).setOnClickListener {
            service?.testShot() ?: toastStarting()
        }
        findViewById<TextView>(R.id.gridToggle).setOnClickListener { toggleGrid() }
        applyInsets(topBar, top = true)
        applyInsets(bottomPanel, top = false)
    }

    override fun onStart() {
        super.onStart()
        alive = true
        StayAwake.immersive(this)
        screen.start()
        if (readyToStart()) {
            armSession()
        } else if (!askedPermissions) {
            askedPermissions = true
            permissionLauncher.launch(missingPermissions())
        } else {
            showDenied()
        }
    }

    override fun onResume() {
        super.onResume()
        StayAwake.immersive(this)
        pushRotation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) StayAwake.immersive(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        StayAwake.immersive(this)
        preview.post { pushRotation() }
    }

    override fun onStop() {
        alive = false
        screen.stop()
        handler.removeCallbacks(startFallback)
        service?.hudListener = null
        service?.onShutter = null
        if (!isChangingConfigurations) releaseService()
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun armSession() {
        if (bound) return
        if (StayAwake.askBatteryExemption(this)) {
            handler.postDelayed(startFallback, 800)
        } else {
            ensureService()
        }
    }

    private fun ensureService() {
        val intent = Intent(this, CameraService::class.java)
        startService(intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }

    private fun releaseService() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        stopService(Intent(this, CameraService::class.java))
    }

    private fun pushRotation() {
        val rotation = preview.display?.rotation ?: return
        service?.setRotation(rotation)
    }

    private fun showHud(hud: CameraService.Hud) {
        ip.text = hud.ip
        status.text = hud.detail
        status.setOnClickListener(null)
        status.isClickable = false
        service?.let { flashToggle.text = it.flashLabel() }
    }

    private fun toggleGrid() {
        val showing = gridOverlay.visibility != View.VISIBLE
        gridOverlay.visibility = if (showing) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.gridToggle).text = if (showing) "GRID ON" else "GRID OFF"
    }

    private fun flash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 1f
        flashOverlay.animate().alpha(0f).setStartDelay(30).setDuration(180).start()
    }

    private fun showDenied() {
        ip.text = "Permission needed"
        status.text = "Allow the camera, then come back.\nTap here to open settings."
        status.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun readyToStart(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun missingPermissions(): Array<String> {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return needed.toTypedArray()
    }

    private fun toastStarting() {
        Toast.makeText(this, "Still starting", Toast.LENGTH_SHORT).show()
    }

    private fun applyInsets(target: View, top: Boolean) {
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val side = 16.dp
            if (top) {
                view.setPadding(bars.left + side, bars.top + 8.dp, bars.right + side, 8.dp)
            } else {
                view.setPadding(bars.left + side, 12.dp, bars.right + side, bars.bottom + 16.dp)
            }
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
