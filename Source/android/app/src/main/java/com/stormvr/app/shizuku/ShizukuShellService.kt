package com.stormvr.app.shizuku

import java.util.concurrent.TimeUnit

/**
 * UserService do Shizuku: esta classe roda num PROCESSO PRÓPRIO iniciado pelo Shizuku (uid shell/root), não no processo do app.
 * Só o [ShizukuShell] a cria (via Shizuku.bindUserService) e só depois de o StormVR estar autorizado.
 * Precisa de construtor sem argumentos (exigência do Shizuku).
 */
class ShizukuShellService() : IShellService.Stub() {

    override fun destroy() { System.exit(0) }

    override fun helper(line: String): String = com.stormvr.app.applauncher.RootHelper.handleLine(line) ?: ""

    override fun helperSend(line: String) { com.stormvr.app.applauncher.RootHelper.handleLine(line) }

    override fun exec(argv: Array<String>, timeoutMs: Long): Array<String> {
        return try {
            val p = ProcessBuilder(*argv).start()
            val out = StringBuilder(); val err = StringBuilder()
            val to = Thread { try { p.inputStream.bufferedReader().use { r -> pump(r, out) } } catch (_: Throwable) {} }
            val te = Thread { try { p.errorStream.bufferedReader().use { r -> pump(r, err) } } catch (_: Throwable) {} }
            to.start(); te.start()
            val done = p.waitFor(timeoutMs.coerceIn(500L, 120_000L), TimeUnit.MILLISECONDS)
            if (!done) { p.destroyForcibly(); return arrayOf("-1", out.toString(), "tempo esgotado (${timeoutMs} ms)") }
            to.join(1500); te.join(1500)
            arrayOf(p.exitValue().toString(), out.toString(), err.toString())
        } catch (t: Throwable) {
            arrayOf("-1", "", "falha ao executar: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** Limita a saída (o Binder aceita ~1 MB por transação). */
    private fun pump(r: java.io.Reader, sb: StringBuilder) {
        val buf = CharArray(4096)
        while (true) {
            val n = r.read(buf); if (n < 0) break
            if (sb.length < 200_000) sb.append(buf, 0, n)
        }
    }
}
