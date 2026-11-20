package com.stormvr.app

import android.content.Context
import android.os.SystemClock

/** Estado de UM controle 3DoF (venha ele do ESP32 por Bluetooth/Wi-Fi ou de outro celular). */
class ControllerState {
    @Volatile var lastRx = 0L
    @Volatile var buttons = 0
    @Volatile var joyX = 0f          // -1..1 (+ = direita)
    @Volatile var joyY = 0f          // -1..1 (+ = cima)
    @Volatile var roll = 0f          // graus
    @Volatile var pitch = 0f         // graus (+ = para cima)
    @Volatile var yaw = 0f           // graus (+ = para a esquerda)
    @Volatile var source = ""

    fun active(): Boolean = SystemClock.elapsedRealtime() - lastRx < 600L

    fun set(buttons: Int, jx: Float, jy: Float, roll: Float, pitch: Float, yaw: Float, source: String) {
        this.buttons = buttons; joyX = jx; joyY = jy
        this.roll = roll; this.pitch = pitch; this.yaw = yaw
        this.source = source
        lastRx = SystemClock.elapsedRealtime()
    }
}

/**
 * Reune os controles que o app enxerga: mao direita e mao esquerda, em QUALQUER combinacao (2 ESP32, 2 celulares,
 * ESP32 + celular). Cada FONTE (endereco Bluetooth ou IP UDP) tem uma chave; a mao vem do que o proprio controle diz
 * (flag do pacote). Se duas fontes pedirem a MESMA mao, a segunda vai para a mao livre. Uma fonte mantem sua mao
 * enquanto estiver ativa; so muda se ela mesma mudar de mao e a outra mao estiver livre.
 */
object ControllerHub {
    val right = ControllerState()
    val left = ControllerState()
    @Volatile var bleStatus = "Bluetooth: parado"
    /** Troca direita <-> esquerda (para quando os dois controles dizem a mao errada). */
    @Volatile var swapHands = false
    private var udp: ControllerUdp? = null
    private val owner = arrayOfNulls<String>(2)        // [0] = direita, [1] = esquerda: chave da fonte dona da mao

    fun hand(isLeft: Boolean): ControllerState = if (isLeft) left else right
    private fun slotState(i: Int): ControllerState = if (i == 1) left else right

    /** Chamado pelas threads do UDP e do Bluetooth. Devolve a mao usada (false = direita, true = esquerda) ou null se descartado. */
    @Synchronized
    fun submit(key: String, wantLeft: Boolean, buttons: Int, jx: Float, jy: Float, roll: Float, pitch: Float, yaw: Float, source: String): Boolean? {
        val want = if (wantLeft != swapHands) 1 else 0
        fun free(i: Int) = owner[i] == null || !slotState(i).active()
        var slot = if (owner[0] == key) 0 else if (owner[1] == key) 1 else -1
        if (slot < 0) {
            slot = if (free(want)) want else if (free(1 - want)) 1 - want else -1
            if (slot < 0) return null
            owner[slot] = key
        } else if (slot != want && free(want)) {              // o controle mudou de mao e a outra esta livre
            owner[slot] = null
            slot = want
            owner[slot] = key
        }
        slotState(slot).set(buttons, jx, jy, roll, pitch, yaw, source)
        return slot == 1
    }

    /** Esquece o dono de uma mao (ex.: Bluetooth desconectou), liberando-a para outra fonte. */
    @Synchronized
    fun release(key: String) {
        for (i in 0..1) if (owner[i] == key) owner[i] = null
    }

    fun summary(): String {
        val r = if (right.active()) "direita: " + right.source else "direita: —"
        val l = if (left.active()) "esquerda: " + left.source else "esquerda: —"
        return "$r  |  $l"
    }

    fun start(context: Context) {
        if (udp == null) {
            val u = ControllerUdp()
            udp = u
            u.start()
        }
        BleController.start(context.applicationContext)
    }

    @Synchronized
    fun stop() {
        udp?.shutdown()
        udp = null
        BleController.stop()
        owner[0] = null; owner[1] = null
    }
}
