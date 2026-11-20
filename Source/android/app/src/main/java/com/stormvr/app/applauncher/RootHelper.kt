package com.stormvr.app.applauncher

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.reflect.Method

/**
 * Processo AUXILIAR que roda como ROOT: iniciado por [RootHelperClient] com `su` + `app_process` a partir do PRÓPRIO APK do StormVR
 * (nada extra para instalar). Nunca é carregado dentro do app: só o método [main] é executado, no processo root.
 *
 * Faz só o que um APK comum não pode: abrir um app num display virtual alheio (`am start --display`, root passa nas travas do Android),
 * injetar toque/tecla nesse display (InputManager por reflexão, como o scrcpy) e dizer em qual display o app realmente ficou (`dumpsys`).
 *
 * Protocolo de texto, uma linha por comando (stdin) e por resposta (stdout):
 *   PING                         -> PONG
 *   LAUNCH <display> <pkg/cls>   -> LAUNCHED <código> <saída do am>
 *   WHERE <pkg>                  -> WHERE <displays separados por vírgula ou -> :: <evidências>
 *   T <display> <acao> <x> <y>   (0=down 1=up 2=move 3=cancel; sem resposta)
 *   K <display> <keycode>        (sem resposta)
 *   STOP <pkg>                   -> STOPPED   (am force-stop)
 *   QUIT                         (encerra)
 * Quando o app fecha o pipe (morreu), o helper sai sozinho: não sobra processo root órfão.
 */
object RootHelper {
    private var im: Any? = null
    private var injectM: Method? = null
    private var injectArgs = 2
    private var setDisplayM: Method? = null
    private var injectWhy = ""
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        setupInput()
        out.println("READY uid=" + android.os.Process.myUid() + " sdk=" + Build.VERSION.SDK_INT +
            " inject=" + (if (injectM != null) "ok" else "fallback($injectWhy)"))
        val br = BufferedReader(InputStreamReader(FileInputStream(FileDescriptor.`in`), "UTF-8"))
        while (true) {
            val line = br.readLine() ?: break
            if (line == "QUIT") break
            val first = line.substringBefore(' ')
            val reply: String? = try {
                handle(line)
            } catch (t: Throwable) {
                val m = t.javaClass.simpleName + ": " + (t.message ?: "")
                if (first == "T" || first == "K") "EVT erro em $first: $m" else "ERR $m"
            }
            if (reply != null) out.println(reply.replace('\n', '¶'))
        }
        out.println("EVT encerrado")
    }

    @Volatile private var setupDone = false
    /** Usado pelo Shizuku: o mesmo helper, mas rodando DENTRO do processo do Shizuku (uid shell). Uma linha do protocolo -> resposta (ou null). */
    @JvmStatic
    fun handleLine(line: String): String? {
        if (!setupDone) synchronized(this) { if (!setupDone) { setupInput(); setupDone = true } }
        val first = line.substringBefore(' ')
        return try {
            if (line == "READY?") "READY uid=" + android.os.Process.myUid() + " sdk=" + Build.VERSION.SDK_INT + " inject=" + (if (injectM != null) "ok" else "fallback($injectWhy)")
            else handle(line)?.replace('\n', '¶')
        } catch (t: Throwable) {
            val m = t.javaClass.simpleName + ": " + (t.message ?: "")
            if (first == "T" || first == "K") null else "ERR $m"
        }
    }

    private fun handle(line: String): String? {
        val p = line.trim().split(' ')
        when (p[0]) {
            "PING" -> return "PONG"
            "T" -> { touch(p[1].toInt(), p[2].toInt(), p[3].toFloat(), p[4].toFloat()); return null }
            "K" -> { key(p[1].toInt(), p[2].toInt()); return null }
            "LAUNCH" -> {
                val r = run(arrayOf("/system/bin/am", "start", "--display", p[1], "-n", p[2], "-f", "0x18000000"), 3000)
                return "LAUNCHED " + r.first + " " + r.second.trim()
            }
            "WHERE" -> return where(p[1])
            "STOP" -> {
                run(arrayOf("/system/bin/am", "force-stop", p[1]), 500)
                return "STOPPED"
            }
        }
        return "ERR comando desconhecido: " + p[0]
    }

    // ------------------------------------------------------------------ injeção de entrada

    private fun findManager(): Pair<Class<*>, Any>? {
        // Android 14+: InputManagerGlobal; antes: InputManager.getInstance() (ambos ocultos; root/app_process não sofre a restrição de apps)
        for (name in arrayOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val c = Class.forName(name)
                val m = c.getDeclaredMethod("getInstance")
                m.isAccessible = true
                val o = m.invoke(null)
                if (o != null) return Pair(c, o)
            } catch (t: Throwable) {
                injectWhy = t.javaClass.simpleName
            }
        }
        return null
    }

    private fun setupInput() {
        val found = findManager()
        if (found == null) { injectWhy = "sem InputManager ($injectWhy)"; return }
        val cls = found.first
        var m: Method? = null
        try { m = cls.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE); injectArgs = 2 } catch (t: Throwable) { }
        if (m == null) {
            try { m = cls.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE, Integer.TYPE); injectArgs = 3 } catch (t: Throwable) { }
        }
        var sd: Method? = null
        try { sd = InputEvent::class.java.getMethod("setDisplayId", Integer.TYPE) } catch (t: Throwable) { }
        // SEM setDisplayId o toque iria para a tela principal (a sala VR): então só usa a injeção direta se os DOIS existirem.
        if (m == null || sd == null) { injectWhy = "injectInputEvent/setDisplayId não encontrados"; return }
        im = found.second
        injectM = m
        setDisplayM = sd
    }

    private fun inject(ev: InputEvent, display: Int) {
        val m = injectM ?: return
        setDisplayM?.invoke(ev, display)
        if (injectArgs == 2) m.invoke(im, ev, 0) else m.invoke(im, ev, 0, -1)     // modo 0 = assíncrono; -1 = qualquer uid
    }

    private fun touch(display: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { downTime = now; downX = x; downY = y; downAt = now } else if (downTime == 0L) return
        if (injectM != null) {
            val pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            val ev = MotionEvent.obtain(downTime, now, action, x, y, pressure, 1f, 0, 1f, 1f, 0, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            try { inject(ev, display) } finally { ev.recycle() }
        } else if (action == MotionEvent.ACTION_UP) {
            // alternativa lenta (um processo por gesto): toque ou arrasto
            val dist = Math.abs(x - downX) + Math.abs(y - downY)
            if (dist < 16f) run(arrayOf("/system/bin/input", "-d", display.toString(), "tap", x.toInt().toString(), y.toInt().toString()), 200)
            else run(arrayOf("/system/bin/input", "-d", display.toString(), "swipe", downX.toInt().toString(), downY.toInt().toString(),
                x.toInt().toString(), y.toInt().toString(), Math.max(80L, now - downAt).toString()), 200)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0L
    }

    private fun key(display: Int, code: Int) {
        if (injectM != null) {
            val t = SystemClock.uptimeMillis()
            for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                inject(KeyEvent(t, SystemClock.uptimeMillis(), a, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), display)
            }
        } else {
            run(arrayOf("/system/bin/input", "-d", display.toString(), "keyevent", code.toString()), 200)
        }
    }

    // ------------------------------------------------------------------ onde o app ficou?

    /** Procura o pacote no `dumpsys activity activities` e devolve os displays em que há Activity dele (heurística; traz as linhas como evidência). */
    private fun where(pkg: String): String {
        val r = run(arrayOf("/system/bin/dumpsys", "activity", "activities"), 8_000_000)
        val found = LinkedHashSet<Int>()
        val evidence = ArrayList<String>()
        val dispRe = Regex("Display #(\\d+)")
        val idRe = Regex("displayId=(\\d+)")
        val needle = "$pkg/"
        var cur = -1
        for (raw in r.second.lineSequence()) {
            val m = dispRe.find(raw)
            if (m != null) cur = m.groupValues[1].toInt()
            if (!raw.contains(needle) || !raw.contains("ActivityRecord{")) continue
            val id = idRe.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: cur
            if (id >= 0) found.add(id)
            if (evidence.size < 6) evidence.add(raw.trim().take(180))
        }
        return "WHERE " + (if (found.isEmpty()) "-" else found.joinToString(",")) + " :: " + evidence.joinToString(" ¦ ")
    }

    private fun run(cmd: Array<String>, cap: Int): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val txt = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return Pair(code, if (txt.length > cap) txt.substring(0, cap) else txt)
    }
}
