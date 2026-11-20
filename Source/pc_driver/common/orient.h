// orient.h - matematica de orientacao do StormVR (C++ puro, SEM windows.h / OpenVR: pode ser testada em qualquer compilador).
//  * Quaternions (w,x,y,z) e conversao dos angulos do ESP32 (roll/pitch/yaw, sequencia Z-Y-X) para quaternion SEM gimbal lock.
//  * Troca de base (mapeamento dos eixos do MPU -> eixos do SteamVR) feita no quaternion.
//  * Velocidade angular a partir de amostras de orientacao + previsao (extrapolacao) do movimento.
//  * Filtro "One Euro" para quaternions (suavizacao adaptativa: parado = estavel, movendo = quase sem atraso).
//  * Modo "esfera" do Arm Model: o controle gira em volta de um ponto (como se estivesse preso por um cabo).
#pragma once
#include <cmath>
#include <algorithm>
// windows.h define min/max como macros e quebra std::min/std::max: limpa se vier antes deste arquivo
#ifdef min
#undef min
#endif
#ifdef max
#undef max
#endif

namespace kbt { namespace ori {

constexpr double kPi = 3.14159265358979323846;
constexpr double kD2R = kPi / 180.0;

struct Q { double w = 1, x = 0, y = 0, z = 0; };

inline Q mul(const Q& a, const Q& b) {
    return { a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
             a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
             a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
             a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w };
}
inline Q conj(const Q& a) { return { a.w, -a.x, -a.y, -a.z }; }
inline double dot(const Q& a, const Q& b) { return a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z; }
inline Q normalize(const Q& a) {
    const double n = std::sqrt(a.w * a.w + a.x * a.x + a.y * a.y + a.z * a.z);
    if (n < 1e-12) return Q{};
    return { a.w / n, a.x / n, a.y / n, a.z / n };
}
inline Q fromAxisAngle(double ax, double ay, double az, double rad) {
    const double s = std::sin(rad * 0.5);
    return { std::cos(rad * 0.5), ax * s, ay * s, az * s };
}
inline Q rotX(double rad) { return fromAxisAngle(1, 0, 0, rad); }
inline Q rotY(double rad) { return fromAxisAngle(0, 1, 0, rad); }
inline Q rotZ(double rad) { return fromAxisAngle(0, 0, 1, rad); }

// Angulos do firmware (graus): roll = giro em X, pitch = giro em Y, yaw = giro em Z; q = Rz(yaw) * Ry(pitch) * Rx(roll).
// E exatamente o inverso de Madgwick::euler() do ESP32. Nao tem singularidade: com pitch = +-90 continua dando um quaternion valido.
inline Q fromEulerZYX(double rollDeg, double pitchDeg, double yawDeg) {
    const double cr = std::cos(rollDeg * kD2R * 0.5), sr = std::sin(rollDeg * kD2R * 0.5);
    const double cp = std::cos(pitchDeg * kD2R * 0.5), sp = std::sin(pitchDeg * kD2R * 0.5);
    const double cy = std::cos(yawDeg * kD2R * 0.5), sy = std::sin(yawDeg * kD2R * 0.5);
    return { cr * cp * cy + sr * sp * sy,
             sr * cp * cy - cr * sp * sy,
             cr * sp * cy + sr * cp * sy,
             cr * cp * sy - sr * sp * cy };
}
// Mesmas formulas do firmware (para testes)
inline void toEulerZYX(const Q& q, double& rollDeg, double& pitchDeg, double& yawDeg) {
    rollDeg = std::atan2(2 * (q.w * q.x + q.y * q.z), 1 - 2 * (q.x * q.x + q.y * q.y)) / kD2R;
    double sp = 2 * (q.w * q.y - q.z * q.x); sp = (std::max)(-1.0, (std::min)(1.0, sp));
    pitchDeg = std::asin(sp) / kD2R;
    yawDeg = std::atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y * q.y + q.z * q.z)) / kD2R;
}

inline void rotate(const Q& q, const double v[3], double out[3]) {   // v rotacionado por q
    const Q p = mul(mul(q, Q{ 0, v[0], v[1], v[2] }), conj(q));
    out[0] = p.x; out[1] = p.y; out[2] = p.z;
}
inline Q slerp(const Q& a, Q b, double t) {
    double d = dot(a, b);
    if (d < 0) { b = { -b.w, -b.x, -b.y, -b.z }; d = -d; }
    if (d > 0.9995) return normalize({ a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t });
    const double th = std::acos((std::min)(1.0, d)), s = std::sin(th);
    const double wa = std::sin((1 - t) * th) / s, wb = std::sin(t * th) / s;
    return { a.w * wa + b.w * wb, a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb };
}
// vetor de rotacao (rad) <-> quaternion
inline Q expMap(double wx, double wy, double wz) {
    const double a = std::sqrt(wx * wx + wy * wy + wz * wz);
    if (a < 1e-12) return normalize({ 1, wx * 0.5, wy * 0.5, wz * 0.5 });
    const double s = std::sin(a * 0.5) / a;
    return { std::cos(a * 0.5), wx * s, wy * s, wz * s };
}
inline void logMap(Q q, double out[3]) {
    if (q.w < 0) q = { -q.w, -q.x, -q.y, -q.z };
    const double vn = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
    if (vn < 1e-12) { out[0] = out[1] = out[2] = 0; return; }
    const double ang = 2.0 * std::atan2(vn, q.w);
    out[0] = q.x / vn * ang; out[1] = q.y / vn * ang; out[2] = q.z / vn * ang;
}
// yaw (giro em Y, SteamVR) da direcao "frente" (-Z) de q
inline double yawOfForward(const Q& q) {
    const double fx = -2 * (q.x * q.z + q.w * q.y), fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
    return std::atan2(-fx, -fz);
}

// ---------------------------------------------------------------- mapeamento de eixos (MPU -> SteamVR) feito no quaternion
// srcOf[a]: qual eixo do MPU (0 = roll/X, 1 = pitch/Y, 2 = yaw/Z; 3 = nenhum) comanda o eixo SteamVR a (0 = yaw/Y, 1 = pitch/X, 2 = roll/Z)
// (mesma ordem de CtlAxis). inv[a]: inverte. en[a]: ativo. Devolve false se a combinacao nao e uma rotacao fisica valida
// (eixo repetido/desativado/"nenhum" ou numero IMPAR de inversoes = espelhamento): nesse caso o chamador usa o modo antigo (Euler).
// P e a matriz 3x3 (linha-a-linha) que leva um vetor do corpo do MPU (x,y,z) para o mundo SteamVR (x,y,z).
inline bool makeBasis(const int srcOf[3], const bool inv[3], const bool en[3], double P[9]) {
    // eixo do SteamVR (CtlAxis) -> indice do eixo SteamVR: yaw=Y(1), pitch=X(0), roll=Z(2)
    const int svAxis[3] = { 1, 0, 2 };
    for (int i = 0; i < 9; ++i) P[i] = 0;
    bool used[3] = { false, false, false };
    for (int a = 0; a < 3; ++a) {
        const int s = srcOf[a];
        if (!en[a] || s < 0 || s > 2 || used[s]) return false;
        used[s] = true;
        P[svAxis[a] * 3 + s] = inv[a] ? -1.0 : 1.0;
    }
    const double det = P[0] * (P[4] * P[8] - P[5] * P[7]) - P[1] * (P[3] * P[8] - P[5] * P[6]) + P[2] * (P[3] * P[7] - P[4] * P[6]);
    return det > 0.5;
}
// q' = P q P^T : o vetor (eixo) do quaternion vai pela matriz, o angulo (w) fica igual
inline Q basisChange(const double P[9], const Q& q) {
    return { q.w, P[0] * q.x + P[1] * q.y + P[2] * q.z,
                  P[3] * q.x + P[4] * q.y + P[5] * q.z,
                  P[6] * q.x + P[7] * q.y + P[8] * q.z };
}

// ---------------------------------------------------------------- velocidade angular + previsao
// Alimentada com cada orientacao NOVA e o instante de captura (s). Estima a velocidade angular no referencial do CORPO (rad/s):
// w_body = log(q_prev^-1 * q_new) / dt, com filtro leve. Usa o relogio de CAPTURA (nao o de chegada do pacote): sem o jitter da rede.
struct AngVel {
    Q last; double lastT = 0; bool init = false;
    double w[3] = { 0, 0, 0 };
    void reset() { init = false; w[0] = w[1] = w[2] = 0; }
    // devolve true se aceitou a amostra
    bool push(const Q& q, double t) {
        if (!init) { last = q; lastT = t; init = true; return false; }
        const double dt = t - lastT;
        if (dt < 1e-4) return false;                       // amostra repetida / fora de ordem
        double d[3];
        logMap(mul(conj(last), q), d);
        const double ang = std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        last = q; lastT = t;
        if (ang > 1.2 || dt > 0.25) { w[0] = w[1] = w[2] = 0; return true; }   // salto (recentrar) ou muito espacado: sem velocidade
        const double a = 0.4;                              // mistura: reage rapido, tira ruido de quantizacao
        for (int i = 0; i < 3; ++i) w[i] += (d[i] / dt - w[i]) * a;
        return true;
    }
    // orientacao prevista t_ahead segundos a frente (rotacao no referencial do corpo)
    Q predict(const Q& q, double tAhead, double maxAhead = 0.08) const {
        const double t = (std::max)(0.0, (std::min)(tAhead, maxAhead));
        if (t <= 0) return q;
        return normalize(mul(q, expMap(w[0] * t, w[1] * t, w[2] * t)));
    }
};

// ---------------------------------------------------------------- buffer de jitter (interpolacao)
// Guarda as ultimas orientacoes recebidas (com o instante de CAPTURA) e devolve a orientacao no instante "agora - atraso", INTERPOLANDO
// (slerp) entre dois pacotes. Com 50 pacotes/s e Wi-Fi com rajadas isso elimina os "teleportes"/tremedeira que a extrapolacao causava:
// um pacote atrasado nao muda nada, so um pacote que atrase mais que o atraso cai na extrapolacao (limitada) pela velocidade angular.
struct QBuf {
    static constexpr int N = 8;
    struct S { double t; Q q; } s[N];
    int n = 0; double off = 0; bool offInit = false; double avgDt = 0.02, jit = 0.0;
    double lastArr = -1e9;                                            // instante (relogio local) do ultimo pacote ACEITO
    void reset() { n = 0; offInit = false; avgDt = 0.02; jit = 0.0; lastArr = -1e9; }
    // Fluxo "vivo": o ultimo pacote chegou ha pouco, na escala da taxa que o proprio remetente esta usando (funciona com 5 ou 500 pacotes/s).
    // Fora disso NAO se extrapola nada: a pose fica EXATAMENTE na ultima orientacao recebida ate chegar a proxima.
    bool fresh(double now) const { return n > 0 && (now - lastArr) < (std::min)(0.05, avgDt * 2.5 + 0.01); }
    // t = captura (s, relogio do remetente); arrive = chegada (s, relogio local)
    void push(const Q& q, double t, double arrive) {
        if (n > 0) {
            if (t < s[n - 1].t - 0.5) reset();                           // remetente reiniciou
            else if (t <= s[n - 1].t) return;                             // repetido / fora de ordem
        }
        const double d = arrive - t;
        if (!offInit) { off = d; offInit = true; }
        else if (d < off) off = d;                                        // o pacote mais rapido define a diferenca entre os relogios
        else off += (d - off) * 0.002;                                    // e ela sobe bem devagar (deriva dos relogios)
        { const double dev = (std::min)(d - off, 0.2); jit = (std::max)(dev, jit * 0.995); }   // pico recente de atraso da rede (cai devagar, ~3 s)
        if (n > 0) { const double dt = (std::max)(0.004, (std::min)(0.1, t - s[n - 1].t)); avgDt += (dt - avgDt) * 0.1; }
        if (n == N) { for (int i = 1; i < N; ++i) s[i - 1] = s[i]; n = N - 1; }
        s[n++] = { t, q };
        lastArr = arrive;
    }
    // atraso de reproducao: 1 pacote + margem proporcional ao jitter MEDIDO da rede (Wi-Fi ruim = mais atraso, mas sem pulos)
    double delay() const { return (std::max)(0.006, (std::min)(0.080, avgDt * 1.0 + 1.0 * jit)); }
    // w: velocidade angular (rad/s, referencial do corpo) usada so se faltar pacote (extrapolacao ate maxExtra s)
    bool sample(double now, const double w[3], Q& out, double maxExtra = 0.03) const {
        if (n == 0) return false;
        const double tr = now - off - delay();
        if (tr <= s[0].t) { out = s[0].q; return true; }
        for (int i = 0; i < n - 1; ++i) {
            if (tr <= s[i + 1].t) {
                const double span = s[i + 1].t - s[i].t;
                out = slerp(s[i].q, s[i + 1].q, span > 1e-9 ? (tr - s[i].t) / span : 1.0);
                return true;
            }
        }
        if (!fresh(now)) { out = s[n - 1].q; return true; }                // sem pacotes novos: segura a ultima pose (sem extrapolar)
        const double dt = (std::min)(tr - s[n - 1].t, maxExtra);
        out = normalize(mul(s[n - 1].q, expMap(w[0] * dt, w[1] * dt, w[2] * dt)));
        return true;
    }
};

// ---------------------------------------------------------------- One Euro para quaternion
// cutoffMin (Hz): quanto MENOR, mais suave quando parado. beta (Hz por rad/s): quanto MAIOR, menos atraso ao mover.
struct OneEuroQ {
    Q y; double dw = 0; bool init = false;
    void reset() { init = false; dw = 0; }
    static double alpha(double cutoff, double dt) { const double tau = 1.0 / (2.0 * kPi * cutoff); return 1.0 / (1.0 + tau / dt); }
    Q filter(const Q& x, double dt, double cutoffMin, double beta, double dCutoff = 1.0) {
        if (!init || dt <= 0) { y = x; init = true; dw = 0; return y; }
        double d[3];
        logMap(mul(conj(y), x), d);                          // erro entre a saida anterior e a entrada nova
        const double inst = std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) / dt;        // velocidade angular instantanea (rad/s)
        const double aD = alpha(dCutoff, dt);
        dw += (inst - dw) * aD;
        const double a = alpha(cutoffMin + beta * dw, dt);
        y = slerp(y, x, a);
        return y;
    }
};
// Suavizacao 0..100 (%) -> frequencia de corte minima (Hz). 0 = sem filtro, 100 = bem suave (~0,6 Hz).
inline double cutoffFromPercent(double pct) {
    pct = (std::max)(0.0, (std::min)(100.0, pct));
    if (pct <= 0.0) return 1e6;
    return 25.0 * std::pow(0.6 / 25.0, pct / 100.0);
}

// ---------------------------------------------------------------- Arm Model "SIMPLES" (esfera)
// O controle e como uma bola presa por um cabo de comprimento L ao ponto pivo (ombro): a posicao e pivo + L * (direcao em que ele aponta).
// Girar o controle 30 graus tambem leva a mao 30 graus sobre a esfera, na mesma direcao. fwd = frente do controle (unitario).
inline void armSphere(const double pivot[3], double len, const double fwd[3], double hand[3]) {
    for (int i = 0; i < 3; ++i) hand[i] = pivot[i] + fwd[i] * len;
}

}} // namespace kbt::ori
