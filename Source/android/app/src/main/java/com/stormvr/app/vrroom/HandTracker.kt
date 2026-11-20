package com.stormvr.app.vrroom

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Resultado publicado pelo tracker. Posições no referencial da cena (metros): 21 pontos x (x,y,z). */
class HandsFrame(
    val valid: BooleanArray,
    val pts: Array<FloatArray>,
    val vel: Array<FloatArray>,
    val pinch: BooleanArray,
    val ratio: FloatArray,          // distância polegar-indicador / tamanho da mão (contínuo)
    val closing: BooleanArray,      // dedos se fechando rápido (pinça a caminho)
    val latNs: Long,                // atraso entre o envio do quadro e a publicação (conversão + detecção + fila)
    val arrivalNs: Long
)

/** Filtro One Euro: tira o tremor quando a mão está parada e quase não atrasa quando ela se move. */
class OneEuro(private val n: Int, var minCutoff: Float, private val beta: Float, private val dCutoff: Float) {
    private val xPrev = FloatArray(n)
    private val dxPrev = FloatArray(n)
    private var tPrev = 0f
    private var init = false

    fun reset() { init = false }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun apply(x: FloatArray, t: Float) {
        if (!init) {
            System.arraycopy(x, 0, xPrev, 0, n)
            java.util.Arrays.fill(dxPrev, 0f)
            tPrev = t
            init = true
            return
        }
        val dt = max(t - tPrev, 1e-3f)
        tPrev = t
        val aD = alpha(dCutoff, dt)
        for (i in 0 until n) {
            val dx = (x[i] - xPrev[i]) / dt
            val dxh = aD * dx + (1f - aD) * dxPrev[i]
            dxPrev[i] = dxh
            val a = alpha(minCutoff + beta * abs(dxh), dt)
            val xh = a * x[i] + (1f - a) * xPrev[i]
            xPrev[i] = xh
            x[i] = xh
        }
    }
}

/**
 * Rastreamento de mãos com MediaPipe Hand Landmarker.
 * A imagem vem da câmera do próprio ARCore (a câmera não pode ser aberta duas vezes).
 */
class HandTracker(
    private val ctx: Context,
    private val st: VrState,
    private val onMessage: (String) -> Unit
) {
    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

        /** true = tenta a GPU (bem mais rápido). Se falhar, cai sozinho para a CPU. */
        private const val USE_GPU = true

        /** Ajuste fino: 1.0 = mão de tamanho médio. Se suas mãos parecerem longe/perto demais, mude aqui. */
        private const val HAND_SIZE_SCALE = 1.0f

        // pinça: relação (distância polegar-indicador) / (tamanho da mão)
        private const val PINCH_ON = 0.40f
        // solta quando a razão passa de (mínimo atingido na pinça + folga), limitado a esta faixa: abre-se pouco
        private const val PINCH_REL_MIN = 0.46f
        private const val PINCH_REL_MAX = 0.58f
    }

    /** Dados de um quadro da câmera (copiados na GL thread). Reaproveitado: só há 1 job por vez. */
    class Job {
        var y = ByteArray(0)
        var u = ByteArray(0)
        var v = ByteArray(0)
        var w = 0
        var h = 0
        var yRow = 0
        var uvRow = 0
        var uvPix = 0
        var rot = 0                      // rotação horária para ficar "em pé" (0/90/180/270)
        val pose = FloatArray(16)        // matriz olho -> cena no instante do quadro
        var fx = 0f; var fy = 0f; var cx = 0f; var cy = 0f
        var tsNs = 0L
        var submitNs = 0L

        fun copyFrom(img: Image) {
            w = img.width
            h = img.height
            val p = img.planes
            yRow = p[0].rowStride
            uvRow = p[1].rowStride
            uvPix = p[1].pixelStride
            y = fill(p[0].buffer, y)
            u = fill(p[1].buffer, u)
            v = fill(p[2].buffer, v)
        }

        private fun fill(b: ByteBuffer, arr: ByteArray): ByteArray {
            b.position(0)
            val n = b.remaining()
            val a = if (arr.size >= n) arr else ByteArray(n)
            b.get(a, 0, n)
            return a
        }
    }

    // fila de 1 posição ("último quadro vence"): enquanto um quadro é processado, o mais novo já fica esperando,
    // então o rastreador nunca fica ocioso aguardando a câmera (era isso que limitava a ~20 fps)
    private val buffers = arrayOf(Job(), Job())
    private val qLock = Any()
    private var proc = -1
    private var pend = -1

    // diagnóstico (mostrado no painel dentro do VR)
    @Volatile var stage = "aguardando"
    @Volatile var delegateName = "?"
    @Volatile var submitted = 0
    @Volatile var processed = 0
    @Volatile var lastMs = 0L
    @Volatile var lastHands = 0
    @Volatile var lastError = ""
    @Volatile var procFps = 0
    @Volatile var camFps = 0
    private var subCnt = 0
    private var subT = 0L
    @Volatile private var phase = ""
    private var fpsCnt = 0
    private var fpsT = 0L

    fun info(): String =
        "modelo: $stage [$delegateName${if (st.lite || autoHalf) " ½res" else ""}] | mãos: $lastHands/$builtHands | ${procFps}/${camFps} fps | ${lastMs} ms | enviados: $submitted" +
            (if (lastError.isNotEmpty()) " | erro: $lastError" else "")

    @Volatile var ready = false
    @Volatile var busy = true
    @Volatile var latest: HandsFrame? = null

    private val sem = java.util.concurrent.Semaphore(0)
    @Volatile private var errorShown = false
    @Volatile private var running = false
    private var thread: Thread? = null
    private var landmarker: HandLandmarker? = null
    private var usingGpu = false
    @Volatile var wantHands = 1            // 1 = só uma mão (bem mais leve), 2 = duas mãos
    private var builtHands = 0
    private var switchTo: Delegate? = null
    private var benchPhase = 0
    private var benchSum = 0L
    private var benchN = 0
    private var avg0 = 0L
    @Volatile private var autoHalf = false
    private val offCount = IntArray(2)
    private val pinchMin = FloatArray(2)
    private val conf = IntArray(2)
    private var modelBuf: ByteBuffer? = null
    private var fromAssets = false

    private var argb = IntArray(0)
    private var bmp: Bitmap? = null
    private var lastTsMs = 0L

    // estado por mão (0 = esquerda, 1 = direita)
    private val filters = Array(2) { OneEuro(63, 2.2f, 28f, 1.0f) }
    private val priors = Array(2) { HandPrior() }        // anatomia: mantém o tamanho dos dedos e limita a dobra (mão fechando)
    private val active = BooleanArray(2)
    private val pinchState = BooleanArray(2)
    private val prevPts = Array(2) { FloatArray(63) }
    private val prevT = FloatArray(2)
    private val vel = Array(2) { FloatArray(3) }
    private val ratioOut = FloatArray(2) { 1f }
    private var lastSeenNs = 0L
    private val startNs = System.nanoTime()
    private val closingS = BooleanArray(2)
    private val rvF = FloatArray(2)
    private var swapVotes = 3      // >= 0: o rótulo do MediaPipe vem invertido (imagem não espelhada)

    fun start() {
        if (running) return
        sem.drainPermits()
        synchronized(qLock) { pend = -1; proc = -1 }
        errorShown = false
        lastError = ""
        running = true
        ready = false
        busy = true
        thread = Thread({ run() }, "hand-tracker").also { it.start() }
    }

    fun stop() {
        running = false
        sem.release()
        try { thread?.join(1500) } catch (_: Exception) {}
        thread = null
        ready = false
        latest = null
    }

    /**
     * Chamado pela GL thread a cada quadro novo da câmera. [fill] preenche o buffer livre; se já havia um quadro
     * esperando, ele é substituído pelo mais novo.
     */
    fun submit(fill: (Job) -> Unit) {
        synchronized(qLock) {
            val target = if (pend >= 0) pend else if (proc == 0) 1 else 0
            fill(buffers[target])
            pend = target
        }
        submitted++
        subCnt++
        val tc = System.nanoTime()
        if (tc - subT > 1_000_000_000L) { camFps = subCnt; subCnt = 0; subT = tc }
        sem.drainPermits()
        sem.release()
    }

    // ------------------------------------------------------------------ thread
    private fun run() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
        st.handState = 1
        stage = "baixando/carregando…"
        var consecutiveErrors = 0
        try {
            val lm = createLandmarker()
            if (lm == null && st.handSource != 1) {
                st.handState = 0
                running = false
                return
            }
            // modo "controle com bolinhas": não depende do modelo de mãos (se ele não carregou, segue só com o detector de bolinhas)
            if (lm != null) landmarker = lm else builtHands = wantHands
            stage = "pronto"
            for (h in 0..1) { filters[h].reset(); active[h] = false }
            ready = true
            busy = false
            st.handState = 2
            var lastProcNs = 0L
            while (running) {
                // teto de FPS (padrão 30): o laço antes processava TODO quadro da câmera o mais rápido possível, o que esquentava o aparelho
                val gapNs = 1_000_000_000L / st.handMaxFps.coerceIn(10, 60)
                val waitNs = gapNs - (System.nanoTime() - lastProcNs)
                if (waitNs > 1_000_000L) { try { Thread.sleep(waitNs / 1_000_000L) } catch (_: InterruptedException) { break } }
                lastProcNs = System.nanoTime()
                if (builtHands != wantHands || switchTo != null) {
                    busy = true
                    rebuild()
                    busy = false
                    if (!running) break
                }
                if (!sem.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS)) continue
                if (!running) break
                val idx = synchronized(qLock) {
                    val i = pend
                    pend = -1
                    proc = i
                    i
                }
                if (idx < 0) continue
                try {
                    val t0 = System.nanoTime()
                    process(buffers[idx])
                    lastMs = (System.nanoTime() - t0) / 1_000_000L
                    processed++
                    consecutiveErrors = 0
                    fpsCnt++
                    val tn = System.nanoTime()
                    if (tn - fpsT > 1_000_000_000L) { procFps = fpsCnt; fpsCnt = 0; fpsT = tn }
                    benchSum += lastMs
                    benchN++
                    if (benchN >= 40) autoTune(benchSum / benchN)
                } catch (e: Throwable) {
                    lastError = "[$phase] ${e.javaClass.simpleName} ${e.message ?: ""}"
                    Log.e("VRRoom", "erro nas mãos na fase $phase", e)
                    consecutiveErrors++
                    if (usingGpu && consecutiveErrors >= 2) {
                        // a GPU não está funcionando bem: troca para a CPU
                        try { landmarker?.close() } catch (_: Throwable) {}
                        usingGpu = false
                        delegateName = "CPU (fallback)"
                        landmarker = try { buildLandmarker(Delegate.CPU) } catch (t: Throwable) { null }
                        consecutiveErrors = 0
                        if (landmarker == null) running = false
                    }
                    if (!errorShown) {
                        errorShown = true
                        onMessage("Erro no rastreamento de mãos: ${e.javaClass.simpleName} ${e.message ?: ""}")
                    }
                }
                synchronized(qLock) { proc = -1 }
                busy = false
            }
        } catch (e: Throwable) {
            stage = "FALHA: ${e.javaClass.simpleName}"
            lastError = "${e.javaClass.simpleName} ${e.message ?: ""}"
            onMessage("Rastreamento de mãos não iniciou: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            try { landmarker?.close() } catch (_: Exception) {}
            landmarker = null
            ready = false
            running = false
            st.handState = 0
        }
    }

    private fun modelBuffer(): ByteBuffer? {
        val f = File(ctx.filesDir, MODEL_FILE)
        if (!(f.exists() && f.length() > 1_000_000L)) {
            try {
                onMessage("Baixando o modelo de mãos (~8 MB), só na primeira vez…")
                val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (tmp.length() < 1_000_000L) throw RuntimeException("arquivo incompleto")
                tmp.renameTo(f)
            } catch (e: Exception) {
                stage = "FALHA no download"
                lastError = "${e.javaClass.simpleName} ${e.message ?: ""}"
                onMessage("Não consegui baixar o modelo de mãos. Coloque o arquivo hand_landmarker.task em app/src/main/assets/ (veja o LEIA-ME.txt).")
                return null
            }
        }
        FileInputStream(f).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        }
    }

    private fun buildLandmarker(delegate: Delegate): HandLandmarker {
        val bb = BaseOptions.builder()
        if (fromAssets) bb.setModelAssetPath(MODEL_FILE) else bb.setModelAssetBuffer(modelBuf!!)
        val base = bb.setDelegate(delegate).build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setNumHands(wantHands)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.VIDEO)
            .build()
        val lmk = HandLandmarker.createFromOptions(ctx, opts)
        builtHands = wantHands
        return lmk
    }

    /** Recria o landmarker (troca de delegate GPU/CPU ou de 1 para 2 mãos). */
    private fun rebuild() {
        val target = switchTo ?: (if (usingGpu) Delegate.GPU else Delegate.CPU)
        try { landmarker?.close() } catch (_: Throwable) {}
        landmarker = null
        try {
            landmarker = buildLandmarker(target)
            usingGpu = target == Delegate.GPU
            delegateName = if (usingGpu) "GPU" else "CPU"
        } catch (e: Throwable) {
            try {
                landmarker = buildLandmarker(Delegate.CPU)
                usingGpu = false
                delegateName = "CPU"
            } catch (t: Throwable) {
                lastError = "[rebuild] ${t.javaClass.simpleName} ${t.message ?: ""}"
                running = false
            }
        }
        switchTo = null
        for (h in 0..1) { filters[h].reset(); active[h] = false; conf[h] = 0; pinchState[h] = false }
        benchSum = 0
        benchN = 0
    }

    /** Mede a velocidade e escolhe sozinho o melhor delegate (GPU x CPU) e, se preciso, reduz a resolução. */
    private fun autoTune(avg: Long) {
        benchSum = 0
        benchN = 0
        when (benchPhase) {
            0 -> {
                avg0 = avg
                if (avg <= 28 || !USE_GPU) benchPhase = 3
                else { switchTo = if (usingGpu) Delegate.CPU else Delegate.GPU; benchPhase = 1 }
            }
            1 -> {
                benchPhase = 3
                if (avg >= avg0 * 0.85f) switchTo = if (usingGpu) Delegate.CPU else Delegate.GPU   // volta ao anterior
            }
            else -> {
                if (avg > 26 && !autoHalf && !st.lite) autoHalf = true    // ainda pesado: metade da resolução
            }
        }
    }

    private fun createLandmarker(): HandLandmarker? {
        fromAssets = try { ctx.assets.open(MODEL_FILE).close(); true } catch (_: Exception) { false }
        if (!fromAssets) {
            modelBuf = modelBuffer() ?: return null
        }
        return try {
            if (USE_GPU) {
                try {
                    val l = buildLandmarker(Delegate.GPU)
                    usingGpu = true
                    delegateName = "GPU"
                    l
                } catch (_: Throwable) {
                    usingGpu = false
                    delegateName = "CPU"
                    buildLandmarker(Delegate.CPU)
                }
            } else {
                delegateName = "CPU"
                buildLandmarker(Delegate.CPU)
            }
        } catch (e: Throwable) {
            stage = "FALHA ao criar"
            lastError = "${e.javaClass.simpleName} ${e.message ?: ""}"
            onMessage("Falha ao iniciar o rastreamento de mãos: ${e.javaClass.simpleName} ${e.message ?: ""}")
            null
        }
    }

    // ------------------------------------------------------------------ processamento
    private fun c8(x: Int) = if (x < 0) 0 else if (x > 255) 255 else x

    /** YUV_420_888 -> ARGB já girado para ficar "em pé". Duas amostras de luma por amostra de croma. */
    private fun convert(j: Job): Bitmap {
        val w = j.w
        val h = j.h
        val rot = j.rot
        val ow = if (rot == 90 || rot == 270) h else w
        val oh = if (rot == 90 || rot == 270) w else h
        if (argb.size != ow * oh) argb = IntArray(ow * oh)
        val out = argb
        val yArr = j.y
        val uArr = j.u
        val vArr = j.v
        val uMax = uArr.size - 1
        val vMax = vArr.size - 1
        val uvPix = j.uvPix
        for (sy in 0 until h) {
            val yBase = sy * j.yRow
            val uvBase = (sy shr 1) * j.uvRow
            var d: Int
            val step: Int
            when (rot) {
                90 -> { d = h - 1 - sy; step = ow }
                180 -> { d = (h - 1 - sy) * ow + (w - 1); step = -1 }
                270 -> { d = sy + (w - 1) * ow; step = -ow }
                else -> { d = sy * ow; step = 1 }
            }
            var sx = 0
            while (sx < w) {
                val ci = uvBase + (sx shr 1) * uvPix
                val uu = (uArr[if (ci > uMax) uMax else ci].toInt() and 0xFF) - 128
                val vv = (vArr[if (ci > vMax) vMax else ci].toInt() and 0xFF) - 128
                val rv = (1436 * vv) shr 10
                val gv = (352 * uu + 731 * vv) shr 10
                val bu = (1814 * uu) shr 10
                var yy = yArr[yBase + sx].toInt() and 0xFF
                out[d] = (0xFF shl 24) or (c8(yy + rv) shl 16) or (c8(yy - gv) shl 8) or c8(yy + bu)
                d += step
                if (sx + 1 < w) {
                    yy = yArr[yBase + sx + 1].toInt() and 0xFF
                    out[d] = (0xFF shl 24) or (c8(yy + rv) shl 16) or (c8(yy - gv) shl 8) or c8(yy + bu)
                    d += step
                }
                sx += 2
            }
        }
        var bm = bmp
        if (bm == null || bm.isRecycled || bm.width != ow || bm.height != oh) {
            bm = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bmp = bm
        }
        bm!!.setPixels(out, 0, ow, 0, 0, ow, oh)
        return bm
    }

    private fun dist3(a: com.google.mediapipe.tasks.components.containers.Landmark,
                      b: com.google.mediapipe.tasks.components.containers.Landmark): Float {
        val dx = a.x() - b.x(); val dy = a.y() - b.y(); val dz = a.z() - b.z()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Versão "leve": metade da resolução (1 amostra a cada 2x2 pixels) — conversão ~4x mais barata. */
    private fun convertHalf(j: Job): Bitmap {
        val w2 = j.w / 2
        val h2 = j.h / 2
        val rot = j.rot
        val ow = if (rot == 90 || rot == 270) h2 else w2
        val oh = if (rot == 90 || rot == 270) w2 else h2
        if (argb.size != ow * oh) argb = IntArray(ow * oh)
        val out = argb
        val yArr = j.y
        val uArr = j.u
        val vArr = j.v
        val uMax = uArr.size - 1
        val vMax = vArr.size - 1
        for (uy in 0 until h2) {
            val sy = uy * 2
            val yBase = sy * j.yRow
            val uvBase = uy * j.uvRow
            var d: Int
            val step: Int
            when (rot) {
                90 -> { d = h2 - 1 - uy; step = ow }
                180 -> { d = (h2 - 1 - uy) * ow + (w2 - 1); step = -1 }
                270 -> { d = uy + (w2 - 1) * ow; step = -ow }
                else -> { d = uy * ow; step = 1 }
            }
            for (ux in 0 until w2) {
                val ci = uvBase + ux * j.uvPix
                val uu = (uArr[if (ci > uMax) uMax else ci].toInt() and 0xFF) - 128
                val vv = (vArr[if (ci > vMax) vMax else ci].toInt() and 0xFF) - 128
                val yy = yArr[yBase + ux * 2].toInt() and 0xFF
                out[d] = (0xFF shl 24) or (c8(yy + ((1436 * vv) shr 10)) shl 16) or
                    (c8(yy - ((352 * uu + 731 * vv) shr 10)) shl 8) or c8(yy + ((1814 * uu) shr 10))
                d += step
            }
        }
        var bm = bmp
        if (bm == null || bm.isRecycled || bm.width != ow || bm.height != oh) {
            bm = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bmp = bm
        }
        bm!!.setPixels(out, 0, ow, 0, 0, ow, oh)
        return bm
    }

    private class Det(
        val pts: FloatArray,
        val wristU: Float,
        val ratio: Float,
        val label: String,
        val score: Float,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val depth: Float = 0f
    ) {
        val cx get() = (x0 + x1) * 0.5f
        val cy get() = (y0 + y1) * 0.5f
    }

    /** Interseção / menor área: pega a "mão fantasma" pequena dentro da mão verdadeira. */
    private fun overlapMin(a: Det, b: Det): Float {
        val ix = (minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)).coerceAtLeast(0f)
        val iy = (minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)).coerceAtLeast(0f)
        val m = minOf((a.x1 - a.x0) * (a.y1 - a.y0), (b.x1 - b.x0) * (b.y1 - b.y0))
        return if (m <= 1e-6f) 0f else ix * iy / m
    }

    private fun iou(a: Det, b: Det): Float {
        val ix = (minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)).coerceAtLeast(0f)
        val iy = (minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)).coerceAtLeast(0f)
        val inter = ix * iy
        val ua = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
        return if (ua <= 1e-6f) 0f else inter / ua
    }

    /** Qual mão (0/1) ativa está perto desta detecção (continuidade)? -1 se nenhuma. */
    private fun contSlot(d: Det): Int {
        var best = -1
        var bd = 0.25f
        for (h in 0..1) {
            if (!active[h]) continue
            val dx = d.pts[0] - prevPts[h][0]
            val dy = d.pts[1] - prevPts[h][1]
            val dz = d.pts[2] - prevPts[h][2]
            val dd = sqrt(dx * dx + dy * dy + dz * dz)
            if (dd < bd) { bd = dd; best = h }
        }
        return best
    }

    /** Mão (0 = esquerda, 1 = direita) segundo o rótulo do MediaPipe, corrigindo a inversão da imagem não espelhada. */
    private fun labelSlot(d: Det): Int {
        if (d.label.isEmpty()) return -1
        val labelRight = d.label == "Right"
        val realRight = if (swapVotes >= 0) !labelRight else labelRight
        return if (realRight) 1 else 0
    }

    // ---- controle 6DoF com bolinhas (DotDetector): publica o mesmo HandsFrame que as mãos, com 1 "mão" = o celular-controle
    private val dotPts = FloatArray(63)
    private val dotPrev = FloatArray(63)
    private val dotVel = FloatArray(3)
    private var dotLastNs = 0L
    private var dotLastSeenNs = 0L
    private var dotInit = false
    private val dotFilter = OneEuro(63, 2.2f, 28f, 1.0f)

    private fun processDots(j: Job) {
        val now = System.nanoTime()
        val r = DotDetector.detect(j, st.dotsScreenW, st.dotsScreenH, st.handScale)
        st.dotsSeen = r != null
        val slot = if (st.dotsLeft) 0 else 1
        if (r != null) {
            st.dotsDepthRaw = r.depthRaw
            val tSec = (now - startNs) / 1e9f
            System.arraycopy(r.pts, 0, dotPts, 0, 63)
            if (!dotInit) { dotFilter.reset(); dotInit = true; dotLastNs = 0L; dotVel.fill(0f) }
            dotFilter.apply(dotPts, tSec)
            if (dotLastNs != 0L) {
                val dt = ((now - dotLastNs) / 1e9f).coerceAtLeast(1e-3f)
                for (c in 0..2) dotVel[c] = (0.5f * dotVel[c] + 0.5f * ((dotPts[c] - dotPrev[c]) / dt)).coerceIn(-3f, 3f)
            }
            System.arraycopy(dotPts, 0, dotPrev, 0, 63)
            dotLastNs = now; dotLastSeenNs = now
        } else if (now - dotLastSeenNs > 250_000_000L) {
            dotInit = false
        }
        val live = dotInit && now - dotLastSeenNs <= 250_000_000L
        val valid = BooleanArray(2); valid[slot] = live
        val pts = Array(2) { if (it == slot) dotPts.copyOf() else FloatArray(63) }
        val vels = Array(2) { if (it == slot) dotVel.copyOf() else FloatArray(3) }
        val ratios = FloatArray(2) { 1f }
        val latNs = (System.nanoTime() - j.submitNs).coerceIn(0L, 400_000_000L)
        latest = HandsFrame(valid, pts, vels, BooleanArray(2), ratios, BooleanArray(2), latNs, now)
        st.handState = if (live) 3 else 2
    }

    private fun process(j: Job) {
        if (st.handSource == 1) { processDots(j); return }
        val lm = landmarker ?: return
        if (j.w <= 0 || j.h <= 0 || j.y.isEmpty()) return
        phase = "converter"
        val half = st.lite || autoHalf
        val bm = if (half) convertHalf(j) else convert(j)
        val ow = bm.width
        val oh = bm.height
        val sc = if (half) 0.5f else 1f

        // intrínsecos da imagem já girada (e reduzida)
        val fx0: Float; val fy0: Float; val cx0: Float; val cy0: Float
        when (j.rot) {
            90 -> { fx0 = j.fy; fy0 = j.fx; cx0 = j.h - j.cy; cy0 = j.cx }
            180 -> { fx0 = j.fx; fy0 = j.fy; cx0 = j.w - j.cx; cy0 = j.h - j.cy }
            270 -> { fx0 = j.fy; fy0 = j.fx; cx0 = j.cy; cy0 = j.w - j.cx }
            else -> { fx0 = j.fx; fy0 = j.fy; cx0 = j.cx; cy0 = j.cy }
        }
        val fx = fx0 * sc
        val fy = fy0 * sc
        val cx = cx0 * sc
        val cy = cy0 * sc

        var ts = j.tsNs / 1_000_000L
        if (ts <= lastTsMs) ts = lastTsMs + 1
        lastTsMs = ts
        phase = "detectar"
        // IMPORTANTE: não chamar mp.close() — isso recicla o Bitmap, que é reaproveitado a cada quadro
        val mp = BitmapImageBuilder(bm).build()
        val res = lm.detectForVideo(mp, ts)
        phase = "3D"

        val now = System.nanoTime()
        val tSec = (now - startNs) / 1e9f      // relativo ao início: o Float perde precisão com o tempo desde o boot
        val n = res.landmarks().size
        lastHands = n

        val raw = ArrayList<Det>()
        val pose = j.pose
        val hands = res.handednesses()
        for (k in 0 until n) {
            val nl = res.landmarks()[k]
            val wl = res.worldLandmarks()[k]
            if (nl.size < 21 || wl.size < 21) continue

            // profundidade por escala: tamanho da mão em metros (modelo) / tamanho em pixels
            val pairs = intArrayOf(0, 5, 0, 9, 0, 13, 0, 17, 5, 9, 9, 13, 13, 17)
            var sumW = 0f
            var sumP = 0f
            var q = 0
            while (q < pairs.size) {
                val a = pairs[q]; val b = pairs[q + 1]
                val dwx = wl[a].x() - wl[b].x(); val dwy = wl[a].y() - wl[b].y()
                val dpx = (nl[a].x() - nl[b].x()) * ow; val dpy = (nl[a].y() - nl[b].y()) * oh
                sumW += sqrt(dwx * dwx + dwy * dwy)
                sumP += sqrt(dpx * dpx + dpy * dpy)
                q += 2
            }
            if (sumP < 4f || sumW < 1e-4f) continue
            val zc = (((fx + fy) * 0.5f) * (sumW / sumP) * st.handScale).coerceIn(0.10f, 1.4f)

            val out = FloatArray(63)
            var bx0 = 1f; var by0 = 1f; var bx1 = 0f; var by1 = 0f
            for (i in 0 until 21) {
                val nx = nl[i].x()
                val ny = nl[i].y()
                if (nx < bx0) bx0 = nx
                if (ny < by0) by0 = ny
                if (nx > bx1) bx1 = nx
                if (ny > by1) by1 = ny
                val u = nx * ow
                val v = ny * oh
                val zi = (zc + wl[i].z()).coerceAtLeast(0.05f)
                val xc = (u - cx) / fx * zi
                val yc = (v - cy) / fy * zi
                // camera (x dir, y baixo, z frente) -> olho OpenGL (x dir, y cima, -z frente) -> cena
                val ex = xc
                val ey = -yc
                val ez = -zi
                out[i * 3] = pose[0] * ex + pose[4] * ey + pose[8] * ez + pose[12]
                out[i * 3 + 1] = pose[1] * ex + pose[5] * ey + pose[9] * ez + pose[13]
                out[i * 3 + 2] = pose[2] * ex + pose[6] * ey + pose[10] * ez + pose[14]
            }
            val size = dist3(wl[0], wl[9]).coerceAtLeast(1e-4f)
            val r3 = dist3(wl[4], wl[8]) / size
            // razão em 2D (na imagem) usando o maior entre comprimento e largura da palma
            fun d2(a: Int, b: Int): Float {
                val ddx = (nl[a].x() - nl[b].x()) * ow
                val ddy = (nl[a].y() - nl[b].y()) * oh
                return sqrt(ddx * ddx + ddy * ddy)
            }
            val size2 = maxOf(d2(0, 9), d2(5, 17)).coerceAtLeast(1e-3f)
            if (d2(0, 9) < 12f * sc) continue            // detecção minúscula = provável fantasma
            val r2 = d2(4, 8) / size2
            val ratioMix = 0.5f * (r2 + r3)
            val cat = if (k < hands.size && hands[k].isNotEmpty()) hands[k][0] else null
            raw.add(Det(out, nl[0].x(), ratioMix,
                cat?.categoryName() ?: "", cat?.score() ?: 0f, bx0, by0, bx1, by1, zc))
        }

        // remove duplicatas (a mesma mão detectada duas vezes): sobreposição alta ou centros muito próximos
        val dets = ArrayList<Det>()
        for (d in raw.sortedByDescending { it.score }) {
            var dup = false
            for (e in dets) {
                val cd = abs(d.cx - e.cx) + abs(d.cy - e.cy)
                if (iou(d, e) > 0.30f || overlapMin(d, e) > 0.5f || cd < 0.08f) { dup = true; break }
            }
            if (!dup && dets.size < wantHands) dets.add(d)
        }
        // "mãos fantasma" distantes: uma mão NOVA vista além de 0,8 m não pode ser a do usuário (o CardboardXR descreve o mesmo problema:
        // o detector de palma às vezes acha "mãos" a 0,9-1,7 m). Uma mão que já vinha sendo seguida pode ir mais longe (braço esticado).
        val di = dets.iterator()
        while (di.hasNext()) { val d = di.next(); if (d.depth > 0.80f && contSlot(d) < 0) di.remove() }

        // identidade esquerda/direita: continuidade > rótulo (corrigido) > posição na imagem
        val slot = IntArray(dets.size) { -1 }
        if (dets.size == 2) {
            val a = dets[0]
            val b = dets[1]
            val ca = contSlot(a)
            val cb = contSlot(b)
            var sa: Int
            var sb: Int
            if (ca >= 0 && cb >= 0 && ca != cb) { sa = ca; sb = cb }
            else if (ca >= 0 && cb < 0) { sa = ca; sb = 1 - ca }
            else if (cb >= 0 && ca < 0) { sb = cb; sa = 1 - cb }
            else {
                sa = if (a.wristU <= b.wristU) 0 else 1      // a mais à esquerda da imagem é a esquerda
                sb = 1 - sa
            }
            slot[0] = sa
            slot[1] = sb
            // calibração automática do rótulo: com as duas mãos bem separadas, a da esquerda da imagem é a esquerda
            if (abs(a.wristU - b.wristU) > 0.2f) {
                val leftDet = if (a.wristU <= b.wristU) a else b
                if (leftDet.label.isNotEmpty()) {
                    swapVotes = (swapVotes + (if (leftDet.label == "Right") 1 else -1)).coerceIn(-20, 20)
                }
            }
        } else if (dets.size == 1) {
            val d = dets[0]
            val c = contSlot(d)
            slot[0] = if (c >= 0) c else {
                val ls = labelSlot(d)
                if (ls >= 0) ls else if (d.wristU < 0.5f) 0 else 1
            }
        }

        val seen = BooleanArray(2)
        for (i in dets.indices) {
            val h = slot[i]
            if (h < 0) continue
            seen[h] = true
            val d = dets[i]
            if (!active[h]) {
                filters[h].reset(); pinchState[h] = false; vel[h].fill(0f)
                ratioOut[h] = 1f; closingS[h] = false; rvF[h] = 0f
                conf[h] = 0; offCount[h] = 0
            }
            if (conf[h] < 10) conf[h]++
            val dtH = (tSec - prevT[h]).coerceAtLeast(1e-3f)
            priors[h].enforce(d.pts, d.score > 0.6f && d.ratio > 0.55f)          // ossos com comprimento real + articulações sem dobra impossível
            filters[h].apply(d.pts, tSec)
            if (active[h]) {
                for (c in 0..2) {
                    val nv = (d.pts[c] - prevPts[h][c]) / dtH
                    vel[h][c] = (0.5f * vel[h][c] + 0.5f * nv).coerceIn(-3f, 3f)
                }
            }
            System.arraycopy(d.pts, 0, prevPts[h], 0, 63)
            prevT[h] = tSec

            // razão da pinça (levemente filtrada) e velocidade com que ela muda
            val oldR = ratioOut[h]
            ratioOut[h] = 0.7f * d.ratio + 0.3f * oldR
            if (active[h]) rvF[h] = 0.5f * rvF[h] + 0.5f * ((ratioOut[h] - oldR) / dtH)
            val rr = ratioOut[h]
            if (pinchState[h]) {
                if (rr < pinchMin[h]) pinchMin[h] = rr
                val thr = (pinchMin[h] + 0.15f).coerceIn(PINCH_REL_MIN, PINCH_REL_MAX)
                if (rr > thr) {
                    offCount[h]++
                    // abrindo de propósito (razão subindo rápido): solta no 1º quadro; senão confirma em 2
                    val need = if (rvF[h] > 0.4f) 1 else 2
                    if (offCount[h] >= need) pinchState[h] = false
                } else {
                    offCount[h] = 0
                }
            } else {
                offCount[h] = 0
                if (rr < PINCH_ON) {
                    pinchState[h] = true
                    pinchMin[h] = rr
                }
            }
            // "fechando": a razão está caindo RÁPIDO (pinça a caminho); mão relaxada não dispara
            if (!closingS[h]) {
                if (ratioOut[h] < 0.95f && rvF[h] < -0.9f) closingS[h] = true
            } else if (ratioOut[h] > 0.9f || rvF[h] > 0.8f) {
                closingS[h] = false
            }
            active[h] = true
        }

        if (seen[0] || seen[1]) lastSeenNs = now
        for (h in 0..1) {
            // mão FECHADA (pinça/punho) some do detector por instantes: segura a pose um pouco mais para o trigger não soltar sozinho
            val holdNs = if (pinchState[h] || ratioOut[h] < 0.55f) 450_000_000L else 200_000_000L
            if (!seen[h] && active[h] && now - lastSeenNs > holdNs) {
                active[h] = false
                conf[h] = 0
                pinchState[h] = false
                closingS[h] = false
                vel[h].fill(0f)
                ratioOut[h] = 1f
            }
        }

        val valid = BooleanArray(2) { active[it] && conf[it] >= 2 }
        val pts = Array(2) { prevPts[it].copyOf() }
        val vels = Array(2) { vel[it].copyOf() }
        val pinch = BooleanArray(2) { valid[it] && pinchState[it] }
        val ratios = FloatArray(2) { ratioOut[it] }
        val closing = BooleanArray(2) { valid[it] && closingS[it] }
        val latNs = (System.nanoTime() - j.submitNs).coerceIn(0L, 400_000_000L)
        latest = HandsFrame(valid, pts, vels, pinch, ratios, closing, latNs, now)
        st.handState = if (valid[0] || valid[1]) 3 else 2
    }
}
