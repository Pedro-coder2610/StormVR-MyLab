package com.stormvr.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Modo CONTROLE: este celular vira um controle (orientacao 3DoF, ou 6DoF com ARCore) com botoes na tela EDITAVEIS, botoes de volume
 * mapeaveis e GAMEPAD (USB/Bluetooth/qualquer um que o Android reconheca) mapeavel para cada botao. Envia por UDP ao driver StormVR no PC
 * (porta 5555 = direita, 5558 = esquerda) e/ou ao celular do headset (Sala VR). A S Pen (SPenBridge) funciona em qualquer tela do app.
 */
class ControllerActivity : Activity() {
    private val names = arrayOf("(nenhum)", "Grip", "Trigger", "A", "B", "X", "Y", "Start", "L3", "R2")
    private val bitOf = intArrayOf(0, 1 shl 0, 1 shl 1, 1 shl 2, 1 shl 3, 1 shl 4, 1 shl 5, 1 shl 6, 1 shl 7, 1 shl 8)
    private val stickNames = arrayOf("Analógico esquerdo", "Analógico direito", "D-pad (setas)", "Nenhum")

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var ori: PhoneOrientation
    private val st = CtlState()
    private lateinit var pad: PadView
    private lateinit var root: FrameLayout
    private lateinit var cfgView: View
    private lateinit var padRoot: FrameLayout
    private lateinit var editBar: LinearLayout
    private lateinit var tvSel: TextView
    private lateinit var btnSelToggle: Button
    private lateinit var btnFloat: Button
    private lateinit var etPc: EditText
    private lateinit var btnHand: Button
    private lateinit var btnLayout: Button
    private lateinit var btnVolUp: Button
    private lateinit var btnVolDown: Button
    private lateinit var btnStart: Button
    private lateinit var btn6: Button
    private lateinit var btnSPen: Button
    private lateinit var btnSPenHand: Button
    private lateinit var btnSPenGain: Button
    private lateinit var btnSPenMode: Button
    private lateinit var btnStickSrc: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPads: TextView
    private val mapButtons = HashMap<String, Button>()
    private var sender: ControllerSender? = null
    private var ar: ArPose6? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var learning: String? = null
    // eixos do celular-controle: saída (roll, pitch, yaw) <- entrada escolhida, com inversão individual
    private val axSrc = intArrayOf(0, 1, 2)
    private val axInv = booleanArrayOf(false, false, false)
    private val axNames = arrayOf("Roll", "Pitch", "Yaw")
    private val p6Names = arrayOf("frente", "esquerda", "cima")
    private val btnAx = arrayOfNulls<Button>(3)
    private val btnAxInv = arrayOfNulls<Button>(3)
    private val btnP6Ax = arrayOfNulls<Button>(3)
    private lateinit var btnP6Scale: Button
    private lateinit var btnOpen: Button
    private val posInvBtn = arrayOfNulls<Button>(3)
    private var padOpened = false
    private val axes = FloatArray(48)
    private val keysDown = HashSet<Int>()
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 300) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("stormvr_ctl", Context.MODE_PRIVATE)
        ori = PhoneOrientation(this)
        pad = PadView(this, prefs)
        pad.layout = prefs.getInt("layout", 0)
        loadAxes()
        if (pad.dots) window.attributes = window.attributes.apply { screenBrightness = 1f }
        pad.onMenu = { showConfig() }
        pad.onChange = { st.padBits = pad.touchBits; st.padX = pad.stickX; st.padY = pad.stickY }
        pad.onSelect = { updateEditBar() }
        cfgView = buildConfig()
        padRoot = FrameLayout(this)
        padRoot.addView(pad, FrameLayout.LayoutParams(-1, -1))
        editBar = buildEditBar()
        padRoot.addView(editBar, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        padRoot.visibility = View.GONE
        root = FrameLayout(this)
        root.background = Ui.screenBg()
        root.addView(cfgView, FrameLayout.LayoutParams(-1, -1))
        root.addView(padRoot, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ori.start(); ar?.resume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll); ar?.pause() }
    override fun onDestroy() { stopSending(); ar?.stop(); ar = null; ori.stop(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Texto da caixa única de IP: o IP da tela inicial vem primeiro (é o mesmo IP), depois outros destinos salvos aqui (ex.: celular do headset). */
    private fun destinationText(): String {
        val saved = (prefs.getString("pc", "").orEmpty() + "," + prefs.getString("hmd", "").orEmpty()).split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val home = Prefs(this).host.trim()
        val first = home.ifEmpty { saved.firstOrNull() ?: "192.168.100.108" }
        return (listOf(first) + saved.filter { it != first && it != home }).distinct().joinToString(", ")
    }

    private fun small(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE); stateListAnimator = null
        background = Ui.round(Ui.CARD2, 12, this@ControllerActivity, Ui.ACCENT)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- barra do modo edicao
    private fun buildEditBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = Ui.round(Color.argb(235, 14, 8, 26), 18, this@ControllerActivity, Ui.ACCENT)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        tvSel = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(4), 0, 0, dp(4)) }
        bar.addView(tvSel)
        val r1 = LinearLayout(this)
        btnSelToggle = small("Ativo") { pad.toggleSelected(); updateEditBar() }
        r1.addView(btnSelToggle)
        r1.addView(small("Menor −") { pad.scaleSelected(0.9f) })
        r1.addView(small("Maior +") { pad.scaleSelected(1.1f) })
        bar.addView(r1)
        val r2 = LinearLayout(this)
        btnFloat = small("") { pad.toggleFloating(); updateEditBar() }
        r2.addView(btnFloat)
        r2.addView(small("Restaurar") { pad.resetLayout(); updateEditBar() })
        r2.addView(small("Concluir") { showConfig() })
        bar.addView(r2)
        return bar
    }

    private fun updateEditBar() {
        val id = pad.selectedId
        val nm = if (id == "stick") "Analógico" else pad.item(id ?: "")?.label
        tvSel.text = if (nm == null) "Toque num item para editar (arraste para mover)" else "Selecionado: $nm"
        btnSelToggle.visibility = if (nm == null) View.INVISIBLE else View.VISIBLE
        btnSelToggle.text = if (pad.selectedEnabled()) "Ativo ✓ (tocar desativa)" else "Desativado (tocar ativa)"
        btnFloat.text = if (pad.stickFloating) "Analógico: flutuante" else "Analógico: fixo"
    }

    // ---------------------------------------------------------------- tela de configuracao
    private fun buildConfig(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(30), dp(16), dp(8)); background = Ui.screenBg() }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }       // rola junto com o conteúdo (some ao rolar para baixo)
        header.addView(Ui.title(this, "🕹  Modo controle", "Este celular vira um controle e envia por Wi-Fi. Direita → porta 5555, esquerda → porta 5558."))
        val pConn = Ui.page(this); val pBtn = Ui.page(this); val pGp = Ui.page(this); val pAx = Ui.page(this); val p6 = Ui.page(this); val pSp = Ui.page(this)

        val conn = Ui.section(this, "📡", "Conexão", "Um único IP: o do PC (driver StormVR) ou o do celular do headset (Sala VR).")
        conn.addView(Ui.label(this, "IP de destino — vazio = não enviar. Para enviar a dois destinos, separe por vírgula (o 1º é o PC)."))
        etPc = Ui.input(this, "ex.: 192.168.100.108", destinationText())
        conn.addView(etPc)
        pConn.addView(conn)

        btnHand = Ui.button(this, "") { prefs.edit().putBoolean("left", !prefs.getBoolean("left", false)).apply(); refreshLabels() }
        btnLayout = Ui.button(this, "") { pad.layout = 1 - pad.layout; prefs.edit().putInt("layout", pad.layout).apply(); refreshLabels() }
        btnVolUp = Ui.button(this, "") { prefs.edit().putInt("volUp", (prefs.getInt("volUp", 2) + 1) % names.size).apply(); refreshLabels() }
        btnVolDown = Ui.button(this, "") { prefs.edit().putInt("volDown", (prefs.getInt("volDown", 0) + 1) % names.size).apply(); refreshLabels() }
        val opts = Ui.section(this, "🎚", "Mão, layout e volume", "Qual mão este controle representa e o que os botões de volume fazem.")
        opts.addView(Ui.label(this, "Mão que este controle representa")); opts.addView(btnHand)
        opts.addView(Ui.label(this, "Layout dos botões na tela")); opts.addView(btnLayout)
        opts.addView(Ui.label(this, "Botões de volume (mapeáveis)")); opts.addView(btnVolUp); opts.addView(btnVolDown)
        pBtn.addView(opts)

        val edit = Ui.section(this, "✏️", "Botões na tela", "Ative/desative, mova e redimensione cada botão; analógico fixo ou flutuante.")
        edit.addView(Ui.label(this, "Botões na tela"))
        edit.addView(Ui.label(this, "Ative/desative cada botão e o analógico, mude posição e tamanho, e escolha analógico fixo ou flutuante (arrasta em qualquer lugar vazio da tela; o dedo fica preso ao analógico até soltar).", 12f, Ui.MUTED))
        edit.addView(Ui.button(this, "Editar botões e analógico") { padOpened = true; showPad(true) })
        pBtn.addView(edit)

        val gp = Ui.section(this, "🎮", "Gamepad", "Um mapeamento só: o que você mapear em “Mapear gamepad” vale no Moonlight, na Sala VR e aqui.")
        gp.addView(Ui.label(this, "Gamepad ligado ao celular (USB, Bluetooth ou outro; Joy-Con e Pro Controller também)"))
        tvPads = Ui.label(this, "", 13f, Color.WHITE)
        gp.addView(tvPads)
        gp.addView(Ui.label(this, "Os botões do gamepad viram os botões do controle pelo mapeamento global (RT = Trigger, RB = Grip, LB = R2, A/B/X/Y, Start, L3). Só valem os botões que estiverem ativos na tela de edição.", 12f, Ui.MUTED))
        btnStickSrc = Ui.button(this, "") { cycleStickSource() }
        gp.addView(btnStickSrc)
        gp.addView(Ui.button(this, "Mapear gamepad e inverter analógicos (vale para tudo)", true) { startActivity(Intent(this, GamepadMapActivity::class.java)) })
        pGp.addView(gp)

        // ---- eixos de rotação do celular-controle
        val axc = Ui.section(this, "🧭", "Eixos do celular", "Escolha qual eixo faz o quê e inverta cada um.")
        axc.addView(Ui.label(this, "Eixos do celular-controle (escolha qual eixo faz o quê e inverta cada um)"))
        for (o in 0..2) {
            val row = LinearLayout(this)
            val b1 = small("") { axSrc[o] = (axSrc[o] + 1) % 3; saveAxes(); refreshLabels() }
            val b2 = small("") { axInv[o] = !axInv[o]; saveAxes(); refreshLabels() }
            btnAx[o] = b1; btnAxInv[o] = b2
            row.addView(b1); row.addView(b2); axc.addView(row)
        }
        axc.addView(Ui.button(this, "Restaurar eixos") { for (i in 0..2) { axSrc[i] = i; axInv[i] = false }; saveAxes(); refreshLabels() })
        pAx.addView(axc)

        val six = Ui.section(this, "📍", "Posição (6DoF)", "ARCore (câmera deste celular) ou bolinhas lidas pelo celular do headset.")
        six.addView(Ui.label(this, "Posição (6DoF) do celular-controle"))
        btn6 = Ui.button(this, "") { cyclePosMode() }
        six.addView(btn6)
        six.addView(Ui.label(this, "ARCore: a câmera deste celular mede a posição (app na tela, câmera e luz). Bolinhas (prévia): este celular mostra bolinhas e o celular do HEADSET as lê. Em qualquer modo 6DoF o Arm Model é desligado só para este controle. Segure o celular na vertical, com o TOPO para a frente, e toque em Recentrar.", 12f, Ui.MUTED))
        for (o in 0..2) {
            val row = LinearLayout(this)
            val b1 = small("") { st.pSrc[o] = (st.pSrc[o] + 1) % 3; savePos6(); refreshLabels() }
            val b2 = small("") { st.pInv[o] = !st.pInv[o]; savePos6(); refreshLabels() }
            btnP6Ax[o] = b1
            row.addView(b1); row.addView(b2); six.addView(row)
            posInvBtn[o] = b2
        }
        btnP6Scale = Ui.button(this, "") { val k = st.pScale; st.pScale = if (k >= 2.9f) 0.5f else (k + 0.25f); savePos6(); refreshLabels() }
        six.addView(btnP6Scale)
        p6.addView(six)

        val sp = Ui.section(this, "✒️", "S Pen", "A S Pen vira um segundo controle ao ser removida do celular.")
        sp.addView(Ui.label(this, "S Pen (Galaxy S Ultra)"))
        btnSPen = Ui.button(this, "") { SPenBridge.enabled = !SPenBridge.enabled; refreshLabels() }
        btnSPenHand = Ui.button(this, "") { SPenBridge.handMode = (SPenBridge.handMode + 1) % 3; refreshLabels() }
        btnSPenGain = Ui.button(this, "") {
            val g = SPenBridge.gain; SPenBridge.gain = if (g >= 3.9f) 0.25f else g * 1.5f; refreshLabels()
        }
        btnSPenMode = Ui.button(this, "") { SPenBridge.buttonMode = 1 - SPenBridge.buttonMode; refreshLabels() }
        sp.addView(btnSPen); sp.addView(btnSPenHand); sp.addView(btnSPenGain); sp.addView(btnSPenMode)
        sp.addView(Ui.button(this, "Recentrar S Pen") { SPenBridge.recenter() })
        sp.addView(Ui.label(this, "Tire a S Pen do celular com o StormVR aberto: ela vira um controle (mova no ar para os lados/cima/baixo; botão lateral = trigger). Vale na Sala VR e no SteamVR (3DoF/6DoF).", 12f, Ui.MUTED))
        pSp.addView(sp)
        refreshLabels()

        // INICIAR A TRANSMISSÃO e ABRIR O CONTROLE são coisas separadas: na tela do controle, "Voltar" abre esta configuração sem parar nada.
        // topo fixo: transmitir / abrir o controle / recentrar / status; as abas ficam embaixo
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = Ui.button(this, "▶ Iniciar transmissão", true) { if (sender == null) startSending() else stopSending(); refresh() }
        btnOpen = Ui.button(this, "Abrir controle") { padOpened = true; showPad(false) }
        actions.addView(btnStart, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        actions.addView(btnOpen, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        header.addView(actions)
        header.addView(Ui.button(this, "🎯 Recentrar (apontar para a frente)") { ori.recenter(); ar?.recenter() })
        tvStatus = Ui.label(this, "", 13f, Color.WHITE)
        header.addView(tvStatus)
        root.addView(Ui.tabs(this, listOf("📡 Conexão" to pConn, "🔘 Botões" to pBtn, "🎮 Gamepad" to pGp, "🧭 Eixos" to pAx, "📍 6DoF" to p6, "✒️ S Pen" to pSp), 0, header), LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun mapText(id: String): String {
        val m = gpMap(id)
        val what = when {
            m < 0 -> "(sem mapeamento)"
            m >= 10000 -> "eixo " + MotionEvent.axisToString(m - 10000)
            else -> KeyEvent.keyCodeToString(m).removePrefix("KEYCODE_")
        }
        return what
    }

    private fun refreshLabels() {
        btnHand.text = if (prefs.getBoolean("left", false)) "Mão ESQUERDA" else "Mão DIREITA"
        btnLayout.text = if (pad.layout == 1) "Espelhado (canhoto)" else "Padrão (analógico à esquerda)"
        btnVolUp.text = "Volume + → " + names[prefs.getInt("volUp", 2) % names.size]
        btnVolDown.text = "Volume − → " + names[prefs.getInt("volDown", 0) % names.size]
        btnStickSrc.text = "Analógico da tela ← " + stickNames[stickSource()] + " (toque para trocar)"
        btn6.text = "Posição: " + arrayOf("desligada (3DoF)", "ARCore (câmera deste celular)", "Bolinhas (lidas pelo celular do headset) — prévia")[posMode()] + "  (toque para trocar)"
        for (o in 0..2) {
            btnAx[o]?.text = axNames[o] + " ← " + axNames[axSrc[o]]
            btnAxInv[o]?.text = if (axInv[o]) "Inverter: SIM" else "Inverter: não"
            btnP6Ax[o]?.text = "Posição " + p6Names[o] + " ← " + p6Names[st.pSrc[o]]
            posInvBtn[o]?.text = if (st.pInv[o]) "Inverter: SIM" else "Inverter: não"
        }
        btnP6Scale.text = "Escala da posição: %.2fx (toque para mudar)".format(st.pScale)
        btnSPen.text = if (SPenBridge.enabled) "S Pen como controle: LIGADA" else "S Pen como controle: desligada"
        btnSPenHand.text = "S Pen: mão " + arrayOf("AUTOMÁTICA (oposta à do celular)", "DIREITA", "ESQUERDA")[SPenBridge.handMode] + " (toque para trocar)"
        btnSPenGain.text = "Sensibilidade da S Pen: %.2fx (toque para mudar)".format(SPenBridge.gain)
        btnSPenMode.text = if (SPenBridge.buttonMode == 0) "Botão lateral: trigger direto" else "Botão lateral: clique = trigger, segurar = só mirar"
    }

    private fun showConfig() {
        pad.editMode = false
        editBar.visibility = View.GONE
        padRoot.visibility = View.GONE
        cfgView.visibility = View.VISIBLE
        refresh()
    }

    private fun showPad(edit: Boolean) {
        pad.editMode = edit
        editBar.visibility = if (edit) View.VISIBLE else View.GONE
        if (edit) updateEditBar()
        cfgView.visibility = View.GONE
        padRoot.visibility = View.VISIBLE
        pad.invalidate()
    }

    // ---------------------------------------------------------------- 6DoF
    /** 0 = sem posição (3DoF), 1 = ARCore (câmera deste celular), 2 = bolinhas (lidas pelo celular do headset). Compatível com o antigo "sixdof". */
    private fun posMode(): Int = if (prefs.contains("pos_mode")) prefs.getInt("pos_mode", 0).coerceIn(0, 2) else if (prefs.getBoolean("sixdof", false)) 1 else 0

    private fun cyclePosMode() {
        prefs.edit().putInt("pos_mode", (posMode() + 1) % 3).apply()
        applyPosMode()
        refreshLabels()
    }

    private fun applyPosMode() {
        val m = posMode()
        pad.dots = m == 2
        window.attributes = window.attributes.apply { screenBrightness = if (m == 2) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }   // bolinhas: brilho no máximo
        if (m != 1) { ar?.stop(); ar = null } else if (sender != null) start6dof()
        st.hasPos = false
    }

    private fun loadAxes() {
        for (i in 0..2) {
            axSrc[i] = prefs.getInt("axs$i", i).coerceIn(0, 2); axInv[i] = prefs.getBoolean("axi$i", false)
            st.pSrc[i] = prefs.getInt("p6s$i", i).coerceIn(0, 2); st.pInv[i] = prefs.getBoolean("p6i$i", false)
        }
        st.pScale = prefs.getFloat("p6scale", 1f).coerceIn(0.25f, 3f)
        pad.dots = posMode() == 2
    }
    private fun saveAxes() { val e = prefs.edit(); for (i in 0..2) { e.putInt("axs$i", axSrc[i]); e.putBoolean("axi$i", axInv[i]) }; e.apply() }
    private fun savePos6() { val e = prefs.edit(); for (i in 0..2) { e.putInt("p6s$i", st.pSrc[i]); e.putBoolean("p6i$i", st.pInv[i]) }; e.putFloat("p6scale", st.pScale); e.apply() }

    override fun onBackPressed() {
        if (padRoot.visibility == View.VISIBLE) showConfig()          // "Voltar" no controle = configuração (a transmissão continua)
        else super.onBackPressed()
    }

    private fun start6dof() {
        if (posMode() != 1 || ar != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), 71); return }
        val a = ArPose6(this, ori, st)
        if (a.start(root)) ar = a else Toast.makeText(this, a.status, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) start6dof()
            else Toast.makeText(this, "Sem permissão de câmera o 6DoF não funciona.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- envio
    private fun startSending() {
        if (!ori.available()) { Toast.makeText(this, "Sem sensor de rotação neste aparelho.", Toast.LENGTH_LONG).show(); return }
        val dest = etPc.text.toString().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (dest.isEmpty()) { Toast.makeText(this, "Informe o IP de destino (PC ou celular do headset).", Toast.LENGTH_SHORT).show(); return }
        val pc = dest[0]
        val hmd = dest.getOrNull(1) ?: ""
        prefs.edit().putString("pc", dest.joinToString(", ")).remove("hmd").apply()
        Prefs(this).host = pc                                   // a tela inicial usa o mesmo IP
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "stormvr:ctl").apply { acquire() }
        val s = ControllerSender(ori, st, pc, hmd, prefs.getBoolean("left", false), 100, axSrc, axInv)
        sender = s
        s.start()
        KeepAliveService.start(this, "Controle do celular enviando ao PC")
        pad.info = "Controle ativo (" + (if (prefs.getBoolean("left", false)) "esquerda" else "direita") + ")"
        start6dof()
        Toast.makeText(this, "Transmissão iniciada. Toque em \"Abrir controle\".", Toast.LENGTH_SHORT).show()
    }

    private fun stopSending() {
        sender?.shutdown(); sender = null
        if (!BtForward.enabled) KeepAliveService.stop(this)
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        st.volBits = 0; st.gpBits = 0; st.padBits = 0
        ar?.stop(); ar = null
    }

    private fun refresh() {
        btnStart.text = if (sender == null) "▶ Iniciar transmissão" else "■ Parar transmissão"
        btnOpen.text = if (padOpened) "Voltar ao controle" else "Abrir controle"
        val s = sender
        tvStatus.text = (if (s == null) "○ Parado" else "● Enviando %.0f pacotes/s (total %d)".format(s.rateHz, s.sent)) +
            (if (s != null && s.lastError.isNotEmpty()) "\n⚠ " + s.lastError else "") +
            (if (posMode() == 1) "\n" + (ar?.status ?: "6DoF: ligado (inicia com a transmissão)") else if (posMode() == 2) "\n6DoF por bolinhas: bolinhas visíveis na tela do controle" else "") +
            "\n" + SPenBridge.status
        tvPads.text = gamepadList()
        pad.info = if (s != null) "Controle ativo · %.0f pct/s".format(s.rateHz) + (if (st.hasPos) " · 6DoF" else "") + " · Voltar = configuração" else "Transmissão PARADA · Voltar = configuração"
        pad.postInvalidate()
    }

    // ---------------------------------------------------------------- gamepad
    private fun gamepadList(): String {
        val l = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            val s = d.sources
            if ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) l.add("🎮 " + d.name)
        }
        return if (l.isEmpty()) "Nenhum gamepad conectado" else l.joinToString("\n")
    }

    private fun isPadSource(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun defaultMap(id: String): Int = when (id) {
        "a" -> KeyEvent.KEYCODE_BUTTON_A; "b" -> KeyEvent.KEYCODE_BUTTON_B; "x" -> KeyEvent.KEYCODE_BUTTON_X; "y" -> KeyEvent.KEYCODE_BUTTON_Y
        "start" -> KeyEvent.KEYCODE_BUTTON_START; "l3" -> KeyEvent.KEYCODE_BUTTON_THUMBL
        "grip" -> KeyEvent.KEYCODE_BUTTON_L1; "r2" -> KeyEvent.KEYCODE_BUTTON_R1; "trigger" -> KeyEvent.KEYCODE_BUTTON_R2
        else -> -1
    }
    private fun gpMap(id: String): Int = prefs.getInt("gpmap_$id", defaultMap(id))
    private fun stickSource(): Int = prefs.getInt("gpstick", 0).coerceIn(0, 3)
    private fun cycleStickSource() { prefs.edit().putInt("gpstick", (stickSource() + 1) % stickNames.size).apply(); recomputeGamepad(); refreshLabels() }

    private fun startLearn(id: String) {
        learning = id
        Toast.makeText(this, if (id == "stick") "Mexa o analógico do gamepad" else "Aperte o botão do gamepad para " + (pad.item(id)?.label ?: id), Toast.LENGTH_SHORT).show()
        refreshLabels()
    }

    private fun axisDown(axis: Int): Boolean = axis in axes.indices && Math.abs(axes[axis]) > 0.5f

    private fun mappedDown(m: Int): Boolean {
        if (m < 0) return false
        if (m >= 10000) return axisDown(m - 10000)
        if (keysDown.contains(m)) return true
        if (m == KeyEvent.KEYCODE_BUTTON_R2) return axisDown(MotionEvent.AXIS_RTRIGGER) || axisDown(MotionEvent.AXIS_GAS)
        if (m == KeyEvent.KEYCODE_BUTTON_L2) return axisDown(MotionEvent.AXIS_LTRIGGER) || axisDown(MotionEvent.AXIS_BRAKE)
        return false
    }

    private fun recomputeGamepad() {
        var mask = 0
        for (i in pad.items) if (i.enabled) mask = mask or i.bit
        st.gpBits = GamepadMap.vrBits() and mask                       // MESMO mapeamento global de todo o app (sem segunda tabela)
        var x = 0f; var y = 0f
        if (pad.stickEnabled) when (stickSource()) {
            0 -> { x = GamepadMap.analog(GamepadMap.Fn.LSX); y = -GamepadMap.analog(GamepadMap.Fn.LSY) }
            1 -> { x = GamepadMap.analog(GamepadMap.Fn.RSX); y = -GamepadMap.analog(GamepadMap.Fn.RSY) }
            2 -> {
                x = axes[MotionEvent.AXIS_HAT_X]; y = -axes[MotionEvent.AXIS_HAT_Y]
                if (keysDown.contains(KeyEvent.KEYCODE_DPAD_LEFT)) x = -1f; if (keysDown.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) x = 1f
                if (keysDown.contains(KeyEvent.KEYCODE_DPAD_UP)) y = 1f; if (keysDown.contains(KeyEvent.KEYCODE_DPAD_DOWN)) y = -1f
            }
        }
        val dz = 0.12f
        if (Math.abs(x) < dz) x = 0f
        if (Math.abs(y) < dz) y = 0f
        st.gpX = x.coerceIn(-1f, 1f); st.gpY = y.coerceIn(-1f, 1f)
    }

    override fun dispatchKeyEvent(ev0: KeyEvent): Boolean {
        var ev = ev0
        if (isPadSource(ev0.source) && ev0.keyCode != KeyEvent.KEYCODE_VOLUME_UP && ev0.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            ev = GamepadMap.translate(ev0) ?: return true                  // mapeamento GLOBAL do gamepad (tela "Mapear gamepad")
            val down = ev.action == KeyEvent.ACTION_DOWN
            if (down) keysDown.add(ev.keyCode) else if (ev.action == KeyEvent.ACTION_UP) keysDown.remove(ev.keyCode)
            recomputeGamepad()
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (isPadSource(ev.source) && ev.action == MotionEvent.ACTION_MOVE) {
            GamepadMap.onMotion(ev)
            val dev = ev.device
            if (dev != null) for (r in dev.motionRanges) if (r.axis in axes.indices) axes[r.axis] = ev.getAxisValue(r.axis)
            recomputeGamepad()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---------------------------------------------------------------- volume
    private fun volBit(keyCode: Int): Int {
        val idx = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) prefs.getInt("volUp", 2) else prefs.getInt("volDown", 0)
        return bitOf[idx % bitOf.size]
    }

    // UM CLIQUE BASTA: o botão de volume solto muito rápido ficava menos tempo apertado que um ciclo de envio/rampa do driver e se perdia.
    // O bit continua ligado por pelo menos VOL_MIN_MS desde o aperto (se segurar mais, vale o tempo segurado).
    private val volDownAt = HashMap<Int, Long>()
    private val volRelease = HashMap<Int, Runnable>()
    private val VOL_MIN_MS = 140L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (sender != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val b = volBit(keyCode)
            if (b != 0) {
                volRelease.remove(keyCode)?.let { ui.removeCallbacks(it) }              // apertou de novo antes de soltar o anterior
                if ((event?.repeatCount ?: 0) == 0) volDownAt[keyCode] = android.os.SystemClock.uptimeMillis()
                st.volBits = st.volBits or b
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val b = volBit(keyCode)
            if (b != 0) {
                val held = android.os.SystemClock.uptimeMillis() - (volDownAt.remove(keyCode) ?: 0L)
                if (held >= VOL_MIN_MS) st.volBits = st.volBits and b.inv()
                else {
                    val r = Runnable { st.volBits = st.volBits and b.inv(); volRelease.remove(keyCode) }
                    volRelease[keyCode] = r
                    ui.postDelayed(r, VOL_MIN_MS - held)
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

/** ids dos itens do PadView na ordem da tela de mapeamento. */
object PadItems { val ids = listOf("trigger", "grip", "r2", "a", "b", "x", "y", "start", "l3") }
