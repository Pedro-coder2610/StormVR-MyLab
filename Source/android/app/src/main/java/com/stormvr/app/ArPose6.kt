package com.stormvr.app

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 6DoF do CONTROLE (modo controle): a posicao do celular vem do ARCore (camera + IMU) e e enviada ao driver junto com a orientacao.
 * A camera NAO e desenhada (GLSurfaceView de 1x1 px so para o ARCore ter um contexto GL). So funciona com o app na tela.
 *
 * O ARCore tem o proprio "norte" (arbitrario): o referencial e fixado no RECENTRAR (topo do celular apontando para a frente), igual ao yaw do giroscopio.
 * Saida (CtlState): deslocamento desde o recentrar em metros: frente / esquerda / cima (com mapeamento, inversao e escala escolhidos no app).
 */
class ArPose6(private val act: Activity, private val ori: PhoneOrientation, private val st: CtlState) {
    @Volatile var status = "6DoF: desligado"
        private set
    private var session: Session? = null
    private var gl: GLSurfaceView? = null
    private var container: ViewGroup? = null
    @Volatile private var recenterReq = true
    private var ref = FloatArray(3)
    @Volatile private var running = false

    fun recenter() { recenterReq = true; refSet = false }

    /** Chame com a permissao de CAMERA ja concedida. Devolve false (e explica em [status]) se o ARCore nao estiver disponivel. */
    fun start(parent: ViewGroup): Boolean {
        if (running) return true
        try {
            when (ArCoreApk.getInstance().requestInstall(act, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> {}
                else -> { status = "6DoF: instale/atualize \"Google Play Services para RA\" e tente de novo"; return false }
            }
            val s = Session(act)
            val cfg = Config(s)
            cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            cfg.focusMode = Config.FocusMode.AUTO
            cfg.lightEstimationMode = Config.LightEstimationMode.DISABLED
            cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
            s.configure(cfg)
            session = s
            val v = GLSurfaceView(act)
            v.setEGLContextClientVersion(2)
            v.preserveEGLContextOnPause = true
            v.setRenderer(renderer)
            v.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            container = parent
            parent.addView(v, FrameLayout.LayoutParams(1, 1))
            gl = v
            s.resume()
            running = true
            recenterReq = true; refSet = false; smInit = false
            st.hasPos = false
            status = "6DoF: iniciando (mova o celular devagar para o ARCore achar o ambiente)"
            return true
        } catch (t: Throwable) {
            status = "6DoF indisponível: " + (t.javaClass.simpleName)
            stop()
            return false
        }
    }

    fun pause() { if (running) { st.hasPos = false; try { gl?.onPause(); session?.pause() } catch (_: Throwable) {} } }       // em segundo plano o ARCore para: volta ao 3DoF
    fun resume() { if (running) { try { session?.resume(); gl?.onResume() } catch (_: Throwable) {} } }

    fun stop() {
        running = false
        st.hasPos = false
        try { gl?.onPause() } catch (_: Throwable) {}
        try { container?.removeView(gl) } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        gl = null; session = null; container = null
        status = "6DoF: desligado"
    }

    private val renderer = object : GLSurfaceView.Renderer {
        private var w = 1
        private var h = 1
        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            try { session?.setCameraTextureName(t[0]) } catch (_: Throwable) {}
        }
        override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) { w = width; h = height; GLES20.glViewport(0, 0, width, height) }
        override fun onDrawFrame(g: GL10?) {
            val s = session ?: return
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            try {
                @Suppress("DEPRECATION")
                s.setDisplayGeometry(act.windowManager.defaultDisplay.rotation, w, h)
                val cam = s.update().camera
                if (cam.trackingState == TrackingState.TRACKING) {
                    process(cam.displayOrientedPose)
                    status = "6DoF: rastreando"
                } else {
                    status = "6DoF: perdeu o rastreamento (mova o celular devagar, com luz)"
                }
            } catch (_: Throwable) {}
        }
    }

    private var refHead = 0.0
    private var refSet = false
    private val tmp = FloatArray(3)
    private val sm = FloatArray(3)
    private var smInit = false

    /**
     * Referencial FIXADO NO RECENTRAR: com o celular na vertical e o TOPO apontando para a frente (a pose de uso do controle), guardamos a posição e o
     * rumo da ponta no mundo do ARCore. Dali em diante o deslocamento é medido nesse referencial (frente = rumo do recentrar, esquerda, cima).
     * Isso não depende do giroscópio nem de estimar desvio de rumo: o giroscópio também é zerado no mesmo "Recentrar", então "frente" é a mesma nos dois.
     */
    private fun process(pose: com.google.ar.core.Pose) {
        val t = pose.translation
        val f = pose.rotateVector(floatArrayOf(0f, 1f, 0f))            // borda de CIMA do celular (a ponta) no mundo do ARCore (y = cima)
        val fx = f[0]; val fy = -f[2]                                  // ARCore (x, y cima, z) -> ENU-like (X = x, Y = -z, Z = y)
        val hl = hypot(fx, fy)
        val px = t[0]; val py = -t[2]; val pz = t[1]
        if (recenterReq || !refSet) {
            if (hl < 0.35f) { status = "6DoF: aponte o TOPO do celular para a frente (na horizontal) e toque em Recentrar"; return }
            ref = floatArrayOf(px, py, pz); refHead = atan2(fy.toDouble(), fx.toDouble()); refSet = true; recenterReq = false; smInit = false
        }
        val dx = px - ref[0]; val dy = py - ref[1]; val du = pz - ref[2]
        val ch = cos(refHead); val sh = sin(refHead)
        tmp[0] = (dx * ch + dy * sh).toFloat()                         // frente
        tmp[1] = (-dx * sh + dy * ch).toFloat()                        // esquerda
        tmp[2] = du                                                    // cima
        // suavização adaptativa: parado = firme, em movimento = quase sem atraso
        if (!smInit) { for (i in 0..2) sm[i] = tmp[i]; smInit = true }
        else {
            val err = hypot(hypot((tmp[0] - sm[0]).toDouble(), (tmp[1] - sm[1]).toDouble()), (tmp[2] - sm[2]).toDouble())
            val a = (0.25 + err * 18.0).coerceIn(0.25, 1.0).toFloat()
            for (i in 0..2) sm[i] += (tmp[i] - sm[i]) * a
        }
        val k = st.pScale
        st.posF = sm[st.pSrc[0].coerceIn(0, 2)] * k * (if (st.pInv[0]) -1f else 1f)
        st.posL = sm[st.pSrc[1].coerceIn(0, 2)] * k * (if (st.pInv[1]) -1f else 1f)
        st.posU = sm[st.pSrc[2].coerceIn(0, 2)] * k * (if (st.pInv[2]) -1f else 1f)
        st.hasPos = true
    }
}
