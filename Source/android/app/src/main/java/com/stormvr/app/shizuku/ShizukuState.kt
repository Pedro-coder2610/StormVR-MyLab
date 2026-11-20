package com.stormvr.app.shizuku

/** Estado do Shizuku do ponto de vista do StormVR. "Instalado" NUNCA basta: só [CONNECTED] significa que o StormVR pode usar privilégios. */
enum class ShizukuState {
    /** App Shizuku não está instalado neste aparelho. */
    NOT_INSTALLED,
    /** Instalado, mas o serviço não está rodando (precisa ser iniciado por depuração sem fio, ADB ou root). */
    NOT_RUNNING,
    /** Serviço rodando, mas versão antiga (pré-v11) — sem a API que o StormVR usa. Atualize o Shizuku. */
    OUTDATED,
    /** Serviço rodando; falta o usuário autorizar o StormVR (ou a autorização foi negada/perdida). */
    WAITING_PERMISSION,
    /** Serviço rodando E StormVR autorizado: privilégios disponíveis. */
    CONNECTED
}

/** Foto do estado num instante. Imutável: quem consome nunca precisa falar com a API do Shizuku. */
data class ShizukuInfo(
    val state: ShizukuState,
    val installed: Boolean,
    val serviceRunning: Boolean,
    val authorized: Boolean,
    /** Versão do serviço Shizuku (-1 se desconhecida). */
    val serverVersion: Int = -1,
    /** uid do serviço: 0 = root, 2000 = shell (ADB / depuração sem fio), -1 = desconhecido. */
    val serverUid: Int = -1,
    /** O usuário negou a autorização (nesta sessão). */
    val denied: Boolean = false,
    /** Negou marcando "não perguntar de novo": a caixa de autorização não aparece mais; só no app Shizuku (Apps autorizados). */
    val deniedPermanently: Boolean = false,
    /** O canal de comandos (processo privilegiado) está aberto e pronto. */
    val shellReady: Boolean = false,
    /** SDK do Android. Depuração sem fio (iniciar o Shizuku sem PC) exige Android 11+ (30). */
    val sdk: Int = 0
) {
    val wirelessDebuggingSupported: Boolean get() = sdk >= 30
    val modeName: String get() = when (serverUid) { 0 -> "root"; 2000 -> "ADB (shell)"; -1 -> "—"; else -> "uid $serverUid" }
}

/** Resultado de um comando executado no processo privilegiado. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val error: String? = null) {
    val ok: Boolean get() = error == null && exitCode == 0
    fun text(): String = if (error != null) error else (stdout.trim() + (if (stderr.isNotBlank()) "\n" + stderr.trim() else "")).trim()
}
