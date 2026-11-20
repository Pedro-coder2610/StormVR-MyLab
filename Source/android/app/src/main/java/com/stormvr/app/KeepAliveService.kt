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

/**
 * Mantém o StormVR vivo em segundo plano (tela apagada ou outro app na frente): serviço em primeiro plano + WakeLock + Wi-Fi de baixa latência.
 * Sem isso o Android corta sensores e rede do app minutos depois de sair da tela. Usado pelo modo controle do celular e pelo reenvio
 * do Bluetooth ao PC. LIMITE do Android: a CÂMERA (6DoF por ARCore e mãos) só funciona com o app na tela.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val what = intent?.getStringExtra(EXTRA_TEXT) ?: "Enviando dados ao PC"
        startInForeground(what)
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stormvr:keepalive").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "stormvr:keepalive").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "StormVR em segundo plano", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("StormVR ativo").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "stormvr_keepalive"
        private const val NOTIF_ID = 4412
        const val ACTION_STOP = "com.stormvr.app.KEEPALIVE_STOP"
        const val EXTRA_TEXT = "text"
        fun start(c: Context, text: String) {
            val i = Intent(c, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, KeepAliveService::class.java)) }
    }
}
