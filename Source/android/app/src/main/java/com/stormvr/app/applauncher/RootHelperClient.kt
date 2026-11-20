package com.stormvr.app.applauncher

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lado do app do helper root: abre `su`, executa o [RootHelper] (mesmo APK, via app_process) e conversa por stdin/stdout
 * (sem sockets, então SELinux não atrapalha). Um helper por [AppLauncher].
 *
 * [ensureStarted] e [awaitReply]/[request] BLOQUEIAM: chame de thread de fundo. [send] e [close] não bloqueiam.
 * O Magisk/KernelSU pode mostrar o pedido de permissão root na primeira vez (o tempo de espera cobre isso).
 */
class RootHelperClient(private val apkPath: String) {
    companion object { private const val TAG = "StormVR/AppLauncher" }

    private var proc: Process? = null
    private val replies = LinkedBlockingQueue<String>()
    private val outQ = LinkedBlockingQueue<String>()
    private val errText = StringBuilder()
    @Volatile private var alive = false
    private var shizuku: com.stormvr.app.shizuku.IShellService? = null      // sem root: o helper roda dentro do processo do Shizuku
    val usingShizuku: Boolean get() = shizuku != null

    /** Linha READY do helper (uid, sdk, modo de injeção). */
    @Volatile
    var info: String = ""
        private set

    val isAlive: Boolean get() = alive

    /** null = pronto. Senão, o motivo da falha. */
    @Synchronized
    fun ensureStarted(): String? {
        if (alive) return null
        val rootErr = startRoot()
        if (rootErr == null) return null
        // sem root (ou root negado): tenta o Shizuku (Android 11+, depuração sem fio)
        val sErr = startShizuku()
        if (sErr == null) return null
        return "$rootErr | Shizuku: $sErr"
    }

    private fun startShizuku(): String? {
        if (!com.stormvr.app.shizuku.ShizukuManager.isReady()) return com.stormvr.app.shizuku.ShizukuManager.notReadyMessage() + " (abra Configurações › Shizuku)"
        val svc = com.stormvr.app.shizuku.ShizukuManager.privilegedService(10_000) ?: return "o processo privilegiado do Shizuku não abriu"
        val ready = try { svc.helper("READY?") } catch (t: Throwable) { return "falha ao falar com o Shizuku: ${t.message}" }
        if (ready.isNullOrEmpty() || !ready.startsWith("READY")) return "o helper dentro do Shizuku não respondeu"
        replies.clear(); outQ.clear()
        shizuku = svc
        info = ready.replace("READY", "READY(Shizuku)")
        alive = true
        thread(isDaemon = true, name = "AppLauncher-shizuku-in") { shizukuLoop(svc) }
        return null
    }

    private fun shizukuLoop(svc: com.stormvr.app.shizuku.IShellService) {
        try {
            while (true) {
                val l = outQ.take()
                if (l == "QUIT") break
                val first = l.substringBefore(' ')
                if (first == "T" || first == "K") svc.helperSend(l)
                else { val r = svc.helper(l); if (!r.isNullOrEmpty()) replies.offer(r) }
            }
        } catch (t: Throwable) { Log.w(TAG, "Shizuku helper caiu: ${t.message}") }
        alive = false
        shizuku = null
    }

    private fun startRoot(): String? {
        shutdownProcess()
        replies.clear(); outQ.clear(); errText.setLength(0)
        val p = try { Runtime.getRuntime().exec("su") } catch (t: Throwable) { return "su não encontrado (${t.message})" }
        proc = p
        val ready = LinkedBlockingQueue<String>()

        thread(isDaemon = true, name = "AppLauncher-root-out") {
            try {
                BufferedReader(InputStreamReader(p.inputStream, "UTF-8")).use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        if (l.startsWith("READY")) ready.offer(l)
                        else if (l.startsWith("EVT")) Log.i(TAG, "helper: $l")
                        else replies.offer(l)
                    }
                }
            } catch (t: Throwable) { }
            alive = false
        }
        thread(isDaemon = true, name = "AppLauncher-root-err") {
            try {
                BufferedReader(InputStreamReader(p.errorStream, "UTF-8")).use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        Log.w(TAG, "helper stderr: $l")
                        if (errText.length < 600) errText.append(l).append(' ')
                    }
                }
            } catch (t: Throwable) { }
        }

        try {
            val os = p.outputStream
            os.write(("CLASSPATH='" + apkPath + "' exec /system/bin/app_process /system/bin --nice-name=stormvr-helper " +
                "com.stormvr.app.applauncher.RootHelper\n").toByteArray())
            os.flush()
        } catch (t: Throwable) {
            shutdownProcess()
            return "não consegui falar com o su (${t.message})"
        }
        val line = ready.poll(25, TimeUnit.SECONDS)
        if (line == null) {
            val why = errText.toString().trim()
            shutdownProcess()
            return "o helper root não respondeu em 25 s (root negado, su sem suporte a stdin ou app_process bloqueado)" + (if (why.isNotEmpty()) ": $why" else "")
        }
        info = line
        alive = true
        thread(isDaemon = true, name = "AppLauncher-root-in") { writerLoop(p) }
        return null
    }

    private fun writerLoop(p: Process) {
        try {
            val os = p.outputStream
            while (true) {
                val l = outQ.take()
                os.write((l + "\n").toByteArray())
                if (outQ.isEmpty()) os.flush()
                if (l == "QUIT") break
            }
        } catch (t: Throwable) { }
    }

    /** Enfileira um comando sem esperar. [droppable] = pode ser descartado se a fila estiver cheia (movimentos de toque). */
    fun send(line: String, droppable: Boolean = false) {
        if (!alive) return
        if (droppable && outQ.size > 24) return
        outQ.offer(line)
    }

    /** Espera a próxima resposta que comece com [prefix] (ou "ERR"). null = tempo esgotado ou helper morto. */
    fun awaitReply(prefix: String, timeoutMs: Long): String? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (!alive) return null
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return null
            val r = replies.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (r.startsWith(prefix) || r.startsWith("ERR")) return r
        }
    }

    fun request(line: String, prefix: String, timeoutMs: Long): String? {
        if (!alive) return null
        outQ.offer(line)
        return awaitReply(prefix, timeoutMs)
    }

    /** Encerra sem bloquear: o QUIT entra depois dos comandos já enfileirados (ex.: STOP) e o processo é derrubado após 6 s no máximo. */
    fun close() {
        if (shizuku != null) { if (alive) outQ.offer("QUIT"); return }
        val p = proc ?: return
        if (alive) outQ.offer("QUIT")
        thread(isDaemon = true, name = "AppLauncher-root-reap") {
            try { if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroy() } catch (t: Throwable) { try { p.destroy() } catch (u: Throwable) { } }
        }
        proc = null
    }

    private fun shutdownProcess() {
        alive = false
        try { proc?.destroy() } catch (t: Throwable) { }
        proc = null
    }
}
