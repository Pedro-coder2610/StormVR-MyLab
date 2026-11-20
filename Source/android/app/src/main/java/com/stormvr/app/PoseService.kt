package com.stormvr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager

/**
 * Servico em primeiro plano: mantem sensores + envio UDP funcionando enquanto o Moonlight esta na tela.
 * (Desde o Android 9 apps em segundo plano nao recebem sensores; servico em primeiro plano recebe.)
 */
class PoseService : Service() {
    private var tracker: HeadTracker? = null
    private var sender: PoseSender? = null
    private var extras: HeadExtras? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val ticker = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            val t = tracker ?: continue
            t.displayRotation = currentRotation()
            extras?.apply(Prefs(this@PoseService))            // mudou nas opções? vale na hora
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECENTER -> { sender?.recenterCount?.incrementAndGet(); return START_NOT_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        if (sender != null) return START_NOT_STICKY
        val prefs = Prefs(this)
        startInForeground()

        val t = HeadTracker(this)
        t.orientationMode = prefs.orientation
        t.displayRotation = currentRotation()
        if (!t.available() || !t.start()) {
            State.error = "Sem sensor de rotacao neste aparelho."
            stopSelf(); return START_NOT_STICKY
        }
        tracker = t
        val ex = HeadExtras(this).also { it.apply(prefs) }
        extras = ex
        val s = PoseSender(t, prefs.host, prefs.port, prefs.rateHz, prefs.deviceId, ex) { Prefs(this).extrasToSteam }
        sender = s
        s.start(); ticker.start()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stormvr:pose").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "stormvr:wifi").apply { acquire() }

        // Controles Bluetooth (ESP32) conectados a este celular vão ao PC JUNTO com a pose 3DoF (mesmo serviço em primeiro plano,
        // então continua com a tela apagada). Liga/desliga na tela inicial.
        BtForward.host = prefs.host
        BtForward.enabled = prefs.btForward
        if (prefs.btForward) ControllerHub.start(applicationContext)

        State.service = this
        State.running = true
        State.error = ""
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!Prefs(this).btForward) BtForward.enabled = false
        State.running = false
        State.service = null
        ticker.interrupt()
        sender?.shutdown(); sender = null
        tracker?.stop(); tracker = null
        extras?.stop(); extras = null
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    fun snapshot(out: FloatArray): Boolean {
        val s = sender ?: return false
        val t = tracker ?: return false
        out[0] = s.rateHz; out[1] = s.sent.toFloat(); out[2] = s.errors.toFloat(); out[3] = t.sensorEvents.toFloat()
        State.error = s.lastError
        return true
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "StormVR", NotificationManager.IMPORTANCE_LOW))
        }
        fun action(a: String, label: String): Notification.Action {
            val pi = PendingIntent.getService(this, a.hashCode(), Intent(this, PoseService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pi).build()
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("StormVR: enviando rastreamento da cabeça")
            .setContentText("Toque para abrir")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_RECENTER, "Recentrar"))
            .addAction(action(ACTION_STOP, "Parar"))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    /** Estado compartilhado simples entre servico e tela. */
    object State {
        @Volatile var running = false
        @Volatile var error = ""
        @Volatile var service: PoseService? = null
    }

    companion object {
        const val ACTION_RECENTER = "com.stormvr.app.RECENTER"
        const val ACTION_STOP = "com.stormvr.app.STOP"
        private const val CHANNEL = "stormvr"
    }
}
