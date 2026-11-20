package com.stormvr.app.shizuku

/**
 * PORTA DE ENTRADA para qualquer função do StormVR que precise de privilégio. Nenhuma outra parte do app deve chamar a API do Shizuku.
 *
 * Como usar numa funcionalidade nova:
 *     if (PrivilegedOps.available) PrivilegedOps.run("cmd statusbar collapse") { r -> if (r.ok) ... else mostrar(r.text()) }
 *     else mostrar(PrivilegedOps.whyNot())      // ex.: "O StormVR ainda não foi autorizado no Shizuku."
 *
 * Regras: só roda com o serviço ativo e o StormVR autorizado (senão devolve erro SEM executar); nada aqui promete algo que o Shizuku
 * não permita — o que um comando consegue depende do uid do serviço (ADB/shell = 2000 ou root = 0) e das regras do Android.
 */
object PrivilegedOps {
    /** Dá para executar comandos privilegiados agora? (serviço rodando + autorizado) */
    val available: Boolean get() = ShizukuManager.isReady()

    /** Motivo legível de [available] estar falso. */
    fun whyNot(): String = ShizukuManager.notReadyMessage()

    /** Executa um comando shell (em segundo plano; [onResult] na thread principal). */
    fun run(command: String, timeoutMs: Long = 15_000, onResult: (ShellResult) -> Unit) = ShizukuManager.exec(command, timeoutMs, onResult)

    /** Versão bloqueante, para quem já está numa thread de trabalho. */
    fun runBlocking(command: String, timeoutMs: Long = 15_000): ShellResult = ShizukuManager.execBlocking(command, timeoutMs)

    /** Várias linhas em sequência; para no primeiro erro. Resultado = o do último comando executado. */
    fun runAll(commands: List<String>, timeoutMs: Long = 15_000, onResult: (ShellResult) -> Unit) {
        if (!available) { onResult(ShellResult(-1, "", "", whyNot())); return }
        Thread {
            var last = ShellResult(0, "", "")
            for (c in commands) { last = runBlocking(c, timeoutMs); if (!last.ok) break }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(last) }
        }.apply { isDaemon = true; name = "stormvr-privileged" }.start()
    }

    /** Quem está executando os comandos: "root", "ADB (shell)"... Útil para decidir o que tentar. */
    fun modeName(): String = ShizukuManager.info.modeName
    /** uid do serviço (0 = root, 2000 = shell). -1 se desconhecido. */
    fun uid(): Int = ShizukuManager.info.serverUid
}
