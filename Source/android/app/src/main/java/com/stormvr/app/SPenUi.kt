package com.stormvr.app

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Opções da S Pen como controle, reutilizáveis em qualquer tela (menu principal, painel da Sala VR...).
 * Vale com o S23 Ultra usado como HEADSET: com o StormVR aberto, a S Pen removida vira um controle (botão lateral = trigger).
 */
object SPenUi {
    fun addTo(ctx: Context, parent: LinearLayout, labelColor: Int = Color.WHITE) {
        val title = TextView(ctx).apply { text = "S Pen como controle (quando removida do celular)"; setTextColor(labelColor); textSize = 15f; setPadding(0, 18, 0, 4) }
        val status = TextView(ctx).apply { setTextColor(Color.rgb(214, 180, 255)); textSize = 12f }
        val bOn = Button(ctx).apply { isAllCaps = false }
        val bHand = Button(ctx).apply { isAllCaps = false }
        val bGain = Button(ctx).apply { isAllCaps = false }
        val bMode = Button(ctx).apply { isAllCaps = false }
        val bCenter = Button(ctx).apply { isAllCaps = false; text = "Recentrar a S Pen" }
        fun refresh() {
            bOn.text = if (SPenBridge.enabled) "S Pen como controle: LIGADA" else "S Pen como controle: desligada"
            bHand.text = "Mão da S Pen: " + arrayOf("AUTOMÁTICA (direita; esquerda se o celular-controle usar a direita)", "DIREITA", "ESQUERDA")[SPenBridge.handMode]
            bGain.text = "Sensibilidade da S Pen: %.2fx (toque para mudar)".format(SPenBridge.gain)
            bMode.text = if (SPenBridge.buttonMode == 0) "Botão lateral: trigger direto" else "Botão lateral: clique = trigger, segurar = só mirar"
            status.text = SPenBridge.status + "  (eventos: botão ${SPenBridge.evButton}, movimento ${SPenBridge.evMotion})" + (if (SPenBridge.lastError.isNotEmpty()) "\n" + SPenBridge.lastError else "")
        }
        bOn.setOnClickListener { SPenBridge.enabled = !SPenBridge.enabled; refresh() }
        bHand.setOnClickListener { SPenBridge.handMode = (SPenBridge.handMode + 1) % 3; refresh() }
        bGain.setOnClickListener { val g = SPenBridge.gain; SPenBridge.gain = if (g >= 3.9f) 0.25f else g * 1.5f; refresh() }
        bMode.setOnClickListener { SPenBridge.buttonMode = 1 - SPenBridge.buttonMode; refresh() }
        bCenter.setOnClickListener { SPenBridge.recenter(); refresh() }
        status.setOnClickListener { refresh() }
        for (v in listOf<android.view.View>(title, bOn, bHand, bGain, bMode, bCenter, status)) parent.addView(v)
        refresh()
    }
}
