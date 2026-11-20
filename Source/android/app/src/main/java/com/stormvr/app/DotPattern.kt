package com.stormvr.app

/**
 * Padrão das bolinhas BRANCAS do "controle 6DoF com bolinhas": posições (u, v) em fração da tela (0..1, v para baixo), espalhadas e SEM simetria,
 * para o celular do headset reconhecer o padrão mesmo com várias bolinhas escondidas (por dedos, botões ou ângulo).
 * É usado igual pelo desenho (PadView) e pela detecção (DotDetector): mexer aqui exige os dois celulares com a mesma versão.
 */
object DotPattern {
    val u = floatArrayOf(0.10f, 0.52f, 0.91f, 0.27f, 0.73f, 0.12f, 0.47f, 0.88f, 0.31f, 0.68f, 0.16f, 0.57f, 0.90f, 0.40f)
    val v = floatArrayOf(0.07f, 0.04f, 0.10f, 0.21f, 0.27f, 0.42f, 0.50f, 0.55f, 0.69f, 0.76f, 0.90f, 0.93f, 0.88f, 0.34f)
    val n: Int get() = u.size
    /** Raio da bolinha em fração da LARGURA da tela. */
    const val RADIUS = 0.034f
}
