package com.stormvr.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.InvocationHandler
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Proxy

/**
 * S Pen (Galaxy S Ultra / Note) como CONTROLE: o movimento da caneta no ar (esquerda/direita/cima/baixo) vira a orientacao do controle e o
 * botao lateral vira o TRIGGER. Usa o S Pen Remote SDK da Samsung por REFLEXAO: o app compila com ou sem os .jar (app/libs/LEIA-ME.txt);
 * sem eles a S Pen so fica desativada. So funciona com o StormVR aberto na tela (limite do SDK).
 *
 * Destinos (os mesmos de qualquer controle Bluetooth): ControllerHub (Sala VR do celular) e BtForward (driver no PC / SteamVR,
 * em qualquer modo - 3DoF ou 6DoF - desde que o IP do PC esteja configurado).
 */
object SPenBridge {
    private const val P = "com.samsung.android.sdk.penremote."
    private const val PREFS = "stormvr_ctl"
    private const val IDLE_MS = 20_000L

    private var app: Application? = null
    /** Activity em primeiro plano: o SDK da Samsung conecta melhor com ela do que com o contexto da Application. */
    @Volatile var activity: Activity? = null
    @Volatile var evButton = 0
        private set
    @Volatile var evMotion = 0
        private set
    @Volatile var lastError = ""
        private set
    private var sock: DatagramSocket? = null
    private val seqUdp = IntArray(2)
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    @Volatile private var connecting = false
    @Volatile var connected = false
        private set
    @Volatile var status = "S Pen: desligada"
        private set

    // estado do "controle S Pen"
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var btnDown = false
    @Volatile private var pulseUntil = 0L
    private var downAt = 0L
    @Volatile private var activeUntil = 0L

    private var remote: Any? = null
    private var manager: Any? = null
    private var unitBtn: Any? = null
    private var unitAir: Any? = null
    private var sender: Thread? = null
    @Volatile private var sending = false

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs()?.getBoolean("spen_on", true) ?: false
        set(v) { prefs()?.edit()?.putBoolean("spen_on", v)?.apply(); if (v) tryConnect() else disconnect() }
    /** Mão da S Pen: 0 = AUTOMÁTICA (a oposta à do celular-controle, para não brigarem pela mesma mão), 1 = direita, 2 = esquerda. */
    var handMode: Int
        get() = prefs()?.getInt("spen_hand", 0) ?: 0
        set(v) { prefs()?.edit()?.putInt("spen_hand", v.coerceIn(0, 2))?.apply() }
    fun isLeft(): Boolean = when (handMode) {
        1 -> false
        2 -> true
        else -> !(prefs()?.getBoolean("left", false) ?: false)           // celular-controle na direita -> S Pen na esquerda (e vice-versa)
    }
    var gain: Float
        get() = prefs()?.getFloat("spen_gain", 1f) ?: 1f
        set(v) { prefs()?.edit()?.putFloat("spen_gain", v.coerceIn(0.2f, 4f))?.apply() }
    /** 0 = botao lateral = trigger direto; 1 = clique curto = trigger, SEGURAR = so mira (para quando a caneta so mede movimento com o botao apertado). */
    var buttonMode: Int
        get() = prefs()?.getInt("spen_btnmode", 0) ?: 0
        set(v) { prefs()?.edit()?.putInt("spen_btnmode", v)?.apply() }

    fun sdkPresent(): Boolean = try { Class.forName(P + "SpenRemote"); true } catch (_: Throwable) { false }
    fun recenter() { yaw = 0f; pitch = 0f }

    fun init(a: Application) {
        app = a
        try {
            // a Samsung avisa quando a S Pen sai/entra no celular (broadcast dinamico, sem permissao)
            a.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    activeUntil = SystemClock.elapsedRealtime() + IDLE_MS
                    if (foreground) { main.removeCallbacks(retry); main.postDelayed({ disconnectQuiet(); tryConnect() }, 500) }
                }
            }, IntentFilter("com.samsung.pen.INSERT"))
        } catch (_: Throwable) {}
    }

    fun onForeground(fg: Boolean) {
        foreground = fg
        if (fg) { tryConnect() } else { main.removeCallbacks(retry); disconnect() }
    }

    private val retry = Runnable { tryConnect() }

    fun tryConnect() {
        val a = app ?: return
        val ctx: Context = activity ?: a
        if (!foreground || !enabled || connected || connecting) return
        if (!sdkPresent()) { status = "S Pen: coloque os .jar do SDK em app/libs (veja LEIA-ME.txt)"; return }
        connecting = true
        try {
            val rc = Class.forName(P + "SpenRemote")
            val r = rc.getMethod("getInstance").invoke(null)
            remote = r
            if (rc.getMethod("isConnected").invoke(r) as Boolean) { try { rc.getMethod("disconnect", Context::class.java).invoke(r, a) } catch (_: Throwable) {} }
            val cbc = Class.forName(P + "SpenRemote\$ConnectionResultCallback")
            val cb = Proxy.newProxyInstance(cbc.classLoader, arrayOf(cbc), InvocationHandler { _, m, args ->
                when (m.name) {
                    "onSuccess" -> main.post { onConnected(args!![0]) }
                    "onFailure" -> main.post { onFail((args?.getOrNull(0) as? Int) ?: -1) }
                    "hashCode" -> return@InvocationHandler 0
                    "equals" -> return@InvocationHandler false
                    "toString" -> return@InvocationHandler "StormVR-SPenCallback"
                }
                null
            })
            rc.getMethod("connect", Context::class.java, cbc).invoke(r, ctx, cb)
            status = "S Pen: conectando..."
            main.postDelayed({ if (connecting && !connected) { connecting = false; status = "S Pen: o SDK não respondeu (a caneta está FORA do celular?)"; main.postDelayed(retry, 3000) } }, 7000)
        } catch (t: Throwable) {
            connecting = false
            lastError = describe(t)
            status = "S Pen: indisponível — $lastError"
            if (foreground && enabled) main.postDelayed(retry, 5000)
        }
    }

    /** Causa-raiz com mensagem (o "IllegalArgumentException" sozinho não diz nada). */
    private fun describe(t: Throwable): String {
        var r: Throwable = t
        while (r.cause != null && r.cause !== r) r = r.cause!!
        val st = r.stackTrace.firstOrNull()
        return r.javaClass.simpleName + (if (!r.message.isNullOrBlank()) ": " + r.message else "") + (if (st != null) " @" + st.className.substringAfterLast('.') + "." + st.methodName else "")
    }

    private fun onFail(code: Int) {
        connecting = false; connected = false
        status = "S Pen: sem conexao (erro $code) - tentando de novo"
        // 1 = dispositivo sem suporte: nao adianta insistir; outros: tenta de novo em 4 s enquanto o app estiver na tela
        if (foreground && enabled && code != 0) main.postDelayed(retry, 4000)
    }

    private fun listener(lc: Class<*>, h: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(lc.classLoader, arrayOf(lc), InvocationHandler { _, m, args ->
            when (m.name) {
                "hashCode" -> return@InvocationHandler 0
                "equals" -> return@InvocationHandler false
                "toString" -> return@InvocationHandler "StormVR-SPenListener"
                else -> try { h(args?.getOrNull(0)) } catch (_: Throwable) {}
            }
            null
        })

    private fun onConnected(mgr: Any?) {
        connecting = false
        if (mgr == null) { status = "S Pen: sem gerenciador"; return }
        try {
            manager = mgr
            val mc = Class.forName(P + "SpenUnitManager")
            val uc = Class.forName(P + "SpenUnit")
            val lc = Class.forName(P + "SpenEventListener")
            val getUnit = mc.getMethod("getUnit", Int::class.javaPrimitiveType)
            val reg = mc.getMethod("registerSpenEventListener", lc, uc)
            unitBtn = getUnit.invoke(mgr, uc.getField("TYPE_BUTTON").getInt(null))
            unitAir = getUnit.invoke(mgr, uc.getField("TYPE_AIR_MOTION").getInt(null))
            val bc = Class.forName(P + "ButtonEvent"); val ac = Class.forName(P + "AirMotionEvent"); val ec = Class.forName(P + "SpenEvent")
            val bNew = bc.getConstructor(ec); val aNew = ac.getConstructor(ec)
            val bAct = bc.getMethod("getAction"); val down = bc.getField("ACTION_DOWN").getInt(null)
            val dxM = ac.getMethod("getDeltaX"); val dyM = ac.getMethod("getDeltaY")
            if (unitBtn != null) reg.invoke(mgr, listener(lc) { ev -> onButton(bAct.invoke(bNew.newInstance(ev)) as Int == down) }, unitBtn)
            if (unitAir != null) reg.invoke(mgr, listener(lc) { ev ->
                val o = aNew.newInstance(ev); onMotion(dxM.invoke(o) as Float, dyM.invoke(o) as Float)
            }, unitAir)
            connected = true
            yaw = 0f; pitch = 0f
            activeUntil = SystemClock.elapsedRealtime() + IDLE_MS
            status = "S Pen: conectada (botao lateral = trigger)"
            startSender()
        } catch (t: Throwable) {
            connected = false
            lastError = describe(t)
            status = "S Pen: falha ao ligar os sensores — $lastError"
        }
    }

    private fun onButton(down: Boolean) {
        evButton++
        val now = SystemClock.elapsedRealtime()
        activeUntil = now + IDLE_MS
        if (buttonMode == 0) { btnDown = down; return }
        if (down) { downAt = now } else if (now - downAt < 350L) pulseUntil = now + 160L      // clique curto = trigger
    }

    private fun onMotion(dx: Float, dy: Float) {
        evMotion++
        activeUntil = SystemClock.elapsedRealtime() + IDLE_MS
        val k = 22f * gain                                   // graus por unidade de movimento (ajustavel em "Sensibilidade da S Pen")
        var y = yaw - dx * k                                 // +x = direita  -> yaw (+ = esquerda) diminui
        while (y > 180f) y -= 360f
        while (y < -180f) y += 360f
        yaw = y
        pitch = (pitch + dy * k).coerceIn(-85f, 85f)         // +y = cima
    }

    private fun startSender() {
        if (sending) return
        sending = true
        val t = Thread({
            while (sending && connected) {
                val now = SystemClock.elapsedRealtime()
                if (now < activeUntil) {
                    val trig = btnDown || now < pulseUntil
                    val bits = if (trig) ControllerUdp.BTN_TRIGGER else 0
                    val left = isLeft()
                    val h = ControllerHub.submit("spen", left, bits, 0f, 0f, 0f, pitch, yaw, "S Pen")        // Sala VR do próprio celular
                    BtForward.onBle(h ?: left, bits, 0f, 0f, 0f, pitch, yaw)                                  // PC, se o reenvio do app estiver ligado
                    sendDirect(left, bits)                                                                    // PC / celular-headset pelos IPs do modo controle
                }
                try { Thread.sleep(11) } catch (_: InterruptedException) { break }
            }
            sending = false
        }, "StormVR-SPenSender")
        t.isDaemon = true
        sender = t
        t.start()
    }

    /** Envia a S Pen como um controle UDP (mesmo pacote do ControllerSender) para os IPs configurados no modo controle (PC e/ou celular-headset). */
    private fun sendDirect(left: Boolean, bits: Int) {
        val p = prefs() ?: return
        val ips = LinkedHashSet<String>()
        val all = ((p.getString("pc", "") ?: "") + "," + (p.getString("hmd", "") ?: "")).split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        for (ip in all) if (!(BtForward.enabled && BtForward.host.trim() == ip)) ips.add(ip)      // BtForward já cobre este destino
        if (ips.isEmpty()) return
        try {
            val sk = sock ?: DatagramSocket().also { it.trafficClass = 0x10; sock = it }
            val i = if (left) 1 else 0
            val buf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(ControllerUdp.MAGIC)
            buf.putShort((seqUdp[i]++ and 0xFFFF).toShort())
            buf.putShort((bits and 0xFFFF).toShort())
            buf.putShort(2048); buf.putShort(2048)
            buf.putFloat(0f); buf.putFloat(pitch); buf.putFloat(yaw)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
            buf.putInt(SystemClock.elapsedRealtime().toInt())
            buf.put(0)
            buf.put(((1 shl 2) or (if (left) ControllerUdp.FLAG_HAND_LEFT else 0)).toByte())
            for (ip in ips) sk.send(DatagramPacket(buf.array(), 42, InetAddress.getByName(ip), if (left) ControllerUdp.PORT_LEFT else ControllerUdp.PORT))
        } catch (t: Throwable) {
            lastError = "UDP: " + describe(t)
            try { sock?.close() } catch (_: Throwable) {}
            sock = null
        }
    }

    private fun disconnectQuiet() {
        sending = false; sender?.interrupt(); sender = null
        val a = app
        try {
            val r = remote
            if (r != null) {
                val mc = Class.forName(P + "SpenUnitManager"); val uc = Class.forName(P + "SpenUnit")
                val un = mc.getMethod("unregisterSpenEventListener", uc)
                val m = manager
                if (m != null) { unitBtn?.let { try { un.invoke(m, it) } catch (_: Throwable) {} }; unitAir?.let { try { un.invoke(m, it) } catch (_: Throwable) {} } }
                if (a != null) Class.forName(P + "SpenRemote").getMethod("disconnect", Context::class.java).invoke(r, a)
            }
        } catch (_: Throwable) {}
        manager = null; unitBtn = null; unitAir = null
        connected = false; connecting = false; btnDown = false
        ControllerHub.release("spen")
    }

    fun disconnect() {
        disconnectQuiet()
        status = if (enabled) "S Pen: aguardando o StormVR ficar aberto" else "S Pen: desligada"
    }
}
