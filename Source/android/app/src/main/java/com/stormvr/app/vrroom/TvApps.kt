package com.stormvr.app.vrroom

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.input.MouseButtonPacket
import com.stormvr.app.moonlight.MoonlightSession
import com.stormvr.app.moonlight.MoonlightStore
import com.limelight.nvstream.jni.MoonBridge

/** Apps que podem aparecer na telona da Sala 3D (a lista da Home). */
object TvApp { const val HOME = 0; const val BROWSER = 1; const val MOONLIGHT = 2; const val SABER = 3; const val APPS = 4 }

/** Home da "TV": tela inicial com os apps. Novos apps entram como novas peças em [tiles]. */
class TvHome(ctx: Context, private val onOpen: (Int) -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(9, 5, 16), Color.rgb(38, 22, 66)))
        val dm = resources.displayMetrics
        val unit = minOf(dm.widthPixels, dm.heightPixels)
        addView(TextView(ctx).apply {
            text = "StormVR"; setTextColor(Color.WHITE); textSize = unit * 0.075f / dm.scaledDensity
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER
        })
        addView(TextView(ctx).apply {
            text = "Escolha o que abrir na tela"; setTextColor(Color.rgb(166, 150, 198)); textSize = unit * 0.032f / dm.scaledDensity
            gravity = Gravity.CENTER; setPadding(0, 0, 0, (unit * 0.06f).toInt())
        })
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val tiles = listOf(
            Triple(TvApp.BROWSER, "🌐", "Navegador"),
            Triple(TvApp.MOONLIGHT, "🖥", "Moonlight"),
            Triple(TvApp.SABER, "⚔", "StormSaber"),
            Triple(TvApp.APPS, "📱", "Apps"))
        for ((id, icon, name) in tiles) row.addView(tile(ctx, unit, icon, name) { onOpen(id) })
        addView(row)
    }

    private fun tile(ctx: Context, unit: Int, icon: String, name: String, click: () -> Unit): View {
        val s = (unit * 0.30f).toInt()
        return LinearLayout(ctx).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.argb(200, 38, 22, 66)); cornerRadius = s * 0.16f; setStroke(2, Color.argb(110, 157, 78, 255)) }
            isClickable = true
            setOnClickListener { click() }
            addView(TextView(ctx).apply { text = icon; textSize = s * 0.30f / resources.displayMetrics.scaledDensity; gravity = Gravity.CENTER })
            addView(TextView(ctx).apply { text = name; setTextColor(Color.WHITE); textSize = s * 0.11f / resources.displayMetrics.scaledDensity; gravity = Gravity.CENTER })
            layoutParams = LayoutParams(s, s).apply { setMargins(s / 8, 0, s / 8, 0) }
        }
    }
}

/** Moonlight dentro da telona: o decodificador desenha numa SurfaceView da própria "TV"; toques/laser viram mouse no PC. */
class MoonlightPane(ctx: Context, private val act: Activity) : FrameLayout(ctx), SurfaceHolder.Callback, MoonlightSession.Events {
    private val store = MoonlightStore(act)
    private val sv = SurfaceView(ctx)
    private val msg = TextView(ctx)
    private var session: MoonlightSession? = null
    private var surfaceReady = false
    private var pending: Pair<String, NvApp>? = null
    private var down = false
    /** Chamado depois de ENCERRAR a sessão (a Sala volta para a Home da TV). */
    var onExit: (() -> Unit)? = null
    private val mm = com.stormvr.app.moonlight.MoonMouse(store, { session?.connection() }) { say(it) }
    private val endBtn = TextView(ctx)

    init {
        setBackgroundColor(Color.BLACK)
        addView(sv, LayoutParams(-1, -1))
        sv.holder.addCallback(this)
        msg.setTextColor(Color.WHITE); msg.gravity = Gravity.CENTER
        msg.textSize = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.04f / resources.displayMetrics.scaledDensity
        addView(msg, LayoutParams(-1, -2, Gravity.CENTER))
        sv.setOnTouchListener { v, ev -> touch(v, ev) }
        sv.setOnGenericMotionListener { v, ev -> generic(v, ev) }
        // ■ Encerrar sessão: desconecta E manda o PC fechar o app (o "Home" só sai e deixa o jogo rodando no PC)
        endBtn.apply {
            text = "■  Encerrar sessão"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(26, 12, 26, 12); isClickable = true; visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(200, 120, 36, 200)); cornerRadius = 40f }
            setOnClickListener { endSession() }
        }
        addView(endBtn, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 24, 24, 0) })
    }

    fun endSession() {
        val s = session ?: return
        session = null
        stopMouseTick(); down = false
        ui { endBtn.visibility = View.GONE }
        say("Encerrando a sessão no PC...")
        s.endSession(store) { onExit?.invoke() }
    }

    // proporção do stream dentro do painel: "manter 16:9" ou "preencher tudo" (opção na tela Moonlight do app)
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fitSurface(w, h)
    }
    private fun fitSurface(cw: Int, ch: Int) {
        if (cw <= 0 || ch <= 0) return
        var w = cw; var h = ch
        if (!store.fill()) { val ar = store.width().toFloat() / store.height(); if (cw.toFloat() / ch > ar) w = (ch * ar).toInt() else h = (cw / ar).toInt() }
        val lp = sv.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; sv.layoutParams = lp }
    }

    private fun ui(r: Runnable) { act.runOnUiThread(r) }
    private fun say(t: String) { ui { msg.visibility = View.VISIBLE; msg.text = t } }

    /** Abre a transmissão: usa o primeiro PC pareado e o app escolhido para a TV (tela "Moonlight" do app; padrão "Desktop"). */
    fun open() {
        if (session != null) return
        val ip = store.hosts().firstOrNull { store.paired(it) }
        if (ip == null) { say("Nenhum PC pareado.\nAbra “Moonlight” na tela inicial do app StormVR e pareie o PC."); return }
        say("Conectando a $ip...")
        Thread {
            try {
                val http = NvHTTP(ComputerDetails.AddressTuple(ip, NvHTTP.DEFAULT_HTTP_PORT), 0, store.uniqueId(), store.cert(ip), AndroidCryptoProvider(act))
                val apps = http.appList
                if (apps.isEmpty()) { say("O PC não tem apps no Sunshine."); return@Thread }
                if (store.askApp() && apps.size > 1) {            // pergunta qual app abrir (com as imagens cadastradas no Sunshine)
                    ui { showPicker(ip, http, apps.toList()) }
                    return@Thread
                }
                val app = apps.firstOrNull { it.appName.equals(store.tvApp(), true) } ?: apps.firstOrNull { it.appName.equals("Desktop", true) } ?: apps.first()
                ui { begin(ip, app) }
            } catch (e: Exception) { say("Erro ao conectar: ${e.message ?: e.javaClass.simpleName}") }
        }.start()
    }

    private var picker: View? = null
    private fun dismissPicker() { picker?.let { this@MoonlightPane.removeView(it) }; picker = null }

    /** Seletor de app dentro da telona: um cartão por app do Sunshine (Desktop, Big Picture, monitor virtual...) com a imagem dele. */
    private fun showPicker(ip: String, http: NvHTTP, apps: List<NvApp>) {
        msg.visibility = View.GONE
        dismissPicker()
        val dm = resources.displayMetrics
        val unit = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val cw = (unit * 0.36f).toInt(); val ch = (cw * 4 / 3)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 24, 24, 24) }
        val images = ArrayList<ImageView>()
        for (app in apps) {
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(40, 24, 70)) }
            images.add(img)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply { setColor(Color.rgb(22, 13, 38)); cornerRadius = unit * 0.03f }
                setPadding(10, 10, 10, 14)
                addView(img, LinearLayout.LayoutParams(cw, ch))
                addView(TextView(context).apply { text = app.appName; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = unit * 0.05f / dm.scaledDensity; setSingleLine(true) },
                    LinearLayout.LayoutParams(cw, LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { store.setTvApp(app.appName); dismissPicker(); msg.visibility = View.VISIBLE; begin(ip, app) }
            }
            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { setMargins(14, 0, 14, 0) })
        }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Color.argb(235, 9, 5, 16)); addView(row) }
        val title = TextView(context).apply { text = "Qual app abrir no PC?"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = unit * 0.06f / dm.scaledDensity }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.argb(235, 9, 5, 16)); addView(title); addView(scroll) }
        picker = box
        addView(box, LayoutParams(-1, -1))
        Thread {                                                   // imagens (box art do Sunshine), uma por vez
            for ((i, app) in apps.withIndex()) {
                try {
                    http.getBoxArt(app).use { st ->
                        val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                        val bmp: Bitmap? = BitmapFactory.decodeStream(st, null, o)
                        if (bmp != null) ui { images[i].setImageBitmap(bmp) }
                    }
                } catch (_: Throwable) {}
            }
        }.start()
    }

    // ---- teclado e gamepad da Sala 3D -> PC (Moonlight)
    fun isLive(): Boolean = session?.connection() != null && isShown && picker == null
    fun sendText(t: String) { session?.connection()?.sendUtf8Text(t) }
    /** Tecla do Windows (VK): aperta e solta. */
    fun sendVk(vk: Int, shift: Boolean = false) {
        val c = session?.connection() ?: return
        val mod: Byte = if (shift) 0x01 else 0
        c.sendKeyboardInput((0x8000 or vk).toShort(), 0x03.toByte(), mod, 0)
        c.sendKeyboardInput((0x8000 or vk).toShort(), 0x04.toByte(), mod, 0)
    }
    /** Gamepad Xbox virtual no PC (botões no formato do Moonlight; eixos -1..1; gatilhos 0..1). */
    fun sendPad(buttons: Int, lt: Float, rt: Float, lx: Float, ly: Float, rx: Float, ry: Float) {
        val c = session?.connection() ?: return
        fun s16(v: Float) = (v.coerceIn(-1f, 1f) * 32766f).toInt().toShort()
        c.sendControllerInput(0, 1, buttons, (lt.coerceIn(0f, 1f) * 255f).toInt().toByte(), (rt.coerceIn(0f, 1f) * 255f).toInt().toByte(),
            s16(lx), s16(-ly), s16(rx), s16(-ry))
    }

    private fun begin(ip: String, app: NvApp) {
        fitSurface(width, height)
        if (!surfaceReady) { pending = ip to app; return }
        val s = MoonlightSession(act, this)
        session = s
        try { s.start(ip, app, store, sv.holder.surface) } catch (e: Throwable) { session = null; say("Falha ao iniciar: ${e.message ?: e.javaClass.simpleName}") }
    }

    fun close() {
        dismissPicker()
        stopMouseTick()
        pending = null
        session?.stop(); session = null
        down = false
        ui { endBtn.visibility = View.GONE }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; pending?.let { pending = null; begin(it.first, it.second) } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }

    override fun onStatus(text: String) { say(text) }
    override fun onStarted() { ui { msg.visibility = View.GONE; endBtn.visibility = View.VISIBLE } }
    override fun onEnded(errorCode: Int, message: String) {
        session = null
        ui { endBtn.visibility = View.GONE }
        say("Transmissão encerrada" + (if (errorCode != 0) " (erro $errorCode)" else "") + (if (message.isNotEmpty()) "\n$message" else "") + "\nToque em ⌂ e abra de novo.")
    }

    // ---- entrada: o laser/pinça do StormVR vira mouse no PC
    // O cursor não pula mais: a posição pedida pelo laser/pinça é só o ALVO; um relógio de ~120 Hz desliza o cursor até ele com suavização
    // adaptativa (parado = firme, movendo = quase sem atraso) e só envia ao PC quando o pixel muda.
    private var tgx = 0f; private var tgy = 0f; private var curx = 0f; private var cury = 0f; private var haveCur = false
    private var sentX = -1; private var sentY = -1
    private val mouseHandler = Handler(Looper.getMainLooper())
    private val mouseTick = object : Runnable {
        override fun run() {
            stepMouse(0.008f)
            mouseHandler.postDelayed(this, 8)
        }
    }
    private var mouseTicking = false
    private fun startMouseTick() { if (!mouseTicking) { mouseTicking = true; mouseHandler.postDelayed(mouseTick, 8) } }
    private fun stopMouseTick() { mouseTicking = false; mouseHandler.removeCallbacks(mouseTick); haveCur = false; sentX = -1; sentY = -1 }

    private fun sendCursor(x: Float, y: Float) {
        val c = session?.connection() ?: return
        val rw = store.width(); val rh = store.height()
        val xi = x.toInt().coerceIn(0, rw - 1); val yi = y.toInt().coerceIn(0, rh - 1)
        if (xi == sentX && yi == sentY) return
        sentX = xi; sentY = yi
        c.sendMousePosition(xi.toShort(), yi.toShort(), rw.toShort(), rh.toShort())
    }
    private fun stepMouse(dt: Float) {
        if (!haveCur || session == null) return
        val dx = tgx - curx; val dy = tgy - cury
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < 0.3f) { curx = tgx; cury = tgy; sendCursor(curx, cury); return }
        val tau = 0.050f / (1f + dist / 10f)                          // erro de 10 px: metade da suavização; 100 px: ~1/11
        val a = 1f - kotlin.math.exp(-dt / tau)
        curx += dx * a; cury += dy * a
        sendCursor(curx, cury)
    }
    private fun pos(v: View, ev: MotionEvent, snap: Boolean = false, idx: Int = 0) {
        if (session?.connection() == null) return
        val rw = store.width(); val rh = store.height()
        tgx = (ev.getX(idx) / v.width.coerceAtLeast(1) * rw).coerceIn(0f, rw - 1f)
        tgy = (ev.getY(idx) / v.height.coerceAtLeast(1) * rh).coerceIn(0f, rh - 1f)
        if (!haveCur || snap) { curx = tgx; cury = tgy; haveCur = true; sendCursor(curx, cury) }
        startMouseTick()
    }
    private fun touch(v: View, ev: MotionEvent): Boolean {
        val c = session?.connection() ?: return true
        // Cada controle (id de ponteiro 0 = esquerdo, 1 = direito) tem o seu modo: MOUSE (cursor + clique; GRIP = botao direito) ou TOQUE (multitoque
        // nativo do Sunshine). "Mesmo modo nos dois" (padrao) usa a mesma escolha para ambos. Escolha em Moonlight > Telona.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> ptrDown(c, v, ev, ev.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> ptrUp(c, v, ev, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) ptrMove(c, v, ev, i)
            MotionEvent.ACTION_CANCEL -> {
                for (k in 0..1) if (touchActive[k]) { c.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, k, 0f, 0f, 0f, 0f, 0f, 0.toShort()); touchActive[k] = false }
                if (down) c.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                down = false; mouseOwner = -1
            }
        }
        return true
    }

    private val touchActive = BooleanArray(2)
    private var mouseOwner = -1
    private var touchUnsupported = false
    private fun modeOf(k: Int): Int = if (touchUnsupported) 0 else if (store.ptrSame()) store.ptrMode(0) else store.ptrMode(if (k == 0) 0 else 1)

    private fun sendTouch(c: com.limelight.nvstream.NvConnection, v: View, ev: MotionEvent, i: Int, k: Int, type: Byte) {
        val w = v.width.coerceAtLeast(1).toFloat(); val h = v.height.coerceAtLeast(1).toFloat()
        val rc = c.sendTouchEvent(type, k, (ev.getX(i) / w).coerceIn(0f, 1f), (ev.getY(i) / h).coerceIn(0f, 1f), 1f, 0f, 0f, 0.toShort())
        if (rc == MoonBridge.LI_ERR_UNSUPPORTED && !touchUnsupported) { touchUnsupported = true; say("O PC não aceita toque (Sunshine antigo): usando mouse nos dois controles") }
    }

    private fun ptrDown(c: com.limelight.nvstream.NvConnection, v: View, ev: MotionEvent, i: Int) {
        val k = if (ev.getPointerId(i) == 0) 0 else 1
        if (modeOf(k) == 1) { sendTouch(c, v, ev, i, k, MoonBridge.LI_TOUCH_EVENT_DOWN); if (!touchUnsupported) touchActive[k] = true; return }
        if (mouseOwner == -1) {                                    // so um controle por vez e o "mouse" (o outro espera ele soltar)
            mouseOwner = k
            pos(v, ev, true, i); c.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT); down = true
        }
    }
    private fun ptrMove(c: com.limelight.nvstream.NvConnection, v: View, ev: MotionEvent, i: Int) {
        val k = if (ev.getPointerId(i) == 0) 0 else 1
        if (touchActive[k]) sendTouch(c, v, ev, i, k, MoonBridge.LI_TOUCH_EVENT_MOVE)
        else if (k == mouseOwner) pos(v, ev, false, i)
    }
    private fun ptrUp(c: com.limelight.nvstream.NvConnection, v: View, ev: MotionEvent, i: Int) {
        val k = if (ev.getPointerId(i) == 0) 0 else 1
        if (touchActive[k]) { sendTouch(c, v, ev, i, k, MoonBridge.LI_TOUCH_EVENT_UP); touchActive[k] = false; return }
        if (k == mouseOwner) { pos(v, ev, true, i); if (down) c.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT); down = false; mouseOwner = -1 }
    }

    /** GRIP = botao DIREITO do mouse (so para o controle em modo MOUSE). (x, y) = ponto do laser no sistema de coordenadas desta view. */
    fun gripRight(k: Int, pressed: Boolean, x: Float, y: Float) {
        val c = session?.connection() ?: return
        if (modeOf(k) != 0) return
        if (pressed) {
            val rw = store.width(); val rh = store.height()
            tgx = (x / width.coerceAtLeast(1) * rw).coerceIn(0f, rw - 1f); tgy = (y / height.coerceAtLeast(1) * rh).coerceIn(0f, rh - 1f)
            curx = tgx; cury = tgy; haveCur = true; sendCursor(curx, cury); startMouseTick()
            c.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
        } else c.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
    }
    private fun generic(v: View, ev: MotionEvent): Boolean {
        val c = session?.connection() ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> pos(v, ev)
            MotionEvent.ACTION_SCROLL -> c.sendMouseHighResScroll((ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f).toInt().toShort())
        }
        return true
    }
}
