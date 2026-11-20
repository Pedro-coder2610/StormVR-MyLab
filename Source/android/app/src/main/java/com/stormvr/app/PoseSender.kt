package com.stormvr.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Envia a pose da cabeca ao driver (KbtHeadPacket, 48 bytes, little endian) por UDP.
 * Roda numa thread propria: independe do video (Moonlight) e da interface.
 * Cada pacote leva o estado COMPLETO; o driver descarta pacotes velhos/fora de ordem.
 */
class PoseSender(
    private val tracker: HeadTracker,
    private val host: String,
    private val port: Int,
    private val hz: Int,
    private val deviceId: Int,
    private val extras: HeadExtras? = null,
    private val sendExtras: () -> Boolean = { true },
) : Thread("StormVR-PoseSender") {

    @Volatile private var running = true
    val recenterCount = AtomicInteger(0)

    @Volatile var sent = 0L
        private set
    @Volatile var errors = 0L
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var rateHz = 0f
        private set
    private val session = (System.nanoTime() and 0x7fffffff).toInt() or 1

    fun shutdown() { running = false; interrupt() }

    override fun run() {
        val q = FloatArray(4)
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var seq = 0
        var socket: DatagramSocket? = null
        var addr: InetAddress? = null
        val periodNs = 1_000_000_000L / hz.coerceIn(20, 500)
        var next = System.nanoTime()
        var winStart = System.nanoTime(); var winCount = 0
        var lastEvents = -1L
        var lastSentNs = 0L
        var lastRc = recenterCount.get()

        while (running) {
            try {
                if (socket == null) {
                    addr = InetAddress.getByName(host)          // resolve fora da thread principal
                    socket = DatagramSocket()
                    socket.trafficClass = 0x10                  // baixa latencia (IPTOS_LOWDELAY)
                }
                // só envia quando o sensor produziu uma orientação NOVA: repetir a mesma pose zerava a velocidade que o driver mede para prever o movimento
                val ev = tracker.sensorEvents
                // cabeça PARADA: o sensor para de reportar; sem este "batimento" o driver achava que o headset sumiu (tela preta). Reenvia a cada 25 ms.
                if (tracker.hasData && (ev != lastEvents || System.nanoTime() - lastSentNs > 25_000_000L)) {
                    lastEvents = ev
                    lastSentNs = System.nanoTime()
                    tracker.read(q)
                    val ex = extras
                    var fl = 0; var exLean = 0f; var exDrop = 0f
                    if (ex != null) {
                        val rcNow = recenterCount.get()
                        if (rcNow != lastRc) { lastRc = rcNow; ex.recenter() }
                        if (ex.active && sendExtras()) { ex.updateLean(q); fl = 2; exLean = if (ex.leanOn) ex.lean else 0f; exDrop = if (ex.baroOn) ex.drop else 0f }
                    }
                    buf.clear()
                    buf.putInt(MAGIC)
                    buf.putInt(deviceId)
                    buf.putInt(session)
                    buf.putInt(seq++)
                    buf.putLong(SystemClock.elapsedRealtimeNanos() / 1000L)
                    buf.putShort(fl.toShort())                  // flags: bit1 = extras do 3DoF (inclinacao lateral / barometro)
                    buf.put(0)                                  // trackingMode: 0 = 3DoF
                    buf.put(0)                                  // reservado
                    buf.putFloat(q[0]); buf.putFloat(q[1]); buf.putFloat(q[2]); buf.putFloat(q[3])
                    buf.putInt(recenterCount.get())
                    buf.putFloat(exLean); buf.putFloat(exDrop); buf.putFloat(0f)   // pos[]: 6DoF real OU, com flag 2, extras do 3DoF (lado, altura)
                    socket.send(DatagramPacket(buf.array(), PACKET_SIZE, addr, port))
                    sent++; winCount++
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                errors++
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                try { socket?.close() } catch (_: Exception) {}
                socket = null                                   // tenta de novo (Wi-Fi caiu, IP invalido...)
                try { sleep(500) } catch (_: InterruptedException) { break }
            }

            val now = System.nanoTime()
            if (now - winStart >= 1_000_000_000L) {
                rateHz = winCount * 1e9f / (now - winStart); winCount = 0; winStart = now
            }
            next += periodNs
            val wait = next - System.nanoTime()
            if (wait > 0) {
                try { sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) } catch (_: InterruptedException) { break }
            } else if (wait < -periodNs * 5) {
                next = System.nanoTime()                        // ficou muito atrasado: nao acumula rajada
            }
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        const val PACKET_SIZE = 60
        const val MAGIC = 0x31485453          // "STH1"
    }
}
