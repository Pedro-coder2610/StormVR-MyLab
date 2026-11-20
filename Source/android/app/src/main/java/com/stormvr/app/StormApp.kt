package com.stormvr.app

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Tela cheia OBRIGATÓRIA em todas as telas do StormVR: 100% da tela, sem barra de status/navegação e POR BAIXO do recorte (notch / furo da câmera),
 * em qualquer orientação. Aplicado a toda Activity automaticamente (e reaplicado quando o sistema tenta trazer as barras de volta).
 *
 * Também liga a S Pen (SPenBridge): com o StormVR aberto e a caneta fora do celular, ela vira um controle.
 */
class StormApp : Application() {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        SPenBridge.init(this)
        GamepadMap.init(this)
        com.stormvr.app.shizuku.ShizukuManager.init(this)          // Shizuku: só observa (instalado/ativo/autorizado); não pede nem executa nada sozinho
        try { com.stormvr.app.vrroom.SongLibrary.root(this) } catch (_: Throwable) {}        // cria Android/data/com.stormvr.app/files/Playlists/ logo na primeira abertura
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { prepare(a) }
            override fun onActivityStarted(a: Activity) {
                prepare(a)
                if (++started == 1) SPenBridge.onForeground(true)
            }
            override fun onActivityResumed(a: Activity) {
                SPenBridge.activity = a
                immersive(a)
                a.window.decorView.post { immersive(a) }
            }
            override fun onActivityPaused(a: Activity) { if (SPenBridge.activity === a) SPenBridge.activity = null }
            override fun onActivityStopped(a: Activity) { if (started > 0 && --started == 0) SPenBridge.onForeground(false) }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun prepare(a: Activity) {
        try {
            val w = a.window
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = w.attributes
                // ALWAYS (Android 11+): o conteudo ocupa o recorte em QUALQUER borda e orientacao. Antes disso, SHORT_EDGES.
                lp.layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            w.decorView.setOnSystemUiVisibilityChangeListener { vis -> if (vis and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) immersive(a) }
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun immersive(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                a.window.setDecorFitsSystemWindows(false)
                a.window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            a.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } catch (_: Throwable) {}
    }
}
