package com.stormvr.app.applauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.Collator
import java.util.concurrent.Executors

/**
 * Biblioteca de aplicativos: descobre os apps com ícone de launcher, com nome e ícone, e se mantém atualizada.
 *
 * Visibilidade de pacotes (Android 11+): usa o `<queries><intent>` MAIN/LAUNCHER do manifesto, o mesmo caminho dos launchers.
 * NÃO usa QUERY_ALL_PACKAGES. A varredura roda numa thread própria; os resultados e o [onChanged] chegam na thread principal.
 * Use só na thread principal (refresh, startWatching, stopWatching, release).
 */
class AppRepository(context: Context) {
    companion object {
        private const val TAG = "StormVR/AppLauncher"
        /** Lado (px) do ícone guardado na memória. */
        const val ICON_PX = 96
        private const val DEBOUNCE_MS = 1500L
    }

    private val app: Context = context.applicationContext
    private val pm: PackageManager = app.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLauncher-scan").apply { isDaemon = true } }
    @Volatile private var released = false
    private var scanning = false
    private var rescanQueued = false
    private var receiver: BroadcastReceiver? = null
    private val delayedRefresh = Runnable { refresh() }

    /** Lista atual, ordenada pelo nome. Nunca contém o próprio StormVR (evita abrir o app dentro dele mesmo). */
    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    /** true depois da primeira varredura concluída com sucesso. */
    @Volatile
    var loaded: Boolean = false
        private set

    /** Mensagem do último erro de varredura (null = sem erro). */
    @Volatile
    var error: String? = null
        private set

    /** Chamado na thread principal quando a lista (ou o erro) mudou. */
    var onChanged: (() -> Unit)? = null

    /** Refaz a lista em segundo plano. Pedidos que chegam durante uma varredura viram UMA nova varredura depois (sem acumular). */
    fun refresh() {
        if (released) return
        if (scanning) { rescanQueued = true; return }
        scanning = true
        try {
            io.execute {
                var result: List<AppEntry>? = null
                var err: String? = null
                try {
                    result = scan()
                } catch (t: Throwable) {
                    Log.w(TAG, "varredura de apps falhou", t)
                    err = t.javaClass.simpleName + ": " + (t.message ?: "")
                }
                main.post { onScanDone(result, err) }
            }
        } catch (t: Throwable) {       // executor já encerrado
            scanning = false
        }
    }

    private fun onScanDone(result: List<AppEntry>?, err: String?) {
        scanning = false
        if (released) return
        if (result != null) { apps = result; loaded = true; error = null } else { error = err ?: "erro desconhecido" }
        onChanged?.invoke()
        if (rescanQueued) { rescanQueued = false; refresh() }
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchers(): List<ResolveInfo> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(q, PackageManager.ResolveInfoFlags.of(0L))
        else pm.queryIntentActivities(q, 0)
    }

    /** Roda na thread de varredura. Um app por pacote; pacotes sem Activity inicializável utilizável são ignorados. */
    private fun scan(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        for (ri in queryLaunchers()) {
            try {
                val ai = ri.activityInfo ?: continue
                val pkg: String = ai.packageName ?: continue
                if (pkg == app.packageName || byPkg.containsKey(pkg)) continue
                if (!ai.exported || !ai.enabled) continue                       // sem Activity inicializável utilizável

                val label = (ri.loadLabel(pm)?.toString() ?: "").trim().ifEmpty { pkg }
                val own = iconOf(ri)
                val icon = own ?: placeholder(label)
                val sys = ai.applicationInfo != null && (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                byPkg[pkg] = AppEntry(pkg, label, ComponentName(pkg, ai.name), sys, icon, own != null)
            } catch (t: Throwable) {
                Log.w(TAG, "app ignorado na varredura", t)                       // um app problemático não derruba a lista
            }
        }
        val col = Collator.getInstance()
        return byPkg.values.sortedWith(Comparator<AppEntry> { a, b -> col.compare(a.label, b.label) })
    }

    private fun iconOf(ri: ResolveInfo): Bitmap? {
        return try {
            val d: Drawable? = ri.loadIcon(pm)
            if (d == null) null else drawableToBitmap(d)
        } catch (t: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(c)
        return bmp
    }

    /** Ícone gerado para app sem ícone: quadrado arredondado roxo com a inicial. */
    private fun placeholder(label: String): Bitmap {
        val s = ICON_PX.toFloat()
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(88, 36, 170)
        c.drawRoundRect(RectF(0f, 0f, s, s), s * 0.22f, s * 0.22f, p)
        p.color = Color.WHITE
        p.textSize = s * 0.55f
        p.textAlign = Paint.Align.CENTER
        val ch = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        c.drawText(ch, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    /** Intent para abrir o app pelo packageName (null se ele não tem mais Activity inicializável). */
    fun launchIntent(pkg: String): Intent? = try { pm.getLaunchIntentForPackage(pkg) } catch (t: Throwable) { null }

    /** Passa a ouvir instalação/remoção/atualização de apps (receiver dinâmico; NÃO usa receiver no manifesto). Reagrupa rajadas em 1 varredura. */
    fun startWatching() {
        if (released || receiver != null) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (released) return
                main.removeCallbacks(delayedRefresh)
                main.postDelayed(delayedRefresh, DEBOUNCE_MS)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f)
            receiver = r
        } catch (t: Throwable) {
            Log.w(TAG, "não consegui observar instalações", t)
        }
    }

    fun stopWatching() {
        main.removeCallbacks(delayedRefresh)
        val r = receiver ?: return
        receiver = null
        try { app.unregisterReceiver(r) } catch (t: Throwable) { }
    }

    fun release() {
        released = true
        stopWatching()
        main.removeCallbacksAndMessages(null)
        onChanged = null
        try { io.shutdownNow() } catch (t: Throwable) { }
        apps = emptyList()
    }
}
