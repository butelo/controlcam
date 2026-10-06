package com.controlcam.common

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

object Lan {
    private const val TAG = "ControlCamLan"

    fun localIpv4(): List<String> {
        val ips = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "local ip", e)
        }
        return ips.toList()
    }

    fun broadcastAddresses(): List<InetAddress> {
        val found = linkedSetOf<InetAddress>()
        try {
            found.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found.toList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val broadcast = ia.broadcast
                    if (broadcast is Inet4Address) found.add(broadcast)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast addresses", e)
        }
        return found.toList()
    }

    fun udp(port: Int): DatagramSocket {
        return DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 400
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        }
    }

    fun sendBroadcast(socket: DatagramSocket, payload: ByteArray, port: Int): Int {
        var sent = 0
        for (addr in broadcastAddresses()) {
            try {
                socket.send(DatagramPacket(payload, payload.size, addr, port))
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "send to $addr", e)
            }
        }
        return sent
    }

    fun parseIpv4Endpoint(raw: String, defaultPort: Int): Pair<String, Int>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val colon = text.lastIndexOf(':')
        val host: String
        val port: Int
        if (colon > 0 && text.indexOf(':') == colon) {
            host = text.substring(0, colon)
            port = text.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = text
            port = defaultPort
        }
        if (port !in 1..65535) return null
        val octets = host.split('.')
        if (octets.size != 4) return null
        val numbers = octets.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        val normalized = numbers.joinToString(".")
        if (normalized == "0.0.0.0" || normalized == "255.255.255.255") return null
        return normalized to port
    }
}
