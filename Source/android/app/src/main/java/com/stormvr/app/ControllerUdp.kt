package com.stormvr.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escuta os pacotes UDP de controles (KbtPacket, 42 bytes, porta 5555): o ESP32 por Wi-Fi (broadcast quando PC_IP esta
 * vazio) ou outro celular no modo controle. A mao vem da flag KBT_FLAG_HAND_LEFT (padrao: direita); varias fontes (IPs)
 * ao mesmo tempo sao distribuidas entre as duas maos pelo ControllerHub.
 */
class ControllerUdp : Thread("StormVR-ControllerUdp") {
    @Volatile private var running = true
    private val sockets = ArrayList<DatagramSocket>()
    private var leftThread: Thread? = null

    fun shutdown() {
        running = false
        synchronized(sockets) { for (s in sockets) try { s.close() } catch (_: Exception) {} }
    }

    override fun run() {
        // Porta do controle ESQUERDO separada da do direito: cada mao tem a sua; o mesmo IP pode ser os dois controles.
        leftThread = Thread({ listen(PORT_LEFT, true) }, "StormVR-ControllerUdp-L").also { it.start() }
        listen(PORT, false)
    }

    /** forceLeft: tudo que chega na porta esquerda e a mao esquerda. Na porta principal a mao vem da flag do pacote (legado). */
    private fun listen(port: Int, forceLeft: Boolean) {
        var s: DatagramSocket? = null
        try {
            s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.soTimeout = 250
            synchronized(sockets) { sockets.add(s) }
            val buf = ByteArray(128)
            val pkt = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    pkt.length = buf.size
                    s.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (pkt.length == 46) {                       // firmware novo: KbtPacketQ (orientação em quaternion)
                    val qb = ByteBuffer.wrap(buf, 0, 46).order(ByteOrder.LITTLE_ENDIAN)
                    if (qb.getInt(0) != MAGIC_Q) continue
                    val qButtons = qb.getShort(6).toInt() and 0xFFFF
                    val qjx = ((qb.getShort(8).toInt() and 0xFFFF) - 2048) / 2048f
                    val qjy = (2048 - (qb.getShort(10).toInt() and 0xFFFF)) / 2048f
                    val qFlags = buf[45].toInt() and 0xFF
                    val qLeft = forceLeft || (qFlags and FLAG_HAND_LEFT) != 0
                    val qIp = pkt.address?.hostAddress ?: continue
                    val eul = quatToEuler(qb.getFloat(12), qb.getFloat(16), qb.getFloat(20), qb.getFloat(24))
                    ControllerHub.submit("udp:$qIp:$port", qLeft, qButtons, qjx, qjy, eul[0], eul[1], eul[2], "ESP32 Wi-Fi")
                    continue
                }
                if (pkt.length != 42) continue
                val bb = ByteBuffer.wrap(buf, 0, 42).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.getInt(0) != MAGIC) continue
                val buttons = bb.getShort(6).toInt() and 0xFFFF
                val jx = ((bb.getShort(8).toInt() and 0xFFFF) - 2048) / 2048f
                val jy = (2048 - (bb.getShort(10).toInt() and 0xFFFF)) / 2048f
                val flags = buf[41].toInt() and 0xFF
                val left = forceLeft || (flags and FLAG_HAND_LEFT) != 0
                val ip = pkt.address?.hostAddress ?: continue
                val esp = buf[40].toInt() != 0 || bb.getFloat(24) != 0f || bb.getFloat(28) != 0f || bb.getFloat(32) != 0f   // ESP32 manda RSSI/giroscopio; o celular manda zeros
                // a chave inclui a porta: o mesmo IP pode ocupar as duas maos (um controle por porta)
                ControllerHub.submit("udp:$ip:$port", left, buttons, jx, jy, bb.getFloat(12), bb.getFloat(16), bb.getFloat(20), if (esp) "ESP32 Wi-Fi" else "celular Wi-Fi")
            }
        } catch (_: Exception) {
            // porta ocupada ou sem rede: a sala continua com olhar/toque e Bluetooth
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val PORT = 5555                 // controle DIREITO (e legado: mao pela flag)
        const val PORT_LEFT = 5558            // controle ESQUERDO (porta propria)
        const val PORT_HAND = 5557            // pacotes de maos rastreadas (KbtHandPacket) para o driver
        const val MAGIC = 0x3154424B          // "KBT1"
        const val MAGIC_Q = 0x3151424B        // "KBQ1" (KbtPacketQ, 46 bytes)
        const val FLAG_GAMEPAD = 1 shl 4
        /** Quaternion (w,x,y,z) -> roll, pitch, yaw em graus (mesma convenção do firmware: Z-Y-X). */
        fun quatToEuler(w: Float, x: Float, y: Float, z: Float): FloatArray {
            val d = 180.0 / Math.PI
            val roll = Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y)) * d
            val sp = (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
            val pitch = Math.asin(sp) * d
            val yaw = Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z)) * d
            return floatArrayOf(roll.toFloat(), pitch.toFloat(), yaw.toFloat())
        }
        const val FLAG_HAND_LEFT = 1 shl 3
        const val BTN_GRIP = 1 shl 0
        const val BTN_TRIGGER = 1 shl 1
        const val BTN_A = 1 shl 2
        const val BTN_B = 1 shl 3
        const val BTN_START = 1 shl 6
        const val BTN_X = 1 shl 4
        const val BTN_Y = 1 shl 5
        const val BTN_L3 = 1 shl 7
        const val BTN_R2 = 1 shl 8            // botao extra do ESP32 (GPIO4 / D4)
    }
}
