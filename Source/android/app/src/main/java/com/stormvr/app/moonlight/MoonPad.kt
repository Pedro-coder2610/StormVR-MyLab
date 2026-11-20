package com.stormvr.app.moonlight

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.limelight.nvstream.NvConnection
import com.stormvr.app.GamepadMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Gamepad do celular (USB / Bluetooth / qualquer um que o Android reconheca) -> controle Xbox 360 virtual no PC (Moonlight).
 * Mesma logica da Sala VR: START DUAS VEZES seguidas liga/desliga o envio ao PC (e avisa na tela).
 */
class MoonPad(private val conn: () -> NvConnection?, private val say: (String) -> Unit, startEnabled: Boolean, private val onToggle: (Boolean) -> Unit = {}) {
    var enabled = startEnabled
        private set
    private var btns = 0
    private var lt = 0f; private var rt = 0f; private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var hatX = 0; private var hatY = 0
    private var keyL2 = false; private var keyR2 = false
    private var lastStart = 0L

    private fun isPad(src: Int) = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun bit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000; KeyEvent.KEYCODE_BUTTON_B -> 0x2000; KeyEvent.KEYCODE_BUTTON_X -> 0x4000; KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100; KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010; KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040; KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001; KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002; KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004; KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        else -> 0
    }

    private fun send() {
        val c = conn() ?: return
        var b = btns
        if (hatX < 0) b = b or 0x0004 else if (hatX > 0) b = b or 0x0008
        if (hatY < 0) b = b or 0x0001 else if (hatY > 0) b = b or 0x0002
        fun s16(v: Float) = (v.coerceIn(-1f, 1f) * 32766f).toInt().toShort()
        val l2 = max(lt, if (keyL2) 1f else 0f); val r2 = max(rt, if (keyR2) 1f else 0f)
        c.sendControllerInput(0, 1, b, (l2 * 255f).toInt().toByte(), (r2 * 255f).toInt().toByte(), s16(lx), s16(-ly), s16(rx), s16(-ry))
    }

    private fun release() { btns = 0; lt = 0f; rt = 0f; lx = 0f; ly = 0f; rx = 0f; ry = 0f; hatX = 0; hatY = 0; keyL2 = false; keyR2 = false; send() }

    /** true = o evento era do gamepad e foi tratado (nao repasse ao sistema). */
    fun key(ev: KeyEvent): Boolean {
        if (!isPad(ev.source)) return false
        val e = GamepadMap.translate(ev) ?: return true          // mapeamento global do gamepad (tela "Mapear gamepad"): botao sem funcao = ignorado
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_START && down && e.repeatCount == 0) {
            val t = SystemClock.uptimeMillis()
            if (t - lastStart < 450L) {                                            // duplo clique no Start
                lastStart = 0L
                enabled = !enabled
                if (!enabled) release()
                onToggle(enabled)
                say(if (enabled) "Gamepad → PC (Xbox 360)" else "Gamepad → desligado")
                return true
            }
            lastStart = t
        }
        if (!enabled) return e.keyCode != KeyEvent.KEYCODE_BACK              // desligado: nao deixa o gamepad "voltar"/sair sem querer
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L2) { keyL2 = down; send(); return true }
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R2) { keyR2 = down; send(); return true }
        val b = bit(e.keyCode)
        if (b != 0) { btns = if (down) btns or b else btns and b.inv(); send(); return true }
        return false
    }

    fun motion(e: MotionEvent): Boolean {
        if (!isPad(e.source) || e.action != MotionEvent.ACTION_MOVE) return false
        if (!enabled) return true
        GamepadMap.onMotion(e)                                       // le os eixos pelo mapeamento global (analogicos, gatilhos, direcional)
        lx = GamepadMap.analog(GamepadMap.Fn.LSX); ly = GamepadMap.analog(GamepadMap.Fn.LSY)
        rx = GamepadMap.analog(GamepadMap.Fn.RSX); ry = GamepadMap.analog(GamepadMap.Fn.RSY)
        lt = GamepadMap.analog(GamepadMap.Fn.LT); rt = GamepadMap.analog(GamepadMap.Fn.RT)
        hatX = if (GamepadMap.pressed(GamepadMap.Fn.RIGHT)) 1 else if (GamepadMap.pressed(GamepadMap.Fn.LEFT)) -1 else 0
        hatY = if (GamepadMap.pressed(GamepadMap.Fn.DOWN)) 1 else if (GamepadMap.pressed(GamepadMap.Fn.UP)) -1 else 0
        send()
        return true
    }
}
