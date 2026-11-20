package com.stormvr.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Mapeia o gamepad conectado ao celular (USB, Bluetooth ou outro) para cada função. Toque em "Mapear" e aperte o botão / mova o eixo no gamepad. */
class GamepadMapActivity : Activity() {
    private var capturing: GamepadMap.Fn? = null
    private val rows = HashMap<GamepadMap.Fn, Pair<TextView, Button>>()
    private lateinit var tvPads: TextView
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immersive.apply(this)
        GamepadMap.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(this@GamepadMapActivity, 18), Ui.dp(this@GamepadMapActivity, 36), Ui.dp(this@GamepadMapActivity, 18), Ui.dp(this@GamepadMapActivity, 28)); setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(this, "Mapear gamepad", "Funciona com gamepad USB, Bluetooth ou qualquer outro que o Android reconheça. Vale no Moonlight, na Sala VR e no Modo controle."))
        tvPads = Ui.label(this, "", 14f, Color.WHITE); root.addView(tvPads)
        tvHint = Ui.label(this, "Toque em \"Mapear\" e depois aperte o botão (ou mova o eixo) no gamepad.", 13f, Ui.MUTED); root.addView(tvHint)
        for (f in GamepadMap.Fn.values()) {
            val card = Ui.card(this)
            card.addView(TextView(this).apply { text = f.label; textSize = 16f; setTextColor(Color.WHITE) })
            val cur = TextView(this).apply { textSize = 13f; setTextColor(Ui.MUTED) }
            card.addView(cur)
            val b = Ui.button(this, "Mapear") { startCapture(f) }
            card.addView(b)
            root.addView(card)
            rows[f] = cur to b
        }
        val invCard = Ui.section(this, "↕", "Inverter analógicos", "Vale em tudo: Moonlight, Sala VR e Modo controle.")
        for (f in arrayOf(GamepadMap.Fn.LSX, GamepadMap.Fn.LSY, GamepadMap.Fn.RSX, GamepadMap.Fn.RSY)) {
            lateinit var b: Button
            fun txt() = f.label + ": " + (if (GamepadMap.isInverted(f)) "INVERTIDO" else "normal")
            b = Ui.button(this, txt()) { GamepadMap.setInverted(f, !GamepadMap.isInverted(f)); b.text = txt() }
            invCard.addView(b)
        }
        root.addView(invCard)
        root.addView(Ui.button(this, "Restaurar padrão") { GamepadMap.reset(); capturing = null; refresh() })
        root.addView(Ui.button(this, "Voltar", true) { finish() })
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)); setBackgroundColor(Ui.BG) })
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) Immersive.apply(this) }

    private fun refresh() {
        val pads = GamepadMap.connectedPads()
        tvPads.text = if (pads.isEmpty()) "Nenhum gamepad detectado. Conecte por USB ou pareie por Bluetooth." else "Gamepad: " + pads.joinToString(", ")
        for ((f, v) in rows) {
            v.first.text = "Atual: " + GamepadMap.describe(GamepadMap.sourceOf(f))
            v.second.text = if (capturing == f) "Aguardando o gamepad…" else "Mapear"
        }
    }

    private fun startCapture(f: GamepadMap.Fn) { capturing = f; refresh(); tvHint.text = "Aperte agora: " + f.label }
    private fun done(f: GamepadMap.Fn, s: String) {
        GamepadMap.setSource(f, s); capturing = null
        tvHint.text = "Salvo: " + f.label + " = " + GamepadMap.describe(s); refresh()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val f = capturing
        if (f != null && GamepadMap.isPad(e)) { GamepadMap.captureKey(e)?.let { if (!f.analog || f == GamepadMap.Fn.LT || f == GamepadMap.Fn.RT) done(f, it) }; return true }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val f = capturing
        if (f != null && ev.action == MotionEvent.ACTION_MOVE) { GamepadMap.captureMotion(ev, f)?.let { done(f, it); return true } }
        return super.dispatchGenericMotionEvent(ev)
    }
}
