package com.stormvr.app

import kotlin.math.abs

/**
 * Estado do controle do celular, juntando TODAS as fontes de entrada: toque na tela (PadView), gamepad ligado ao celular (USB/Bluetooth),
 * botoes de volume e a posicao 6DoF (ARCore). O ControllerSender le daqui; nada aqui bloqueia.
 */
class CtlState {
    @Volatile var padBits = 0
    @Volatile var padX = 0f
    @Volatile var padY = 0f
    @Volatile var gpBits = 0
    @Volatile var gpX = 0f
    @Volatile var gpY = 0f
    @Volatile var volBits = 0

    // 6DoF: deslocamento do celular desde o recentrar (m), no referencial do yaw do controle: frente, esquerda, cima
    @Volatile var hasPos = false
    @Volatile var posF = 0f
    @Volatile var posL = 0f
    @Volatile var posU = 0f
    // mapeamento dos eixos de POSIÇÃO (saída[i] = entrada[pSrc[i]] * escala, invertida se pInv[i]); entradas: 0 = frente, 1 = esquerda, 2 = cima
    val pSrc = intArrayOf(0, 1, 2)
    val pInv = booleanArrayOf(false, false, false)
    @Volatile var pScale = 1f

    fun bits(): Int = (padBits or gpBits or volBits) and 0xFFFF
    fun x(): Float = if (abs(padX) >= abs(gpX)) padX else gpX
    fun y(): Float = if (abs(padY) >= abs(gpY)) padY else gpY
}
