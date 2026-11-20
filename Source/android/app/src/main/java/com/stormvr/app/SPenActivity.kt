package com.stormvr.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView

/** Tela de opções da S Pen (também disponíveis no painel da Sala VR e no Modo controle). */
class SPenActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((18 * d).toInt(), (34 * d).toInt(), (18 * d).toInt(), (28 * d).toInt()); setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(this, "S Pen como controle", "Com o StormVR aberto, tire a S Pen do celular: o botão lateral é o trigger e mover a caneta no ar aponta. Vai para a Sala VR e para o SteamVR (3DoF/6DoF). Funciona também com o S23 Ultra como HEADSET."))
        SPenUi.addTo(this, root)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) })
    }
}
