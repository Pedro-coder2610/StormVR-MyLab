package com.stormvr.app.vrroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLUtils
import kotlin.math.abs

/** Textura 2D alimentada por um Bitmap (desenhado na UI thread, enviado na GL thread). */
class UiPanel(val w: Int, val h: Int) {
    private val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    var texId = 0
    private var dirty = true

    fun draw(block: (Canvas) -> Unit) {
        synchronized(this) {
            block(canvas)
            dirty = true
        }
    }

    fun initTexture() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        synchronized(this) { dirty = true }
    }

    fun uploadIfDirty() {
        synchronized(this) {
            if (!dirty) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            dirty = false
        }
    }
}

class Key(val type: Int, val lower: String, val upper: String = lower, val weight: Float = 1f, val vk: Int = 0) {
    val r = RectF()
}

/** Modelo + desenho da barra de ferramentas e do teclado virtual. */
class VrUi {
    companion object {
        const val KB_BAR_ZONE = 76f
        // tipos de tecla
        const val K_CHAR = 0
        const val K_BKSP = 1
        const val K_ENTER = 2
        const val K_SHIFT = 3
        const val K_SPACE = 4
        const val K_CLOSE = 5
        const val K_DOTCOM = 6
        const val K_FN = 7            // atalhos: Esc, Tab, F1-F12 (vk = tecla do Windows)

        // botões da barra de ferramentas
        const val B_BACK = 0
        const val B_FWD = 1
        const val B_RELOAD = 2
        const val B_HOME = 3
        const val B_URL = 4
        const val B_ZM = 5
        const val B_ZP = 6
        const val B_KB = 7
        const val B_CURVE = 8
        const val B_DOF = 9
        const val B_HANDS = 10
        const val B_HLASER = 11
        const val B_TWO = 12
        const val B_LITE = 13
        const val B_CAM = 14
        const val B_CENTER = 15
        const val B_EXIT = 16
        const val B_TVHOME = 17    // volta para a Home da telona (antes era um botão solto no canto da tela)
        const val B_MENU = 18      // mostra/esconde todas as outras opções (e os cantos e textos de diagnóstico)
        const val B_HIDE = 19      // fecha / reabre a tela (o Home também reabre)
        const val B_COUNT = 20
    }

    val toolbar = UiPanel(VrState.TB_W, VrState.TB_H)
    val keyboard = UiPanel(VrState.KB_W, VrState.KB_H)
    val status = UiPanel(1400, 120)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- barra de ferramentas ----
    private val labels = arrayOf(
        "Voltar", "Avançar", "Recarregar", "Início", "",
        "Z−", "Z+", "Teclado", "Curva", "6DoF", "Mãos", "Ponteiro", "2 Mãos", "Leve", "Câmera", "Centrar", "Sair", "Home", "Menu", "Fechar tela"
    )

    @Volatile var screenHidden = false
    private fun labelOf(i: Int): String = if (i == B_MENU && menuOpen) "Fechar" else if (i == B_HIDE && screenHidden) "Abrir tela" else labels[i]

    /** Menu aberto: aparecem todas as opções, os cantos de redimensionar e o painel de diagnóstico. Fechado: só Voltar, Início, Home, Teclado e Menu. */
    @Volatile var menuOpen = false
        private set
    fun setMenu(open: Boolean) { menuOpen = open; layoutToolbar(); drawToolbar() }
    private val tbRects = Array(B_COUNT) { RectF() }
    val tbHov = intArrayOf(-1, -1)
    var tbFlash = -1
    var url = ""
    var editing = false
    var kbOn = false
    var curvedOn = true
    var dofOn = true
    var dofState = 0   // 0 off, 1 procurando, 2 rastreando
    var handsOn = true
    var handsState = 0 // 0 off, 1 carregando, 2 procurando, 3 rastreando
    var hlaserOn = true
    var aimEye = true
    var camOn = false
    var liteOn = false
    var twoOn = false

    // ---- teclado ----
    val rows = ArrayList<List<Key>>()
    val keys = ArrayList<Key>()
    private val rowOf = ArrayList<Int>()
    val kbHov = intArrayOf(-1, -1)
    var kbFocus = -1
    var kbFlash = -1
    var shift = false

    /** Barra de mover o teclado (embaixo dele), como a barra da tela. */
    private val kbBar = RectF(VrState.KB_W * 0.5f - 260f, VrState.KB_H - 58f, VrState.KB_W * 0.5f + 260f, VrState.KB_H - 18f)
    @Volatile var kbBarHot = false

    init {
        layoutToolbar()
        buildKeyboard()
        drawToolbar()
        drawKeyboard()
    }

    private fun layoutToolbar() {
        paint.textSize = 30f
        val pad = 12f
        val gap = 8f
        val top = 12f
        val bot = VrState.TB_H - 12f
        for (r in tbRects) r.setEmpty()
        if (!menuOpen) {
            // barra COMPACTA e centralizada: Voltar, Início, Home, Teclado, Menu
            val ids = intArrayOf(B_BACK, B_HOME, B_TVHOME, B_KB, B_MENU)
            val bw = 250f
            val total = ids.size * bw + (ids.size - 1) * gap
            var x = (VrState.TB_W - total) / 2f
            for (id in ids) { tbRects[id].set(x, top, x + bw, bot); x += bw + gap }
            return
        }
        fun bw(i: Int) = paint.measureText(labelOf(i)) + 36f
        var x = pad
        for (i in intArrayOf(B_BACK, B_FWD, B_RELOAD, B_HOME, B_TVHOME, B_KB)) {
            tbRects[i].set(x, top, x + bw(i), bot)
            x += bw(i) + gap
        }
        var xr = VrState.TB_W - pad
        for (i in intArrayOf(B_MENU, B_HIDE, B_EXIT, B_CENTER, B_CAM, B_LITE, B_TWO, B_HLASER, B_HANDS, B_DOF, B_CURVE, B_ZP, B_ZM)) {
            val w = bw(i)
            tbRects[i].set(xr - w, top, xr, bot)
            xr -= w + gap
        }
        tbRects[B_URL].set(x, top, xr, bot)
    }

    fun toolbarAt(u: Float, v: Float): Int {
        val px = u * VrState.TB_W
        val py = v * VrState.TB_H
        for (i in tbRects.indices) if (tbRects[i].contains(px, py)) return i
        return -1
    }

    /** Painel de diagnóstico (2 linhas) mostrado acima da janela. */
    fun drawStatus(l1: String, l2: String) {
        status.draw { c ->
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(235, 20, 20, 28)
            c.drawRoundRect(0f, 0f, 1400f, 120f, 26f, 26f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 34f
            paint.color = Color.WHITE
            c.drawText(l1, 24f, 46f, paint)
            paint.textSize = 30f
            paint.color = if (l2.contains("erro", true) || l2.contains("falha", true)) Color.rgb(255, 190, 70) else Color.rgb(214, 180, 255)
            c.save()
            c.clipRect(0f, 60f, 1400f, 120f)
            c.drawText(l2, 24f, 98f, paint)
            c.restore()
        }
    }

    private fun textY(r: RectF) = r.centerY() - (paint.ascent() + paint.descent()) / 2f

    fun drawToolbar() {
        toolbar.draw { c ->
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(28, 28, 36)
            if (menuOpen) c.drawRoundRect(0f, 0f, VrState.TB_W.toFloat(), VrState.TB_H.toFloat(), 30f, 30f, paint)
            else {
                var l = Float.MAX_VALUE; var rr = 0f
                for (r in tbRects) if (!r.isEmpty) { l = minOf(l, r.left); rr = maxOf(rr, r.right) }
                c.drawRoundRect(l - 14f, 0f, rr + 14f, VrState.TB_H.toFloat(), 30f, 30f, paint)
            }
            paint.textSize = 30f
            for (i in 0 until B_COUNT) {
                val r = tbRects[i]
                if (r.isEmpty) continue
                val hov = tbHov[0] == i || tbHov[1] == i
                paint.style = Paint.Style.FILL
                paint.color = when {
                    tbFlash == i -> Color.WHITE
                    hov -> Color.rgb(157, 78, 255)
                    i == B_URL -> if (editing) Color.rgb(36, 22, 64) else Color.rgb(14, 14, 20)
                    i == B_KB && kbOn -> Color.rgb(124, 58, 214)
                    i == B_MENU && menuOpen -> Color.rgb(124, 58, 214)
                    i == B_CURVE && curvedOn -> Color.rgb(124, 58, 214)
                    i == B_DOF && dofOn && dofState == 2 -> Color.rgb(124, 58, 214)
                    i == B_DOF && dofOn -> Color.rgb(200, 130, 40)
                    i == B_HANDS && handsOn && handsState == 3 -> Color.rgb(124, 58, 214)
                    i == B_HANDS && handsOn -> Color.rgb(200, 130, 40)
                    i == B_HLASER && hlaserOn -> Color.rgb(124, 58, 214)
                    i == B_CAM && camOn -> Color.rgb(124, 58, 214)
                    i == B_LITE && liteOn -> Color.rgb(124, 58, 214)
                    i == B_TWO && twoOn -> Color.rgb(124, 58, 214)
                    else -> Color.rgb(58, 58, 72)
                }
                c.drawRoundRect(r, 18f, 18f, paint)
                paint.color = if (tbFlash == i) Color.BLACK else Color.WHITE
                if (i == B_URL) {
                    val s = if (editing) "$url|" else url
                    val tw = paint.measureText(s)
                    val x = if (tw > r.width() - 32f) r.right - 16f - tw else r.left + 16f
                    c.save()
                    c.clipRect(r)
                    paint.textAlign = Paint.Align.LEFT
                    c.drawText(s, x, textY(r), paint)
                    c.restore()
                } else {
                    paint.textAlign = Paint.Align.CENTER
                    c.drawText(labelOf(i), r.centerX(), textY(r), paint)
                }
            }
        }
    }

    // ---------------- teclado ----------------
    private fun ch(l: String, u: String) = Key(K_CHAR, l, u)

    private fun buildKeyboard() {
        // linha de atalhos (pequena): Esc, F1-F12, Tab
        rows.add(listOf(Key(K_FN, "Esc", "Esc", 1.3f, 0x1B)) + (1..12).map { Key(K_FN, "F$it", "F$it", 1f, 0x70 + it - 1) } + Key(K_FN, "Tab", "Tab", 1.3f, 0x09))
        val digits = "1234567890"
        val syms = "!@#$%^&*()"
        rows.add(digits.indices.map { Key(K_CHAR, digits[it].toString(), syms[it].toString()) } +
            Key(K_BKSP, "", "", 2f))
        rows.add("qwertyuiop".map { ch(it.toString(), it.uppercaseChar().toString()) } + ch("-", "_"))
        rows.add("asdfghjkl".map { ch(it.toString(), it.uppercaseChar().toString()) } +
            ch("ç", "Ç") + Key(K_ENTER, "", "", 2f))
        rows.add(listOf(Key(K_SHIFT, "", "", 2f)) +
            "zxcvbnm".map { ch(it.toString(), it.uppercaseChar().toString()) } +
            listOf(ch(",", ";"), ch(".", ":"), ch("/", "?")))
        rows.add(listOf(Key(K_CLOSE, "", "", 2f), Key(K_FN, "Win/⌘", "Win/⌘", 1.8f, 0x5B), ch("@", "#"), Key(K_SPACE, " ", " ", 5f),
            Key(K_DOTCOM, ".com", ".com", 2f)))

        val pad = 16f
        val gap = 8f
        val rowH = (VrState.KB_H - KB_BAR_ZONE - 2 * pad - 5 * gap) / 6f
        for ((ri, row) in rows.withIndex()) {
            var sum = 0f
            for (k in row) sum += k.weight
            val avail = VrState.KB_W - 2 * pad - gap * (row.size - 1)
            var x = pad
            val y = pad + ri * (rowH + gap)
            for (k in row) {
                val w = avail * k.weight / sum
                k.r.set(x, y, x + w, y + rowH)
                x += w + gap
                keys.add(k)
                rowOf.add(ri)
            }
        }
    }

    fun inKbBar(u: Float, v: Float): Boolean {
        val px = u * VrState.KB_W; val py = v * VrState.KB_H
        return px >= kbBar.left - 30f && px <= kbBar.right + 30f && py >= kbBar.top - 14f && py <= VrState.KB_H.toFloat()
    }

    fun keyAt(u: Float, v: Float): Int {
        val px = u * VrState.KB_W
        val py = v * VrState.KB_H
        for (i in keys.indices) {
            val r = keys[i].r
            if (px >= r.left - 4 && px <= r.right + 4 && py >= r.top - 4 && py <= r.bottom + 4) return i
        }
        return -1
    }

    fun defaultFocus(): Int = rowOf.indexOf(3) + 4

    /** Navegação pelo D-pad: dx = -1/+1 (esq/dir), dy = -1/+1 (cima/baixo). */
    fun navigate(from: Int, dx: Int, dy: Int): Int {
        if (from < 0 || from >= keys.size) return defaultFocus()
        val row = rowOf[from]
        if (dx != 0) {
            val first = rowOf.indexOf(row)
            val last = rowOf.lastIndexOf(row)
            return (from + dx).coerceIn(first, last)
        }
        val nr = (row + dy + rows.size) % rows.size
        val cx = keys[from].r.centerX()
        var best = from
        var bd = Float.MAX_VALUE
        for (i in keys.indices) {
            if (rowOf[i] == nr) {
                val d = abs(keys[i].r.centerX() - cx)
                if (d < bd) { bd = d; best = i }
            }
        }
        return best
    }

    private fun keyLabel(k: Key): String = when (k.type) {
        K_CHAR -> if (shift) k.upper else k.lower
        K_BKSP -> "Apagar"
        K_ENTER -> "Enter"
        K_SHIFT -> "Shift"
        K_SPACE -> "Espaço"
        K_CLOSE -> "Fechar"
        K_DOTCOM -> ".com"
        K_FN -> k.lower
        else -> ""
    }

    fun drawKeyboard() {
        keyboard.draw { c ->
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(24, 24, 32)
            c.drawRoundRect(0f, 0f, VrState.KB_W.toFloat(), VrState.KB_H.toFloat(), 34f, 34f, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (kbBarHot) Color.rgb(157, 78, 255) else Color.rgb(96, 62, 150)
            c.drawRoundRect(kbBar, 20f, 20f, paint)
            paint.color = Color.argb(200, 255, 255, 255)
            for (g in -1..1) c.drawRoundRect(kbBar.centerX() - 60f, kbBar.centerY() - 4f + g * 11f, kbBar.centerX() + 60f, kbBar.centerY() + 1f + g * 11f, 3f, 3f, paint)
            for ((i, k) in keys.withIndex()) {
                val hov = kbHov[0] == i || kbHov[1] == i
                paint.style = Paint.Style.FILL
                paint.color = when {
                    kbFlash == i -> Color.WHITE
                    hov -> Color.rgb(157, 78, 255)
                    k.type == K_SHIFT && shift -> Color.rgb(124, 58, 214)
                    k.type == K_FN -> Color.rgb(48, 28, 86)
                    k.type == K_CHAR || k.type == K_SPACE -> Color.rgb(62, 62, 78)
                    else -> Color.rgb(44, 44, 58)
                }
                c.drawRoundRect(k.r, 16f, 16f, paint)
                if (i == kbFocus) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 6f
                    paint.color = Color.rgb(255, 205, 40)
                    c.drawRoundRect(k.r, 16f, 16f, paint)
                    paint.style = Paint.Style.FILL
                }
                val label = keyLabel(k)
                paint.color = if (kbFlash == i) Color.BLACK else Color.WHITE
                paint.textSize = if (k.type == K_FN) 26f else if (label.length > 1) 32f else 46f
                paint.textAlign = Paint.Align.CENTER
                c.drawText(label, k.r.centerX(), textY(k.r), paint)
            }
        }
    }
}
