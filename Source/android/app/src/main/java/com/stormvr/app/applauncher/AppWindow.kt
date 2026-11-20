package com.stormvr.app.applauncher

/** Estado de execução de uma janela de aplicativo. */
enum class AppRunState(val label: String) {
    CREATED("criada"),
    STARTING("iniciando"),
    RUNNING("executando"),
    UNVERIFIED("iniciou, sem imagem verificada"),
    BLOCKED("bloqueada pelo Android"),
    FAILED("falhou"),
    CLOSED("fechada")
}

/**
 * Uma janela 3D de aplicativo externo (apenas o MODELO: ainda não é desenhada pelo renderizador, veja RELATORIO_APP_LAUNCHER.md).
 *
 * Mundo da sala, em metros — mesma convenção do VrState: X direita, Y frente, Z cima.
 * [yawDeg] gira em torno de Z no mesmo sentido do azimute (de +Y para +X); 0 = de frente para a origem, vista de +Y.
 * Acesso só pela thread principal (a thread GL deve receber uma cópia/instantâneo quando o desenho existir).
 */
class AppWindow(val id: Int, val packageName: String, val appName: String) {
    var x = 0f
    var y = 2.2f
    var z = 1.6f
    var yawDeg = 0f
    var pitchDeg = 0f
    var rollDeg = 0f
    var widthM = 1.6f
    var heightM = 0.9f

    /** Resolução e densidade da superfície própria da janela (para o display virtual quando houver). */
    var pixelWidth = 1280
    var pixelHeight = 720
    var dpi = 240

    var visible = true
    var focused = false
    var state = AppRunState.CREATED
    var status = ""

    /** Posição "na grade" ao redor do usuário (0 = frente, 1 = direita, 2 = esquerda...). Usada só para o posicionamento inicial. */
    internal var slot = 0

    /** Superfície própria da janela (criada sob demanda; ainda não ligada ao renderizador). */
    var surface: AppWindowSurface? = null
        private set

    fun ensureSurface(): AppWindowSurface {
        val s = surface
        if (s != null) return s
        val n = AppWindowSurface(pixelWidth, pixelHeight)
        surface = n
        return n
    }

    fun releaseSurface() {
        try { surface?.release() } catch (t: Throwable) { }
        surface = null
    }
}
