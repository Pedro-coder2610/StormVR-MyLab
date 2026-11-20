package com.stormvr.app.applauncher

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Biblioteca de apps em tela comum (2D): a mesma interface da Sala 3D, útil para testar a lista e a prova de conceito de execução
 * externa sem colocar o celular no headset. Não toca na Sala VR.
 */
class AppLauncherActivity : Activity() {
    private var launcher: AppLauncher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = AppLauncher(this)
        launcher = l
        val root = FrameLayout(this)
        val top = (28 * resources.displayMetrics.density).toInt()      // fora do entalhe/barra de status
        root.setPadding(0, top, 0, 0)
        root.addView(AppLauncherPane(this, l), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onDestroy() {
        launcher?.release()
        launcher = null
        super.onDestroy()
    }
}
