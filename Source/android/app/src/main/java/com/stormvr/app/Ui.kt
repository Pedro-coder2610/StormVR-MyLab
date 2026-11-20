package com.stormvr.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Visual único do app (telas comuns do Android): roxo vivo, preto e branco; cartões grandes e arredondados, ícones em emoji, botões em degradê e ABAS.
 * Tudo que as telas já usavam (title/card/label/button/input/round) continua igual, só mais bonito.
 */
object Ui {
    val BG = Color.rgb(9, 5, 16)
    val BG2 = Color.rgb(26, 11, 52)
    val CARD = Color.rgb(24, 14, 42)
    val CARD2 = Color.rgb(42, 25, 72)
    val ACCENT = Color.rgb(157, 78, 255)
    val OK = Color.rgb(196, 148, 255)
    val ACCENT_DK = Color.rgb(88, 36, 170)
    val MUTED = Color.rgb(176, 160, 208)
    val STROKE = Color.argb(70, 157, 78, 255)

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int, c: Context, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp).toFloat(); if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    /** Fundo das telas: degradê preto → roxo escuro (de cima para baixo). */
    fun screenBg() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG2, BG, BG))

    fun gradient(c: Context, radiusDp: Int, from: Int = ACCENT_DK, to: Int = ACCENT) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(from, to)).apply { cornerRadius = dp(c, radiusDp).toFloat() }

    fun title(c: Context, t: String, sub: String? = null) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 8), 0, dp(c, 14))
        addView(TextView(c).apply { text = t; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (sub != null) addView(TextView(c).apply { text = sub; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(c, 4), 0, 0); setLineSpacing(0f, 1.1f) })
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = round(CARD, 22, c, STROKE); setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 14) }
    }

    /** Cartão com cabeçalho: ícone (emoji) em círculo roxo + título + explicação curta. O conteúdo vai em cima do cartão devolvido. */
    fun section(c: Context, emoji: String, title: String, sub: String? = null): LinearLayout {
        val card = card(c)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(c).apply {
            text = emoji; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD2) }
        }, LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 12), 0, 0, 0) }
        col.addView(TextView(c).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (sub != null) col.addView(TextView(c).apply { text = sub; textSize = 12.5f; setTextColor(MUTED) })
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)
        return card
    }

    fun label(c: Context, t: String, size: Float = 13f, color: Int = MUTED) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, dp(c, 8), 0, dp(c, 2)); setLineSpacing(0f, 1.08f)
    }

    fun button(c: Context, t: String, primary: Boolean = false, onClick: () -> Unit) = Button(c).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (primary) gradient(c, 16) else round(CARD2, 16, c, STROKE)
        setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 8) }
        setOnClickListener { onClick() }
    }

    /** Quadro grande da tela inicial: ícone, título e uma linha explicando. Dois por linha (peso igual). */
    fun tile(c: Context, emoji: String, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = if (highlight) gradient(c, 22) else round(CARD, 22, c, STROKE)
        setPadding(dp(c, 10), dp(c, 18), dp(c, 10), dp(c, 16))
        isClickable = true
        addView(TextView(c).apply { text = emoji; textSize = 30f; gravity = Gravity.CENTER })
        addView(TextView(c).apply { text = title; textSize = 15.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(c, 6), 0, 0); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(TextView(c).apply { text = sub; textSize = 11.5f; setTextColor(if (highlight) Color.argb(220, 255, 255, 255) else MUTED); gravity = Gravity.CENTER; setPadding(0, dp(c, 2), 0, 0) })
        setOnClickListener { onClick() }
    }

    /** Linha com [n] quadros iguais lado a lado. */
    fun row(c: Context, vararg tiles: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, t) in tiles.withIndex()) addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = dp(c, 10) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 10) }
    }

    fun input(c: Context, hint: String, value: String, number: Boolean = false) = EditText(c).apply {
        setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 16f
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        background = round(CARD2, 14, c, STROKE); setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 4) }
    }

    /**
     * ABAS: barra de pílulas que rola para o lado + uma página por aba (cada página rola sozinha). Recebe pares (título, conteúdo).
     * O resultado deve ocupar o resto da tela (peso 1) dentro de um LinearLayout vertical.
     */
    /**
     * Abas em pílulas. Sem [header]: cada página rola sozinha embaixo das abas (comportamento antigo).
     * Com [header]: o cabeçalho, as abas e a página formam UMA lista que rola junto — ao rolar para baixo o cabeçalho SOBE e some,
     * deixando a tela inteira para o conteúdo (antes o cabeçalho ficava preso e sobravam só ~2 cm para as opções).
     */
    fun tabs(c: Context, items: List<Pair<String, View>>, startIndex: Int = 0, header: View? = null): View {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(c, 4), dp(c, 6), dp(c, 4), dp(c, 8)) }
        val hs = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
        val body = FrameLayout(c)
        val pills = ArrayList<TextView>(); val pages = ArrayList<View>()
        fun select(i: Int) {
            for (k in pills.indices) {
                val on = k == i
                pages[k].visibility = if (on) View.VISIBLE else View.GONE
                pills[k].background = if (on) gradient(c, 20) else round(CARD2, 20, c, STROKE)
                pills[k].setTextColor(if (on) Color.WHITE else MUTED)
            }
        }
        for ((i, item) in items.withIndex()) {
            val pill = TextView(c).apply {
                text = item.first; textSize = 14f; gravity = Gravity.CENTER; setSingleLine(true)
                setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9)); isClickable = true
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setOnClickListener { select(i) }
            }
            pills.add(pill)
            bar.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(c, 8) })
            if (header == null) {
                val sv = ScrollView(c).apply { addView(item.second); isVerticalScrollBarEnabled = false }
                pages.add(sv)
                body.addView(sv, FrameLayout.LayoutParams(-1, -1))
            } else {
                pages.add(item.second)
                body.addView(item.second, FrameLayout.LayoutParams(-1, -2))
            }
        }
        select(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        if (header == null) {
            col.addView(hs, LinearLayout.LayoutParams(-1, -2))
            col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            return col
        }
        (header.parent as? ViewGroup)?.removeView(header)
        col.addView(header, LinearLayout.LayoutParams(-1, -2))
        col.addView(hs, LinearLayout.LayoutParams(-1, -2))
        col.addView(body, LinearLayout.LayoutParams(-1, -2))
        return ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(col, ViewGroup.LayoutParams(-1, -2)) }
    }

    fun page(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 4), dp(c, 6), dp(c, 4), dp(c, 24)) }

    fun gravityCenter(v: TextView) { v.gravity = Gravity.CENTER }
    fun hide(v: View) { v.visibility = View.GONE }
}
