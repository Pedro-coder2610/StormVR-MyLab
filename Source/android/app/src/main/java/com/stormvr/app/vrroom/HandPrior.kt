package com.stormvr.app.vrroom

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Noção dos movimentos que a mão pode fazer": modelo anatômico leve aplicado aos 21 pontos do MediaPipe, por mão.
 *  1) COMPRIMENTO DOS OSSOS: aprende o tamanho real de cada osso quando a mão está aberta e bem vista, e depois obriga os dedos a manter esse tamanho.
 *     Ao fechar a mão, o ruído de profundidade esticava/encolhia os dedos — era isso que "quebrava" o rastreamento.
 *  2) LIMITE DE DOBRA: uma articulação não dobra além de ~105° (polegar ~85°) nem para trás; ângulos impossíveis são puxados para o limite.
 * Não inventa pose: só corrige o que a anatomia proíbe. Trabalha nos pontos 3D da cena (metros).
 */
class HandPrior {
    // cadeias (pai -> filho) do MediaPipe: 0 punho; 1-4 polegar; 5-8 indicador; 9-12 médio; 13-16 anelar; 17-20 mindinho
    private val chains = arrayOf(
        intArrayOf(0, 1, 2, 3, 4), intArrayOf(0, 5, 6, 7, 8), intArrayOf(0, 9, 10, 11, 12), intArrayOf(0, 13, 14, 15, 16), intArrayOf(0, 17, 18, 19, 20)
    )
    private val len = Array(5) { FloatArray(4) }          // comprimento aprendido de cada osso de cada cadeia
    private var learned = 0
    private val d0 = FloatArray(3); private val d1 = FloatArray(3)

    fun reset() { learned = 0; for (c in len) c.fill(0f) }

    private fun dist(p: FloatArray, a: Int, b: Int): Float {
        val x = p[a * 3] - p[b * 3]; val y = p[a * 3 + 1] - p[b * 3 + 1]; val z = p[a * 3 + 2] - p[b * 3 + 2]
        return sqrt(x * x + y * y + z * z)
    }

    /** [p] = 63 floats (21 pontos). [reliable] = mão aberta e bem detectada (só aí os comprimentos são aprendidos). Altera [p] no lugar. */
    fun enforce(p: FloatArray, reliable: Boolean) {
        if (reliable) {
            for (c in chains.indices) for (k in 0 until 4) {
                val l = dist(p, chains[c][k], chains[c][k + 1])
                if (l < 0.005f || l > 0.15f) continue
                len[c][k] = if (len[c][k] == 0f) l else len[c][k] + (l - len[c][k]) * 0.05f
            }
            if (learned < 1000) learned++
        }
        if (learned < 8) return                                   // ainda não aprendeu o tamanho da mão
        for (c in chains.indices) {
            val maxA = if (c == 0) 85f else 105f
            var prevDirOk = false
            for (k in 0 until 4) {
                val a = chains[c][k]; val b = chains[c][k + 1]
                val l = len[c][k]; if (l <= 0f) { prevDirOk = false; continue }
                var dx = p[b * 3] - p[a * 3]; var dy = p[b * 3 + 1] - p[a * 3 + 1]; var dz = p[b * 3 + 2] - p[a * 3 + 2]
                var n = sqrt(dx * dx + dy * dy + dz * dz)
                if (n < 1e-5f) { dx = d1[0]; dy = d1[1]; dz = d1[2]; n = 1f }
                dx /= n; dy /= n; dz /= n
                if (prevDirOk && k >= 2) {                         // limite de dobra das articulações dos dedos (a base, k=1, é livre)
                    val cs = (d0[0] * dx + d0[1] * dy + d0[2] * dz).coerceIn(-1f, 1f)
                    val ang = Math.toDegrees(acos(cs).toDouble()).toFloat()
                    if (ang > maxA) {
                        var px = dx - d0[0] * cs; var py = dy - d0[1] * cs; var pz = dz - d0[2] * cs
                        val pn = sqrt(px * px + py * py + pz * pz)
                        if (pn > 1e-5f) {
                            px /= pn; py /= pn; pz /= pn
                            val ma = Math.toRadians(maxA.toDouble()).toFloat(); val co = cos(ma); val si = sin(ma)
                            dx = d0[0] * co + px * si; dy = d0[1] * co + py * si; dz = d0[2] * co + pz * si
                        }
                    }
                }
                p[b * 3] = p[a * 3] + dx * l; p[b * 3 + 1] = p[a * 3 + 1] + dy * l; p[b * 3 + 2] = p[a * 3 + 2] + dz * l   // osso com o comprimento real
                d0[0] = dx; d0[1] = dy; d0[2] = dz; d1[0] = dx; d1[1] = dy; d1[2] = dz
                prevDirOk = true
            }
        }
    }
}
