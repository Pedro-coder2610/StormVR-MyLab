package com.stormvr.app.shizuku

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * GERENCIADOR CENTRAL DE PRIVILÉGIOS (Shizuku).
 *
 * Responsabilidades (e só estas):
 *  1. COMUNICAÇÃO com o serviço Shizuku (Binder): instalado? rodando? versão/uid? recebeu/perdeu o Binder?
 *  2. PERMISSÃO: o StormVR está autorizado? pedir autorização (só quando o usuário toca no botão); detectar perda/negação.
 *  3. MONITORAMENTO: avisa os ouvintes quando algo muda (Shizuku iniciado/encerrado, autorização concedida/perdida) e reabre o canal de comandos.
 *  4. Porta de entrada dos COMANDOS: [exec] só roda depois de confirmar a autorização (a execução em si está em [ShizukuShell]).
 *
 * Nunca considera "conectado" só porque o app Shizuku está instalado. Nunca pede autorização sozinho (ela exige confirmação do usuário).
 * O resto do StormVR NÃO fala com a API do Shizuku: usa [PrivilegedOps] (ou este objeto). Não há ADB próprio: tudo passa pelo Shizuku.
 */
object ShizukuManager {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 4242
    private const val POLL_MS = 2500L

    fun interface Listener { fun onShizukuChanged(info: ShizukuInfo) }

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var inited = false
    private var deniedNow = false
    private var shellWanted = false           // alguém já usou comandos: se o canal cair, reabre quando der
    @Volatile var info: ShizukuInfo = ShizukuInfo(ShizukuState.NOT_INSTALLED, false, false, false)
        private set

    // ------------------------------------------------------------------ ciclo de vida
    /** Chamar uma vez (StormApp.onCreate). Registra os ouvintes oficiais do Shizuku. */
    fun init(application: Application) {
        if (inited) return
        inited = true
        app = application
        ShizukuShell.init(application) { refresh() }
        try {
            Shizuku.addBinderReceivedListenerSticky { onBinderReceived() }          // serviço Shizuku (re)iniciou e entregou o Binder
            Shizuku.addBinderDeadListener { onBinderDead() }                        // serviço Shizuku encerrou
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQ_CODE) main.post { onPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED) }
            }
        } catch (_: Throwable) { /* API ausente: o estado fica NOT_RUNNING/NOT_INSTALLED */ }
        refresh()
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        if (listeners.size == 1) main.postDelayed(poll, POLL_MS)     // enquanto alguma tela olha, confere de tempos em tempos (não existe aviso de "autorização revogada")
        l.onShizukuChanged(info)
    }
    fun removeListener(l: Listener) { listeners.remove(l); if (listeners.isEmpty()) main.removeCallbacks(poll) }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (listeners.isNotEmpty()) main.postDelayed(this, POLL_MS)
        }
    }

    // ------------------------------------------------------------------ perguntas simples
    fun isInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Throwable) { false }

    fun isServiceRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    /** Autorização REAL concedida ao StormVR (só vale com o serviço rodando). */
    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Pronto para comandos: serviço rodando + StormVR autorizado. */
    fun isReady(): Boolean = hasPermission()

    // ------------------------------------------------------------------ estado
    /** Relê tudo no Shizuku e avisa os ouvintes se mudou. Seguro chamar de qualquer tela. */
    fun refresh(): ShizukuInfo {
        if (!inited) return info
        val installed = isInstalled()
        var running = false; var preV11 = false; var authorized = false; var perm = false
        var ver = -1; var uid = -1
        try {
            running = Shizuku.pingBinder()
            if (running) {
                preV11 = Shizuku.isPreV11()
                try { ver = Shizuku.getVersion() } catch (_: Throwable) {}
                try { uid = Shizuku.getUid() } catch (_: Throwable) {}
                if (!preV11) {
                    authorized = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    if (!authorized) perm = Shizuku.shouldShowRequestPermissionRationale()       // "negar e não perguntar de novo"
                }
            }
        } catch (_: Throwable) { running = false; authorized = false }
        if (authorized) deniedNow = false
        val state = when {
            running && preV11 -> ShizukuState.OUTDATED
            running && authorized -> ShizukuState.CONNECTED
            running -> ShizukuState.WAITING_PERMISSION
            installed -> ShizukuState.NOT_RUNNING
            else -> ShizukuState.NOT_INSTALLED
        }
        // perdeu a autorização ou o serviço: fecha o canal de comandos (não pode haver comando privilegiado sem autorização)
        if (state != ShizukuState.CONNECTED && ShizukuShell.isReady()) ShizukuShell.unbind()
        // voltou a ficar autorizado e alguém já usava comandos: reabre sozinho (reconexão)
        if (state == ShizukuState.CONNECTED && shellWanted && !ShizukuShell.isReady()) ShizukuShell.bind()
        val n = ShizukuInfo(state, installed, running, authorized, ver, uid, deniedNow && !authorized, perm && !authorized, ShizukuShell.isReady(), Build.VERSION.SDK_INT)
        if (n != info) { info = n; for (l in listeners.toList()) l.onShizukuChanged(n) }
        return info
    }

    private fun onBinderReceived() { main.post { refresh() } }
    private fun onBinderDead() { ShizukuShell.forget(); main.post { refresh() } }
    private fun onPermissionResult(granted: Boolean) { deniedNow = !granted; refresh() }

    // ------------------------------------------------------------------ ações do USUÁRIO (botões)
    /** Abre a caixa de autorização do Shizuku. SÓ chamar a partir de um toque do usuário. Devolve false se não deu para pedir. */
    fun requestPermission(): Boolean {
        val i = refresh()
        if (i.state != ShizukuState.WAITING_PERMISSION) return false
        if (i.deniedPermanently) return false        // o Android/Shizuku nem mostra mais a caixa: o usuário precisa ir em "Apps autorizados" do Shizuku
        return try { deniedNow = false; Shizuku.requestPermission(REQ_CODE); true } catch (_: Throwable) { false }
    }

    /** Abre o app Shizuku (ou a página de instalação se ele não estiver instalado). */
    fun openShizuku(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        return try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); launch != null } catch (_: Throwable) { false }
    }

    /** Abre as Opções do desenvolvedor (onde fica a Depuração sem fio). */
    fun openDeveloperOptions(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ comandos privilegiados
    /**
     * Roda um comando shell no processo do Shizuku. Recusa (sem executar nada) se o serviço não estiver rodando ou o StormVR não estiver autorizado.
     * [onResult] volta na thread principal.
     */
    fun exec(command: String, timeoutMs: Long = 15_000, onResult: (ShellResult) -> Unit) {
        if (!hasPermission()) { refresh(); onResult(ShellResult(-1, "", "", notReadyMessage())); return }
        shellWanted = true
        ShizukuShell.exec(arrayOf("sh", "-c", command), timeoutMs, onResult)
    }

    /** Versão bloqueante (NÃO chamar na thread principal). */
    fun execBlocking(command: String, timeoutMs: Long = 15_000): ShellResult {
        if (!hasPermission()) { main.post { refresh() }; return ShellResult(-1, "", "", notReadyMessage()) }
        shellWanted = true
        return ShizukuShell.execBlocking(arrayOf("sh", "-c", command), timeoutMs)
    }

    /**
     * Canal direto com o processo privilegiado (para o abridor de apps sem root). Só devolve algo se o StormVR estiver AUTORIZADO.
     * NÃO chamar na thread principal.
     */
    fun privilegedService(timeoutMs: Long = 8000): IShellService? {
        if (!hasPermission()) return null
        shellWanted = true
        return ShizukuShell.connect(timeoutMs)
    }

    fun notReadyMessage(): String = when (info.state) {
        ShizukuState.NOT_INSTALLED -> "Shizuku não está instalado."
        ShizukuState.NOT_RUNNING -> "Shizuku não está ativado."
        ShizukuState.OUTDATED -> "Shizuku desatualizado: atualize o app Shizuku."
        ShizukuState.WAITING_PERMISSION -> "O StormVR ainda não foi autorizado no Shizuku."
        ShizukuState.CONNECTED -> "Shizuku conectado."
    }
}
