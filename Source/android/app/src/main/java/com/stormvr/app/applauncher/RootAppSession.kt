package com.stormvr.app.applauncher

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * Execução externa COM ROOT. O StormVR cria o display virtual (dono da Surface = a da aba "Ao vivo"); o helper root
 * ([RootHelper]) abre o app nesse display, injeta toque/tecla e confirma onde o app ficou. Nada é simulado: o veredito sai
 * do `am start` e do `dumpsys`, e o relatório mostra as linhas de evidência.
 *
 * Ao fechar, o app aberto é parado (`am force-stop`) ANTES de soltar o display, porque soltar o display com o app dentro pode
 * jogá-lo para a tela principal do celular. Isso descarta o estado não salvo do app.
 *
 * [start] e [close] podem ser chamados da thread principal (o trabalho pesado roda em threads próprias); [onDone] volta na principal.
 */
class RootAppSession(
    private val activity: Activity,
    private val app: AppEntry,
    private val client: RootHelperClient,
    initialSurface: Surface?,
    private val onDone: (LaunchReport) -> Unit
) {
    companion object {
        private const val TAG = "StormVR/AppLauncher"
        private const val SETTLE_MS = 2500L
    }

    private val main = Handler(Looper.getMainLooper())
    private val report = LaunchReport()
    @Volatile private var surface: Surface? = initialSurface
    @Volatile private var closed = false
    @Volatile private var vd: VirtualDisplay? = null
    @Volatile private var displayId = -1

    /** true enquanto há um app (lançado e aceito) dentro do display virtual. */
    @Volatile
    var running = false
        private set

    fun start() {
        Thread(Runnable { runSteps() }, "AppLauncher-root-session").apply { isDaemon = true; start() }
    }

    private fun runSteps() {
        val r = report
        r.add("Modo: ROOT (helper via su + app_process)")
        r.add("Aplicativo: ${app.label} (${app.packageName})")
        r.add("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        val err = client.ensureStarted()
        if (err != null) { r.add("Helper root: FALHOU — $err"); done(Verdict.ROOT_UNAVAILABLE, false); return }
        r.add("Helper root: " + client.info)
        if (closed) return

        val s = surface
        if (s == null || !s.isValid) { r.add("Superfície da aba Ao vivo indisponível"); done(Verdict.DISPLAY_FAILED, false); return }
        val d = createDisplay(s)
        if (d == null) { done(Verdict.DISPLAY_FAILED, false); return }
        vd = d
        displayId = d.display.displayId
        if (closed) { releaseDisplay(); return }

        val comp = app.component.flattenToString()
        val launch = client.request("LAUNCH $displayId $comp", "LAUNCHED", 20000L)
        if (launch == null) { r.add("am start: sem resposta do helper"); done(Verdict.ROOT_LAUNCH_FAILED, false); return }
        r.add("am start --display $displayId -n $comp: " + launch.removePrefix("LAUNCHED ").replace('¶', ' ').take(300))
        val code = launch.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val low = launch.lowercase()
        if (launch.startsWith("ERR") || code != 0 || low.contains("error") || low.contains("exception") || low.contains("not started")) {
            done(Verdict.ROOT_LAUNCH_FAILED, false)
            return
        }

        try { Thread.sleep(SETTLE_MS) } catch (t: InterruptedException) { }
        if (closed) { stopAndRelease(); return }
        val where = client.request("WHERE ${app.packageName}", "WHERE", 20000L)
        if (where == null) { r.add("dumpsys: sem resposta do helper"); running = true; done(Verdict.ROOT_UNVERIFIED, true); return }
        val body = where.removePrefix("WHERE ")
        val ids = body.substringBefore(" :: ").trim()
        val evidence = body.substringAfter(" :: ", "").replace('¶', ' ')
        r.add("dumpsys: Activity do app vista no(s) display(s): $ids  (display virtual do StormVR = $displayId)")
        for (e in evidence.split(" ¦ ")) if (e.isNotBlank()) r.add("   evidência: " + e.trim())

        val seen = ids.split(',').mapNotNull { it.trim().toIntOrNull() }
        when {
            seen.contains(displayId) -> { running = true; done(Verdict.ROOT_RUNNING, true) }
            seen.isEmpty() -> { running = true; done(Verdict.ROOT_UNVERIFIED, true) }
            else -> done(Verdict.ROOT_WRONG_DISPLAY, false, stop = true)
        }
    }

    private fun createDisplay(s: Surface): VirtualDisplay? {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val attempts = intArrayOf(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        for (flags in attempts) {
            val names = if ((flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) "PUBLIC|OWN_CONTENT_ONLY" else "OWN_CONTENT_ONLY"
            try {
                val d = dm.createVirtualDisplay("stormvr-root-app", ExternalAppSession.W, ExternalAppSession.H, ExternalAppSession.DPI, s, flags)
                if (d != null) {
                    report.add("Display virtual criado: SIM (id=${d.display.displayId}, ${ExternalAppSession.W}x${ExternalAppSession.H} @${ExternalAppSession.DPI}dpi, flags=$names)")
                    return d
                }
                report.add("Display virtual ($names): o sistema devolveu null")
            } catch (e: Throwable) {
                report.add("Display virtual ($names): ERRO ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    private fun done(v: Verdict, keep: Boolean, stop: Boolean = false) {
        report.verdict = v
        val interaction: String
        if (v == Verdict.ROOT_RUNNING || v == Verdict.ROOT_UNVERIFIED) {
            interaction = "toque (arrastar/rolar) e tecla Voltar pelo helper root; texto ainda não ligado"
        } else {
            interaction = "não avaliável"
        }
        report.add("Interação: $interaction")
        report.add("Conclusão: " + v.short)
        Log.i(TAG, "relatório (root):\n" + report.text())
        if (closed) {                                    // fechada enquanto trabalhava: limpa e não avisa ninguém
            running = false
            if (keep || stop) stopAndRelease() else releaseDisplay()
            return
        }
        if (!keep) { if (stop) stopAndRelease() else releaseDisplay() }
        main.post { onDone(report) }
    }

    /** Pausa/retoma a imagem quando a aba Ao vivo some/volta (o app continua rodando no display). */
    fun setSurface(s: Surface?) {
        surface = s
        try { vd?.surface = s } catch (t: Throwable) { }
    }

    /** Toque no app, em pixels do display virtual (0..W-1, 0..H-1). action: 0 down, 1 up, 2 move, 3 cancel. */
    fun touch(action: Int, x: Int, y: Int) {
        if (!running || displayId < 0) return
        client.send("T $displayId $action $x $y", action == 2)
    }

    fun key(code: Int) {
        if (!running || displayId < 0) return
        client.send("K $displayId $code")
    }

    private fun releaseDisplay() {
        try { vd?.release() } catch (t: Throwable) { }
        vd = null
    }

    private fun stopAndRelease() {
        client.send("STOP ${app.packageName}")
        client.awaitReply("STOPPED", 6000L)
        releaseDisplay()
    }

    /** Fecha: para o app, solta o display. Não bloqueia quem chama. */
    fun close() {
        if (closed) return
        closed = true
        val wasRunning = running
        running = false
        if (wasRunning) {
            client.send("STOP ${app.packageName}")                           // enfileirado já, antes de qualquer QUIT do cliente
            Thread(Runnable { client.awaitReply("STOPPED", 6000L); releaseDisplay() }, "AppLauncher-root-close").apply { isDaemon = true; start() }
        } else {
            releaseDisplay()
        }
    }
}
