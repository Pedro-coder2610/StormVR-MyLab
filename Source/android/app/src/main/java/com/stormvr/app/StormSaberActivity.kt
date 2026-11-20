package com.stormvr.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.stormvr.app.vrroom.StormSaberPane

/**
 * StormSaber em TELA CHEIA do celular, já em ESTÉREO (SBS) para usar dentro do headset.
 * Usa os controles reais (ESP32 por Bluetooth/Wi-Fi ou outro celular) pelo ControllerHub.
 * Os botões "Visão", "Sabres" e "Menu" ficam no canto (tocar na tela, ou apontar o laser).
 */
class StormSaberActivity : Activity() {
    private lateinit var pane: StormSaberPane
    private val handler = Handler(Looper.getMainLooper())
    private val yaw0 = FloatArray(2)
    private val have = BooleanArray(2)
    private val sy = FloatArray(2)
    private val sp = FloatArray(2)
    private var lastMs = 0L
    // cabeça: o celular dentro do headset gira a cena (olhar em volta). yaw/pitch em graus; o rumo de partida vira "frente".
    private lateinit var head: HeadTracker
    private val hq = FloatArray(4)
    private var yawRef = Float.NaN
    private lateinit var extras: HeadExtras
    private var lookFixed = false

    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = ((now - lastMs).coerceIn(1L, 50L)) / 1000f
            lastMs = now
            @Suppress("DEPRECATION") run { head.displayRotation = windowManager.defaultDisplay.rotation }
            if (head.hasData) {
                head.read(hq)                                                  // quaternio w,x,y,z no referencial do SteamVR (x direita, y cima, -z frente)
                val w = hq[0]; val x = hq[1]; val y = hq[2]; val z = hq[3]
                val fx = -2f * (x * z + w * y); val fy = -2f * (y * z - w * x); val fz = -(1f - 2f * (x * x + y * y))
                val yaw = Math.toDegrees(kotlin.math.atan2(-fx.toDouble(), -fz.toDouble())).toFloat()
                val pitch = Math.toDegrees(kotlin.math.asin(fy.toDouble().coerceIn(-1.0, 1.0))).toFloat()
                if (yawRef.isNaN()) yawRef = yaw
                var dyw = yaw - yawRef
                while (dyw > 180f) dyw -= 360f
                while (dyw < -180f) dyw += 360f
                if (lookFixed) pane.setHead(0f, 0f) else pane.setHead(dyw, pitch)          // cabeça fixa (opção): a cena não acompanha o 3DoF
                if (extras.active) { extras.updateLean(hq); pane.setBody(if (extras.leanOn) extras.lean else 0f, if (extras.baroOn) extras.drop else 0f) } else pane.setBody(0f, 0f)
            }
            for (i in 0..1) {
                val c = ControllerHub.hand(i == 0)
                if (!c.active()) { have[i] = false; if (!pane.fingerHolds(i)) { pane.setSaber(i, 0f, 1f, 0f, false); pane.setTrigger(i, false) }; continue }
                pane.setTrigger(i, (c.buttons and ControllerUdp.BTN_TRIGGER) != 0)
                if (!have[i]) { have[i] = true; yaw0[i] = c.yaw; sy[i] = 0f; sp[i] = c.pitch }
                val yaw = c.yaw - yaw0[i]
                var ey = yaw - sy[i]
                while (ey > 180f) ey -= 360f
                while (ey < -180f) ey += 360f
                val ep = c.pitch - sp[i]
                val err = kotlin.math.sqrt(ey * ey + ep * ep)
                val al = 1f - kotlin.math.exp(-dt / (0.05f / (1f + err / 6f)))     // mesma suavização adaptativa da Sala 3D
                sy[i] += ey * al; sp[i] += ep * al
                val yr = Math.toRadians(sy[i].toDouble()).toFloat(); val pr = Math.toRadians(sp[i].toDouble()).toFloat()
                pane.setSaber(i, -kotlin.math.sin(yr) * kotlin.math.cos(pr), kotlin.math.cos(yr) * kotlin.math.cos(pr), kotlin.math.sin(pr), true)
            }
            handler.postDelayed(this, 8)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) com.stormvr.app.Immersive.apply(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Immersive.apply(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ControllerHub.start(this)
        if (BleController.hasPermission(this)) BleController.start(applicationContext)
        else requestPermissions(BleController.permissionsNeeded(), 79)
        head = HeadTracker(this)
        extras = HeadExtras(this)
        pane = StormSaberPane(this).apply { stereo = true; onQuit = { finish() } }
        setContentView(pane)
    }

    override fun onResume() { super.onResume(); lastMs = android.os.SystemClock.uptimeMillis(); yawRef = Float.NaN; head.start(); val p = Prefs(this); extras.apply(p); lookFixed = p.saberLookFixed; extras.recenter(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); head.stop(); extras.stop(); super.onPause() }
}
