package com.stormvr.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * EXECUÇÃO DE COMANDOS: abre (sob demanda) o processo privilegiado do Shizuku — o UserService [ShizukuShellService] — e roda comandos nele.
 * Não decide quando pode rodar: quem decide é o [ShizukuManager] (só chama aqui depois de confirmar a autorização).
 * Ninguém fora do pacote shizuku usa esta classe: use [PrivilegedOps] ou [ShizukuManager].
 */
internal object ShizukuShell {
    private const val SERVICE_VERSION = 1
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "stormvr-shizuku-shell").apply { isDaemon = true } }

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false
    @Volatile private var latch: CountDownLatch? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var onStateChanged: (() -> Unit)? = null

    fun init(ctx: Context, changed: () -> Unit) {
        onStateChanged = changed
        args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShizukuShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(SERVICE_VERSION)
    }

    fun isReady(): Boolean = try { service?.asBinder()?.pingBinder() == true } catch (_: Throwable) { false }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = if (binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
            latch?.countDown()
            main.post { onStateChanged?.invoke() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; binding = false
            main.post { onStateChanged?.invoke() }
        }
    }

    /** Pede a abertura do processo privilegiado (chamar só com o StormVR autorizado). */
    @Synchronized
    fun bind() {
        if (isReady() || binding) return
        val a = args ?: return
        try {
            binding = true
            latch = CountDownLatch(1)
            Shizuku.bindUserService(a, conn)
        } catch (_: Throwable) { binding = false }
    }

    /** Fecha o processo privilegiado (perda de autorização, Shizuku encerrado ou pedido do usuário). */
    @Synchronized
    fun unbind() {
        val a = args
        try { if (a != null) Shizuku.unbindUserService(a, conn, true) } catch (_: Throwable) {}
        service = null; binding = false
        latch?.countDown()
    }

    /** Descarta a referência sem falar com o Shizuku (o servidor já morreu). */
    fun forget() { service = null; binding = false; latch?.countDown() }

    /** Serviço privilegiado pronto (abre se preciso). NÃO chamar na thread principal. */
    fun connect(timeoutMs: Long): IShellService? {
        if (!isReady()) {
            bind()
            latch?.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        return if (isReady()) service else null
    }

    /** Roda [argv] em segundo plano; [cb] volta na thread principal. */
    fun exec(argv: Array<String>, timeoutMs: Long, cb: (ShellResult) -> Unit) {
        pool.execute {
            val r = execBlocking(argv, timeoutMs)
            main.post { cb(r) }
        }
    }

    /** NÃO chamar na thread principal. */
    fun execBlocking(argv: Array<String>, timeoutMs: Long): ShellResult {
        if (!isReady()) {
            bind()
            latch?.await(8, TimeUnit.SECONDS)
        }
        val s = service ?: return ShellResult(-1, "", "", "O processo privilegiado do Shizuku não abriu (tente \"Verificar novamente\").")
        return try {
            val o = s.exec(argv, timeoutMs)
            ShellResult(o.getOrNull(0)?.toIntOrNull() ?: -1, o.getOrNull(1) ?: "", o.getOrNull(2) ?: "")
        } catch (t: Throwable) {
            service = null
            ShellResult(-1, "", "", "Falha de comunicação com o Shizuku: " + (t.message ?: t.javaClass.simpleName))
        }
    }
}
