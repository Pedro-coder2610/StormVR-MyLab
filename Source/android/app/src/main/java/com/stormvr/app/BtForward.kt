package com.stormvr.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reenvia ao PC (driver StormVR) os controles que estão conectados a ESTE celular por Bluetooth (ESP32), como se o
 * ESP32 estivesse no Wi-Fi: KbtPacket de 42 bytes, direita -> porta 5555, esquerda -> porta 5558. Liga/desliga a qualquer
 * momento ([enabled]); desligado não envia nada e o controle continua valendo só na Sala 3D do celular.
 */
object BtForward {
    @Volatile var enabled = false
    @Volatile var host = ""
    @Volatile var sent = 0L
        private set
    @Volatile var lastError = ""
        private set

    private val buf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
    private var socket: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var addrHost = ""
    private val seq = IntArray(2)

    /** Chamado pelo ControllerHub a cada pacote de uma fonte Bluetooth. [left]: mão que o hub atribuiu. */
    @Synchronized
    fun onBle(left: Boolean, buttons: Int, jx: Float, jy: Float, roll: Float, pitch: Float, yaw: Float, quat: FloatArray? = null, srcFlags: Int = 0, tsMs: Int = -1) {
        if (!enabled || host.isBlank()) return
        try {
            if (socket == null) { socket = DatagramSocket().also { it.trafficClass = 0x10 } }
            if (addr == null || addrHost != host) { addr = InetAddress.getByName(host.trim()); addrHost = host }
            val i = if (left) 1 else 0
            if (quat != null) {                              // KbtPacketQ (46 bytes): orientação em quaternion, sem Euler
                val qbuf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                qbuf.putInt(ControllerUdp.MAGIC_Q)
                qbuf.putShort((seq[i]++ and 0xFFFF).toShort())
                qbuf.putShort((buttons and 0xFFFF).toShort())
                qbuf.putShort((2048 + (jx * 2047f).toInt()).toShort())
                qbuf.putShort((2048 - (jy * 2047f).toInt()).toShort())
                qbuf.putFloat(quat[0]); qbuf.putFloat(quat[1]); qbuf.putFloat(quat[2]); qbuf.putFloat(quat[3])
                qbuf.putFloat(0f); qbuf.putFloat(0f); qbuf.putFloat(0f)
                // carimbo REAL da amostra (millis do ESP32, vindo no pacote Bluetooth de 18 bytes): o driver interpola com o ritmo certo mesmo que o
                // Bluetooth entregue em rajadas. Firmware antigo (16 bytes) = hora de chegada neste celular.
                qbuf.putInt(if (tsMs >= 0) tsMs else SystemClock.elapsedRealtime().toInt())
                qbuf.put(0)
                val fl = (srcFlags and ControllerUdp.FLAG_GAMEPAD) or (1 shl 2) or (if (left) ControllerUdp.FLAG_HAND_LEFT else 0)
                qbuf.put(fl.toByte())
                socket!!.send(DatagramPacket(qbuf.array(), 46, addr, if (left) ControllerUdp.PORT_LEFT else ControllerUdp.PORT))
                sent++
                return
            }
            buf.clear()
            buf.putInt(ControllerUdp.MAGIC)
            buf.putShort((seq[i]++ and 0xFFFF).toShort())
            buf.putShort((buttons and 0xFFFF).toShort())
            buf.putShort((2048 + (jx * 2047f).toInt()).toShort())
            buf.putShort((2048 - (jy * 2047f).toInt()).toShort())
            buf.putFloat(roll); buf.putFloat(pitch); buf.putFloat(yaw)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
            buf.putInt(SystemClock.elapsedRealtime().toInt())
            buf.put(0)
            buf.put(((1 shl 2) or (if (left) ControllerUdp.FLAG_HAND_LEFT else 0)).toByte())   // KBT_FLAG_CALIBRATED + mão
            socket!!.send(DatagramPacket(buf.array(), 42, addr, if (left) ControllerUdp.PORT_LEFT else ControllerUdp.PORT))
            sent++
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            try { socket?.close() } catch (_: Exception) {}
            socket = null; addr = null
        }
    }
}
