package com.stormvr.app.vrroom

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Dedos na tela viram mira de controle/sabre (metade esquerda = esquerdo, metade direita = direito).
 *  - TOCAR (toque curto, sem arrastar) = CLIQUE (gatilho por um instante) onde a mira está.
 *  - SEGURAR um instante (HOLD_MS) e então ARRASTAR = move a mira, de forma RELATIVA: tirar o dedo e tocar de novo NÃO leva a mira de volta
 *    (ela continua onde parou). Arrastar antes de segurar não move nada (evita mexer sem querer).
 * yaw/pitch em graus (+ yaw = direita, + pitch = cima).
 */
class FingerAim(private val yawRange: Float, private val pitchRange: Float) {
    companion object { const val HOLD_MS = 150L; const val TAP_MS = 260L; const val CLICK_MS = 120L }

    val yaw = FloatArray(2)
    val pitch = FloatArray(2)
    val used = BooleanArray(2)
    private val pid = IntArray(2) { -1 }
    private val tDown = LongArray(2)
    private val armed = BooleanArray(2)
    private val moved = BooleanArray(2)
    private val sx = FloatArray(2); private val sy = FloatArray(2)
    private val lx = FloatArray(2); private val ly = FloatArray(2)
    private val clickUntil = LongArray(2)

    @Synchronized fun reset() { for (i in 0..1) { yaw[i] = 0f; pitch[i] = 0f; used[i] = false; pid[i] = -1; armed[i] = false; clickUntil[i] = 0L } }
    /** Mira presente desde o começo (StormSaber: os sabres já aparecem na frente). */
    @Synchronized fun showBoth() { used[0] = true; used[1] = true }
    @Synchronized fun click(i: Int, now: Long): Boolean = now < clickUntil[i]
    @Synchronized fun dragging(i: Int): Boolean = armed[i] && pid[i] != -1

    @Synchronized fun onTouch(ev: MotionEvent, w: Float, h: Float, now: Long) {
        val slop = (w * 0.025f).coerceAtLeast(12f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = ev.actionIndex
                val x = ev.getX(k); val y = ev.getY(k)
                val i = if (x < w / 2f) 0 else 1
                if (pid[i] == -1) { pid[i] = ev.getPointerId(k); tDown[i] = now; armed[i] = false; moved[i] = false; sx[i] = x; sy[i] = y; lx[i] = x; ly[i] = y; used[i] = true }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0..1) {
                if (pid[i] == -1) continue
                val k = ev.findPointerIndex(pid[i]); if (k < 0) continue
                val x = ev.getX(k); val y = ev.getY(k)
                if (!armed[i]) {
                    if (hypot(x - sx[i], y - sy[i]) > slop) { if (now - tDown[i] < HOLD_MS) moved[i] = true }
                    if (!moved[i] && now - tDown[i] >= HOLD_MS) { armed[i] = true; lx[i] = x; ly[i] = y }      // segurou o bastante: a partir daqui arrasta
                }
                if (armed[i]) {
                    yaw[i] = (yaw[i] + (x - lx[i]) / (w * 0.25f) * yawRange).coerceIn(-yawRange * 1.6f, yawRange * 1.6f)
                    pitch[i] = (pitch[i] - (y - ly[i]) / (h * 0.5f) * pitchRange).coerceIn(-pitchRange * 1.6f, pitchRange * 1.6f)
                    lx[i] = x; ly[i] = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val k = if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) -1 else ev.actionIndex
                for (i in 0..1) {
                    if (pid[i] == -1) continue
                    val ends = k == -1 || ev.getPointerId(k) == pid[i]
                    if (!ends) continue
                    if (ev.actionMasked != MotionEvent.ACTION_CANCEL && !armed[i] && !moved[i] && now - tDown[i] <= TAP_MS) clickUntil[i] = now + CLICK_MS   // toque curto = clique
                    pid[i] = -1; armed[i] = false
                }
            }
        }
    }
}
