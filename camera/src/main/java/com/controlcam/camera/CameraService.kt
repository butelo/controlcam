package com.controlcam.camera

import android.content.ContentValues
import android.content.Intent
import android.media.MediaActionSound
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.controlcam.common.Lan
import com.controlcam.common.NetLocks
import com.controlcam.common.Protocol
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection

class CameraService : LifecycleService() {
    data class Hud(val ip: String, val detail: String)

    inner class LocalBinder : Binder() {
        val service: CameraService get() = this@CameraService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> main.post(command) }
    private val locks by lazy { NetLocks(this) }
    private val loops = AtomicBoolean(false)
    private val shots = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val statusLock = Any()
    private val captureQueue = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "controlcam-capture").apply { isDaemon = true }
    }
    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "controlcam-client").apply { isDaemon = true }
    }

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault())

    private lateinit var deviceName: String
    private lateinit var shutterSound: MediaActionSound
    private var udpThread: Thread? = null
    private var tcpThread: Thread? = null
    private var udp: DatagramSocket? = null
    private var tcp: ServerSocket? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var surfaceProvider: Preview.SurfaceProvider? = null
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var flashMode = ImageCapture.FLASH_MODE_AUTO
    @Volatile private var rotation = Surface.ROTATION_0
    @Volatile private var cameraReady = false
    @Volatile private var lastSaved = ""
    @Volatile private var cameraNote = ""
    @Volatile private var udpNote = ""
    @Volatile private var tcpNote = ""
    @Volatile private var running = false

    var hud = Hud("Starting…", "Opening the camera")
        private set
    var hudListener: ((Hud) -> Unit)? = null
    var onShutter: (() -> Unit)? = null

    private var lastHud: Hud? = null
    private var bindGeneration = 0

    private val reopen = Runnable {
        if (running && !cameraReady) openCamera()
    }

    override fun onCreate() {
        super.onCreate()
        deviceName = buildDeviceName()
        shutterSound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
        locks.acquire()
        refreshStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        running = true
        if (loops.compareAndSet(false, true)) {
            Log.i(TAG, "listening as $deviceName")
            udpThread = Thread({ udpLoop() }, "controlcam-udp").apply {
                isDaemon = true
                start()
            }
            tcpThread = Thread({ tcpLoop() }, "controlcam-tcp").apply {
                isDaemon = true
                start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        udpThread?.interrupt()
        tcpThread?.interrupt()
        try {
            udp?.close()
        } catch (_: Exception) {
        }
        try {
            tcp?.close()
        } catch (_: Exception) {
        }
        clients.shutdownNow()
        captureQueue.shutdownNow()
        locks.release()
        if (::shutterSound.isInitialized) shutterSound.release()
        super.onDestroy()
    }

    fun attachPreview(provider: Preview.SurfaceProvider, displayRotation: Int) {
        surfaceProvider = provider
        rotation = displayRotation
        main.post { openCamera() }
    }

    fun setRotation(value: Int) {
        rotation = value
        main.post { imageCapture?.targetRotation = value }
    }

    fun flashLabel(): String = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> "FLASH ON"
        ImageCapture.FLASH_MODE_OFF -> "FLASH OFF"
        else -> "FLASH AUTO"
    }

    fun cycleFlash(): String {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        refreshStatus()
        return flashLabel()
    }

    fun flip() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        cameraReady = false
        main.post { openCamera() }
    }

    fun testShot() {
        requestCapture("local-${System.currentTimeMillis()}")
    }

    private fun openCamera() {
        if (!running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraNote = "Camera permission is missing"
            cameraReady = false
            refreshStatus()
            return
        }
        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (generation != bindGeneration || !running) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindNow(provider)
            } catch (e: Exception) {
                Log.e(TAG, "camera provider", e)
                cameraNote = "Camera failed: ${e.message ?: "unavailable"}"
                cameraReady = false
                refreshStatus()
                scheduleReopen()
            }
        }, mainExecutor)
    }

    private fun bindNow(provider: ProcessCameraProvider) {
        var lastError: Exception? = null
        for (quality in listOf(true, false)) {
            try {
                bindOnce(provider, quality)
                cameraReady = true
                cameraNote = ""
                main.removeCallbacks(reopen)
                refreshStatus()
                return
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "bind quality=$quality", e)
            }
        }
        imageCapture = null
        cameraReady = false
        cameraNote = "Camera failed: ${lastError?.message ?: "unavailable"}"
        refreshStatus()
        scheduleReopen()
    }

    private fun bindOnce(provider: ProcessCameraProvider, quality: Boolean) {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(
                if (quality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setJpegQuality(95)
            .setTargetRotation(rotation)
            .setFlashMode(flashMode)
            .build()
        val useCases = mutableListOf<UseCase>(capture)
        val previewSurface = surfaceProvider
        if (previewSurface != null) {
            val preview = Preview.Builder().setTargetRotation(rotation).build()
            preview.setSurfaceProvider(previewSurface)
            useCases.add(0, preview)
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
        imageCapture = capture
    }

    private fun scheduleReopen() {
        main.removeCallbacks(reopen)
        main.postDelayed(reopen, 3_000)
    }

    private fun requestCapture(id: String): CompletableFuture<String> {
        val created = CompletableFuture<String>()
        val existing = pending.putIfAbsent(id, created)
        if (existing != null) return existing
        main.post { onShutter?.invoke() }
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        try {
            captureQueue.execute {
                val result = try {
                    doCapture()
                } catch (e: Exception) {
                    "ERR ${e.message ?: "capture failed"}"
                }
                created.complete(result)
                main.postDelayed({ pending.remove(id) }, 8_000)
            }
        } catch (_: RejectedExecutionException) {
            created.complete("ERR stopping")
        }
        return created
    }

    private fun doCapture(): String {
        val capture = awaitCapture() ?: return "ERR camera not ready"
        val name = fileName()
        val options = outputOptions(name) ?: return "ERR could not open storage"
        val saved = CompletableFuture<String>()
        try {
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (Build.VERSION.SDK_INT < 29) {
                            MediaScannerConnection.scanFile(
                                this@CameraService,
                                arrayOf(legacyFile(name).absolutePath),
                                arrayOf("image/jpeg"),
                                null
                            )
                        }
                        shots.incrementAndGet()
                        lastSaved = "Last photo: $name"
                        cameraNote = ""
                        refreshStatus()
                        saved.complete("OK $name")
                    }

                    override fun onError(exc: ImageCaptureException) {
                        lastSaved = "Capture failed: ${exc.message ?: "error ${exc.imageCaptureError}"}"
                        refreshStatus()
                        if (exc.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                            cameraReady = false
                            scheduleReopen()
                        }
                        saved.complete("ERR ${exc.message ?: "capture failed"}")
                    }
                }
            )
        } catch (e: Exception) {
            return "ERR ${e.message ?: "capture failed"}"
        }
        return try {
            saved.get(45, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "ERR timed out"
        }
    }

    private fun awaitCapture(): ImageCapture? {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            imageCapture?.let { return it }
            if (!running) return null
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return imageCapture
    }

    private fun outputOptions(name: String): ImageCapture.OutputFileOptions? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ControlCam")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            return ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraNote = "Storage permission is missing"
            refreshStatus()
            return null
        }
        val file = legacyFile(name)
        val dir = file.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) return null
        return ImageCapture.OutputFileOptions.Builder(file).build()
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "ControlCam"
        )
        return File(dir, name)
    }

    private fun fileName(): String {
        val safe = deviceName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "ControlCam_${safe}_${stamp.format(Instant.now())}.jpg"
    }

    private fun udpLoop() {
        var socket: DatagramSocket? = null
        while (running && socket == null) {
            socket = try {
                Lan.udp(Protocol.UDP_PORT)
            } catch (e: Exception) {
                udpNote = "Port ${Protocol.UDP_PORT} is busy on this phone"
                refreshStatus()
                Log.e(TAG, "udp bind", e)
                if (!sleepQuiet(1_500)) return
                null
            }
        }
        val bound = socket ?: return
        udp = bound
        udpNote = ""
        refreshStatus()
        val buf = ByteArray(1500)
        var nextBeacon = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (now >= nextBeacon) {
                val sent = Lan.sendBroadcast(
                    bound,
                    Protocol.beacon(deviceName).toByteArray(Charsets.UTF_8),
                    Protocol.UDP_PORT
                )
                udpNote = if (sent == 0) "No local network to announce on" else ""
                refreshStatus()
                locks.acquire()
                nextBeacon = now + 1_000
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                bound.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "udp", e)
                continue
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val snap = Protocol.parse(text) as? Protocol.Message.Snap ?: continue
            requestCapture(snap.id)
        }
    }

    private fun tcpLoop() {
        var server: ServerSocket? = null
        while (running && server == null) {
            server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), Protocol.TCP_PORT), 50)
                }
            } catch (e: Exception) {
                tcpNote = "Port ${Protocol.TCP_PORT} is busy on this phone"
                refreshStatus()
                Log.e(TAG, "tcp bind", e)
                if (!sleepQuiet(1_500)) return
                null
            }
        }
        val bound = server ?: return
        tcp = bound
        tcpNote = ""
        refreshStatus()
        while (running) {
            val client = try {
                bound.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "accept", e)
                continue
            }
            try {
                clients.execute { handleClient(client) }
            } catch (_: RejectedExecutionException) {
                client.close()
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 8_000
                val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                socket.soTimeout = 60_000
                val reply = try {
                    when (val msg = line?.let(Protocol::parse)) {
                        is Protocol.Message.Snap -> requestCapture(msg.id).get(50, TimeUnit.SECONDS)
                        else -> "ERR unknown"
                    }
                } catch (e: Exception) {
                    "ERR ${e.message ?: "failed"}"
                }
                socket.soTimeout = 5_000
                socket.getOutputStream().write((reply + "\n").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            } catch (e: Exception) {
                Log.w(TAG, "client", e)
            }
        }
    }

    private fun sleepQuiet(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun buildDeviceName(): String {
        val model = Build.MODEL.ifBlank { "camera" }
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val suffix = id.takeLast(4).ifBlank { "0000" }
        return "$model-$suffix".replace('|', ' ').replace('\n', ' ').take(48)
    }

    private fun buildHud(): Hud {
        val ips = Lan.localIpv4()
        val ip = if (ips.isEmpty()) "No Wi-Fi IP yet" else ips.joinToString("\n")
        val lens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Front camera" else "Back camera"
        val lines = mutableListOf(
            deviceName,
            "$lens · ${flashLabel()}",
            "Leave this open. Screen stays on.",
            "Photos: DCIM/ControlCam",
            "${shots.get()} saved this session"
        )
        if (lastSaved.isNotEmpty()) lines.add(lastSaved)
        if (cameraNote.isNotEmpty()) lines.add(cameraNote)
        if (udpNote.isNotEmpty()) lines.add(udpNote)
        if (tcpNote.isNotEmpty()) lines.add(tcpNote)
        return Hud(ip, lines.joinToString("\n"))
    }

    private fun refreshStatus() {
        val built = buildHud()
        val listener = synchronized(statusLock) {
            hud = built
            if (built == lastHud) return
            lastHud = built
            hudListener
        }
        if (listener == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) listener.invoke(built)
        else main.post { hudListener?.invoke(built) }
    }

    companion object {
        private const val TAG = "ControlCam"
    }
}
