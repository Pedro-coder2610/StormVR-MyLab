package com.stormvr.app.moonlight

import android.view.MotionEvent
import android.view.View
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Mouse do Moonlight a partir dos toques na tela (3 modos, escolhidos em Moonlight > "Mouse"):
 *  0 ABSOLUTO: o dedo e o cursor (toque = clique esquerdo, arrastar = arrastar).
 *  1 TOUCHPAD: arrastar move o cursor (relativo); toque curto = clique; toque com 2 dedos = clique direito; 2 dedos arrastando = rolagem;
 *     segurar parado e arrastar = arrastar (botao esquerdo apertado).
 *  2 TOQUE: multitoque nativo do Sunshine (o Windows recebe toques de verdade, com pinca etc.). Se o PC nao suportar, cai no ABSOLUTO.
 */
class MoonMouse(private val store: MoonlightStore, private val conn: () -> NvConnection?, private val say: (String) -> Unit) {
    private var touchUnsupported = false
    private var warned = false
    private var absDown = false
    // touchpad
    private var lastX = 0f; private var lastY = 0f
    private var accX = 0f; private var accY = 0f
    private var downT = 0L; private var downX = 0f; private var downY = 0f
    private var moved = false; private var dragging = false; private var maxPtr = 0
    private var lastScrollY = 0f

    fun mode(): Int { val m = store.mouseMode(); return if (m == 2 && touchUnsupported) 0 else m }

    fun onTouch(v: View, ev: MotionEvent): Boolean {
        val c = conn() ?: return true
        when (mode()) { 2 -> touch(c, v, ev); 1 -> pad(c, ev); else -> absolute(c, v, ev) }
        return true
    }

    // ---- 0: absoluto
    private fun absolute(c: NvConnection, v: View, ev: MotionEvent) {
        val rw = store.width(); val rh = store.height()
        fun pos() {
            val x = (ev.x / v.width.coerceAtLeast(1) * rw).toInt().coerceIn(0, rw - 1)
            val y = (ev.y / v.height.coerceAtLeast(1) * rh).toInt().coerceIn(0, rh - 1)
            c.sendMousePosition(x.toShort(), y.toShort(), rw.toShort(), rh.toShort())
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pos(); c.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT); absDown = true }
            MotionEvent.ACTION_MOVE -> pos()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pos(); if (absDown) c.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT); absDown = false }
        }
    }

    // ---- 1: touchpad
    private fun avgY(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / max(1, ev.pointerCount) }

    private fun pad(c: NvConnection, ev: MotionEvent) {
        val sens = 1.8f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downT = ev.eventTime; downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                moved = false; dragging = false; maxPtr = 1; accX = 0f; accY = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> { maxPtr = max(maxPtr, ev.pointerCount); lastScrollY = avgY(ev) }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val y = avgY(ev); val dy = y - lastScrollY; lastScrollY = y
                    if (abs(dy) >= 1f) { c.sendMouseHighResScroll((dy * 4f).toInt().toShort()); moved = true }
                } else {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    if (hypot(ev.x - downX, ev.y - downY) > 12f) moved = true
                    if (!dragging && !moved && ev.eventTime - downT > 450L) { dragging = true; c.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT) }
                    accX += dx * sens; accY += dy * sens
                    val ix = accX.toInt(); val iy = accY.toInt()
                    if (ix != 0 || iy != 0) { accX -= ix; accY -= iy; c.sendMouseMove(ix.toShort(), iy.toShort()) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (ev.actionIndex == 0) 1 else 0
                if (idx < ev.pointerCount) { lastX = ev.getX(idx); lastY = ev.getY(idx) }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) c.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                else if (!moved && ev.eventTime - downT < 280L) {
                    val b = if (maxPtr >= 2) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT
                    c.sendMouseButtonDown(b); c.sendMouseButtonUp(b)
                }
                dragging = false; moved = false; maxPtr = 0
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) c.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT); dragging = false; moved = false; maxPtr = 0 }
        }
    }

    // ---- 2: toque nativo
    private fun touch(c: NvConnection, v: View, ev: MotionEvent) {
        val w = v.width.coerceAtLeast(1).toFloat(); val h = v.height.coerceAtLeast(1).toFloat()
        fun send(type: Byte, i: Int) {
            val rc = c.sendTouchEvent(type, ev.getPointerId(i), (ev.getX(i) / w).coerceIn(0f, 1f), (ev.getY(i) / h).coerceIn(0f, 1f),
                ev.getPressure(i).coerceIn(0f, 1f), 0f, 0f, 0.toShort())
            if (rc == MoonBridge.LI_ERR_UNSUPPORTED && !touchUnsupported) {
                touchUnsupported = true
                if (!warned) { warned = true; say("O PC não aceita toque (Sunshine antigo): usando mouse absoluto") }
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(MoonBridge.LI_TOUCH_EVENT_DOWN, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) send(MoonBridge.LI_TOUCH_EVENT_MOVE, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(MoonBridge.LI_TOUCH_EVENT_UP, ev.actionIndex)
            MotionEvent.ACTION_CANCEL -> c.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0f, 0f, 0f, 0f, 0f, 0.toShort())
        }
    }
}
