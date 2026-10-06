package com.controlcam.common

/** Wire format: `CCAM1|CAM|<tcpPort>|<name>` and `CCAM1|SNAP|<id>`. */
object Protocol {
    const val UDP_PORT = 47821
    const val TCP_PORT = 47822

    private const val PREFIX = "CCAM1"
    private const val KIND_CAM = "CAM"
    private const val KIND_SNAP = "SNAP"

    fun beacon(name: String): String = "$PREFIX|$KIND_CAM|$TCP_PORT|$name"

    fun snap(id: String): ByteArray = "$PREFIX|$KIND_SNAP|$id".toByteArray(Charsets.UTF_8)

    fun snapLine(id: String): ByteArray = "$PREFIX|$KIND_SNAP|$id\n".toByteArray(Charsets.UTF_8)

    sealed class Message {
        data class Camera(val port: Int, val name: String) : Message()
        data class Snap(val id: String) : Message()
    }

    fun parse(raw: String): Message? {
        val parts = raw.trim().split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        return when (parts[1]) {
            KIND_CAM -> {
                val port = parts.getOrNull(2)?.toIntOrNull() ?: return null
                if (parts.size < 4 || port !in 1..65535) return null
                val name = parts.subList(3, parts.size).joinToString("|").trim()
                if (name.isEmpty()) return null
                Message.Camera(port, name)
            }
            KIND_SNAP -> {
                val id = parts.subList(2, parts.size).joinToString("|").trim()
                if (id.isEmpty()) return null
                Message.Snap(id)
            }
            else -> null
        }
    }
}
