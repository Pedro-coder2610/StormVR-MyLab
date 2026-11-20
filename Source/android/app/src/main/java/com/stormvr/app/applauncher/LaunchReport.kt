package com.stormvr.app.applauncher

/** Resultado de uma tentativa de abrir um app externo num display virtual do StormVR. */
enum class Verdict(val short: String, val ok: Boolean) {
    UNSUPPORTED_DEVICE("o aparelho não declara suporte a Activities em displays secundários", false),
    DISPLAY_FAILED("não foi possível criar o display virtual", false),
    BLOCKED_BY_SYSTEM("o Android bloqueou: um APK comum não pode abrir app de terceiros em display virtual próprio", false),
    DENIED("o Android negou o início (SecurityException)", false),
    ERROR("erro ao iniciar o app", false),
    FELL_BACK_TO_MAIN_DISPLAY("o app parece ter aberto na tela principal (o StormVR perdeu o primeiro plano)", false),
    STARTED_NO_FRAMES("o app iniciou, mas nenhuma imagem chegou ao StormVR", false),
    STARTED_BLANK("o app iniciou, mas as imagens recebidas estão em branco/uniformes", false),
    RENDERING("o app está renderizando no display virtual do StormVR", true),

    // ---- modo ROOT
    ROOT_UNAVAILABLE("root/su indisponível ou o helper não iniciou", false),
    ROOT_LAUNCH_FAILED("o Android recusou o início mesmo com root", false),
    ROOT_WRONG_DISPLAY("com root, o app abriu em outro display (não no virtual do StormVR)", false),
    ROOT_UNVERIFIED("com root, o início foi aceito, mas não consegui confirmar o app no display (olhe a aba Ao vivo)", false),
    ROOT_RUNNING("com root, o app está rodando no display virtual do StormVR (confirmado pelo dumpsys)", true)
}

/** Relatório legível (pt-BR) de uma tentativa: cada linha é um fato MEDIDO ou "não avaliável" — nada é presumido. */
class LaunchReport {
    var verdict: Verdict? = null
    private val lines = ArrayList<String>()

    fun add(line: String) { lines.add(line) }
    fun text(): String = lines.joinToString("\n")
}
