package com.controlcam.controller

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.controlcam.common.Lan
import com.controlcam.common.NetLocks
import com.controlcam.common.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ControlService : android.app.Service() {
    data class Peer(
        val ip: String,
        val port: Int,
        val name: String,
        val manual: Boolean,
        val lastSeen: Long,
    )

    inner class LocalBinder : Binder() {
        val service: ControlService get() = this@ControlService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val locks by lazy { NetLocks(this) }
    private val peers = ConcurrentHashMap<String, Peer>()
    private val loops = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val sendLock = Any()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val work = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "button-work").apply { isDaemon = true }
    }
    private val tcpPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "button-tcp").apply { isDaemon = true }
    }

    private var udpThread: Thread? = null
    private var pruneThread: Thread? = null
    private var udp: DatagramSocket? = null

    @Volatile private var running = false

    var listener: ((List<Peer>) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        locks.acquire()
        loadManual()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        if (loops.compareAndSet(false, true)) {
            Log.i(TAG, "button listening")
            udpThread = Thread({ listenLoop() }, "button-udp").apply {
                isDaemon = true
                start()
            }
            pruneThread = Thread({ pruneLoop() }, "button-prune").apply {
                isDaemon = true
                start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        running = false
        udpThread?.interrupt()
        pruneThread?.interrupt()
        try {
            udp?.close()
        } catch (_: Exception) {
        }
        work.shutdownNow()
        tcpPool.shutdownNow()
        locks.release()
        super.onDestroy()
    }

    fun snapshot(): List<Peer> =
        peers.values.sortedWith(compareBy({ it.name.lowercase() }, { it.ip }))

    fun addManual(raw: String): String? {
        val endpoint = Lan.parseIpv4Endpoint(raw, Protocol.TCP_PORT)
            ?: return "Enter an IPv4 address, like 192.168.1.20"
        val (ip, port) = endpoint
        val previous = peers[ip]
        val name = previous?.name?.takeUnless { it.startsWith("IP ") } ?: "IP $ip"
        peers[ip] = Peer(
            ip = ip,
            port = port,
            name = name,
            manual = true,
            lastSeen = previous?.lastSeen ?: 0L
        )
        saveManual()
        notifyPeers()
        return null
    }

    fun clearManual() {
        val now = System.currentTimeMillis()
        peers.entries.toList().forEach { (key, peer) ->
            if (!peer.manual) return@forEach
            if (peer.lastSeen > 0L && now - peer.lastSeen < 6_000) {
                peers[key] = peer.copy(manual = false)
            } else {
                peers.remove(key)
            }
        }
        saveManual()
        notifyPeers()
    }

    fun snap(onDone: (String) -> Unit) {
        if (!running) {
            main.post { onDone("App is stopping") }
            return
        }
        work.execute {
            val summary = try {
                performSnap()
            } catch (e: Exception) {
                "Failed: ${e.message ?: "could not reach cameras"}"
            }
            if (!running) return@execute
            main.post { onDone(summary) }
        }
    }

    private fun performSnap(): String {
        val id = "${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val payload = Protocol.snap(id)
        val socket = udp
        if (socket != null) {
            synchronized(sendLock) {
                repeat(3) { attempt ->
                    Lan.sendBroadcast(socket, payload, Protocol.UDP_PORT)
                    if (attempt < 2) Thread.sleep(25)
                }
            }
        }
        val targets = peers.values.toList()
        if (targets.isEmpty()) {
            val ips = Lan.localIpv4().ifEmpty { listOf("no IP") }.joinToString(", ")
            return "No cameras checked in.\nOpen ControlCam on the other phones, on this same Wi-Fi.\nThis phone: $ips"
        }
        val jobs = targets.map { peer -> tcpPool.submit<String> { snapOne(peer, id) } }
        val lines = jobs.map { job ->
            try {
                job.get(65, TimeUnit.SECONDS)
            } catch (e: Exception) {
                "FAIL ${e.javaClass.simpleName}"
            }
        }
        val ok = lines.count { it.startsWith("OK") }
        return buildString {
            append("$ok/${lines.size} saved")
            if (ok == 0) {
                append("\nIf a camera screen flashed, that photo still saved.")
            }
            lines.forEach {
                append('\n')
                append(it)
            }
        }
    }

    private fun snapOne(peer: Peer, id: String): String {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.ip, peer.port), 3_000)
                socket.soTimeout = 60_000
                val out = socket.getOutputStream()
                out.write(Protocol.snapLine(id))
                out.flush()
                val reply = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    ?: return "FAIL ${peer.name}: empty reply"
                if (reply.startsWith("OK")) "OK ${peer.name}" else "FAIL ${peer.name}: $reply"
            }
        } catch (e: Exception) {
            "FAIL ${peer.name}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun listenLoop() {
        var socket: DatagramSocket? = null
        while (running && socket == null) {
            socket = try {
                Lan.udp(Protocol.UDP_PORT)
            } catch (e: Exception) {
                Log.e(TAG, "udp bind", e)
                if (!sleepQuiet(1_500)) return
                null
            }
        }
        val bound = socket ?: return
        udp = bound
        val buf = ByteArray(1500)
        while (running) {
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
            val msg = Protocol.parse(text) as? Protocol.Message.Camera ?: continue
            val address = packet.address
            if (address !is Inet4Address || address.isLoopbackAddress) continue
            val ip = address.hostAddress ?: continue
            val previous = peers[ip]
            peers[ip] = Peer(
                ip = ip,
                port = msg.port,
                name = msg.name,
                manual = previous?.manual == true,
                lastSeen = System.currentTimeMillis()
            )
            if (previous == null || previous.name != msg.name || previous.port != msg.port) {
                notifyPeers()
            }
        }
    }

    private fun pruneLoop() {
        while (running) {
            if (!sleepQuiet(1_000)) break
            val now = System.currentTimeMillis()
            var changed = false
            val iterator = peers.entries.iterator()
            while (iterator.hasNext()) {
                val peer = iterator.next().value
                if (!peer.manual && now - peer.lastSeen > 6_000) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) notifyPeers()
            locks.acquire()
        }
    }

    private fun notifyPeers() {
        val copy = snapshot()
        main.post { listener?.invoke(copy) }
    }

    private fun loadManual() {
        val saved = prefs.getStringSet(PREFS_KEY, emptySet())?.toList().orEmpty()
        for (entry in saved) {
            val endpoint = Lan.parseIpv4Endpoint(entry, Protocol.TCP_PORT) ?: continue
            val (ip, port) = endpoint
            peers[ip] = Peer(ip, port, "IP $ip", manual = true, lastSeen = 0L)
        }
    }

    private fun saveManual() {
        val set = peers.values.filter { it.manual }.map { "${it.ip}:${it.port}" }.toSet()
        prefs.edit().putStringSet(PREFS_KEY, set).apply()
    }

    private fun sleepQuiet(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }
    }

    companion object {
        private const val TAG = "ControlButton"
        private const val PREFS = "cameras"
        private const val PREFS_KEY = "manual"
    }
}
