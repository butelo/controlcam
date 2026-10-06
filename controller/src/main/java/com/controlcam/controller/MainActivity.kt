package com.controlcam.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.controlcam.common.Lan
import com.controlcam.common.ScreenAnchor
import com.controlcam.common.StayAwake
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val screen by lazy { ScreenAnchor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val idleColor = 0xFFFFE14A.toInt()
    private val sendingColor = 0xFF1FA34A.toInt()

    private var service: ControlService? = null
    private var bound = false
    private var alive = false
    private var lastFire = 0L
    private var inFlight = 0
    private var resultText = ""
    private var peers: List<ControlService.Peer> = emptyList()
    private var tone: ToneGenerator? = null

    private lateinit var shutter: TextView
    private lateinit var topBar: View
    private lateinit var status: TextView

    private val startFallback = Runnable { if (alive && !bound) ensureService() }
    private val uiTick = object : Runnable {
        override fun run() {
            if (!alive) return
            service?.let { peers = it.snapshot() }
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!alive) return
            val svc = (binder as ControlService.LocalBinder).service
            service = svc
            svc.listener = { list ->
                peers = list
                render()
            }
            peers = svc.snapshot()
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StayAwake.applyWindow(this)
        setContentView(R.layout.activity_main)
        shutter = findViewById(R.id.shutter)
        topBar = findViewById(R.id.topBar)
        status = findViewById(R.id.status)
        findViewById<View>(R.id.overlay).isClickable = false
        findViewById<View>(R.id.addIp).setOnClickListener { showAddDialog() }
        // The yellow label fills the screen. The status strip lets taps through; ADD IP does not.
        shutter.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) fire()
            true
        }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left + 12.dp, bars.top + 8.dp, bars.right + 12.dp, 8.dp)
            insets
        }
        ViewCompat.requestApplyInsets(topBar)
    }

    override fun onStart() {
        super.onStart()
        alive = true
        StayAwake.immersive(this)
        screen.start()
        armSession()
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    override fun onResume() {
        super.onResume()
        StayAwake.immersive(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) StayAwake.immersive(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        StayAwake.immersive(this)
    }

    override fun onStop() {
        alive = false
        screen.stop()
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(startFallback)
        service?.listener = null
        if (!isChangingConfigurations) releaseService()
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        tone?.release()
        tone = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null &&
            event.repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            fire()
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        val intent = Intent(this, ControlService::class.java)
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
        stopService(Intent(this, ControlService::class.java))
    }

    private fun fire() {
        if (!alive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFire < 300) return
        lastFire = now
        val svc = service
        if (svc == null) {
            resultText = "Still starting. Tap again in a second."
            render()
            return
        }
        inFlight += 1
        shutter.setBackgroundColor(sendingColor)
        shutter.text = "SENDING"
        buzz()
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }
        svc.snap { summary ->
            if (!alive) return@snap
            resultText = summary
            inFlight = (inFlight - 1).coerceAtLeast(0)
            if (inFlight == 0) {
                shutter.setBackgroundColor(idleColor)
                shutter.text = "SNAP"
            }
            val bad = !allSaved(summary)
            val toneType = if (bad) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK
            runCatching { tone?.startTone(toneType, 140) }
            render()
        }
    }

    private fun render() {
        val local = Lan.localIpv4().ifEmpty { listOf("no IP yet") }.joinToString(", ")
        val lines = mutableListOf<String>()
        if (resultText.isNotBlank()) lines.add(resultText.trim())
        if (peers.isEmpty()) {
            lines.add("No cameras yet")
            lines.add("Open ControlCam on the other phones")
        } else {
            val noun = if (peers.size == 1) "camera" else "cameras"
            lines.add("${peers.size} $noun online")
            lines.add(peers.joinToString(", ") { it.name })
        }
        lines.add("This phone: $local")
        status.text = lines.joinToString("\n")
    }

    private fun showAddDialog() {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, "Still starting", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 22f
            setPadding(24.dp, 16.dp, 24.dp, 16.dp)
        }
        val container = FrameLayout(this).apply {
            val pad = 20.dp
            setPadding(pad, pad, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add a camera")
            .setMessage("Type the IP shown on the ControlCam phone.")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val error = svc.addManual(input.text.toString())
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } else {
                    peers = svc.snapshot()
                    render()
                }
            }
            .setNeutralButton("Clear typed IPs") { _, _ ->
                svc.clearManual()
                peers = svc.snapshot()
                render()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun allSaved(summary: String): Boolean {
        val first = summary.lineSequence().firstOrNull().orEmpty()
        val match = SAVED.matcher(first)
        if (!match.find()) return false
        return match.group(1) == match.group(2) && match.group(1) != "0"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private val SAVED: Pattern = Pattern.compile("(\\d+)/(\\d+) saved")
    }
}
