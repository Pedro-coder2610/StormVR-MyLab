package com.stormvr.app.moonlight

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.stormvr.app.Ui
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.limelight.nvstream.http.NvApp

/**
 * Moonlight em TELA CHEIA: ocupa 100% da tela do celular, sem sala 3D, sem estéreo e sem elementos de VR.
 * Entradas: toque -> mouse (modo absoluto / touchpad / toque nativo, ver MoonMouse), gamepad do celular -> Xbox virtual no PC (MoonPad; Start 2x liga/desliga)
 * e o botão ■ no canto: ENCERRAR SESSÃO (fecha o app no PC) ou só sair.
 * (A tela flutuante na Sala 3D usa a mesma MoonlightSession com a Surface da textura da sala.)
 */
class MoonlightStreamActivity : Activity(), SurfaceHolder.Callback, MoonlightSession.Events {
    private lateinit var surface: SurfaceView
    private lateinit var msg: TextView
    private var session: MoonlightSession? = null
    private var started = false
    private var ended = false
    private lateinit var store: MoonlightStore
    private lateinit var mouse: MoonMouse
    private lateinit var pad: MoonPad

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = MoonlightStore(this)
        mouse = MoonMouse(store, { session?.connection() }) { t -> runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() } }
        pad = MoonPad({ session?.connection() }, { t -> runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() } }, store.padToPc()) { on -> store.setPadToPc(on) }
        requestedOrientation = if (com.stormvr.app.Prefs(this).orientation == 1) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                               else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this); surface.holder.addCallback(this)
        surface.setOnTouchListener { v, ev -> mouse.onTouch(v, ev) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        // proporção: "manter 16:9" (barras pretas) ou "preencher tudo" (opção na tela Moonlight)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val st0 = MoonlightStore(this)
            val cw = r - l; val ch = b - t
            if (cw > 0 && ch > 0) {
                var w = cw; var h = ch
                if (!st0.fill()) { val ar = st0.width().toFloat() / st0.height(); if (cw.toFloat() / ch > ar) w = (ch * ar).toInt() else h = (cw / ar).toInt() }
                val lp = surface.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; surface.layoutParams = lp }
            }
        }
        msg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; text = "Conectando..." }
        root.addView(msg, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        // botão ■ (canto superior direito): encerrar sessão / sair / modo do mouse
        val menu = TextView(this).apply {
            text = "■"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            background = Ui.round(Color.argb(150, 88, 36, 170), 22, this@MoonlightStreamActivity, Ui.ACCENT)
            setOnClickListener { showMenu() }
        }
        val sz = (resources.displayMetrics.density * 44).toInt()
        root.addView(menu, FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.END).apply { setMargins(0, sz / 3, sz / 3, 0) })
        setContentView(root)
    }

    private val mouseNames = arrayOf("Mouse: ABSOLUTO (o dedo é o cursor)", "Mouse: TOUCHPAD (arrastar move o cursor)", "Mouse: TOQUE nativo (multitoque no PC)")

    private fun showMenu() {
        val items = arrayOf("■ Encerrar sessão (fecha o app no PC)", "⏏ Só sair (o PC continua rodando)", mouseNames[store.mouseMode().coerceIn(0, 2)] + " — trocar",
            "Gamepad → PC: " + (if (pad.enabled) "LIGADO" else "desligado") + "  (Start 2x alterna)", "Mapear gamepad…")
        AlertDialog.Builder(this).setTitle("Moonlight").setItems(items) { _, i ->
            when (i) {
                0 -> endSession()
                1 -> finish()
                2 -> { store.setMouseMode((store.mouseMode() + 1) % 3); Toast.makeText(this, mouseNames[store.mouseMode()], Toast.LENGTH_SHORT).show() }
                4 -> startActivity(android.content.Intent(this, com.stormvr.app.GamepadMapActivity::class.java))
            }
        }.setNegativeButton("Fechar", null).show()
    }

    /** Desconecta e manda o PC fechar o app. */
    private fun endSession() {
        val s = session ?: return finish()
        session = null
        ended = true
        msg.visibility = View.VISIBLE; msg.text = "Encerrando a sessão no PC..."
        s.endSession(store) { finish() }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean = if (pad.key(ev)) true else super.dispatchKeyEvent(ev)
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = if (pad.motion(ev)) true else super.dispatchGenericMotionEvent(ev)

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        started = true
        val ip = intent.getStringExtra("ip") ?: return finish()
        val app = NvApp(intent.getStringExtra("appName") ?: "Desktop", intent.getStringExtra("appUuid") ?: "", intent.getIntExtra("appId", 0), false)
        val st = store
        session = MoonlightSession(this, this)
        try { session!!.start(ip, app, st, holder.surface) }
        catch (e: Throwable) { onEnded(-1, "Falha ao iniciar: ${e.message ?: e.javaClass.simpleName}") }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    override fun onStatus(text: String) { runOnUiThread { if (!ended) { msg.visibility = View.VISIBLE; msg.text = text } } }
    override fun onStarted() { runOnUiThread { msg.visibility = View.GONE } }
    override fun onEnded(errorCode: Int, message: String) {
        runOnUiThread {
            if (ended) return@runOnUiThread
            ended = true
            msg.visibility = View.VISIBLE
            msg.text = "Transmissão encerrada" + (if (errorCode != 0) " (erro $errorCode)" else "") + (if (message.isNotEmpty()) "\n$message" else "") + "\n\nToque em Voltar para sair."
        }
    }

    override fun onDestroy() { session?.stop(); super.onDestroy() }
}
