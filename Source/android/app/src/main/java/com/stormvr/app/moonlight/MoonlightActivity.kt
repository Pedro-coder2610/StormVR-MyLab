package com.stormvr.app.moonlight

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.stormvr.app.Ui

/** Moonlight dentro do StormVR: PCs salvos, pareamento por PIN, lista de apps e início da transmissão. */
class MoonlightActivity : Activity() {
    private lateinit var st: MoonlightStore
    private lateinit var list: LinearLayout
    private lateinit var etIp: EditText
    private lateinit var tvStatus: TextView

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) com.stormvr.app.Immersive.apply(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.stormvr.app.Immersive.apply(this)
        st = MoonlightStore(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        refreshList()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun build(): ScrollView {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(24)); setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(this, "Moonlight", "Espelha a tela do PC (Sunshine / Apollo / GeForce) neste aparelho"))

        val add = Ui.card(this)
        add.addView(Ui.label(this, "IP do PC com Sunshine"))
        etIp = Ui.input(this, "ex.: 192.168.0.10", "")
        add.addView(etIp)
        add.addView(Ui.button(this, "Adicionar PC", true) {
            val ip = etIp.text.toString().trim()
            if (ip.isEmpty()) Toast.makeText(this, "Informe o IP do PC.", Toast.LENGTH_SHORT).show()
            else { st.addHost(ip); etIp.setText(""); refreshList() }
        })
        root.addView(add)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        val q = Ui.card(this)
        q.addView(Ui.label(this, "Qualidade da transmissão"))
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(real)
        val phone = maxOf(real.widthPixels, real.heightPixels).let { it - it % 2 } to minOf(real.widthPixels, real.heightPixels).let { it - it % 2 }
        val resOpts = (listOf(1280 to 720, 1920 to 1080, 2560 to 1440) + phone).distinct()          // a última é a resolução nativa deste celular
        phoneRes = phone
        q.addView(Ui.button(this, "") { cycleRes(resOpts) }.also { b -> resBtn = b })
        q.addView(Ui.button(this, "") { st.setFill(!st.fill()); refreshQuality() }.also { b -> fillBtn = b })
        q.addView(Ui.button(this, "") { st.setMouseMode((st.mouseMode() + 1) % 3); refreshQuality() }.also { b -> mouseBtn = b })
        q.addView(Ui.button(this, "") { st.setPadToPc(!st.padToPc()); refreshQuality() }.also { b -> padBtn = b })
        q.addView(Ui.button(this, "") { st.setPtrSame(!st.ptrSame()); refreshQuality() }.also { b -> ptrSameBtn = b })
        q.addView(Ui.button(this, "") { st.setPtrMode(0, 1 - st.ptrMode(0)); refreshQuality() }.also { b -> ptrBtn0 = b })
        q.addView(Ui.button(this, "") { st.setPtrMode(1, 1 - st.ptrMode(1)); refreshQuality() }.also { b -> ptrBtn1 = b })
        q.addView(Ui.button(this, "") { val o = listOf(30, 60, 90, 120); st.setFps(o[(o.indexOf(st.fps()) + 1).mod(o.size)]); refreshQuality() }.also { fpsBtn = it })
        q.addView(Ui.button(this, "") { val o = listOf(10000, 20000, 40000, 80000); st.setBitrateKbps(o[(o.indexOf(st.bitrateKbps()) + 1).mod(o.size)]); refreshQuality() }.also { brBtn = it })
        root.addView(q)
        refreshQuality()

        val tv = Ui.card(this)
        tv.addView(Ui.label(this, "TV da Sala 3D (modo TV)"))
        tv.addView(Ui.button(this, "") { chooseTvApp() }.also { tvAppBtn = it })
        tv.addView(Ui.button(this, "") { st.setLocalAudio(!st.localAudio()); refreshTv() }.also { tvAudioBtn = it })
        tv.addView(Ui.button(this, "") { st.setAskApp(!st.askApp()); refreshTv() }.also { tvAskBtn = it })
        root.addView(tv)
        refreshTv()

        tvStatus = Ui.label(this, "", 14f, Color.WHITE)
        root.addView(tvStatus)
        root.addView(Ui.label(this, "1) Adicione o IP do PC  2) Toque em “Conectar” — na primeira vez aparece um PIN: digite-o no Sunshine (https://IP-DO-PC:47990 → PIN)  3) Escolha o app e a transmissão abre em tela cheia.", 12f, Ui.MUTED))
        return ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
    }

    private var tvAppBtn: android.widget.Button? = null
    private var tvAudioBtn: android.widget.Button? = null
    private var tvAskBtn: android.widget.Button? = null
    private fun refreshTv() {
        tvAppBtn?.text = "App aberto na TV: ${st.tvApp()}  (tocar para trocar)"
        tvAskBtn?.text = "Perguntar o app ao abrir na Sala 3D: " + (if (st.askApp()) "SIM" else "não (abre o app acima)")
        tvAudioBtn?.text = "Áudio também no PC: " + (if (st.localAudio()) "LIGADO" else "desligado")
    }
    /** Lista os apps do PC (Desktop, Steam Big Picture, monitor secundário...) e guarda o escolhido para a TV da Sala 3D. */
    private fun chooseTvApp() {
        val ip = st.hosts().firstOrNull { st.paired(it) }
        if (ip == null) { Toast.makeText(this, "Pareie um PC primeiro.", Toast.LENGTH_LONG).show(); return }
        status("Buscando apps de $ip...")
        Thread {
            try {
                val http = NvHTTP(ComputerDetails.AddressTuple(ip, NvHTTP.DEFAULT_HTTP_PORT), 0, st.uniqueId(), st.cert(ip), AndroidCryptoProvider(this))
                val apps = http.appList
                runOnUiThread {
                    status("")
                    if (apps.isEmpty()) { Toast.makeText(this, "Nenhum app no PC.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    val names = apps.map { it.appName }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("App para a TV da Sala 3D").setItems(names) { _, i -> st.setTvApp(names[i]); refreshTv() }.show()
                }
            } catch (e: Exception) { status("Erro: ${e.message ?: e.javaClass.simpleName}") }
        }.start()
    }

    private var phoneRes = 0 to 0
    private var fillBtn: android.widget.Button? = null
    private var mouseBtn: android.widget.Button? = null
    private var padBtn: android.widget.Button? = null
    private var ptrSameBtn: android.widget.Button? = null
    private var ptrBtn0: android.widget.Button? = null
    private var ptrBtn1: android.widget.Button? = null
    private var resBtn: android.widget.Button? = null
    private var fpsBtn: android.widget.Button? = null
    private var brBtn: android.widget.Button? = null
    private fun cycleRes(o: List<Pair<Int, Int>>) {
        val i = o.indexOfFirst { it.first == st.width() && it.second == st.height() }
        val n = o[(i + 1).mod(o.size)]; st.setResolution(n.first, n.second); refreshQuality()
    }
    private fun refreshQuality() {
        mouseBtn?.text = "Mouse na tela cheia: " + arrayOf("ABSOLUTO (o dedo é o cursor)", "TOUCHPAD (arrastar move o cursor)", "TOQUE nativo (multitoque no PC)")[st.mouseMode().coerceIn(0, 2)]
        val pm = arrayOf("MOUSE (gatilho = clique, GRIP = botão direito)", "TOQUE (multitoque no PC)")
        ptrSameBtn?.text = "Telona (Sala 3D): " + (if (st.ptrSame()) "mesmo modo nos DOIS controles" else "modo diferente em CADA controle")
        ptrBtn0?.text = (if (st.ptrSame()) "Os dois controles: " else "Controle ESQUERDO: ") + pm[st.ptrMode(0).coerceIn(0, 1)]
        ptrBtn1?.visibility = if (st.ptrSame()) android.view.View.GONE else android.view.View.VISIBLE
        ptrBtn1?.text = "Controle DIREITO: " + pm[st.ptrMode(1).coerceIn(0, 1)]
        padBtn?.text = "Gamepad do celular → PC (tela cheia): " + (if (st.padToPc()) "LIGADO" else "desligado") + "  (Start 2x alterna)"
        fillBtn?.text = "Tela: " + (if (st.fill()) "preencher tudo (pode esticar)" else "manter proporção 16:9 (barras pretas)")
        resBtn?.text = "Resolução: ${st.width()}×${st.height()}" + (if (st.width() == phoneRes.first && st.height() == phoneRes.second) "  (nativa do celular)" else "")
        fpsBtn?.text = "Quadros por segundo: ${st.fps()}"
        brBtn?.text = "Taxa de bits: ${st.bitrateKbps() / 1000} Mbps"
    }

    private fun refreshList() {
        list.removeAllViews()
        for (ip in st.hosts()) {
            val c = Ui.card(this)
            c.addView(TextView(this).apply { text = ip; textSize = 18f; setTextColor(Color.WHITE) })
            c.addView(Ui.label(this, if (st.paired(ip)) "Pareado" else "Ainda não pareado", 12f, if (st.paired(ip)) Ui.OK else Ui.MUTED))
            c.addView(Ui.button(this, if (st.paired(ip)) "Conectar e escolher app" else "Parear (PIN)", true) { if (st.paired(ip)) openApps(ip) else pair(ip) })
            c.addView(Ui.button(this, "Remover") { st.removeHost(ip); refreshList() })
            list.addView(c)
        }
    }

    private fun status(t: String) = runOnUiThread { tvStatus.text = t }

    private fun pair(ip: String) {
        status("Conectando a $ip...")
        Thread {
            try {
                val http = NvHTTP(ComputerDetails.AddressTuple(ip, NvHTTP.DEFAULT_HTTP_PORT), 0, st.uniqueId(), null, AndroidCryptoProvider(this))
                val info = http.getServerInfo(true)                      // se isto funciona, o celular ESTÁ falando com o Sunshine do PC
                val host = Regex("<hostname>(.*?)</hostname>").find(info)?.groupValues?.get(1) ?: ip
                status("Conectado ao PC \"$host\". Aguardando o PIN...")
                val pin = String.format("%04d", java.security.SecureRandom().nextInt(10000))   // PIN gerado aqui (aleatório, 4 dígitos)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("PIN: $pin")
                        .setMessage("No PC \"$host\" abra  https://$ip:47990  (aceite o aviso do certificado), vá em PIN, digite  $pin  e confirme.\n\nO pareamento termina assim que você confirmar no PC.")
                        .setCancelable(false)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Abrir página do Sunshine") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://$ip:47990/pin"))) } catch (_: Exception) {}
                        }.show()
                }
                val pm = http.pairingManager
                val res = pm.pair(info, pin, null)                       // null = pareamento normal por PIN ("" ativaria o modo de senha única e o Sunshine recusaria)
                runOnUiThread {
                    if (res == PairingManager.PairState.PAIRED && pm.pairedCert != null) {
                        st.saveCert(ip, pm.pairedCert); status("Pareado com \"$host\"."); refreshList()
                    } else status("Pareamento falhou ($res). Confira se digitou o PIN $pin no Sunshine e tente de novo.")
                }
            } catch (e: Exception) { status("Erro ao falar com $ip: ${e.message ?: e.javaClass.simpleName}") }
        }.start()
    }

    private fun openApps(ip: String) {
        status("Buscando apps de $ip...")
        Thread {
            try {
                val http = NvHTTP(ComputerDetails.AddressTuple(ip, NvHTTP.DEFAULT_HTTP_PORT), 0, st.uniqueId(), st.cert(ip), AndroidCryptoProvider(this))
                val apps = http.appList
                runOnUiThread {
                    status("")
                    if (apps.isEmpty()) { Toast.makeText(this, "Nenhum app no PC.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    val names = apps.map { it.appName }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Escolha o que transmitir").setItems(names) { _, i ->
                        val a = apps[i]
                        startActivity(Intent(this, MoonlightStreamActivity::class.java)
                            .putExtra("ip", ip).putExtra("appId", a.appId).putExtra("appName", a.appName).putExtra("appUuid", a.appUUID ?: ""))
                    }.show()
                }
            } catch (e: Exception) { status("Erro: ${e.message ?: e.javaClass.simpleName} (se o PC reiniciou o Sunshine, pareie de novo)") }
        }.start()
    }
}
