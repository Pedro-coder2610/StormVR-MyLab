package com.stormvr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Tela inicial do StormVR: conectar ao PC (3DoF/6DoF) e atalhos grandes; ajustes na engrenagem ⚙ (SettingsActivity). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var etHost: EditText
    private lateinit var btn3: Button
    private lateinit var btn6: Button
    private lateinit var btnStart: Button
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 300) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) com.stormvr.app.Immersive.apply(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immersive.apply(this)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)   // notificação do serviço em segundo plano
    }

    override fun onResume() {
        super.onResume(); ui.post(poll)
        BtForward.host = prefs.host; BtForward.enabled = prefs.btForward
        if (prefs.btForward) ControllerHub.start(this)
        refreshMode()
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun buildUi(): View {
        val c = this
        fun dp(v: Int) = Ui.dp(c, v)
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(30), dp(18), dp(24)); background = Ui.screenBg() }

        // ---- topo: logo + nome + engrenagem (configurações) + coração (apoie)
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
        top.addView(android.widget.ImageView(c).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val names = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(TextView(c).apply { text = "StormVR"; textSize = 26f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD) })
        names.addView(TextView(c).apply { text = "Seu celular vira headset e controles"; textSize = 12.5f; setTextColor(Ui.MUTED) })
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        fun roundBtn(t: String, onClick: () -> Unit) = TextView(c).apply {
            text = t; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); isClickable = true
            background = Ui.round(Ui.CARD2, 24, c, Ui.STROKE); setOnClickListener { onClick() }
        }
        top.addView(roundBtn("💜") { startActivity(Intent(c, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TAB, SettingsActivity.TAB_SUPPORT)) },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(8) })
        top.addView(roundBtn("⚙") { startActivity(Intent(c, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(top)

        // ---- conectar ao PC + rastreamento da cabeça (um seletor, um botão)
        val trk = Ui.section(c, "🖥", "Conectar ao PC", "Informe o IP do PC onde roda o SteamVR e escolha como a cabeça é rastreada.")
        etHost = Ui.input(c, "ex.: 192.168.100.108", prefs.host); trk.addView(etHost, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })   // folga: os botões 3DoF/6DoF não encostam mais na caixa do IP
        val seg = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        btn3 = Ui.button(c, "3DoF") { setMode(false) }
        btn6 = Ui.button(c, "6DoF") { setMode(true) }
        seg.addView(btn3, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        seg.addView(btn6, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        trk.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(4) })
        tvHint = Ui.label(c, "", 12f, Ui.MUTED); trk.addView(tvHint)
        btnStart = Ui.button(c, "Iniciar rastreamento", primary = true) { toggle() }
        trk.addView(btnStart)
        tvStatus = Ui.label(c, "", 13f, Color.WHITE); trk.addView(tvStatus)
        root.addView(trk)

        // ---- atalhos grandes
        root.addView(Ui.label(c, "O que você quer fazer?", 15f, Color.WHITE))
        root.addView(Ui.row(c,
            Ui.tile(c, "🥽", "Sala VR", "navegador, TV e mãos", highlight = true) { startActivity(Intent(c, com.stormvr.app.vrroom.VrRoomActivity::class.java)) },
            Ui.tile(c, "⚔️", "StormSaber", "jogo de sabres") { startActivity(Intent(c, StormSaberActivity::class.java)) }))
        root.addView(Ui.row(c,
            Ui.tile(c, "🕹", "Modo controle", "este celular vira controle") { startActivity(Intent(c, ControllerActivity::class.java)) },
            Ui.tile(c, "📺", "Moonlight", "espelhar o PC") { startActivity(Intent(c, com.stormvr.app.moonlight.MoonlightActivity::class.java)) }))
        root.addView(Ui.row(c,
            Ui.tile(c, "📱", "Aplicativos", "apps do Android na TV") { startActivity(Intent(c, com.stormvr.app.applauncher.AppLauncherActivity::class.java)) },
            Ui.tile(c, "🎮", "Gamepad", "mapear botões") { startActivity(Intent(c, GamepadMapActivity::class.java)) }))

        // ---- apoie
        root.addView(Ui.button(c, "💜  Me ajude a continuar melhorando o StormVR", primary = true) {
            startActivity(Intent(c, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TAB, SettingsActivity.TAB_SUPPORT))
        })
        root.addView(Ui.label(c, "Toque na engrenagem ⚙ para ajustes: posição do celular no headset, Bluetooth, S Pen, Moonlight e mais.", 12f, Ui.MUTED))
        return ScrollView(c).apply { addView(root); background = Ui.screenBg(); isVerticalScrollBarEnabled = false }
    }

    /** 3DoF e 6DoF são exclusivos: escolher um desliga o outro (um só emissor de cabeça por vez). */
    private fun setMode(six: Boolean) {
        if (prefs.sixDof == six) return
        prefs.sixDof = six
        if (six && PoseService.State.running) stopService3dof()          // 6DoF usa a câmera (tela do app); o serviço 3DoF não pode continuar junto
        refreshMode()
    }

    private fun refreshMode() {
        val six = prefs.sixDof
        fun style(b: Button, on: Boolean) { b.background = Ui.round(if (on) Ui.ACCENT else Ui.CARD2, 14, this) }
        style(btn3, !six); style(btn6, six)
        btn3.text = (if (!six) "● " else "") + "3DoF"; btn6.text = (if (six) "● " else "") + "6DoF"
        tvHint.text = if (six) "6DoF: posição + rotação + mãos pela câmera (ARCore). O app precisa ficar na tela, aberto e com a tela ligada."
                      else "3DoF: só rotação pelo giroscópio. Funciona em segundo plano e com a tela apagada (notificação “StormVR”)."
        btnStart.text = if (six) "Abrir rastreamento 6DoF" else if (PoseService.State.running) "Parar rastreamento 3DoF" else "Iniciar rastreamento 3DoF"
    }

    private fun stopService3dof() {
        startService(Intent(this, PoseService::class.java).setAction(PoseService.ACTION_STOP))
    }

    private fun toggle() {
        val host = etHost.text.toString().trim()
        if (host.isEmpty()) { Toast.makeText(this, "Informe o IP do PC.", Toast.LENGTH_SHORT).show(); return }
        prefs.host = host
        if (prefs.sixDof) {
            if (PoseService.State.running) stopService3dof()
            startActivity(Intent(this, com.stormvr.app.vrroom.VrRoomActivity::class.java)
                .putExtra(com.stormvr.app.vrroom.VrRoomActivity.EXTRA_TRACK_ONLY, true))
        } else {
            if (PoseService.State.running) stopService3dof()
            else {
                val i = Intent(this, PoseService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        }
        ui.postDelayed({ refreshMode() }, 400)
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        val six = prefs.sixDof
        refreshMode()
        tvStatus.text = when {
            prefs.host.isBlank() -> "Informe o IP do PC."
            six -> "PC: ${prefs.host}:${prefs.port} · modo 6DoF"
            PoseService.State.running -> {
                val v = FloatArray(4)
                val ok = PoseService.State.service?.snapshot(v) == true
                "Enviando 3DoF para ${prefs.host}:${prefs.port}" + (if (ok) " · %.0f pacotes/s".format(v[0]) else "") +
                    (if (PoseService.State.error.isNotEmpty()) "\n" + PoseService.State.error else "")
            }
            else -> "PC: ${prefs.host}:${prefs.port} · parado"
        }
    }
}
