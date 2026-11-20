package com.stormvr.app

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Mapeamento do GAMEPAD ligado ao celular (USB, Bluetooth ou qualquer outro: o Android o expõe como InputDevice) para cada função.
 * Cada função tem uma "fonte": "k:<keyCode>" (botão), "a:<eixo>:+" / "a:<eixo>:-" (metade de um eixo, gatilhos e direcional) ou "a:<eixo>" (analógico).
 * Varias fontes podem ser combinadas com "|". Usado no Moonlight (tela cheia), na Sala VR e no Modo controle.
 */
object GamepadMap {
    enum class Fn(val label: String, val key: Int, val moon: Int, val analog: Boolean = false) {
        A("A (baixo)", KeyEvent.KEYCODE_BUTTON_A, 0x1000), B("B (direita)", KeyEvent.KEYCODE_BUTTON_B, 0x2000),
        X("X (esquerda)", KeyEvent.KEYCODE_BUTTON_X, 0x4000), Y("Y (cima)", KeyEvent.KEYCODE_BUTTON_Y, 0x8000),
        LB("LB / L1", KeyEvent.KEYCODE_BUTTON_L1, 0x0100), RB("RB / R1", KeyEvent.KEYCODE_BUTTON_R1, 0x0200),
        LT("LT / L2 (gatilho)", KeyEvent.KEYCODE_BUTTON_L2, 0, true), RT("RT / R2 (gatilho)", KeyEvent.KEYCODE_BUTTON_R2, 0, true),
        L3("Clique do analógico esq. (L3)", KeyEvent.KEYCODE_BUTTON_THUMBL, 0x0040), R3("Clique do analógico dir. (R3)", KeyEvent.KEYCODE_BUTTON_THUMBR, 0x0080),
        START("Start", KeyEvent.KEYCODE_BUTTON_START, 0x0010), BACK("Select / Back", KeyEvent.KEYCODE_BUTTON_SELECT, 0x0020),
        GUIDE("Guide / Home", KeyEvent.KEYCODE_BUTTON_MODE, 0x0400),
        UP("Direcional ↑", KeyEvent.KEYCODE_DPAD_UP, 0x0001), DOWN("Direcional ↓", KeyEvent.KEYCODE_DPAD_DOWN, 0x0002),
        LEFT("Direcional ←", KeyEvent.KEYCODE_DPAD_LEFT, 0x0004), RIGHT("Direcional →", KeyEvent.KEYCODE_DPAD_RIGHT, 0x0008),
        LSX("Analógico esq. — eixo X", 0, 0, true), LSY("Analógico esq. — eixo Y", 0, 0, true),
        RSX("Analógico dir. — eixo X", 0, 0, true), RSY("Analógico dir. — eixo Y", 0, 0, true)
    }

    private val AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER)

    private var prefs: android.content.SharedPreferences? = null
    private val src = HashMap<Fn, String>()
    private val keyToFn = HashMap<Int, Fn>()
    private val down = HashSet<Int>()
    private val ax = FloatArray(32)

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("stormvr_gamepad", Context.MODE_PRIVATE)
        load(); loadInv()
    }

    fun default(f: Fn): String = when (f) {
        Fn.LT -> "k:${f.key}|a:${MotionEvent.AXIS_LTRIGGER}:+"
        Fn.RT -> "k:${f.key}|a:${MotionEvent.AXIS_RTRIGGER}:+"
        Fn.UP -> "k:${f.key}|a:${MotionEvent.AXIS_HAT_Y}:-"
        Fn.DOWN -> "k:${f.key}|a:${MotionEvent.AXIS_HAT_Y}:+"
        Fn.LEFT -> "k:${f.key}|a:${MotionEvent.AXIS_HAT_X}:-"
        Fn.RIGHT -> "k:${f.key}|a:${MotionEvent.AXIS_HAT_X}:+"
        Fn.LSX -> "a:${MotionEvent.AXIS_X}"
        Fn.LSY -> "a:${MotionEvent.AXIS_Y}"
        Fn.RSX, Fn.RSY -> "auto"
        else -> "k:${f.key}"
    }

    fun sourceOf(f: Fn): String = src[f] ?: default(f)

    // ---- inverter o eixo dos analógicos (vale para TUDO: Moonlight, Sala VR e Modo controle)
    private val inv = HashSet<Fn>()
    fun isInverted(f: Fn): Boolean = inv.contains(f)
    fun setInverted(f: Fn, v: Boolean) { if (v) inv.add(f) else inv.remove(f); prefs?.edit()?.putBoolean("inv_" + f.name, v)?.apply() }
    private fun loadInv() { inv.clear(); for (f in arrayOf(Fn.LSX, Fn.LSY, Fn.RSX, Fn.RSY)) if (prefs?.getBoolean("inv_" + f.name, false) == true) inv.add(f) }
    fun setSource(f: Fn, s: String) { src[f] = s; prefs?.edit()?.putString("src_" + f.name, s)?.apply(); rebuild() }
    fun reset() { src.clear(); inv.clear(); val e = prefs?.edit(); for (f in Fn.values()) { e?.remove("src_" + f.name); e?.remove("inv_" + f.name) }; e?.apply(); rebuild() }

    private fun load() { for (f in Fn.values()) prefs?.getString("src_" + f.name, null)?.let { src[f] = it }; rebuild() }

    private fun rebuild() {
        keyToFn.clear()
        for (f in Fn.values()) {
            if (f.key == 0) continue
            for (p in sourceOf(f).split('|')) { val t = p.split(':'); if (t[0] == "k") t.getOrNull(1)?.toIntOrNull()?.let { keyToFn[it] = f } }
        }
    }

    // ---------------------------------------------------------------- entrada
    private fun isPadKey(code: Int) = code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE || code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
    fun isPad(e: KeyEvent) = isPadKey(e.keyCode) && (e.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0

    /** Atualiza o estado; devolve true se o evento veio de um gamepad. */
    fun onKey(e: KeyEvent): Boolean {
        if (!isPad(e)) return false
        if (e.action == KeyEvent.ACTION_DOWN) down.add(e.keyCode) else if (e.action == KeyEvent.ACTION_UP) down.remove(e.keyCode)
        return true
    }

    fun onMotion(ev: MotionEvent): Boolean {
        if ((ev.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        for (a in AXES) ax[a] = ev.getAxisValue(a)
        return true
    }

    /** Converte o botão físico na tecla "padrão" da função mapeada (a Sala VR usa isso). null = botão sem função. */
    fun translate(e: KeyEvent): KeyEvent? {
        if (!onKey(e)) return e
        val f = keyToFn[e.keyCode] ?: return null
        if (f.key == 0 || f.key == e.keyCode) return e
        return KeyEvent(e.downTime, e.eventTime, e.action, f.key, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
    }

    // ---------------------------------------------------------------- leitura
    private fun axisRaw(a: Int): Float = when (a) {
        MotionEvent.AXIS_LTRIGGER -> max(ax[MotionEvent.AXIS_LTRIGGER], ax[MotionEvent.AXIS_BRAKE])
        MotionEvent.AXIS_RTRIGGER -> max(ax[MotionEvent.AXIS_RTRIGGER], ax[MotionEvent.AXIS_GAS])
        else -> ax.getOrElse(a) { 0f }
    }

    private fun eval(s: String): Float {
        var best = 0f
        for (part in s.split('|')) {
            val t = part.split(':')
            val v = when (t[0]) {
                "k" -> if (down.contains(t.getOrNull(1)?.toIntOrNull() ?: -1)) 1f else 0f
                "a" -> {
                    val raw = axisRaw(t.getOrNull(1)?.toIntOrNull() ?: 0)
                    when (t.getOrNull(2)) { "+" -> max(0f, raw); "-" -> max(0f, -raw); else -> raw }
                }
                else -> 0f
            }
            if (abs(v) > abs(best)) best = v
        }
        return best
    }

    private fun autoRs(x: Boolean): Float {
        val a = if (x) ax[MotionEvent.AXIS_Z] else ax[MotionEvent.AXIS_RZ]
        val b = if (x) ax[MotionEvent.AXIS_RX] else ax[MotionEvent.AXIS_RY]
        return if (abs(b) > abs(a)) b else a
    }

    fun pressed(f: Fn): Boolean = eval(sourceOf(f)) > 0.5f

    /** Gatilhos: 0..1. Analógicos: -1..1 com zona morta (Y positivo = para baixo, como o Android). */
    fun analog(f: Fn): Float {
        val s = sourceOf(f)
        val v = if (s == "auto") autoRs(f == Fn.RSX) else eval(s)
        return when (f) {
            Fn.LT, Fn.RT -> v.coerceIn(0f, 1f)
            else -> { val z = if (abs(v) < 0.12f) 0f else v.coerceIn(-1f, 1f); if (inv.contains(f)) -z else z }
        }
    }

    /** Botões no formato do Moonlight/Xbox (os gatilhos vão à parte). */
    fun moonButtons(): Int { var b = 0; for (f in Fn.values()) if (!f.analog && f.moon != 0 && pressed(f)) b = b or f.moon; return b }

    /** Botões no formato do controle StormVR (UDP): RT = Trigger, RB = Grip, LB = R2. */
    fun vrBits(): Int {
        var b = 0
        if (analog(Fn.RT) > 0.5f) b = b or ControllerUdp.BTN_TRIGGER
        if (pressed(Fn.RB) || analog(Fn.LT) > 0.5f) b = b or ControllerUdp.BTN_GRIP
        if (pressed(Fn.LB)) b = b or ControllerUdp.BTN_R2
        if (pressed(Fn.A)) b = b or ControllerUdp.BTN_A
        if (pressed(Fn.B)) b = b or ControllerUdp.BTN_B
        if (pressed(Fn.X)) b = b or ControllerUdp.BTN_X
        if (pressed(Fn.Y)) b = b or ControllerUdp.BTN_Y
        if (pressed(Fn.START)) b = b or ControllerUdp.BTN_START
        if (pressed(Fn.L3)) b = b or ControllerUdp.BTN_L3
        return b
    }

    // ---------------------------------------------------------------- captura (tela de mapeamento)
    fun captureKey(e: KeyEvent): String? = if (e.action == KeyEvent.ACTION_DOWN && isPad(e)) "k:${e.keyCode}" else null

    fun captureMotion(ev: MotionEvent, f: Fn): String? {
        if ((ev.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return null
        for (a in AXES) {
            val v = ev.getAxisValue(a)
            if (abs(v) < 0.6f) continue
            return if (f.analog && f != Fn.LT && f != Fn.RT) "a:$a" else "a:$a:" + (if (v > 0) "+" else "-")
        }
        return null
    }

    fun describe(s: String): String = s.split('|').joinToString(" ou ") { p ->
        val t = p.split(':')
        when (t[0]) {
            "k" -> KeyEvent.keyCodeToString(t.getOrNull(1)?.toIntOrNull() ?: 0).removePrefix("KEYCODE_")
            "a" -> MotionEvent.axisToString(t.getOrNull(1)?.toIntOrNull() ?: 0).removePrefix("AXIS_") + (t.getOrNull(2) ?: "")
            else -> "automático"
        }
    }

    fun connectedPads(): List<String> {
        val out = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                out.add(d.name + (if (isJoyCon(d)) " [Joy-Con]" else ""))
        }
        return out
    }

    /** Joy-Con (L/R) e Pro Controller da Nintendo (Bluetooth) chegam ao Android como gamepads comuns: a mesma tela de mapeamento vale para eles. */
    fun isJoyCon(d: InputDevice): Boolean = d.vendorId == 0x057e || d.name.contains("Joy-Con", true) || d.name.contains("Pro Controller", true)
}
