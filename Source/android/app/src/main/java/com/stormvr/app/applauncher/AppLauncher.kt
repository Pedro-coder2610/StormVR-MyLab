package com.stormvr.app.applauncher

import android.app.Activity
import android.graphics.Bitmap
import android.view.Surface

/**
 * Fachada do módulo AppLauncher: junta a biblioteca de apps, as janelas 3D e a sessão de execução externa.
 * Pertence à Activity (não à tela), então sobrevive a reconstruções da "TV" da sala. Thread principal apenas.
 * [release] libera tudo (observador de instalações, thread de varredura, display virtual, leitor de imagens).
 */
class AppLauncher(private val activity: Activity) {

    interface Listener {
        fun onAppsChanged() {}
        fun onWindowsChanged() {}
        fun onReportChanged() {}
    }

    val repository = AppRepository(activity)
    val windows = AppWindowManager()

    var lastReport: LaunchReport? = null
        private set
    var preview: Bitmap? = null
        private set
    var status: String = ""
        private set

    private val listeners = ArrayList<Listener>()
    private var session: ExternalAppSession? = null
    private var sessionWindowId = -1
    private var sessionBusy = false
    private var released = false

    // ---- modo ROOT
    private var rootClient: RootHelperClient? = null
    private var rootSession: RootAppSession? = null
    private var liveSurface: Surface? = null
    private var pendingRoot: AppEntry? = null
    private var pendingRootWindow = -1

    /** true quando há um app rodando no display virtual por root (a aba "Ao vivo" mostra e aceita toque). */
    val rootActive: Boolean get() = rootSession?.running == true

    init {
        repository.onChanged = { for (l in ArrayList(listeners)) l.onAppsChanged() }
        windows.onChanged = { for (l in ArrayList(listeners)) l.onWindowsChanged() }
    }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** A tela está visível: observa instalações e atualiza a lista. Escondida: para de observar (nenhum trabalho em segundo plano). */
    fun setWatching(on: Boolean) {
        if (released) return
        if (on) { repository.startWatching(); repository.refresh() } else repository.stopWatching()
    }

    private fun reportChanged() { for (l in ArrayList(listeners)) l.onReportChanged() }

    private fun setStatus(s: String) { status = s; reportChanged() }

    /**
     * "Abrir em janela 3D": cria/reaproveita a janela do app e TENTA a execução externa (prova de conceito).
     * Só existe uma sessão externa por vez nesta versão. Nada é repetido automaticamente.
     */
    fun open(app: AppEntry) {
        if (released) return
        if (sessionBusy) { setStatus("Aguarde: o teste anterior ainda está em andamento."); return }
        val w = windows.openFor(app.packageName, app.label)
        if (w == null) { setStatus("Limite de ${windows.maxWindows} janelas atingido. Feche uma janela primeiro."); return }

        val previousWindowId = sessionWindowId
        closeSession()                                   // sessão externa anterior (se havia) é encerrada
        if (previousWindowId != -1 && previousWindowId != w.id) {
            windows.setState(previousWindowId, AppRunState.CLOSED, "encerrada (só há 1 sessão externa por vez)")
        }
        windows.setState(w.id, AppRunState.STARTING, "testando a execução externa…")
        setStatus("Testando a abertura de “${app.label}” em um display virtual…")

        val s = ExternalAppSession(activity, app, object : ExternalAppSession.Callback {
            override fun onPreview(frame: Bitmap) {
                if (released) return
                preview = frame
                reportChanged()
            }

            override fun onFinished(report: LaunchReport) {
                sessionBusy = false
                if (released) return
                val v = report.verdict ?: Verdict.ERROR
                lastReport = report
                windows.setState(w.id, stateFor(v), v.short)
                if (!v.ok && v != Verdict.STARTED_NO_FRAMES && v != Verdict.STARTED_BLANK) {
                    // nada rodando no display virtual: a janela fica listada como bloqueada/falha para o usuário ver o motivo
                    if (sessionWindowId == w.id) { session?.close(); session = null; sessionWindowId = -1 }
                }
                setStatus(if (v.ok) "“${app.label}”: ${v.short}." else "“${app.label}”: ${v.short}. Veja a aba Relatório.")
            }
        })
        session = s
        sessionWindowId = w.id
        sessionBusy = true
        preview = null
        s.start()
    }

    private fun stateFor(v: Verdict): AppRunState = when (v) {
        Verdict.RENDERING, Verdict.ROOT_RUNNING -> AppRunState.RUNNING
        Verdict.STARTED_NO_FRAMES, Verdict.STARTED_BLANK, Verdict.ROOT_UNVERIFIED -> AppRunState.UNVERIFIED
        Verdict.UNSUPPORTED_DEVICE, Verdict.BLOCKED_BY_SYSTEM, Verdict.DENIED, Verdict.FELL_BACK_TO_MAIN_DISPLAY,
        Verdict.ROOT_LAUNCH_FAILED, Verdict.ROOT_WRONG_DISPLAY -> AppRunState.BLOCKED
        Verdict.DISPLAY_FAILED, Verdict.ERROR, Verdict.ROOT_UNAVAILABLE -> AppRunState.FAILED
    }

    /** Fecha a janela e, se ela era a da sessão externa, libera o display virtual. */
    fun closeWindow(id: Int) {
        if (id == sessionWindowId) closeSession()
        windows.close(id)
    }

    private fun closeSession() {
        val s = session
        val rs = rootSession
        session = null
        rootSession = null
        pendingRoot = null
        sessionWindowId = -1
        sessionBusy = false
        try { s?.close() } catch (t: Throwable) { }
        try { rs?.close() } catch (t: Throwable) { }
        preview = null
    }

    /** Fecha a janela/sessão que estiver ativa (botão "Fechar" da aba Ao vivo). */
    fun closeActive() {
        val id = sessionWindowId
        if (id != -1) closeWindow(id) else closeSession()
        reportChanged()
    }

    // ------------------------------------------------------------------ modo ROOT

    /**
     * "Abrir com root": cria/reaproveita a janela e abre o app num display virtual do StormVR, pelo helper root.
     * Precisa da superfície da aba "Ao vivo": se ela ainda não existe, a tentativa fica pendente e começa quando a aba aparecer.
     */
    fun openRoot(app: AppEntry) {
        if (released) return
        if (sessionBusy) { setStatus("Aguarde: o teste anterior ainda está em andamento."); return }
        val w = windows.openFor(app.packageName, app.label)
        if (w == null) { setStatus("Limite de ${windows.maxWindows} janelas atingido. Feche uma janela primeiro."); return }
        val previousWindowId = sessionWindowId
        closeSession()
        if (previousWindowId != -1 && previousWindowId != w.id) {
            windows.setState(previousWindowId, AppRunState.CLOSED, "encerrada (só há 1 sessão externa por vez)")
        }
        pendingRoot = app
        pendingRootWindow = w.id
        sessionWindowId = w.id
        windows.setState(w.id, AppRunState.STARTING, "aguardando a aba Ao vivo…")
        setStatus("Abra a aba “Ao vivo”: o app “${app.label}” começa assim que ela aparecer.")
        tryStartRoot()
    }

    private fun tryStartRoot() {
        val app = pendingRoot ?: return
        val surf = liveSurface
        if (surf == null || !surf.isValid) return
        pendingRoot = null
        val wid = pendingRootWindow
        val client = rootClient ?: RootHelperClient(activity.packageCodePath).also { rootClient = it }
        val rs = RootAppSession(activity, app, client, surf) { report -> onRootFinished(wid, app, report) }
        rootSession = rs
        sessionBusy = true
        preview = null
        windows.setState(wid, AppRunState.STARTING, "iniciando com root…")
        setStatus("Iniciando “${app.label}” com root (a 1ª vez pode pedir permissão no gerenciador de root)…")
        rs.start()
    }

    private fun onRootFinished(wid: Int, app: AppEntry, report: LaunchReport) {
        sessionBusy = false
        if (released) return
        val v = report.verdict ?: Verdict.ERROR
        lastReport = report
        windows.setState(wid, stateFor(v), v.short)
        if (v != Verdict.ROOT_RUNNING && v != Verdict.ROOT_UNVERIFIED) {
            rootSession = null                      // a sessão já soltou o display sozinha
            if (sessionWindowId == wid) sessionWindowId = -1
        }
        setStatus(if (v.ok) "“${app.label}”: ${v.short}." else "“${app.label}”: ${v.short}. Veja a aba Relatório.")
    }

    /** A aba "Ao vivo" ganhou superfície (visível). */
    fun attachLive(s: Surface) {
        liveSurface = s
        rootSession?.setSurface(s)
        tryStartRoot()
    }

    /** A aba "Ao vivo" perdeu a superfície (escondida): o app continua, só para de aparecer. */
    fun detachLive() {
        liveSurface = null
        rootSession?.setSurface(null)
    }

    /** Toque na aba Ao vivo, em pixels do display virtual. */
    fun sendTouch(action: Int, x: Int, y: Int) { rootSession?.touch(action, x, y) }

    /** Tecla no app (ex.: 4 = Voltar). */
    fun sendKey(code: Int) { rootSession?.key(code) }

    fun release() {
        if (released) return
        released = true
        closeSession()
        try { rootClient?.close() } catch (t: Throwable) { }       // o QUIT entra depois do STOP já enfileirado pelo closeSession
        rootClient = null
        windows.onChanged = null
        windows.closeAll()
        repository.release()
        listeners.clear()
    }
}
