package com.stormvr.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Controle na tela (multitoque): analogico, Trigger, R2, Grip, A, B, X, Y, Start e L3.
 *  - MODO CONTROLE: cada botao pode ser ATIVADO/DESATIVADO; o analogico tambem (e pode ser FIXO ou FLUTUANTE).
 *  - Analogico flutuante: o dedo comecando em QUALQUER lugar vazio da tela (fora de outro botao) vira o analogico, com o centro onde o
 *    dedo encostou. Em qualquer modo, quando o dedo comeca a arrastar o analogico, ele e "capturado": continua valendo mesmo que o dedo saia
 *    da area, ate o dedo sair da tela.
 *  - MODO EDICAO: arraste qualquer item para mudar a posicao; toque para selecionar (a barra de baixo ativa/desativa, muda tamanho, etc.).
 * layout 0 = analogico a esquerda / botoes a direita; layout 1 = espelhado (canhoto). Toque no canto superior direito (≡) volta ao menu.
 */
class PadView(context: Context, private val prefs: SharedPreferences) : View(context) {
    class Item(val id: String, val label: String, val bit: Int, val dcx: Float, val dcy: Float, val dw: Float, val dh: Float, val round: Boolean) {
        var enabled = true; var cx = dcx; var cy = dcy; var scale = 1f
    }

    @Volatile var touchBits = 0
        private set
    @Volatile var stickX = 0f
        private set
    @Volatile var stickY = 0f
        private set
    var layout = 0
        set(v) { field = v; invalidate() }
    var info = ""
    /** Modo "6DoF por bolinhas": mostra bolinhas coloridas nas bordas da tela para o celular do headset enxergar e medir a posição. */
    var dots = false
        set(v) { field = v; invalidate() }
    var onMenu: (() -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var onSelect: ((String?) -> Unit)? = null

    var editMode = false
        set(v) { field = v; if (!v) { selectedId = null; onSelect?.invoke(null) }; releaseAll(); invalidate() }
    var selectedId: String? = null
        private set

    // posicoes normalizadas (x: fracao da largura, y: fracao da altura; w/h: fracao da largura)
    val items: List<Item> = listOf(
        Item("trigger", "Trigger", ControllerUdp.BTN_TRIGGER, 0.83f, 0.89f, 0.30f, 0.20f, false),
        Item("r2", "R2", ControllerUdp.BTN_R2, 0.50f, 0.89f, 0.28f, 0.20f, false),
        Item("grip", "Grip", ControllerUdp.BTN_GRIP, 0.17f, 0.89f, 0.28f, 0.20f, false),
        Item("a", "A", ControllerUdp.BTN_A, 0.62f, 0.62f, 0.19f, 0.19f, true),
        Item("b", "B", ControllerUdp.BTN_B, 0.86f, 0.62f, 0.19f, 0.19f, true),
        Item("x", "X", ControllerUdp.BTN_X, 0.62f, 0.47f, 0.15f, 0.15f, true),
        Item("y", "Y", ControllerUdp.BTN_Y, 0.86f, 0.47f, 0.15f, 0.15f, true),
        Item("start", "Start", ControllerUdp.BTN_START, 0.74f, 0.36f, 0.30f, 0.07f, false),
        Item("l3", "L3", ControllerUdp.BTN_L3, 0.27f, 0.42f, 0.14f, 0.14f, true)
    )
    // analogico (pseudo-item "stick")
    var stickEnabled = true
    var stickFloating = false
    private var stickCx = 0.27f; private var stickCy = 0.64f; private var stickScale = 1f
    private val stickR = 0.21f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stickPtr = -1
    private var stickOx = 0f; private var stickOy = 0f      // origem do analogico (centro fixo ou onde o dedo encostou)
    private var bits = 0
    private var dragPtr = -1; private var dragDx = 0f; private var dragDy = 0f

    init { load() }

    private fun fx(x: Float): Float = if (layout == 1) 1f - x else x

    fun item(id: String): Item? = items.firstOrNull { it.id == id }
    /** Mascara dos botoes ATIVOS (gamepad e volume so valem para botoes ativos). */
    fun enabledMask(): Int { var m = 0; for (i in items) if (i.enabled) m = m or i.bit; return m }

    private fun rectOf(b: Item): RectF {
        val cx = fx(b.cx) * width; val cy = b.cy * height
        val hw = b.dw * b.scale * width / 2f
        val hh = if (b.round) hw else b.dh * b.scale * height / 2f
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }
    private fun stickR() = stickR * stickScale * width
    private fun stickCenterX() = fx(stickCx) * width
    private fun stickCenterY() = stickCy * height

    // ---------------------------------------------------------------- persistencia
    fun load() {
        for (i in items) {
            val v = prefs.getString("pad_" + i.id, null)?.split("|") ?: continue
            if (v.size >= 4) { i.enabled = v[0] == "1"; i.cx = v[1].toFloatOrNull() ?: i.dcx; i.cy = v[2].toFloatOrNull() ?: i.dcy; i.scale = (v[3].toFloatOrNull() ?: 1f).coerceIn(0.5f, 2.2f) }
        }
        val s = prefs.getString("pad_stick", null)?.split("|")
        if (s != null && s.size >= 5) {
            stickEnabled = s[0] == "1"; stickCx = s[1].toFloatOrNull() ?: 0.27f; stickCy = s[2].toFloatOrNull() ?: 0.64f
            stickScale = (s[3].toFloatOrNull() ?: 1f).coerceIn(0.5f, 2.2f); stickFloating = s[4] == "1"
        }
    }

    fun save() {
        val e = prefs.edit()
        for (i in items) e.putString("pad_" + i.id, (if (i.enabled) "1" else "0") + "|" + i.cx + "|" + i.cy + "|" + i.scale)
        e.putString("pad_stick", (if (stickEnabled) "1" else "0") + "|" + stickCx + "|" + stickCy + "|" + stickScale + "|" + (if (stickFloating) "1" else "0"))
        e.apply()
    }

    fun resetLayout() {
        for (i in items) { i.enabled = true; i.cx = i.dcx; i.cy = i.dcy; i.scale = 1f }
        stickEnabled = true; stickFloating = false; stickCx = 0.27f; stickCy = 0.64f; stickScale = 1f
        save(); invalidate()
    }

    // ---------------------------------------------------------------- edicao (chamado pela barra de baixo)
    fun selectedEnabled(): Boolean = if (selectedId == "stick") stickEnabled else item(selectedId ?: "")?.enabled ?: false
    fun toggleSelected() {
        val id = selectedId ?: return
        if (id == "stick") stickEnabled = !stickEnabled else item(id)?.let { it.enabled = !it.enabled }
        save(); invalidate(); onChange?.invoke()
    }
    fun scaleSelected(f: Float) {
        val id = selectedId ?: return
        if (id == "stick") stickScale = (stickScale * f).coerceIn(0.5f, 2.2f) else item(id)?.let { it.scale = (it.scale * f).coerceIn(0.5f, 2.2f) }
        save(); invalidate()
    }
    fun toggleFloating() { stickFloating = !stickFloating; save(); invalidate() }

    // ---------------------------------------------------------------- desenho
    private fun accent(a: Int = 255) = Color.argb(a, 157, 78, 255)

    override fun onDraw(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(14, 8, 26), Color.rgb(30, 14, 56), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        if (dots) drawDots(c)
        p.textAlign = Paint.Align.CENTER
        p.textSize = width * 0.045f
        p.color = Color.argb(200, 255, 255, 255)
        c.drawText(if (editMode) "MODO EDIÇÃO — arraste os itens" else info, width / 2f, height * 0.07f, p)
        p.color = Color.argb(130, 210, 180, 255)
        p.textSize = width * 0.035f
        c.drawText(if (editMode) "toque num item para ativar/desativar ou mudar o tamanho" else "Aponte com a borda de cima do celular", width / 2f, height * 0.13f, p)
        p.color = Color.argb(200, 255, 255, 255)
        p.textSize = width * 0.09f
        c.drawText("≡", width * 0.92f, height * 0.06f, p)

        // analogico
        if (stickEnabled || editMode) {
            val sr = stickR()
            val active = stickPtr >= 0
            val scx = if (active) stickOx else stickCenterX()
            val scy = if (active) stickOy else stickCenterY()
            val dim = !stickEnabled
            p.style = Paint.Style.FILL
            p.color = if (dim) Color.argb(60, 90, 60, 130) else if (stickFloating && !active && !editMode) Color.argb(60, 60, 34, 110) else Color.rgb(44, 24, 78)
            c.drawCircle(scx, scy, sr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = if (dim) Color.argb(90, 157, 78, 255) else accent(if (stickFloating && !active) 120 else 255)
            c.drawCircle(scx, scy, sr, p)
            p.style = Paint.Style.FILL
            p.color = if (dim) Color.argb(80, 196, 148, 255) else Color.rgb(176, 110, 255)
            c.drawCircle(scx + stickX * sr * 0.7f, scy - stickY * sr * 0.7f, sr * 0.36f, p)
            if (editMode || (stickFloating && !active)) {
                p.color = Color.argb(150, 255, 255, 255); p.textSize = width * 0.032f
                c.drawText(if (stickFloating) "analógico (flutuante)" else "analógico", scx, scy + sr + width * 0.045f, p)
            }
            if (editMode && selectedId == "stick") outline(c, RectF(scx - sr, scy - sr, scx + sr, scy + sr), true)
        }
        // botoes
        for (b in items) {
            if (!b.enabled && !editMode) continue
            val r = rectOf(b)
            val on = !editMode && (bits and b.bit) != 0
            p.style = Paint.Style.FILL
            p.color = if (!b.enabled) Color.argb(60, 80, 60, 110) else if (on) Color.rgb(196, 148, 255) else Color.rgb(52, 30, 92)
            if (b.round) c.drawOval(r, p) else c.drawRoundRect(r, 26f, 26f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = accent(if (b.enabled) 230 else 80)
            if (b.round) c.drawOval(r, p) else c.drawRoundRect(r, 26f, 26f, p)
            p.style = Paint.Style.FILL
            p.color = if (on) Color.rgb(20, 8, 40) else if (b.enabled) Color.WHITE else Color.argb(110, 255, 255, 255)
            p.textSize = width * 0.05f
            c.drawText(b.label, r.centerX(), r.centerY() + p.textSize * 0.35f, p)
            if (editMode && selectedId == b.id) outline(c, r, b.round)
        }
    }

    /**
     * Bolinhas do 6DoF por câmera: 14 pontos BRANCOS espalhados pela tela em posições assimétricas (DotPattern). O celular do headset reconhece o padrão
     * mesmo que algumas bolinhas não estejam visíveis. Fundo escuro e brilho máximo (a Activity liga o brilho) deixam o contraste alto para a câmera.
     */
    private fun drawDots(c: Canvas) {
        val r = width * DotPattern.RADIUS
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (k in 0 until DotPattern.n) c.drawCircle(DotPattern.u[k] * width, DotPattern.v[k] * height, r, p)
    }

    private fun outline(c: Canvas, r: RectF, round: Boolean) {
        val q = RectF(r); q.inset(-10f, -10f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE; p.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        if (round) c.drawOval(q, p) else c.drawRoundRect(q, 30f, 30f, p)
        p.pathEffect = null; p.style = Paint.Style.FILL
    }

    // ---------------------------------------------------------------- toque
    private fun releaseAll() { stickPtr = -1; bits = 0; touchBits = 0; stickX = 0f; stickY = 0f; dragPtr = -1; onChange?.invoke() }

    private fun hitItem(x: Float, y: Float): String? {
        for (b in items.reversed()) if (rectOf(b).let { r -> RectF(r).apply { inset(-8f, -8f) }.contains(x, y) }) return b.id
        val sr = stickR(); if (hypot(x - stickCenterX(), y - stickCenterY()) <= sr * 1.1f) return "stick"
        return null
    }

    private fun onEdit(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > width * 0.80f && e.y < height * 0.10f) { onMenu?.invoke(); return true }
                val id = hitItem(e.x, e.y)
                selectedId = id; onSelect?.invoke(id)
                if (id != null) {
                    dragPtr = e.getPointerId(0)
                    val (cx, cy) = if (id == "stick") Pair(stickCenterX(), stickCenterY()) else item(id)!!.let { Pair(fx(it.cx) * width, it.cy * height) }
                    dragDx = cx - e.x; dragDy = cy - e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = selectedId
                val i = e.findPointerIndex(dragPtr)
                if (id != null && i >= 0 && width > 0 && height > 0) {
                    val nx = fx(((e.getX(i) + dragDx) / width).coerceIn(0.04f, 0.96f))
                    val ny = ((e.getY(i) + dragDy) / height).coerceIn(0.10f, 0.97f)
                    if (id == "stick") { stickCx = nx; stickCy = ny } else item(id)?.let { it.cx = nx; it.cy = ny }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragPtr = -1; save() }
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return onEdit(e)
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_DOWN && e.x > width * 0.80f && e.y < height * 0.10f) { onMenu?.invoke(); return true }

        // 1) captura do analogico: um dedo que COMECA no analogico (fixo) ou em area vazia (flutuante) fica preso a ele ate sair da tela
        if (stickEnabled && stickPtr < 0 && (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN)) {
            val i = e.actionIndex
            val x = e.getX(i); val y = e.getY(i)
            val onButton = items.any { it.enabled && rectOf(it).contains(x, y) }
            val inStick = hypot(x - stickCenterX(), y - stickCenterY()) <= stickR() * 1.25f
            val menuZone = x > width * 0.80f && y < height * 0.10f
            if (!onButton && !menuZone && (stickFloating || inStick)) {
                stickPtr = e.getPointerId(i)
                if (stickFloating) { stickOx = x; stickOy = y } else { stickOx = stickCenterX(); stickOy = stickCenterY() }
            }
        }
        if ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL)) stickPtr = -1
        else if (act == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex) == stickPtr) stickPtr = -1

        var nb = 0
        var sx = 0f; var sy = 0f
        if (act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
            val sr = stickR()
            for (i in 0 until e.pointerCount) {
                if (act == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i); val y = e.getY(i)
                if (e.getPointerId(i) == stickPtr) {                              // dedo preso ao analogico: vale mesmo fora da area
                    val dx = x - stickOx; val dy = y - stickOy
                    val len = hypot(dx, dy)
                    if (len > 1f) { val k = if (len > sr) sr / len else 1f; sx = (dx * k / sr).coerceIn(-1f, 1f); sy = (-dy * k / sr).coerceIn(-1f, 1f) }
                    continue
                }
                for (b in items) if (b.enabled && rectOf(b).contains(x, y)) nb = nb or b.bit
            }
        }
        bits = nb
        touchBits = nb
        stickX = sx; stickY = sy
        invalidate()
        onChange?.invoke()
        return true
    }
}
