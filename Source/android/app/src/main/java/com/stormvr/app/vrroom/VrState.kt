package com.stormvr.app.vrroom

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Tipos de alvo que um laser pode estar apontando. */
object Hit {
    const val NONE = 0
    const val WIN = 1      // conteúdo do navegador
    const val TB = 2       // barra de ferramentas
    const val KB = 3       // teclado virtual
    const val BAR = 4      // barra de mover (embaixo da janela)
    const val CORNER = 5   // cantos de redimensionar
    const val KBBAR = 6    // barra de mover o teclado (embaixo dele)
}

/**
 * Retângulo de um painel. Para a janela/toolbar/barra, (cx, cz) são coordenadas na SUPERFÍCIE
 * (cx = coordenada de arco "s", cz = altura). Para o teclado, é posição no mundo (plano).
 */
class Geom {
    var cx = 0f
    var cy = 0f
    var cz = 0f
    var w = 1f
    var h = 1f

    fun set(cx: Float, cy: Float, cz: Float, w: Float, h: Float) {
        this.cx = cx; this.cy = cy; this.cz = cz; this.w = w; this.h = h
    }
}

/** Estado compartilhado entre a UI (main thread) e a thread GL. Mundo: X direita, Y frente, Z cima. */
class VrState {
    companion object {
        const val TEX_W = 1600      // resolução do display virtual do navegador
        const val TEX_H = 900
        const val TB_W = 3000       // textura da barra de ferramentas
        const val TB_H = 120
        const val KB_W = 1400       // textura do teclado
        const val KB_H = 610        // inclui a barra de mover embaixo das teclas
        const val KB_METERS = 2.2f  // largura do teclado no mundo
    }

    @Volatile var stereo = true
    @Volatile var recenter = true
    @Volatile var rotation = 0

    // opções
    @Volatile var curved = true
    @Volatile var sixDof = true
    @Volatile var arState = 0       // 0 desligado, 1 procurando, 2 rastreando
    @Volatile var handsOn = true     // rastreamento de mãos
    @Volatile var handLasers = true  // lasers saindo das mãos
    @Volatile var handState = 0      // 0 off, 1 carregando modelo, 2 procurando mãos, 3 rastreando
    @Volatile var passthrough = false // modo câmera: sem sala, mostra a imagem da câmera
    @Volatile var handAim = 0         // 0 = olho -> pinça (alinhada), 1 = braço (amplificada)
    @Volatile var lite = false        // modo leve: menos resolução/detalhes, mais FPS
    @Volatile var twoHands = false    // rastrear 2 mãos (mais pesado) em vez de 1
    @Volatile var handScale = 1f      // calibração do tamanho da mão (distância estimada)
    // Fonte das "mãos": 0 = mãos (MediaPipe), 1 = CONTROLE 6DoF COM BOLINHAS (celular-controle mostrando 5 bolinhas; ver DotDetector)
    @Volatile var handSource = 0
    @Volatile var dotsLeft = false            // a mão que o controle-bolinhas ocupa
    @Volatile var dotsScreenW = 71f           // largura da TELA do celular-controle (mm)
    @Volatile var dotsScreenH = 153f          // altura da TELA do celular-controle (mm)
    @Volatile var dotsDepthRaw = 0f           // última distância medida (m, sem a escala): usada na calibração
    @Volatile var dotsSeen = false
    @Volatile var imuPos = true       // posição 6DoF assistida pelo acelerômetro
    @Volatile var ipdMm = 64f         // distância entre os olhos usada na profundidade 3D
    @Volatile var sepMm = 0f          // ajuste da distância entre os CENTROS das duas imagens (mm, + = mais afastadas)
    @Volatile var sepOffFrac = 0f     // deslocamento de cada imagem como fração da largura da tela
    @Volatile var tolPct = 50         // tolerância de clique / estabilização do cursor (0..100)
    // ---- vindos da sala 3D do StormVR (agora uma sala só)
    @Volatile var fovDeg = 90f        // FOV vertical de cada olho no modo 3D (graus)
    @Volatile var k1 = 0f             // distorção radial da lente (0 = desligada); mesma convenção do driver SteamVR
    @Volatile var k2 = 0f
    @Volatile var sbs = false         // conteúdo 3D lado a lado: olho esquerdo = metade esquerda do vídeo, direito = metade direita
    @Volatile var pcMode = false      // "modo PC": envia cabeça e mãos ao driver do SteamVR
    @Volatile var pcSendPos = true    // enviar a POSIÇÃO das mãos
    @Volatile var pcSendPinch = true  // enviar a PINÇA (o driver a usa como trigger)
    @Volatile var fullscreen = false  // tela cheia: só a página/stream ocupando o celular inteiro, com toque direto
    @Volatile var fsFill = false      // tela cheia: esticar até as bordas (senão mantém a proporção 16:9)
    @Volatile var dwell = false       // clicar olhando (~1,2 s) nos botões e no teclado, para quem está sem controle/mãos
    val gaze = FloatArray(3)          // direção do olhar (frente da cabeça) no referencial da cena, escrita pela GL thread
    @Volatile var glFps = 0

    // mãos (índice 0 = esquerda, 1 = direita), em coordenadas da cena: 21 pontos x (x,y,z)
    val handLock = Any()
    val handPts = Array(2) { FloatArray(63) }
    val handValid = BooleanArray(2)
    val handPinch = BooleanArray(2)
    val handRatio = floatArrayOf(1f, 1f)   // distância polegar-indicador / tamanho da mão
    val handClosing = BooleanArray(2)      // pinça se fechando (cursor deve congelar)
    // bolinha da pinça = cursor (na linha olho -> alvo, na distância da mão)
    val ball = Array(2) { FloatArray(3) }
    val ballOn = BooleanArray(2)

    // estado "gatilho apertado" (gamepad OU pinça) e origem de cada laser
    val pressed = BooleanArray(2)
    val lox = floatArrayOf(-0.28f, 0.28f)
    val loy = floatArrayOf(0.45f, 0.45f)
    val loz = floatArrayOf(-0.40f, -0.40f)
    val laserHand = BooleanArray(2)
    // origem do raio de seleção (olho nos modos de mão; mão do controle nos demais)
    val rox = floatArrayOf(-0.28f, 0.28f)
    val roy = floatArrayOf(0.45f, 0.45f)
    val roz = floatArrayOf(-0.40f, -0.40f)

    // posição da cabeça no referencial da cena (6DoF), escrita pela GL thread
    // extras do 3DoF (inclinar a cabeça = andar para o lado; barômetro = agachar): ligados nas opções, lidos pelo renderizador e pelo envio ao PC
    @Volatile var screenHidden = false   // tela da telona fechada (a barra de baixo continua)
    @Volatile var freezeHead = false     // StormSaber de cabeça fixa: a vista não acompanha o 3DoF
    @Volatile var extraLean = 0f
    @Volatile var extraDrop = 0f
    @Volatile var extrasActive = false
    @Volatile var extrasSend = true
    @Volatile var headX = 0f
    @Volatile var headY = 0f
    @Volatile var headZ = 0f

    // Pose 6DoF da cabeça publicada pela MESMA rotina da Sala 3D (updateHead). O envio ao SteamVR lê só isto:
    // é o único cálculo de rastreamento do app. Eixos do SteamVR: x direita, y cima, -z frente (metros).
    val poseLock = Object()                      // notifyAll a cada pose nova: o envio acorda na hora (sem sleep fixo)
    val headQ = floatArrayOf(1f, 0f, 0f, 0f)     // w, x, y, z
    val headPosSv = FloatArray(3)
    @Volatile var headPosOk = false              // posição medida (ARCore TRACKING + 6DoF ligado)
    @Volatile var headStampUs = 0L               // instante da pose (SystemClock.elapsedRealtimeNanos()/1000)
    @Volatile var headRecenterCount = 0
    @Volatile var poseCount = 0L
    @Volatile var handMaxFps = 30                // teto de quadros/s do rastreamento de mãos (menos calor; 15-60)
    @Volatile var clickFreezeMs = 120            // ao apertar o gatilho o ponteiro fica parado esse tempo (ms) para o clique não virar arrasto
    @Volatile var headHz = 100                   // taxa de envio da cabeça ao PC (Hz), independente do FPS da tela/câmera
    @Volatile var pinchSmooth = 0.5f             // 0..1: suavização da bolinha da pinça (0 = cola nos dedos, 1 = bem suave)
    @Volatile var predMs = 30f                   // previsão da rotação da cabeça (ms)
    @Volatile var trackOnly = false              // "Iniciar rastreamento": sem desenhar a Sala 3D

    // janela
    @Volatile var width = 3.6f
    @Volatile var distance = 3.0f
    @Volatile var wx = 0f
    @Volatile var wz = 0.5f
    @Volatile var kbVisible = false

    // posição/rotação da TELA (graus): órbita em volta do usuário (azimute + = direita, elevação + = para cima) e giro da tela sobre o
    // próprio centro nos 3 eixos (yaw = vertical, pitch = horizontal, roll = eixo que sai da tela). Tudo 0 = tela reta à frente.
    @Volatile var scrAz = 0f
    @Volatile var scrEl = 0f
    @Volatile var scrYaw = 0f
    @Volatile var scrPitch = 0f
    @Volatile var scrRoll = 0f

    // órbita do TECLADO em volta do usuário (graus): azimute + = direita, elevação + = para cima. 0 = onde ele sempre ficou.
    @Volatile var kbAz = 0f
    @Volatile var kbEl = 0f
    /** Rotação do teclado (row-major 3x3, preenchida em updateGeom): colunas = direita, frente, cima do teclado. */
    val kbRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // sticks (x direita +, y baixo +) e gatilhos 0..1
    @Volatile var lx = 0f
    @Volatile var ly = 0f
    @Volatile var rx = 0f
    @Volatile var ry = 0f
    @Volatile var l2 = 0f
    @Volatile var r2 = 0f

    // ponto de mira de cada laser NA SUPERFÍCIE: ax = coordenada de arco (m), az = altura (m)
    val ax = floatArrayOf(-0.7f, 0.7f)
    val az = floatArrayOf(0.5f, 0.5f)
    val lastMove = LongArray(2)

    // resultado do raycast (preenchido pela UI thread, lido pela GL thread)
    val hitX = floatArrayOf(-0.7f, 0.7f)
    val hitY = floatArrayOf(3f, 3f)
    val hitZ = floatArrayOf(0.5f, 0.5f)
    val hoverKind = IntArray(2)
    val hoverIdx = IntArray(2) { -1 }

    val win = Geom()
    val tb = Geom()
    val kb = Geom()
    val bar = Geom()

    init { updateGeom() }

    fun handle() = width * 0.045f
    fun cornerS(i: Int) = wx + (if (i % 2 == 0) -1f else 1f) * (win.w / 2f + handle() * 0.35f)
    fun cornerZ(i: Int) = win.cz + (if (i < 2) 1f else -1f) * (win.h / 2f + handle() * 0.35f)

    /** Curvatura K = 1/R (0 = plano). */
    fun curveK(): Float = if (curved) 1f / (distance * 1.25f) else 0f

    private fun rx(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c) }
    private fun ry(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c) }
    private fun rz(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f) }
    private fun mm(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (i in 0..2) for (j in 0..2) o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return o
    }
    fun screenIdentity() = scrAz == 0f && scrEl == 0f && scrYaw == 0f && scrPitch == 0f && scrRoll == 0f

    /**
     * Transformação da tela (mundo = A·local + b): p' = O·(C + Rs·(p − C)), com C = centro da tela, Rs = giro sobre o próprio centro
     * (yaw·pitch·roll) e O = órbita em volta do usuário (azimute·elevação). [a] row-major 3x3, [b] 3.
     */
    fun screenXf(a: FloatArray, b: FloatArray) {
        val d = (Math.PI / 180.0).toFloat()
        val rs = mm(mm(rz(scrYaw * d), rx(scrPitch * d)), ry(scrRoll * d))
        val o = mm(rz(-scrAz * d), rx(scrEl * d))
        val cx = wx; val cy = distance; val cz = wz
        val rcx = rs[0] * cx + rs[1] * cy + rs[2] * cz
        val rcy = rs[3] * cx + rs[4] * cy + rs[5] * cz
        val rcz = rs[6] * cx + rs[7] * cy + rs[8] * cz
        val tx = cx - rcx; val ty = cy - rcy; val tz = cz - rcz
        val am = mm(o, rs)
        for (i in 0..8) a[i] = am[i]
        b[0] = o[0] * tx + o[1] * ty + o[2] * tz
        b[1] = o[3] * tx + o[4] * ty + o[5] * tz
        b[2] = o[6] * tx + o[7] * ty + o[8] * tz
    }

    /** Centro da tela já com a órbita/giro (para quem precisa saber para onde a tela aponta). */
    fun screenCenter(out: FloatArray) {
        val a = FloatArray(9); val b = FloatArray(3); screenXf(a, b)
        out[0] = a[0] * wx + a[1] * distance + a[2] * wz + b[0]
        out[1] = a[3] * wx + a[4] * distance + a[5] * wz + b[1]
        out[2] = a[6] * wx + a[7] * distance + a[8] * wz + b[2]
    }

    /** Ponto do mundo para a coordenada de superfície (s, z), deslocado [depth] m em direção ao usuário. */
    fun surfPoint(s: Float, z: Float, depth: Float, out: FloatArray) {
        surfLocal(s, z, depth, out)
        if (screenIdentity()) return
        val a = FloatArray(9); val b = FloatArray(3); screenXf(a, b)
        val x = out[0]; val y = out[1]; val zz = out[2]
        out[0] = a[0] * x + a[1] * y + a[2] * zz + b[0]
        out[1] = a[3] * x + a[4] * y + a[5] * zz + b[1]
        out[2] = a[6] * x + a[7] * y + a[8] * zz + b[2]
    }

    private fun surfLocal(s: Float, z: Float, depth: Float, out: FloatArray) {
        val k = curveK()
        if (k < 1e-4f) {
            out[0] = s
            out[1] = distance - depth
            out[2] = z
        } else {
            val phi = (s - wx) * k
            out[0] = wx + sin(phi) / k - sin(phi) * depth
            out[1] = distance - (1f - cos(phi)) / k - cos(phi) * depth
            out[2] = z
        }
    }

    /**
     * Interseção de um raio (origem o, direção d) com a superfície da janela (plana ou curva).
     * Devolve em out[0] = coordenada de arco s e out[1] = altura z.
     */
    fun intersectSurface(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        out: FloatArray
    ): Boolean {
        if (screenIdentity()) return intersectLocal(ox, oy, oz, dx, dy, dz, out)
        // leva o raio para as coordenadas LOCAIS da tela (inversa da transformação = transposta da rotação)
        val a = FloatArray(9); val b = FloatArray(3); screenXf(a, b)
        val px = ox - b[0]; val py = oy - b[1]; val pz = oz - b[2]
        val lox = a[0] * px + a[3] * py + a[6] * pz
        val loy = a[1] * px + a[4] * py + a[7] * pz
        val loz = a[2] * px + a[5] * py + a[8] * pz
        val ldx = a[0] * dx + a[3] * dy + a[6] * dz
        val ldy = a[1] * dx + a[4] * dy + a[7] * dz
        val ldz = a[2] * dx + a[5] * dy + a[8] * dz
        return intersectLocal(lox, loy, loz, ldx, ldy, ldz, out)
    }

    private fun intersectLocal(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        out: FloatArray
    ): Boolean {
        val k = curveK()
        if (k < 1e-4f) {
            if (dy < 0.05f) return false
            val t = (distance - oy) / dy
            if (t <= 0f) return false
            out[0] = ox + t * dx
            out[1] = oz + t * dz
            return true
        }
        val r = 1f / k
        val px = ox - wx
        val py = oy - (distance - r)
        val a = dx * dx + dy * dy
        if (a < 1e-6f) return false
        val b = 2f * (px * dx + py * dy)
        val c = px * px + py * py - r * r
        val disc = b * b - 4f * a * c
        if (disc < 0f) return false
        val t = (-b + sqrt(disc)) / (2f * a)
        if (t <= 0f) return false
        val x = px + t * dx
        val y = py + t * dy
        if (y < 0.1f * r) return false
        val phi = atan2(x, y)
        if (abs(phi) > 1.4f) return false
        out[0] = wx + phi * r
        out[1] = oz + t * dz
        return true
    }

    fun updateGeom() {
        val w = width
        val h = w * TEX_H / TEX_W
        win.set(wx, distance, wz, w, h)
        val tbH = w * TB_H / TB_W
        val gap = w * 0.012f
        tb.set(wx, distance, wz - h / 2f - gap - tbH / 2f, w, tbH)
        val barH = w * 0.022f
        bar.set(wx, distance, tb.cz - tbH / 2f - gap - barH / 2f, w * 0.25f, barH)
        val ky = (distance - 0.8f).coerceAtLeast(1.0f)
        var kx: Float; var kyy: Float; var kz: Float; var kw: Float
        var px = 0f; var py = 0f; var pz = 0f                                    // pivô da órbita
        if (handsOn && handState >= 2) {
            // com as mãos ligadas o teclado fica ao alcance (a ~60 cm), para digitar tocando ou pinçando as teclas
            kx = headX; kyy = headY + 0.62f; kz = headZ - 0.12f; kw = 0.70f
            px = headX; py = headY; pz = headZ
        } else { kx = wx; kyy = ky; kz = -0.85f; kw = KB_METERS }
        val d = (Math.PI / 180.0).toFloat()
        val o = mm(rz(-kbAz * d), rx(kbEl * d))
        for (i in 0..8) kbRot[i] = o[i]
        val rx0 = kx - px; val ry0 = kyy - py; val rz0 = kz - pz
        kx = px + o[0] * rx0 + o[1] * ry0 + o[2] * rz0
        kyy = py + o[3] * rx0 + o[4] * ry0 + o[5] * rz0
        kz = pz + o[6] * rx0 + o[7] * ry0 + o[8] * rz0
        kb.set(kx, kyy, kz, kw, kw * KB_H / KB_W)
    }

    // zona morta + curva quadrática (movimentos pequenos = mais precisão)
    private fun curve(x: Float): Float {
        if (abs(x) < 0.12f) return 0f
        val d = sign(x) * (abs(x) - 0.12f) / 0.88f
        return d * abs(d)
    }

    /** O analógico controla a VELOCIDADE do laser; ao soltar, ele fica parado. */
    fun moveAim(dt: Float) {
        val speed = 0.55f * width   // metros por segundo (proporcional ao tamanho da janela)
        for (i in 0..1) {
            val sx = curve(if (i == 0) lx else rx)
            val sy = curve(if (i == 0) ly else ry)
            if (sx != 0f || sy != 0f) lastMove[i] = SystemClock.uptimeMillis()
            ax[i] = (ax[i] + sx * speed * dt).coerceIn(-7f, 7f)
            az[i] = (az[i] - sy * speed * dt).coerceIn(-3f, 3.6f)
        }
    }
}
