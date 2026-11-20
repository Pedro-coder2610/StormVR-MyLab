package com.stormvr.app.applauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * PROVA DE CONCEITO: tenta abrir UM app externo num display virtual criado pelo StormVR e MEDE o que acontece.
 *
 * Sem root, sem permissões especiais, sem MediaProjection: só APIs públicas (DisplayManager, ActivityOptions.setLaunchDisplayId,
 * ActivityManager.isActivityStartAllowedOnDisplay). Pelas regras de segurança do Android (veja RELATORIO_APP_LAUNCHER.md) o esperado é que
 * o sistema BLOQUEIE apps de terceiros que não declaram android:allowEmbedded — e este código reporta exatamente o que o aparelho responder.
 *
 * O destino das imagens aqui é um ImageReader (para MEDIR se chegam quadros com conteúdo). Na versão final troca-se esta Surface pela
 * [AppWindowSurface] da janela, sem mudar o resto.
 *
 * Regras de segurança: NUNCA tenta lançar se o sistema já respondeu "negado" (evita o app cair na tela principal), não tenta de novo
 * sozinha, e libera display/leitor/thread em [close]. Toda a API pública é da thread principal.
 */
class ExternalAppSession(
    private val activity: Activity,
    private val app: AppEntry,
    private val cb: Callback
) {
    interface Callback {
        /** Miniatura de um quadro recebido do display virtual (thread principal, no máximo ~2 por segundo). */
        fun onPreview(frame: Bitmap)

        /** Veredito final (thread principal). Chamado uma vez. */
        fun onFinished(report: LaunchReport)
    }

    companion object {
        private const val TAG = "StormVR/AppLauncher"
        const val W = 1280
        const val H = 720
        const val DPI = 240
        private const val OBSERVE_MS = 6000L
        private const val FRAME_EVERY_MS = 500L
        private const val PREVIEW_W = 400
        private const val PREVIEW_H = 225
        /** Diferença mínima de luminância entre pontos amostrados para dizer que o quadro "tem conteúdo". */
        private const val CONTENT_RANGE = 12
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var closed = false
    private var finished = false
    private var launched = false
    private val frames = AtomicInteger(0)
    private val contentFrames = AtomicInteger(0)
    @Volatile private var lastProcessedMs = 0L
    @Volatile private var lostForeground = false
    private val report = LaunchReport()

    private fun yn(b: Boolean) = if (b) "SIM" else "NÃO"

    private fun flagsText(f: Int): String {
        val parts = ArrayList<String>()
        if ((f and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) parts.add("PUBLIC")
        if ((f and DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) parts.add("OWN_CONTENT_ONLY")
        return parts.joinToString("|")
    }

    /** Executa as etapas na ordem; o veredito sai por [Callback.onFinished] (na hora, se falhar cedo; após ~6 s se o app for iniciado). */
    fun start() {
        val r = report
        val pm = activity.packageManager
        r.add("Aplicativo: ${app.label} (${app.packageName})")
        r.add("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        // 1) o aparelho aceita Activities em displays secundários? (sem isso o Android IGNORA o display pedido e abriria na tela principal)
        val feature = pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
        r.add("Recurso android.software.activities_on_secondary_displays: " + yn(feature))
        if (!feature) { finish(Verdict.UNSUPPORTED_DEVICE); return }

        // 2) o app ainda tem Activity inicializável?
        val intent: Intent? = try { pm.getLaunchIntentForPackage(app.packageName) } catch (t: Throwable) { null }
        if (intent == null) {
            r.add("Intent de lançamento: AUSENTE (o app não tem mais Activity inicializável)")
            finish(Verdict.ERROR)
            return
        }

        // 3) display virtual próprio
        if (!createDisplay()) { finish(Verdict.DISPLAY_FAILED); return }
        val displayId = vd?.display?.displayId ?: -1
        if (displayId < 0) { r.add("Display virtual sem id válido"); finish(Verdict.DISPLAY_FAILED); return }

        // 4) a checagem do PRÓPRIO Android, antes de tentar (API 29+): usa as mesmas regras do início real da Activity
        var allowed: Boolean? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val method = ActivityManager::class.java.getMethod(
                    "isActivityStartAllowedOnDisplay",
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                )
                allowed = method.invoke(null, activity, displayId, intent) as? Boolean
            } catch (t: Throwable) {
                r.add("Checagem do sistema: erro ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        r.add("Checagem do sistema (ActivityManager.isActivityStartAllowedOnDisplay): " + triText(allowed))
        if (allowed == false) {
            r.add("startActivity com setLaunchDisplayId: NÃO TENTADO (o sistema já negou; forçar só arriscaria abrir o app na tela principal)")
            finish(Verdict.BLOCKED_BY_SYSTEM)
            return
        }

        // 5) lançamento no display pedido. NEW_TASK|MULTIPLE_TASK: cria uma tarefa NOVA no display virtual em vez de puxar
        //    para cá uma tarefa que o app já tenha na tela principal.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        registerLifecycle()
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(intent, opts.toBundle())
            launched = true
            r.add("startActivity com setLaunchDisplayId=$displayId: executado sem exceção")
        } catch (e: SecurityException) {
            r.add("startActivity com setLaunchDisplayId=$displayId: NEGADO (SecurityException: ${e.message})")
            finish(Verdict.DENIED)
            return
        } catch (e: ActivityNotFoundException) {
            r.add("startActivity: ActivityNotFoundException (${e.message})")
            finish(Verdict.ERROR)
            return
        } catch (e: Throwable) {
            r.add("startActivity: ${e.javaClass.simpleName}: ${e.message}")
            finish(Verdict.ERROR)
            return
        }

        // 6) observa por alguns segundos: chegam quadros? o StormVR continua em primeiro plano?
        main.postDelayed({ evaluate() }, OBSERVE_MS)
    }

    private fun triText(b: Boolean?): String = when (b) {
        true -> "PERMITIDO"
        false -> "NEGADO"
        null -> "indisponível (API < 29 ou erro)"
    }

    private fun createDisplay(): Boolean {
        val t = HandlerThread("AppLauncher-probe")
        t.start()
        thread = t
        val rd = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
        reader = rd
        rd.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { onFrame(it) }, Handler(t.looper))
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val attempts = intArrayOf(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        for (flags in attempts) {
            try {
                val d = dm.createVirtualDisplay("stormvr-app", W, H, DPI, rd.surface, flags)
                if (d != null) {
                    vd = d
                    report.add("Display virtual criado: SIM (id=${d.display.displayId}, ${W}x$H @${DPI}dpi, flags=${flagsText(flags)})")
                    return true
                }
                report.add("Display virtual (${flagsText(flags)}): o sistema devolveu null")
            } catch (e: Throwable) {
                report.add("Display virtual (${flagsText(flags)}): ERRO ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return false
    }

    private fun registerLifecycle() {
        val l = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity && launched) lostForeground = true }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        lifecycle = l
        try { activity.application.registerActivityLifecycleCallbacks(l) } catch (t: Throwable) { lifecycle = null }
    }

    private fun unregisterLifecycle() {
        val l = lifecycle ?: return
        lifecycle = null
        try { activity.application.unregisterActivityLifecycleCallbacks(l) } catch (t: Throwable) { }
    }

    /** Thread do leitor de imagens: conta quadros e mede se têm conteúdo (amostra de uma grade de pontos); ~2 miniaturas por segundo. */
    private fun onFrame(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (t: Throwable) { null }
        if (img == null) return
        try {
            frames.incrementAndGet()
            val now = SystemClock.uptimeMillis()
            if (now - lastProcessedMs < FRAME_EVERY_MS) return
            lastProcessedMs = now

            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pix = plane.pixelStride
            val w = img.width
            val h = img.height
            var mn = 255
            var mx = 0
            val gx = 24
            val gy = 14
            for (j in 0 until gy) {
                val y = (j * (h - 1)) / (gy - 1)
                for (i in 0 until gx) {
                    val x = (i * (w - 1)) / (gx - 1)
                    val o = y * rowStride + x * pix
                    if (o + 2 >= buf.limit()) continue
                    val rr = buf.get(o).toInt() and 0xFF
                    val gg = buf.get(o + 1).toInt() and 0xFF
                    val bb = buf.get(o + 2).toInt() and 0xFF
                    val l = (rr * 30 + gg * 59 + bb * 11) / 100
                    if (l < mn) mn = l
                    if (l > mx) mx = l
                }
            }
            if (mx - mn > CONTENT_RANGE) contentFrames.incrementAndGet()

            // miniatura (respeitando o preenchimento de linha do buffer)
            val pad = (rowStride - pix * w) / pix
            val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            val small = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(full, Rect(0, 0, w, h), Rect(0, 0, PREVIEW_W, PREVIEW_H), Paint(Paint.FILTER_BITMAP_FLAG))
            full.recycle()
            main.post { if (!closed) cb.onPreview(small) }
        } catch (t: Throwable) {
            // leitor fechado no meio do quadro ou buffer inesperado: só perde a miniatura
        } finally {
            try { img.close() } catch (t: Throwable) { }
        }
    }

    private fun evaluate() {
        if (closed || finished) return
        val n = frames.get()
        val c = contentFrames.get()
        report.add("StormVR manteve o primeiro plano: " + yn(!lostForeground))
        report.add("Quadros recebidos na superfície do StormVR: $n (com conteúdo visível: $c)")
        val v = when {
            lostForeground -> Verdict.FELL_BACK_TO_MAIN_DISPLAY
            n == 0 -> Verdict.STARTED_NO_FRAMES
            c == 0 -> Verdict.STARTED_BLANK
            else -> Verdict.RENDERING
        }
        finish(v)
    }

    private fun finish(v: Verdict) {
        if (finished) return
        finished = true
        unregisterLifecycle()
        report.verdict = v
        val interaction: String
        if (v == Verdict.RENDERING) {
            interaction = "NÃO DISPONÍVEL — injetar eventos em outro app exige INJECT_EVENTS (permissão de assinatura; só shell/sistema)"
        } else {
            interaction = "não avaliável (o app não está no display)"
        }
        report.add("Interação (toque/teclado) no app externo: $interaction")
        report.add("Conclusão: " + v.short)
        Log.i(TAG, "relatório da prova de conceito:\n" + report.text())
        // Se o app foi de fato iniciado no display, o display fica até o usuário fechar a janela (soltá-lo pode mover o app para a tela
        // principal). Nos demais casos não há nada a manter.
        if (!launched || v == Verdict.FELL_BACK_TO_MAIN_DISPLAY) releaseResources()
        cb.onFinished(report)
    }

    private fun releaseResources() {
        try { vd?.release() } catch (t: Throwable) { }
        vd = null
        try { reader?.setOnImageAvailableListener(null, null) } catch (t: Throwable) { }
        try { reader?.close() } catch (t: Throwable) { }
        reader = null
        try { thread?.quitSafely() } catch (t: Throwable) { }
        thread = null
    }

    /** Encerra a sessão e libera display, leitor de imagens e thread. Pode ser chamado várias vezes. */
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        unregisterLifecycle()
        releaseResources()
    }
}
