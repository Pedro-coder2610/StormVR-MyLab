package com.stormvr.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Envia o estado do controle (KbtPacket, 42 bytes, ate 200 Hz) por UDP para:
 *  - o driver StormVR no PC (porta 5555 = direita, 5558 = esquerda) e/ou
 *  - o celular que esta no headset (sala VR, porta 5555).
 * E o MESMO formato do ESP32: o driver trata o celular como um controle 3DoF. Com o 6DoF ligado (ARCore) o pacote cresce para 54 bytes
 * (KbtPacketP: + frente/esquerda/cima em metros) e o driver passa a mover tambem a POSICAO da mao.
 */
class ControllerSender(
    private val ori: PhoneOrientation,
    private val st: CtlState,
    private val pcIp: String,
    private val hmdIp: String,
    private val leftHand: Boolean,
    private val hz: Int,
    private val axSrc: IntArray = intArrayOf(0, 1, 2),                    // saída (roll, pitch, yaw) <- entrada (0 roll, 1 pitch, 2 yaw)
    private val axInv: BooleanArray = booleanArrayOf(false, false, false)  // inversão de cada saída
) : Thread("StormVR-ControllerSender") {
    private val eul = FloatArray(3)

    @Volatile private var running = true
    @Volatile var sent = 0L
        private set
    @Volatile var rateHz = 0f
        private set
    @Volatile var lastError = ""
        private set

    fun shutdown() { running = false; interrupt() }

    override fun run() {
        val buf = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        var socket: DatagramSocket? = null
        val targets = ArrayList<DatagramPacket>()
        val isPc = ArrayList<Boolean>()                 // so o PC entende o pacote de 54 bytes (6DoF); a Sala VR do celular recebe o de 42
        var seq = 0
        val period = 1_000_000_000L / hz.coerceIn(20, 200)
        var next = System.nanoTime()
        var winStart = next
        var winCount = 0
        while (running) {
            try {
                var sk = socket
                if (sk == null) {
                    sk = DatagramSocket()
                    sk.trafficClass = 0x10
                    targets.clear()
                    isPc.clear()
                    for ((idx, ip) in arrayOf(pcIp, hmdIp).withIndex()) {
                        if (ip.isBlank()) continue
                        isPc.add(idx == 0)
                        targets.add(DatagramPacket(ByteArray(54), 54, InetAddress.getByName(ip.trim()), if (leftHand) ControllerUdp.PORT_LEFT else ControllerUdp.PORT))
                    }
                    socket = sk
                }
                val pos6 = st.hasPos
                val flags = (1 shl 2) or (if (leftHand) ControllerUdp.FLAG_HAND_LEFT else 0) or (if (pos6) FLAG_POS else 0)
                buf.clear()
                buf.putInt(ControllerUdp.MAGIC)
                buf.putShort((seq++ and 0xFFFF).toShort())
                buf.putShort(st.bits().toShort())
                buf.putShort((2048 + (st.x() * 2047f).toInt()).toShort())          // direita = ADC maior
                buf.putShort((2048 - (st.y() * 2047f).toInt()).toShort())          // cima = ADC menor (como o analogico do ESP32)
                eul[0] = ori.roll; eul[1] = ori.pitch; eul[2] = ori.yaw
                for (o in 0..2) { val v = eul[axSrc[o].coerceIn(0, 2)]; buf.putFloat(if (axInv[o]) -v else v) }      // eixos escolhidos / invertidos no app
                buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
                buf.putInt(SystemClock.elapsedRealtime().toInt())
                buf.put(0)
                buf.put(flags.toByte())
                if (pos6) { buf.putFloat(st.posF); buf.putFloat(st.posL); buf.putFloat(st.posU) }
                if (ori.hasData) {
                    for ((k, t) in targets.withIndex()) {
                        val l = if (pos6 && isPc[k]) 54 else 42
                        System.arraycopy(buf.array(), 0, t.data, 0, l); t.length = l; sk.send(t)
                    }
                    sent++; winCount++
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                try { sleep(500) } catch (_: InterruptedException) { break }
            }
            val now = System.nanoTime()
            if (now - winStart >= 1_000_000_000L) { rateHz = winCount * 1e9f / (now - winStart); winCount = 0; winStart = now }
            next += period
            val wait = next - System.nanoTime()
            if (wait > 0) { try { sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) } catch (_: InterruptedException) { break } }
            else if (wait < -period * 5) next = System.nanoTime()
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    private companion object { const val FLAG_POS = 1 shl 6 }
}
