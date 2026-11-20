package com.stormvr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Configurações do StormVR (engrenagem da tela inicial): uma aba por assunto. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (30 * d).toInt(), (16 * d).toInt(), (8 * d).toInt()); background = Ui.screenBg() }

        // cabeçalho: voltar + título
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
            background = Ui.round(Ui.CARD2, 23, this@SettingsActivity, Ui.STROKE); isClickable = true; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
        top.addView(Ui.title(this, "⚙  Configurações", "Tudo do StormVR, separado por assunto"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (12 * d).toInt() })

        val tab = intent.getIntExtra(EXTRA_TAB, 0)
        root.addView(Ui.tabs(this, listOf(
            "🎧 Headset" to pageHeadset(),
            "🥽 Sala VR" to pageRoom(),
            "🕹 Controles" to pageControls(),
            "✒️ S Pen" to pageSPen(),
            "📺 Moonlight" to pageMoonlight(),
            "💜 Apoie e redes" to pageSupport(),
            "🔐 Shizuku" to com.stormvr.app.shizuku.ShizukuPanel.build(this)
        ), tab, top), LinearLayout.LayoutParams(-1, 0, 1f))      // o cabeçalho rola junto com o conteúdo
        setContentView(root)
    }

    private fun pageHeadset(): LinearLayout {
        val p = Ui.page(this)
        val pose = Ui.section(this, "📐", "Posição do celular no headset", "Escolha o lado em que fica o TOPO do celular quando ele está no headset. Se o mundo aparecer de cabeça para baixo, troque. Não há rotação automática.")
        lateinit var b: android.widget.Button
        fun upd() { b.text = if (prefs.orientation == 1) "Topo do celular à ESQUERDA  (tocar para trocar)" else "Topo do celular à DIREITA  (tocar para trocar)" }
        b = Ui.button(this, "") { prefs.orientation = if (prefs.orientation == 1) 2 else 1; upd() }
        pose.addView(b); upd(); p.addView(pose)
        val net = Ui.section(this, "📡", "Rede", "O IP do PC fica na tela inicial. Só mude a porta se o driver estiver usando outra.")
        net.addView(Ui.label(this, "Porta UDP da pose (cabeça e mãos)"))
        val et = Ui.input(this, "5556", prefs.port.toString(), number = true)
        et.setOnFocusChangeListener { _, f -> if (!f) prefs.port = et.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: prefs.port }
        net.addView(et)
        net.addView(Ui.label(this, "Dica: a escolha entre 3DoF e 6DoF está na tela inicial, no cartão “Conectar ao PC”.", 12f, Ui.MUTED))
        p.addView(net)
        p.addView(ExtrasUi.build(this))
        return p
    }

    private fun pageRoom(): LinearLayout {
        val p = Ui.page(this)
        val c = Ui.section(this, "🥽", "Sala VR", "Navegador, TV, mãos, tela curva, posição e giro da tela, lente e estéreo. As opções ficam no painel que abre antes de entrar na sala, separadas por abas.")
        c.addView(Ui.button(this, "Abrir a Sala VR", primary = true) { startActivity(Intent(this, com.stormvr.app.vrroom.VrRoomActivity::class.java)) })
        p.addView(c)
        val s = Ui.section(this, "⚔️", "StormSaber", "Músicas em Android/data/com.stormvr.app/files/Playlists/. Ajuste o tamanho, a posição da mão e o alcance do sabre dentro do jogo (“Ajustar sabres”).")
        s.addView(Ui.button(this, "Abrir o StormSaber") { startActivity(Intent(this, StormSaberActivity::class.java)) })
        p.addView(s)
        val a = Ui.section(this, "📱", "Apps do Android na TV", "Abra apps do celular numa janela da Sala 3D (precisa de root).")
        a.addView(Ui.button(this, "Abrir aplicativos") { startActivity(Intent(this, com.stormvr.app.applauncher.AppLauncherActivity::class.java)) })
        p.addView(a)
        return p
    }

    private fun pageControls(): LinearLayout {
        val p = Ui.page(this)
        val bt = Ui.section(this, "📶", "Controles Bluetooth (ESP32)", "Envia os controles ESP32 ligados a este celular para o PC. Direita = porta 5555, esquerda = 5558. Sai junto com o rastreamento 3DoF e continua com a tela apagada.")
        bt.addView(Switch(this).apply {
            text = "Enviar controles Bluetooth ao PC"; setTextColor(Color.WHITE); textSize = 15f; isChecked = prefs.btForward
            setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, Ui.dp(this@SettingsActivity, 6))
            setOnCheckedChangeListener { _, on ->
                prefs.btForward = on; BtForward.host = prefs.host; BtForward.enabled = on
                if (on) { ControllerHub.start(this@SettingsActivity); KeepAliveService.start(this@SettingsActivity, "Controles Bluetooth enviando ao PC") }
                else KeepAliveService.stop(this@SettingsActivity)
            }
        })
        bt.addView(Ui.button(this, "Permissões Bluetooth") {
            if (BleController.hasPermission(this)) Toast.makeText(this, "Permissões Bluetooth já concedidas.", Toast.LENGTH_SHORT).show()
            else requestPermissions(BleController.permissionsNeeded(), 2)
        })
        p.addView(bt)
        val m = Ui.section(this, "🕹", "Este celular como controle", "Botões na tela editáveis, eixos, 6DoF (ARCore ou bolinhas), gamepad e S Pen — tudo em abas dentro do modo controle.")
        m.addView(Ui.button(this, "Abrir o modo controle", primary = true) { startActivity(Intent(this, ControllerActivity::class.java)) })
        p.addView(m)
        val g = Ui.section(this, "🎮", "Gamepad (USB / Bluetooth)", "Mapeie cada botão do gamepad. Vale no Moonlight, na Sala VR e no modo controle.")
        g.addView(Ui.button(this, "Mapear gamepad") { startActivity(Intent(this, GamepadMapActivity::class.java)) })
        p.addView(g)
        return p
    }

    private fun pageSPen(): LinearLayout {
        val p = Ui.page(this)
        val c = Ui.section(this, "✒️", "S Pen como controle", "Com o StormVR aberto, tire a S Pen do celular: o botão lateral é o trigger e mover a caneta no ar aponta. Funciona também com o S23 Ultra como HEADSET.")
        p.addView(c)
        SPenUi.addTo(this, c)
        return p
    }

    private fun pageMoonlight(): LinearLayout {
        val p = Ui.page(this)
        val c = Ui.section(this, "📺", "Moonlight (espelhar o PC)", "Qualidade, modo do mouse (absoluto, touchpad ou toque), gamepad e telona. Abra a tela do Moonlight para escolher o PC e ajustar.")
        c.addView(Ui.button(this, "Abrir o Moonlight", primary = true) { startActivity(Intent(this, com.stormvr.app.moonlight.MoonlightActivity::class.java)) })
        p.addView(c)
        return p
    }

    private fun pageSupport(): LinearLayout { val p = Ui.page(this); SupportUi.addTo(this, p); return p }

    companion object { const val EXTRA_TAB = "tab"; const val TAB_SUPPORT = 5; const val TAB_SHIZUKU = 6 }
}
