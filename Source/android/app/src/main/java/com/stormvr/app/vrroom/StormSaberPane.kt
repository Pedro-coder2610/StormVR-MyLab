package com.stormvr.app.vrroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * StormSaber = porta do OpenSaberVR (github.com/dhcdht/OpenSaberVR, MIT, © 2019 Daniel Isenmann) para o StormVR/Android.
 *
 * Fiel ao original (NotesSpawner.cs / Saber.cs / MainMenu.cs / LoadSongInfos.cs):
 *  - 12 pontos de surgimento (3 camadas × 4 linhas: x = ±0.35 / ±0.75 m, alturas 0.9 / 1.4 / 1.9 m), cubos de 0,3 m vindos de 10,5 m a 8 m/s;
 *  - o cubo surge ANTICIPATION = 1,1 s antes do tempo da nota (a nota chega a ~1,7 m do jogador na batida); BPM -> segundos como no BeatSaver Viewer;
 *  - corte: o sabre (1 m) precisa atravessar o cubo da MESMA cor e se mover a mais de 130° do "up" do cubo (= dentro de 50° da direção da seta);
 *    cubos sem direção (8) cortam em qualquer sentido; cubo da cor errada não interage;
 *  - (StormVR) PONTUAÇÃO como no Beat Saber (até 115 por corte × multiplicador de combo), "ERROU" ao deixar o bloco passar, 10 VIDAS (errar tira 1, acertar devolve 1) e tela de resultado;
 *  - fluxo: Título -> Músicas (capa, nome, artista, BPM, níveis; prévia do áudio aos 40 s) -> Níveis (se houver mais de uma dificuldade) -> jogo
 *    -> ao fim da música (ou ao perder as 10 vidas) aparece o resultado. Obstáculos e minas não são suportados (igual ao original).
 * Diferenças por causa do 3DoF: o movimento é medido na PONTA do sabre (a mão do StormVR é fixa), com velocidade mínima contra tremor.
 */
class StormSaberPane(ctx: Context) : FrameLayout(ctx) {

    private enum class St { TITLE, OPTIONS, SONGS, PLAY, RESULT }
    private class Cube(var d: Float, val x: Float, val z: Float, val color: Int, val dir: Int)
    private class Piece(var x: Float, val d: Float, var z: Float, var vx: Float, var vz: Float, var rot: Float, val vr: Float, val color: Int, var life: Float, val side: Int, val ang: Float)

    // constantes do original
    private val SPAWN = 10.5f
    private val SPEED = 8f
    private val ANTIC = 1.1f
    private val CUBE = 0.30f
    // ---- AJUSTES do sabre (menu "Ajustar sabres", salvos): a mão fica perto do corpo (profundidade pequena) para o sabre aparecer COMPRIDO mesmo apontado
    // para a frente e para alcançar os blocos QUANDO JÁ ESTÃO PERTO; o 3DoF não tem posição, então o deslocamento da mão é escolhido aqui.
    private var HAND_D = 0.30f                 // profundidade da mão (m à frente dos olhos)
    private var SABER_LEN = 1.80f              // comprimento da lâmina (original: 1 m)
    private var HAND_X = 0.22f                 // lateral: cada mão fica HAND_X m para o seu lado
    private var HAND_Z = -0.30f                // altura em relação aos olhos
    private var AIM_GAIN = 1.7f                // ALCANCE: multiplica o ângulo do controle para os lados/cima/baixo (pistas laterais ficam ao alcance do pulso)
    private val sabPrefs = ctx.getSharedPreferences("stormvr_saber", Context.MODE_PRIVATE)
    private fun loadSaberPrefs() {
        HAND_D = sabPrefs.getFloat("hand_d", 0.30f).coerceIn(0.10f, 1.2f); SABER_LEN = sabPrefs.getFloat("len", 1.80f).coerceIn(0.8f, 3.0f)
        HAND_X = sabPrefs.getFloat("hand_x", 0.22f).coerceIn(0.05f, 0.7f); HAND_Z = sabPrefs.getFloat("hand_z", -0.30f).coerceIn(-0.9f, 0.3f)
        AIM_GAIN = sabPrefs.getFloat("aim_gain", 1.7f).coerceIn(1.0f, 3.0f)
    }
    private fun saveSaberPrefs() { sabPrefs.edit().putFloat("hand_d", HAND_D).putFloat("len", SABER_LEN).putFloat("hand_x", HAND_X).putFloat("hand_z", HAND_Z).putFloat("aim_gain", AIM_GAIN).apply() }
    private val lanes = floatArrayOf(-0.75f, -0.35f, 0.35f, 0.75f)
    private val layers = floatArrayOf(-0.70f, -0.20f, 0.30f)          // 0,9 / 1,4 / 1,9 m menos a altura dos olhos (1,6 m)
    private val colors = intArrayOf(Color.rgb(255, 28, 40), Color.rgb(0, 144, 255))   // Red.mat / Blue.mat (0 = esquerda vermelho, 1 = direita azul)

    private class Flash(val x: Float, val d: Float, val z: Float, var life: Float, val color: Int)
    private class Popup(val x: Float, val d: Float, val z: Float, var life: Float, val text: String, val color: Int)
    private val popups = ArrayList<Popup>()

    // ---- obstáculos (paredes do mapa): desvie inclinando a cabeça / agachando (3DoF com extras, ou 6DoF)
    private var walls: List<SongLibrary.Wall> = emptyList()
    private var wallHits = 0
    private var lastWallMs = 0L
    @Volatile private var bodyX = 0f
    @Volatile private var bodyZ = 0f
    /** Posição da cabeça em relação à posição de partida: x = lado (m, + direita), z = altura (m, − agachou). */
    fun setBody(x: Float, z: Float) { bodyX = x; bodyZ = z }
    @Volatile var wallsOn = ctx.getSharedPreferences("stormvr_saber", Context.MODE_PRIVATE).getBoolean("walls", true)
        set(v) { field = v; sabPrefs.edit().putBoolean("walls", v).apply() }

    // ---- pontuação, vidas e resultado
    private val MAX_LIVES = 10
    private val MAX_CUT = 115                          // 70 de direção/velocidade/centro + bônus, como o máximo do Beat Saber
    private var lives = MAX_LIVES
    private var score = 0
    private var combo = 0
    private var maxCombo = 0
    private var hits = 0
    private var misses = 0
    private var processed = 0                          // notas já resolvidas (acertadas ou perdidas)
    private var maxSoFar = 0                           // pontuação máxima possível das notas já resolvidas
    private var maxTotal = 0
    private var totalNotes = 0
    private var failed = false
    private var diffIdx = 0
    private var resultShown = false
    private fun multAt(c: Int) = if (c < 2) 1 else if (c < 6) 2 else if (c < 14) 4 else 8
    private fun accuracy(): Float = if (maxSoFar <= 0) 100f else 100f * score / maxSoFar
    private fun rankOf(pct: Float, fail: Boolean) = if (fail) "F" else if (pct >= 90f) "SS" else if (pct >= 80f) "S" else if (pct >= 65f) "A" else if (pct >= 50f) "B" else if (pct >= 35f) "C" else if (pct >= 20f) "D" else "E"
    private val flashes = ArrayList<Flash>()
    private val cubes = ArrayList<Cube>()
    private val pieces = ArrayList<Piece>()

    // sabres: mão (origem), ponta e velocidade da ponta
    private val hand = Array(2) { FloatArray(3) }
    private val tip = Array(2) { FloatArray(3) }
    private val prevTip = Array(2) { FloatArray(3) }
    private val vel = Array(2) { FloatArray(3) }
    private val active = BooleanArray(2)
    private val hasPrev = BooleanArray(2)

    @Volatile var stereo = false
        set(v) { field = v; post { applyVis(); refreshLabels() } }
    @Volatile var twoSabers = true
    /** Dedos na tela movem os sabres (metade esquerda = sabre esquerdo, metade direita = sabre direito). Opção salva; pode ser desligada. */
    @Volatile var fingerSabers = ctx.getSharedPreferences("stormvr_saber", Context.MODE_PRIVATE).getBoolean("finger", true)
        set(v) { field = v; sabPrefs.edit().putBoolean("finger", v).apply(); post { refreshLabels() } }
    /** Falso quando quem recebe o toque é a Sala VR (ela mesma converte os dedos em sabres/lasers). */
    var handleTouch = true
    private val fingerPtr = IntArray(2) { -1 }
    /** Se definido, aparece o botão "Sair" no título (a Activity usa para fechar). */
    var onQuit: (() -> Unit)? = null

    private var st = St.TITLE
    private var songs: List<SongLibrary.Song> = emptyList()
    private var songIdx = 0
    private var curSong: SongLibrary.Song? = null
    private var curDiff: SongLibrary.Diff? = null
    private var notes: List<SongLibrary.Note> = emptyList()
    private var noteI = 0
    private var cutCount = 0
    private var preload = 0f
    private var T = 0f
    private var audioReady = false
    private var audioStarted = false
    private var audioDone = false
    private var doneAtMs = 0L
    private var game: MediaPlayer? = null
    private var preview: MediaPlayer? = null
    private var lastNs = 0L
    private val ipd = 0.064f
    private var lastHud = ""
    private var status = ""
    private var menuShown = true
    private var btnTitleView: TextView? = null
    private var btnTitleSabers: TextView? = null

    // ---- ponteiro no modo ESTÉREO: o menu é desenhado em cada olho, num plano a UI_D metros; aponte o sabre e use o gatilho (ou segure 1 s) ----
    private val UI_D = 2.2f
    private val trig = BooleanArray(2)
    private val trigPrev = BooleanArray(2)
    private var cursorOn = false
    private var cursorPx = 0f
    private var cursorPy = 0f
    private var dwellView: View? = null
    private var dwellT = 0f
    private var cooldown = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }   // BLOOM: soma de luz
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val dst = RectF()
    private var scroll = 0f
    private val haloRed: Bitmap
    private val haloBlue: Bitmap
    private val haloWhite: Bitmap
    private val bg: Bitmap
    private val qx = FloatArray(8)
    private val qy = FloatArray(8)
    private var gCx = 0f
    private var gCy = 0f
    private var gF = 1f
    private var gEye = 0f
    private val rect = RectF()
    private val dm = resources.displayMetrics
    private val unit = min(dm.widthPixels, dm.heightPixels)
    private val face: Typeface? = try { Typeface.createFromAsset(ctx.assets, "opensaber/Gugi-Regular.ttf") } catch (_: Throwable) { null }
    private val PURPLE = Color.rgb(168, 85, 247)

    private val hud: TextView
    private val btnView: TextView
    private val btnSabers: TextView
    private val btnMenu: TextView
    private val menu: LinearLayout

    // ------------------------------------------------------------------ interface (Views: tocar ou apontar o laser)
    private fun tv(t: String, size: Float, color: Int = Color.WHITE) = TextView(context).apply {
        text = t; setTextColor(color); textSize = unit * size / dm.scaledDensity; gravity = Gravity.CENTER; face?.let { typeface = it }
    }

    private fun btn(t: String, primary: Boolean = false, onClick: () -> Unit) = tv(t, if (primary) 0.040f else 0.032f).apply {
        setPadding(unit / 14, unit / 50, unit / 14, unit / 50); isClickable = true
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(104, 36, 206), Color.rgb(176, 96, 255))).apply { cornerRadius = 90f; setStroke(3, Color.argb(220, 230, 205, 255)) }
                     else GradientDrawable().apply { setColor(Color.argb(210, 34, 18, 62)); setStroke(3, Color.argb(170, 168, 85, 247)); cornerRadius = 90f }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(unit / 70, unit / 70, unit / 70, unit / 70) }
    }

    /** Botão de ligar/desligar: mostra o estado (● ligado / ○ desligado). */
    private fun toggle(label: String, on: () -> Boolean, set: (Boolean) -> Unit) = btn("") { set(!on()); }.also { b ->
        fun upd() { b.text = (if (on()) "●  " else "○  ") + label + (if (on()) ": LIGADO" else ": DESLIGADO") }
        upd(); b.setOnClickListener { set(!on()); upd(); invalidate() }
    }

    private fun panelBg() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(235, 30, 14, 62), Color.argb(235, 12, 6, 26))).apply { setStroke(3, Color.argb(200, 168, 85, 247)); cornerRadius = 44f }

    private fun diffColor(name: String): Int = when (name.lowercase().replace(" ", "").replace("+", "plus")) {
        "easy" -> Color.rgb(76, 175, 80); "normal" -> Color.rgb(33, 150, 243); "hard" -> Color.rgb(255, 152, 0)
        "expert" -> Color.rgb(244, 67, 54); "expertplus" -> Color.rgb(186, 85, 255); else -> Color.rgb(168, 85, 247)
    }

    private fun diffChip(name: String, selected: Boolean, onClick: () -> Unit) = tv(name.replace("ExpertPlus", "Expert+"), 0.028f).apply {
        val c = diffColor(name)
        setPadding(unit / 22, unit / 90, unit / 22, unit / 90); isClickable = true
        setTextColor(if (selected) Color.WHITE else Color.argb(220, 220, 205, 245))
        background = GradientDrawable().apply { setColor(if (selected) c else Color.argb(150, 24, 12, 44)); setStroke(if (selected) 4 else 2, c); cornerRadius = 70f }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(unit / 110, unit / 90, unit / 110, unit / 90) }
    }

    init {
        setBackgroundColor(Color.rgb(8, 6, 14))
        setWillNotDraw(false)
        fun chip(t: String, onClick: () -> Unit) = tv(t, 0.028f).apply {
            setPadding(26, 12, 26, 12); isClickable = true
            background = GradientDrawable().apply { setColor(Color.argb(190, 30, 16, 56)); setStroke(2, PURPLE); cornerRadius = 40f }
            setOnClickListener { onClick() }
        }
        btnView = chip("Visão: MONO") { stereo = !stereo; refreshLabels() }
        btnSabers = chip("Sabres: 2") { twoSabers = !twoSabers; refreshLabels() }
        btnMenu = chip("Menu") { if (st == St.PLAY) { endGame(); showSongs() } else showTitle() }
        addView(btnView, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 24, 24, 0) })
        addView(btnSabers, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 24 + (unit * 0.07f).toInt(), 24, 0) })
        addView(btnMenu, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 24 + (unit * 0.14f).toInt(), 24, 0) })
        hud = tv("", 0.04f).apply { setShadowLayer(8f, 0f, 0f, Color.BLACK) }
        addView(hud, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, 24) })
        menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(unit / 22, unit / 26, unit / 22, unit / 26)
            background = panelBg(); minimumWidth = (unit * 1.05f).toInt()
        }
        addView(menu, LayoutParams(-2, -2, Gravity.CENTER))
        haloRed = makeHalo(colors[0]); haloBlue = makeHalo(colors[1]); haloWhite = makeHalo(Color.WHITE); bg = makeBg()
        loadSaberPrefs()
        songs = try { SongLibrary.scan(ctx) } catch (_: Throwable) { emptyList() }
        refreshLabels()
        showTitle()
        applyVis()
    }

    private fun refreshLabels() {
        val v = if (stereo) "Visão: ESTÉREO SBS" else "Visão: MONO"
        val sb = "Sabres: " + (if (twoSabers) "2" else "1")
        btnView.text = v; btnSabers.text = sb; btnTitleView?.text = v; btnTitleSabers?.text = sb
    }

    /** Mono: Views normais. Estéreo: as Views ficam INVISÍVEIS (só medem) e são desenhadas à mão em cada olho (drawOverlay). */
    private fun applyVis() {
        menu.visibility = if (!menuShown) View.GONE else if (stereo) View.INVISIBLE else View.VISIBLE
        hud.visibility = if (stereo) View.INVISIBLE else View.VISIBLE
        btnView.visibility = if (stereo) View.GONE else View.VISIBLE
        btnSabers.visibility = if (stereo) View.GONE else View.VISIBLE
        btnMenu.visibility = if (stereo) (if (st == St.PLAY) View.INVISIBLE else View.GONE) else View.VISIBLE
    }

    private fun clearMenu() { menu.removeAllViews(); menuShown = true; applyVis() }

    private fun hRow(vararg v: View) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; for (x in v) addView(x) }

    private fun showTitle() {
        st = St.TITLE; status = ""; stopPreview(); stopGameAudio(); clearMenu()
        try { menu.addView(ImageView(context).apply { setImageBitmap(BitmapFactory.decodeStream(context.assets.open("opensaber/Title.png"))); adjustViewBounds = true }, LinearLayout.LayoutParams((unit * 0.85f).toInt(), -2)) }
        catch (_: Throwable) { menu.addView(tv("STORMSABER", 0.08f, PURPLE)) }
        menu.addView(tv("Corte os blocos no ritmo da música", 0.026f, Color.argb(200, 220, 200, 255)).apply { setPadding(0, 0, 0, unit / 40) })
        menu.addView(btn("▶   Jogar", true) { showSongs() })
        menu.addView(btn("⚙   Opções") { showOptions() })
        if (onQuit != null) menu.addView(btn("Sair") { onQuit?.invoke() })
    }

    /** Opções: visão, número de sabres, dedos como sabres e ajuste da lâmina. */
    private fun showOptions() {
        st = St.OPTIONS; clearMenu()
        menu.addView(tv("Opções", 0.046f, PURPLE))
        btnTitleView = btn("") { stereo = !stereo; refreshLabels() }; menu.addView(btnTitleView)
        btnTitleSabers = btn("") { twoSabers = !twoSabers; refreshLabels() }; menu.addView(btnTitleSabers)
        refreshLabels()
        menu.addView(toggle("Dedos movem os sabres", { fingerSabers }, { fingerSabers = it }))
        menu.addView(toggle("Obstáculos (paredes)", { wallsOn }, { wallsOn = it }))
        menu.addView(tv("Metade esquerda da tela = sabre esquerdo · metade direita = sabre direito", 0.021f, Color.argb(190, 210, 190, 240)).apply { setPadding(0, 0, 0, unit / 60) })
        menu.addView(btn("Ajustar sabres (tamanho e posição)") { showAdjust() })
        menu.addView(btn("‹  Voltar") { showTitle() })
    }

    /** Tela "Ajustar sabres": comprimento e DESLOCAMENTO da mão (o 3DoF não tem posição: a mão é um ponto fixo que você escolhe). */
    private fun showAdjust() {
        clearMenu()
        menu.addView(tv("Ajustar sabres", 0.045f, PURPLE))
        fun row(name: String, get: () -> Float, set: (Float) -> Unit, step: Float, unit2: String = " m") {
            val lbl = tv("", 0.027f); fun upd() { lbl.text = name + ": " + "%.2f".format(get()) + unit2 }; upd()
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            r.addView(btn("−") { set(get() - step); saveSaberPrefs(); upd() })
            r.addView(lbl, LinearLayout.LayoutParams(unit * 6 / 10, -2))
            r.addView(btn("+") { set(get() + step); saveSaberPrefs(); upd() })
            menu.addView(r)
        }
        row("Comprimento", { SABER_LEN }, { SABER_LEN = it.coerceIn(0.8f, 3.0f) }, 0.1f)
        row("Afastamento lateral", { HAND_X }, { HAND_X = it.coerceIn(0.05f, 0.7f) }, 0.03f)
        row("Altura da mão", { HAND_Z }, { HAND_Z = it.coerceIn(-0.9f, 0.3f) }, 0.05f)
        row("Distância da mão", { HAND_D }, { HAND_D = it.coerceIn(0.10f, 1.2f) }, 0.05f)
        row("Alcance (ângulo ×)", { AIM_GAIN }, { AIM_GAIN = it.coerceIn(1.0f, 3.0f) }, 0.1f, " ×")
        menu.addView(hRow(btn("Restaurar") { HAND_D = 0.30f; SABER_LEN = 1.80f; HAND_X = 0.22f; HAND_Z = -0.30f; AIM_GAIN = 1.7f; saveSaberPrefs(); showAdjust() }, btn("‹  Voltar") { showOptions() }))
    }

    /** Escolha da música: capa grande, título, artista, níveis coloridos NA MESMA TELA e um único botão Jogar. */
    private fun showSongs() {
        st = St.SONGS; stopGameAudio(); clearMenu()
        if (songs.isEmpty()) {
            menu.addView(tv("Nenhuma música encontrada", 0.045f, PURPLE))
            menu.addView(tv("Baixe músicas do BeatSaver / BeastSaber e coloque (pasta ou .zip) em:\nAndroid/data/com.stormvr.app/files/Playlists/", 0.026f).apply { setPadding(0, unit / 40, 0, unit / 40) })
            menu.addView(hRow(btn("Atualizar lista", true) { songs = try { SongLibrary.scan(context) } catch (_: Throwable) { emptyList() }; showSongs() }, btn("‹  Voltar") { showTitle() }))
            return
        }
        songIdx = songIdx.coerceIn(0, songs.size - 1)
        val s = songs[songIdx]; curSong = s
        diffIdx = diffIdx.coerceIn(0, s.diffs.size - 1)
        menu.addView(tv("ESCOLHA A MÚSICA", 0.024f, Color.argb(210, 200, 170, 250)))
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Color.rgb(30, 16, 56)); setStroke(4, Color.argb(220, 168, 85, 247)); cornerRadius = 28f }
            if (android.os.Build.VERSION.SDK_INT >= 21) { outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true }
        }
        s.cover?.let { f -> try { val o = BitmapFactory.Options().apply { inSampleSize = 4 }; cover.setImageBitmap(BitmapFactory.decodeFile(f.path, o)) } catch (_: Throwable) { } }
        val side = (unit * 0.30f).toInt()
        val prev = btn("◀") { songIdx = (songIdx - 1 + songs.size) % songs.size; diffIdx = 0; showSongs() }.apply { setPadding(unit / 24, unit / 14, unit / 24, unit / 14) }
        val next = btn("▶") { songIdx = (songIdx + 1) % songs.size; diffIdx = 0; showSongs() }.apply { setPadding(unit / 24, unit / 14, unit / 24, unit / 14) }
        val cr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cr.addView(prev); cr.addView(cover, LinearLayout.LayoutParams(side, side).apply { setMargins(unit / 40, unit / 60, unit / 40, unit / 60) }); cr.addView(next)
        menu.addView(cr)
        menu.addView(tv(s.name, 0.046f, Color.WHITE).apply { maxWidth = (unit * 0.95f).toInt(); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        menu.addView(tv(s.artist, 0.028f, PURPLE).apply { maxWidth = (unit * 0.95f).toInt(); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        menu.addView(tv("♪ ${s.bpm.toInt()} BPM    ·    ${songIdx + 1} / ${songs.size}", 0.024f, Color.argb(210, 220, 200, 255)).apply { setPadding(0, unit / 90, 0, unit / 90) })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((k, d) in s.diffs.withIndex()) chips.addView(diffChip(d.name, k == diffIdx) { diffIdx = k; showSongs() })
        menu.addView(chips)
        menu.addView(hRow(btn("▶   Jogar", true) { startGame(s, s.diffs[diffIdx.coerceIn(0, s.diffs.size - 1)]) }, btn("‹  Voltar") { showTitle() }))
        previewSong(s)
    }

    /** Resultado: pontos, precisão, rank, acertos/erros e melhor combo (também quando as 10 vidas acabam). */
    private fun showResult(fail: Boolean) {
        st = St.RESULT; resultShown = true; stopGameAudio(); clearMenu()
        val total = maxTotal.coerceAtLeast(1)
        val pct = if (fail) accuracy() else 100f * score / total
        val rank = rankOf(pct, fail)
        menu.addView(tv(if (fail) "VOCÊ PERDEU" else "MÚSICA CONCLUÍDA", 0.040f, if (fail) Color.rgb(255, 90, 100) else PURPLE))
        menu.addView(tv((curSong?.name ?: "") + "  ·  " + (curDiff?.name ?: "").replace("ExpertPlus", "Expert+"), 0.024f, Color.argb(210, 220, 200, 255)).apply { maxWidth = (unit * 0.95f).toInt(); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        menu.addView(tv(rank, 0.15f, if (fail) Color.rgb(255, 90, 100) else Color.rgb(255, 214, 90)).apply { setShadowLayer(14f, 0f, 0f, Color.argb(200, 255, 190, 60)) })
        fun stat(k: String, v: String, c: Int = Color.WHITE) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tv(k, 0.026f, Color.argb(210, 200, 180, 240)).apply { gravity = Gravity.START }, LinearLayout.LayoutParams((unit * 0.48f).toInt(), -2))
            addView(tv(v, 0.030f, c).apply { gravity = Gravity.END }, LinearLayout.LayoutParams((unit * 0.42f).toInt(), -2))
        }
        menu.addView(stat("Pontuação", "%,d".format(score).replace(',', '.')))
        menu.addView(stat("Precisão", "%.1f %%".format(pct), Color.rgb(255, 214, 90)))
        menu.addView(stat("Acertos", "$hits / $totalNotes", Color.rgb(120, 230, 140)))
        menu.addView(stat("Erros", "$misses", if (misses == 0) Color.rgb(120, 230, 140) else Color.rgb(255, 110, 120)))
        menu.addView(stat("Melhor combo", "$maxCombo"))
        if (walls.isNotEmpty()) menu.addView(stat("Paredes batidas", "$wallHits", if (wallHits == 0) Color.rgb(120, 230, 140) else Color.rgb(255, 150, 40)))
        if (fail) menu.addView(stat("Notas restantes", "${(totalNotes - processed).coerceAtLeast(0)}", Color.argb(200, 220, 200, 255)))
        menu.addView(hRow(btn("↻   Tentar de novo", true) { val a = curSong; val d = curDiff; if (a != null && d != null) startGame(a, d) else showSongs() }, btn("Músicas") { showSongs() }))
    }

    // ------------------------------------------------------------------ áudio
    private fun stopPreview() { try { preview?.release() } catch (_: Throwable) { }; preview = null }
    private fun stopGameAudio() { try { game?.release() } catch (_: Throwable) { }; game = null; audioReady = false; audioStarted = false }

    /** Prévia do áudio a partir dos 40 s, como o MainMenu.cs. */
    private fun previewSong(s: SongLibrary.Song) {
        stopPreview()
        val f = s.audio ?: return
        try {
            preview = MediaPlayer().apply {
                setDataSource(f.path); setVolume(0.6f, 0.6f)
                setOnPreparedListener { m -> try { if (m.duration > 45000) m.seekTo(40000); m.start() } catch (_: Throwable) { } }
                prepareAsync()
            }
        } catch (_: Throwable) { stopPreview() }
    }

    // ------------------------------------------------------------------ jogo
    /**
     * Remove notas IMPOSSÍVEIS: duas notas da mesma cor tão próximas no tempo que o braço não alcança (ou na mesma batida, em lugares diferentes).
     * Tempo necessário = distância entre as casas / velocidade máxima da mão + pequena folga; a nota de baixo é descartada.
     */
    private fun fixNotes(src: List<SongLibrary.Note>): List<SongLibrary.Note> {
        val out = ArrayList<SongLibrary.Note>(src.size)
        val last = arrayOfNulls<SongLibrary.Note>(2)
        for (n in src.sortedBy { it.t }) {
            val c = n.color.coerceIn(0, 1)
            val p = last[c]
            if (p != null) {
                val dx = lanes[n.lane.coerceIn(0, 3)] - lanes[p.lane.coerceIn(0, 3)]
                val dz = layers[n.layer.coerceIn(0, 2)] - layers[p.layer.coerceIn(0, 2)]
                val dist = hypot(dx, dz)
                val need = if (dist < 0.01f) 0.07f else 0.09f + dist / 5.0f          // 5 m/s de varredura confortável
                if (n.t - p.t < need) continue                                       // perto demais: impossível acertar as duas
            }
            out.add(n); last[c] = n
        }
        return out
    }

    private fun startGame(s: SongLibrary.Song, d: SongLibrary.Diff) {
        stopPreview(); stopGameAudio()
        val raw = try { SongLibrary.notes(s, d) } catch (_: Throwable) { emptyList() }
        if (raw.isEmpty()) { showSongs(); status = "Mapa sem notas ou formato não suportado"; return }
        val n = fixNotes(raw)
        curSong = s; curDiff = d; notes = n; noteI = 0; cutCount = 0
        cubes.clear(); pieces.clear(); flashes.clear(); popups.clear(); preload = 0f; T = -ANTIC; audioDone = false; lastHud = ""
        aim.reset(); if (fingerSabers && handleTouch) { aim.showBoth(); applyAim() }; lives = MAX_LIVES; score = 0; combo = 0; maxCombo = 0; hits = 0; misses = 0; processed = 0; maxSoFar = 0; failed = false; resultShown = false
        totalNotes = n.size
        walls = if (wallsOn) try { SongLibrary.walls(s, d) } catch (_: Throwable) { emptyList() } else emptyList()
        wallHits = 0; lastWallMs = 0L
        var mt = 0; for (k in n.indices) mt += MAX_CUT * multAt(k); maxTotal = mt
        val f = s.audio
        if (f != null) {
            try {
                game = MediaPlayer().apply {
                    setDataSource(f.path)
                    setOnPreparedListener { audioReady = true }
                    setOnCompletionListener { audioDone = true; doneAtMs = SystemClock.uptimeMillis() }
                    setOnErrorListener { _, _, _ -> audioReady = true; game = null; true }
                    prepareAsync()
                }
            } catch (_: Throwable) { audioReady = true; game = null }
        } else audioReady = true                                                          // sem áudio: toca em silêncio
        status = ""; st = St.PLAY; menuShown = false; applyVis()
    }

    private fun endGame() { stopGameAudio(); cubes.clear(); pieces.clear(); popups.clear() }

    fun resetGame() { val s = curSong; val d = curDiff; if (st == St.PLAY && s != null && d != null) startGame(s, d) }

    /** Chamado a cada quadro: direção (dx,dy,dz) em que o controle aponta (mesmo referencial do laser). */
    fun setSaber(i: Int, dx: Float, dy: Float, dz: Float, on: Boolean) {
        if (i !in 0..1) return
        synchronized(this) {
            active[i] = on
            if (!on) { hasPrev[i] = false; return }
            hand[i][0] = if (i == 0) -HAND_X else HAND_X; hand[i][1] = HAND_D; hand[i][2] = HAND_Z
            val n = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            var ux = dx / n; var uy = dy / n; var uz = dz / n
            if (AIM_GAIN != 1f && uy > 0.05f) {                                  // amplifica o ângulo em relação à frente: pequenos giros do pulso alcançam as pistas das pontas
                val yaw = atan2(ux, uy); val pit = atan2(uz, hypot(ux, uy))
                val y2 = (yaw * AIM_GAIN).coerceIn(-1.45f, 1.45f); val p2 = (pit * AIM_GAIN).coerceIn(-1.45f, 1.45f)
                ux = sin(y2) * cos(p2); uy = cos(y2) * cos(p2); uz = sin(p2)
            }
            tip[i][0] = hand[i][0] + ux * SABER_LEN; tip[i][1] = hand[i][1] + uy * SABER_LEN; tip[i][2] = hand[i][2] + uz * SABER_LEN
        }
    }

    // ---- cabeça: o mundo (blocos, sabres, feixes) é girado para o referencial da câmera; só o centro de cada objeto gira (o cubo continua desenhado alinhado)
    private var hCy = 1f; private var hSy = 0f; private var hCp = 1f; private var hSp = 0f
    private val rv = FloatArray(3)
    /** Orientação da cabeça: yaw (graus, + = esquerda) e pitch (graus, + = cima). Sem chamar, a cabeça fica parada olhando para a frente. */
    fun setHead(yawDeg: Float, pitchDeg: Float) {
        val y = Math.toRadians(yawDeg.toDouble()); val pch = Math.toRadians(pitchDeg.toDouble())
        hCy = cos(y).toFloat(); hSy = sin(y).toFloat(); hCp = cos(pch).toFloat(); hSp = sin(pch).toFloat()
    }
    /** mundo (x direita, d frente, z cima) -> câmera, em [rv]. */
    private fun toCam(x: Float, d: Float, z: Float) {
        val x1 = x * hCy + d * hSy; val d1 = -x * hSy + d * hCy
        rv[0] = x1; rv[1] = d1 * hCp + z * hSp; rv[2] = -d1 * hSp + z * hCp
    }

    /** Gatilho do controle i (0 = esquerda, 1 = direita): clica no que o sabre está apontando (estéreo). */
    fun setTrigger(i: Int, on: Boolean) { if (i in 0..1) trig[i] = on }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) { lastNs = 0L; try { if (audioStarted && !audioDone) game?.start() } catch (_: Throwable) { }; postInvalidateOnAnimation() }
        else { try { game?.pause() } catch (_: Throwable) { }; try { preview?.pause() } catch (_: Throwable) { } }
    }

    override fun onDetachedFromWindow() { stopPreview(); stopGameAudio(); super.onDetachedFromWindow() }

    /** Direção do golpe (x lateral, z vertical): 0 cima 1 baixo 2 esq 3 dir 4 cima-esq 5 cima-dir 6 baixo-esq 7 baixo-dir; 8 = qualquer. */
    private fun swipe(d: Int): FloatArray { val r = 0.7071f; return when (d) { 0 -> floatArrayOf(0f, 1f); 1 -> floatArrayOf(0f, -1f); 2 -> floatArrayOf(-1f, 0f); 3 -> floatArrayOf(1f, 0f)
        4 -> floatArrayOf(-r, r); 5 -> floatArrayOf(r, r); 6 -> floatArrayOf(-r, -r); 7 -> floatArrayOf(r, -r); else -> floatArrayOf(0f, 0f) } }

    private fun segBox(a: FloatArray, b: FloatArray, cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float): Boolean {
        var t0 = 0f; var t1 = 1f
        val c = floatArrayOf(cx, cy, cz); val h = floatArrayOf(hx, hy, hz)
        for (k in 0..2) {
            val d = b[k] - a[k]; val lo = c[k] - h[k]; val hi = c[k] + h[k]
            if (abs(d) < 1e-6f) { if (a[k] < lo || a[k] > hi) return false }
            else { var ta = (lo - a[k]) / d; var tb = (hi - a[k]) / d; if (ta > tb) { val t = ta; ta = tb; tb = t }; t0 = max(t0, ta); t1 = min(t1, tb); if (t0 > t1) return false }
        }
        return true
    }

    private fun step(dt: Float) {
        if (st != St.PLAY) return
        synchronized(this) {
            for (i in 0..1) {                                                            // velocidade da ponta
                if (active[i] && hasPrev[i]) for (k in 0..2) vel[i][k] = (tip[i][k] - prevTip[i][k]) / dt
                else { vel[i][0] = 0f; vel[i][1] = 0f; vel[i][2] = 0f }
                if (active[i]) { System.arraycopy(tip[i], 0, prevTip[i], 0, 3); hasPrev[i] = true }
            }
        }
        // relógio da música (preload de 1,1 s antes do áudio, como o BeatsPreloadTime do original)
        if (!audioStarted) {
            preload += dt; T = preload - ANTIC
            if (preload >= ANTIC && audioReady) { audioStarted = true; T = 0f; try { game?.start() } catch (_: Throwable) { } ; if (game == null) { audioDone = false } }
        } else {
            T += dt
            val g = game
            if (g != null && !audioDone) try { if (g.isPlaying) T += (g.currentPosition / 1000f - T) * 0.2f } catch (_: Throwable) { }
            if (g == null && noteI >= notes.size && T > (notes.lastOrNull()?.t ?: 0f) + 2f && !audioDone) { audioDone = true; doneAtMs = SystemClock.uptimeMillis() }
        }
        // surgimento: a nota nasce ANTIC segundos antes da sua batida
        while (noteI < notes.size && notes[noteI].t - ANTIC <= T) {
            val n = notes[noteI++]
            val late = T - (n.t - ANTIC)
            cubes.add(Cube(SPAWN - SPEED * late, lanes[n.lane], layers[n.layer], n.color, n.dir))
        }
        // movimento e corte
        val it = cubes.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.d -= SPEED * dt
            var cut = false
            // JANELA DE ACERTO MAIOR: o bloco pode ser cortado desde bem antes até bem depois de chegar ao jogador (antes: -0,5 m; agora -1,1 m)
            if (c.d < HAND_D + SABER_LEN + 1.0f && c.d > MISS_D) for (i in 0..1) {
                if (!active[i] || (i == 0 && !twoSabers)) continue
                if (twoSabers && i != c.color) continue                                   // cor errada: atravessa (camada/Layer do original)
                if (!segBox(hand[i], tip[i], c.x, c.d, c.z, CUBE / 2 + 0.20f, 0.75f, CUBE / 2 + 0.20f)) continue          // caixa de acerto generosa (assistência de mira)
                val vx = vel[i][0]; val vz = vel[i][2]; val sp = hypot(vx, vz)
                if (sp < 0.25f) continue                                                  // só a lâmina PARADA (tremor do 3DoF) não corta: não precisa de movimento rápido
                var dirQ = 1f
                if (c.dir != 8) { val sw = swipe(c.dir); val al = (vx * sw[0] + vz * sw[1]) / sp; if (al <= 0.30f) continue; dirQ = ((al - 0.30f) / 0.70f).coerceIn(0f, 1f) }   // dentro de ~72° da seta
                cut = true; cutCount++
                // ---- pontos: direção do golpe (50) + velocidade (35) + quão no centro do bloco (30) = até 115, × multiplicador de combo
                val hy = hand[i][1]; val ty = tip[i][1]
                val tt = if (abs(ty - hy) < 1e-4f) 1f else ((c.d - hy) / (ty - hy)).coerceIn(0f, 1f)
                val ex = hand[i][0] + (tip[i][0] - hand[i][0]) * tt - c.x; val ez = hand[i][2] + (tip[i][2] - hand[i][2]) * tt - c.z
                val centerQ = (1f - hypot(ex, ez) / 0.40f).coerceIn(0f, 1f)
                val speedQ = ((sp - 0.25f) / 3.0f).coerceIn(0f, 1f)
                val pts = max(10, (50 * dirQ + 35 * speedQ + 30 * centerQ).toInt())
                registerHit(pts)
                val nx = -vz / sp; val nz = vx / sp
                val ang = Math.toDegrees(atan2(vz.toDouble(), vx.toDouble())).toFloat()
                flashes.add(Flash(c.x, c.d, c.z, 0.35f, c.color))
                pieces.add(Piece(c.x, c.d, c.z, vx * 0.15f + nx * 0.9f, vz * 0.15f + nz * 0.9f, 0f, 60f, c.color, 2f, 0, ang))
                pieces.add(Piece(c.x, c.d, c.z, vx * 0.15f - nx * 0.9f, vz * 0.15f - nz * 0.9f, 0f, -60f, c.color, 2f, 1, ang))
                break
            }
            if (cut) { it.remove(); continue }
            if (c.d <= MISS_D) {                                                          // passou do jogador sem ser cortado: ERROU
                it.remove()
                if (active[0] || active[1]) registerMiss(c.x, c.z, c.color)
            }
        }
        // paredes: encostou nelas (dentro do vão em x e, nas altas, sem ter abaixado) = perde 1 vida (no máx. a cada 0,7 s)
        if (audioStarted && walls.isNotEmpty() && lives > 0) {
            val nowMs = SystemClock.uptimeMillis()
            for (w in walls) {
                val front = (w.t - T) * SPEED; val back = front + w.dur * SPEED
                if (front > 0.30f) break                                                  // ordenadas por tempo: as próximas ainda estão longe
                if (back < -0.30f) continue
                val x0 = lanes[w.lane] - 0.22f; val x1 = lanes[(w.lane + w.width - 1).coerceAtMost(3)] + 0.22f
                val inX = bodyX > x0 - 0.20f && bodyX < x1 + 0.20f
                val hit = inX && (w.type == 0 || bodyZ > -0.28f)
                if (hit && nowMs - lastWallMs > 700L) {
                    lastWallMs = nowMs; wallHits++
                    combo = 0; lives = max(0, lives - 1)
                    popups.add(Popup(0f, 1.3f, 0f, 0.9f, if (w.type == 0) "PAREDE" else "ABAIXE", Color.rgb(255, 150, 40)))
                    if (lives <= 0) failed = true
                }
            }
        }
        val pi = pieces.iterator()
        val fi = flashes.iterator(); while (fi.hasNext()) { val q = fi.next(); q.life -= dt; if (q.life <= 0f) fi.remove() }
        while (pi.hasNext()) { val q = pi.next(); q.life -= dt; q.vz -= 9.8f * dt; q.x += q.vx * dt; q.z += q.vz * dt; q.rot += q.vr * dt; if (q.life <= 0f) pi.remove() }
        for (q in popups) { q.life -= dt }
        popups.removeAll { q -> q.life <= 0f }
        // fim: perdeu as 10 vidas (tela de derrota) ou a música acabou e não há mais blocos (tela de resultado)
        if (!resultShown) {
            if (failed) { post { showResult(true) } ; resultShown = true }
            else if (audioDone && noteI >= notes.size && cubes.isEmpty() && SystemClock.uptimeMillis() - doneAtMs > 1500) { resultShown = true; post { showResult(false) } }
            else if (audioDone && SystemClock.uptimeMillis() - doneAtMs > 9000) { resultShown = true; post { showResult(false) } }
        }
    }

    private val MISS_D = -1.1f
    private fun registerHit(pts: Int) {
        val mult = multAt(combo)
        score += pts * mult; combo++; if (combo > maxCombo) maxCombo = combo
        hits++; processed++; maxSoFar += MAX_CUT * multAt(processed - 1)
        lives = min(MAX_LIVES, lives + 1)                                              // acertar recupera 1 vida (limite 10)
    }
    private fun registerMiss(x: Float, z: Float, color: Int) {
        combo = 0; misses++; processed++; maxSoFar += MAX_CUT * multAt(processed - 1)
        lives = max(0, lives - 1)                                                      // cada bloco que passa tira 1 vida
        popups.add(Popup(x, 1.6f, z, 0.9f, "ERROU", Color.rgb(255, 70, 80)))
        if (lives <= 0) failed = true
    }

    // ------------------------------------------------------------------ ponteiro e toque (estéreo)
    private fun clickableAt(v: View, x: Float, y: Float): View? {
        if (v.visibility == View.GONE || x < 0f || y < 0f || x > v.width || y > v.height) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
            val ch = v.getChildAt(i); if (ch.visibility == View.GONE) continue
            clickableAt(ch, x - ch.left, y - ch.top)?.let { return it }
        }
        return if (v.isClickable) v else null
    }

    /** px,py = deslocamento (em pixels) a partir do centro do olho; py positivo = para cima. */
    private fun findHit(px: Float, py: Float): View? {
        val w = width.toFloat(); val vc = height / 2f - py
        for (chip in listOf(btnMenu, btnView, btnSabers)) if (chip.visibility != View.GONE) clickableAt(chip, w * 0.75f + px - chip.left, vc - chip.top)?.let { return it }
        if (menuShown) clickableAt(menu, w / 2f + px - menu.left, vc - menu.top)?.let { return it }
        return null
    }

    private fun updatePointer(dt: Float) {
        cursorOn = false
        if (!stereo || width == 0) { dwellView = null; return }
        val i = if (active[1]) 1 else if (active[0]) 0 else -1
        if (i < 0) { dwellView = null; return }
        val dx = tip[i][0] - hand[i][0]; val dy = tip[i][1] - hand[i][1]; val dz = tip[i][2] - hand[i][2]
        if (dy < 0.2f) { dwellView = null; return }
        val t = (UI_D - hand[i][1]) / dy
        val f = height * 1.05f
        cursorPx = (hand[i][0] + dx * t) * f / UI_D; cursorPy = (hand[i][2] + dz * t) * f / UI_D
        cursorOn = true
        val rising = trig[i] && !trigPrev[i]; trigPrev[i] = trig[i]
        cooldown -= dt
        val hit = findHit(cursorPx, cursorPy)
        if (st == St.PLAY && hit == null) cursorOn = false              // no jogo só mostra o cursor sobre o botão Menu
        if (hit == null) { dwellView = null; dwellT = 0f; return }
        if (hit === dwellView) dwellT += dt else { dwellView = hit; dwellT = 0f }
        val dwell = st != St.PLAY && dwellT > 1.0f                         // segurar 1 s também clica (não no jogo, para não sair sem querer)
        if ((rising || dwell) && cooldown <= 0f) { cooldown = 0.8f; dwellT = 0f; dwellView = null; post { hit.performClick() } }
    }

    private val aim = FingerAim(32f, 26f)
    /** O sabre [i] está sendo mantido pelos dedos (fica onde parou ao tirar o dedo, para não sumir). */
    fun fingerHolds(i: Int) = fingerSabers && handleTouch && i in 0..1 && aim.used[i] && st == St.PLAY

    /** Dedos -> sabres: segurar um instante e arrastar move o sabre do seu lado (relativo: tirar o dedo NÃO reinicia); tocar = gatilho. */
    private fun fingerTouch(e: MotionEvent) {
        aim.onTouch(e, width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f), SystemClock.uptimeMillis())
        applyAim()
        val now = SystemClock.uptimeMillis()
        for (i in 0..1) trig[i] = aim.click(i, now)
    }

    private fun applyAim() {
        for (i in 0..1) {
            if (!aim.used[i]) continue
            val yr = Math.toRadians(aim.yaw[i].toDouble()); val pr = Math.toRadians(aim.pitch[i].toDouble())
            setSaber(i, (sin(yr) * cos(pr)).toFloat(), (cos(yr) * cos(pr)).toFloat(), sin(pr).toFloat(), true)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (st == St.PLAY && fingerSabers && handleTouch) {
            if (stereo && e.actionMasked == MotionEvent.ACTION_DOWN) {                       // o botão Menu (canto) continua clicável
                val w = width.toFloat(); val f = height * 1.05f; val disp = ipd / 2 * f / UI_D
                val left = e.x < w / 2
                val px0 = if (left) e.x - w / 4 - disp else e.x - 3 * w / 4 + disp
                if (btnMenu.visibility != View.GONE && clickableAt(btnMenu, w * 0.75f + px0 - btnMenu.left, height / 2f - (height / 2f - e.y) - btnMenu.top) != null) { post { endGame(); showSongs() }; return true }
            }
            fingerTouch(e); return true
        }
        if (!stereo) return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val w = width.toFloat(); val f = height * 1.05f; val disp = ipd / 2 * f / UI_D
            val left = e.x < w / 2
            val px = if (left) e.x - w / 4 - disp else e.x - 3 * w / 4 + disp
            findHit(px, height / 2f - e.y)?.let { v -> post { v.performClick() } }
        }
        return true
    }

    private fun drawOverlay(c: Canvas, ox: Float, eye: Float) {
        val w = width.toFloat(); val f = height * 1.05f
        val disp = -eye * f / UI_D
        fun item(v: View, center: Boolean) {
            if (v.visibility == View.GONE || v.width == 0) return
            c.save(); c.translate(ox + v.left - (if (center) w / 4 else w / 2) + disp, v.top.toFloat()); v.draw(c); c.restore()
        }
        item(menu, true); item(hud, true); item(btnMenu, false)
        if (cursorOn) {
            val x = ox + w / 4 + cursorPx + disp; val y = height / 2f - cursorPy; val r = unit * 0.014f
            p.style = Paint.Style.FILL; p.color = Color.argb(230, 255, 255, 255); c.drawCircle(x, y, r, p)
            if (dwellView != null && st != St.PLAY) { p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.5f; p.color = PURPLE; rect.set(x - r * 2, y - r * 2, x + r * 2, y + r * 2); c.drawArc(rect, -90f, 360f * (dwellT / 1.0f).coerceIn(0f, 1f), false, p); p.style = Paint.Style.FILL }
        }
    }

    // ------------------------------------------------------------------ desenho
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now
        step(dt)
        scroll += dt * (if (st == St.PLAY) SPEED else 1.5f)
        updatePointer(dt)
        val w = width.toFloat(); val h = height.toFloat()
        if (stereo) {
            c.save(); c.clipRect(0f, 0f, w / 2, h); drawScene(c, 0f, w / 2, h, -ipd / 2); drawOverlay(c, 0f, -ipd / 2); c.restore()
            c.save(); c.clipRect(w / 2, 0f, w, h); drawScene(c, w / 2, w / 2, h, ipd / 2); drawOverlay(c, w / 2, ipd / 2); c.restore()
        } else drawScene(c, 0f, w, h, 0f)
        val ht = when {
            st != St.PLAY -> status
            else -> (if (!active[0] && !active[1] && !(fingerSabers && handleTouch)) "Ligue os controles (ESP32/celular) para jogar" else if (!active[0] && !active[1]) "Arraste os dedos na tela para mover os sabres" else "")
        }
        if (ht != lastHud) { lastHud = ht; hud.text = ht }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ visual 3D com bloom
    private fun makeHalo(col: Int): Bitmap {
        val n = 128; val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Color.red(col); val g = Color.green(col); val bl = Color.blue(col)
        pp.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(Color.argb(255, r, g, bl), Color.argb(90, r, g, bl), Color.argb(0, r, g, bl)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(n / 2f, n / 2f, n / 2f, pp); return b
    }

    private fun makeBg(): Bitmap {
        val bw = 256; val bh = 144; val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val pp = Paint()
        pp.shader = LinearGradient(0f, 0f, bw.toFloat(), 0f, intArrayOf(Color.rgb(120, 22, 46), Color.rgb(84, 40, 124), Color.rgb(8, 72, 156)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), pp)
        pp.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(175, 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), pp)
        pp.shader = RadialGradient(bw / 2f, bh * 0.46f, bh * 0.62f, Color.argb(160, 255, 205, 255), Color.argb(0, 255, 205, 255), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), pp)
        return b
    }

    private fun shade(col: Int, k: Float): Int = Color.rgb((Color.red(col) * k).toInt().coerceIn(0, 255), (Color.green(col) * k).toInt().coerceIn(0, 255), (Color.blue(col) * k).toInt().coerceIn(0, 255))
    private fun lighten(col: Int, t: Float): Int = Color.rgb((Color.red(col) + (255 - Color.red(col)) * t).toInt(), (Color.green(col) + (255 - Color.green(col)) * t).toInt(), (Color.blue(col) + (255 - Color.blue(col)) * t).toInt())
    private fun px(x: Float, d: Float) = gCx + gF * (x - gEye) / d
    private fun py(z: Float, d: Float) = gCy - gF * z / d
    private fun haloOf(color: Int) = if (color == 0) haloRed else haloBlue

    private fun halo(c: Canvas, bmp: Bitmap, x: Float, y: Float, r: Float, alpha: Int) {
        glowP.color = Color.argb(alpha, 255, 255, 255); dst.set(x - r, y - r, x + r, y + r); c.drawBitmap(bmp, null, dst, glowP)
    }

    private fun poly4(c: Canvas, a: Int, b: Int, cc: Int, d: Int, paint: Paint) {
        path.reset(); path.moveTo(qx[a], qy[a]); path.lineTo(qx[b], qy[b]); path.lineTo(qx[cc], qy[cc]); path.lineTo(qx[d], qy[d]); path.close(); c.drawPath(path, paint)
    }

    private fun strip(c: Canvas, ax: Float, ay: Float, aw: Float, bx: Float, by: Float, bw: Float, color: Int, paint: Paint) {
        val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy).coerceAtLeast(1e-3f); val nx = -dy / len; val ny = dx / len
        path.reset(); path.moveTo(ax + nx * aw, ay + ny * aw); path.lineTo(bx + nx * bw, by + ny * bw); path.lineTo(bx - nx * bw, by - ny * bw); path.lineTo(ax - nx * aw, ay - ny * aw); path.close()
        paint.color = color; c.drawPath(path, paint)
    }

    // feixes de luz paralelos ao eixo de profundidade (as "listras" que convergem para o centro)
    private val rails = floatArrayOf(-1.9f, -0.9f, -2.8f, 0.6f, -3.6f, -0.2f, -4.8f, 1.4f, -2.3f, 1.8f, -1.3f, -1.5f, 1.9f, -0.9f, 2.8f, 0.6f, 3.6f, -0.2f, 4.8f, 1.4f, 2.3f, 1.8f, 1.3f, -1.5f)

    private fun drawRails(c: Canvas) {
        val cycle = 24f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; glowP.style = Paint.Style.STROKE; glowP.strokeCap = Paint.Cap.ROUND
        for (r in 0 until rails.size / 2) {
            val x = rails[r * 2]; val z = rails[r * 2 + 1]
            val col = if (x < 0) colors[0] else colors[1]
            for (k in 0..2) {
                var d0 = (((r * 3.7f + k * 8f - scroll) % cycle) + cycle) % cycle + 0.5f
                var d1 = d0 + 3.4f
                if (d0 < 0.5f) d0 = 0.5f
                val a = (0.25f + 0.75f * (1f - d0 / cycle)).coerceIn(0f, 1f)
                toCam(x, d0, z); val cx0 = rv[0]; val cd0 = rv[1]; val cz0 = rv[2]
                toCam(x, d1, z); val cx1 = rv[0]; val cd1 = rv[1]; val cz1 = rv[2]
                if (cd0 < 0.2f && cd1 < 0.2f) continue
                val dd0 = max(cd0, 0.2f); val dd1 = max(cd1, 0.2f)
                val x0 = px(cx0, dd0); val y0 = py(cz0, dd0); val x1 = px(cx1, dd1); val y1 = py(cz1, dd1)
                val wd = (gF * 0.07f / ((dd0 + dd1) / 2f)).coerceIn(1.5f, 40f)
                glowP.strokeWidth = wd * 3.2f; glowP.color = Color.argb((60 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawLine(x0, y0, x1, y1, glowP)
                glowP.strokeWidth = wd * 1.6f; glowP.color = Color.argb((120 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawLine(x0, y0, x1, y1, glowP)
                glowP.strokeWidth = wd * 0.6f; val lc = lighten(col, 0.7f); glowP.color = Color.argb((230 * a).toInt(), Color.red(lc), Color.green(lc), Color.blue(lc)); c.drawLine(x0, y0, x1, y1, glowP)
            }
        }
        glowP.style = Paint.Style.FILL; p.style = Paint.Style.FILL
    }

    private fun drawCube(c: Canvas, b0: Cube) {
        toCam(b0.x, b0.d, b0.z)
        val b = Cube(rv[1], rv[0], rv[2], b0.color, b0.dir)                                // centro no referencial da câmera
        val hh = CUBE / 2
        val df = b.d - hh; val db = b.d + hh
        if (df < 0.12f) return
        val col = colors[b.color]
        val phi = if (b.dir == 8) 0f else { val sw = swipe(b.dir); atan2(sw[0], -sw[1]) }
        val cs = cos(phi); val sn = sin(phi)
        val lx = floatArrayOf(-hh, hh, hh, -hh); val lz = floatArrayOf(-hh, -hh, hh, hh)
        for (k in 0..3) {
            val wx = b.x + lx[k] * cs - lz[k] * sn; val wz = b.z + lx[k] * sn + lz[k] * cs
            qx[k] = px(wx, df); qy[k] = py(wz, df); qx[k + 4] = px(wx, db); qy[k + 4] = py(wz, db)
        }
        val s = gF * CUBE / b.d
        val ccx = px(b.x, b.d); val ccy = py(b.z, b.d)
        halo(c, haloOf(b.color), ccx, ccy, s * 2.0f, 150)                                  // bloom atrás do bloco
        // laterais visíveis (iluminação simples vinda de cima-esquerda)
        val nxl = floatArrayOf(0f, 1f, 0f, -1f); val nzl = floatArrayOf(-1f, 0f, 1f, 0f)
        p.style = Paint.Style.FILL
        for (e in 0..3) {
            val i = e; val j = (e + 1) % 4
            val nx = nxl[e] * cs - nzl[e] * sn; val nz = nxl[e] * sn + nzl[e] * cs
            val fx = b.x + (lx[i] + lx[j]) / 2 * cs - (lz[i] + lz[j]) / 2 * sn; val fz = b.z + (lx[i] + lx[j]) / 2 * sn + (lz[i] + lz[j]) / 2 * cs
            if (nx * (gEye - fx) + nz * (0f - fz) <= 0f) continue
            val lit = (-0.45f * nx + 0.85f * nz).coerceIn(-1f, 1f)
            p.color = shade(lighten(col, 0.12f), (0.62f + 0.38f * lit).coerceIn(0.25f, 1f))
            poly4(c, i, j, j + 4, i + 4, p)
        }
        // face da frente: escura, com aro e a barra brilhante do lado do golpe (como o cubo do original)
        p.color = shade(col, 0.50f); poly4(c, 0, 1, 2, 3, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, s * 0.07f); p.color = lighten(col, 0.35f); poly4(c, 0, 1, 2, 3, p)
        p.style = Paint.Style.FILL
        val bxs: FloatArray; val bzs: FloatArray
        if (b.dir == 8) { bxs = floatArrayOf(-0.28f * hh, 0.28f * hh, 0.28f * hh, -0.28f * hh); bzs = floatArrayOf(-0.28f * hh, -0.28f * hh, 0.28f * hh, 0.28f * hh) }
        else { bxs = floatArrayOf(-0.70f * hh, 0.70f * hh, 0.70f * hh, -0.70f * hh); bzs = floatArrayOf(-0.82f * hh, -0.82f * hh, -0.42f * hh, -0.42f * hh) }
        path.reset()
        for (k in 0..3) {
            val wx = b.x + bxs[k] * cs - bzs[k] * sn; val wz = b.z + bxs[k] * sn + bzs[k] * cs
            if (k == 0) path.moveTo(px(wx, df), py(wz, df)) else path.lineTo(px(wx, df), py(wz, df))
        }
        path.close()
        p.color = lighten(col, 0.88f); c.drawPath(path, p)
        glowP.style = Paint.Style.FILL; glowP.color = Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)); c.drawPath(path, glowP)
    }

    private fun drawSaber(c: Canvas, i: Int) {
        val col = colors[i]
        toCam(hand[i][0], hand[i][1], hand[i][2]); val hx = rv[0]; val hy = rv[1]; val hz = rv[2]
        toCam(tip[i][0], tip[i][1], tip[i][2]); val tx = rv[0]; val ty = rv[1]; val tz = rv[2]
        val dx = tx - hx; val dy = ty - hy; val dz = tz - hz
        val ht = 0.16f
        val d0 = max(hy, 0.12f); val d1 = max(hy + dy * ht, 0.12f); val d2 = max(ty, 0.12f)         // mais perto que antes (0,3): o sabre COMPRIDO aparece inteiro
        val x0 = px(hx, d0); val y0 = py(hz, d0)
        val x1 = px(hx + dx * ht, d1); val y1 = py(hz + dz * ht, d1)
        val x2 = px(tx, d2); val y2 = py(tz, d2)
        val w0 = gF * 0.030f / d0; val w1 = gF * 0.027f / d1; val w2 = gF * 0.027f / d2
        p.style = Paint.Style.FILL
        strip(c, x0, y0, w0, x1, y1, w1, Color.rgb(46, 46, 58), p)                          // cabo
        strip(c, x0, y0, w0 * 0.55f, x1, y1, w1 * 0.55f, Color.rgb(120, 120, 140), p)
        glowP.style = Paint.Style.FILL
        halo(c, haloOf(i), (x1 + x2) / 2, (y1 + y2) / 2, hypot(x2 - x1, y2 - y1) * 0.75f, 70)    // aura ampla
        strip(c, x1, y1, w1 * 3.4f, x2, y2, w2 * 3.4f, Color.argb(55, Color.red(col), Color.green(col), Color.blue(col)), glowP)
        strip(c, x1, y1, w1 * 1.9f, x2, y2, w2 * 1.9f, Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)), glowP)
        strip(c, x1, y1, w1, x2, y2, w2, col, p)                                            // lâmina
        strip(c, x1, y1, w1 * 0.45f, x2, y2, w2 * 0.45f, Color.argb(255, 255, 255, 255), glowP)   // núcleo branco
        halo(c, haloWhite, x2, y2, w2 * 5f, 120)
    }

    private val wallPath = Path()
    /** Parede translúcida vermelha: de chão ao teto (desviar para o lado) ou só no alto (abaixar). */
    private fun drawWall(c: Canvas, w: SongLibrary.Wall) {
        val front = (w.t - T) * SPEED; val back = front + w.dur * SPEED
        if (back < 0.25f || front > SPAWN) return
        val df = max(front, 0.25f); val db = min(back, SPAWN)
        val x0 = lanes[w.lane] - 0.22f; val x1 = lanes[(w.lane + w.width - 1).coerceAtMost(3)] + 0.22f
        val z0 = if (w.type == 0) -1.6f else -0.12f; val z1 = if (w.type == 0) 1.0f else 1.0f
        val cx = floatArrayOf(x0, x1, x1, x0); val cz = floatArrayOf(z0, z0, z1, z1)
        p.style = Paint.Style.FILL
        for (k in 0..3) {                                                                     // laterais/teto/chão do túnel da parede
            val j = (k + 1) % 4
            wallPath.reset()
            wallPath.moveTo(px(cx[k], df), py(cz[k], df)); wallPath.lineTo(px(cx[j], df), py(cz[j], df))
            wallPath.lineTo(px(cx[j], db), py(cz[j], db)); wallPath.lineTo(px(cx[k], db), py(cz[k], db)); wallPath.close()
            p.color = Color.argb(70, 255, 40, 70); c.drawPath(wallPath, p)
        }
        wallPath.reset()
        wallPath.moveTo(px(x0, df), py(z0, df)); wallPath.lineTo(px(x1, df), py(z0, df)); wallPath.lineTo(px(x1, df), py(z1, df)); wallPath.lineTo(px(x0, df), py(z1, df)); wallPath.close()
        p.color = Color.argb(110, 255, 40, 70); c.drawPath(wallPath, p)                       // face da frente
        p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, gF * 0.012f / df); p.color = Color.argb(230, 255, 110, 130); c.drawPath(wallPath, p)
        p.style = Paint.Style.FILL
    }

    private fun drawScene(c: Canvas, ox: Float, w: Float, h: Float, eye: Float) {
        gF = h * 1.05f; gCx = ox + w / 2; gCy = h * 0.46f; gEye = eye
        dst.set(ox, 0f, ox + w, h); c.drawBitmap(bg, null, dst, bmpP)
        drawRails(c)
        if (st != St.PLAY) { p.style = Paint.Style.FILL; return }
        for (wl in walls) drawWall(c, wl)
        for (b in cubes.sortedByDescending { it.d }) drawCube(c, b)
        // efeito de corte: clarão (bloom) + metades que caem (EzySlice + Rigidbody do original)
        for (q in flashes) {
            toCam(q.x, q.d, q.z); if (rv[1] < 0.15f) continue
            val a = (q.life / 0.35f).coerceIn(0f, 1f); val dd = max(rv[1], 0.3f); val fx = rv[0]; val fz = rv[2]
            halo(c, haloWhite, px(fx, dd), py(fz, dd), gF * CUBE / dd * (1.2f + (1f - a) * 3f), (a * 220).toInt())
            halo(c, haloOf(q.color), px(fx, dd), py(fz, dd), gF * CUBE / dd * (2f + (1f - a) * 4f), (a * 160).toInt())
        }
        for (q in pieces) {
            toCam(q.x, q.d, q.z); if (rv[1] < 0.15f) continue
            val dd = max(rv[1], 0.3f); val s = gF * CUBE / dd; val x = px(rv[0], dd); val y = py(rv[2], dd)
            val a = (q.life / 2f).coerceIn(0f, 1f)
            halo(c, haloOf(q.color), x, y, s * 1.6f, (a * 120).toInt())
            c.save(); c.translate(x, y); c.rotate(-q.ang + q.rot)
            if (q.side == 0) c.clipRect(-s, -s, s, 0f) else c.clipRect(-s, 0f, s, s)
            p.style = Paint.Style.FILL; p.color = Color.argb((a * 255).toInt(), Color.red(lighten(colors[q.color], 0.2f)), Color.green(lighten(colors[q.color], 0.2f)), Color.blue(lighten(colors[q.color], 0.2f)))
            rect.set(-s / 2, -s / 2, s / 2, s / 2); c.drawRoundRect(rect, s * 0.1f, s * 0.1f, p)
            c.restore()
        }
        for (i in 0..1) { if (!active[i] || (i == 0 && !twoSabers)) continue; drawSaber(c, i) }    // sabres só durante o jogo (no menu ficam escondidos, como no original)
        drawPopups(c)
        drawHud(c)
        p.style = Paint.Style.FILL
    }

    /** "ERROU" sobe e some no lugar do bloco que passou (como o MISS do Beat Saber). */
    private fun drawPopups(c: Canvas) {
        if (popups.isEmpty()) return
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; face?.let { p.typeface = it }
        for (q in popups) {
            val a = (q.life / 0.9f).coerceIn(0f, 1f)
            val rise = (1f - a) * 0.25f
            val d = max(q.d, 0.4f)
            val x = px(q.x, d); val y = py(q.z + rise, d)
            p.textSize = gF * 0.085f / d
            p.color = Color.argb((a * 120).toInt(), 0, 0, 0); c.drawText(q.text, x + 3f, y + 3f, p)
            p.color = Color.argb((a * 255).toInt(), Color.red(q.color), Color.green(q.color), Color.blue(q.color)); c.drawText(q.text, x, y, p)
        }
        p.textAlign = Paint.Align.LEFT
    }

    /** Barra das 10 vidas (em cima), pontos, combo e precisão (embaixo) — desenhados a UI_D metros, iguais nos dois olhos. */
    private fun drawHud(c: Canvas) {
        val d = UI_D
        face?.let { p.typeface = it }
        // vidas
        val pip = 0.058f; val gap = 0.016f; val total = MAX_LIVES * pip + (MAX_LIVES - 1) * gap
        p.style = Paint.Style.FILL
        for (k in 0 until MAX_LIVES) {
            val x0 = -total / 2 + k * (pip + gap); val z0 = 0.66f
            rect.set(px(x0, d), py(z0, d), px(x0 + pip, d), py(z0 - pip * 0.55f, d))
            val on = k < lives
            val low = lives <= 3
            p.color = if (on) (if (low) Color.rgb(255, 80, 90) else Color.rgb(176, 96, 255)) else Color.argb(90, 120, 100, 150)
            c.drawRoundRect(rect, rect.height() * 0.45f, rect.height() * 0.45f, p)
        }
        // pontos / combo / precisão
        p.textAlign = Paint.Align.CENTER
        val cx = px(0f, d)
        p.textSize = gF * 0.062f / d; p.color = Color.WHITE
        c.drawText("%,d".format(score).replace(',', '.'), cx, py(-0.50f, d), p)
        p.textSize = gF * 0.040f / d; p.color = Color.argb(235, 255, 214, 90)
        c.drawText("%.1f%%   ·   x%d   ·   combo %d".format(accuracy(), multAt(combo), combo), cx, py(-0.60f, d), p)
        p.textAlign = Paint.Align.LEFT
    }
}
