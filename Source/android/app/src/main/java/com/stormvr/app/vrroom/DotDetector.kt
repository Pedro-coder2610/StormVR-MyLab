package com.stormvr.app.vrroom

import com.stormvr.app.DotPattern
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Controle 6DoF com bolinhas" (PRÉVIA para os ESP32 com 6DoF): o celular-controle mostra bolinhas BRANCAS num padrão fixo (DotPattern) e o celular do
 * HEADSET as procura na imagem da câmera (a mesma do rastreamento de mãos).
 *
 * Como acha mesmo com bolinhas escondidas: (1) acha as manchas brancas pequenas e redondas da imagem; (2) para cada par de manchas e cada par do padrão,
 * supõe "estas duas são estas duas" e calcula a transformação (escala + giro + deslocamento) que leva o padrão para a imagem; (3) conta quantas OUTRAS
 * bolinhas do padrão caem em cima de manchas; (4) a hipótese com mais acertos (mínimo 4) vence e é refinada por mínimos quadrados com todos os acertos.
 * Com a pose anterior conhecida, o limite cai para 3 acertos (rastreamento contínuo).
 *
 * Saída: posição da tela do controle em 3D (distância pela escala: Z = foco / (pixels por metro)); a rotação continua vindo do giroscópio do controle.
 * Os pontos saem no formato dos 21 pontos de uma mão (ponto 0 = base da tela, 9 = topo), para reaproveitar o caminho das mãos até o SteamVR.
 */
object DotDetector {
    class Result(val pts: FloatArray, val depthRaw: Float, val u: Float, val v: Float, val matched: Int)

    private const val STEP = 2
    private const val MAX_BLOBS = 18
    private var mask = BooleanArray(0)
    private var stack = IntArray(0)
    private val bx = FloatArray(MAX_BLOBS); private val by = FloatArray(MAX_BLOBS); private val ba = IntArray(MAX_BLOBS)
    private var nb = 0
    private var lastScale = 0f; private var lastOk = 0L          // prior de escala (px/m) para acelerar/estabilizar

    private fun findBlobs(j: HandTracker.Job) {
        val gw = j.w / STEP; val gh = j.h / STEP
        if (mask.size < gw * gh) { mask = BooleanArray(gw * gh); stack = IntArray(gw * gh) }
        java.util.Arrays.fill(mask, 0, gw * gh, false)
        for (gy in 0 until gh) {
            val y = gy * STEP; val yRow = y * j.yRow; val uvBase = (y shr 1) * j.uvRow
            for (gx in 0 until gw) {
                val x = gx * STEP
                if ((j.y[yRow + x].toInt() and 0xFF) < 205) continue                          // só o que é bem brilhante
                val ui = uvBase + (x shr 1) * j.uvPix
                val u = (j.u[ui].toInt() and 0xFF) - 128; val v = (j.v[ui].toInt() and 0xFF) - 128
                if (abs(u) < 26 && abs(v) < 26) mask[gy * gw + gx] = true                     // brilhante E sem cor = branco
            }
        }
        nb = 0
        val cand = ArrayList<FloatArray>()
        for (s0 in 0 until gw * gh) {
            if (!mask[s0]) continue
            var sp = 0; stack[sp++] = s0; mask[s0] = false
            var area = 0; var sx = 0L; var sy = 0L; var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (sp > 0) {
                val c = stack[--sp]; val cx = c % gw; val cy = c / gw
                area++; sx += cx; sy += cy
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                if (cx > 0 && mask[c - 1]) { mask[c - 1] = false; stack[sp++] = c - 1 }
                if (cx < gw - 1 && mask[c + 1]) { mask[c + 1] = false; stack[sp++] = c + 1 }
                if (cy > 0 && mask[c - gw]) { mask[c - gw] = false; stack[sp++] = c - gw }
                if (cy < gh - 1 && mask[c + gw]) { mask[c + gw] = false; stack[sp++] = c + gw }
            }
            val w = maxX - minX + 1; val h = maxY - minY + 1
            if (area < 2 || area > 500) continue
            if (max(w, h) > 2.4f * min(w, h)) continue                                       // fios/linhas: não é bolinha
            if (area < 0.4f * w * h) continue                                                // buracos/formas vazadas
            cand.add(floatArrayOf((sx.toFloat() / area + 0.5f) * STEP, (sy.toFloat() / area + 0.5f) * STEP, area.toFloat()))
        }
        cand.sortByDescending { it[2] }
        for (c in cand) { if (nb >= MAX_BLOBS) break; bx[nb] = c[0]; by[nb] = c[1]; ba[nb] = c[2].toInt(); nb++ }
    }

    fun detect(j: HandTracker.Job, screenWm: Float, screenHm: Float, scale: Float): Result? {
        if (j.w <= 0 || j.h <= 0 || j.y.isEmpty() || j.u.isEmpty() || j.v.isEmpty()) return null
        findBlobs(j)
        if (nb < 3) return null
        val n = DotPattern.n
        val wM = screenWm / 1000f; val hM = screenHm / 1000f
        val px = FloatArray(n) { (DotPattern.u[it] - 0.5f) * wM }                              // pontos do padrão em metros, centro da tela = origem
        val py = FloatArray(n) { (DotPattern.v[it] - 0.5f) * hM }
        val f = (j.fx + j.fy) * 0.5f
        val sMin = f / 1.6f; val sMax = f / 0.07f                                              // distâncias plausíveis: 7 cm .. 1,6 m
        val needFirst = 4
        var bestIn = 0; var bestErr = 1e9f
        var bS = 0f; var bC = 1f; var bSn = 0f; var bTx = 0f; var bTy = 0f
        val used = IntArray(n)
        for (a in 0 until nb) for (b in 0 until nb) {
            if (a == b) continue
            val dax = bx[b] - bx[a]; val day = by[b] - by[a]
            val dl = hypot(dax, day); if (dl < 8f) continue
            val angA = atan2(day, dax)
            for (i in 0 until n) for (k in 0 until n) {
                if (i == k) continue
                val dpx = px[k] - px[i]; val dpy = py[k] - py[i]
                val pl = hypot(dpx, dpy); if (pl < 0.012f) continue
                val s = dl / pl
                if (s < sMin || s > sMax) continue
                val th = angA - atan2(dpy, dpx)
                val c = cos(th); val sn = sin(th)
                val tx = bx[a] - s * (c * px[i] - sn * py[i]); val ty = by[a] - s * (sn * px[i] + c * py[i])
                val tol = max(4f, 0.028f * s * wM)
                var inl = 0; var err = 0f
                for (q in 0 until n) {
                    val qx = tx + s * (c * px[q] - sn * py[q]); val qy = ty + s * (sn * px[q] + c * py[q])
                    var bd = tol * tol
                    for (m in 0 until nb) { val ex = bx[m] - qx; val ey = by[m] - qy; val d2 = ex * ex + ey * ey; if (d2 < bd) bd = d2 }
                    if (bd < tol * tol) { inl++; err += bd }
                }
                if (inl > bestIn || (inl == bestIn && err < bestErr)) { bestIn = inl; bestErr = err; bS = s; bC = c; bSn = sn; bTx = tx; bTy = ty }
                used[0] = 0
            }
        }
        val prior = lastScale > 0f && System.nanoTime() - lastOk < 500_000_000L
        if (bestIn < (if (prior) 3 else needFirst)) return null
        // refino: casa cada ponto do padrão com a mancha mais próxima e ajusta (escala, giro, deslocamento) por mínimos quadrados (Umeyama 2D)
        val tol = max(4f, 0.028f * bS * wM)
        val ip = ArrayList<Int>(); val ib = ArrayList<Int>()
        for (q in 0 until n) {
            val qx = bTx + bS * (bC * px[q] - bSn * py[q]); val qy = bTy + bS * (bSn * px[q] + bC * py[q])
            var bm = -1; var bd = tol * tol
            for (m in 0 until nb) { val ex = bx[m] - qx; val ey = by[m] - qy; val d2 = ex * ex + ey * ey; if (d2 < bd) { bd = d2; bm = m } }
            if (bm >= 0 && !ib.contains(bm)) { ip.add(q); ib.add(bm) }
        }
        val m = ip.size; if (m < 3) return null
        var mpx = 0f; var mpy = 0f; var mix = 0f; var miy = 0f
        for (t in 0 until m) { mpx += px[ip[t]]; mpy += py[ip[t]]; mix += bx[ib[t]]; miy += by[ib[t]] }
        mpx /= m; mpy /= m; mix /= m; miy /= m
        var sxx = 0f; var sxy = 0f; var den = 0f
        for (t in 0 until m) {
            val ax = px[ip[t]] - mpx; val ay = py[ip[t]] - mpy; val cx = bx[ib[t]] - mix; val cy = by[ib[t]] - miy
            sxx += ax * cx + ay * cy; sxy += ax * cy - ay * cx; den += ax * ax + ay * ay
        }
        if (den < 1e-9f) return null
        val norm = sqrt(sxx * sxx + sxy * sxy); if (norm < 1e-9f) return null
        val rc = sxx / norm; val rs = sxy / norm
        val sc = norm / den                                                                    // pixels por metro no plano do padrão
        if (sc < sMin || sc > sMax) return null
        val ox = mix - sc * (rc * mpx - rs * mpy); val oy = miy - sc * (rs * mpx + rc * mpy)  // onde cai o centro da tela (padrão 0,0) na imagem
        lastScale = sc; lastOk = System.nanoTime()

        val zRaw = f / sc
        val z = (zRaw * scale).coerceIn(0.05f, 3f)
        val out = FloatArray(63)
        val p = j.pose
        fun put(i: Int, u: Float, v: Float) {
            val ex = (u - j.cx) / j.fx * z; val ey = -((v - j.cy) / j.fy * z); val ez = -z
            out[i * 3] = p[0] * ex + p[4] * ey + p[8] * ez + p[12]
            out[i * 3 + 1] = p[1] * ex + p[5] * ey + p[9] * ez + p[13]
            out[i * 3 + 2] = p[2] * ex + p[6] * ey + p[10] * ez + p[14]
        }
        fun img(mx: Float, my: Float, o: FloatArray) { o[0] = ox + sc * (rc * mx - rs * my); o[1] = oy + sc * (rs * mx + rc * my) }
        val t = FloatArray(2)
        for (i in 0 until 21) put(i, ox, oy)
        img(0f, hM / 2f, t); put(0, t[0], t[1])                                                // base da tela
        img(0f, -hM / 2f, t); put(9, t[0], t[1])                                               // topo da tela (aponta para onde o controle aponta)
        img(-wM / 2f, -hM / 2f, t); put(5, t[0], t[1])
        img(wM / 2f, -hM / 2f, t); put(13, t[0], t[1]); put(17, t[0], t[1])
        return Result(out, zRaw, ox, oy, m)
    }
}
