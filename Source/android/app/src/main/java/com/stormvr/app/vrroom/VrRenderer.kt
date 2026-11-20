package com.stormvr.app.vrroom

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// Distorção de lente (a mesma do driver StormVR): amostra = centro + (p - centro) * (1 + k1 r² + k2 r⁴)
private const val FS_VS = """
attribute vec2 aPos;
uniform mat4 uTex;
uniform vec2 uScale;
varying vec2 vUV;
void main() {
  vUV = (uTex * vec4(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5, 0.0, 1.0)).xy;
  gl_Position = vec4(aPos * uScale, 0.0, 1.0);
}
"""
private const val FS_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vUV;
void main() { gl_FragColor = texture2D(uTexture, vUV); }
"""
private const val LENS_VS = """
attribute vec2 aPos;
varying vec2 vUV;
void main() {
  vUV = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
private const val LENS_FS = """
precision highp float;
uniform sampler2D uTex;
uniform float uK1;
uniform float uK2;
uniform float uAspect;
uniform float uSep;
varying vec2 vUV;
void main() {
  float eye = step(0.5, vUV.x);
  vec2 e = vec2((vUV.x - eye * 0.5) * 2.0, vUV.y);
  e.x = e.x + (eye < 0.5 ? uSep : -uSep);
  vec2 p = (e - 0.5) * 2.0;
  p.x = p.x * uAspect;
  float r2 = dot(p, p);
  float sc = 1.0 + uK1 * r2 + uK2 * r2 * r2;
  vec2 s = 0.5 + (e - 0.5) * sc;
  if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  } else {
    gl_FragColor = texture2D(uTex, vec2(s.x * 0.5 + eye * 0.5, s.y));
  }
}
"""

class VrRenderer(
    context: Context,
    private val st: VrState,
    private val ui: VrUi,
    private val onSurface: (Surface?) -> Unit
) : GLSurfaceView.Renderer, SensorEventListener {

    // ---------- sensores (rotação 3DoF de baixa latência) ----------
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val hasSensor: Boolean get() = sensor != null

    private val lock = Any()
    private val rotRaw = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val rotTmp = FloatArray(16)
    private val rotMap = FloatArray(16)
    @Volatile private var heading0 = 0f

    private val gyroSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 0f
    @Volatile private var angSpeed = 0f

    private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // histórico da rotação (carimbo de tempo do sensor): permite usar a rotação do instante da imagem da câmera
    private val hN = 64
    private val hT = LongArray(hN)
    private val hR = Array(hN) { FloatArray(9) }
    private var hHead = 0
    private var hCount = 0

    // velocidade (no referencial "raw" do AR) assistida pelo acelerômetro
    private val velI = FloatArray(3)
    private var lastAccT = 0L
    @Volatile private var arLatNs = 45_000_000L      // atraso medido: captura da imagem -> disponível
    private var lastTrackedNs = 0L

    fun startSensors() {
        sensor?.let { sm.registerListener(this, it, 10_000) }
        gyroSensor?.let { sm.registerListener(this, it, 5_000) }
        accelSensor?.let { sm.registerListener(this, it, 10_000) }
        startHeadSampler()
    }

    /** Rotação (device -> mundo) do instante [tNs] (relógio dos sensores). Chamar dentro de synchronized(lock). */
    private fun rotationAt(tNs: Long, out: FloatArray) {
        if (hCount == 0) { System.arraycopy(rotRaw, 0, out, 0, 16); return }
        var found = (hHead - 1 + hN) % hN
        for (k in 0 until hCount) {
            val i = (hHead - 1 - k + hN) % hN
            found = i
            if (hT[i] <= tNs) break
        }
        val r = hR[found]
        java.util.Arrays.fill(out, 0f)
        out[0] = r[0]; out[1] = r[1]; out[2] = r[2]
        out[4] = r[3]; out[5] = r[4]; out[6] = r[5]
        out[8] = r[6]; out[9] = r[7]; out[10] = r[8]
        out[15] = 1f
    }

    fun stopSensors() { sm.unregisterListener(this); stopHeadSampler() }

    // ---------- envio da cabeça ao PC, FORA do laço de desenho ----------
    // Antes a pose era publicada a cada quadro de GL; em 6DoF o laço espera pela câmera (ARCore) e, com o aparelho esquentando, caía para 5-10 Hz:
    // a cabeça "dava pulinhos". Agora uma thread própria lê a rotação do giroscópio a headHz (padrão 100 Hz), sem previsão (o driver prevê e suaviza).
    private var headThread: Thread? = null
    @Volatile private var headRun = false
    private val sRot = FloatArray(16)
    private val sMap = FloatArray(16)
    private val sView = FloatArray(16)
    private val sQ = FloatArray(4)

    private fun startHeadSampler() {
        if (headRun) return
        headRun = true
        headThread = Thread({ headLoop() }, "StormVR-HeadSampler").also { it.start() }
    }

    private fun stopHeadSampler() {
        headRun = false
        headThread?.interrupt()
        headThread = null
    }

    private fun headLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
        var next = System.nanoTime()
        while (headRun) {
            try {
                if (hCount > 0) sampleHead()
                val period = 1_000_000_000L / st.headHz.coerceIn(30, 200)
                next += period
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                else if (wait < -period * 5) next = System.nanoTime()
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    /** Mesma rotação usada para desenhar (sensor -> tela -> recentrar), sem previsão. */
    private fun sampleHead() {
        synchronized(lock) { System.arraycopy(rotRaw, 0, sRot, 0, 16) }
        val ok = when (st.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(sRot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, sMap)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(sRot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, sMap)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(sRot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, sMap)
            else -> false
        }
        if (!ok) System.arraycopy(sRot, 0, sMap, 0, 16)
        System.arraycopy(sMap, 0, sView, 0, 16)
        Matrix.rotateM(sView, 0, -Math.toDegrees(heading0.toDouble()).toFloat(), 0f, 0f, 1f)
        viewToHeadQuat(sView, sQ)
        synchronized(st.poseLock) {
            st.headQ[0] = sQ[0]; st.headQ[1] = sQ[1]; st.headQ[2] = sQ[2]; st.headQ[3] = sQ[3]
            st.headStampUs = SystemClock.elapsedRealtimeNanos() / 1000L
            st.poseCount++
            st.poseLock.notifyAll()
        }
    }

    /** Estende a rotação [VrState.predMs] ms (padrão 30) para frente com o giroscópio (compensa a latência até o pixel/PC). */
    private fun predictRotation(r: FloatArray) {
        val wx = gx
        val wy = gy
        val wz = gz
        val mag = sqrt(wx * wx + wy * wy + wz * wz)
        angSpeed = mag
        if (mag < 0.03f) return
        val horizon = (st.predMs / 1000f).coerceIn(0f, 0.08f)
        if (horizon <= 0f) return
        val th = (mag * horizon).coerceAtMost(0.12f)
        val ax = wx / mag
        val ay = wy / mag
        val az = wz / mag
        val c = cos(th)
        val sn = sin(th)
        val t = 1f - c
        val r00 = t * ax * ax + c;       val r01 = t * ax * ay - sn * az; val r02 = t * ax * az + sn * ay
        val r10 = t * ax * ay + sn * az; val r11 = t * ay * ay + c;       val r12 = t * ay * az - sn * ax
        val r20 = t * ax * az - sn * ay; val r21 = t * ay * az + sn * ax; val r22 = t * az * az + c
        for (i in 0..2) {
            val a0 = r[4 * i]
            val a1 = r[4 * i + 1]
            val a2 = r[4 * i + 2]
            r[4 * i] = a0 * r00 + a1 * r10 + a2 * r20
            r[4 * i + 1] = a0 * r01 + a1 * r11 + a2 * r21
            r[4 * i + 2] = a0 * r02 + a1 * r12 + a2 * r22
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gx = e.values[0]; gy = e.values[1]; gz = e.values[2]
            return
        }
        if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            if (!st.imuPos) return
            val t = e.timestamp
            val dt = if (lastAccT == 0L) 0.01f else ((t - lastAccT) / 1e9f).coerceIn(0.001f, 0.05f)
            lastAccT = t
            var ax = e.values[0]
            var ay = e.values[1]
            var az = e.values[2]
            if (ax * ax + ay * ay + az * az < 0.0064f) { ax = 0f; ay = 0f; az = 0f }   // ignora ruído (< 0,08 m/s²)
            var wx = 0f
            var wy = 0f
            var wz = 0f
            synchronized(lock) {
                wx = rotRaw[0] * ax + rotRaw[1] * ay + rotRaw[2] * az
                wy = rotRaw[4] * ax + rotRaw[5] * ay + rotRaw[6] * az
                wz = rotRaw[8] * ax + rotRaw[9] * ay + rotRaw[10] * az
            }
            val yo = yawOff                       // mundo do sensor -> referencial "raw" do AR
            val c = cos(yo)
            val s = sin(yo)
            val rx = wx * c - wy * s
            val ry = wx * s + wy * c
            synchronized(velI) {
                val leak = 1f - 1.5f * dt        // vazamento: impede a deriva da integração
                velI[0] = ((velI[0] + rx * dt) * leak).coerceIn(-3f, 3f)
                velI[1] = ((velI[1] + ry * dt) * leak).coerceIn(-3f, 3f)
                velI[2] = ((velI[2] + wz * dt) * leak).coerceIn(-3f, 3f)
            }
            return
        }
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        synchronized(lock) {
            SensorManager.getRotationMatrixFromVector(rotRaw, v)
            val i = hHead
            hT[i] = e.timestamp
            val r = hR[i]
            r[0] = rotRaw[0]; r[1] = rotRaw[1]; r[2] = rotRaw[2]
            r[3] = rotRaw[4]; r[4] = rotRaw[5]; r[5] = rotRaw[6]
            r[6] = rotRaw[8]; r[7] = rotRaw[9]; r[8] = rotRaw[10]
            hHead = (hHead + 1) % hN
            if (hCount < hN) hCount++
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- ARCore (posição 6DoF) ----------
    @Volatile var arSession: Session? = null
    @Volatile var arRunning = false
    @Volatile var tracker: HandTracker? = null
    @Volatile var sensorOrientation = 90
    private var arTexId = 0
    private var arTexSet = false
    private var geomRot = -1
    private var geomW = 0
    private var geomH = 0
    private var lastArTs = 0L
    private val arM = FloatArray(16)
    @Volatile private var yawOff = 0f
    private var yawInit = false
    private var haveRaw = false
    private var haveRaw0 = false
    private var needP0 = true
    private var posInit = false
    private var arrivalNs = 0L
    private val lastRaw = FloatArray(3)
    private val vel = FloatArray(3)
    private val posS = FloatArray(3)
    private val raw0 = FloatArray(3)

    private fun wrap(a: Float): Float {
        var x = a
        val twoPi = (2 * PI).toFloat()
        while (x > PI) x -= twoPi
        while (x < -PI) x += twoPi
        return x
    }

    /** Lê a pose do ARCore (se houver frame novo) e guarda a posição da câmera. */
    private fun updateAr(imuFx: Float, imuFy: Float) {
        val s = arSession
        if ((!st.sixDof && !st.handsOn && !st.passthrough) || s == null || !arRunning) {
            st.arState = 0
            haveRaw0 = false
            needP0 = true
            return
        }
        try {
            if (!arTexSet) {
                s.setCameraTextureName(arTexId)
                arTexSet = true
            }
            // a geometria é a de UM olho: assim a imagem da câmera e a projeção casam com cada viewport
            val gw = if (st.stereo) width / 2 else width
            if (geomRot != st.rotation || geomW != gw || geomH != height) {
                s.setDisplayGeometry(st.rotation, gw, height)
                geomRot = st.rotation; geomW = gw; geomH = height
            }
            val frame = s.update()
            val cam = frame.camera
            val tracking = cam.trackingState == TrackingState.TRACKING
            st.arState = if (tracking) 2 else 1
            val newFrame = frame.timestamp != lastArTs
            if (newFrame) lastArTs = frame.timestamp

            // modo câmera: UV da imagem para um quad em tela cheia + projeção real da câmera
            if (st.passthrough && frame.timestamp != 0L) {
                try {
                    frame.transformCoordinates2d(
                        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, bgNdc,
                        Coordinates2d.TEXTURE_NORMALIZED, bgUv)
                    bgUvBuf.position(0)
                    bgUvBuf.put(bgUv)
                    bgUvBuf.position(0)
                    cam.getProjectionMatrix(arProj, 0, 0.05f, 60f)
                    bgReady = true
                } catch (_: Exception) {
                }
            }

            if (tracking && newFrame) {
            lastTrackedNs = System.nanoTime()
            val latNs = SystemClock.elapsedRealtimeNanos() - frame.timestamp
            if (latNs in 3_000_000L..250_000_000L) arLatNs = (0.8f * arLatNs + 0.2f * latNs).toLong()
            val pose = cam.displayOrientedPose
            val t = pose.translation
            pose.toMatrix(arM, 0)
            // eixos do ARCore (Y cima, -Z frente) -> eixos do mundo do sensor (Z cima, Y frente)
            val rx = t[0]
            val ry = -t[2]
            val rz = t[1]

            // alinhamento de yaw entre o mundo do ARCore e o mundo do giroscópio
            val wfx = -arM[8]
            val wfy = arM[10]          // = -(-arM[10])
            if (angSpeed < 0.6f && sqrt(wfx * wfx + wfy * wfy) > 0.4f && sqrt(imuFx * imuFx + imuFy * imuFy) > 0.4f) {
                val delta = wrap(atan2(imuFx, imuFy) - atan2(wfx, wfy))
                if (!yawInit) { yawOff = delta; yawInit = true }
                else yawOff += 0.08f * wrap(delta - yawOff)
            }

            // velocidade (para prever a posição durante a latência da câmera)
            val now = System.nanoTime()
            if (haveRaw) {
                val dt = (now - arrivalNs) / 1e9f
                if (dt > 0.005f && dt < 0.25f) {
                    vel[0] = (0.5f * vel[0] + 0.5f * (rx - lastRaw[0]) / dt).coerceIn(-3f, 3f)
                    vel[1] = (0.5f * vel[1] + 0.5f * (ry - lastRaw[1]) / dt).coerceIn(-3f, 3f)
                    vel[2] = (0.5f * vel[2] + 0.5f * (rz - lastRaw[2]) / dt).coerceIn(-3f, 3f)
                }
            }
            synchronized(velI) {
                // a velocidade medida pelo AR corrige a do acelerômetro (que sozinha derivaria)
                for (k in 0..2) velI[k] = 0.5f * velI[k] + 0.5f * vel[k]
            }
            lastRaw[0] = rx; lastRaw[1] = ry; lastRaw[2] = rz
            arrivalNs = now
            haveRaw = true
            if (!posInit) {
                posS[0] = rx; posS[1] = ry; posS[2] = rz
                posInit = true
            }
            if (!haveRaw0 || needP0) {
                raw0[0] = rx; raw0[1] = ry; raw0[2] = rz
                haveRaw0 = true
                needP0 = false
            }

            }

            // manda a imagem da câmera para o rastreador de mãos (só quando ele está livre = menor latência).
            // Funciona mesmo que o 6DoF esteja "perdido" (ex.: mãos cobrindo a câmera): nesse caso usa o giroscópio.
            val tr = tracker
            if (newFrame && st.handsOn && tr != null && tr.ready) {
                submitHandFrame(frame, cam.imageIntrinsics, tr, tracking && haveRaw0 && st.sixDof)
            }
        } catch (e: Exception) {
            st.arState = 1
            arErr = e.javaClass.simpleName
        }
    }

    @Volatile var arErr = ""
    @Volatile var camErr = ""

    // fundo "câmera" (passthrough)
    @Volatile private var bgReady = false
    private val bgNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val bgUv = FloatArray(8)
    private val arProj = FloatArray(16)
    private val bgPosBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val bgUvBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var bgProg = 0
    private var lensProg = 0
    private var fsProg = 0
    private var lensFbo = 0
    private var lensTex = 0
    private var lensDepth = 0
    private var curEye = -1                      // -1 = imagem única; 0 = olho esquerdo; 1 = olho direito
    private val sbsTex = FloatArray(16)
    private val tmpTexMat = FloatArray(16)
    private val lensQuad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
        it.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); it.position(0)
    }
    private var bgPos = 0
    private var bgUvLoc = 0
    private var bgSampler = 0

    private val tcIn = floatArrayOf(0.5f, 0.5f, 0.6f, 0.5f, 0.5f, 0.6f)
    private val tcOut = FloatArray(6)

    /** Rotação horária (0/90/180/270) que deixa a imagem da câmera "em pé" para a tela atual. */
    private fun imageRotation(frame: com.google.ar.core.Frame): Int {
        try {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, tcIn, Coordinates2d.VIEW_NORMALIZED, tcOut)
            val vx = tcOut[2] - tcOut[0]
            val vy = tcOut[3] - tcOut[1]
            return if (abs(vx) >= abs(vy)) {
                if (vx >= 0f) 0 else 180
            } else {
                if (vy > 0f) 90 else 270
            }
        } catch (_: Exception) {
            val rotDeg = when (st.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            return ((sensorOrientation - rotDeg) % 360 + 360) % 360
        }
    }

    /** Matriz olho->cena a partir da pose do ARCore (mesma transformação usada na posição da cabeça). */
    private fun eyeToSceneFromAr(out: FloatArray) {
        val a = heading0 - yawOff
        val c = cos(a)
        val sn = sin(a)
        for (col in 0..2) {
            val mx = arM[col * 4]
            val my = -arM[col * 4 + 2]
            val mz = arM[col * 4 + 1]
            out[col * 4] = mx * c - my * sn
            out[col * 4 + 1] = mx * sn + my * c
            out[col * 4 + 2] = mz
            out[col * 4 + 3] = 0f
        }
        val dx = arM[12] - raw0[0]
        val dy = -arM[14] - raw0[1]
        val dz = arM[13] - raw0[2]
        out[12] = dx * c - dy * sn
        out[13] = dx * sn + dy * c
        out[14] = dz
        out[15] = 1f
    }

    private fun submitHandFrame(
        frame: com.google.ar.core.Frame,
        intr: com.google.ar.core.CameraIntrinsics,
        tr: HandTracker,
        useArPose: Boolean
    ) {
        var img: android.media.Image? = null
        try {
            val im = frame.acquireCameraImage()
            img = im
            val rot = imageRotation(frame)
            tr.submit { j ->
                j.copyFrom(im)
                if (useArPose) eyeToSceneFromAr(j.pose) else Matrix.invertM(j.pose, 0, view, 0)
                j.fx = intr.focalLength[0]
                j.fy = intr.focalLength[1]
                j.cx = intr.principalPoint[0]
                j.cy = intr.principalPoint[1]
                j.rot = rot
                j.tsNs = frame.timestamp
                j.submitNs = System.nanoTime()
            }
            camErr = ""
        } catch (e: Exception) {
            camErr = "câmera: ${e.javaClass.simpleName}"
        } finally {
            try { img?.close() } catch (_: Exception) {}
        }
    }

    /** As mãos chegam do rastreador a ~15-30 Hz: prevemos o movimento e suavizamos a cada quadro (fluidez). */
    private fun updateHands() {
        val f = if (st.handsOn) tracker?.latest else null
        val now = System.nanoTime()
        val dt = if (lastHandsNs == 0L) 0.016f else ((now - lastHandsNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastHandsNs = now
        val a = 1f - exp(-dt * 38f)
        synchronized(st.handLock) {
            for (h in 0..1) {
                val ok = f != null && f.valid[h] && (now - f.arrivalNs) < 300_000_000L
                st.handValid[h] = ok
                st.handPinch[h] = ok && f!!.pinch[h]
                st.handRatio[h] = if (ok) f!!.ratio[h] else 1f
                st.handClosing[h] = ok && f!!.closing[h]
                if (!ok) {
                    hRenderInit[h] = false
                    continue
                }
                // atraso real: processamento do rastreador + (fora do modo câmera) latência da câmera
                val camLat = if (st.passthrough) 0f else arLatNs / 1e9f
                val age = ((((now - f!!.arrivalNs) + f.latNs) / 1e9f + camLat) * 0.8f).coerceIn(0f, 0.14f)
                val src = f.pts[h]
                val v = f.vel[h]
                val dst = st.handPts[h]
                val r = hRender[h]
                for (i in 0 until 21) {
                    for (c in 0..2) {
                        val k = i * 3 + c
                        val t = src[k] + v[c] * age
                        if (!hRenderInit[h]) r[k] = t else r[k] += (t - r[k]) * a
                        dst[k] = r[k]
                    }
                }
                hRenderInit[h] = true
            }
        }
    }

    // ---------- GL ----------
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val tr = FloatArray(16)
    private val texMat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val quad = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-.5f, -.5f, 0f, .5f, -.5f, 0f, -.5f, .5f, 0f, .5f, .5f, 0f))
            position(0)
        }

    // malha em faixa (48 colunas) — a curvatura é aplicada no vertex shader
    private val segs = 48
    private val strip = ByteBuffer.allocateDirect((segs + 1) * 2 * 2 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            for (i in 0..segs) {
                val x = -0.5f + i.toFloat() / segs
                put(x); put(-0.5f)
                put(x); put(0.5f)
            }
            position(0)
        }

    private inner class SurfProg(vs: String, fs: String) {
        val p = program(vs, fs)
        val aPos = GLES20.glGetAttribLocation(p, "aPos")
        val uVP = GLES20.glGetUniformLocation(p, "uVP")
        val uRect = GLES20.glGetUniformLocation(p, "uRect")
        val uOrg = GLES20.glGetUniformLocation(p, "uOrg")
        val uSA = GLES20.glGetUniformLocation(p, "uSA")
        val uSB = GLES20.glGetUniformLocation(p, "uSB")
        val uDepth = GLES20.glGetUniformLocation(p, "uDepth")
        val uTex = GLES20.glGetUniformLocation(p, "uTex")
        val uColor = GLES20.glGetUniformLocation(p, "uColor")
        val uTexture = GLES20.glGetUniformLocation(p, "uTexture")
    }

    private lateinit var surfExt: SurfProg
    private lateinit var surfUi: SurfProg
    private lateinit var surfColor: SurfProg

    // programa da sala (procedural)
    private var roomProg = 0
    private var rPos = 0; private var rVP = 0; private var rModel = 0; private var rColor = 0
    private var rMode = 0; private var rScale = 0; private var rLight = 0; private var rLite = 0

    // programa das mãos (malha com normais)
    private var handProg = 0
    private var hPos = 0; private var hNrm = 0; private var hVP = 0
    private var hModel = 0; private var hColor = 0; private var hEye = 0; private var hTaper = 0
    private val cylBuf = buildCylinder()
    private val sphBuf = buildSphere()
    private val palmBuf: FloatBuffer = ByteBuffer.allocateDirect(24 * 6 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val handCopy = Array(2) { FloatArray(63) }
    private val handOk = BooleanArray(2)
    private val handPin = BooleanArray(2)
    private val handRat = FloatArray(2)
    private val ballColor = FloatArray(4)

    // programa 2D plano (teclado)
    private var uiProg = 0
    private var uPos = 0; private var uMvp = 0; private var uSampler = 0

    private var texId = 0
    private var surfaceTex: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)

    private var width = 1
    private var height = 1

    private val ipd = 0.064f
    private val floorZ = -2f
    private val ceilZ = 3f

    private val cWand = floatArrayOf(0.25f, 0.25f, 0.28f, 1f)
    private val cLaserL = floatArrayOf(0.2f, 0.6f, 1f, 1f)
    private val cLaserR = floatArrayOf(1f, 0.35f, 0.2f, 1f)
    private val cPressed = floatArrayOf(1f, 1f, 1f, 1f)
    private val cHandle = floatArrayOf(0.85f, 0.85f, 0.92f, 0.55f)
    private val cHandleHot = floatArrayOf(0.3f, 0.65f, 1f, 1f)
    private val cFrame = floatArrayOf(0.02f, 0.02f, 0.03f, 1f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        roomProg = program(ROOM_VS, ROOM_FS)
        rPos = GLES20.glGetAttribLocation(roomProg, "aPos")
        rVP = GLES20.glGetUniformLocation(roomProg, "uVP")
        rModel = GLES20.glGetUniformLocation(roomProg, "uModel")
        rColor = GLES20.glGetUniformLocation(roomProg, "uColor")
        rMode = GLES20.glGetUniformLocation(roomProg, "uMode")
        rScale = GLES20.glGetUniformLocation(roomProg, "uScale")
        rLight = GLES20.glGetUniformLocation(roomProg, "uLight")
        rLite = GLES20.glGetUniformLocation(roomProg, "uLite")

        surfExt = SurfProg(SURF_VS, EXT_FS)
        surfUi = SurfProg(SURF_VS, SURF_UI_FS)
        surfColor = SurfProg(SURF_VS, SURF_COLOR_FS)

        bgProg = program(BG_VS, BG_FS)
        lensProg = program(LENS_VS, LENS_FS)
        fsProg = program(FS_VS, FS_FS)
        lensFbo = 0; lensTex = 0; lensDepth = 0
        bgPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bgUvLoc = GLES20.glGetAttribLocation(bgProg, "aUV")
        bgSampler = GLES20.glGetUniformLocation(bgProg, "uTexture")
        bgReady = false

        handProg = program(HAND_VS, HAND_FS)
        hTaper = GLES20.glGetUniformLocation(handProg, "uTaper")
        hPos = GLES20.glGetAttribLocation(handProg, "aPos")
        hNrm = GLES20.glGetAttribLocation(handProg, "aNrm")
        hVP = GLES20.glGetUniformLocation(handProg, "uVP")
        hModel = GLES20.glGetUniformLocation(handProg, "uModel")
        hColor = GLES20.glGetUniformLocation(handProg, "uColor")
        hEye = GLES20.glGetUniformLocation(handProg, "uEye")

        uiProg = program(UI_VS, UI_FS)
        uPos = GLES20.glGetAttribLocation(uiProg, "aPos")
        uMvp = GLES20.glGetUniformLocation(uiProg, "uMVP")
        uSampler = GLES20.glGetUniformLocation(uiProg, "uTexture")

        ui.toolbar.initTexture()
        ui.keyboard.initTexture()
        ui.status.initTexture()

        val ext = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        texId = t[0]
        arTexId = t[1]
        arTexSet = false
        for (id in t) {
            GLES20.glBindTexture(ext, id)
            GLES20.glTexParameteri(ext, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(ext, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(ext, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(ext, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        try { surface?.release() } catch (_: Exception) {}
        try { surfaceTex?.release() } catch (_: Exception) {}
        val stex = SurfaceTexture(texId)
        stex.setDefaultBufferSize(VrState.TEX_W, VrState.TEX_H)
        stex.setOnFrameAvailableListener { frameAvailable.set(true) }
        surfaceTex = stex
        surface = Surface(stex)
        onSurface(surface)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        makeLensTarget(w, h)
    }

    /** Alvo fora da tela onde os dois olhos são desenhados quando a distorção de lente está ligada. */
    private fun makeLensTarget(w: Int, h: Int) {
        if (lensFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(lensFbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(lensTex), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(lensDepth), 0)
            lensFbo = 0; lensTex = 0; lensDepth = 0
        }
        if (w < 2 || h < 2) return
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        lensTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lensTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val rb = IntArray(1)
        GLES20.glGenRenderbuffers(1, rb, 0)
        lensDepth = rb[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, lensDepth)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        lensFbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lensFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, lensTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, lensDepth)
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!ok) { lensFbo = 0 }                          // sem FBO: a distorção fica desligada, o resto funciona
    }

    /** Tela cheia: só o conteúdo (página ou stream), sem sala, olhos nem sensores. */
    private fun drawFullscreen() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        var sx = 1f
        var sy = 1f
        if (!st.fsFill && width > 0 && height > 0) {
            val sa = width.toFloat() / height
            val ta = VrState.TEX_W.toFloat() / VrState.TEX_H
            if (sa > ta) sx = ta / sa else sy = sa / ta
        }
        GLES20.glUseProgram(fsProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(fsProg, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(fsProg, "uTex"), 1, false, texMat, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(fsProg, "uScale"), sx, sy)
        val aPos = GLES20.glGetAttribLocation(fsProg, "aPos")
        lensQuad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, lensQuad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    private fun lensPass() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(lensProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lensTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(lensProg, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lensProg, "uK1"), st.k1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lensProg, "uK2"), st.k2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lensProg, "uAspect"), (width / 2f) / height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lensProg, "uSep"), st.sepOffFrac * 2f)   // deslocamento de cada imagem (fração da largura de UM olho)
        val aPos = GLES20.glGetAttribLocation(lensProg, "aPos")
        lensQuad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, lensQuad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    private var fpsCount = 0
    private var fpsT = 0L
    private var lastHandsNs = 0L
    private val hRender = Array(2) { FloatArray(63) }
    private val hRenderInit = BooleanArray(2)

    override fun onDrawFrame(gl: GL10?) {
        if (st.trackOnly) {                                   // "Iniciar rastreamento": só sensores/ARCore/mãos, sem desenhar a sala
            if (frameAvailable.getAndSet(false)) surfaceTex?.updateTexImage()
            updateHead()
            GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        fpsCount++
        val nowNs = System.nanoTime()
        if (nowNs - fpsT > 1_000_000_000L) { st.glFps = fpsCount; fpsCount = 0; fpsT = nowNs }
        if (frameAvailable.getAndSet(false)) {
            surfaceTex?.updateTexImage()
            surfaceTex?.getTransformMatrix(texMat)
        }
        ui.toolbar.uploadIfDirty()
        ui.status.uploadIfDirty()
        if (st.kbVisible) ui.keyboard.uploadIfDirty()
        if (st.fullscreen) {
            if (st.pcMode) updateHead()                       // modo PC em tela cheia: mantém câmera/mãos/cabeça ativas
            drawFullscreen(); return
        }
        updateHead()

        // com distorção de lente ligada os olhos vão para um alvo fora da tela e depois passam pelo shader da lente
        val lens = st.stereo && lensFbo != 0 && (st.k1 > 0.005f || st.k2 > 0.005f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (lens) lensFbo else 0)
        // o ARCore pode alterar o estado do GL durante update(): garantir o nosso
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (st.stereo) {
            val w = width / 2
            val half = st.ipdMm / 2000f                       // metade da distância entre os olhos (m)
            val off = if (lens) 0 else (st.sepOffFrac * width).toInt()   // com lente, o deslocamento é feito no shader
            val fov = st.fovDeg
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, 0, w, height)
            curEye = 0
            renderEye(-off, 0, w, height, half, fov)         // olho esquerdo (centro da imagem desloca p/ a esquerda)
            GLES20.glScissor(w, 0, width - w, height)
            curEye = 1
            renderEye(w + off, 0, w, height, -half, fov)     // olho direito
            curEye = -1
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            if (lens) lensPass()
        } else {
            curEye = -1
            renderEye(0, 0, width, height, 0f, 75f)
        }
    }

    private fun updateHead() {
        // modo câmera: a janela usa a rotação do instante da imagem (mesmo atraso do vídeo) => não "nada" no mundo real
        val syncPass = st.passthrough && bgReady && haveRaw0 && (System.nanoTime() - lastTrackedNs) < 200_000_000L
        if (syncPass) {
            val target = SystemClock.elapsedRealtimeNanos() - arLatNs - 12_000_000L
            synchronized(lock) { rotationAt(target, rotTmp) }
        } else {
            synchronized(lock) { System.arraycopy(rotRaw, 0, rotTmp, 0, 16) }
            predictRotation(rotTmp)
        }
        val ok = when (st.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotTmp, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rotMap)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotTmp, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rotMap)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotTmp, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, rotMap)
            else -> false
        }
        if (!ok) System.arraycopy(rotTmp, 0, rotMap, 0, 16)

        val imuFx = -rotMap[2]
        val imuFy = -rotMap[6]

        updateAr(imuFx, imuFy)

        if (st.recenter && abs(imuFx) + abs(imuFy) > 0.1f) {
            heading0 = atan2(imuFx, imuFy)
            st.recenter = false
            st.headRecenterCount++
            if (haveRaw) {
                raw0[0] = lastRaw[0]; raw0[1] = lastRaw[1]; raw0[2] = lastRaw[2]
                haveRaw0 = true
                needP0 = false
            } else {
                needP0 = true
            }
        }

        // posição da cabeça no referencial da cena
        var dsx = 0f
        var dsy = 0f
        var dsz = 0f
        if (st.sixDof && haveRaw && haveRaw0) {
            // sem predição no modo câmera (mantém a janela colada ao vídeo); fora dele prevê o atraso medido da câmera
            val age = if (syncPass) 0f
            else ((System.nanoTime() - arrivalNs) / 1e9f + arLatNs / 1e9f * 0.8f).coerceIn(0f, 0.11f)
            synchronized(velI) {
                val vu = if (st.imuPos) velI else vel
                for (k in 0..2) {
                    val target = lastRaw[k] + vu[k] * age
                    if (syncPass) posS[k] = target else posS[k] += (target - posS[k]) * 0.5f
                }
            }
            val dx = posS[0] - raw0[0]
            val dy = posS[1] - raw0[1]
            val dz = posS[2] - raw0[2]
            val a = heading0 - yawOff
            val c = cos(a)
            val s = sin(a)
            dsx = (dx * c - dy * s).coerceIn(-1.6f, 1.6f)
            dsy = (dx * s + dy * c).coerceIn(-1.6f, 1.9f)
            dsz = dz.coerceIn(-0.9f, 0.9f)
        }
        if (!st.sixDof && st.extrasActive) {             // 3DoF + extras: inclinou a cabeça = vai para o lado; barômetro = agachou
            val gx = st.gaze[0]; val gy = st.gaze[1]; val gn = kotlin.math.sqrt(gx * gx + gy * gy)
            if (gn > 0.25f) { dsx += st.extraLean * gy / gn; dsy += -st.extraLean * gx / gn }
            dsz += st.extraDrop
        }
        st.headX = dsx
        st.headY = dsy
        st.headZ = dsz

        // matriz de view = rotação (giroscópio) * translação (ARCore)
        if (st.freezeHead) Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)           // olhando fixamente para a tela (ignora o 3DoF)
        else {
            System.arraycopy(rotMap, 0, view, 0, 16)
            Matrix.rotateM(view, 0, -Math.toDegrees(heading0.toDouble()).toFloat(), 0f, 0f, 1f)
        }
        Matrix.translateM(view, 0, -dsx, -dsy, -dsz)
        st.gaze[0] = -view[2]; st.gaze[1] = -view[6]; st.gaze[2] = -view[10]     // para onde a cabeça aponta (clique por olhar)
        publishHeadPose(dsx, dsy, dsz, st.sixDof && haveRaw && haveRaw0)

        updateHands()
    }

    /**
     * Publica a pose da cabeça para o SteamVR a partir da MESMA matriz de view usada para desenhar a sala.
     * view = mundo(cena: x direita, y frente, z cima) -> olho; a orientação da cabeça é a transposta da parte 3x3.
     * Cena -> SteamVR: (x, y, z) -> (x, z, -y).
     */
    private fun publishHeadPose(px: Float, py: Float, pz: Float, posOk: Boolean) {
        // só a POSIÇÃO é publicada aqui (no ritmo da câmera/tela); a rotação sai da thread "HeadSampler" (sampleHead), a headHz
        synchronized(st.poseLock) {
            st.headPosSv[0] = px; st.headPosSv[1] = pz; st.headPosSv[2] = -py
            st.headPosOk = posOk
        }
    }

    /** Quaternion da orientação da cabeça (mundo SteamVR) a partir da matriz de view. Cena -> SteamVR: (x, y, z) -> (x, z, -y). */
    private fun viewToHeadQuat(v: FloatArray, out: FloatArray) {
        // R[r][c] = v[r*4 + c] (cabeça -> mundo da cena); linhas reordenadas para o mundo do SteamVR
        val m00 = v[0];  val m01 = v[1];  val m02 = v[2]
        val m10 = v[8];  val m11 = v[9];  val m12 = v[10]
        val m20 = -v[4]; val m21 = -v[5]; val m22 = -v[6]
        val tr = m00 + m11 + m22
        var w: Float; var x: Float; var y: Float; var z: Float
        if (tr > 0f) {
            val sq = sqrt(tr + 1f) * 2f
            w = 0.25f * sq; x = (m21 - m12) / sq; y = (m02 - m20) / sq; z = (m10 - m01) / sq
        } else if (m00 > m11 && m00 > m22) {
            val sq = sqrt(1f + m00 - m11 - m22) * 2f
            w = (m21 - m12) / sq; x = 0.25f * sq; y = (m01 + m10) / sq; z = (m02 + m20) / sq
        } else if (m11 > m22) {
            val sq = sqrt(1f + m11 - m00 - m22) * 2f
            w = (m02 - m20) / sq; x = (m01 + m10) / sq; y = 0.25f * sq; z = (m12 + m21) / sq
        } else {
            val sq = sqrt(1f + m22 - m00 - m11) * 2f
            w = (m10 - m01) / sq; x = (m02 + m20) / sq; y = (m12 + m21) / sq; z = 0.25f * sq
        }
        val n = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-6f)
        out[0] = w / n; out[1] = x / n; out[2] = y / n; out[3] = z / n
    }

    private fun renderEye(x: Int, y: Int, w: Int, h: Int, shift: Float, fov: Float) {
        GLES20.glViewport(x, y, w, h)
        if (st.passthrough && bgReady) System.arraycopy(arProj, 0, proj, 0, 16)
        else Matrix.perspectiveM(proj, 0, fov, w.toFloat() / h, 0.05f, 60f)
        Matrix.setIdentityM(tr, 0)
        tr[12] = shift
        Matrix.multiplyMM(eyeView, 0, tr, 0, view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, eyeView, 0)
        drawScene()
    }

    // =====================================================================  cena
    private fun drawBackground() {
        if (!bgReady) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arTexId)
        GLES20.glUniform1i(bgSampler, 0)
        GLES20.glEnableVertexAttribArray(bgPos)
        GLES20.glEnableVertexAttribArray(bgUvLoc)
        bgPosBuf.position(0)
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 8, bgPosBuf)
        bgUvBuf.position(0)
        GLES20.glVertexAttribPointer(bgUvLoc, 2, GLES20.GL_FLOAT, false, 8, bgUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawScene() {
        if (st.passthrough) drawBackground() else drawRoom()
        drawBrowserWindow()

        // ----- teclado (plano, flutuando na frente) -----
        if (st.kbVisible) {
            GLES20.glUseProgram(uiProg)
            val g = st.kb
            val kr = st.kbRot
            basis(kr[0], kr[3], kr[6], kr[2], kr[5], kr[8], -kr[1], -kr[4], -kr[7], g.cx, g.cy, g.cz, g.w, g.h)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ui.keyboard.texId)
            GLES20.glUniform1i(uSampler, 0)
            GLES20.glEnableVertexAttribArray(uPos)
            GLES20.glVertexAttribPointer(uPos, 3, GLES20.GL_FLOAT, false, 12, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        drawHands()
        drawLasers()
    }

    // ---------- mãos 3D ----------
    private fun toBuf(a: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a)
        b.position(0)
        return b
    }

    private fun buildCylinder(): FloatBuffer {
        val sides = 8                                       // mão mais leve: 8 lados (antes 10)
        val a = FloatArray(sides * 6 * 6)
        var k = 0
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float) {
            a[k++] = x; a[k++] = y; a[k++] = z; a[k++] = nx; a[k++] = ny; a[k++] = 0f
        }
        for (i in 0 until sides) {
            val t0 = (2.0 * PI * i / sides).toFloat()
            val t1 = (2.0 * PI * (i + 1) / sides).toFloat()
            val c0 = cos(t0); val s0 = sin(t0); val c1 = cos(t1); val s1 = sin(t1)
            v(c0, s0, 0f, c0, s0); v(c1, s1, 0f, c1, s1); v(c0, s0, 1f, c0, s0)
            v(c1, s1, 0f, c1, s1); v(c1, s1, 1f, c1, s1); v(c0, s0, 1f, c0, s0)
        }
        return toBuf(a)
    }

    private val sphVerts = 6 * 8 * 6

    private fun buildSphere(): FloatBuffer {
        val lat = 6                                         // esfera mais leve (antes 8 x 12)
        val lon = 8
        val a = FloatArray(lat * lon * 6 * 6)
        var k = 0
        fun pt(i: Int, j: Int, out: FloatArray) {
            val th = (PI * i / lat).toFloat()
            val ph = (2.0 * PI * j / lon).toFloat()
            out[0] = sin(th) * cos(ph); out[1] = sin(th) * sin(ph); out[2] = cos(th)
        }
        val p00 = FloatArray(3); val p10 = FloatArray(3); val p01 = FloatArray(3); val p11 = FloatArray(3)
        fun put(p: FloatArray) {
            a[k++] = p[0]; a[k++] = p[1]; a[k++] = p[2]; a[k++] = p[0]; a[k++] = p[1]; a[k++] = p[2]
        }
        for (i in 0 until lat) {
            for (j in 0 until lon) {
                pt(i, j, p00); pt(i + 1, j, p10); pt(i, j + 1, p01); pt(i + 1, j + 1, p11)
                put(p00); put(p10); put(p01)
                put(p10); put(p11); put(p01)
            }
        }
        return toBuf(a)
    }

    private fun drawMesh(buf: FloatBuffer, count: Int) {
        GLES20.glUniformMatrix4fv(hModel, 1, false, model, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(hPos, 3, GLES20.GL_FLOAT, false, 24, buf)
        buf.position(3)
        GLES20.glVertexAttribPointer(hNrm, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        buf.position(0)
    }

    private fun drawSphere(x: Float, y: Float, z: Float, r: Float) {
        GLES20.glUniform1f(hTaper, 1f)
        basis(r, 0f, 0f, 0f, r, 0f, 0f, 0f, r, x, y, z, 1f, 1f)
        drawMesh(sphBuf, sphVerts)
    }

    private fun drawBone(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        r0: Float, r1: Float
    ) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        val zx = dx / len; val zy = dy / len; val zz = dz / len
        var xx = -zy
        var xy = zx
        val xz = 0f
        val xl = sqrt(xx * xx + xy * xy)
        if (xl < 1e-3f) { xx = 1f; xy = 0f } else { xx /= xl; xy /= xl }
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        model[0] = xx * r0; model[1] = xy * r0; model[2] = xz * r0; model[3] = 0f
        model[4] = yx * r0; model[5] = yy * r0; model[6] = yz * r0; model[7] = 0f
        model[8] = zx * len; model[9] = zy * len; model[10] = zz * len; model[11] = 0f
        model[12] = ax; model[13] = ay; model[14] = az; model[15] = 1f
        GLES20.glUniform1f(hTaper, r1 / r0)       // dedo afina em direção à ponta
        drawMesh(cylBuf, 8 * 6)
        GLES20.glUniform1f(hTaper, 1f)
    }

    private val bones = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4,
        0, 5, 5, 6, 6, 7, 7, 8,
        9, 10, 10, 11, 11, 12,
        13, 14, 14, 15, 15, 16,
        17, 18, 18, 19, 19, 20
    )
    private val palmBones = intArrayOf(5, 9, 9, 13, 13, 17, 0, 17)
    private val jointR = floatArrayOf(
        0.012f,
        0.0125f, 0.0112f, 0.0100f, 0.0090f,
        0.0112f, 0.0100f, 0.0088f, 0.0078f,
        0.0112f, 0.0100f, 0.0088f, 0.0078f,
        0.0105f, 0.0094f, 0.0083f, 0.0074f,
        0.0098f, 0.0088f, 0.0078f, 0.0069f
    ).also { for (i in it.indices) it[i] *= 0.88f }       // dedos mais finos e elegantes
    private val cHandL = floatArrayOf(0.55f, 0.82f, 1.00f, 1f)    // azul-gelo suave
    private val cHandR = floatArrayOf(1.00f, 0.70f, 0.62f, 1f)    // coral suave
    private val cPinch = floatArrayOf(0.30f, 1.00f, 0.55f, 1f)
    private val cReticle = floatArrayOf(1f, 1f, 1f, 1f)
    private val ballS = Array(2) { FloatArray(3) }
    private val ballInit = BooleanArray(2)
    private var lastBallNs = 0L
    private val palmIdx = intArrayOf(0, 1, 5, 0, 5, 9, 0, 9, 13, 0, 13, 17)

    private fun drawHands() {
        synchronized(st.handLock) {
            for (h in 0..1) {
                handOk[h] = st.handValid[h]
                handPin[h] = st.handPinch[h]
                handRat[h] = st.handRatio[h]
                if (handOk[h]) System.arraycopy(st.handPts[h], 0, handCopy[h], 0, 63)
            }
        }
        if (!handOk[0] && !handOk[1]) return

        GLES20.glUseProgram(handProg)
        GLES20.glUniformMatrix4fv(hVP, 1, false, vp, 0)
        GLES20.glUniform3f(hEye, st.headX, st.headY, st.headZ)
        GLES20.glUniform1f(hTaper, 1f)
        GLES20.glEnableVertexAttribArray(hPos)
        GLES20.glEnableVertexAttribArray(hNrm)

        for (h in 0..1) {
            if (!handOk[h]) { ballInit[h] = false; continue }
            val p = handCopy[h]
            val base = if (h == 0) cHandL else cHandR
            GLES20.glUniform4fv(hColor, 1, base, 0)

            // palma com espessura: duas camadas deslocadas ao longo da normal
            palmBuf.position(0)
            for (layer in 0..1) {
                val sgn = if (layer == 0) 1f else -1f
                var q = 0
                while (q < palmIdx.size) {
                    val ia = palmIdx[q] * 3; val ib = palmIdx[q + 1] * 3; val ic = palmIdx[q + 2] * 3
                    val ux = p[ib] - p[ia]; val uy = p[ib + 1] - p[ia + 1]; val uz = p[ib + 2] - p[ia + 2]
                    val vx = p[ic] - p[ia]; val vy = p[ic + 1] - p[ia + 1]; val vz = p[ic + 2] - p[ia + 2]
                    var nx = uy * vz - uz * vy
                    var ny = uz * vx - ux * vz
                    var nz = ux * vy - uy * vx
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    nx /= nl; ny /= nl; nz /= nl
                    val off = 0.0075f * sgn
                    for (i3 in intArrayOf(ia, ib, ic)) {
                        palmBuf.put(p[i3] + nx * off); palmBuf.put(p[i3 + 1] + ny * off); palmBuf.put(p[i3 + 2] + nz * off)
                        palmBuf.put(nx * sgn); palmBuf.put(ny * sgn); palmBuf.put(nz * sgn)
                    }
                    q += 3
                }
            }
            palmBuf.position(0)
            Matrix.setIdentityM(model, 0)
            drawMesh(palmBuf, 24)

            // ossos dos dedos (afinando) e ossos da palma (mais grossos)
            var b = 0
            while (b < bones.size) {
                val a0 = bones[b]
                val b0 = bones[b + 1]
                drawBone(p[a0 * 3], p[a0 * 3 + 1], p[a0 * 3 + 2],
                    p[b0 * 3], p[b0 * 3 + 1], p[b0 * 3 + 2], jointR[a0], jointR[b0])
                b += 2
            }
            b = 0
            while (b < palmBones.size) {
                val a0 = palmBones[b]
                val b0 = palmBones[b + 1]
                drawBone(p[a0 * 3], p[a0 * 3 + 1], p[a0 * 3 + 2],
                    p[b0 * 3], p[b0 * 3 + 1], p[b0 * 3 + 2], 0.0115f, 0.0115f)
                b += 2
            }

            // (sem antebraço/cotoco: só a mão é desenhada)

            // articulações (arredondam as dobras)
            for (i in 1 until 21) {
                if (handPin[h] && (i == 4 || i == 8)) continue
                if (i == 12 || i == 16 || i == 20) continue                  // pontas já arredondadas pelo afinamento do osso
                drawSphere(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], jointR[i] * 1.06f)
            }

            // pontas verdes quando pinçando
            if (handPin[h]) {
                GLES20.glUniform4fv(hColor, 1, cPinch, 0)
                drawSphere(p[12], p[13], p[14], jointR[4] * 1.25f)
                drawSphere(p[24], p[25], p[26], jointR[8] * 1.25f)
            }
            // bolinha = cursor: o objeto que ela cobre (visto do seu olho) é o selecionado
            if (st.handLasers) {
                // a bolinha fica SEMPRE no meio da pinça real (mesmos dados que desenham os dedos)
                val tx = (p[12] + p[24]) / 2
                val ty = (p[13] + p[25]) / 2
                val tz = (p[14] + p[26]) / 2
                // suavização da bolinha (barra "Suavização da bolinha"): 0 = cola no meio dos dedos; 1 = bem suave
                val bnow = System.nanoTime()
                val bdt = if (lastBallNs == 0L || bnow - lastBallNs > 200_000_000L) 0.016f else ((bnow - lastBallNs) / 1e9f).coerceIn(0.001f, 0.1f)
                if (h == 1 || !handOk[1]) lastBallNs = bnow
                val tau = st.pinchSmooth * 0.18f
                val ba = if (tau < 0.003f || !ballInit[h]) 1f else 1f - exp(-bdt / tau)
                val bs = ballS[h]
                if (!ballInit[h]) { bs[0] = tx; bs[1] = ty; bs[2] = tz; ballInit[h] = true }
                else { bs[0] += (tx - bs[0]) * ba; bs[1] += (ty - bs[1]) * ba; bs[2] += (tz - bs[2]) * ba }
                val bx = bs[0]; val by = bs[1]; val bz = bs[2]
                // a bolinha vai de branca a verde conforme a pinça se fecha (feedback de quanto falta)
                val t = if (handPin[h]) 1f else ((0.95f - handRat[h]) / (0.95f - 0.40f)).coerceIn(0f, 1f)
                ballColor[0] = 1f - 0.7f * t
                ballColor[1] = 1f
                ballColor[2] = 1f - 0.45f * t
                ballColor[3] = 1f
                GLES20.glUniform4fv(hColor, 1, ballColor, 0)
                drawSphere(bx, by, bz, 0.0058f + 0.0014f * t)          // bolinha menor (antes ~1 cm)
            }
        }
    }

    // ---------- janela do navegador (curva ou plana) ----------
    private val xfA = FloatArray(9)
    private val xfB = FloatArray(3)
    private val xfM = FloatArray(9)
    private fun drawSurf(p: SurfProg, s0: Float, z0: Float, w: Float, h: Float, depth: Float) {
        GLES20.glUniformMatrix4fv(p.uVP, 1, false, vp, 0)
        GLES20.glUniform4f(p.uRect, s0, z0, w, h)
        GLES20.glUniform3f(p.uOrg, st.wx, st.distance, st.curveK())
        st.screenXf(xfA, xfB)                                            // órbita + giro da tela (3 eixos)
        for (r in 0..2) for (c in 0..2) xfM[c * 3 + r] = xfA[r * 3 + c]   // row-major -> column-major do GL
        GLES20.glUniformMatrix3fv(p.uSA, 1, false, xfM, 0)
        GLES20.glUniform3f(p.uSB, xfB[0], xfB[1], xfB[2])
        GLES20.glUniform1f(p.uDepth, depth)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, strip)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (segs + 1) * 2)
    }

    private fun drawBrowserWindow() {
        val g = st.win

        val hidden = st.screenHidden
        if (!hidden) {
        // moldura
        GLES20.glUseProgram(surfColor.p)
        GLES20.glUniform4fv(surfColor.uColor, 1, cFrame, 0)
        drawSurf(surfColor, g.cx, g.cz, g.w + 0.08f, g.h + 0.08f, -0.012f)

        // conteúdo
        GLES20.glUseProgram(surfExt.p)
        if (st.sbs && curEye >= 0) {                      // 3D lado a lado: cada olho vê a sua metade do conteúdo
            Matrix.setIdentityM(sbsTex, 0)
            sbsTex[0] = 0.5f
            sbsTex[12] = 0.5f * curEye
            Matrix.multiplyMM(tmpTexMat, 0, texMat, 0, sbsTex, 0)
            GLES20.glUniformMatrix4fv(surfExt.uTex, 1, false, tmpTexMat, 0)
        } else {
            GLES20.glUniformMatrix4fv(surfExt.uTex, 1, false, texMat, 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(surfExt.uTexture, 0)
        drawSurf(surfExt, g.cx, g.cz, g.w, g.h, 0f)
        }   // fim: tela aberta

        // cantos (estilo Quest: dois traços em L, só aparecem quando você aponta para a tela) + barra de mover
        GLES20.glUseProgram(surfColor.p)
        val hs = st.handle()
        val near = !hidden && (ui.menuOpen || st.hoverKind[0] == Hit.WIN || st.hoverKind[1] == Hit.WIN || st.hoverKind[0] == Hit.CORNER ||
            st.hoverKind[1] == Hit.CORNER || st.hoverKind[0] == Hit.BAR || st.hoverKind[1] == Hit.BAR)
        if (near) for (c in 0..3) {
            val hot = (st.hoverKind[0] == Hit.CORNER && st.hoverIdx[0] == c) || (st.hoverKind[1] == Hit.CORNER && st.hoverIdx[1] == c)
            GLES20.glUniform4fv(surfColor.uColor, 1, if (hot) cHandleHot else cHandle, 0)
            val sx = if (c % 2 == 0) -1f else 1f; val sz = if (c < 2) 1f else -1f
            val cs = g.cx + sx * (g.w / 2f); val cz = g.cz + sz * (g.h / 2f)
            val t = hs * (if (hot) 0.42f else 0.30f); val l = hs * 1.7f
            drawSurf(surfColor, cs + sx * (t - l) / 2f, cz + sz * t / 2f, l + t, t, 0.006f)           // braço horizontal do "L"
            drawSurf(surfColor, cs + sx * t / 2f, cz + sz * (t - l) / 2f, t, l + t, 0.006f)           // braço vertical do "L"
        }
        if (!hidden) {
            val b = st.bar
            val barHot = st.hoverKind[0] == Hit.BAR || st.hoverKind[1] == Hit.BAR
            GLES20.glUniform4fv(surfColor.uColor, 1, if (barHot) cHandleHot else cHandle, 0)
            drawSurf(surfColor, b.cx, b.cz, b.w, b.h, 0.006f)
        }

        // barra de ferramentas
        GLES20.glUseProgram(surfUi.p)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ui.toolbar.texId)
        GLES20.glUniform1i(surfUi.uTexture, 0)
        drawSurf(surfUi, st.tb.cx, st.tb.cz, st.tb.w, st.tb.h, 0.002f)

        // painel de diagnóstico das mãos (acima da janela)
        if (st.handsOn && ui.menuOpen && !hidden) {              // textos de diagnóstico: só com o Menu aberto
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ui.status.texId)
            val sw = minOf(st.win.w, 3.2f)
            val sh = sw * 120f / 1400f
            drawSurf(surfUi, st.wx, st.win.cz + st.win.h / 2f + sh / 2f + 0.05f, sw, sh, 0.002f)
        }
    }

    // ---------- lasers ----------
    private fun drawLasers() {
        GLES20.glUseProgram(roomProg)
        for (i in 0..1) {
            val left = i == 0
            val pressed = st.pressed[i]
            val ox = st.lox[i]
            val oy = st.loy[i]
            val oz = st.loz[i]
            val tx = st.hitX[i]
            val ty = st.hitY[i]
            val tz = st.hitZ[i]

            val color = if (pressed) cPressed else if (left) cLaserL else cLaserR
            if (!st.laserHand[i]) drawBeam(ox, oy, oz, tx, ty, tz, if (pressed) 0.012f else 0.006f, color)

            if (!st.laserHand[i]) {
                val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                drawBeam(ox - dx / l * 0.18f, oy - dy / l * 0.18f, oz - dz / l * 0.18f,
                    ox, oy, oz, 0.03f, cWand)
            }

            val ds = if (st.laserHand[i]) (if (pressed) 0.05f else 0.03f) else (if (pressed) 0.07f else 0.045f)
            if (st.hoverKind[i] == Hit.KB || st.hoverKind[i] == Hit.KBBAR) {
                // ponto sobre o teclado (plano, acompanha a órbita do teclado)
                val kr = st.kbRot
                basis(kr[0], kr[3], kr[6], kr[2], kr[5], kr[8], -kr[1], -kr[4], -kr[7], tx + kr[1] * 0.01f, ty + kr[4] * 0.01f, tz + kr[7] * 0.01f, ds * 0.6f, ds * 0.6f)
                quadRoom(color, 8f, 1f, 1f)
            } else {
                // ponto sobre a superfície (curva ou plana)
                GLES20.glUseProgram(surfColor.p)
                GLES20.glUniform4fv(surfColor.uColor, 1, color, 0)
                drawSurf(surfColor, st.ax[i], st.az[i], ds, ds, 0.012f)
                GLES20.glUseProgram(roomProg)
            }
        }
    }

    private fun drawBeam(
        ox: Float, oy: Float, oz: Float,
        tx: Float, ty: Float, tz: Float,
        w: Float, color: FloatArray
    ) {
        var dx = tx - ox; var dy = ty - oy; var dz = tz - oz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        dx /= len; dy /= len; dz /= len
        var ax = dy; var ay = -dx
        val al = sqrt(ax * ax + ay * ay)
        if (al < 1e-4f) { ax = 1f; ay = 0f } else { ax /= al; ay /= al }
        val bx = -dz * ay
        val by = dz * ax
        val bz = dx * ay - dy * ax
        val mx = (ox + tx) / 2; val my = (oy + ty) / 2; val mz = (oz + tz) / 2
        basis(ax, ay, 0f, dx, dy, dz, bx, by, bz, mx, my, mz, w, len)
        quadRoom(color, 8f, 1f, 1f)
        basis(bx, by, bz, dx, dy, dz, ax, ay, 0f, mx, my, mz, w, len)
        quadRoom(color, 8f, 1f, 1f)
    }

    // ---------- sala ----------
    private val cWood = floatArrayOf(1f, 1f, 1f, 1f)
    private val cWall = floatArrayOf(0.17f, 0.19f, 0.30f, 1f)
    private val cWall2 = floatArrayOf(0.22f, 0.17f, 0.26f, 1f)
    private val cWhite = floatArrayOf(1f, 1f, 1f, 1f)
    private val cGlow = floatArrayOf(0.25f, 0.55f, 1f, 0.45f)
    private val cDark = floatArrayOf(0.06f, 0.06f, 0.08f, 1f)
    private val cWindowFrame = floatArrayOf(0.03f, 0.03f, 0.04f, 1f)
    private val cSofa = floatArrayOf(0.30f, 0.13f, 0.14f, 1f)
    private val cSpeaker = floatArrayOf(0.10f, 0.10f, 0.12f, 1f)
    private val cConsole = floatArrayOf(0.20f, 0.12f, 0.08f, 1f)
    private val cLampHead = floatArrayOf(1f, 0.85f, 0.55f, 1f)
    private val cPole = floatArrayOf(0.12f, 0.12f, 0.14f, 1f)
    private val tmpC = FloatArray(4)

    private fun shade(c: FloatArray, f: Float): FloatArray {
        tmpC[0] = c[0] * f; tmpC[1] = c[1] * f; tmpC[2] = c[2] * f; tmpC[3] = c[3]
        return tmpC
    }

    /** Caixa com 5 faces (sem fundo). */
    private fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, c: FloatArray, mode: Float = 0f) {
        basis(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, cx, cy, cz + sz / 2, sx, sy)
        quadRoom(shade(c, 1.0f), mode, sx, sy)
        basis(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, cx, cy - sy / 2, cz, sx, sz)
        quadRoom(shade(c, 0.8f), mode, sx, sz)
        basis(1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, cx, cy + sy / 2, cz, sx, sz)
        quadRoom(shade(c, 0.55f), mode, sx, sz)
        basis(0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, cx - sx / 2, cy, cz, sy, sz)
        quadRoom(shade(c, 0.65f), mode, sy, sz)
        basis(0f, 1f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, cx + sx / 2, cy, cz, sy, sz)
        quadRoom(shade(c, 0.65f), mode, sy, sz)
    }

    private fun drawRoom() {
        GLES20.glUseProgram(roomProg)
        val midZ = (floorZ + ceilZ) / 2
        val hZ = ceilZ - floorZ

        // piso de madeira + tapete
        basis(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, floorZ, 10f, 10f)
        quadRoom(cWood, 1f, 10f, 10f)
        basis(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0.3f, floorZ + 0.006f, 5.4f, 4.2f)
        quadRoom(cWhite, 5f, 5.4f, 4.2f)
        // teto com luminárias embutidas
        basis(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, ceilZ, 10f, 10f)
        quadRoom(cDark, 3f, 10f, 10f)
        // paredes (ripadas, rodapé e luz de teto)
        basis(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 0f, 5f, midZ, 10f, hZ)
        quadRoom(cWall, 2f, 10f, hZ)
        basis(1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, -5f, midZ, 10f, hZ)
        quadRoom(cWall, 2f, 10f, hZ)
        basis(0f, 1f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, 5f, 0f, midZ, 10f, hZ)
        quadRoom(cWall2, 2f, 10f, hZ)
        basis(0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -5f, 0f, midZ, 10f, hZ)
        quadRoom(cWall2, 2f, 10f, hZ)

        // brilho ambiente atrás da tela
        basis(1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, st.wx, 4.97f, st.wz, 9.5f, 5.0f)
        quadRoom(cGlow, 4f, 1f, 1f)

        // janela noturna (parede leste) e estante (parede oeste)
        basis(0f, 1f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, 4.985f, 0.4f, 0.7f, 5.0f, 2.7f)
        quadRoom(cWindowFrame, 8f, 1f, 1f)
        basis(0f, 1f, 0f, 0f, 0f, 1f, -1f, 0f, 0f, 4.97f, 0.4f, 0.7f, 4.8f, 2.5f)
        quadRoom(cWhite, 6f, 4.8f, 2.5f)
        basis(0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -4.97f, 0.2f, 0.4f, 7.0f, 3.6f)
        quadRoom(cWhite, 7f, 7.0f, 3.6f)

        // móveis
        box(0f, 4.55f, floorZ, 5.2f, 0.7f, 0.75f, cConsole)             // rack da TV
        box(-3.4f, 4.5f, floorZ, 0.55f, 0.5f, 2.3f, cSpeaker)           // caixas de som
        box(3.4f, 4.5f, floorZ, 0.55f, 0.5f, 2.3f, cSpeaker)
        box(0f, -3.7f, floorZ, 3.2f, 1.0f, 0.55f, cSofa)                // sofá atrás
        box(0f, -4.15f, floorZ + 0.55f, 3.2f, 0.25f, 0.75f, cSofa)
        box(-1.55f, -3.7f, floorZ + 0.55f, 0.25f, 1.0f, 0.3f, cSofa)
        box(1.55f, -3.7f, floorZ + 0.55f, 0.25f, 1.0f, 0.3f, cSofa)
        // abajures de piso
        for (sx in floatArrayOf(-4.3f, 4.3f)) {
            box(sx, 3.6f, floorZ, 0.07f, 0.07f, 2.1f, cPole)
            box(sx, 3.6f, floorZ + 2.1f, 0.38f, 0.38f, 0.38f, cLampHead, 8f)
        }
    }

    private fun quadRoom(c: FloatArray, mode: Float, sx: Float, sy: Float) {
        GLES20.glUniformMatrix4fv(rVP, 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(rModel, 1, false, model, 0)
        GLES20.glUniform4fv(rColor, 1, c, 0)
        GLES20.glUniform1f(rMode, mode)
        GLES20.glUniform2f(rScale, sx, sy)
        GLES20.glUniform4f(rLight, st.wx, st.distance - 0.5f, st.wz, 1.7f)
        GLES20.glUniform1f(rLite, if (st.lite) 1f else 0f)
        GLES20.glEnableVertexAttribArray(rPos)
        GLES20.glVertexAttribPointer(rPos, 3, GLES20.GL_FLOAT, false, 12, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun basis(
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        nx: Float, ny: Float, nz: Float,
        tx: Float, ty: Float, tz: Float,
        su: Float, sv: Float
    ) {
        model[0] = ux * su; model[1] = uy * su; model[2] = uz * su; model[3] = 0f
        model[4] = vx * sv; model[5] = vy * sv; model[6] = vz * sv; model[7] = 0f
        model[8] = nx; model[9] = ny; model[10] = nz; model[11] = 0f
        model[12] = tx; model[13] = ty; model[14] = tz; model[15] = 1f
    }

    // ---------- shaders ----------
    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        // ----- sala procedural -----
        private const val ROOM_VS = """
            uniform mat4 uVP;
            uniform mat4 uModel;
            attribute vec4 aPos;
            varying vec2 vPos;
            varying vec3 vW;
            void main() {
                vPos = aPos.xy;
                vec4 w = uModel * aPos;
                vW = w.xyz;
                gl_Position = uVP * w;
            }
        """

        private const val ROOM_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec4 uColor;
            uniform float uMode;
            uniform vec2 uScale;
            uniform vec4 uLight;
            uniform float uLite;
            varying vec2 vPos;
            varying vec3 vW;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

            void main() {
                vec2 m = (vPos + 0.5) * uScale;
                vec3 col = uColor.rgb;
                float alpha = uColor.a;
                int mode = int(uMode + 0.5);

                if (mode == 8) { gl_FragColor = uColor; return; }

                if (mode == 4) {                       // brilho radial (ambilight)
                    float r = length(vPos * 2.0);
                    float a = pow(clamp(1.0 - r, 0.0, 1.0), 2.0) * uColor.a;
                    gl_FragColor = vec4(uColor.rgb, a);
                    return;
                }

                if (uLite > 0.5) {                     // modo leve: cores lisas, sem padrões procedurais
                    vec3 b = uColor.rgb;
                    if (mode == 1) b = vec3(0.30, 0.18, 0.10);
                    else if (mode == 5) b = vec3(0.16, 0.20, 0.38);
                    else if (mode == 6) { gl_FragColor = vec4(0.04, 0.06, 0.16, 1.0); return; }
                    else if (mode == 7) b = vec3(0.22, 0.14, 0.09);
                    else if (mode == 3) b = vec3(0.07, 0.07, 0.09);
                    vec3 dv0 = vW - uLight.xyz;
                    float lt = uLight.w / (1.0 + 0.18 * dot(dv0, dv0));
                    gl_FragColor = vec4(b * (0.42 + lt * vec3(0.75, 0.90, 1.10)), alpha);
                    return;
                }

                if (mode == 6) {                       // janela noturna com cidade
                    vec3 sky = mix(vec3(0.10, 0.12, 0.30), vec3(0.01, 0.02, 0.08), vPos.y + 0.5);
                    float stars = step(0.993, hash(floor(m * 45.0))) * step(0.0, vPos.y);
                    sky += vec3(stars);
                    float col_i = floor(m.x * 3.0);
                    float bh = -0.5 + 0.12 + 0.32 * hash(vec2(col_i, 3.0));
                    if (vPos.y < bh) {
                        vec2 cell = floor(m * vec2(14.0, 9.0));
                        vec2 f = fract(m * vec2(14.0, 9.0));
                        float lit = step(0.55, hash(cell)) * step(0.2, f.x) * step(f.x, 0.8) * step(0.25, f.y) * step(f.y, 0.75);
                        sky = vec3(0.015, 0.015, 0.04) + lit * vec3(1.0, 0.82, 0.45);
                    }
                    float d = abs(fract(vPos.x * 3.0) - 0.5);
                    if (d > 0.485 || abs(vPos.y) < 0.012) sky = vec3(0.02);
                    gl_FragColor = vec4(sky, 1.0);
                    return;
                }

                if (mode == 1) {                       // piso de madeira
                    float pw = 0.2;
                    float pi = floor(m.x / pw);
                    float off = hash(vec2(pi, 1.0)) * 1.7;
                    float seg = floor((m.y + off) / 1.6);
                    float h = hash(vec2(pi, seg));
                    col = mix(vec3(0.20, 0.11, 0.06), vec3(0.38, 0.23, 0.12), h);
                    float grain = 0.5 + 0.5 * sin(m.x * 60.0 + h * 20.0 + sin(m.y * 4.0 + h * 9.0) * 2.0);
                    col *= 0.88 + 0.22 * grain;
                    float seam = step(fract(m.x / pw), 0.02);
                    float joint = step(fract((m.y + off) / 1.6), 0.005);
                    col *= 1.0 - 0.55 * max(seam, joint);
                } else if (mode == 5) {                // tapete
                    vec2 q = abs(vPos) * 2.0;
                    float edge = max(q.x, q.y);
                    float border = step(0.90, edge);
                    float border2 = step(0.82, edge) - border;
                    col = mix(vec3(0.10, 0.15, 0.32), vec3(0.30, 0.12, 0.15), border2);
                    float dm = q.x + q.y;
                    float pat = step(0.5, fract(dm * 4.0));
                    col = mix(col, col * 1.45, pat * (1.0 - border - border2));
                    col = mix(col, vec3(0.78, 0.62, 0.36), border);
                } else if (mode == 3) {                // teto com luminárias
                    vec2 f = fract(m / 2.5);
                    float inside = step(0.3, f.x) * step(f.x, 0.7) * step(0.3, f.y) * step(f.y, 0.7);
                    if (inside > 0.5) { gl_FragColor = vec4(1.0, 0.93, 0.80, 1.0); return; }
                    col = vec3(0.05, 0.05, 0.07);
                } else if (mode == 2) {                // parede ripada + rodapé + luz de teto
                    float f = fract(m.x / 0.16);
                    float slat = smoothstep(0.0, 0.2, f) * smoothstep(1.0, 0.8, f);
                    col *= 0.55 + 0.6 * slat;
                    if (vW.z < -1.8) col = vec3(0.05, 0.05, 0.07);
                    if (vW.z > 2.86) {
                        float wv = 0.65 + 0.35 * sin(vW.x * 1.2 + vW.y * 1.2);
                        gl_FragColor = vec4(vec3(0.30, 0.50, 1.0) * wv, 1.0);
                        return;
                    }
                } else if (mode == 7) {                // estante com livros
                    float rows = 6.0;
                    float ry = (vPos.y + 0.5) * rows;
                    float row = floor(ry);
                    float fy = fract(ry);
                    float bx = m.x * 7.0;
                    float bi = floor(bx);
                    float bw = fract(bx);
                    float h = hash(vec2(bi, row));
                    vec3 book = 0.30 + 0.65 * vec3(hash(vec2(bi, row + 1.0)), hash(vec2(bi, row + 2.0)), hash(vec2(bi, row + 3.0)));
                    float bookH = 0.50 + 0.42 * h;
                    col = vec3(0.04, 0.03, 0.025);
                    if (fy > 0.07 && fy < 0.07 + bookH * 0.9 && bw > 0.08 && bw < 0.92) col = book;
                    if (fy < 0.07) col = vec3(0.30, 0.19, 0.10);
                }

                // luz da tela iluminando a sala
                vec3 dv = vW - uLight.xyz;
                float lit = uLight.w / (1.0 + 0.18 * dot(dv, dv));
                col = col * (0.42 + lit * vec3(0.75, 0.90, 1.10));
                gl_FragColor = vec4(col, alpha);
            }
        """

        // ----- superfície curvável (janela, barra, cantos) -----
        private const val SURF_VS = """
            uniform mat4 uVP;
            uniform vec4 uRect;    // x: s do centro, y: z do centro, z: largura, w: altura
            uniform vec3 uOrg;     // x: s da origem (wx), y: distância, z: curvatura K
            uniform mat3 uSA;      // giro/órbita da tela
            uniform vec3 uSB;
            uniform float uDepth;  // deslocamento para o usuário
            uniform mat4 uTex;
            attribute vec2 aPos;
            varying vec2 vUV;
            varying vec2 vUI;
            void main() {
                float s = uRect.x + aPos.x * uRect.z;
                float z = uRect.y + aPos.y * uRect.w;
                float k = uOrg.z;
                vec3 p;
                vec3 n;
                if (k < 0.0001) {
                    p = vec3(s, uOrg.y, z);
                    n = vec3(0.0, -1.0, 0.0);
                } else {
                    float phi = (s - uOrg.x) * k;
                    p = vec3(uOrg.x + sin(phi) / k, uOrg.y - (1.0 - cos(phi)) / k, z);
                    n = vec3(-sin(phi), -cos(phi), 0.0);
                }
                p += n * uDepth;
                p = uSA * p + uSB;
                vUV = (uTex * vec4(aPos.x + 0.5, aPos.y + 0.5, 0.0, 1.0)).xy;
                vUI = vec2(aPos.x + 0.5, 0.5 - aPos.y);
                gl_Position = uVP * vec4(p, 1.0);
            }
        """
        private const val EXT_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUV;
            varying vec2 vUI;
            void main() { gl_FragColor = texture2D(uTexture, vUV); }
        """
        private const val SURF_UI_FS = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUV;
            varying vec2 vUI;
            void main() { gl_FragColor = texture2D(uTexture, vUI); }
        """
        private const val SURF_COLOR_FS = """
            precision mediump float;
            uniform vec4 uColor;
            varying vec2 vUV;
            varying vec2 vUI;
            void main() { gl_FragColor = uColor; }
        """

        // ----- fundo da câmera (modo passthrough) -----
        private const val BG_VS = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """
        private const val BG_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUV;
            void main() { gl_FragColor = texture2D(uTexture, vUV); }
        """

        // ----- mãos -----
        private const val HAND_VS = """
            uniform mat4 uVP;
            uniform mat4 uModel;
            uniform float uTaper;
            attribute vec3 aPos;
            attribute vec3 aNrm;
            varying vec3 vN;
            varying vec3 vW;
            void main() {
                float k = mix(1.0, uTaper, clamp(aPos.z, 0.0, 1.0));
                vec4 w = uModel * vec4(aPos.xy * k, aPos.z, 1.0);
                vW = w.xyz;
                vN = (uModel * vec4(aNrm, 0.0)).xyz;
                gl_Position = uVP * w;
            }
        """
        private const val HAND_FS = """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uEye;
            varying vec3 vN;
            varying vec3 vW;
            void main() {
                vec3 n = normalize(vN);
                vec3 v = normalize(uEye - vW);
                if (dot(n, v) < 0.0) n = -n;
                vec3 l = normalize(vec3(0.3, -0.5, 0.8));
                float diff = 0.42 + 0.58 * max(dot(n, l), 0.0);
                float rim = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 2.5);
                vec3 col = uColor.rgb * diff + vec3(0.35, 0.70, 1.0) * rim * 0.65;
                gl_FragColor = vec4(col, uColor.a);
            }
        """

        // ----- painel 2D plano (teclado) -----
        private const val UI_VS = """
            uniform mat4 uMVP;
            attribute vec4 aPos;
            varying vec2 vUV;
            void main() {
                vUV = vec2(aPos.x + 0.5, 0.5 - aPos.y);
                gl_Position = uMVP * aPos;
            }
        """
        private const val UI_FS = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUV;
            void main() { gl_FragColor = texture2D(uTexture, vUV); }
        """
    }
}
