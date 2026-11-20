package com.stormvr.app.vrroom

import android.content.Context
import android.os.SystemClock
import com.stormvr.app.HeadTracker
import com.stormvr.app.PoseSender
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * "Modo PC (SteamVR)" da Sala VR: envia ao driver a CABEÇA (orientação, mesmo pacote do rastreamento do app) e as MÃOS
 * rastreadas pela câmera (posição + pinça), pelo pacote KbtHandPacket (UDP 5557). A posição vai relativa ao headset e
 * alinhada só ao giro da cabeça (x direita, y cima, z para trás), então não depende de onde o "norte" está no celular
 * nem no PC. Posição e pinça são ligáveis separadamente ([sendPos] / [sendPinch]).
 */
class PcLink(
    private val ctx: Context,
    private val st: VrState,
    private val host: String,
    private val headPort: Int,
    private val handPort: Int,
    private val deviceId: Int,
    private val rateHz: Int,
    private val sendHead: Boolean,
    private val orientationMode: Int,
) {
    @Volatile var sendPos = true
    @Volatile var sendPinch = true
    @Volatile var sent = 0L
        private set
    @Volatile var lastError = ""
        private set

    private var th: Thread? = null
    @Volatile private var running = false
    private val session = (System.nanoTime() and 0x7fffffff).toInt() or 1
    @Volatile var headSent = 0L
        private set
    @Volatile var headHz = 0f
        private set

    fun start() {
        if (running) return
        running = true
        th = Thread({ handLoop() }, "StormVR-PoseSender").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun stop() {
        running = false
        th?.interrupt(); th = null
    }

    fun recenter() { st.recenter = true }

    fun headRate(): Float = headHz

    private fun handLoop() {
        val buf = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
        val pts = Array(2) { FloatArray(63) }
        val valid = BooleanArray(2)
        val pinch = BooleanArray(2)
        val ratio = FloatArray(2)
        var seq = 0
        var fx = 0f
        var fy = 1f
        var socket: DatagramSocket? = null
        var addr: InetAddress? = null
        val hbuf = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
        var hseq = 0
        var lastCount = -1L
        var winStart = System.nanoTime(); var winCount = 0
        var lastHandNs = 0L
        while (running) {
            try {
                if (socket == null) {
                    addr = InetAddress.getByName(host)
                    socket = DatagramSocket()
                    socket.trafficClass = 0x10
                }
                // espera a PRÓXIMA pose da sala (sem sleep fixo: sem fila e sem até 16 ms de atraso); 25 ms de segurança
                synchronized(st.poseLock) {
                    if (st.poseCount == lastCount) st.poseLock.wait(25)
                    lastCount = st.poseCount
                }
                if (!running) break
                if (sendHead) {
                    val qw: Float; val qx: Float; val qy: Float; val qz: Float
                    val hp = FloatArray(3); val posOk: Boolean; val stampUs: Long; val rc: Int
                    synchronized(st.poseLock) {
                        qw = st.headQ[0]; qx = st.headQ[1]; qy = st.headQ[2]; qz = st.headQ[3]
                        hp[0] = st.headPosSv[0]; hp[1] = st.headPosSv[1]; hp[2] = st.headPosSv[2]
                        posOk = st.headPosOk; stampUs = st.headStampUs; rc = st.headRecenterCount
                    }
                    hbuf.clear()
                    hbuf.putInt(PoseSender.MAGIC); hbuf.putInt(deviceId); hbuf.putInt(session); hbuf.putInt(hseq++)
                    hbuf.putLong(stampUs)                              // instante da CAPTURA da pose (não do envio)
                    val exOn = !posOk && st.extrasActive && st.extrasSend
                    if (exOn) { hp[0] = st.extraLean; hp[1] = st.extraDrop; hp[2] = 0f }
                    hbuf.putShort((if (posOk) 1 else if (exOn) 2 else 0).toShort())   // KBT_HEAD_F_POS / KBT_HEAD_F_EXTRA (3DoF: lado + altura)
                    hbuf.put((if (posOk) 1 else 0).toByte())                      // trackingMode: 1 = 6DoF
                    hbuf.put(0.toByte())
                    hbuf.putFloat(qw); hbuf.putFloat(qx); hbuf.putFloat(qy); hbuf.putFloat(qz)
                    hbuf.putInt(rc)
                    hbuf.putFloat(hp[0]); hbuf.putFloat(hp[1]); hbuf.putFloat(hp[2])
                    socket.send(DatagramPacket(hbuf.array(), 60, addr, headPort))
                    headSent++; winCount++
                    val nowN = System.nanoTime()
                    if (nowN - winStart >= 1_000_000_000L) { headHz = winCount * 1e9f / (nowN - winStart); winCount = 0; winStart = nowN }
                }
                // mãos: no máximo ~60 pacotes/s (a cabeça sai a headHz, acima disso não há ganho para as mãos)
                val nowH = System.nanoTime()
                if (nowH - lastHandNs < 16_000_000L) continue
                lastHandNs = nowH
                synchronized(st.handLock) {
                    for (i in 0..1) {
                        valid[i] = st.handsOn && st.handValid[i]
                        pinch[i] = st.handPinch[i]
                        ratio[i] = st.handRatio[i]
                        if (valid[i]) System.arraycopy(st.handPts[i], 0, pts[i], 0, 63)
                    }
                }
                // frente da cabeça no plano horizontal (referencial da cena: x direita, y frente, z cima)
                val gx = st.gaze[0]
                val gy = st.gaze[1]
                val gn = sqrt(gx * gx + gy * gy)
                if (gn > 0.25f) { fx = gx / gn; fy = gy / gn }
                val rx = fy            // direita = frente girada -90°
                val ry = -fx
                val hx = st.headX
                val hy = st.headY
                val hz = st.headZ

                buf.clear()
                buf.putInt(KBT_HAND_MAGIC)
                buf.putInt(deviceId)
                buf.putInt(session)
                buf.putInt(seq++)
                buf.putLong(SystemClock.elapsedRealtimeNanos() / 1000L)
                var fl = 0
                if (sendPos) fl = fl or F_POS
                if (sendPinch) fl = fl or F_PINCH
                buf.putShort(fl.toShort())
                buf.putShort(0)
                for (i in 0..1) {                                 // [0] = esquerda, [1] = direita (igual ao VrState)
                    var sf = 0
                    var px = 0f; var py = 0f; var pz = 0f
                    var ax = 0f; var ay = 0f; var az = 0f
                    if (valid[i]) {
                        sf = 1
                        if (pinch[i]) sf = sf or 2
                        val p = pts[i]
                        // centro da palma: pulso (0) e bases do indicador, médio, anelar e mindinho (5, 9, 13, 17)
                        val cx = (p[0] + p[15] + p[27] + p[39] + p[51]) / 5f
                        val cy = (p[1] + p[16] + p[28] + p[40] + p[52]) / 5f
                        val cz = (p[2] + p[17] + p[29] + p[41] + p[53]) / 5f
                        val dx = cx - hx; val dy = cy - hy; val dz = cz - hz
                        px = dx * rx + dy * ry
                        py = dz
                        pz = -(dx * fx + dy * fy)
                        var vx = p[27] - p[0]; var vy = p[28] - p[1]; var vz = p[29] - p[2]     // pulso -> base do dedo médio
                        val vn = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-5f)
                        vx /= vn; vy /= vn; vz /= vn
                        ax = vx * rx + vy * ry
                        ay = vz
                        az = -(vx * fx + vy * fy)
                    }
                    buf.put(sf.toByte()); buf.put(0); buf.put(0); buf.put(0)
                    buf.putFloat(px); buf.putFloat(py); buf.putFloat(pz)
                    buf.putFloat(ax); buf.putFloat(ay); buf.putFloat(az)
                    buf.putFloat(ratio[i])
                }
                socket.send(DatagramPacket(buf.array(), 92, addr, handPort))
                sent++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        const val KBT_HAND_MAGIC = 0x3148424B
        const val F_POS = 1
        const val F_PINCH = 2
    }
}
