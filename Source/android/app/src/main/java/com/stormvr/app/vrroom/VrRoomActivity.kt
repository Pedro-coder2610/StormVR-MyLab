package com.stormvr.app.vrroom

import com.stormvr.app.GamepadMap
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.stormvr.app.BleController
import com.stormvr.app.ControllerHub
import com.stormvr.app.HeadExtras
import com.stormvr.app.ControllerUdp
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class VrRoomActivity : Activity() {

    companion object {
        const val EXTRA_TRACK_ONLY = "trackonly"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        /** Largura da página em pixels CSS (menor = zoom maior). */
        private val WIDTHS = intArrayOf(960, 1100, 1280, 1440, 1600, 1920)
        private const val HOOK_JS = "(function(){if(window.__vrHook)return;window.__vrHook=1;" +
            "function ed(t){if(!t)return false;var n=(t.tagName||'').toLowerCase();" +
            "return n==='textarea'||(n==='input'&&!/^(button|checkbox|radio|submit|reset|file|image|range|color)$/i.test(t.type||'text'))||t.isContentEditable;}" +
            "document.addEventListener('focusin',function(e){VR.onFocus(ed(e.target));},true);" +
            "document.addEventListener('focusout',function(){setTimeout(function(){if(!ed(document.activeElement))VR.onFocus(false);},80);},true);})();"
        private const val D_NONE = 0
        private const val D_MOUSE = 1
        private const val D_RESIZE = 2
        private const val D_MOVE = 3
        private const val D_KBMOVE = 5
    }

    private val state = VrState()
    private val ui = VrUi()
    private lateinit var renderer: VrRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var startPanel: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var scaleLabel: TextView
    private lateinit var diagLabel: TextView

    // navegador (vive dentro de um display virtual, renderizado para a textura do GL)
    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    private var web: WebView? = null
    private var browserDpi = 0
    @Volatile private var texSurface: Surface? = null
    private var widthIdx = 2
    private var homeUrl = "https://www.google.com"
    @Volatile private var currentUrl = ""

    private var vrMode = false

    // mãos (MediaPipe)
    private var tracker: HandTracker? = null

    // 6DoF (ARCore)
    private var arSession: Session? = null
    private var installRequested = false

    // gamepad
    private var axL2 = 0f
    private var axR2 = 0f
    private var keyL2 = false
    private var keyR2 = false
    private var hatX = 0f
    private var hatY = 0f
    private var hatDx = 0
    private var hatDy = 0
    private var hatNext = 0L
    private var keyDx = 0f
    private var keyDy = 0f

    // interação dos lasers
    private val prevPressed = BooleanArray(2)
    private val drag = IntArray(2)
    private var mouseLaser = -1
    private val gripHeld = BooleanArray(2)
    private val dSide = FloatArray(2)
    private val dStartW = FloatArray(2)
    private val dStartAx = FloatArray(2)
    private val dStartAz = FloatArray(2)
    private val dStartWx = FloatArray(2)
    private val dStartWz = FloatArray(2)
    private val dRay0 = Array(2) { FloatArray(2) }          // ângulos (azimute, elevação) do raio ao começar a arrastar uma barra
    private val dOrb0 = Array(2) { FloatArray(2) }          // órbita (azimute, elevação) da tela/teclado nesse instante
    private val rayTmp = FloatArray(2)

    /** Azimute/elevação (graus) do raio do laser [i]: da mão ao ponto apontado. As barras de mover giram a tela/teclado pelo MESMO ângulo. */
    private fun rayAngles(i: Int, out: FloatArray) {
        val dx = state.hitX[i] - state.rox[i]; val dy = state.hitY[i] - state.roy[i]; val dz = state.hitZ[i] - state.roz[i]
        out[0] = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        out[1] = Math.toDegrees(atan2(dz.toDouble(), hypot(dx, dy).toDouble())).toFloat()
    }
    private fun wrap180(a: Float): Float { var x = (a + 540f) % 360f; if (x < 0) x += 360f; return x - 180f }
    private var downTime = 0L
    private var lastMx = -1f
    private var lastMy = -1f
    private val tmp = FloatArray(2)

    // teclado virtual
    private val urlText = StringBuilder()
    private var kbTargetUrl = false
    private val kcm: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
    private var flashUntil = 0L
    private var tbFlashUntil = 0L
    private var sigTb = 0
    private var sigKb = 0

    private var lastFrameNs = 0L

    private fun loadPrefs() {
        val p = getSharedPreferences("vrroom", Context.MODE_PRIVATE)
        state.ipdMm = p.getFloat("ipd", 64f).coerceIn(50f, 80f)
        state.sepMm = p.getFloat("sep", 0f).coerceIn(-20f, 20f)
        state.tolPct = p.getInt("tol", 50).coerceIn(0, 100)
        state.fovDeg = p.getFloat("fov", 90f).coerceIn(60f, 120f)
        state.k1 = p.getFloat("k1", 0f).coerceIn(0f, 0.8f)
        state.k2 = p.getFloat("k2", 0f).coerceIn(0f, 1.0f)
        state.sbs = p.getBoolean("sbs", false)
        fingerCtl = p.getBoolean("fingerctl", true)
        state.dwell = p.getBoolean("dwell", false)
        state.fsFill = p.getBoolean("fsfill", false)
        state.pcMode = p.getBoolean("pcmode", false)
        state.pcSendPos = p.getBoolean("pcpos", true)
        state.pcSendPinch = p.getBoolean("pcpinch", true)
        ControllerHub.swapHands = p.getBoolean("swap", false)
        hubInvYaw = p.getBoolean("iy", false)
        hubInvPitch = p.getBoolean("ip", false)
        state.handScale = p.getFloat("hscale", 1f).coerceIn(0.3f, 3f)
        state.handSource = p.getInt("hsrc", 0).coerceIn(0, 1)
        state.dotsLeft = p.getBoolean("dleft", false)
        state.dotsScreenW = p.getFloat("dscrw", 71f).coerceIn(40f, 120f)
        state.dotsScreenH = p.getFloat("dscrh", 153f).coerceIn(80f, 220f)
        if (state.handSource == 1) state.handsOn = true                     // as bolinhas usam o mesmo pipeline de câmera das mãos
        state.imuPos = p.getBoolean("imupos", true)
        state.handMaxFps = p.getInt("handfps", 30).coerceIn(10, 60)
        state.scrAz = p.getFloat("scraz", 0f).coerceIn(-180f, 180f)
        state.scrEl = p.getFloat("screl", 0f).coerceIn(-80f, 80f)
        state.scrYaw = p.getFloat("scryaw", 0f).coerceIn(-180f, 180f)
        state.scrPitch = p.getFloat("scrpitch", 0f).coerceIn(-180f, 180f)
        state.scrRoll = p.getFloat("scrroll", 0f).coerceIn(-180f, 180f)
    }

    private fun savePrefs() {
        getSharedPreferences("vrroom", Context.MODE_PRIVATE).edit()
            .putFloat("ipd", state.ipdMm)
            .putFloat("sep", state.sepMm)
            .putInt("tol", state.tolPct)
            .putFloat("fov", state.fovDeg)
            .putFloat("k1", state.k1)
            .putFloat("k2", state.k2)
            .putBoolean("sbs", state.sbs)
            .putBoolean("dwell", state.dwell)
            .putBoolean("fsfill", state.fsFill)
            .putBoolean("pcmode", state.pcMode)
            .putBoolean("pcpos", state.pcSendPos)
            .putBoolean("pcpinch", state.pcSendPinch)
            .putBoolean("swap", ControllerHub.swapHands)
            .putBoolean("iy", hubInvYaw)
            .putBoolean("fingerctl", fingerCtl)
            .putBoolean("ip", hubInvPitch)
            .putFloat("hscale", state.handScale)
            .putInt("hsrc", state.handSource).putBoolean("dleft", state.dotsLeft)
            .putFloat("dscrw", state.dotsScreenW).putFloat("dscrh", state.dotsScreenH)
            .putBoolean("imupos", state.imuPos)
            .putInt("handfps", state.handMaxFps)
            .putFloat("scraz", state.scrAz).putFloat("screl", state.scrEl)
            .putFloat("scryaw", state.scrYaw).putFloat("scrpitch", state.scrPitch).putFloat("scrroll", state.scrRoll)
            .apply()
    }

    /** Converte o ajuste em mm da distância entre as imagens para fração da largura da tela. */
    private fun updateSepFrac() {
        val fullW = if (glView.width > 0) glView.width else resources.displayMetrics.widthPixels
        val px = (state.sepMm / 2f) * resources.displayMetrics.xdpi / 25.4f      // cada imagem anda metade
        state.sepOffFrac = if (fullW > 0) px / fullW else 0f
    }

    private fun makeBar(maxV: Int, value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        max = maxV
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { savePrefs() }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dpiFor() = (160f * VrState.TEX_W / WIDTHS[widthIdx]).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) com.stormvr.app.Immersive.apply(this) }

    // =================================================================== ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.stormvr.app.Immersive.apply(this)
        GamepadMap.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { val lp0 = window.attributes; lp0.preferredRefreshRate = 60f; window.attributes = lp0 } catch (_: Throwable) {}   // 120 Hz só esquenta: 60 Hz basta
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        loadPrefs()
        renderer = VrRenderer(this, state, ui) { s -> runOnUiThread { onTexSurface(s) } }
        glView = GLSurfaceView(this).apply {
            setOnTouchListener { _, ev -> if (vrMode && state.fullscreen) { forwardFullscreenTouch(ev); true } else if (vrMode && fingerCtl) { fingerTouch(ev); true } else false }
            setEGLContextClientVersion(2)
            setZOrderOnTop(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            visibility = View.GONE
        }
        startPanel = buildStartPanel()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 16, 22))
            addView(glView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(startPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        requestedOrientation = if (com.stormvr.app.Prefs(this).orientation == 1) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                               else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE   // travada: sem rotação automática
        state.rotation = fixedRotation()
        state.sixDof = com.stormvr.app.Prefs(this).sixDof          // 3DoF/6DoF: uma escolha só, na tela inicial
        state.predMs = com.stormvr.app.Prefs(this).predictionMs.toFloat()
        state.headHz = com.stormvr.app.Prefs(this).rateHz.coerceIn(50, 120)
        state.pinchSmooth = getSharedPreferences("vrroom", Context.MODE_PRIVATE).getFloat("pinchsmooth", 0.5f).coerceIn(0f, 1f)
        state.clickFreezeMs = getSharedPreferences("vrroom", Context.MODE_PRIVATE).getInt("clickfreeze", 120).coerceIn(0, 400)
        if (intent.getBooleanExtra(EXTRA_TRACK_ONLY, false)) {
            // 6DoF: mesmo motor da Sala 3D (6DoF, mãos, envio ao SteamVR), mas com a telona em TELA CHEIA mostrando o Moonlight
            // (em vez da antiga tela preta). A sala 3D não é desenhada (só a textura da tela).
            trackSession = true
            tvApp = TvApp.MOONLIGHT
            state.pcMode = true
            glView.post { enterVr(true) }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 77)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 77) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
                if (vrMode) startAr()
            } else {
                state.sixDof = false
                state.handsOn = false
                toast("Sem permissão da câmera: 6DoF e mãos desativados (só rotação).")
            }
        } else if (code == 78) {
            if (vrMode && BleController.hasPermission(this)) BleController.start(applicationContext)
            else if (!BleController.hasPermission(this)) toast("Sem permissão de Bluetooth: os controles ESP32 só funcionam por Wi-Fi.")
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (vrMode) {
            renderer.startSensors()
            startAr()
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCb)
        }
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCb)
        stopAr()
        renderer.stopSensors()
        try { vd?.surface = null } catch (_: Exception) {}
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopPcLink()
        stopHub()
        stopAr()
        tracker = null
        renderer.tracker = null
        try { arSession?.close() } catch (_: Exception) {}
        arSession = null
        renderer.arSession = null
        teardownBrowser()
        try { appLauncher?.release() } catch (_: Exception) {}
        appLauncher = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (trackSession) { exitVr(); finish(); return }
        if (vrMode) exitVr() else super.onBackPressed()
    }

    // =================================================================== tela inicial (no celular)
    private fun buildStartPanel(): LinearLayout {
        val title = TextView(this).apply {
            text = "StormVR — Sala VR"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        urlField = EditText(this).apply {
            setSingleLine()
            setText("https://www.google.com")
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        scaleLabel = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val zoomMinus = Button(this).apply {
            text = "Zoom −"
            setOnClickListener { stepScale(+1) }
        }
        val zoomPlus = Button(this).apply {
            text = "Zoom +"
            setOnClickListener { stepScale(-1) }
        }
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(zoomMinus)
            addView(scaleLabel, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(zoomPlus)
        }
        val stereoBox = CheckBox(this).apply {
            text = "Modo 3D (estéreo)"
            isChecked = true
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.stereo = c }
        }
        val curveBox = CheckBox(this).apply {
            text = "Tela curva"
            isChecked = true
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.curved = c }
        }
        val dofBox = CheckBox(this).apply {
            text = "6DoF (câmera + ARCore)"
            isChecked = true
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.sixDof = c }
        }
        val handsBox = CheckBox(this).apply {
            text = "Rastreamento de mãos"
            isChecked = true
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.handsOn = c }
        }
        val hlaserBox = CheckBox(this).apply {
            text = "Ponteiro das mãos (bolinha na pinça)"
            isChecked = true
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.handLasers = c }
        }
        val twoBox = CheckBox(this).apply {
            text = "Rastrear as duas mãos (mais pesado; desmarcado = 1 mão, mais fluido)"
            isChecked = false
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.twoHands = c }
        }
        val liteBox = CheckBox(this).apply {
            text = "Modo leve (menos detalhes e resolução, mais FPS)"
            isChecked = false
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.lite = c }
        }
        val camBox = CheckBox(this).apply {
            text = "Modo câmera (sem sala, só a tela flutuante)"
            isChecked = false
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.passthrough = c }
        }
        val startBtn = Button(this).apply {
            background = com.stormvr.app.Ui.gradient(this@VrRoomActivity, 16); setTextColor(Color.WHITE); isAllCaps = false; stateListAnimator = null
            text = "ENTRAR NO MODO VR"
            textSize = 18f
            setOnClickListener { enterVr() }
        }
        val pcBox = CheckBox(this).apply {
            text = "MODO PC (SteamVR): enviar cabeça e mãos ao driver (IP do PC: o da tela inicial)"
            isChecked = state.pcMode
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.pcMode = c; savePrefs() }
        }
        val btFwdBox = CheckBox(this).apply {
            text = "   Enviar controles Bluetooth (ESP32) ao PC"
            isChecked = com.stormvr.app.Prefs(this@VrRoomActivity).btForward
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c ->
                val pr = com.stormvr.app.Prefs(this@VrRoomActivity)
                pr.btForward = c
                com.stormvr.app.BtForward.host = pr.host
                com.stormvr.app.BtForward.enabled = c
            }
        }
        val pcPosBox = CheckBox(this).apply {
            text = "   Enviar a POSIÇÃO das mãos"
            isChecked = state.pcSendPos
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.pcSendPos = c; pcLink?.sendPos = c; savePrefs() }
        }
        val pcPinchBox = CheckBox(this).apply {
            text = "   Enviar a PINÇA (vira Trigger no SteamVR)"
            isChecked = state.pcSendPinch
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.pcSendPinch = c; pcLink?.sendPinch = c; savePrefs() }
        }
        val fsBtn = Button(this).apply {
            background = com.stormvr.app.Ui.round(com.stormvr.app.Ui.CARD2, 16, this@VrRoomActivity, com.stormvr.app.Ui.STROKE); setTextColor(Color.WHITE); isAllCaps = false; stateListAnimator = null
            text = "TELA CHEIA (usar a tela toda do celular, toque direto)"
            setOnClickListener { enterVr(true) }
        }
        val fsFillBox = CheckBox(this).apply {
            text = "Tela cheia: esticar até as bordas (senão mantém a proporção 16:9)"
            isChecked = state.fsFill
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.fsFill = c; savePrefs() }
        }
        val help = TextView(this).apply {
            text = "Botões (iguais no gamepad e nos controles ESP32/celular): B = voltar (fecha o teclado) • X = recarregar (apagar) • Y = centrar (espaço) • Start = liga/desliga o 3D (Enter) • clique do analógico = teclado • A = tecla em foco\n" +
                "Clique/arrastar: Trigger, R2/L2 ou pinça (polegar+indicador). Dois controles ou duas mãos ao mesmo tempo = dois dedos (zoom). Analógico dos controles e L1/R1 do gamepad rolam a página.\n" +
                "Teclado virtual: D-pad move o foco, A digita\n" +
                "Select/Voltar: sair do VR"
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        diagLabel = TextView(this).apply {
            setTextColor(Color.rgb(255, 205, 110))
            textSize = 11f
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }

        // ---- ajustes finos ----
        val ipdLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val sepLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val tolLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val hsLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        fun refreshLabels() {
            ipdLabel.text = "Distância entre os olhos (profundidade 3D): ${state.ipdMm.toInt()} mm"
            sepLabel.text = "Distância entre o centro das 2 imagens: ${if (state.sepMm > 0) "+" else ""}${state.sepMm.toInt()} mm"
            tolLabel.text = "Tolerância de clique / estabilização da mão: ${state.tolPct}%"
            hsLabel.text = "Tamanho da mão (calibra a distância das mãos): ${(state.handScale * 100).toInt()}%"
        }
        val ipdBar = makeBar(30, (state.ipdMm - 50f).toInt()) { p ->
            state.ipdMm = 50f + p
            refreshLabels()
        }
        val sepBar = makeBar(40, (state.sepMm + 20f).toInt()) { p ->
            state.sepMm = p - 20f
            updateSepFrac()
            refreshLabels()
        }
        val tolBar = makeBar(100, state.tolPct) { p ->
            state.tolPct = p
            refreshLabels()
        }
        val fovLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        val lensLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        fun refreshLens() {
            fovLabel.text = "FOV de cada olho (modo 3D): ${state.fovDeg.toInt()}°"
            lensLabel.text = "Distorção da lente: ${if (state.k1 < 0.005f && state.k2 < 0.005f) "desligada" else "%.2f".format(state.k1)}"
        }
        val fovBar = makeBar(60, (state.fovDeg - 60f).toInt()) { p -> state.fovDeg = 60f + p; refreshLens() }
        val lensBar = makeBar(80, (state.k1 * 100f).toInt()) { p ->
            state.k1 = p / 100f
            state.k2 = if (p == 0) 0f else (p / 100f) * 1.6f          // mesma proporção do driver SteamVR (k2 ≈ 1,6 × k1)
            refreshLens()
        }
        val freezeLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        fun refreshFreeze() { freezeLabel.text = "Estabilizar o clique: ${state.clickFreezeMs} ms (ponteiro parado ao apertar o gatilho)" }
        refreshFreeze()
        val freezeBar = makeBar(400, state.clickFreezeMs) { p ->
            state.clickFreezeMs = p
            refreshFreeze()
            getSharedPreferences("vrroom", Context.MODE_PRIVATE).edit().putInt("clickfreeze", p).apply()
        }
        val pinchLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        fun refreshPinch() { pinchLabel.text = "Suavização da bolinha da pinça: ${(state.pinchSmooth * 100).toInt()}% (0 = cola nos dedos)" }
        refreshPinch()
        val pinchBar = makeBar(100, (state.pinchSmooth * 100f).toInt()) { p ->
            state.pinchSmooth = p / 100f
            refreshPinch()
            getSharedPreferences("vrroom", Context.MODE_PRIVATE).edit().putFloat("pinchsmooth", state.pinchSmooth).apply()
        }
        val hsBar = makeBar(50, (state.handScale * 100f - 80f).toInt().coerceIn(0, 50)) { p ->
            state.handScale = (80 + p) / 100f
            refreshLabels()
        }
        val imuBox = CheckBox(this).apply {
            text = "6DoF assistido pelo acelerômetro (menos atraso na posição)"
            isChecked = state.imuPos
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.imuPos = c; savePrefs() }
        }
        refreshLabels()
        refreshLens()
        val sbsBox = CheckBox(this).apply {
            text = "Conteúdo 3D lado a lado (SBS): cada olho vê uma metade da tela"
            isChecked = state.sbs
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.sbs = c; savePrefs() }
        }
        val dwellBox = CheckBox(this).apply {
            text = "Clique por olhar (botões e teclado, sem controle nem mãos)"
            isChecked = state.dwell
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> state.dwell = c; savePrefs() }
        }
        val swapBox = CheckBox(this).apply {
            text = "Controles ESP32/celular: trocar mãos (direita ↔ esquerda)"
            isChecked = ControllerHub.swapHands
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> ControllerHub.swapHands = c; savePrefs() }
        }
        val invYawBox = CheckBox(this).apply {
            text = "Controles: inverter esquerda/direita do apontar"
            isChecked = hubInvYaw
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> hubInvYaw = c; savePrefs() }
        }
        val invPitchBox = CheckBox(this).apply {
            text = "Controles: inverter cima/baixo do apontar"
            isChecked = hubInvPitch
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> hubInvPitch = c; savePrefs() }
        }
        val fingerBox = CheckBox(this).apply {
            text = "Dedos na tela como controles (metade esquerda = esquerdo, direita = direito). Tocar = clique; segurar um instante e arrastar = mover a mira"
            isChecked = fingerCtl
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> fingerCtl = c; if (!c) fAim.reset(); savePrefs() }
        }
        updateScaleLabel()

        fun mkPage() = com.stormvr.app.Ui.page(this)
        val pgNav = mkPage(); val pgTela = mkPage(); val pgMaos = mkPage(); val pgPc = mkPage(); val pgLens = mkPage(); val pgSpen = mkPage(); val pgAdv = mkPage()
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            var tgt: LinearLayout = pgNav
            fun addView(v: View) { tgt.addView(v) }                  // cada addView abaixo vai para a página (aba) atual
            fun head(t: String) = TextView(this@VrRoomActivity).apply {
                text = t; textSize = 15f; setTextColor(com.stormvr.app.Ui.ACCENT); setPadding(0, dp(14), 0, dp(4))
            }
            // ---- navegador
            addView(urlField)
            addView(zoomRow)
            // ---- tela e mundo
            tgt = pgTela
            addView(head("Tela e mundo"))
            addView(stereoBox)
            addView(curveBox)
            // posição e rotação da TELA: órbita em volta de você + giro sobre o próprio centro nos 3 eixos
            addView(TextView(this@VrRoomActivity).apply { text = "Posição e rotação da tela"; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(10), 0, dp(2)) })
            fun scrBar(name: String, get: () -> Float, set: (Float) -> Unit, lo: Int, hi: Int) {
                val lb = TextView(this@VrRoomActivity).apply { setTextColor(Color.rgb(214, 180, 255)); textSize = 13f }
                fun upd() { lb.text = name + ": " + get().toInt() + "°" }
                upd()
                addView(lb)
                addView(makeBar(hi - lo, get().toInt() - lo) { v -> set((v + lo).toFloat()); upd() })
            }
            scrBar("Órbita em volta de você (← esquerda / direita →)", { state.scrAz }, { state.scrAz = it }, -180, 180)
            scrBar("Órbita para cima / baixo", { state.scrEl }, { state.scrEl = it }, -80, 80)
            scrBar("Girar a tela — esquerda/direita (eixo vertical)", { state.scrYaw }, { state.scrYaw = it }, -180, 180)
            scrBar("Girar a tela — inclinar para cima/baixo", { state.scrPitch }, { state.scrPitch = it }, -180, 180)
            scrBar("Girar a tela — rolar (eixo da tela)", { state.scrRoll }, { state.scrRoll = it }, -180, 180)
            addView(Button(this@VrRoomActivity).apply {
                text = "Tela reta à frente (zerar)"; isAllCaps = false
                setOnClickListener { state.scrAz = 0f; state.scrEl = 0f; state.scrYaw = 0f; state.scrPitch = 0f; state.scrRoll = 0f; savePrefs(); toast("Tela zerada: reinicie a Sala para atualizar os controles do painel") }
            })
            addView(camBox)
            addView(liteBox)
            // ---- rastreamento (3DoF/6DoF e Bluetooth ficam só na tela inicial: uma escolha, sem repetir)
            tgt = pgMaos
            addView(head("Rastreamento"))
            addView(handsBox)
            addView(fingerBox)
            addView(com.stormvr.app.ExtrasUi.build(this@VrRoomActivity))
            addView(hlaserBox)
            addView(twoBox)
            addView(TextView(this@VrRoomActivity).apply {
                text = "3DoF/6DoF e controles Bluetooth: escolha na tela inicial do StormVR."
                textSize = 12f; setTextColor(Color.GRAY)
            })
            // ---- modo PC
            tgt = pgPc
            addView(head("Modo PC (SteamVR)"))
            addView(pcBox)
            addView(pcPosBox)
            addView(pcPinchBox)
            // ---- avançado (recolhido)
            var advTgt: LinearLayout = pgLens
            fun av(v: View) { advTgt.addView(v) }
            av(ipdLabel); av(ipdBar)
            av(sepLabel); av(sepBar)
            av(tolLabel); av(tolBar)
            av(fovLabel); av(fovBar)
            av(lensLabel); av(lensBar)
            advTgt = pgMaos
            av(hsLabel); av(hsBar)
            // ---- fonte das mãos: mãos (câmera) OU controle 6DoF com bolinhas (prévia)
            val dotsInfo = TextView(this@VrRoomActivity).apply { setTextColor(Color.rgb(214, 180, 255)); textSize = 12f }
            val srcBtn = Button(this@VrRoomActivity).apply { isAllCaps = false }
            val dotHandBtn = Button(this@VrRoomActivity).apply { isAllCaps = false }
            val calBtn = Button(this@VrRoomActivity).apply { isAllCaps = false; text = "Calibrar escala: controle a 40 cm da câmera (agora)" }
            val wLabel = TextView(this@VrRoomActivity).apply { setTextColor(Color.WHITE) }
            val hLabel = TextView(this@VrRoomActivity).apply { setTextColor(Color.WHITE) }
            fun refreshDots() {
                srcBtn.text = if (state.handSource == 1) "Fonte do 6DoF das mãos: CONTROLE COM BOLINHAS (prévia)" else "Fonte do 6DoF das mãos: MÃOS (câmera)"
                dotHandBtn.text = "O controle com bolinhas vale como a mão: " + (if (state.dotsLeft) "ESQUERDA" else "DIREITA")
                wLabel.text = "Largura da TELA do celular-controle: ${state.dotsScreenW.toInt()} mm"
                hLabel.text = "Altura da TELA do celular-controle: ${state.dotsScreenH.toInt()} mm"
                dotsInfo.text = if (state.handSource == 1) "Bolinhas: " + (if (state.dotsSeen) "vendo as 5 (distância %.0f cm)".format(state.dotsDepthRaw * state.handScale * 100f) else "não vejo as 5 bolinhas") else
                    "No celular-controle: Modo controle > Posição > Bolinhas, e abra o controle. Ele mostra 5 bolinhas coloridas."
            }
            srcBtn.setOnClickListener { state.handSource = 1 - state.handSource; if (state.handSource == 1) state.handsOn = true; savePrefs(); refreshDots() }
            dotHandBtn.setOnClickListener { state.dotsLeft = !state.dotsLeft; savePrefs(); refreshDots() }
            calBtn.setOnClickListener {
                // com o celular-controle na mesa a 40 cm da câmera do headset: ajusta a escala para a distância medida bater com 40 cm
                if (state.handSource == 1 && state.dotsSeen && state.dotsDepthRaw > 0.05f) {
                    state.handScale = (0.40f / state.dotsDepthRaw).coerceIn(0.3f, 3f); savePrefs(); refreshLabels(); refreshDots()
                    toast("Escala calibrada: ${(state.handScale * 100).toInt()}%")
                } else toast("Precisa estar na Sala com o modo bolinhas ligado e vendo as 5 bolinhas")
            }
            av(srcBtn); av(dotHandBtn); av(wLabel)
            av(makeBar(80, (state.dotsScreenW - 40f).toInt().coerceIn(0, 80)) { p -> state.dotsScreenW = 40f + p; savePrefs(); refreshDots() })
            av(hLabel)
            av(makeBar(140, (state.dotsScreenH - 80f).toInt().coerceIn(0, 140)) { p -> state.dotsScreenH = 80f + p; savePrefs(); refreshDots() })
            av(calBtn); av(dotsInfo)
            refreshDots()
            advTgt = pgSpen
            com.stormvr.app.SPenUi.addTo(this@VrRoomActivity, pgSpen)   // S Pen como controle (celular usado como headset)
            advTgt = pgAdv
            av(pinchLabel); av(pinchBar)
            av(freezeLabel); av(freezeBar)
            av(sbsBox); av(fsFillBox); av(dwellBox)
            av(swapBox); av(invYawBox); av(invPitchBox)
            av(imuBox)
            av(help)
            av(diagLabel)
            val predBtn = Button(this@VrRoomActivity).apply {
                isAllCaps = false
                fun upd() { text = "Previsão de rotação: " + (if (state.predMs <= 0f) "desligada" else "%.0f ms".format(state.predMs)) }
                upd()
                setOnClickListener {
                    val o = floatArrayOf(0f, 15f, 22f, 30f, 40f, 60f)
                    val i = o.indexOfFirst { it >= state.predMs - 0.5f }.let { if (it < 0) 0 else it }
                    state.predMs = o[(i + 1) % o.size]
                    com.stormvr.app.Prefs(this@VrRoomActivity).predictionMs = state.predMs.toInt()
                    upd()
                }
            }
            av(predBtn)
            val fpsBtn = Button(this@VrRoomActivity).apply {
                isAllCaps = false
                fun upd() { text = "Rastreamento de mãos: no máximo ${state.handMaxFps} quadros/s (menos = menos calor)" }
                upd()
                setOnClickListener {
                    val o = intArrayOf(15, 20, 30, 45, 60)
                    val i = o.indexOfFirst { it >= state.handMaxFps }.let { if (it < 0) 0 else it }
                    state.handMaxFps = o[(i + 1) % o.size]
                    savePrefs()
                    upd()
                }
            }
            av(fpsBtn)
        }
        // cabeçalho (título, Entrar, tela cheia, apoio) ROLA junto com as abas: ao rolar para baixo ele sobe e some, sobrando a tela toda para as opções
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(fsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(Button(this@VrRoomActivity).apply {
                text = "💜  Me ajude a continuar melhorando o StormVR"; isAllCaps = false; setTextColor(Color.WHITE); stateListAnimator = null
                background = com.stormvr.app.Ui.round(com.stormvr.app.Ui.CARD2, 16, this@VrRoomActivity, com.stormvr.app.Ui.ACCENT)
                setOnClickListener { startActivity(android.content.Intent(this@VrRoomActivity, com.stormvr.app.SettingsActivity::class.java).putExtra(com.stormvr.app.SettingsActivity.EXTRA_TAB, com.stormvr.app.SettingsActivity.TAB_SUPPORT)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        }
        val tabsView = com.stormvr.app.Ui.tabs(this, listOf(
            "🌐 Navegador" to pgNav, "🖥 Tela" to pgTela, "✋ Mãos e 6DoF" to pgMaos, "🔭 Lente" to pgLens,
            "✒️ S Pen" to pgSpen, "🎮 Modo PC" to pgPc, "⚙ Avançado" to pgAdv), 0, header)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            background = com.stormvr.app.Ui.screenBg()
            addView(tabsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun updateScaleLabel() {
        scaleLabel.text = "Largura da página: ${WIDTHS[widthIdx]} px"
    }

    /** delta -1 = mais zoom (página mais estreita), +1 = menos zoom. */
    private fun stepScale(delta: Int) {
        val n = (widthIdx + delta).coerceIn(0, WIDTHS.size - 1)
        if (n == widthIdx) return
        widthIdx = n
        updateScaleLabel()
        if (vrMode) texSurface?.let { buildBrowser(it, dpiFor()) }
    }

    // =================================================================== navegador em display virtual
    private fun onTexSurface(s: Surface?) {
        texSurface = s
        if (s == null) return
        val src = screenSource
        if (src != null) { src.attach(s, VrState.TEX_W, VrState.TEX_H); return }
        val dpi = dpiFor()
        if (vd == null || dpi != browserDpi) buildBrowser(s, dpi)
        else try { vd?.surface = s } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildBrowser(s: Surface, dpi: Int) {
        val saved = Bundle()
        val old = web
        val hadPage = old != null
        val prevUrl = old?.url ?: homeUrl
        old?.saveState(saved)
        teardownBrowser()

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val v = try {
            dm.createVirtualDisplay(
                "vr-browser", VrState.TEX_W, VrState.TEX_H, dpi, s,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        } catch (e: Exception) { null }
        if (v == null) {
            toast("Não consegui criar o display virtual do navegador.")
            return
        }
        vd = v
        browserDpi = dpi

        try {
            val p = Presentation(this, v.display)
            val w = WebView(p.context)
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = DESKTOP_UA
                setSupportZoom(true)                  // pinça com dois controles/dedos dá zoom na página
                builtInZoomControls = true
                displayZoomControls = false
            }
            w.setBackgroundColor(Color.WHITE)
            w.webChromeClient = WebChromeClient()
            w.addJavascriptInterface(object {
                @JavascriptInterface
                fun onFocus(editable: Boolean) {
                    runOnUiThread {
                        if (editable) {
                            if (!state.kbVisible) { kbTargetUrl = false; showKeyboard(true) }
                        } else if (state.kbVisible && !kbTargetUrl) {
                            showKeyboard(false)
                        }
                    }
                }
            }, "VR")
            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    view.evaluateJavascript(HOOK_JS, null)
                }
            }
            val box = FrameLayout(p.context)
            box.addView(w, FrameLayout.LayoutParams(-1, -1))
            val home = TvHome(p.context) { app -> showTv(app) }
            box.addView(home, FrameLayout.LayoutParams(-1, -1))
            val moon = MoonlightPane(p.context, this)
            moon.onExit = { runOnUiThread { showTv(TvApp.HOME) } }
            box.addView(moon, FrameLayout.LayoutParams(-1, -1))
            val saber = StormSaberPane(p.context).also { it.handleTouch = false }        // os dedos viram sabres/lasers aqui na Sala (updateFingerControllers)
            box.addView(saber, FrameLayout.LayoutParams(-1, -1))
            val al = appLauncher ?: com.stormvr.app.applauncher.AppLauncher(this).also { appLauncher = it }
            val apps = com.stormvr.app.applauncher.AppLauncherPane(p.context, al, false)
            box.addView(apps, FrameLayout.LayoutParams(-1, -1))
            val hb = TextView(p.context).apply {
                text = "⌂  Home"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                setPadding(26, 12, 26, 12); isClickable = true
                background = GradientDrawable().apply { setColor(Color.argb(170, 22, 13, 38)); cornerRadius = 40f }
                setOnClickListener { showTv(TvApp.HOME) }
            }
            // (o botão Home agora fica na barra de baixo da telona; o antigo, solto no canto, foi removido)
            tvBox = box; tvHome = home; tvMoon = moon; tvSaber = saber; tvHomeBtn = hb; tvApps = apps
            p.setContentView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            p.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            p.show()
            pres = p
            web = w
            val restored = hadPage && w.restoreState(saved) != null
            if (!restored) w.loadUrl(prevUrl)
            applyTvVisibility()
        } catch (e: Exception) {
            toast("Erro ao criar o navegador: ${e.message}")
        }
    }

    private fun teardownBrowser() {
        try { tvMoon?.close() } catch (_: Exception) {}
        tvBox = null; tvHome = null; tvMoon = null; tvSaber = null; tvHomeBtn = null; tvApps = null
        try { pres?.dismiss() } catch (_: Exception) {}
        try { web?.destroy() } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        pres = null
        web = null
        vd = null
    }

    private fun normalizeUrl(text: String): String {
        val t = text.trim()
        return when {
            t.isEmpty() -> "https://www.google.com"
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
    }

    private fun go(text: String) {
        web?.loadUrl(normalizeUrl(text))
    }

    // =================================================================== entrar / sair do VR
    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            ) else 0
    }

    /** Modo leve: renderiza em ~70% da resolução (a imagem é ampliada) e usa uma sala simplificada. */
    private fun applyLite() {
        val w = glView.width
        val h = glView.height
        if (w <= 0 || h <= 0) return
        if (state.lite) {
            glView.holder.setFixedSize((w * 0.7f).toInt().coerceAtLeast(480), (h * 0.7f).toInt().coerceAtLeast(270))
        } else {
            glView.holder.setSizeFromLayout()
        }
    }

    private fun enterVr(fullscreen: Boolean = false) {
        state.fullscreen = fullscreen
        if (!renderer.hasSensor && !fullscreen) toast("Este aparelho não tem sensor de rotação (giroscópio).")
        homeUrl = normalizeUrl(urlField.text.toString())
        if (currentUrl.isEmpty()) currentUrl = homeUrl
        vrMode = true
        applyExtras(); extras?.recenter()
        startPanel.visibility = View.GONE
        glView.visibility = View.VISIBLE
        glView.post { applyLite(); updateSepFrac() }
        applyImmersive(true)
        state.recenter = true
        if (!fullscreen) {
            startHub()
            renderer.startSensors()
            startAr()
        } else if (state.pcMode) {                         // tela cheia + modo PC: só cabeça e mãos (sem controles na tela)
            renderer.startSensors()
            startAr()
            if (com.stormvr.app.Prefs(this).btForward) startHub()      // controles Bluetooth -> PC mesmo sem a sala
        }                                                  // tela cheia comum = só a página com toque direto
        startPcLink()
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun exitVr() {
        vrMode = false
        extras?.stop()
        stopHub()
        stopPcLink()
        state.fullscreen = false
        val diag = tracker?.info()
        stopAr()
        diagLabel.text = if (diag != null) "Último diagnóstico das mãos: $diag" else ""
        Log.i("VRRoom", "diag mãos: $diag")
        Choreographer.getInstance().removeFrameCallback(frameCb)
        renderer.stopSensors()
        releaseTouches()
        for (i in 0..1) { drag[i] = D_NONE; prevPressed[i] = false }; fAim.reset()
        state.kbVisible = false
        ui.kbOn = false
        try { vd?.surface = null } catch (_: Exception) {}
        glView.visibility = View.GONE
        startPanel.visibility = View.VISIBLE
        applyImmersive(false)
    }

    // =================================================================== 6DoF (ARCore)
    private fun startAr() {
        if (!state.sixDof && !state.handsOn && !state.passthrough) { state.arState = 0; return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 77)
            return
        }
        try {
            val status = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            var sess = arSession
            if (sess == null) {
                sess = Session(this)
                val cfg = Config(sess)
                // só o necessário para o rastreamento de movimento: menos CPU = menos latência
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
                cfg.lightEstimationMode = Config.LightEstimationMode.DISABLED
                cfg.depthMode = Config.DepthMode.DISABLED
                cfg.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                cfg.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                sess.configure(cfg)
                pickCameraConfig(sess)
                try {
                    val camId = sess.cameraConfig.cameraId
                    val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    renderer.sensorOrientation =
                        cm.getCameraCharacteristics(camId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                } catch (_: Exception) {
                    renderer.sensorOrientation = 90
                }
                arSession = sess
                renderer.arSession = sess
            }
            applyFocus(sess)
            sess.resume()
            renderer.arRunning = true
            state.arState = 1
            startHands()
        } catch (e: Exception) {
            state.sixDof = false
            state.handsOn = false
            state.passthrough = false
            state.arState = 0
            toast("6DoF/mãos/câmera indisponíveis (${e.javaClass.simpleName}). Usando só rotação.")
        }
    }

    /** Mãos ficam a ~20-60 cm da câmera: precisam de foco automático. Só 6DoF: foco fixo rastreia melhor. */
    private fun applyFocus(sess: Session) {
        try {
            val cfg = Config(sess)
            cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
            cfg.lightEstimationMode = Config.LightEstimationMode.DISABLED
            cfg.depthMode = Config.DepthMode.DISABLED
            cfg.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            cfg.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            cfg.focusMode = if (state.handsOn || state.passthrough) Config.FocusMode.AUTO else Config.FocusMode.FIXED
            sess.configure(cfg)
        } catch (_: Exception) {
        }
    }

    /** Escolhe a configuração de câmera: 60 fps, sem sensores extras, imagem de CPU perto de 640x480. */
    private fun pickCameraConfig(sess: Session) {
        // 60 fps: a câmera entrega o dobro de quadros, então as mãos podem passar de 30 fps (o limite era a câmera)
        val order = arrayOf(CameraConfig.TargetFps.TARGET_FPS_60, CameraConfig.TargetFps.TARGET_FPS_30)
        for (fps in order) {
            try {
                val filter = CameraConfigFilter(sess)
                    .setTargetFps(EnumSet.of(fps))
                    .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
                val list = sess.getSupportedCameraConfigs(filter)
                if (list.isEmpty()) continue
                var best = list[0]
                var bestScore = Long.MAX_VALUE
                for (c in list) {
                    val a = c.imageSize.width.toLong() * c.imageSize.height
                    val score = Math.abs(a - 640L * 480L)
                    if (score < bestScore) { bestScore = score; best = c }
                }
                sess.cameraConfig = best
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun startHands() {
        if (!state.handsOn) return
        var t = tracker
        if (t == null) {
            t = HandTracker(this, state) { msg -> runOnUiThread { toast(msg) } }
            tracker = t
            renderer.tracker = t
        }
        t.wantHands = if (state.twoHands) 2 else 1
        t.start()
    }

    private fun stopHands() {
        tracker?.stop()
        state.handState = 0
    }

    private fun stopAr() {
        stopHands()
        renderer.arRunning = false
        state.arState = 0
        try { arSession?.pause() } catch (_: Exception) {}
    }

    // =================================================================== controles ESP32 / celular (ControllerHub)
    private var hubInvYaw = false
    private var hubInvPitch = false
    private val hubYaw0 = FloatArray(2)
    private val hubHave = BooleanArray(2)
    private val hubPrevBtn = IntArray(2)
    private var hubScrollNext = 0L
    private var hubRecenterSeen = false
    private var mcastLock: WifiManager.MulticastLock? = null
    private val hubHit = FloatArray(2)
    private var gazeOn = false
    private var dwellKey = -1
    private var dwellStart = 0L
    private var dwellFired = false
    private var dwellHoldUntil = 0L

    private var pcLink: PcLink? = null
    private var trackLabel: TextView? = null
    // Rotação REAL da tela. Antes assumia-se ROTATION_90/270 pela opção "posição do celular", mas "paisagem" pode virar ROTATION_270 em alguns
    // celulares: a pose da cabeça ficava girada 180° em relação ao celular e o PC (Beat Saber etc.) via o mundo de cabeça para baixo.
    @Suppress("DEPRECATION")
    private fun fixedRotation(): Int {
        val r = windowManager.defaultDisplay.rotation
        if (r == android.view.Surface.ROTATION_90 || r == android.view.Surface.ROTATION_270) return r
        return if (com.stormvr.app.Prefs(this).orientation == 1) android.view.Surface.ROTATION_90 else android.view.Surface.ROTATION_270
    }

    // ---- "TV" da Sala 3D: Home com apps (Navegador, Moonlight). Novos apps entram em TvHome.tiles + applyTvVisibility().
    private var tvBox: FrameLayout? = null
    private var tvHome: View? = null
    private var tvMoon: MoonlightPane? = null
    private var tvSaber: StormSaberPane? = null
    private var tvHomeBtn: View? = null
    private var tvApps: com.stormvr.app.applauncher.AppLauncherPane? = null          // biblioteca de apps (módulo applauncher)
    private var appLauncher: com.stormvr.app.applauncher.AppLauncher? = null         // dono das janelas/sessão: sobrevive à reconstrução da TV
    private var tvApp = TvApp.HOME
    private var trackSession = false

    private fun showTv(app: Int) {
        val leaving = tvApp
        tvApp = app
        if (leaving == TvApp.MOONLIGHT && app != TvApp.MOONLIGHT) tvMoon?.close()
        applyTvVisibility()
    }

    private fun applyTvVisibility() {
        web?.visibility = if (tvApp == TvApp.BROWSER) View.VISIBLE else View.GONE
        tvHome?.visibility = if (tvApp == TvApp.HOME) View.VISIBLE else View.GONE
        tvMoon?.visibility = if (tvApp == TvApp.MOONLIGHT) View.VISIBLE else View.GONE
        tvSaber?.visibility = if (tvApp == TvApp.SABER) View.VISIBLE else View.GONE
        tvApps?.visibility = if (tvApp == TvApp.APPS) View.VISIBLE else View.GONE
        tvHomeBtn?.visibility = if (tvApp == TvApp.HOME) View.GONE else View.VISIBLE
        if (tvApp == TvApp.MOONLIGHT) tvMoon?.open()
        if (tvApp == TvApp.BROWSER) web?.requestFocus()
    }

    private fun trackUiTick() {
        val l = trackLabel ?: return
        if (isFinishing || isDestroyed) return
        val pl = pcLink
        l.text = "StormVR — rastreamento ativo (sem Sala 3D)\n" +
            "Cabeça → SteamVR: %.0f pacotes/s\n".format(pl?.headRate() ?: 0f) +
            "6DoF (ARCore): " + (if (!state.sixDof) "desligado" else if (state.arState == 2) "rastreando" else "procurando/perdido") + "\n" +
            "Mãos: " + (if (state.handsOn) "ligadas" else "desligadas") + "\n\nVoltar = parar"
        l.postDelayed({ trackUiTick() }, 500)
    }

    private fun startPcLink() {
        if (!state.pcMode) return
        val prefs = com.stormvr.app.Prefs(this)
        com.stormvr.app.BtForward.host = prefs.host; com.stormvr.app.BtForward.enabled = prefs.btForward
        if (prefs.host.isBlank()) { toast("Modo PC: defina o IP do PC na tela inicial do StormVR."); return }
        val headFromService = false     // a cabeça sempre vem da pose da sala (mesmo cálculo): o serviço 3DoF antigo não é mais usado
        val l = PcLink(applicationContext, state, prefs.host, prefs.port, com.stormvr.app.ControllerUdp.PORT_HAND, prefs.deviceId, prefs.rateHz, !headFromService, prefs.orientation)
        l.sendPos = state.pcSendPos
        l.sendPinch = state.pcSendPinch
        pcLink = l
        l.start()
        toast("Modo PC: enviando " + (if (headFromService) "mãos" else "cabeça e mãos") + " para " + prefs.host)
    }

    private fun stopPcLink() { pcLink?.stop(); pcLink = null }

    private fun startHub() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (mcastLock == null) mcastLock = wm.createMulticastLock("stormvr-sala").apply { setReferenceCounted(false) }
            mcastLock?.acquire()
        } catch (_: Exception) {}
        if (!BleController.hasPermission(this)) requestPermissions(BleController.permissionsNeeded(), 78)
        ControllerHub.start(this)
        for (i in 0..1) { hubHave[i] = false; hubPrevBtn[i] = 0 }
    }

    private fun stopHub() {
        ControllerHub.stop()
        try { mcastLock?.release() } catch (_: Exception) {}
    }

    /**
     * Controles 3DoF (ESP32 por Bluetooth/Wi-Fi ou outro celular) viram lasers: a orientação do controle aponta o raio
     * a partir de uma posição fixa da mão (sem rastreio de posição), igual ao laser das mãos rastreadas. Roda DEPOIS de
     * updateHandsAndTriggers e substitui o laser daquela mão enquanto o controle estiver ativo. i: 0 = esquerda, 1 = direita.
     */
    // ---- extras do 3DoF (inclinar a cabeça / barômetro)
    private var extras: HeadExtras? = null
    private val exQ = FloatArray(4)
    private fun applyExtras() {
        val p = com.stormvr.app.Prefs(this)
        val ex = extras ?: HeadExtras(this).also { extras = it }
        ex.apply(p)
        saberFixedPref = p.saberLookFixed
        state.extrasSend = p.extrasToSteam
        state.extrasActive = ex.active
        if (!ex.active) { state.extraLean = 0f; state.extraDrop = 0f }
    }
    @Volatile private var saberFixedPref = false
    private fun tickExtras() {
        state.freezeHead = saberFixedPref && tvApp == TvApp.SABER && !state.fullscreen      // StormSaber de cabeça fixa (opção; padrão desligada)
        if (tvApp == TvApp.SABER) tvSaber?.setBody(state.headX, state.headZ)               // lado/altura da cabeça: desviar das paredes
        val ex = extras ?: return
        if (!ex.active || state.sixDof) { if (state.extraLean != 0f || state.extraDrop != 0f) { state.extraLean = 0f; state.extraDrop = 0f }; return }
        synchronized(state.poseLock) { for (k in 0..3) exQ[k] = state.headQ[k] }
        ex.updateLean(exQ)
        state.extraLean = if (ex.leanOn) ex.lean else 0f
        state.extraDrop = if (ex.baroOn) ex.drop else 0f
    }

    // ---- DEDOS COMO CONTROLES (todos os modos da Sala): cada metade da tela é um controle; arrastar aponta, tocar = gatilho
    @Volatile private var fingerCtl = true
    private val fAim = FingerAim(40f, 28f)

    /** Tocar = clique; segurar um instante e arrastar = mover a mira (relativo, não volta ao centro ao tirar o dedo). */
    private fun fingerTouch(ev: MotionEvent) {
        fAim.onTouch(ev, glView.width.toFloat().coerceAtLeast(1f), glView.height.toFloat().coerceAtLeast(1f), android.os.SystemClock.uptimeMillis())
    }

    /** Aplica os dedos como se fossem os controles que não estão conectados (controle real e mãos rastreadas têm prioridade). */
    private fun updateFingerControllers() {
        if (!fingerCtl) return
        val s = state
        val now = android.os.SystemClock.uptimeMillis()
        for (i in 0..1) {
            if (!fAim.used[i] || ControllerHub.hand(i == 0).active() || s.laserHand[i]) continue
            val yr = Math.toRadians(fAim.yaw[i].toDouble()).toFloat()
            val pr = Math.toRadians(fAim.pitch[i].toDouble()).toFloat()
            val dx = kotlin.math.sin(yr) * kotlin.math.cos(pr)
            val dy = kotlin.math.cos(yr) * kotlin.math.cos(pr)
            val dz = kotlin.math.sin(pr)
            val ox = s.headX + (if (i == 0) -0.22f else 0.22f)
            val oy = s.headY + 0.40f
            val oz = s.headZ - 0.35f
            s.laserHand[i] = false
            s.lox[i] = ox; s.loy[i] = oy; s.loz[i] = oz
            s.rox[i] = ox; s.roy[i] = oy; s.roz[i] = oz
            if (s.intersectSurface(ox, oy, oz, dx, dy, dz, hubHit)) {
                s.ax[i] = hubHit[0].coerceIn(-7f, 7f)
                s.az[i] = hubHit[1].coerceIn(-3f, 3.6f)
            }
            if (tvApp == TvApp.SABER) tvSaber?.setSaber(i, dx, dy, dz, true)
            s.pressed[i] = s.pressed[i] || fAim.click(i, now)                  // toque curto = gatilho
        }
    }

    private fun updateHubControllers(now: Long) {
        val s = state
        if (s.recenter) hubRecenterSeen = true
        else if (hubRecenterSeen) { hubRecenterSeen = false; for (i in 0..1) hubHave[i] = false; pcLink?.recenter() }   // re-referencia o apontar e o yaw no PC
        for (i in 0..1) {
            val c = ControllerHub.hand(i == 0)
            if (!c.active()) {
                if (hubHave[i]) toast("Controle " + (if (i == 0) "esquerdo" else "direito") + " desconectado")
                hubHave[i] = false; hubPrevBtn[i] = 0; hubSInit[i] = false
                if (!(fingerCtl && fAim.used[i])) tvSaber?.setSaber(i, 0f, 1f, 0f, false)       // dedo segurando o sabre: não desativa
                continue
            }
            if (!hubHave[i]) {
                hubHave[i] = true
                hubYaw0[i] = c.yaw
                toast("Controle " + (if (i == 0) "esquerdo" else "direito") + " conectado (" + c.source + ")")
            }
            var yaw = c.yaw - hubYaw0[i]
            while (yaw > 180f) yaw -= 360f
            while (yaw < -180f) yaw += 360f
            var pitch = c.pitch
            if (hubInvYaw) yaw = -yaw
            if (hubInvPitch) pitch = -pitch
            // suavização adaptativa a cada quadro: o controle chega a 50 pacotes/s e o laser dava "pequenos teleportes"; agora desliza até o alvo
            // (parado = firme, movendo rápido = quase sem atraso)
            run {
                val dtS = ((now - hubLastMs).coerceIn(1L, 50L)) / 1000f
                if (!hubSInit[i]) { hubSY[i] = yaw; hubSP[i] = pitch; hubSInit[i] = true }
                else {
                    var ey = yaw - hubSY[i]
                    while (ey > 180f) ey -= 360f
                    while (ey < -180f) ey += 360f
                    val ep = pitch - hubSP[i]
                    val err = kotlin.math.sqrt(ey * ey + ep * ep)
                    val tau = 0.05f / (1f + err / 6f)
                    val al = 1f - kotlin.math.exp(-dtS / tau)
                    hubSY[i] += ey * al
                    hubSP[i] += ep * al
                }
                yaw = hubSY[i]; pitch = hubSP[i]
            }
            val yr = Math.toRadians(yaw.toDouble()).toFloat()
            val pr = Math.toRadians(pitch.toDouble()).toFloat()
            val dx = -kotlin.math.sin(yr) * kotlin.math.cos(pr)          // yaw + = para a esquerda
            val dy = kotlin.math.cos(yr) * kotlin.math.cos(pr)
            val dz = kotlin.math.sin(pr)
            if (tvApp == TvApp.SABER) tvSaber?.setSaber(i, dx, dy, dz, true)
            val ox = s.headX + (if (i == 0) -0.22f else 0.22f)
            val oy = s.headY + 0.40f
            val oz = s.headZ - 0.35f
            s.laserHand[i] = false
            s.lox[i] = ox; s.loy[i] = oy; s.loz[i] = oz
            s.rox[i] = ox; s.roy[i] = oy; s.roz[i] = oz
            if (s.intersectSurface(ox, oy, oz, dx, dy, dz, hubHit)) {
                s.ax[i] = hubHit[0].coerceIn(-7f, 7f)
                s.az[i] = hubHit[1].coerceIn(-3f, 3.6f)
            }
            val b = c.buttons
            val trig = (b and (ControllerUdp.BTN_TRIGGER or ControllerUdp.BTN_R2)) != 0
            s.pressed[i] = s.pressed[i] || trig
            // GRIP = botao DIREITO do mouse na telona (controle em modo MOUSE); so conta se o laser esta na janela
            val gripNow = (b and ControllerUdp.BTN_GRIP) != 0 && s.hoverKind[i] == Hit.WIN
            if (gripNow != gripHeld[i]) {
                gripHeld[i] = gripNow
                mousePos(i, tmp)
                tvMoon?.gripRight(i, gripNow, tmp[0], tmp[1])
            }
            val down = b and hubPrevBtn[i].inv()
            hubPrevBtn[i] = b
            if (down != 0) {                                                  // mesma tabela do gamepad (padButton)
                if ((down and ControllerUdp.BTN_A) != 0) padButton(PadBtn.A, true, true)
                if ((down and ControllerUdp.BTN_B) != 0) padButton(PadBtn.B, true, true)
                if ((down and ControllerUdp.BTN_X) != 0) padButton(PadBtn.X, true, true)
                if ((down and ControllerUdp.BTN_Y) != 0) padButton(PadBtn.Y, true, true)
                if ((down and ControllerUdp.BTN_START) != 0) padButton(PadBtn.START, true, true)
                if ((down and ControllerUdp.BTN_L3) != 0) padButton(PadBtn.THUMB, true, true)
            }
            if (!s.kbVisible && now >= hubScrollNext && kotlin.math.abs(c.joyY) > 0.6f) {      // analógico = rolar a página
                scrollPage(c.joyY > 0f)
                hubScrollNext = now + 160
            }
        }
        hubLastMs = now
    }
    private val hubSY = FloatArray(2)
    private val hubSP = FloatArray(2)
    private val hubSInit = BooleanArray(2)
    private var hubLastMs = 0L

    /**
     * Clique por olhar (da sala 3D do StormVR): sem controle nem mãos, o laser direito segue a direção da cabeça e,
     * ficando ~1,2 s sobre um botão da barra, do teclado ou dos cantos, o clique é feito sozinho.
     */
    private fun updateGaze() {
        val s = state
        gazeOn = s.dwell && !ControllerHub.hand(false).active() && !(s.handsOn && s.handState >= 3)
        if (!gazeOn) return
        val g = s.gaze
        val ox = s.headX
        val oy = s.headY
        val oz = s.headZ
        s.laserHand[1] = false
        s.lox[1] = ox; s.loy[1] = oy + 0.05f; s.loz[1] = oz - 0.12f
        s.rox[1] = ox; s.roy[1] = oy; s.roz[1] = oz
        if (s.intersectSurface(ox, oy, oz, g[0], g[1], g[2], hubHit)) {
            s.ax[1] = hubHit[0].coerceIn(-7f, 7f)
            s.az[1] = hubHit[1].coerceIn(-3f, 3.6f)
        }
    }

    private fun updateDwell(now: Long) {
        val s = state
        if (!gazeOn) { dwellKey = -1; return }
        val kind = s.hoverKind[1]
        val ui = kind == Hit.TB || kind == Hit.KB || kind == Hit.CORNER || kind == Hit.BAR || kind == Hit.KBBAR
        val key = if (ui) kind * 1000 + s.hoverIdx[1] else -1
        if (key != dwellKey) { dwellKey = key; dwellStart = now; dwellFired = false }
        else if (key >= 0 && !dwellFired && now - dwellStart >= 1200L) { dwellFired = true; dwellHoldUntil = now + 180L }
        if (now < dwellHoldUntil) s.pressed[1] = true
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vrMode) return
            val dt = if (lastFrameNs == 0L) 0.016f else (frameTimeNanos - lastFrameNs) / 1e9f
            lastFrameNs = frameTimeNanos
            val now = SystemClock.uptimeMillis()

            @Suppress("DEPRECATION")
            state.rotation = fixedRotation()

            // D-pad contínuo (janela) só quando o teclado está fechado
            if (padToMoon) { /* MODO GAMEPAD: o d-pad é do jogo, não redimensiona/afasta a tela */ }
            else if (!state.kbVisible) {
                val dx = if (hatX != 0f) hatX else keyDx
                val dy = if (hatY != 0f) hatY else keyDy
                if (dy != 0f) state.width = (state.width - dy * 1.5f * dt).coerceIn(1.2f, 7.5f)
                if (dx != 0f) state.distance = (state.distance + dx * 1.5f * dt).coerceIn(1.5f, 4.5f)
            } else if ((hatDx != 0 || hatDy != 0) && now >= hatNext) {
                onDpad(hatDx, hatDy)
                hatNext = now + 110
            }

            state.moveAim(dt)
            updateHandsAndTriggers()
            updateHubControllers(now)
            updateFingerControllers()
            tickExtras()
            updateGaze()
            state.updateGeom()
            for (i in 0..1) pick(i)
            updateDwell(now)
            updatePoke()
            for (i in 0..1) interact(i)
            if (!vrMode) return          // "Sair" foi tocado
            state.updateGeom()
            hoverMouse()

            if (ui.kbFlash != -1 && now > flashUntil) ui.kbFlash = -1
            if (ui.tbFlash != -1 && now > tbFlashUntil) ui.tbFlash = -1
            refreshUi()
            refreshStatus(now)

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var lastHud = 0L
    private var hudSig = ""

    /** Painel de diagnóstico: mostra em que etapa o ARCore e o rastreador de mãos estão. */
    private fun refreshStatus(now: Long) {
        if (!state.handsOn || now - lastHud < 250) return
        lastHud = now
        val ar = when (state.arState) {
            0 -> "desligado"
            1 -> "sem rastreio de posição (mãos funcionam)"
            else -> "rastreando"
        }
        val hs = when (state.handState) {
            0 -> "desligado"
            1 -> "carregando modelo"
            2 -> "procurando mãos"
            else -> "rastreando"
        }
        val l1 = "GL ${state.glFps} fps  |  ARCore: $ar  |  Mãos: $hs"
        var l2 = tracker?.info() ?: "tracker não iniciado"
        if (renderer.camErr.isNotEmpty()) l2 += " | ${renderer.camErr}"
        if (renderer.arErr.isNotEmpty()) l2 += " | erro AR: ${renderer.arErr}"
        val sig = l1 + l2
        if (sig != hudSig) {
            hudSig = sig
            ui.drawStatus(l1, l2)
        }
    }

    private fun refreshUi() {
        ui.editing = kbTargetUrl && state.kbVisible
        ui.url = if (ui.editing) urlText.toString() else currentUrl
        ui.kbOn = state.kbVisible
        ui.curvedOn = state.curved
        ui.dofOn = state.sixDof
        ui.dofState = state.arState
        ui.handsOn = state.handsOn
        ui.handsState = state.handState
        ui.hlaserOn = state.handLasers
        ui.aimEye = true
        ui.liteOn = state.lite
        ui.twoOn = state.twoHands
        ui.camOn = state.passthrough

        var s = ui.tbHov[0]
        s = s * 31 + ui.tbHov[1]
        s = s * 31 + ui.tbFlash
        s = s * 31 + ui.url.hashCode()
        s = s * 31 + (if (ui.kbOn) 1 else 0) + (if (ui.editing) 2 else 0)
        s = s * 31 + (if (ui.curvedOn) 1 else 0) + (if (ui.dofOn) 2 else 0) + ui.dofState * 4
        s = s * 31 + (if (ui.handsOn) 1 else 0) + (if (ui.hlaserOn) 2 else 0) + ui.handsState * 4
        s = s * 31 + (if (ui.aimEye) 1 else 0) + (if (ui.camOn) 2 else 0) + (if (ui.liteOn) 4 else 0) + (if (ui.twoOn) 8 else 0)
        if (s != sigTb) { sigTb = s; ui.drawToolbar() }

        if (state.kbVisible) {
            var k = ui.kbHov[0]
            k = k * 31 + ui.kbHov[1]
            k = k * 31 + ui.kbFocus
            k = k * 31 + ui.kbFlash
            k = k * 31 + (if (ui.shift) 1 else 0)
            ui.kbBarHot = state.hoverKind[0] == Hit.KBBAR || state.hoverKind[1] == Hit.KBBAR || drag[0] == D_KBMOVE || drag[1] == D_KBMOVE
            k = k * 31 + (if (ui.kbBarHot) 1 else 0)
            if (k != sigKb) { sigKb = k; ui.drawKeyboard() }
        }
    }

    // =================================================================== mãos: lasers e pinça
    private val hitTmp = FloatArray(2)
    private val bp = FloatArray(3)
    private val tmp2 = FloatArray(2)
    private val t0Ns = System.nanoTime()
    // estabilização do cursor (tira o tremor da mão) e zona morta do arrasto (tolerância de clique)
    private val aimFilter = Array(2) { OneEuro(2, 1.2f, 3f, 1f) }
    private val dragOn = BooleanArray(2)

    // máquina de estados da pinça por mão: 0 = aberta, 1 = fechando (cursor congelado), 2 = pinçada
    private val hState = IntArray(2)
    private val curInit = BooleanArray(2)
    private val curS = FloatArray(2)
    private val curZ = FloatArray(2)
    private val frzS = FloatArray(2)
    private val frzZ = FloatArray(2)
    private val refS = FloatArray(2)
    private val refZ = FloatArray(2)
    private val offS = FloatArray(2)
    private val offZ = FloatArray(2)

    /**
     * Ponto da superfície que a mão está "apontando".
     *  - Olho: raio do olho passando pelo meio da pinça (o que está entre os dedos é o alvo).
     *  - Braço: direção ombro -> mão, amplificada (movimentos pequenos do braço varrem a tela toda).
     */
    private fun handAimPoint(i: Int, px: Float, py: Float, pz: Float, out: FloatArray): Boolean {
        val s = state
        val ex = s.headX
        val ey = s.headY
        val ez = s.headZ
        var dx: Float
        var dy: Float
        var dz: Float
        if (s.handAim == 0) {
            dx = px - ex; dy = py - ey; dz = pz - ez
        } else {
            val side = if (i == 0) -1f else 1f
            val shx = ex + side * 0.17f
            val shy = ey - 0.05f
            val shz = ez - 0.27f
            val vx = px - shx; val vy = py - shy; val vz = pz - shz
            val yaw = atan2(vx, vy)
            val pitch = atan2(vz, sqrt(vx * vx + vy * vy))
            // posição "neutra" da mão: à frente do rosto, um pouco abaixo
            val nvx = ex - shx; val nvy = (ey + 0.45f) - shy; val nvz = (ez - 0.10f) - shz
            val yawN = atan2(nvx, nvy)
            val pitchN = atan2(nvz, sqrt(nvx * nvx + nvy * nvy))
            val sc3 = FloatArray(3); s.screenCenter(sc3)                       // centro da tela JÁ com órbita/giro
            val scx = sc3[0] - ex; val scy = sc3[1] - ey; val scz = sc3[2] - ez
            val yawS = atan2(scx, scy)
            val pitchS = atan2(scz, sqrt(scx * scx + scy * scy))
            val g = 2.2f
            val yw = yawS + g * (yaw - yawN)
            val pt = pitchS + g * (pitch - pitchN)
            dx = sin(yw) * cos(pt); dy = cos(yw) * cos(pt); dz = sin(pt)
        }
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 0.05f) return false
        dx /= l; dy /= l; dz /= l
        return s.intersectSurface(ex, ey, ez, dx, dy, dz, out)
    }

    /**
     * Define origem/estado de cada laser:
     *  - gatilho = L2/R2 do controle OU pinça da mão;
     *  - com "laser das mãos": o cursor vem do olho/braço, e ao fechar a pinça ele CONGELA no alvo
     *    (a convergência dos dedos não "puxa" o cursor); com a pinça fechada, mover a mão arrasta.
     */
    private fun updateHandsAndTriggers() {
        val s = state
        for (i in 0..1) {
            s.laserHand[i] = false
            s.ballOn[i] = false
            val ox = s.headX + (if (i == 0) -0.28f else 0.28f)
            val oy = s.headY + 0.45f
            val oz = s.headZ - 0.40f
            s.lox[i] = ox; s.loy[i] = oy; s.loz[i] = oz
            s.rox[i] = ox; s.roy[i] = oy; s.roz[i] = oz
            val trig = !padToMoon && (if (i == 0) s.l2 else s.r2) > 0.5f            // no modo gamepad os gatilhos são do jogo (não clicam no laser)

            var has = false
            var pinch = false
            var closing = false
            var ratio = 1f
            var px = 0f
            var py = 0f
            var pz = 0f
            if (s.handsOn) {
                synchronized(s.handLock) {
                    if (s.handValid[i]) {
                        has = true
                        pinch = s.handPinch[i]
                        closing = s.handClosing[i]
                        ratio = s.handRatio[i]
                        val p = s.handPts[i]
                        px = (p[12] + p[24]) * 0.5f      // meio da pinça (pontas do polegar e do indicador)
                        py = (p[13] + p[25]) * 0.5f
                        pz = (p[14] + p[26]) * 0.5f
                    }
                }
            }

            var handPressed = false
            if (has && s.handLasers && handAimPoint(i, px, py, pz, hitTmp)) {
                // estabiliza o cursor: parado = bem suave (tira o tremor); movendo rápido = quase sem atraso
                tmp2[0] = hitTmp[0]
                tmp2[1] = hitTmp[1]
                aimFilter[i].minCutoff = 2.2f - 1.4f * s.tolPct / 100f
                aimFilter[i].apply(tmp2, (System.nanoTime() - t0Ns) / 1e9f)
                val aimS = tmp2[0]
                val aimZ = tmp2[1]
                val tolM = (8f + s.tolPct * 0.7f) / VrState.TEX_W * s.width      // tolerância em metros na tela
                if (!curInit[i]) {
                    curInit[i] = true
                    curS[i] = aimS; curZ[i] = aimZ
                    offS[i] = 0f; offZ[i] = 0f
                    hState[i] = 0
                }
                when (hState[i]) {
                    0 -> {
                        if (pinch) {
                            hState[i] = 2
                            dragOn[i] = false
                            frzS[i] = curS[i]; frzZ[i] = curZ[i]
                            refS[i] = aimS; refZ[i] = aimZ
                        } else if (closing) {
                            hState[i] = 1                                 // pinça a caminho: congela o alvo
                            frzS[i] = curS[i]; frzZ[i] = curZ[i]
                        } else {
                            offS[i] *= 0.8f; offZ[i] *= 0.8f              // volta suavemente ao apontar absoluto
                            curS[i] = aimS + offS[i]; curZ[i] = aimZ + offZ[i]
                        }
                    }
                    1 -> {
                        if (pinch) {
                            hState[i] = 2
                            dragOn[i] = false
                            refS[i] = aimS; refZ[i] = aimZ
                        } else if (!closing) {
                            hState[i] = 0                                 // desistiu de pinçar
                            offS[i] = frzS[i] - aimS; offZ[i] = frzZ[i] - aimZ
                        }
                        curS[i] = frzS[i]; curZ[i] = frzZ[i]
                    }
                    else -> {
                        if (!pinch) {
                            if (closing) {
                                hState[i] = 1
                                frzS[i] = curS[i]; frzZ[i] = curZ[i]
                            } else {
                                hState[i] = 0
                                offS[i] = curS[i] - aimS; offZ[i] = curZ[i] - aimZ
                            }
                        } else if (!dragOn[i]) {
                            // zona morta: o tremor da mão não vira arrasto; só arrasta depois de passar da tolerância
                            val dS = aimS - refS[i]
                            val dZ = aimZ - refZ[i]
                            if (sqrt(dS * dS + dZ * dZ) > tolM) {
                                dragOn[i] = true
                                refS[i] = aimS; refZ[i] = aimZ        // começa a arrastar daqui, sem salto
                            }
                            curS[i] = frzS[i]; curZ[i] = frzZ[i]
                        } else {
                            curS[i] = frzS[i] + (aimS - refS[i])          // arrastar: movimento relativo
                            curZ[i] = frzZ[i] + (aimZ - refZ[i])
                        }
                    }
                }
                val ns = curS[i].coerceIn(-7f, 7f)
                val nz = curZ[i].coerceIn(-3f, 3.6f)
                if (abs(ns - s.ax[i]) + abs(nz - s.az[i]) > 0.002f) s.lastMove[i] = SystemClock.uptimeMillis()
                s.ax[i] = ns
                s.az[i] = nz
                s.lox[i] = px; s.loy[i] = py; s.loz[i] = pz          // o laser nasce no meio da pinça
                s.rox[i] = s.headX; s.roy[i] = s.headY; s.roz[i] = s.headZ   // e a seleção parte do olho
                s.laserHand[i] = true
                // bolinha = cursor: na linha olho -> alvo, na distância da mão
                s.surfPoint(ns, nz, 0f, bp)
                val bdx = bp[0] - s.headX
                val bdy = bp[1] - s.headY
                val bdz = bp[2] - s.headZ
                val bl = sqrt(bdx * bdx + bdy * bdy + bdz * bdz).coerceAtLeast(1e-4f)
                val hdx = px - s.headX
                val hdy = py - s.headY
                val hdz = pz - s.headZ
                val hd = sqrt(hdx * hdx + hdy * hdy + hdz * hdz)
                s.ball[i][0] = s.headX + bdx / bl * hd
                s.ball[i][1] = s.headY + bdy / bl * hd
                s.ball[i][2] = s.headZ + bdz / bl * hd
                s.ballOn[i] = true
                handPressed = hState[i] == 2
            } else {
                hState[i] = 0
                curInit[i] = false
                aimFilter[i].reset()
                dragOn[i] = false
                offS[i] = 0f; offZ[i] = 0f
                handPressed = has && pinch
            }
            s.pressed[i] = trig || handPressed
        }
    }

    // =================================================================== digitar tocando as teclas
    private val pokeDown = BooleanArray(2)
    private val pokeFront = floatArrayOf(1f, 1f)

    /**
     * Com o teclado perto das mãos: a ponta do indicador que "afunda" no plano do teclado digita a tecla.
     * (Também dá para pinçar a tecla: a bolinha aponta pelo olhar.)
     */
    private fun updatePoke() {
        val s = state
        val g = s.kb
        if (!s.kbVisible || !s.handsOn || s.handState < 2 || g.cy - s.headY > 1.2f) {
            pokeDown[0] = false; pokeDown[1] = false
            pokeFront[0] = 1f; pokeFront[1] = 1f
            return
        }
        for (i in 0..1) {
            var tx = 0f
            var ty = 0f
            var tz = 0f
            var ok = false
            synchronized(s.handLock) {
                if (s.handValid[i]) {
                    val p = s.handPts[i]
                    tx = p[24]; ty = p[25]; tz = p[26]          // ponta do indicador (ponto 8)
                    ok = true
                }
            }
            if (!ok) {
                pokeDown[i] = false
                pokeFront[i] = 1f
                continue
            }
            val front = g.cy - ty                               // > 0: na frente do teclado (lado do usuário)
            val u = (tx - (g.cx - g.w / 2f)) / g.w
            val v = ((g.cz + g.h / 2f) - tz) / g.h
            val inside = u in 0f..1f && v in 0f..1f
            val idx = if (inside) ui.keyAt(u, v) else -1
            if (inside && idx >= 0 && front > -0.05f && front < 0.12f) ui.kbHov[i] = idx   // realça a tecla sob o dedo
            if (!pokeDown[i]) {
                if (idx >= 0 && front < 0.015f && front > -0.06f && pokeFront[i] > 0.03f) {
                    pokeDown[i] = true
                    pressKey(idx)
                }
            } else if (front > 0.045f || !inside) {
                pokeDown[i] = false
            }
            pokeFront[i] = front
        }
    }

    // =================================================================== raycast dos lasers
    private fun inRect(g: Geom, x: Float, z: Float) =
        abs(x - g.cx) <= g.w / 2f && abs(z - g.cz) <= g.h / 2f

    private val pt = FloatArray(3)

    private fun pick(i: Int) {
        val s = state
        // origem do laser = mão (acompanha a cabeça no 6DoF)
        val ox = s.rox[i]
        val oy = s.roy[i]
        val oz = s.roz[i]
        val sA = s.ax[i]
        val zA = s.az[i]
        s.surfPoint(sA, zA, 0f, pt)          // ponto de mira na superfície da janela
        var kind = Hit.NONE
        var idx = -1
        var t = 1f

        // 1) teclado (plano flutuante; acompanha a órbita escolhida com a barra dele)
        if (s.kbVisible) {
            val g = s.kb; val kr = s.kbRot
            val nx = -kr[1]; val ny = -kr[4]; val nz = -kr[7]                       // normal do teclado (aponta para o usuário)
            val dx = pt[0] - ox; val dy = pt[1] - oy; val dz = pt[2] - oz
            val den = dx * nx + dy * ny + dz * nz
            if (abs(den) > 1e-4f) {
                val tt = ((g.cx - ox) * nx + (g.cy - oy) * ny + (g.cz - oz) * nz) / den
                if (tt > 0f && tt < 1f) {
                    val rx = ox + tt * dx - g.cx; val ry = oy + tt * dy - g.cy; val rz = oz + tt * dz - g.cz
                    val u = (rx * kr[0] + ry * kr[3] + rz * kr[6]) / g.w + 0.5f
                    val v = 0.5f - (rx * kr[2] + ry * kr[5] + rz * kr[8]) / g.h
                    if (u in 0f..1f && v in 0f..1f) {
                        if (ui.inKbBar(u, v)) kind = Hit.KBBAR else { kind = Hit.KB; idx = ui.keyAt(u, v) }
                        t = tt
                    }
                }
            }
        }

        // 2) itens sobre a superfície da janela (coordenadas s/z)
        if (kind == Hit.NONE) {
            val hs = s.handle() * 0.9f
            if (!s.screenHidden) for (c in 0..3) {                // cantos de redimensionar (aparecem quando você aponta para a tela)
                if (abs(sA - s.cornerS(c)) < hs && abs(zA - s.cornerZ(c)) < hs) {
                    kind = Hit.CORNER; idx = c; break
                }
            }
            if (kind == Hit.NONE && inRect(s.tb, sA, zA)) {
                val g = s.tb
                kind = Hit.TB
                idx = ui.toolbarAt((sA - (g.cx - g.w / 2f)) / g.w, ((g.cz + g.h / 2f) - zA) / g.h)
            }
            if (kind == Hit.NONE && inRect(s.bar, sA, zA)) kind = Hit.BAR
            if (kind == Hit.NONE && !s.screenHidden && inRect(s.win, sA, zA)) kind = Hit.WIN
        }

        if (kind == Hit.KB || kind == Hit.KBBAR) {
            s.hitX[i] = ox + t * (pt[0] - ox)
            s.hitY[i] = oy + t * (pt[1] - oy)
            s.hitZ[i] = oz + t * (pt[2] - oz)
        } else {
            s.surfPoint(sA, zA, 0.012f, pt)
            s.hitX[i] = pt[0]
            s.hitY[i] = pt[1]
            s.hitZ[i] = pt[2]
        }
        s.hoverKind[i] = kind
        s.hoverIdx[i] = idx

        ui.tbHov[i] = if (kind == Hit.TB) idx else -1
        ui.kbHov[i] = if (kind == Hit.KB) idx else -1
    }

    private fun mousePos(i: Int, out: FloatArray) {
        val g = state.win
        val u = ((state.ax[i] - (g.cx - g.w / 2f)) / g.w).coerceIn(0f, 1f)
        val v = (((g.cz + g.h / 2f) - state.az[i]) / g.h).coerceIn(0f, 1f)
        out[0] = u * (VrState.TEX_W - 1)
        out[1] = v * (VrState.TEX_H - 1)
    }

    private fun interact(i: Int) {
        val pressed = state.pressed[i]
        val rising = pressed && !prevPressed[i]
        val falling = !pressed && prevPressed[i]
        val kind = state.hoverKind[i]
        val idx = state.hoverIdx[i]

        if (rising) {
            when (kind) {
                Hit.KB -> if (idx >= 0) pressKey(idx)
                Hit.TB -> if (idx >= 0) toolbarAction(idx)
                Hit.CORNER -> {
                    drag[i] = D_RESIZE
                    dStartW[i] = state.width
                    dStartAx[i] = state.ax[i]
                    dSide[i] = if (idx % 2 == 0) -1f else 1f
                }
                Hit.BAR -> {                                           // barra da tela: leva a tela em ÓRBITA em volta do usuário
                    drag[i] = D_MOVE
                    rayAngles(i, rayTmp); dRay0[i][0] = rayTmp[0]; dRay0[i][1] = rayTmp[1]
                    dOrb0[i][0] = state.scrAz; dOrb0[i][1] = state.scrEl
                }
                Hit.KBBAR -> {                                         // barra do teclado: leva o teclado em órbita em volta do usuário
                    drag[i] = D_KBMOVE
                    rayAngles(i, rayTmp); dRay0[i][0] = rayTmp[0]; dRay0[i][1] = rayTmp[1]
                    dOrb0[i][0] = state.kbAz; dOrb0[i][1] = state.kbEl
                }
                Hit.WIN -> if (!tDown[i]) {
                    if (mouseLaser == -1) mouseLaser = i
                    drag[i] = D_MOUSE
                    mousePos(i, tmp)
                    lastMx = tmp[0]; lastMy = tmp[1]
                    tX[i] = tmp[0]; tY[i] = tmp[1]
                    tDown[i] = true
                    downAt[i] = SystemClock.uptimeMillis(); downX[i] = tX[i]; downY[i] = tY[i]
                    touchEvent(0, i)
                }
            }
        } else if (pressed) {
            when (drag[i]) {
                D_RESIZE -> state.width =
                    (dStartW[i] + 2f * dSide[i] * (state.ax[i] - dStartAx[i])).coerceIn(1.2f, 7.5f)
                D_MOVE -> {
                    rayAngles(i, rayTmp)
                    state.scrAz = wrap180(dOrb0[i][0] + wrap180(rayTmp[0] - dRay0[i][0]))
                    state.scrEl = (dOrb0[i][1] + (rayTmp[1] - dRay0[i][1])).coerceIn(-60f, 60f)
                }
                D_KBMOVE -> {
                    rayAngles(i, rayTmp)
                    state.kbAz = (dOrb0[i][0] + wrap180(rayTmp[0] - dRay0[i][0])).coerceIn(-80f, 80f)
                    state.kbEl = (dOrb0[i][1] + (rayTmp[1] - dRay0[i][1])).coerceIn(-45f, 45f)
                    state.updateGeom()
                }
                D_MOUSE -> {
                    mousePos(i, tmp)
                    // estabilização do clique: ponteiro parado logo após apertar, MAS se o controle se afastar mais que um limite pequeno
                    // (clickMovePx) o mouse volta a seguir na hora (era isso que o prendia: segurar para arrastar não funcionava)
                    if (SystemClock.uptimeMillis() - downAt[i] < state.clickFreezeMs) {
                        val ddx = tmp[0] - downX[i]; val ddy = tmp[1] - downY[i]
                        if (ddx * ddx + ddy * ddy > CLICK_MOVE_PX * CLICK_MOVE_PX) downAt[i] = 0L
                    }
                    if (SystemClock.uptimeMillis() - downAt[i] >= state.clickFreezeMs && (tmp[0] != tX[i] || tmp[1] != tY[i])) {
                        lastMx = tmp[0]; lastMy = tmp[1]
                        tX[i] = tmp[0]; tY[i] = tmp[1]
                        touchEvent(1, i)
                    }
                }
            }
        }
        if (falling) {
            if (drag[i] == D_MOUSE && tDown[i]) {
                touchEvent(2, i)
                tDown[i] = false
                mouseLaser = if (tDown[1 - i]) 1 - i else -1
            }
            drag[i] = D_NONE
        }
        prevPressed[i] = pressed
    }

    /** Movimento do "mouse" (hover) quando nenhum laser está arrastando. */
    private fun hoverMouse() {
        if (mouseLaser != -1 && !tDown[mouseLaser]) mouseLaser = -1      // trava antiga: o botão já foi solto mas o laser ficou "preso"
        if (mouseLaser != -1) return
        var cl = -1
        for (i in 0..1) {
            if (state.hoverKind[i] == Hit.WIN && (cl == -1 || state.lastMove[i] > state.lastMove[cl])) cl = i
        }
        if (cl == -1) return
        mousePos(cl, tmp)
        if (tmp[0] != lastMx || tmp[1] != lastMy) {
            lastMx = tmp[0]; lastMy = tmp[1]
            sendHover(lastMx, lastMy)
        }
    }

    // =================================================================== eventos para o navegador
    /**
     * Toque com VÁRIOS dedos: cada laser/pinça/controle (índice 0 = esquerdo, 1 = direito) é um dedo de id próprio, então dois
     * controles juntos fazem o gesto de pinça (zoom). Os eventos vão para [touchTarget] (hoje o navegador; um stream do Moonlight
     * poderia receber os mesmos toques). kind: 0 = encostou, 1 = moveu, 2 = soltou. Para "soltou", chamar ANTES de limpar tDown.
     */
    private val tDown = BooleanArray(2)
    private val downAt = LongArray(2)
    private val downX = FloatArray(2)
    private val downY = FloatArray(2)
    private val CLICK_MOVE_PX = 22f
    private val tX = FloatArray(2)
    private val tY = FloatArray(2)

    /**
     * Ponto de ligação para o Moonlight (ou qualquer outro vídeo): quem implementar [ScreenSource] e atribuir a [screenSource] recebe a
     * Surface da tela grande (a sala, o modo estéreo e a tela cheia desenham essa textura) e todos os toques multidedo dos controles/dedos.
     * Sem fonte, a tela é o navegador.
     */
    interface ScreenSource {
        fun attach(surface: Surface, width: Int, height: Int)
        fun detach()
        fun touch(ev: MotionEvent)
    }
    @Volatile var screenSource: ScreenSource? = null

    private fun touchTarget(ev: MotionEvent) {
        val src = screenSource
        if (src != null) src.touch(ev) else (tvBox ?: web)?.dispatchTouchEvent(ev)
    }

    private fun touchEvent(kind: Int, i: Int) {
        val now = SystemClock.uptimeMillis()
        val ids = IntArray(2)
        var n = 0
        var idx = 0
        for (k in 0..1) if (tDown[k] || (kind == 2 && k == i)) { if (k == i) idx = n; ids[n++] = k }
        if (n == 0) return
        val action = when (kind) {
            0 -> if (n == 1) MotionEvent.ACTION_DOWN else (MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            2 -> if (n == 1) MotionEvent.ACTION_UP else (MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            else -> MotionEvent.ACTION_MOVE
        }
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = Array(n) { j -> MotionEvent.PointerProperties().apply { id = ids[j]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { j -> MotionEvent.PointerCoords().apply { x = tX[ids[j]]; y = tY[ids[j]]; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        touchTarget(ev)
        ev.recycle()
    }

    private fun touchAny(): Boolean = tDown[0] || tDown[1]

    private fun releaseTouches() {
        for (k in 0..1) if (tDown[k]) { touchEvent(2, k); tDown[k] = false }
        mouseLaser = -1
    }

    /** Tela cheia: o dedo no celular vira toque direto na página (com a mesma conversão de proporção do desenho). */
    private fun forwardFullscreenTouch(ev: MotionEvent) {
        val w = glView.width.toFloat()
        val h = glView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val tw = VrState.TEX_W.toFloat()
        val th = VrState.TEX_H.toFloat()
        var sx = 1f
        var sy = 1f
        if (!state.fsFill) { val sa = w / h; val ta = tw / th; if (sa > ta) sx = ta / sa else sy = sa / ta }
        val m = android.graphics.Matrix()
        m.postScale(tw / (w * sx), th / (h * sy))
        m.postTranslate(tw * (0.5f - 0.5f / sx), th * (0.5f - 0.5f / sy))
        val c = MotionEvent.obtain(ev)
        c.transform(m)
        c.source = InputDevice.SOURCE_TOUCHSCREEN
        touchTarget(c)
        c.recycle()
    }

    /** Hover de mouse (menus, tooltips). */
    private fun sendHover(x: Float, y: Float) {
        val w = (tvBox ?: web) ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Roda do mouse (L1/R1). */
    private fun sendScroll(x: Float, y: Float, v: Float) {
        val w = (tvBox ?: web) ?: return
        val now = SystemClock.uptimeMillis()
        val pp = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun scrollPage(up: Boolean) {
        val x = if (lastMx < 0f) VrState.TEX_W / 2f else lastMx
        val y = if (lastMy < 0f) VrState.TEX_H / 2f else lastMy
        sendScroll(x, y, if (up) 2f else -2f)
    }

    private fun webDispatchKey(e: KeyEvent) {
        web?.dispatchKeyEvent(e)
    }

    private fun webKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        webDispatchKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        webDispatchKey(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun webChar(c: Char) {
        val evs = kcm.getEvents(charArrayOf(c))
        if (evs != null) {
            for (e in evs) webDispatchKey(e)
        } else {
            web?.onCreateInputConnection(EditorInfo())?.commitText(c.toString(), 1)
        }
    }

    // =================================================================== teclado virtual
    private fun showKeyboard(on: Boolean) {
        state.kbVisible = on
        ui.kbOn = on
        if (on && ui.kbFocus < 0) ui.kbFocus = ui.defaultFocus()
        if (!on) kbTargetUrl = false
    }

    /** O teclado virtual digita na telona quando o Moonlight está aberto (senão, no navegador). */
    private fun moonLive(): Boolean = tvMoon?.isLive() == true && !kbTargetUrl

    private fun typeText(s: String) {
        if (kbTargetUrl) urlText.append(s) else if (moonLive()) tvMoon?.sendText(s) else for (ch in s) webChar(ch)
    }

    private fun backspace() {
        if (kbTargetUrl) {
            if (urlText.isNotEmpty()) urlText.deleteCharAt(urlText.length - 1)
        } else if (moonLive()) tvMoon?.sendVk(0x08) else webKey(KeyEvent.KEYCODE_DEL)
    }

    private fun enterKey() {
        if (kbTargetUrl) {
            go(urlText.toString())
            showKeyboard(false)
        } else if (moonLive()) tvMoon?.sendVk(0x0D) else webKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun pressKey(idx: Int) {
        val k = ui.keys[idx]
        ui.kbFlash = idx
        flashUntil = SystemClock.uptimeMillis() + 130
        when (k.type) {
            VrUi.K_CHAR -> {
                typeText(if (ui.shift) k.upper else k.lower)
                ui.shift = false
            }
            VrUi.K_SPACE -> typeText(" ")
            VrUi.K_DOTCOM -> typeText(".com")
            VrUi.K_BKSP -> backspace()
            VrUi.K_ENTER -> enterKey()
            VrUi.K_SHIFT -> ui.shift = !ui.shift
            VrUi.K_CLOSE -> showKeyboard(false)
            VrUi.K_FN -> {                                              // Esc, Tab, F1-F12
                if (moonLive()) tvMoon?.sendVk(k.vk)
                else when (k.vk) {
                    0x1B -> webKey(KeyEvent.KEYCODE_ESCAPE)
                    0x09 -> webKey(KeyEvent.KEYCODE_TAB)
                    0x5B -> webKey(KeyEvent.KEYCODE_META_LEFT)
                    else -> webKey(KeyEvent.KEYCODE_F1 + (k.vk - 0x70))
                }
            }
        }
    }

    private fun onDpad(dx: Int, dy: Int) {
        ui.kbFocus = ui.navigate(ui.kbFocus, dx, dy)
    }

    private fun setScreenHidden(h: Boolean) { state.screenHidden = h; ui.screenHidden = h; ui.setMenu(ui.menuOpen) }

    private fun toolbarAction(id: Int) {
        ui.tbFlash = id
        tbFlashUntil = SystemClock.uptimeMillis() + 130
        when (id) {
            VrUi.B_BACK -> if (tvApp == TvApp.BROWSER) web?.let { if (it.canGoBack()) it.goBack() } else if (tvApp != TvApp.HOME) showTv(TvApp.HOME)
            VrUi.B_FWD -> web?.let { if (it.canGoForward()) it.goForward() }
            VrUi.B_RELOAD -> web?.reload()
            VrUi.B_HOME -> { if (tvApp != TvApp.BROWSER) showTv(TvApp.BROWSER); web?.loadUrl(homeUrl) }       // Início = página inicial do navegador
            VrUi.B_TVHOME -> { setScreenHidden(false); showTv(TvApp.HOME) }                               // Home = tela inicial da telona (reabre a tela)
            VrUi.B_HIDE -> setScreenHidden(!state.screenHidden)
            VrUi.B_MENU -> ui.setMenu(!ui.menuOpen)
            VrUi.B_URL -> {
                urlText.setLength(0)
                kbTargetUrl = true
                showKeyboard(true)
                kbTargetUrl = true
            }
            VrUi.B_ZM -> stepScale(+1)
            VrUi.B_ZP -> stepScale(-1)
            VrUi.B_KB -> {
                if (state.kbVisible) showKeyboard(false)
                else { kbTargetUrl = false; showKeyboard(true) }
            }
            VrUi.B_CURVE -> state.curved = !state.curved
            VrUi.B_DOF -> {
                state.sixDof = !state.sixDof
                if (state.sixDof) startAr() else if (!state.handsOn && !state.passthrough) { stopAr(); state.arState = 0 }
            }
            VrUi.B_HANDS -> {
                state.handsOn = !state.handsOn
                stopAr()
                startAr()
            }
            VrUi.B_HLASER -> state.handLasers = !state.handLasers
            VrUi.B_TWO -> {
                state.twoHands = !state.twoHands
                tracker?.wantHands = if (state.twoHands) 2 else 1      // o rastreador se recria sozinho
            }
            VrUi.B_LITE -> {
                state.lite = !state.lite
                applyLite()
            }
            VrUi.B_CAM -> {
                state.passthrough = !state.passthrough
                stopAr()
                startAr()
            }
            VrUi.B_CENTER -> { state.recenter = true; extras?.recenter() }
            VrUi.B_EXIT -> exitVr()
        }
    }

    // =================================================================== controle (gamepad) e teclado físico
    /**
     * TABELA ÚNICA de botões: gamepad e controles 3DoF (ESP32/celular) passam por aqui, então o mesmo botão faz a mesma coisa
     * em qualquer aparelho. Teclado virtual aberto muda a função (digitar); fechado, navega.
     *   A      teclado: aperta a tecla em foco (ou Enter)        fechado: —
     *   B      teclado: fecha                                    fechado: voltar
     *   X      teclado: apagar                                   fechado: recarregar
     *   Y      teclado: espaço                                   fechado: centrar a vista
     *   Start  teclado: Enter                                    fechado: liga/desliga o 3D (estéreo)
     *   Select teclado: fecha                                    fechado: sair do VR (só gamepad)
     *   L1/R1  rolar para cima/baixo (analógico dos controles 3DoF rola igual)
     *   Thumb  (clique do analógico) abre/fecha o teclado
     *   L2/R2 e Trigger/R2 dos controles: clique/arrastar (não passam por aqui)
     */
    private enum class PadBtn { A, B, X, Y, START, SELECT, L1, R1, THUMB }

    private fun padButton(b: PadBtn, down: Boolean, first: Boolean) {
        val kb = state.kbVisible
        when (b) {
            PadBtn.A -> if (first && kb) { if (ui.kbFocus >= 0) pressKey(ui.kbFocus) else enterKey() }
            PadBtn.X -> if (kb) { if (down) backspace() } else if (first) web?.reload()
            PadBtn.Y -> if (first) { if (kb) typeText(" ") else state.recenter = true }
            PadBtn.B -> if (first) { if (kb) showKeyboard(false) else web?.let { if (it.canGoBack()) it.goBack() } }
            PadBtn.START -> if (first) { if (kb) enterKey() else state.stereo = !state.stereo }
            PadBtn.SELECT -> if (first) { if (kb) showKeyboard(false) else exitVr() }
            PadBtn.L1 -> if (down) scrollPage(true)
            PadBtn.R1 -> if (down) scrollPage(false)
            PadBtn.THUMB -> if (first) toolbarAction(VrUi.B_KB)
        }
    }

    private fun updateTriggers() {
        state.l2 = max(axL2, if (keyL2) 1f else 0f)
        state.r2 = max(axR2, if (keyR2) 1f else 0f)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (vrMode &&
            (ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            ev.action == MotionEvent.ACTION_MOVE
        ) {
            GamepadMap.onMotion(ev)
            state.lx = GamepadMap.analog(GamepadMap.Fn.LSX)
            state.ly = GamepadMap.analog(GamepadMap.Fn.LSY)
            state.rx = GamepadMap.analog(GamepadMap.Fn.RSX)
            state.ry = GamepadMap.analog(GamepadMap.Fn.RSY)
            axL2 = GamepadMap.analog(GamepadMap.Fn.LT)
            axR2 = GamepadMap.analog(GamepadMap.Fn.RT)
            updateTriggers()

            hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val ndx = if (hatX > 0.5f) 1 else if (hatX < -0.5f) -1 else 0
            val ndy = if (hatY > 0.5f) 1 else if (hatY < -0.5f) -1 else 0
            if (padToMoon) { hatDx = ndx; hatDy = ndy; padMoonSend(); return true }
            if (state.kbVisible && (ndx != 0 || ndy != 0) && (ndx != hatDx || ndy != hatDy)) {
                onDpad(ndx, ndy)
                hatNext = SystemClock.uptimeMillis() + 350
            }
            hatDx = ndx
            hatDy = ndy
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- gamepad do celular: START duas vezes seguidas alterna entre "controlar o laser da Sala 3D" e "mandar como controle Xbox 360 ao PC (Moonlight)"
    private var padToMoon = false
    private var lastStartMs = 0L
    private var padBtns = 0
    private fun padBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000; KeyEvent.KEYCODE_BUTTON_B -> 0x2000; KeyEvent.KEYCODE_BUTTON_X -> 0x4000; KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100; KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010; KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040; KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001; KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002; KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004; KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        else -> 0
    }
    private fun padMoonSend() {
        var b = padBtns
        if (hatDx < 0) b = b or 0x0004 else if (hatDx > 0) b = b or 0x0008
        if (hatDy < 0) b = b or 0x0001 else if (hatDy > 0) b = b or 0x0002
        tvMoon?.sendPad(b, state.l2, state.r2, state.lx, state.ly, state.rx, state.ry)
    }

    override fun dispatchKeyEvent(e0: KeyEvent): Boolean {
        if (!vrMode) return super.dispatchKeyEvent(e0)
        val e = GamepadMap.translate(e0) ?: return true      // mapeamento do gamepad (tela "Mapear gamepad")
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val fromPad = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val kb = state.kbVisible
        if (fromPad && e.keyCode == KeyEvent.KEYCODE_BUTTON_START && first) {
            val t = SystemClock.uptimeMillis()
            if (t - lastStartMs < 450) {                                  // duplo clique no Start
                lastStartMs = 0
                if (padToMoon) { padToMoon = false; padBtns = 0; tvMoon?.sendPad(0, 0f, 0f, 0f, 0f, 0f, 0f) }
                else if (moonLive()) padToMoon = true
                Toast.makeText(this, if (padToMoon) "Gamepad → PC (Xbox 360)" else "Gamepad → laser da Sala 3D", Toast.LENGTH_SHORT).show()
                return true
            }
            lastStartMs = t
        }
        if (padToMoon && !moonLive()) padToMoon = false
        if (padToMoon && fromPad) {
            val bit = padBit(e.keyCode)
            if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L2) { keyL2 = down; updateTriggers(); padMoonSend(); return true }
            if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R2) { keyR2 = down; updateTriggers(); padMoonSend(); return true }
            if (bit != 0) { padBtns = if (down) padBtns or bit else padBtns and bit.inv(); padMoonSend(); return true }
            return true                                                   // MODO GAMEPAD: é só um gamepad; nenhuma função antiga (voltar, sair, teclado, rolar) fica ativa
        }

        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { keyL2 = down; updateTriggers(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { keyR2 = down; updateTriggers(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { padButton(PadBtn.L1, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { padButton(PadBtn.R1, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_A -> { padButton(PadBtn.A, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { padButton(PadBtn.X, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> { padButton(PadBtn.Y, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { padButton(PadBtn.B, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_START -> { padButton(PadBtn.START, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> { padButton(PadBtn.SELECT, down, first); return true }
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> { padButton(PadBtn.THUMB, down, first); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (fromPad) {
                if (kb) { if (down) onDpad(0, -1) } else keyDy = if (down) -1f else 0f
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (fromPad) {
                if (kb) { if (down) onDpad(0, 1) } else keyDy = if (down) 1f else 0f
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (fromPad) {
                if (kb) { if (down) onDpad(-1, 0) } else keyDx = if (down) -1f else 0f
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (fromPad) {
                if (kb) { if (down) onDpad(1, 0) } else keyDx = if (down) 1f else 0f
                return true
            }
        }
        // teclado físico Bluetooth -> navegador
        webDispatchKey(e)
        return true
    }
}
