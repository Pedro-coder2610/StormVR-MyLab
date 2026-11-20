// driver_stormvr: cria um par de controles Oculus Touch (CV1) no SteamVR.
//  - Ate DOIS controles reais ao mesmo tempo (um por mao), em qualquer combinacao: ESP32 + celular, 2 celulares, 2 ESP32.
//    Cada controle e uma FONTE UDP (IP) com orientacao, botoes e analogico; o driver decide a mao de cada um
//    (automatico pela mao configurada no controle, por ordem de conexao ou por IP fixo).
//  - Mao sem controle real: teclado (modo "Auto" do menu).
// Configuracoes via VRSettings (secao driver_stormvr), editaveis pelo app stormvr_config.exe
// (aba na dashboard do SteamVR). Telemetria ao vivo e comandos do menu passam por memoria compartilhada.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <mmsystem.h>
#include <openvr_driver.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include "index_skeleton.h"
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include "../common/common.h"

using namespace vr;
using namespace kbt;

// ---------------------------------------------------------------- utilidades
static void DLog(const char* fmt, ...) {
    char buf[512];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf) - 2, fmt, ap);
    va_end(ap);
    strcat_s(buf, "\n");
    if (VRDriverLog()) VRDriverLog()->Log(buf);
}

static void DiagLog(const char* fmt, ...);   // grava no vrserver.txt E na aba Diagnostico

static bool GetB(const char* k, bool d) {
    EVRSettingsError e; bool v = VRSettings()->GetBool(kSection, k, &e);
    return e == VRSettingsError_None ? v : d;
}
static int GetI(const char* k, int d) {
    EVRSettingsError e; int v = VRSettings()->GetInt32(kSection, k, &e);
    return e == VRSettingsError_None ? v : d;
}
static float GetF(const FloatSetting& s) {
    EVRSettingsError e; float v = VRSettings()->GetFloat(kSection, s.key, &e);
    if (e != VRSettingsError_None) return s.def;
    return v < s.mn ? s.mn : (v > s.mx ? s.mx : v);
}
static bool GetB(const BoolSetting& s) { return GetB(s.key, s.def); }
static std::string GetS(const char* k) {
    char buf[160] = {}; EVRSettingsError e;
    VRSettings()->GetString(kSection, k, buf, sizeof(buf), &e);
    return e == VRSettingsError_None ? std::string(buf) : std::string();
}
static int  GetI(const IntSetting& s) {
    int v = GetI(s.key, s.def);
    return v < s.mn ? s.mn : (v > s.mx ? s.mx : v);
}
static void SetF(const FloatSetting& s, float v) { EVRSettingsError e; VRSettings()->SetFloat(kSection, s.key, v, &e); }
static void SetB(const char* k, bool v)          { EVRSettingsError e; VRSettings()->SetBool(kSection, k, v, &e); }
static void SetI(const char* k, int v)           { EVRSettingsError e; VRSettings()->SetInt32(kSection, k, v, &e); }
static void SetS(const char* k, const std::string& v) { EVRSettingsError e; VRSettings()->SetString(kSection, k, v.c_str(), &e); }

static bool KeyDown(int vk) { return vk > 0 && (GetAsyncKeyState(vk) & 0x8000) != 0; }
static float Clampf(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
static float Wrap180(float x) {
    x = std::fmod(x + 180.f, 360.f);
    if (x < 0) x += 360.f;
    return x - 180.f;
}
static constexpr double kDeg2Rad = 3.14159265358979 / 180.0;

static HmdQuaternion_t QuatMul(const HmdQuaternion_t& a, const HmdQuaternion_t& b) {
    HmdQuaternion_t r;
    r.w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
    r.x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
    r.y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
    r.z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
    return r;
}
static HmdQuaternion_t QuatConj(const HmdQuaternion_t& q) { return { q.w, -q.x, -q.y, -q.z }; }
static HmdQuaternion_t QuatAxisY(double a) { return { std::cos(a / 2), 0, std::sin(a / 2), 0 }; }
static HmdQuaternion_t QuatAxisX(double a) { return { std::cos(a / 2), std::sin(a / 2), 0, 0 }; }
static HmdQuaternion_t QuatAxisZ(double a) { return { std::cos(a / 2), 0, 0, std::sin(a / 2) }; }
// Yaw (giro em Y) de uma orientacao; frente = -z.
static double YawOf(const HmdQuaternion_t& q) { return std::atan2(2 * (q.x * q.z + q.w * q.y), 1 - 2 * (q.x * q.x + q.y * q.y)); }

// ------------------------------------------------------------ configuracao
// Ajustes de UM controle (a mao que ele ocupa): cada mao tem os seus, ver CtlSettings em common.h
struct CtlCfg {
    bool  axEn[C_COUNT] = {};
    float axSmooth[C_COUNT] = {};
    int   axSrc[C_COUNT] = {};
    bool  axInv[C_COUNT] = {};
    float axGain[C_COUNT] = {};
    float mpuDz[3] = {};
    float stickDz[2] = {}, stickGamma = 1.f, stickEdge = 0.95f, stickSens = 1.f;
    bool  stickInv[2] = {}, stickSwap = false;
    float trigRamp = 12.f, gripRamp = 12.f, clickThr = 0.9f, yawDrift = 0.f;
};

struct Config {
    bool  keysEnabled = true, follow = true, followRot = false, headFlip = false;
    float predictMs = 15.f;
    bool  enabled[HAND_COUNT] = { true, true };
    float moveSpeed = 0.5f, rotSpeed = 90.f, fixedHeight = 1.3f;
    float off[HAND_COUNT][3] = {};
    int   key[HAND_COUNT][A_COUNT] = {};
    int   kMove = kDefMoveMod, kDepth = kDefDepthMod, kToggle = kDefToggle;
    // ESP32 / MPU
    int   srcMode = 0, port = KBT_PORT_PC, portL = KBT_PORT_PC_LEFT;
    int   bindF[HAND_COUNT][P_COUNT] = {};   // tabela do perfil Steam Frame
    float espTimeout = 0.5f;
    CtlCfg ctl[HAND_COUNT];
    int   bind[HAND_COUNT][P_COUNT] = {};
    int   assign = 0;
    bool  swapHands = false;
    std::string ctlIp[HAND_COUNT];
    bool  armEnabled = false;
    ArmParams arm;
    int   espChannel = 0;
    bool  hideLost = false;
    int   stickC[HAND_COUNT][2] = {};
    bool  comboRecenter = true, autoCenter = true, autoZeroConn = true;
    // Headset
    int   headPort = KBT_PORT_HEAD, handPort = KBT_PORT_HAND;
    float handTimeout = 0.4f;
    bool  handsPos[HAND_COUNT] = {}, handsPinch[HAND_COUNT] = {}, handsAim[HAND_COUNT] = {};
    float headTimeout = 0.5f, headHold = 5.f, userHeight = 1.65f, headHeightOff = 0.f;
    double headOff[3] = { 0, 0, 0 }, headRot[3] = { 0, 0, 0 };   // aba Coordenadas
    // etapa 20
    float headSmooth = 35.f, headPredictMs = 20.f, ctlSmooth = 25.f, armSimpleLen = 0.30f, armSimpleElbow = 0.28f, handsSmooth = 45.f, pos6Scale = 1.f;
    bool  hmdMonitor = false;
    int   pos6Src[3] = { 1, 2, 0 };
    bool  pos6Inv[3] = { true, false, true };
    double worldYaw = 0.0;          // radianos
    double headRoll = 0.0;          // radianos
    bool  ctlQuat = true;
    int   armMode = 1;
};


// ---- Ponte com o app Python (stormvr_painel.py): ele grava %LOCALAPPDATA%\StormVR\external.ini e o driver aplica no VRSettings em tempo real.
// Formato por linha:  f:chave=1.5   |   i:chave=2   |   b:chave=1   (tipo explicito, para nao errar o tipo no VRSettings)
static void ApplyExternalIni() {
    static FILETIME last = {};
    static std::string path;
    if (path.empty()) {
        char buf[MAX_PATH] = {};
        if (!ExpandEnvironmentStringsA("%LOCALAPPDATA%\\StormVR\\external.ini", buf, MAX_PATH)) return;
        path = buf;
    }
    WIN32_FILE_ATTRIBUTE_DATA fa;
    if (!GetFileAttributesExA(path.c_str(), GetFileExInfoStandard, &fa)) return;
    if (CompareFileTime(&fa.ftLastWriteTime, &last) == 0) return;
    last = fa.ftLastWriteTime;
    FILE* f = nullptr; fopen_s(&f, path.c_str(), "r");
    if (!f) return;
    char line[256]; int n = 0;
    while (fgets(line, sizeof(line), f)) {
        char* eq = std::strchr(line, '='); if (!eq || line[1] != ':') continue;
        *eq = 0; const char* key = line + 2; const char* val = eq + 1;
        if (line[0] == 'f') { EVRSettingsError e; VRSettings()->SetFloat(kSection, key, (float)atof(val), &e); ++n; }
        else if (line[0] == 'i') { SetI(key, atoi(val)); ++n; }
        else if (line[0] == 'b') { SetB(key, atoi(val) != 0 || val[0] == 't' || val[0] == 'T'); ++n; }
    }
    fclose(f);
    if (n) DiagLog("Painel Python: %d configuracoes aplicadas", n);
}


// ---- Estado atual para o app WEB: o web escreve %LOCALAPPDATA%\StormVR\keys.ini (lista "f:chave"/"i:chave"/"b:chave") e o driver publica o valor
// ATUAL de cada uma em state.ini (so quando algo muda). Assim o que for mexido no menu do SteamVR aparece no site em ~0,3 s.
static void WriteStateIni() {
    static std::string keysPath, statePath, last;
    static FILETIME keysT = {};
    static std::vector<std::pair<char, std::string>> keys;
    if (keysPath.empty()) {
        char a[MAX_PATH] = {}, b[MAX_PATH] = {};
        if (!ExpandEnvironmentStringsA("%LOCALAPPDATA%\\StormVR\\keys.ini", a, MAX_PATH)) return;
        ExpandEnvironmentStringsA("%LOCALAPPDATA%\\StormVR\\state.ini", b, MAX_PATH);
        keysPath = a; statePath = b;
    }
    WIN32_FILE_ATTRIBUTE_DATA fa;
    if (!GetFileAttributesExA(keysPath.c_str(), GetFileExInfoStandard, &fa)) return;
    if (CompareFileTime(&fa.ftLastWriteTime, &keysT) != 0) {
        keysT = fa.ftLastWriteTime; keys.clear();
        FILE* f = nullptr; fopen_s(&f, keysPath.c_str(), "r");
        if (!f) return;
        char line[200];
        while (fgets(line, sizeof(line), f)) {
            size_t n = std::strlen(line); while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
            if (n > 2 && line[1] == ':') keys.push_back({ line[0], std::string(line + 2) });
        }
        fclose(f); last.clear();
    }
    std::string out; char buf[96];
    for (const auto& k : keys) {
        EVRSettingsError e = VRSettingsError_None;
        if (k.first == 'f') { float v = VRSettings()->GetFloat(kSection, k.second.c_str(), &e); if (e == VRSettingsError_None) { snprintf(buf, sizeof(buf), "%.5g", (double)v); out += "f:" + k.second + "=" + buf + "\n"; } }
        else if (k.first == 'i') { int v = VRSettings()->GetInt32(kSection, k.second.c_str(), &e); if (e == VRSettingsError_None) { out += "i:" + k.second + "=" + std::to_string(v) + "\n"; } }
        else if (k.first == 'b') { bool v = VRSettings()->GetBool(kSection, k.second.c_str(), &e); if (e == VRSettingsError_None) { out += "b:" + k.second + "=" + (v ? "1" : "0") + "\n"; } }
    }
    if (out == last) return;
    last = out;
    const std::string tmp = statePath + ".tmp";
    FILE* f = nullptr; fopen_s(&f, tmp.c_str(), "w");
    if (!f) return;
    std::fwrite(out.data(), 1, out.size(), f); fclose(f);
    MoveFileExA(tmp.c_str(), statePath.c_str(), MOVEFILE_REPLACE_EXISTING);
}

static Config LoadConfig() {
    Config c;
    c.keysEnabled = GetB(kKeysEnabled, true);
    c.follow      = GetB(kFollowHmd, true);
    c.followRot   = GetB(kFollowHmdRot, false);
    c.headFlip    = GetB(kHeadFlip.key, kHeadFlip.def);
    c.predictMs   = GetF(kPredictMs);
    c.moveSpeed   = GetF(kMoveSpeed);
    c.rotSpeed    = GetF(kRotSpeed);
    c.fixedHeight = GetF(kFixedHeight);
    c.kMove   = GetI(kKeyMoveMod, kDefMoveMod);
    c.kDepth  = GetI(kKeyDepthMod, kDefDepthMod);
    c.kToggle = GetI(kKeyToggle, kDefToggle);
    for (int h = 0; h < HAND_COUNT; ++h) {
        c.enabled[h] = GetB(kEnabled[h], true);
        for (int i = 0; i < 3; ++i) c.off[h][i] = GetF(kOffset[h][i]);
        for (int a = 0; a < A_COUNT; ++a) c.key[h][a] = GetI(KeySetting(h, a).c_str(), DefaultKey(h, a));
    }
    c.srcMode = GetI(kSrcMode);  c.port = GetI(kUdpPort);  c.portL = GetI(kUdpPortL);
    c.espTimeout = GetF(kEspTimeout);
    for (int h = 0; h < HAND_COUNT; ++h) for (int p = 0; p < P_COUNT; ++p) { c.bind[h][p] = GetI(kBind[h][p]); c.bindF[h][p] = GetI(kBindFrame[h][p]); }
    if (!BindHasTrigger(c.bind)) {               // regra: sempre existe um botao acionando o Trigger
        static bool warned = false;
        if (!warned) { DiagLog("Mapeamento sem Trigger: restaurando o padrao"); warned = true; }
        for (int h = 0; h < HAND_COUNT; ++h) for (int p = 0; p < P_COUNT; ++p) { c.bind[h][p] = kBind[h][p].def; SetI(kBind[h][p].key, kBind[h][p].def); c.bindF[h][p] = kBindFrame[h][p].def; SetI(kBindFrame[h][p].key, kBindFrame[h][p].def); }
    }
    c.assign = GetI(kAssign);  c.swapHands = GetB(kSwap);
    for (int h = 0; h < HAND_COUNT; ++h) c.ctlIp[h] = GetS(kCtlIp[h]);
    for (int h = 0; h < HAND_COUNT; ++h) {
        const CtlSettings& k = kCtl[h];
        CtlCfg& o = c.ctl[h];
        for (int a = 0; a < C_COUNT; ++a) { o.axEn[a] = GetB(k.axEn[a]); o.axSmooth[a] = GetF(k.axSmooth[a]); o.axSrc[a] = GetI(k.axSrc[a]); o.axInv[a] = GetB(k.axInv[a]); o.axGain[a] = GetF(k.axGain[a]); }
        for (int i = 0; i < 3; ++i) o.mpuDz[i] = GetF(k.mpuDz[i]);
        for (int i = 0; i < 2; ++i) { o.stickDz[i] = GetF(k.stickDz[i]); o.stickInv[i] = GetB(k.stickInv[i]); }
        o.stickGamma = GetF(k.stickGamma); o.stickEdge = GetF(k.stickEdge); o.stickSens = GetF(k.stickSens); o.stickSwap = GetB(k.stickSwap);
        o.trigRamp = GetF(k.trigRamp); o.gripRamp = GetF(k.gripRamp); o.clickThr = GetF(k.clickThr); o.yawDrift = GetF(k.yawDrift);
    }
    c.armEnabled = GetB(kArmEnabled);
    c.arm.upper = GetF(kArmUpper);  c.arm.fore = GetF(kArmFore);  c.arm.shX = GetF(kArmShX);  c.arm.shDrop = GetF(kArmShDrop);
    c.arm.bendUp = GetF(kArmBendUp);  c.arm.bendDown = GetF(kArmBendDown);  c.arm.sideReach = GetF(kArmSideReach);
    c.arm.torso = GetF(kArmTorso);  c.arm.reach = GetF(kArmReach);
    c.espChannel = GetI(kEspChannel);  c.hideLost = GetB(kHideLost);
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < 2; ++i) c.stickC[h][i] = GetI(kStickC[h][i]);
    c.comboRecenter = GetB(kComboRecenter);  c.autoCenter = GetB(kAutoCenter); c.autoZeroConn = GetB(kAutoZeroConn);
    c.handPort = GetI(kHandPort);  c.handTimeout = GetF(kHandTimeout);
    for (int h = 0; h < HAND_COUNT; ++h) { c.handsPos[h] = GetB(kHandsPos[h]); c.handsPinch[h] = GetB(kHandsPinch[h]); c.handsAim[h] = GetB(kHandsAim[h]); }
    c.headPort = GetI(kHeadPort);  c.headTimeout = GetF(kHeadTimeout); c.headHold = GetF(kHeadHold);  c.userHeight = GetF(kUserHeight); c.headHeightOff = GetF(kHeadHeightOff);
    for (int i = 0; i < 3; ++i) { c.headOff[i] = GetF(kHeadOff[i]); c.headRot[i] = GetF(kHeadRot[i]); }
    c.headSmooth = GetF(kHeadSmooth); c.headPredictMs = GetF(kHeadPredictMs); c.ctlSmooth = GetF(kCtlSmooth);
    c.hmdMonitor = GetB(kHmdMonitor); c.pos6Src[0] = GetI(kPos6SrcX); c.pos6Src[1] = GetI(kPos6SrcY); c.pos6Src[2] = GetI(kPos6SrcZ);
        c.pos6Inv[0] = GetB(kPos6InvX); c.pos6Inv[1] = GetB(kPos6InvY); c.pos6Inv[2] = GetB(kPos6InvZ); c.pos6Scale = GetF(kPos6Scale);
        c.armSimpleLen = GetF(kArmSimpleLen); c.armSimpleElbow = GetF(kArmSimpleElbow); c.handsSmooth = GetF(kHandsSmooth);
    c.worldYaw = GetF(kWorldYaw) * kDeg2Rad; c.headRoll = GetF(kHeadRoll) * kDeg2Rad; c.ctlQuat = GetB(kCtlQuat); c.armMode = GetI(kArmMode);
    return c;
}

// ------------------------------------------------------ memoria compartilhada (app de config)
static HANDLE g_liveMap = nullptr;
static Live*  g_live = nullptr;

static void OpenLive() {
    g_liveMap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, sizeof(Live), kLiveName);
    if (!g_liveMap) { DLog("[stormvr] falha ao criar memoria compartilhada (%lu)", GetLastError()); return; }
    const bool existed = GetLastError() == ERROR_ALREADY_EXISTS;
    g_live = static_cast<Live*>(MapViewOfFile(g_liveMap, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Live)));
    if (g_live && !existed) std::memset(g_live, 0, sizeof(Live));
    if (g_live) g_live->version = 1;
}
static void CloseLive() {
    if (g_live) { UnmapViewOfFile(g_live); g_live = nullptr; }
    if (g_liveMap) { CloseHandle(g_liveMap); g_liveMap = nullptr; }
}
static std::mutex g_logMx;
static void DiagLog(const char* fmt, ...) {
    char buf[96];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    DLog("[stormvr] %s", buf);
    std::lock_guard<std::mutex> lk(g_logMx);
    if (g_live) {
        const uint32_t i = g_live->logSeq % 8;
        std::memcpy(g_live->log[i], buf, sizeof(buf));
        g_live->log[i][95] = 0;
        g_live->logSeq++;
    }
}
static LONG ReadCmd(int i) { return g_live ? InterlockedCompareExchange(&g_live->cmd[i], 0, 0) : 0; }

// ------------------------------------------------------------ receptor UDP (ESP32)
// Descarta pacotes velhos / fora de ordem (o receptor so aceita o mais novo).
static bool IsNewer(const KbtPacket& n, const KbtPacket& o) {
    return (int16_t)(n.seq - o.seq) > 0 || n.uptimeMs < o.uptimeMs;       // uptime menor = ESP32 reiniciou
}
static bool IsNewer(const KbtHeadPacket& n, const KbtHeadPacket& o) {
    if (n.session != o.session) return true;                               // app reabriu: nova sessao
    return (int32_t)(n.seq - o.seq) > 0;
}
// Quantos pacotes faltaram entre o anterior aceito e este (so para estatistica)
static bool IsNewer(const KbtHandPacket& n, const KbtHandPacket& o) {
    if (n.session != o.session) return true;
    return (int32_t)(n.seq - o.seq) > 0;
}
static uint32_t SeqGap(const KbtHandPacket& n, const KbtHandPacket& o) { int d = (int32_t)(n.seq - o.seq); return d > 1 ? (uint32_t)(d - 1) : 0u; }
static uint32_t SeqGap(const KbtPacket& n, const KbtPacket& o) { int d = (int16_t)(n.seq - o.seq); return d > 1 ? (uint32_t)(d - 1) : 0u; }
static uint32_t SeqGap(const KbtHeadPacket& n, const KbtHeadPacket& o) {
    if (n.session != o.session) return 0u;
    int32_t d = (int32_t)(n.seq - o.seq); return d > 1 ? (uint32_t)(d - 1) : 0u;
}

template <class P, uint32_t Magic>
class UdpRx {
public:
    struct Info {
        bool any = false;
        float age = 999.f;          // segundos desde o ultimo pacote
        P pkt = {};
        float rate = 0.f;
        uint32_t lost = 0, dropped = 0;
        char ip[16] = {};
    };

    void Start(int port) {
        desired_ = port; run_ = true;
        th_ = std::thread([this] { Run(); });
    }
    void Stop() { run_ = false; if (th_.joinable()) th_.join(); }
    void SetPort(int p) { desired_ = p; }
    void Restart() { restart_ = true; }          // fecha e reabre o socket (sem reiniciar o SteamVR)

    Info Get() {
        Info i;
        std::lock_guard<std::mutex> lk(m_);
        i.any = any_; i.pkt = pkt_; i.rate = rate_; i.lost = lost_; i.dropped = dropped_;
        std::memcpy(i.ip, ip_, sizeof(ip_));
        if (any_) i.age = std::chrono::duration<float>(std::chrono::steady_clock::now() - last_).count();
        return i;
    }

    bool SendCmd(uint8_t cmd, uint8_t arg0 = 0) {
        std::lock_guard<std::mutex> lk(m_);
        if (sock_ == INVALID_SOCKET || !haveFrom_) return false;
        KbtCommand c = {}; c.magic = KBT_MAGIC; c.cmd = cmd; c.arg[0] = arg0;
        return sendto(sock_, reinterpret_cast<const char*>(&c), sizeof(c), 0,
                      reinterpret_cast<const sockaddr*>(&from_), sizeof(from_)) == (int)sizeof(c);
    }

private:
    void Run() {
        WSADATA w; WSAStartup(MAKEWORD(2, 2), &w);
        SOCKET s = INVALID_SOCKET; int bound = 0;
        auto winStart = std::chrono::steady_clock::now(); int count = 0;
        while (run_) {
            if (restart_.exchange(false)) {
                if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); s = INVALID_SOCKET; }
                { std::lock_guard<std::mutex> lk(m_); any_ = false; haveFrom_ = false; lost_ = dropped_ = foreign_ = 0; lockIp_ = 0; rate_ = 0.f; }
                DiagLog("Conexao UDP reiniciada (porta %d)", (int)desired_);
            }
            const int want = desired_;
            if (s == INVALID_SOCKET || want != bound) {
                if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); s = INVALID_SOCKET; }
                s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                if (s != INVALID_SOCKET) {
                    DWORD to = 100; setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&to), sizeof(to));
                    int rb = 1 << 16; setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rb), sizeof(rb));
                    sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_ANY); a.sin_port = htons((u_short)want);
                    if (bind(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == SOCKET_ERROR) {
                        DiagLog("UDP: nao consegui abrir a porta %d (erro %d)", want, WSAGetLastError());
                        closesocket(s); s = INVALID_SOCKET;
                        for (int i = 0; i < 20 && run_; ++i) Sleep(100);
                        continue;
                    }
                    bound = want;
                    { std::lock_guard<std::mutex> lk(m_); sock_ = s; haveFrom_ = false; }
                    DLog("[stormvr] UDP escutando na porta %d", want);
                } else { Sleep(500); continue; }
            }
            char buf[256]; sockaddr_in from = {}; int fl = sizeof(from);
            int n = recvfrom(s, buf, sizeof(buf), 0, reinterpret_cast<sockaddr*>(&from), &fl);
            if (n == (int)sizeof(P)) {
                P p; std::memcpy(&p, buf, sizeof(p));
                if (p.magic == Magic) {
                    std::lock_guard<std::mutex> lk(m_);
                    // Trava de origem: enquanto uma origem (ESP32 ou celular) esta ativa, pacotes de OUTRO IP sao ignorados,
                    // senao os dois controles se misturariam. Sem pacotes por 0,5 s, a proxima origem assume.
                    const auto nowT = std::chrono::steady_clock::now();
                    const uint32_t srcIp = from.sin_addr.s_addr;
                    const bool sameSrc = !any_ || srcIp == lockIp_;
                    const bool lockAlive = any_ && std::chrono::duration<float>(nowT - last_).count() < 0.5f;
                    if (!sameSrc && lockAlive) {
                        ++foreign_;
                    } else if (!sameSrc || !any_ || IsNewer(p, pkt_)) {   // pacote velho/duplicado: ignora
                        const bool switched = any_ && !sameSrc;
                        if (sameSrc && any_) lost_ += SeqGap(p, pkt_);
                        lockIp_ = srcIp;
                        pkt_ = p; any_ = true; last_ = nowT;
                        from_ = from; haveFrom_ = true;
                        inet_ntop(AF_INET, &from.sin_addr, ip_, sizeof(ip_));
                        if (switched) DiagLog("Origem do controle trocada para %s", ip_);
                    } else ++dropped_;
                    ++count;
                }
            }
            auto now = std::chrono::steady_clock::now();
            float el = std::chrono::duration<float>(now - winStart).count();
            if (el >= 1.0f) { std::lock_guard<std::mutex> lk(m_); rate_ = count / el; count = 0; winStart = now; }
        }
        if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); }
        WSACleanup();
    }

    std::atomic<bool> run_{ false };
    std::atomic<int>  desired_{ KBT_PORT_PC };
    std::atomic<bool> restart_{ false };
    uint32_t lost_ = 0, dropped_ = 0, foreign_ = 0, lockIp_ = 0;
    std::thread th_;
    std::mutex m_;
    P pkt_ = {};
    bool any_ = false, haveFrom_ = false;
    std::chrono::steady_clock::time_point last_ = std::chrono::steady_clock::now();
    sockaddr_in from_ = {};
    SOCKET sock_ = INVALID_SOCKET;
    float rate_ = 0.f;
    char ip_[16] = {};
};
using HeadReceiver = UdpRx<KbtHeadPacket, KBT_HEAD_MAGIC>; // cabeca (celular)
using HandReceiver = UdpRx<KbtHandPacket, KBT_HAND_MAGIC>; // maos rastreadas (celular)

// ------------------------------------------------------------ receptor UDP dos CONTROLES (varias fontes)
// Cada IP que envia KbtPacket vira uma FONTE independente (ESP32 ou celular). Ate kMax fontes sao acompanhadas;
// o Loop() decide qual fonte ocupa qual mao. Cada fonte descarta pacotes velhos/fora de ordem por conta propria.
class CtlRx {
public:
    static constexpr int kMax = 6;
    struct Snap {
        bool used = false, alive = false;
        float age = 999.f, rate = 0.f, gap = 0.f;     // gap = intervalo medio entre pacotes (s): o "sem pacotes" se adapta a taxa real da fonte
        KbtPacket pkt = {};
        bool hasQ = false; double q[4] = { 1, 0, 0, 0 };   // quaternion do MPU (pacote KbtPacketQ); sem ele, usa-se pkt.rpy
        bool hasPos = false; float pos[3] = {};            // celular 6DoF (KbtPacketP): frente, esquerda, cima (m) desde o recentrar
        uint32_t lost = 0, dropped = 0, ip = 0;
        uint64_t order = 0;                 // ordem de chegada (1 = primeira fonte vista)
        char ipStr[16] = {};
        int forced = -1;                    // -1 = mao pela flag do pacote; HAND_LEFT = porta exclusiva do controle esquerdo
    };

    // Porta exclusiva de uma mao. A chave da fonte ganha um sal para o MESMO IP poder ser dois controles (um por porta).
    void SetForced(int hand) { forced_ = hand; salt_ = 0x80000000u; }
    void Start(int port) { desired_ = port; run_ = true; th_ = std::thread([this] { Run(); }); }
    void Stop() { run_ = false; if (th_.joinable()) th_.join(); }
    void SetPort(int p) { desired_ = p; }
    void Restart() { restart_ = true; }

    // Copia o estado de todas as fontes; 'alive' = pacote ha menos de 'timeout' segundos. Devolve quantas fontes existem.
    int Snapshot(Snap out[kMax], float timeout) {
        std::lock_guard<std::mutex> lk(m_);
        const auto now = std::chrono::steady_clock::now();
        int n = 0;
        for (int i = 0; i < kMax; ++i) {
            Snap& o = out[i];
            o = Snap();
            const Src& s = src_[i];
            if (!s.used) continue;
            o.hasPos = s.hasPos; for (int k = 0; k < 3; ++k) o.pos[k] = s.pos[k];
            o.used = true; o.pkt = s.pkt; o.hasQ = s.hasQ; for (int k = 0; k < 4; ++k) o.q[k] = s.q[k]; o.lost = s.lost; o.dropped = s.dropped; o.ip = s.ip; o.order = s.order; o.forced = forced_;
            o.age = std::chrono::duration<float>(now - s.last).count();
            o.gap = s.gap;
            // vivo = chegou pacote dentro do timeout OU de ~4 intervalos medios (ate 3 s): com 2 pacotes/s o controle nao desconecta a cada 0,5 s
            o.alive = o.age < std::max(timeout, std::min(3.0f, s.gap * 4.f));
            o.rate = o.age > 1.5f ? 0.f : s.rate;
            { const uint32_t real = s.ip ^ salt_; inet_ntop(AF_INET, const_cast<in_addr*>(reinterpret_cast<const in_addr*>(&real)), o.ipStr, sizeof(o.ipStr)); }
            ++n;
        }
        return n;
    }

    bool SendCmdTo(uint32_t ip, uint8_t cmd, uint8_t arg0 = 0) {
        std::lock_guard<std::mutex> lk(m_);
        if (sock_ == INVALID_SOCKET) return false;
        for (const Src& s : src_) {
            if (!s.used || s.ip != ip) continue;
            KbtCommand c = {}; c.magic = KBT_MAGIC; c.cmd = cmd; c.arg[0] = arg0;
            return sendto(sock_, reinterpret_cast<const char*>(&c), sizeof(c), 0,
                          reinterpret_cast<const sockaddr*>(&s.from), sizeof(s.from)) == (int)sizeof(c);
        }
        return false;
    }

private:
    struct Src {
        bool used = false;
        uint32_t ip = 0;
        sockaddr_in from = {};
        KbtPacket pkt = {};
        bool hasQ = false; double q[4] = { 1, 0, 0, 0 };
        bool hasPos = false; float pos[3] = {};
        int consecDrop = 0;                                     // pacotes seguidos recusados como "velhos" (=> o remetente reiniciou)
        std::chrono::steady_clock::time_point last = std::chrono::steady_clock::now(), win = std::chrono::steady_clock::now();
        int count = 0;
        float rate = 0.f, gap = 0.f;
        uint32_t lost = 0, dropped = 0;
        uint64_t order = 0;
    };

    static bool Newer(const KbtPacket& n, const KbtPacket& o) {
        return (int16_t)(n.seq - o.seq) > 0 || n.uptimeMs < o.uptimeMs;          // uptime menor = o controle reiniciou
    }

    // Procura a fonte do IP; se nao existe, usa uma vaga livre ou recicla a fonte morta ha mais tempo (>5 s).
    Src* Find(uint32_t ip, const std::chrono::steady_clock::time_point& now) {
        for (Src& s : src_) if (s.used && s.ip == ip) return &s;
        Src* pick = nullptr;
        for (Src& s : src_) if (!s.used) { pick = &s; break; }
        if (!pick) {
            float best = 5.f;
            for (Src& s : src_) {
                const float age = std::chrono::duration<float>(now - s.last).count();
                if (age > best) { best = age; pick = &s; }
            }
        }
        if (!pick) return nullptr;
        *pick = Src();
        pick->used = true; pick->ip = ip; pick->order = ++orderCtr_;
        pick->last = now; pick->win = now;
        return pick;
    }

    void Run() {
        WSADATA w; WSAStartup(MAKEWORD(2, 2), &w);
        SOCKET s = INVALID_SOCKET; int bound = 0;
        while (run_) {
            if (restart_.exchange(false)) {
                if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); s = INVALID_SOCKET; }
                { std::lock_guard<std::mutex> lk(m_); for (Src& x : src_) x = Src(); orderCtr_ = 0; }
                DiagLog("Conexao UDP dos controles reiniciada (porta %d)", (int)desired_);
            }
            const int want = desired_;
            if (s == INVALID_SOCKET || want != bound) {
                if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); s = INVALID_SOCKET; }
                s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                if (s != INVALID_SOCKET) {
                    DWORD to = 100; setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&to), sizeof(to));
                    int rb = 1 << 16; setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rb), sizeof(rb));
                    sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_ANY); a.sin_port = htons((u_short)want);
                    if (bind(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == SOCKET_ERROR) {
                        DiagLog("UDP: nao consegui abrir a porta %d (erro %d)", want, WSAGetLastError());
                        closesocket(s); s = INVALID_SOCKET;
                        for (int i = 0; i < 20 && run_; ++i) Sleep(100);
                        continue;
                    }
                    bound = want;
                    { std::lock_guard<std::mutex> lk(m_); sock_ = s; }
                    DLog("[stormvr] UDP dos controles escutando na porta %d", want);
                } else { Sleep(500); continue; }
            }
            char buf[256]; sockaddr_in from = {}; int fl = sizeof(from);
            const int n = recvfrom(s, buf, sizeof(buf), 0, reinterpret_cast<sockaddr*>(&from), &fl);
            KbtPacket p; bool hasQ = false; double qq[4] = { 1, 0, 0, 0 }; bool hasPos = false; float pp[3] = {};
            if (n == (int)sizeof(KbtPacketQ)) {                        // formato novo: orientacao em quaternion
                KbtPacketQ pq; std::memcpy(&pq, buf, sizeof(pq));
                if (pq.magic != KBT_MAGIC_Q) continue;
                double nn = 0; for (int k = 0; k < 4; ++k) { qq[k] = pq.quat[k]; nn += qq[k] * qq[k]; }
                nn = std::sqrt(nn); if (!(nn > 0.5 && nn < 1.5)) continue;
                for (int k = 0; k < 4; ++k) qq[k] /= nn;
                hasQ = true;
                std::memset(&p, 0, sizeof(p));
                p.magic = KBT_MAGIC; p.seq = pq.seq; p.buttons = pq.buttons; p.joyX = pq.joyX; p.joyY = pq.joyY;
                for (int k = 0; k < 3; ++k) p.gyro[k] = pq.gyro[k];
                p.uptimeMs = pq.uptimeMs; p.rssi = pq.rssi; p.flags = pq.flags;
                double r, pt, y; kbt::ori::toEulerZYX({ qq[0], qq[1], qq[2], qq[3] }, r, pt, y);   // so para diagnostico (aba Eixos)
                p.rpy[0] = (float)r; p.rpy[1] = (float)pt; p.rpy[2] = (float)y;
            } else if (n == (int)sizeof(KbtPacket)) {
                std::memcpy(&p, buf, sizeof(p));
                if (p.magic != KBT_MAGIC) continue;
            } else if (n == (int)sizeof(KbtPacketP)) {                 // celular 6DoF: KbtPacket + posicao
                KbtPacketP pp6; std::memcpy(&pp6, buf, sizeof(pp6));
                if (pp6.base.magic != KBT_MAGIC) continue;
                p = pp6.base;
                hasPos = (p.flags & KBT_FLAG_POS) != 0;
                for (int k = 0; k < 3; ++k) pp[k] = (std::isfinite(pp6.pos[k]) ? std::max(-3.f, std::min(3.f, pp6.pos[k])) : 0.f);
            } else continue;
            const auto now = std::chrono::steady_clock::now();
            std::lock_guard<std::mutex> lk(m_);
            Src* x = Find(from.sin_addr.s_addr ^ salt_, now);
            if (!x) continue;                                          // todas as vagas ocupadas por fontes vivas
            if (x->count == 0 && x->pkt.magic == 0) { x->pkt = p; x->hasQ = hasQ; for (int k = 0; k < 4; ++k) x->q[k] = qq[k]; x->hasPos = hasPos; for (int k = 0; k < 3; ++k) x->pos[k] = pp[k]; x->from = from; x->last = now; x->count = 1; continue; }   // 1o pacote
            // Remetente REINICIADO (celular que parou e voltou: o contador seq volta a 0, mas o relogio dele continua): sem isto todos os pacotes
            // novos pareciam "velhos" e o controle so voltava depois de reiniciar o SteamVR. Silencio > 0,7 s ou 6 recusas seguidas = fluxo novo.
            const float silence = std::chrono::duration<float>(now - x->last).count();
            bool accept = Newer(p, x->pkt), resync = false;
            if (accept) x->consecDrop = 0;
            else if (++x->consecDrop >= 6 || silence > 0.7f) { accept = true; resync = true; x->consecDrop = 0; }
            if (accept) {
                if (resync) x->gap = 0.f;
                else { const float g = std::min(2.0f, silence); x->gap = x->gap <= 0.f ? g : x->gap + (g - x->gap) * 0.1f; }
                const int d = (int16_t)(p.seq - x->pkt.seq);
                if (!resync && d > 1) x->lost += (uint32_t)(d - 1);
                x->pkt = p; x->hasQ = hasQ; for (int k = 0; k < 4; ++k) x->q[k] = qq[k]; x->hasPos = hasPos; for (int k = 0; k < 3; ++k) x->pos[k] = pp[k]; x->last = now; x->from = from;
            } else ++x->dropped;                                       // pacote velho/duplicado: ignora
            ++x->count;
            const float el = std::chrono::duration<float>(now - x->win).count();
            if (el >= 1.0f) { x->rate = x->count / el; x->count = 0; x->win = now; }
        }
        if (s != INVALID_SOCKET) { std::lock_guard<std::mutex> lk(m_); sock_ = INVALID_SOCKET; closesocket(s); }
        WSACleanup();
    }

    std::atomic<bool> run_{ false };
    std::atomic<int>  desired_{ KBT_PORT_PC };
    std::atomic<bool> restart_{ false };
    int forced_ = -1; uint32_t salt_ = 0;
    std::thread th_;
    std::mutex m_;
    Src src_[kMax];
    uint64_t orderCtr_ = 0;
    SOCKET sock_ = INVALID_SOCKET;
};

// Analogico: ADC bruto -> -1..1 (centro, borda, inversao, troca, zona morta por eixo, curva)
static void ProcessStick(const CtlCfg& c, int cx, int cy, uint16_t rx, uint16_t ry, float& ox, float& oy, float& nx, float& ny) {
    auto norm = [&](int raw, int center) {
        float d = (float)(raw - center);
        float span = d >= 0 ? (float)(4095 - center) : (float)center;
        if (span < 200.f) span = 200.f;
        return Clampf(d / (span * c.stickEdge), -1.f, 1.f);
    };
    float x = norm(rx, cx), y = norm(ry, cy);
    nx = x; ny = y;                                  // normalizado, antes de inverter/zona morta
    if (c.stickInv[0]) x = -x;
    if (c.stickInv[1]) y = -y;
    if (c.stickSwap) std::swap(x, y);
    auto shape = [&](float v, float dz) {
        float a = std::fabs(v);
        if (a <= dz) return 0.f;
        a = std::pow((a - dz) / (1.f - dz), c.stickGamma);
        return v < 0 ? -a : a;
    };
    x = shape(x, c.stickDz[0]) * c.stickSens;
    y = shape(y, c.stickDz[1]) * c.stickSens;
    float len = std::sqrt(x * x + y * y);
    if (len > 1.f) { x /= len; y /= len; }
    ox = x; oy = y;
}

// ------------------------------------------------------------- controle
static constexpr int kMinPressMs = 110;   // tempo minimo que Trigger/Grip ficam apertados depois de UM toque (volume do celular, botoes rapidos)
struct PadState {
    double pos[3] = {};
    HmdQuaternion_t rot = { 1, 0, 0, 0 };
    double vel[3] = {};
    float trig = 0, grip = 0, sx = 0, sy = 0;
    bool  a = false, b = false, click = false, menu = false;
    bool  gx = false, gy = false;      // X / Y fisicos expostos 1:1 (perfis Generico e Frame)
    bool  bump = false, sys = false;   // bumper e Sistema (perfil Frame)
    float clickThr = 0.9f;             // valor de Trigger/Grip a partir do qual conta como clique
    float fing[5] = {};                // curvatura dos dedos (polegar, indicador, medio, anelar, mindinho), so no perfil Index
    bool  connected = true;
};

// ---- Esqueleto da mao do Valve Index (dados reais do Valve em index_skeleton.h; mao esquerda = espelho em X)
static void IdxBone(int b, bool left, double f, VRBoneTransform_t& out) {
    const float* o = idxskel::kOpen[b]; const float* c = idxskel::kClosed[b];
    double q0[4] = { o[3], o[4], o[5], o[6] }, q1[4] = { c[3], c[4], c[5], c[6] };     // w x y z
    double p0[3] = { o[0], o[1], o[2] }, p1[3] = { c[0], c[1], c[2] };
    if (left) { p0[0] = -p0[0]; p1[0] = -p1[0]; q0[2] = -q0[2]; q0[3] = -q0[3]; q1[2] = -q1[2]; q1[3] = -q1[3]; }
    double d = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3];
    if (d < 0) { for (double& v : q1) v = -v; d = -d; }
    double k0, k1;
    if (d > 0.9995) { k0 = 1.0 - f; k1 = f; }
    else { const double th = std::acos(d), sn = std::sin(th); k0 = std::sin((1.0 - f) * th) / sn; k1 = std::sin(f * th) / sn; }
    double q[4]; for (int i = 0; i < 4; ++i) q[i] = k0 * q0[i] + k1 * q1[i];
    const double n = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    out.orientation.w = q[0] / n; out.orientation.x = q[1] / n; out.orientation.y = q[2] / n; out.orientation.z = q[3] / n;
    for (int i = 0; i < 3; ++i) out.position.v[i] = (float)(p0[i] + (p1[i] - p0[i]) * f);
    out.position.v[3] = 1.f;
}
// curl[5] (polegar..mindinho, 0 = aberto, 1 = fechado) -> 31 ossos
static void IdxPose(bool left, const float curl[5], VRBoneTransform_t* bt) {
    int owner[idxskel::kBones];
    for (int i = 0; i < idxskel::kBones; ++i) owner[i] = -1;
    for (int f = 0; f < 5; ++f) for (int k = 0; k < 7; ++k) { const int b = idxskel::kFingerBones[f][k]; if (b >= 0) owner[b] = f; }
    for (int b = 0; b < idxskel::kBones; ++b) {
        const double f = owner[b] < 0 ? 0.0 : std::max(0.f, std::min(1.f, curl[owner[b]]));
        IdxBone(b, left, f, bt[b]);
    }
}

// Controle virtual. O PERFIL define quais componentes de entrada existem (e portanto o que os jogos enxergam):
//  Oculus Touch  : a/b (ou x/y, application_menu na esquerda), trigger, grip, joystick           [perfil da Oculus]
//  HTC Vive      : application_menu, system, trigger, grip(click), trackpad (x/y/click/touch)     [perfil da HTC]
//  Valve Index   : a, b, system, trigger, grip, thumbstick - SEM esqueleto de dedos             [experimental]
//  Generico      : trigger, grip, a, b, x, y, system, thumbstick (perfil proprio do StormVR)    [experimental]
// O perfil so e lido quando o SteamVR ativa o dispositivo: trocar exige reiniciar o SteamVR.
class KbController : public ITrackedDeviceServerDriver {
public:
    KbController(int hand, int profile) : hand_(hand), profile_(profile) {}

    const char* Serial() const { return hand_ == HAND_RIGHT ? "STORM-CTRL-R" : "STORM-CTRL-L"; }
    uint32_t    Id()     const { return id_; }

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        auto* p = VRProperties();
        PropertyContainerHandle_t c = p->TrackedDeviceToPropertyContainer(id);
        const bool right = hand_ == HAND_RIGHT;

        const char *model, *manuf, *track, *render, *ctype, *iprofile;
        switch (profile_) {
        case PF_VIVE:
            model = "Vive Controller MV"; manuf = "HTC"; track = "lighthouse"; render = "vr_controller_vive_1_5";
            ctype = "vive_controller"; iprofile = "{htc}/input/vive_controller_profile.json"; break;
        case PF_INDEX:
            model = right ? "Knuckles Right" : "Knuckles Left"; manuf = "Valve"; track = "indexcontroller";
            render = right ? "{indexcontroller}valve_controller_knu_1_0_right" : "{indexcontroller}valve_controller_knu_1_0_left";
            ctype = "knuckles"; iprofile = "{indexcontroller}/input/index_controller_profile.json"; break;
        case PF_FRAME:
            model = right ? "Frame Controller Right" : "Frame Controller Left"; manuf = "Valve"; track = "frame_controller";
            render = right ? "{frame_controller}frame_controller_right" : "{frame_controller}frame_controller_left";
            ctype = "frame_controller"; iprofile = "{frame_controller}/input/frame_controller_profile.json"; break;
        case PF_GENERIC:
            model = "StormVR Controller"; manuf = "StormVR"; track = "stormvr";
            render = right ? "oculus_cv1_controller_right" : "oculus_cv1_controller_left";
            ctype = "stormvr_generic"; iprofile = "{stormvr}/input/stormvr_generic_profile.json"; break;
        default:
            model = right ? "Oculus Rift CV1 (Right Controller)" : "Oculus Rift CV1 (Left Controller)"; manuf = "Oculus"; track = "oculus";
            render = right ? "oculus_cv1_controller_right" : "oculus_cv1_controller_left";
            ctype = "oculus_touch"; iprofile = "{oculus}/input/touch_profile.json"; break;
        }
        p->SetStringProperty(c, Prop_ModelNumber_String, model);
        p->SetStringProperty(c, Prop_ManufacturerName_String, manuf);
        p->SetStringProperty(c, Prop_TrackingSystemName_String, track);
        p->SetStringProperty(c, Prop_RenderModelName_String, render);
        p->SetStringProperty(c, Prop_ControllerType_String, ctype);
        p->SetStringProperty(c, Prop_InputProfilePath_String, iprofile);
        p->SetInt32Property(c, Prop_ControllerRoleHint_Int32, right ? TrackedControllerRole_RightHand : TrackedControllerRole_LeftHand);
        p->SetInt32Property(c, Prop_Axis0Type_Int32, k_eControllerAxis_Joystick);
        p->SetInt32Property(c, Prop_Axis1Type_Int32, k_eControllerAxis_Trigger);
        p->SetInt32Property(c, Prop_Axis2Type_Int32, k_eControllerAxis_Trigger);
        p->SetBoolProperty(c, Prop_WillDriftInYaw_Bool, false);
        p->SetBoolProperty(c, Prop_DeviceIsWireless_Bool, true);
        p->SetBoolProperty(c, Prop_DeviceCanPowerOff_Bool, false);
        p->SetBoolProperty(c, Prop_HasDisplayComponent_Bool, false);
        p->SetBoolProperty(c, Prop_HasCameraComponent_Bool, false);
        p->SetBoolProperty(c, Prop_HasDriverDirectModeComponent_Bool, false);
        p->SetBoolProperty(c, Prop_HasVirtualDisplayComponent_Bool, false);

        auto* in = VRDriverInput();
        auto mkB = [&](const char* path, VRInputComponentHandle_t& h) {
            if (in->CreateBooleanComponent(c, path, &h) != 0) { h = k_ulInvalidInputComponentHandle; DiagLog("componente recusado: %s", path); }
        };
        auto mkS = [&](const char* path, VRInputComponentHandle_t& h, EVRScalarUnits u) {
            if (in->CreateScalarComponent(c, path, &h, VRScalarType_Absolute, u) != 0) { h = k_ulInvalidInputComponentHandle; DiagLog("componente recusado: %s", path); }
        };
        switch (profile_) {
        case PF_VIVE:
            mkB("/input/application_menu/click", btn1Click_);
            mkB("/input/system/click", menuClick_);
            mkS("/input/trigger/value", trigValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/trigger/click", trigClick_);
            mkB("/input/grip/click", gripClick_);
            mkS("/input/trackpad/x", joyX_, VRScalarUnits_NormalizedTwoSided);
            mkS("/input/trackpad/y", joyY_, VRScalarUnits_NormalizedTwoSided);
            mkB("/input/trackpad/click", joyClick_);
            mkB("/input/trackpad/touch", joyTouch_);
            break;
        case PF_INDEX:
            mkB("/input/a/click", btn1Click_);   mkB("/input/a/touch", btn1Touch_);
            mkB("/input/b/click", btn2Click_);   mkB("/input/b/touch", btn2Touch_);
            mkB("/input/system/click", menuClick_);
            mkS("/input/trigger/value", trigValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/trigger/click", trigClick_);   mkB("/input/trigger/touch", trigTouch_);
            mkS("/input/grip/value", gripValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/grip/touch", gripTouch_);
            mkS("/input/thumbstick/x", joyX_, VRScalarUnits_NormalizedTwoSided);
            mkS("/input/thumbstick/y", joyY_, VRScalarUnits_NormalizedTwoSided);
            mkB("/input/thumbstick/click", joyClick_);   mkB("/input/thumbstick/touch", joyTouch_);
            // dedos (curvatura): alimentados pelo mapeamento "Dedo ..." da aba Mapa. Se o SteamVR recusar, o log mostra o caminho.
            { static const char* fn[5] = { "thumb", "index", "middle", "ring", "pinky" };
              for (int i = 1; i < 5; ++i) { char pth[48]; snprintf(pth, sizeof pth, "/input/finger/%s", fn[i]); mkS(pth, finger_[i], VRScalarUnits_NormalizedOneSided); } }   // o perfil do Valve nao tem curl do polegar
            { // esqueleto da mao (31 ossos): o perfil do Index referencia /skeleton/hand/<lado>; sem isso os dedos nao se mexem nos jogos
              VRBoneTransform_t grip[idxskel::kBones]; const float closed[5] = { 1, 1, 1, 1, 1 };
              IdxPose(!right, closed, grip);
              const EVRInputError e = in->CreateSkeletonComponent(c, right ? "/input/skeleton/right" : "/input/skeleton/left",
                  right ? "/skeleton/hand/right" : "/skeleton/hand/left", "/pose/raw", VRSkeletalTracking_Partial, grip, idxskel::kBones, &skel_);
              if (e != VRInputError_None) { skel_ = k_ulInvalidInputComponentHandle; DiagLog("esqueleto da mao recusado (erro %d)", (int)e); } }
            break;
        case PF_FRAME:   // perfil do Valve (frame_controller_profile.json): esquerdo = view + dpad; direito = menu/system + A B X Y; os dois: bumper, trigger, grip, thumbstick
            if (right) {
                mkB("/input/a/click", btn1Click_);   mkB("/input/a/touch", btn1Touch_);
                mkB("/input/b/click", btn2Click_);   mkB("/input/b/touch", btn2Touch_);
                mkB("/input/x/click", gxClick_);     mkB("/input/y/click", gyClick_);
                mkB("/input/menu/click", menuClick_);
            } else {
                mkB("/input/dpad_down/click", btn1Click_);   mkB("/input/dpad_right/click", btn2Click_);
                mkB("/input/dpad_left/click", gxClick_);     mkB("/input/dpad_up/click", gyClick_);
                mkB("/input/view/click", menuClick_);
            }
            mkB("/input/system/click", sysClick_);
            mkB("/input/bumper/click", bumpClick_);
            mkS("/input/trigger/value", trigValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/trigger/click", trigClick_);   mkB("/input/trigger/touch", trigTouch_);
            mkS("/input/grip/value", gripValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/grip/click", gripClick_);      mkB("/input/grip/touch", gripTouch_);
            mkS("/input/thumbstick/x", joyX_, VRScalarUnits_NormalizedTwoSided);
            mkS("/input/thumbstick/y", joyY_, VRScalarUnits_NormalizedTwoSided);
            mkB("/input/thumbstick/click", joyClick_);   mkB("/input/thumbstick/touch", joyTouch_);
            break;
        case PF_GENERIC:
            mkS("/input/trigger/value", trigValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/trigger/click", trigClick_);   mkB("/input/trigger/touch", trigTouch_);
            mkS("/input/grip/value", gripValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/grip/click", gripClick_);
            mkB("/input/a/click", btn1Click_);   mkB("/input/b/click", btn2Click_);
            mkB("/input/x/click", gxClick_);     mkB("/input/y/click", gyClick_);
            mkB("/input/system/click", menuClick_);
            mkS("/input/thumbstick/x", joyX_, VRScalarUnits_NormalizedTwoSided);
            mkS("/input/thumbstick/y", joyY_, VRScalarUnits_NormalizedTwoSided);
            mkB("/input/thumbstick/click", joyClick_);   mkB("/input/thumbstick/touch", joyTouch_);
            break;
        default:   // Oculus Touch
            if (right) {
                mkB("/input/a/click", btn1Click_);   mkB("/input/a/touch", btn1Touch_);
                mkB("/input/b/click", btn2Click_);   mkB("/input/b/touch", btn2Touch_);
                mkB("/input/system/click", menuClick_);
            } else {
                mkB("/input/x/click", btn1Click_);   mkB("/input/x/touch", btn1Touch_);
                mkB("/input/y/click", btn2Click_);   mkB("/input/y/touch", btn2Touch_);
                mkB("/input/application_menu/click", menuClick_);
            }
            mkS("/input/trigger/value", trigValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/trigger/touch", trigTouch_);
            mkB("/input/trigger/click", trigClick_);
            mkS("/input/grip/value", gripValue_, VRScalarUnits_NormalizedOneSided);
            mkB("/input/grip/click", gripClick_);
            mkS("/input/joystick/x", joyX_, VRScalarUnits_NormalizedTwoSided);
            mkS("/input/joystick/y", joyY_, VRScalarUnits_NormalizedTwoSided);
            mkB("/input/joystick/click", joyClick_);
            mkB("/input/joystick/touch", joyTouch_);
            break;
        }
        in->CreateHapticComponent(c, "/output/haptic", &haptic_);

        first_ = true;
        DLog("[stormvr] %s ativado (id=%u, perfil %d: %s)", Serial(), id, profile_, ctype);
        return VRInitError_None;
    }

    void Deactivate() override { id_ = k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* resp, uint32_t size) override { if (size > 0) resp[0] = 0; }

    DriverPose_t GetPose() override {
        std::lock_guard<std::mutex> lk(mtx_);
        return pose_;
    }

    bool IsHaptic(VRInputComponentHandle_t h) const { return h == haptic_ && h != k_ulInvalidInputComponentHandle; }

    void Push(const PadState& s) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t pose = {};
        pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
        pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };
        pose.qRotation = s.rot;
        { // correcao de rotacao do modelo/ponteiro por perfil (ajustavel na aba Controles; padrao 0)
            const int pi = profile_ >= 0 && profile_ < PF_COUNT ? profile_ : 0;
            const double sg = hand_ == HAND_RIGHT ? 1.0 : -1.0;
            const double pt = GetF(kRotOff[pi][0]) * kDeg2Rad, yw = GetF(kRotOff[pi][1]) * kDeg2Rad * sg, rl = GetF(kRotOff[pi][2]) * kDeg2Rad * sg;
            if (pt != 0.0 || yw != 0.0 || rl != 0.0)
                pose.qRotation = QuatMul(QuatMul(QuatMul(pose.qRotation, QuatAxisY(yw)), QuatAxisX(pt)), QuatAxisZ(rl));
        }
        for (int i = 0; i < 3; ++i) { pose.vecPosition[i] = s.pos[i] + GetF(kCtlOff[hand_][i]); pose.vecVelocity[i] = s.vel[i]; lastPosW_[i] = (float)pose.vecPosition[i]; }
        pose.poseIsValid = s.connected;
        pose.deviceIsConnected = s.connected;
        pose.result = s.connected ? TrackingResult_Running_OK : TrackingResult_Uninitialized;
        pose.shouldApplyHeadModel = false;
        pose.willDriftInYaw = false;
        { std::lock_guard<std::mutex> lk(mtx_); pose_ = pose; }
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose, sizeof(DriverPose_t));

        auto* in = VRDriverInput();
        auto B = [&](VRInputComponentHandle_t h, bool v, bool& prev) {
            if (h == k_ulInvalidInputComponentHandle) return;
            if (first_ || v != prev) in->UpdateBooleanComponent(h, v, 0.0);
            prev = v;
        };
        auto S = [&](VRInputComponentHandle_t h, float v, float& prev) {
            if (h == k_ulInvalidInputComponentHandle) return;
            if (first_ || std::fabs(v - prev) > 1e-4f) in->UpdateScalarComponent(h, v, 0.0);
            prev = v;
        };
        const bool joyActive = std::fabs(s.sx) > 0.01f || std::fabs(s.sy) > 0.01f || s.click;
        B(btn1Click_, s.a, p_.a);       B(btn1Touch_, s.a, p_.a2);
        B(btn2Click_, s.b, p_.b);       B(btn2Touch_, s.b, p_.b2);
        B(gxClick_, s.gx, p_.gx);       B(gyClick_, s.gy, p_.gy);
        B(bumpClick_, s.bump, p_.bump);
        B(sysClick_, s.sys, p_.sys);
        B(menuClick_, s.menu, p_.menu);
        S(trigValue_, s.trig, p_.trig);
        B(trigTouch_, s.trig > 0.02f, p_.trigT);
        B(trigClick_, s.trig > s.clickThr, p_.trigC);
        S(gripValue_, s.grip, p_.grip);
        B(gripTouch_, s.grip > 0.02f, p_.gripT);
        B(gripClick_, s.grip > s.clickThr, p_.gripC);
        S(joyX_, s.sx, p_.sx);          S(joyY_, s.sy, p_.sy);
        B(joyClick_, s.click, p_.click);
        B(joyTouch_, joyActive, p_.joyT);
        if (profile_ == PF_INDEX) {
            // curvatura final: botoes mapeados aos dedos (aba Mapa) + comportamento natural (gatilho = indicador, grip = demais, polegar nos botoes)
            const bool thumbOn = s.a || s.b || s.click || joyActive;
            float cv[5] = { std::max(s.fing[0], thumbOn ? 0.55f : 0.f), std::max(s.fing[1], s.trig), std::max(s.fing[2], s.grip),
                            std::max(s.fing[3], s.grip), std::max(s.fing[4], s.grip) };
            for (int i = 0; i < 5; ++i) S(finger_[i], cv[i], p_.fing[i]);
            if (skel_ != k_ulInvalidInputComponentHandle) {
                bool ch = first_;
                for (int i = 0; i < 5; ++i) if (std::fabs(cv[i] - skelPrev_[i]) > 0.003f) ch = true;
                if (ch) {
                    VRBoneTransform_t bt[idxskel::kBones];
                    IdxPose(hand_ == HAND_LEFT, cv, bt);
                    in->UpdateSkeletonComponent(skel_, VRSkeletalMotionRange_WithController, bt, idxskel::kBones);
                    in->UpdateSkeletonComponent(skel_, VRSkeletalMotionRange_WithoutController, bt, idxskel::kBones);
                    for (int i = 0; i < 5; ++i) skelPrev_[i] = cv[i];
                }
            }
        }
        first_ = false;
    }

private:
    struct Prev { bool bump=false,sys=false,a=false,a2=false,b=false,b2=false,gx=false,gy=false,menu=false,trigT=false,trigC=false,gripT=false,gripC=false,click=false,joyT=false;
                  float trig=0,grip=0,sx=0,sy=0,fing[5]={}; } p_;
    VRInputComponentHandle_t skel_ = k_ulInvalidInputComponentHandle;
    float lastPosW_[3] = { 0, 0, 0 };
public:
    void LastPos(float p[3]) { for (int i = 0; i < 3; ++i) p[i] = lastPosW_[i]; }
private:
    float skelPrev_[5] = { -1, -1, -1, -1, -1 };
    VRInputComponentHandle_t finger_[5] = { k_ulInvalidInputComponentHandle, k_ulInvalidInputComponentHandle, k_ulInvalidInputComponentHandle, k_ulInvalidInputComponentHandle, k_ulInvalidInputComponentHandle };
    int hand_, profile_;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    bool first_ = true;
    std::mutex mtx_;
    DriverPose_t pose_ = {};
    VRInputComponentHandle_t btn1Click_ = 0, btn1Touch_ = 0, btn2Click_ = 0, btn2Touch_ = 0, gxClick_ = 0, gyClick_ = 0, bumpClick_ = 0, sysClick_ = 0, menuClick_ = 0,
        trigValue_ = 0, trigTouch_ = 0, trigClick_ = 0, gripValue_ = 0, gripTouch_ = 0, gripClick_ = 0,
        joyX_ = 0, joyY_ = 0, joyClick_ = 0, joyTouch_ = 0, haptic_ = 0;
};

// ------------------------------------------------------------ monitor do headset
static bool g_madePrimary = false;
static bool SetMonitorMode(const std::string& dev, int w, int h) {
    DEVMODEA dm = {}; dm.dmSize = sizeof(dm);
    if (!EnumDisplaySettingsA(dev.c_str(), ENUM_CURRENT_SETTINGS, &dm)) return false;
    dm.dmPelsWidth = (DWORD)w; dm.dmPelsHeight = (DWORD)h; dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT;
    return ChangeDisplaySettingsExA(dev.c_str(), &dm, nullptr, CDS_FULLSCREEN, nullptr) == DISP_CHANGE_SUCCESSFUL;
}
// Escolhe o monitor do headset (menu ou automatico), aplica a resolucao pedida (nunca no principal) e, se ligado,
// o torna o monitor PRINCIPAL do Windows enquanto o SteamVR estiver aberto (layout restaurado ao fechar).
static void PrepareHeadsetMonitor(std::string& dev, uint32_t& status) {
    const std::vector<MonitorInfo> mons = ListMonitors();
    dev = GetS(kHmdMonitorKey); status = 0;
    const MonitorInfo* pick = nullptr;
    for (const MonitorInfo& m : mons) if (m.dev == dev) pick = &m;
    if (!pick) {
        if (!dev.empty()) DiagLog("AVISO: monitor '%s' nao encontrado; escolhendo automatico", dev.c_str());
        for (const MonitorInfo& m : mons) if (!m.primary) { pick = &m; break; }          // prefere um que nao seja o principal
        if (!pick) for (const MonitorInfo& m : mons) if (m.primary) { pick = &m; break; }
        if (!pick && !mons.empty()) pick = &mons[0];
        status = 1;
    }
    if (!pick) { dev.clear(); status = 2; return; }
    dev = pick->dev;
    const int rm = GetI(kHmdResMode);
    if (rm > 0 && (pick->w != kResW[rm] || pick->h != kResH[rm])) {
        if (pick->primary) DiagLog("Resolucao %dx%d NAO aplicada: o monitor principal e protegido", kResW[rm], kResH[rm]);
        else if (SetMonitorMode(dev, kResW[rm], kResH[rm])) { Sleep(400); status = 3; DiagLog("Monitor %s ajustado para %dx%d", dev.c_str(), kResW[rm], kResH[rm]); }
        else DiagLog("Monitor %s nao aceitou %dx%d", dev.c_str(), kResW[rm], kResH[rm]);
    }
    if (GetB(kMakePrimary) && !pick->primary) {
        if (MakeMonitorPrimary(dev)) { g_madePrimary = true; Sleep(800); DiagLog("Monitor %s virou o PRINCIPAL do Windows (restaura ao fechar)", dev.c_str()); }
        else DiagLog("Nao consegui tornar %s o principal: defina manualmente no Windows", dev.c_str());
    }
}

// ------------------------------------------------------------------ headset (HMD)
// Modo ESTENDIDO (igual ao driver_null da Valve): o SteamVR abre a janela do compositor nas coordenadas
// abaixo, ja com a distorcao de lente aplicada, dois olhos lado a lado. A janela deve ficar sobre o
// monitor (virtual) que o Sunshine captura. A pose vem do celular por UDP (3DoF).
class HmdDevice : public ITrackedDeviceServerDriver, public IVRDisplayComponent {
public:
    // O SteamVR (modo estendido) exige que a janela do compositor cubra EXATAMENTE um monitor ("tela cheia"); senao
    // mostra "Erro do headset (-202)". Por isso a janela e SEMPRE o retangulo inteiro de um monitor real.
    // 'dev'/'status' vem de PrepareHeadsetMonitor (escolha do menu ou automatica).
    HmdDevice(const std::string& dev, uint32_t status) : status_(status) {
        wx_ = GetI(kHmdWinX); wy_ = GetI(kHmdWinY);
        ww_ = (uint32_t)GetI(kHmdWinW); wh_ = (uint32_t)GetI(kHmdWinH);
        bool found = false;
        for (const MonitorInfo& m : ListMonitors()) {
            if (m.dev != dev) continue;
            wx_ = m.x; wy_ = m.y; ww_ = (uint32_t)m.w; wh_ = (uint32_t)m.h; found = true;
            DiagLog("Janela do headset: %s (%s) %dx%d em %d,%d%s", m.dev.c_str(), m.name.c_str(), m.w, m.h, m.x, m.y, m.primary ? " [principal]" : "");
            break;
        }
        if (!found) { status_ = 2; DiagLog("Nenhum monitor encontrado: usando hmd_win_* (provavel erro -202)"); }
        fovH_ = GetF(kHmdFovH); fovV_ = GetF(kHmdFovV);
        ipd_ = GetF(kHmdIpd); hz_ = kHzTable[GetI(kHmdHzMode)]; scale_ = GetF(kHmdRenderScale);
        k1_ = GetF(kHmdK1); k2_ = GetF(kHmdK2); sep_ = GetF(kHmdSep) / 100.f;
        monitor_ = GetB(kHmdMonitor); antiIdle_ = GetB(kAntiIdle);
        if (monitor_) { ipd_ = 0.f; k1_ = k2_ = 0.f; sep_ = 0.f; }      // monitor comum: imagem unica, sem lente
        DLog("[stormvr] rastreamento da cabeca: %s", GetI(kTrackMode) == 1 ? "6DoF (posicao do celular)" : "3DoF (altura fixa)");
    }
    int32_t WinX() const { return wx_; }  int32_t WinY() const { return wy_; }
    uint32_t WinW() const { return ww_; } uint32_t WinH() const { return wh_; }
    uint32_t Status() const { return status_; }

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        auto* p = VRProperties();
        PropertyContainerHandle_t c = p->TrackedDeviceToPropertyContainer(id);
        p->SetStringProperty(c, Prop_ModelNumber_String, "StormVR HMD");
        p->SetStringProperty(c, Prop_ManufacturerName_String, "StormVR");
        p->SetStringProperty(c, Prop_TrackingSystemName_String, "stormvr");
        p->SetFloatProperty(c, Prop_UserIpdMeters_Float, ipd_);
        p->SetFloatProperty(c, Prop_UserHeadToEyeDepthMeters_Float, 0.f);
        p->SetFloatProperty(c, Prop_DisplayFrequency_Float, hz_);
        p->SetFloatProperty(c, Prop_SecondsFromVsyncToPhotons_Float, 0.011f);
        p->SetBoolProperty(c, Prop_IsOnDesktop_Bool, true);
        // Como o driver_sample da Valve: declara a que "universo" (sistema de coordenadas do chaperone) o headset pertence. Sem isso o SteamVR
        // reaproveita o universo 0 com transformacoes de salas antigas (giradas/rolagem) e apps que usam o espaco "stage" (Beat Saber) ficam
        // rotacionados em relacao aos que usam o espaco local. Id proprio = calibracao propria e limpa.
        p->SetUint64Property(c, Prop_CurrentUniverseId_Uint64, 0x5354524D56520001ULL);
        p->SetBoolProperty(c, Prop_WillDriftInYaw_Bool, false);
        p->SetBoolProperty(c, Prop_DeviceIsWireless_Bool, true);
        p->SetBoolProperty(c, Prop_DeviceCanPowerOff_Bool, false);
        p->SetBoolProperty(c, Prop_ContainsProximitySensor_Bool, false);
        DLog("[stormvr] HMD ativado (id=%u) janela %d,%d %ux%u  FOV %.0fx%.0f  k1=%.2f k2=%.2f", id, wx_, wy_, ww_, wh_, fovH_, fovV_, k1_, k2_);
        return VRInitError_None;
    }
    void Deactivate() override { id_ = k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char* name) override {
        if (name && !_stricmp(name, IVRDisplayComponent_Version)) return static_cast<IVRDisplayComponent*>(this);
        return nullptr;
    }
    void DebugRequest(const char*, char* resp, uint32_t size) override { if (size > 0) resp[0] = 0; }
    DriverPose_t GetPose() override { std::lock_guard<std::mutex> lk(mtx_); return pose_; }

    // ---- IVRDisplayComponent
    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* w, uint32_t* h) override { *x = wx_; *y = wy_; *w = ww_; *h = wh_; }
    bool IsDisplayOnDesktop() override { return true; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) override {
        *w = (uint32_t)((monitor_ ? ww_ : ww_ / 2) * scale_); *h = (uint32_t)(wh_ * scale_);
    }
    void GetEyeOutputViewport(EVREye eye, uint32_t* x, uint32_t* y, uint32_t* w, uint32_t* h) override {
        if (monitor_) { *x = 0; *y = 0; *w = ww_; *h = wh_; return; }            // os dois olhos usam a janela inteira (IPD 0 = a mesma imagem)
        *x = (eye == Eye_Left) ? 0 : ww_ / 2; *y = 0; *w = ww_ / 2; *h = wh_;
    }
    void GetProjectionRaw(EVREye, float* l, float* r, float* t, float* b) override {
        const float tv = (float)std::tan(fovV_ * 0.5 * kDeg2Rad);
        const float th = monitor_ ? tv * (float)ww_ / (float)std::max<uint32_t>(1, wh_) : (float)std::tan(fovH_ * 0.5 * kDeg2Rad);   // monitor: FOV horizontal pela proporcao da tela
        *l = -th; *r = th; *t = -tv; *b = tv;
    }
    DistortionCoordinates_t ComputeDistortion(EVREye eye, float u0, float v) override {
        // (u,v) = posicao no painel do olho (0..1). Devolve onde amostrar a imagem renderizada (0..1).
        if (monitor_) { DistortionCoordinates_t id; id.rfRed[0] = id.rfGreen[0] = id.rfBlue[0] = u0; id.rfRed[1] = id.rfGreen[1] = id.rfBlue[1] = v; return id; }
        const float u = u0 + (eye == Eye_Left ? sep_ : -sep_);   // afasta/aproxima os centros das imagens (ajuste fino por lente)
        const float aspect = (float)(ww_ / 2) / (float)wh_;
        const float px = (u - 0.5f) * 2.f * aspect, py = (v - 0.5f) * 2.f;      // unidades de meia-altura
        const float r2 = px * px + py * py;
        const float sc = 1.f + k1_ * r2 + k2_ * r2 * r2;
        const float su = 0.5f + (u - 0.5f) * sc, sv = 0.5f + (v - 0.5f) * sc;
        DistortionCoordinates_t d;
        d.rfRed[0] = d.rfGreen[0] = d.rfBlue[0] = su;
        d.rfRed[1] = d.rfGreen[1] = d.rfBlue[1] = sv;
        return d;
    }
    bool ComputeInverseDistortion(HmdVector2_t*, EVREye, uint32_t, float, float) override { return false; }

    // Publica a pose (3DoF: posicao fixa na altura do usuario)
    void LastPose(float pos[3], float up[3]) { for (int i = 0; i < 3; ++i) { pos[i] = lastPos_[i]; up[i] = lastUp_[i]; } }
    void Push(const HmdQuaternion_t& q, bool valid, const double pos[3]) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t pose = {};
        pose.qWorldFromDriverRotation = { 1, 0, 0, 0 };
        pose.qDriverFromHeadRotation  = { 1, 0, 0, 0 };
        pose.qRotation = q;
        for (int i = 0; i < 3; ++i) { pose.vecPosition[i] = pos[i]; lastPos_[i] = (float)pos[i]; }
        if (antiIdle_) {
            // pose parada ha mais de 3 s: desloca 1,2 mm no eixo X por ~100 ms a cada 3 s (o SteamVR entende como "tem alguem", sem sair do standby)
            const ULONGLONG nowMs = GetTickCount64();
            const bool same = std::fabs(idlePos_[0] - (float)pos[0]) < 1e-5f && std::fabs(idlePos_[1] - (float)pos[1]) < 1e-5f && std::fabs(idlePos_[2] - (float)pos[2]) < 1e-5f &&
                              std::fabs(idleQ_.w - q.w) < 1e-6 && std::fabs(idleQ_.x - q.x) < 1e-6 && std::fabs(idleQ_.y - q.y) < 1e-6 && std::fabs(idleQ_.z - q.z) < 1e-6;
            if (!same) { idlePos_[0] = (float)pos[0]; idlePos_[1] = (float)pos[1]; idlePos_[2] = (float)pos[2]; idleQ_ = q; idleSince_ = nowMs; nudgeOn_ = false; }
            else if (nowMs - idleSince_ > 3000) {
                if (!nudgeOn_ && nowMs >= nudgeAt_) { nudgeOn_ = true; nudgeAt_ = nowMs + 100; }
                else if (nudgeOn_ && nowMs >= nudgeAt_) { nudgeOn_ = false; nudgeAt_ = nowMs + 2900; }
                if (nudgeOn_) pose.vecPosition[0] += 0.0012;
            }
        }
        lastUp_[0] = (float)(2 * (q.x * q.y - q.w * q.z)); lastUp_[1] = (float)(1 - 2 * (q.x * q.x + q.z * q.z)); lastUp_[2] = (float)(2 * (q.y * q.z + q.w * q.x));
        pose.poseIsValid = valid;
        pose.deviceIsConnected = true;
        pose.result = valid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
        pose.shouldApplyHeadModel = false;
        pose.willDriftInYaw = false;
        { std::lock_guard<std::mutex> lk(mtx_); pose_ = pose; }
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose, sizeof(DriverPose_t));
    }

private:
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    float lastPos_[3] = { 0, 0, 0 }, lastUp_[3] = { 0, 1, 0 };
    int32_t wx_ = 0, wy_ = 0; uint32_t ww_ = 1920, wh_ = 1080;
    uint32_t status_ = 2;
    float fovH_ = 90, fovV_ = 90, ipd_ = 0.063f, sep_ = 0.f, hz_ = 60, scale_ = 1, k1_ = 0.34f, k2_ = 0.55f;
    bool monitor_ = false, antiIdle_ = true;
    ULONGLONG idleSince_ = 0, nudgeAt_ = 0; bool nudgeOn_ = false;
    float idlePos_[3] = { 1e9f, 0, 0 }; HmdQuaternion_t idleQ_ = { 1, 0, 0, 0 };
    std::mutex mtx_;
    DriverPose_t pose_ = {};
};

// ------------------------------------------------------------ guardiao da tela cheia (anti erro -202)
// Se a janela do compositor (vrcompositor.exe) sair do retangulo exato do monitor do headset, recoloca-a:
// sem borda, no topo, cobrindo o monitor. So mexe em janelas do vrcompositor.exe.
class FullscreenKeeper {
public:
    void Start(int x, int y, int w, int h, bool geometry = true) { x_ = x; y_ = y; w_ = w; h_ = h; geom_ = geometry; run_ = true; th_ = std::thread([this] { Run(); }); }
    void Stop() { run_ = false; if (th_.joinable()) th_.join(); }
    uint32_t Fixes() const { return fixes_; }

private:
    struct Ctx { FullscreenKeeper* self; };
    bool IsCompositor(DWORD pid) {
        auto it = cache_.find(pid);
        if (it != cache_.end()) return it->second;
        bool r = false;
        HANDLE hp = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
        if (hp) {
            char buf[MAX_PATH] = {}; DWORD n = MAX_PATH;
            if (QueryFullProcessImageNameA(hp, 0, buf, &n)) {
                std::string p(buf, n);
                for (char& c : p) c = (char)tolower((unsigned char)c);
                r = p.find("vrcompositor.exe") != std::string::npos;
            }
            CloseHandle(hp);
        }
        cache_[pid] = r;
        return r;
    }
    // Mantem a janela do compositor VIVA em segundo plano: sem isso o Windows 11 a trata como "oculta" (modo eficiencia / EcoQoS) e ela
    // para de atualizar (tela preta / vermelha ate clicar nela). Aqui: tira o processo do modo eficiencia, restaura se minimizada e mantem no topo.
    void KeepAlive(HWND h, DWORD pid) {
        if (pwr_.find(pid) == pwr_.end()) {
            pwr_[pid] = true;
            HANDLE hp = OpenProcess(PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
            if (hp) {
                PROCESS_POWER_THROTTLING_STATE ps = {};
                ps.Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
                ps.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
                ps.StateMask = 0;                                           // 0 = NAO limitar (desliga o modo eficiencia)
                SetProcessInformation(hp, ProcessPowerThrottling, &ps, sizeof(ps));
                SetPriorityClass(hp, ABOVE_NORMAL_PRIORITY_CLASS);
                CloseHandle(hp);
                DiagLog("Janela do compositor: modo eficiencia desligado (pid %lu)", (unsigned long)pid);
            }
        }
        if (IsIconic(h)) ShowWindow(h, SW_SHOWNOACTIVATE);
        if (!(GetWindowLongPtrA(h, GWL_EXSTYLE) & WS_EX_TOPMOST))
            SetWindowPos(h, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }
    static BOOL CALLBACK EnumCb(HWND h, LPARAM lp) {
        FullscreenKeeper* k = reinterpret_cast<Ctx*>(lp)->self;
        if (!IsWindowVisible(h)) return TRUE;
        DWORD pid = 0; GetWindowThreadProcessId(h, &pid);
        if (!k->IsCompositor(pid)) return TRUE;
        k->KeepAlive(h, pid);
        if (!k->geom_) return TRUE;
        RECT r; if (!GetWindowRect(h, &r)) return TRUE;
        const int w = r.right - r.left, hh = r.bottom - r.top;
        if (w < 300 || hh < 200) return TRUE;                              // ignora janelinhas auxiliares
        if (std::abs(r.left - k->x_) <= 1 && std::abs(r.top - k->y_) <= 1 && std::abs(w - k->w_) <= 1 && std::abs(hh - k->h_) <= 1) return TRUE;
        const ULONGLONG now = GetTickCount64();
        if (k->fixes_ >= 40 || now - k->lastFix_ < 2000) return TRUE;       // nao brigar com o SteamVR
        k->lastFix_ = now; ++k->fixes_;
        LONG_PTR st = GetWindowLongPtrA(h, GWL_STYLE);
        st &= ~(LONG_PTR)(WS_CAPTION | WS_THICKFRAME | WS_BORDER | WS_DLGFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
        st |= WS_POPUP;
        SetWindowLongPtrA(h, GWL_STYLE, st);
        SetWindowPos(h, HWND_TOPMOST, k->x_, k->y_, k->w_, k->h_, SWP_FRAMECHANGED | SWP_SHOWWINDOW | SWP_NOACTIVATE);
        DiagLog("Compositor fora da tela cheia (%d,%d %dx%d): recolocado em %d,%d %dx%d", r.left, r.top, w, hh, k->x_, k->y_, k->w_, k->h_);
        return TRUE;
    }
    void Run() {
        // coordenadas fisicas (iguais as do EnumDisplaySettings), independente do DPI do vrserver
        typedef HANDLE (WINAPI *SetCtx)(HANDLE);
        if (SetCtx f = reinterpret_cast<SetCtx>(GetProcAddress(GetModuleHandleA("user32.dll"), "SetThreadDpiAwarenessContext")))
            f((HANDLE)-4);
        Ctx c{ this };
        const bool awake = GetB(kAntiIdle);
        while (run_) {
            // tela do headset NUNCA apaga/dorme enquanto o driver estiver ativo (tela preta quando o usuario fica parado: era o Windows desligando o monitor)
            if (awake) SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
            EnumWindows(&FullscreenKeeper::EnumCb, reinterpret_cast<LPARAM>(&c));
            for (int i = 0; i < 10 && run_; ++i) Sleep(50);   // 0,5 s
        }
        if (awake) SetThreadExecutionState(ES_CONTINUOUS);
    }
    int x_ = 0, y_ = 0, w_ = 0, h_ = 0;
    std::atomic<bool> run_{ false };
    std::atomic<uint32_t> fixes_{ 0 };
    ULONGLONG lastFix_ = 0;
    std::unordered_map<DWORD, bool> cache_, pwr_;
    bool geom_ = true;
    std::thread th_;
};

// -------------------------------------------------------------- provider
class Provider : public IServerTrackedDeviceProvider {
public:
    EVRInitError Init(IVRDriverContext* ctx) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
        DLog("[stormvr] Init");
        timeBeginPeriod(1);
        OpenLive();
        udpL_.SetForced(HAND_LEFT);
        udp_.Start(GetI(kUdpPort));
        udpL_.Start(GetI(kUdpPortL));
        head_.Start(GetI(kHeadPort));
        hand_.Start(GetI(kHandPort));
        if (GetB(kHmdEnabled)) {            // o HMD precisa ser adicionado antes dos controles (indice 0)
            RestoreDisplayLayout();         // sobra de uma sessao anterior que fechou de forma abrupta
            std::string hdev; uint32_t hstatus = 0;
            PrepareHeadsetMonitor(hdev, hstatus);
            hmd_ = std::make_unique<HmdDevice>(hdev, hstatus);
            VRServerDriverHost()->TrackedDeviceAdded("STORM-HMD", TrackedDeviceClass_HMD, hmd_.get());
            fs_.Start(hmd_->WinX(), hmd_->WinY(), (int)hmd_->WinW(), (int)hmd_->WinH(), GetB(kForceFs));   // sempre roda: mantem a janela viva; so recoloca a geometria com "Forcar tela cheia"
        }
        profile_ = GetI(kProfile);
        DiagLog("Perfil do controle: %d (0=Oculus 1=Vive 2=Index 3=Generico)", profile_);
        pad_[HAND_RIGHT] = std::make_unique<KbController>(HAND_RIGHT, profile_);
        pad_[HAND_LEFT]  = std::make_unique<KbController>(HAND_LEFT, profile_);
        for (int h = 0; h < HAND_COUNT; ++h)
            VRServerDriverHost()->TrackedDeviceAdded(pad_[h]->Serial(), TrackedDeviceClass_Controller, pad_[h].get());

        running_ = true;
        thread_ = std::thread([this] { Loop(); });
        if (GetB(kAntiIdle)) {                                   // melhor esforco: o SteamVR pode ignorar/recusar estas chaves
            EVRSettingsError pe;
            VRSettings()->SetBool("power", "pauseCompositorOnStandby", false, &pe);
            VRSettings()->SetFloat("power", "turnOffScreensTimeout", 86400.f, &pe);
            VRSettings()->SetBool("power", "turnOffControllersOnStandby", false, &pe);
        }
        if (GetB(kConfigOverlay, true)) LaunchConfigApp();
        if (GetB(kWebAutostart, true)) LaunchWebApp();          // painel WEB no celular: sobe e desce junto com o SteamVR
        return VRInitError_None;
    }

    void Cleanup() override {
        StopWebApp();
        running_ = false;
        if (thread_.joinable()) thread_.join();
        udp_.Stop();
        udpL_.Stop();
        head_.Stop();
        hand_.Stop();
        fs_.Stop();
        hmd_.reset();
        if (g_madePrimary) { RestoreDisplayLayout(); g_madePrimary = false; }   // devolve o monitor principal original
        pad_[HAND_RIGHT].reset();
        pad_[HAND_LEFT].reset();
        CloseLive();
        timeEndPeriod(1);
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }

    const char* const* GetInterfaceVersions() override { return k_InterfaceVersions; }

    void RunFrame() override {
        VREvent_t ev;
        while (VRServerDriverHost()->PollNextEvent(&ev, sizeof(ev))) {
            if (ev.eventType == VREvent_Input_HapticVibration) {
                for (int h = 0; h < HAND_COUNT; ++h)
                    if (pad_[h] && pad_[h]->IsHaptic(ev.data.hapticVibration.componentHandle))
                        DLog("[stormvr] vibracao no controle %d (%.2fs)", h, ev.data.hapticVibration.fDurationSeconds);
            }
        }
    }

    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    static void LaunchConfigApp();
    static void LaunchWebApp();
    static void StopWebApp();
    void Loop();

    std::unique_ptr<KbController> pad_[HAND_COUNT];
    std::thread thread_;
    std::atomic<bool> running_{ false };
    int profile_ = PF_OCULUS;
    bool SendCtl(uint32_t key, uint8_t cmd, uint8_t arg0 = 0) { return udp_.SendCmdTo(key, cmd, arg0) || udpL_.SendCmdTo(key, cmd, arg0); }
    CtlRx udp_;      // porta do controle direito (e legado, mao pela flag)
    CtlRx udpL_;     // porta propria do controle esquerdo: tudo que chega aqui e a mao esquerda
    HeadReceiver head_;
    HandReceiver hand_;
    std::unique_ptr<HmdDevice> hmd_;
    FullscreenKeeper fs_;
};

void Provider::LaunchConfigApp() {
    HMODULE mod = nullptr;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCSTR>(&Provider::LaunchConfigApp), &mod)) return;
    char path[MAX_PATH] = {};
    GetModuleFileNameA(mod, path, MAX_PATH);
    char* slash = strrchr(path, '\\');
    if (!slash) return;
    *slash = 0;
    std::string dir = path;
    std::string exe = dir + "\\stormvr_config.exe";
    if (GetFileAttributesA(exe.c_str()) == INVALID_FILE_ATTRIBUTES) {
        DLog("[stormvr] app de configuracao nao encontrado: %s", exe.c_str());
        return;
    }
    STARTUPINFOA si = {}; si.cb = sizeof(si);
    PROCESS_INFORMATION pi = {};
    std::string cmd = "\"" + exe + "\"";
    if (CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | DETACHED_PROCESS,
                       nullptr, dir.c_str(), &si, &pi)) {
        CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
        DLog("[stormvr] app de configuracao iniciado");
    } else {
        DLog("[stormvr] falha ao iniciar app de configuracao (erro %lu)", GetLastError());
    }
}

// ---- Painel WEB (web/stormvr_web.py) junto com o SteamVR -------------------------------------------------------------------------------
// O driver sobe o servidor Python quando o SteamVR inicia e o encerra quando o SteamVR fecha. O processo entra num JOB com "kill on close":
// se o vrserver cair (ou o driver descarregar), o Windows mata o painel junto - nunca sobra um servidor orfao ocupando a porta 8787.
static HANDLE g_webJob = nullptr;

static bool WebPortBusy(int port) {            // ja ha algo ouvindo (painel aberto na mao, ou outra instancia)? entao nao sobe outro
    WSADATA w; if (WSAStartup(MAKEWORD(2, 2), &w) != 0) return false;
    bool busy = false;
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s != INVALID_SOCKET) {
        u_long nb = 1; ioctlsocket(s, FIONBIO, &nb);
        sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_port = htons((u_short)port); a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a));
        fd_set wf; FD_ZERO(&wf); FD_SET(s, &wf); timeval tv = { 0, 200 * 1000 };
        if (select(0, nullptr, &wf, nullptr, &tv) > 0) { int err = 0, el = sizeof(err); getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&err), &el); busy = err == 0; }
        closesocket(s);
    }
    WSACleanup();
    return busy;
}

void Provider::LaunchWebApp() {
    HMODULE mod = nullptr;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCSTR>(&Provider::LaunchWebApp), &mod)) return;
    char path[MAX_PATH] = {};
    GetModuleFileNameA(mod, path, MAX_PATH);
    char* slash = strrchr(path, '\\');
    if (!slash) return;
    *slash = 0;                                                    // ...\drivers\stormvr\bin\win64
    const std::string bin = path;
    const std::string cands[] = { bin + "\\..\\..\\web\\stormvr_web.py", bin + "\\web\\stormvr_web.py" };
    std::string script, workdir;
    for (const std::string& c : cands) {
        if (GetFileAttributesA(c.c_str()) == INVALID_FILE_ATTRIBUTES) continue;
        script = c; workdir = c.substr(0, c.find_last_of('\\')); break;
    }
    if (script.empty()) { DLog("[stormvr] painel WEB nao encontrado (copie a pasta web para drivers\\stormvr\\web: o install.bat faz isso)"); return; }
    if (WebPortBusy(8787)) { DLog("[stormvr] painel WEB ja esta rodando (porta 8787): nao abro outro"); return; }

    if (!g_webJob) {
        g_webJob = CreateJobObjectA(nullptr, nullptr);
        if (g_webJob) {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION li = {};
            li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            SetInformationJobObject(g_webJob, JobObjectExtendedLimitInformation, &li, sizeof(li));
        }
    }
    // Sem janela de console. pyw/pythonw nao abrem console; py/python entram como alternativa (CREATE_NO_WINDOW).
    const char* launchers[] = { "pyw.exe -3", "pythonw.exe", "py.exe -3", "python.exe" };
    for (const char* L : launchers) {
        std::string cmd = std::string(L) + " \"" + script + "\" --driver";
        STARTUPINFOA si = {}; si.cb = sizeof(si);
        PROCESS_INFORMATION pi = {};
        if (CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | CREATE_BREAKAWAY_FROM_JOB,
                           nullptr, workdir.c_str(), &si, &pi) ||
            CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                           nullptr, workdir.c_str(), &si, &pi)) {
            if (g_webJob) AssignProcessToJobObject(g_webJob, pi.hProcess);
            CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
            DLog("[stormvr] painel WEB iniciado (%s) - abra http://<IP-do-PC>:8787 no celular", L);
            return;
        }
    }
    DLog("[stormvr] nao consegui iniciar o painel WEB: instale o Python 3 (python.org) com o launcher \"py\" (erro %lu)", GetLastError());
}

void Provider::StopWebApp() {
    if (g_webJob) { CloseHandle(g_webJob); g_webJob = nullptr; DLog("[stormvr] painel WEB encerrado"); }   // fechar o JOB mata o processo
}

void Provider::Loop() {
    using clock = std::chrono::steady_clock;
    ApplyExternalIni();
    Config cfg = LoadConfig();
    udp_.SetPort(cfg.port);
    udpL_.SetPort(cfg.portL);
    head_.SetPort(cfg.headPort);
    hand_.SetPort(cfg.handPort);

    // Estado de cada mao (teclado / posicao)
    struct Hand {
        float fing[5] = {};
        double torsoYaw = 0, armHand[3] = { 0, 0, 0 }; bool torsoInit = false, armInit = false;
        clock::time_point trigUntil{}, gripUntil{}; bool trigWas = false, gripWas = false;   // toque curto: o botao fica apertado ao menos o tempo minimo
        double yaw = 0, pitch = 0;
        float  off[3] = {};        // deslocamento em uso (teclado altera)
        float  seen[3] = {};       // ultimo valor lido das configuracoes
        double prevPos[3] = {};
        bool   havePrev = false, prevReset = false, dirty = false;
        float  hp[3] = {};         // posicao da mao rastreada (filtrada), referencial do corpo
        bool   hpInit = false, prevPinch = false;
        float  trig = 0, grip = 0;
        clock::time_point lastWrite = clock::now();
    } hs[HAND_COUNT];
    for (int h = 0; h < HAND_COUNT; ++h)
        for (int i = 0; i < 3; ++i) hs[h].off[i] = hs[h].seen[i] = cfg.off[h][i];

    // Estado do controle real (ESP32 ou celular) que ocupa cada mao
    struct Esp {
        uint32_t ip = 0;           // fonte que ocupa esta mao (0 = nenhuma)
        bool  linked = false, calibrating = false, goodNow = false, good = false, everGood = false, everLinked = false;
        bool  forceCenter = false, prevCombo = false, pendingZero = false;
        clock::time_point lastGood = clock::now();
        float raw[3] = {};         // roll, pitch, yaw brutos
        float off[3] = {};         // referencia de "zero" (recentrar)
        float mpu[3] = {};         // depois do recentrar + zona morta
        float out[C_COUNT] = {};   // yaw, pitch, roll do controle (suavizados)
        float pred[C_COUNT] = {};  // out + previsao de movimento (o que vai para o SteamVR)
        float vel[C_COUNT] = {}, lastOut[C_COUNT] = {};   // graus/s e ultimo valor visto
        clock::time_point chg[C_COUNT] = { clock::now(), clock::now(), clock::now() };
        uint16_t btn = 0;          // botoes fisicos (antes do combo)
        uint16_t btnEff = 0;       // botoes depois do combo
        float sx = 0, sy = 0, nx = 0, ny = 0;
        bool prevLinked = false, prevCal = false, prevMpuErr = false;
        bool outs[O_COUNT] = {};   // funcoes acionadas por ESTE controle agora
        bool prevOut[O_COUNT] = {};
        uint64_t outBits = 0;
        int sentChannel = -1;
        bool clutch = false;
        float clutchRaw[3] = {};
        CtlRx::Snap snap;          // ultimo estado da fonte (valido se linked)
        // ---- modo QUATERNION (etapa 20)
        bool useQ = false; ori::Q qOut;                    // qOut: orientacao final no referencial do SteamVR (sem o yaw do mundo)
        ori::AngVel av; ori::OneEuroQ qf; ori::QBuf qb;
        uint16_t lastSeq = 0; uint32_t lastUp = 0; bool seqInit = false; bool legacyWarned = false;
        clock::time_point arrive = clock::now();            // chegada do ultimo pacote NOVO
        bool wasQ = false;
    } es[HAND_COUNT];
    struct Head {                  // estado da cabeca (celular)
        bool have = false, live = false, haveSession = false;
        uint32_t session = 0, lastRecenter = 0;
        HmdQuaternion_t q = { 1, 0, 0, 0 };
        double yaw0 = 0;
        ori::AngVel av; ori::OneEuroQ qf; ori::QBuf qb; uint32_t lastSeq = 0; bool seqInit = false;
        double pos[3] = { 0, 0, 0 }; bool havePos = false; bool mode6 = false; double extra[3] = { 0, 0, 0 }; bool haveExtra = false;
        HmdQuaternion_t lastQ = { 1, 0, 0, 0 }, dq = { 1, 0, 0, 0 }; float dtc = 0.016f; clock::time_point chg = clock::now();
    } hd;
    LONG lastCmd[CMD_COUNT];
    for (int i = 0; i < CMD_COUNT; ++i) lastCmd[i] = ReadCmd(i);
    int lastAssignSig = -1;
    uint32_t unassignedSeen = 0; char extraIpSeen[16] = {};

    bool prevToggle = false, handLiveWas = false;
    auto last = clock::now();
    unsigned tick = 0;

    while (running_) {
        auto now = clock::now();
        float dt = std::chrono::duration<float>(now - last).count();
        last = now;
        dt = Clampf(dt, 0.0f, 0.05f);

        // Recarrega configuracoes ~5x por segundo
        if (++tick % 50 == 0) {
            ApplyExternalIni();
            { static int ctr = 0; if (++ctr % 3 == 0) WriteStateIni(); }
            cfg = LoadConfig();
            udp_.SetPort(cfg.port);
            udpL_.SetPort(cfg.portL);
            head_.SetPort(cfg.headPort);
            hand_.SetPort(cfg.handPort);
            for (int h = 0; h < HAND_COUNT; ++h)
                for (int i = 0; i < 3; ++i)
                    if (std::fabs(cfg.off[h][i] - hs[h].seen[i]) > 1e-4f) {   // mudou pela interface
                        hs[h].off[i] = hs[h].seen[i] = cfg.off[h][i];
                    }
        }

        // Atalho liga/desliga do teclado
        bool tg = KeyDown(cfg.kToggle);
        if (tg && !prevToggle) { cfg.keysEnabled = !cfg.keysEnabled; SetB(kKeysEnabled, cfg.keysEnabled); }
        prevToggle = tg;

        // ------------------------------------------------ Controles reais: quem ocupa qual mao
        CtlRx::Snap snaps[CtlRx::kMax * 2];
        int nSrc = udp_.Snapshot(snaps, cfg.espTimeout);
        nSrc += udpL_.Snapshot(snaps + CtlRx::kMax, cfg.espTimeout);
        const int assignSig = cfg.assign * 4 + (cfg.swapHands ? 2 : 0);
        if (assignSig != lastAssignSig) {                       // mudou o modo/troca: reatribui tudo
            lastAssignSig = assignSig;
            for (int h = 0; h < HAND_COUNT; ++h) es[h].ip = 0;
        }
        auto findSnap = [&](uint32_t ip) -> const CtlRx::Snap* {
            if (!ip) return nullptr;
            for (const CtlRx::Snap& sn : snaps) if (sn.used && sn.ip == ip) return &sn;
            return nullptr;
        };
        auto handByFlag = [&](const CtlRx::Snap& sn) {
            const bool left = (sn.pkt.flags & KBT_FLAG_HAND_LEFT) != 0;
            return (left != cfg.swapHands) ? (int)HAND_LEFT : (int)HAND_RIGHT;
        };
        // 1) libera maos cuja fonte sumiu; no modo automatico, move quem mudou de mao se a outra estiver livre
        for (int h = 0; h < HAND_COUNT; ++h) {
            if (!es[h].ip) continue;
            const CtlRx::Snap* sn = findSnap(es[h].ip);
            if (!sn || !sn->alive) { es[h].ip = 0; continue; }
            if (cfg.assign == 0 && sn->forced < 0) {
                const int want = handByFlag(*sn);
                if (want != h && es[want].ip == 0) {
                    DiagLog("Controle %s mudou para a mao %s", sn->ipStr, want == HAND_LEFT ? "ESQUERDA" : "DIREITA");
                    es[want].ip = es[h].ip; es[h].ip = 0;
                }
            }
        }
        // 2) fontes vivas sem mao, da mais antiga para a mais nova
        unassignedSeen = 0; extraIpSeen[0] = 0;
        {
            int idx[CtlRx::kMax * 2], n = 0;
            for (int i = 0; i < CtlRx::kMax * 2; ++i) {
                const CtlRx::Snap& sn = snaps[i];
                if (!sn.used || !sn.alive || sn.ip == es[0].ip || sn.ip == es[1].ip) continue;
                idx[n++] = i;
            }
            std::sort(idx, idx + n, [&](int a, int b) { return snaps[a].order < snaps[b].order; });
            for (int k = 0; k < n; ++k) {
                const CtlRx::Snap& sn = snaps[idx[k]];
                int target = -1;
                if (sn.forced >= 0) {                                            // porta exclusiva: a mao e a da porta
                    target = es[sn.forced].ip == 0 ? sn.forced : -1;
                } else if (cfg.assign == 2) {                                           // IP fixo
                    for (int h = 0; h < HAND_COUNT; ++h) {
                        const std::string& want = cfg.ctlIp[cfg.swapHands ? 1 - h : h];
                        if (!want.empty() && want == sn.ipStr && es[h].ip == 0) target = h;
                    }
                } else if (cfg.assign == 1) {                                    // ordem de conexao
                    const int first = cfg.swapHands ? (int)HAND_LEFT : (int)HAND_RIGHT, second = 1 - first;
                    target = es[first].ip == 0 ? first : (es[second].ip == 0 ? second : -1);
                } else {                                                         // automatico (mao do controle)
                    const int want = handByFlag(sn);
                    target = es[want].ip == 0 ? want : (es[1 - want].ip == 0 ? 1 - want : -1);
                }
                if (target >= 0) {
                    es[target].ip = sn.ip;
                    const bool esp = sn.pkt.rssi != 0 || sn.pkt.gyro[0] != 0.f || sn.pkt.gyro[1] != 0.f || sn.pkt.gyro[2] != 0.f;
                    DiagLog("Controle %s (%s) ocupa a mao %s", sn.ipStr, esp ? "ESP32" : "celular", target == HAND_LEFT ? "ESQUERDA" : "DIREITA");
                } else { ++unassignedSeen; std::memcpy(extraIpSeen, sn.ipStr, sizeof(extraIpSeen)); }
            }
        }
        (void)nSrc;

        // 3) estado de cada mao a partir da sua fonte
        for (int h = 0; h < HAND_COUNT; ++h) {
            Esp& e = es[h];
            const CtlRx::Snap* sn = findSnap(e.ip);
            e.snap = sn ? *sn : CtlRx::Snap();
            e.linked = cfg.srcMode != 2 && sn && sn->alive;
            e.calibrating = e.linked && (e.snap.pkt.flags & KBT_FLAG_CALIBRATING);
            e.goodNow = e.linked && !e.calibrating;
            e.btn = e.linked ? e.snap.pkt.buttons : 0;
            if (e.goodNow) for (int i = 0; i < 3; ++i) e.raw[i] = e.snap.pkt.rpy[i];
            if (e.linked) e.everLinked = true;
        }

        // Comandos do menu (valem para os dois controles)
        auto cmdFired = [&](int c) { const LONG v = ReadCmd(c); if (v != lastCmd[c]) { lastCmd[c] = v; return true; } return false; };
        if (cmdFired(CMD_RECENTER_YAW)) for (auto& e : es) e.off[M_YAW] = e.raw[M_YAW];
        if (cmdFired(CMD_RECENTER_YAW_R)) es[HAND_RIGHT].off[M_YAW] = es[HAND_RIGHT].raw[M_YAW];      // so o controle DIREITO
        if (cmdFired(CMD_RECENTER_YAW_L)) es[HAND_LEFT].off[M_YAW] = es[HAND_LEFT].raw[M_YAW];        // so o controle ESQUERDO
        if (cmdFired(CMD_ZERO_ALL)) for (auto& e : es) for (int i = 0; i < 3; ++i) e.off[i] = e.raw[i];
        if (cmdFired(CMD_GYRO_CAL)) for (auto& e : es) if (e.linked && SendCtl(e.ip, KBT_CMD_CALIBRATE_GYRO)) e.forceCenter = true;
        if (cmdFired(CMD_STICK_CENTER)) {
            for (int h = 0; h < HAND_COUNT; ++h) if (es[h].linked) {
                cfg.stickC[h][0] = es[h].snap.pkt.joyX; cfg.stickC[h][1] = es[h].snap.pkt.joyY;
                SetI(kStickC[h][0].key, cfg.stickC[h][0]); SetI(kStickC[h][1].key, cfg.stickC[h][1]);
                DiagLog("Centro do analogico (%s) calibrado: %d, %d", h == HAND_LEFT ? "esq." : "dir.", cfg.stickC[h][0], cfg.stickC[h][1]);
            }
        }
        if (cmdFired(CMD_RESTART_UDP)) { udp_.Restart(); udpL_.Restart(); head_.Restart(); hand_.Restart(); for (auto& e : es) e.ip = 0; }
        for (int h = 0; h < HAND_COUNT; ++h) {
            if (cmdFired(CMD_FLIP_HAND_R + h) && es[h].linked) {                 // vira a mao do controle que ocupa 'h' (grava no ESP32)
                const bool isLeft = (es[h].snap.pkt.flags & KBT_FLAG_HAND_LEFT) != 0;
                if (SendCtl(es[h].ip, KBT_CMD_SET_HAND, isLeft ? 0 : 1))
                    DiagLog("Pedido enviado: o controle %s passa a ser a mao %s", es[h].snap.ipStr, isLeft ? "DIREITA" : "ESQUERDA");
            }
            if (cmdFired(CMD_PIN_R + h) && es[h].ip) {
                SetS(kCtlIp[cfg.swapHands ? 1 - h : h], es[h].snap.ipStr);
                DiagLog("IP %s fixado para a mao %s (modo IP fixo)", es[h].snap.ipStr, h == HAND_LEFT ? "esquerda" : "direita");
            }
        }

        // ---- por controle: conexao, canal, combo, botoes -> funcoes, acoes, eixos, analogico
        bool outAny[O_COUNT] = {};
        for (int h = 0; h < HAND_COUNT; ++h) {
            Esp& e = es[h];
            const KbtPacket& pk = e.snap.pkt;
            const char* side = h == HAND_LEFT ? "esquerdo" : "direito";

            // Conexao nova (ou volta depois de >3 s): recentra o yaw
            if (e.goodNow && !e.good) {
                const bool longGap = !e.everGood || std::chrono::duration<float>(now - e.lastGood).count() > 3.f;
                if ((cfg.autoCenter && longGap) || e.forceCenter) e.off[M_YAW] = e.raw[M_YAW];
                if (cfg.autoZeroConn && (longGap || e.forceCenter)) e.pendingZero = true;      // zera tambem pitch/roll quando o giroscopio terminar de calibrar
                e.forceCenter = false; e.everGood = true;
            }
            if (e.goodNow) e.lastGood = now;
            e.good = e.goodNow;
            if (e.pendingZero && e.goodNow && !e.calibrating) {   // a cada (re)conexao o ESP32 recomeca a referencia: sem isto o controle ficava de cabeca pra baixo
                for (int i = 0; i < 3; ++i) e.off[i] = e.raw[i];
                e.pendingZero = false; DiagLog("Eixos do controle %s zerados ao conectar", side);
            }
            if (e.linked != e.prevLinked) {
                e.prevLinked = e.linked;
                if (e.linked) DiagLog("Controle %s conectado: %s", side, e.snap.ipStr);
                else if (cfg.srcMode != 2) DiagLog("Controle %s SEM PACOTES (timeout %.1fs)", side, cfg.espTimeout);
            }
            if (e.linked) {
                const bool er = (pk.flags & KBT_FLAG_MPU_ERROR) != 0;
                if (er != e.prevMpuErr) { e.prevMpuErr = er; DiagLog(er ? "MPU6050 (%s) NAO RESPONDE (SDA/SCL/3V3/GND)" : "MPU6050 (%s) voltou a responder", side); }
                if (e.calibrating != e.prevCal) { e.prevCal = e.calibrating; DiagLog(e.calibrating ? "Calibrando giroscopio (%s): deixe parado" : "Calibracao do giroscopio (%s) concluida", side); }
            }
            // Canal do ESP32 (Auto / SteamVR Wi-Fi / Android Bluetooth): reenviado ao conectar e quando muda no menu
            if (!e.linked) e.sentChannel = -1;
            else if (e.sentChannel != cfg.espChannel) {
                if (SendCtl(e.ip, KBT_CMD_SET_MODE, (uint8_t)cfg.espChannel)) {
                    e.sentChannel = cfg.espChannel;
                    DiagLog("Canal do ESP32 (%s): %s", side, cfg.espChannel == KBT_CH_AUTO ? "Auto" : (cfg.espChannel == KBT_CH_STEAMVR ? "SteamVR (Wi-Fi)" : "Android (Bluetooth)"));
                }
            }

            // Combo L3 + Start = recentrar yaw (nao envia esses dois botoes ao jogo)
            const bool combo = cfg.comboRecenter && e.good && (e.btn & KBT_BTN_L3) && (e.btn & KBT_BTN_START);
            if (combo && !e.prevCombo) { e.off[M_YAW] = e.raw[M_YAW]; if (hd.have) hd.yaw0 = YawOf(hd.q); }
            e.prevCombo = combo;
            e.btnEff = combo ? (uint16_t)(e.btn & ~(KBT_BTN_L3 | KBT_BTN_START)) : e.btn;

            // Botoes fisicos -> funcoes (tabela PROPRIA da mao; varios botoes podem ter a mesma funcao)
            std::memset(e.outs, 0, sizeof(e.outs));
            e.outBits = 0;
            for (int pb = 0; pb < P_COUNT; ++pb) {
                const int ob = profile_ == PF_FRAME ? cfg.bindF[h][pb] : cfg.bind[h][pb];       // cada perfil tem a sua tabela
                if (((e.btnEff >> pb) & 1u) && ob > 0 && ob < O_COUNT) e.outs[ob] = true;
            }
            for (int o = 1; o < O_COUNT; ++o) if (e.outs[o]) { e.outBits |= (1ull << o); outAny[o] = true; }

            // ---- ACOES disparadas por botoes fisicos
            auto rising = [&](int o) { return e.outs[o] && !e.prevOut[o]; };
            if (rising(O_ACT_RECENTER_CTL) || rising(O_ACT_RECENTER_ALL)) e.off[M_YAW] = e.raw[M_YAW];
            if ((rising(O_ACT_RECENTER_HEAD) || rising(O_ACT_RECENTER_ALL)) && hd.have) hd.yaw0 = YawOf(hd.q);
            if (rising(O_ACT_ZERO)) for (int i = 0; i < 3; ++i) e.off[i] = e.raw[i];
            if (rising(O_ACT_CALIBRATE) && SendCtl(e.ip, KBT_CMD_CALIBRATE_GYRO)) e.forceCenter = true;
            if (rising(O_ACT_ARM_TOGGLE)) { cfg.armEnabled = !cfg.armEnabled; SetB(kArmEnabled.key, cfg.armEnabled); DiagLog("Arm Model %s (botao)", cfg.armEnabled ? "LIGADO" : "desligado"); }
            // embreagem: segurando, a orientacao congela; ao soltar continua do mesmo ponto (sem salto)
            const bool clutchNow = e.outs[O_ACT_CLUTCH];
            if (clutchNow && !e.clutch) for (int i = 0; i < 3; ++i) e.clutchRaw[i] = e.raw[i];
            if (!clutchNow && e.clutch) for (int i = 0; i < 3; ++i) e.off[i] += Wrap180(e.raw[i] - e.clutchRaw[i]);
            e.clutch = clutchNow;
            for (int o = 0; o < O_COUNT; ++o) e.prevOut[o] = e.outs[o];
            if (e.good && cfg.ctl[h].yawDrift != 0.f) e.off[M_YAW] += cfg.ctl[h].yawDrift * dt / 60.f;            // compensacao da deriva do yaw

            // Eixos: recentrar -> zona morta (por eixo do MPU) -> mapeamento -> inverter/ganho -> suavizar
            for (int i = 0; i < 3; ++i) {
                float v = Wrap180(e.raw[i] - e.off[i]);
                const float dz = cfg.ctl[h].mpuDz[i];
                if (std::fabs(v) <= dz) v = 0.f; else v -= (v > 0 ? dz : -dz);
                e.mpu[i] = v;
            }
            if (e.good && !e.clutch) {
                for (int a = 0; a < C_COUNT; ++a) {
                    const int sa = cfg.ctl[h].axSrc[a];
                    float v = (cfg.ctl[h].axEn[a] && sa >= 0 && sa < 3) ? e.mpu[sa] : 0.f;   // desativado ou "Nenhum" => 0
                    if (cfg.ctl[h].axInv[a]) v = -v;
                    v *= cfg.ctl[h].axGain[a];
                    const float tau = cfg.ctl[h].axSmooth[a] / 1000.f;                      // suavizacao individual
                    float al = tau <= 0.f ? 1.f : 1.f - std::exp(-dt / tau);
                    const float dlt = Wrap180(v - e.out[a]);
                    // anti-salto: >30 graus em UM quadro (~2700 graus/s) nao e movimento de mao, e descontinuidade do Euler/sensor
                    // (apontar para cima, rolar 180...). Em vez de pular, desliza em ~60 ms.
                    if (std::fabs(dlt) > 30.f && e.everLinked) al = std::min(al, 1.f - std::exp(-dt / 0.06f));
                    e.out[a] = Wrap180(e.out[a] + dlt * al);
                }
                e.out[C_PITCH] = Clampf(e.out[C_PITCH], -89.f, 89.f);
            }

            // ---- Modo QUATERNION (etapa 20): a orientacao do controle vem do quaternion do MPU (sem angulos de Euler), entao nao ha
            // limite de +-89 graus nem pulos ao apontar para cima/baixo. Recentrar/zerar usam os mesmos deslocamentos (e.off) do modo antigo.
            // Cai para o modo antigo (Euler) se os eixos do MPU nao formam uma rotacao fisica valida, se houver ganho diferente de 1 ou se desligado.
            e.useQ = false;
            if (cfg.ctlQuat && e.linked && e.good) {
                int srcA[3]; bool invA[3], enA[3]; bool gainsOk = true;
                for (int a = 0; a < C_COUNT; ++a) { srcA[a] = cfg.ctl[h].axSrc[a]; invA[a] = cfg.ctl[h].axInv[a]; enA[a] = cfg.ctl[h].axEn[a]; if (std::fabs(cfg.ctl[h].axGain[a] - 1.f) > 0.01f) gainsOk = false; }
                double Pm[9];
                if (gainsOk && ori::makeBasis(srcA, invA, enA, Pm)) {
                    ori::Q qr = e.snap.hasQ ? ori::Q{ e.snap.q[0], e.snap.q[1], e.snap.q[2], e.snap.q[3] }
                                            : ori::fromEulerZYX(pk.rpy[0], pk.rpy[1], pk.rpy[2]);
                    // Buffer de jitter: guarda os pacotes com o instante de CAPTURA (relogio do ESP32) e INTERPOLA entre eles com um pequeno atraso
                    // (~1 pacote). Pacotes atrasados/em rajada do Wi-Fi nao viram teleportes e a velocidade angular medida so e usada na previsao.
                    const double nowS = std::chrono::duration<double>(now.time_since_epoch()).count();
                    const bool isNew = !e.seqInit || pk.seq != e.lastSeq;
                    if (isNew) {
                        if (!e.seqInit || pk.uptimeMs + 500u < e.lastUp) { e.av.reset(); e.qb.reset(); }
                        e.lastSeq = pk.seq; e.lastUp = pk.uptimeMs; e.seqInit = true;
                        e.av.push(qr, pk.uptimeMs * 1e-3);
                        e.qb.push(qr, pk.uptimeMs * 1e-3, nowS);
                    }
                    if (!e.wasQ) { e.qf.reset(); e.wasQ = true; }
                    ori::Q qIn = qr;
                    // sem pacotes novos (rede parou, taxa muito baixa): velocidade 0 => a pose FICA na ultima orientacao recebida ate chegar a proxima
                    const bool rxLive = e.qb.fresh(nowS);
                    const double wHold[3] = { 0, 0, 0 };
                    const double* wUse = rxLive ? e.av.w : wHold;
                    e.qb.sample(nowS, wUse, qIn);
                    // previsao no referencial do CORPO do MPU (antes de recentrar/zerar: la a velocidade medida esta no mesmo referencial)
                    if (cfg.predictMs > 0.f) {
                        const double tp = std::min(0.06, (double)cfg.predictMs / 1000.0);
                        qIn = ori::normalize(ori::mul(qIn, ori::expMap(wUse[0] * tp, wUse[1] * tp, wUse[2] * tp)));
                    }
                    const ori::Q qYaw = ori::rotZ(-(double)e.off[M_YAW] * ori::kD2R);
                    const ori::Q qTare = ori::conj(ori::mul(ori::rotY((double)e.off[M_PITCH] * ori::kD2R), ori::rotX((double)e.off[M_ROLL] * ori::kD2R)));
                    ori::Q qRel = ori::normalize(ori::mul(ori::mul(qYaw, qIn), qTare));
                    if (!e.clutch) {
                        if (cfg.ctlSmooth > 0.5f) qRel = e.qf.filter(qRel, std::max((double)dt, 1e-4), ori::cutoffFromPercent(cfg.ctlSmooth), 8.0);
                        else { e.qf.y = qRel; e.qf.init = true; }
                        e.qOut = ori::basisChange(Pm, qRel);
                    }
                    e.useQ = true;
                }
            }
            if (!e.useQ) {
                e.wasQ = false; e.seqInit = false;
                if (cfg.ctlQuat && e.linked && e.good && !e.legacyWarned) { e.legacyWarned = true; DiagLog("Controle %d: eixos/ganhos nao formam uma rotacao fisica valida (numero IMPAR de inversoes, eixo repetido ou ganho diferente de 1): usando o modo antigo (Euler, sem interpolacao). Ajuste na aba Eixos.", h); }
            }

            // Analogico (centro proprio de cada controle)
            if (e.linked) ProcessStick(cfg.ctl[h], cfg.stickC[h][0], cfg.stickC[h][1], pk.joyX, pk.joyY, e.sx, e.sy, e.nx, e.ny);
            else { e.sx = 0; e.sy = 0; e.nx = 0; e.ny = 0; }
        }
        uint64_t outBitsAll = 0;
        for (int o = 1; o < O_COUNT; ++o) if (outAny[o]) outBitsAll |= (1ull << o);
        float armJoints[HAND_COUNT][3][3] = {};
        bool armNow[HAND_COUNT] = {};

        // ------------------------------------------------ Cabeca (HMD) - pose do celular, independente do video
        if (hmd_) {
            const HeadReceiver::Info hi = head_.Get();
            const bool live = hi.any && hi.age < std::max(cfg.headTimeout, cfg.headHold);        // soluco de rede NAO derruba o rastreamento (cinza/pausa)
            if (hi.any) {
                const auto& pk = hi.pkt;
                HmdQuaternion_t q = { pk.quat[0], pk.quat[1], pk.quat[2], pk.quat[3] };
                const double n = std::sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
                if (n > 0.5 && n < 1.5) {
                    q = { q.w / n, q.x / n, q.y / n, q.z / n };
                    hd.q = q; hd.have = true;
                    const bool six = pk.trackingMode == 1 && (pk.flags & KBT_HEAD_F_POS);
                    hd.mode6 = six;
                    hd.haveExtra = !six && (pk.flags & KBT_HEAD_F_EXTRA) && std::isfinite(pk.pos[0]) && std::isfinite(pk.pos[1]);
                    if (hd.haveExtra) { hd.extra[0] = std::max(-1.0, std::min(1.0, (double)pk.pos[0])); hd.extra[1] = std::max(-1.2, std::min(0.6, (double)pk.pos[1])); }
                    if (six && std::isfinite(pk.pos[0]) && std::isfinite(pk.pos[1]) && std::isfinite(pk.pos[2])) {
                        for (int k = 0; k < 3; ++k) hd.pos[k] = pk.pos[k];      // posicao medida pelo celular
                        hd.havePos = true;
                    }
                    // Em 6DoF o celular ja entrega rotacao E posicao no mesmo referencial recentrado (mesmo calculo da Sala 3D):
                    // o driver nao reaplica yaw automatico; so o recentrar manual (menu/combo) gira os dois juntos.
                    if (!hd.haveSession || pk.session != hd.session) {      // sessao nova
                        hd.haveSession = true; hd.session = pk.session; hd.lastRecenter = pk.recenterCount; hd.av.reset(); hd.qb.reset(); hd.seqInit = false;
                        hd.yaw0 = six ? 0.0 : YawOf(q);
                    } else if (pk.recenterCount != hd.lastRecenter) {         // pedido de recentralizar no celular
                        hd.lastRecenter = pk.recenterCount; hd.av.reset(); hd.qb.reset(); if (!six) hd.yaw0 = YawOf(q);
                    }
                }
            }
            if (ReadCmd(CMD_RECENTER_HEAD) != lastCmd[CMD_RECENTER_HEAD]) {  // pedido pelo menu
                lastCmd[CMD_RECENTER_HEAD] = ReadCmd(CMD_RECENTER_HEAD);
                if (hd.have) hd.yaw0 = YawOf(hd.q);
            }
            if (live != hd.live) {
                hd.live = live;
                if (live) DiagLog("Pose da cabeca: recebendo de %s", hi.ip); else DiagLog("Pose da cabeca: SEM PACOTES (rastreamento perdido)");
            }
            HmdQuaternion_t qUse = hd.q;
            if (hd.have) {
                // Buffer de jitter + velocidade angular medida com o relogio de CAPTURA do celular (timeUs): a cabeca nao pula quando o Wi-Fi atrasa pacotes.
                const double nowS = std::chrono::duration<double>(now.time_since_epoch()).count();
                if (hi.any && (!hd.seqInit || hi.pkt.seq != hd.lastSeq)) {
                    if (!hd.seqInit) { hd.av.reset(); hd.qb.reset(); }
                    hd.lastSeq = hi.pkt.seq; hd.seqInit = true; hd.chg = now;
                    const ori::Q qn{ hd.q.w, hd.q.x, hd.q.y, hd.q.z };
                    hd.av.push(qn, (double)hi.pkt.timeUs * 1e-6);
                    hd.qb.push(qn, (double)hi.pkt.timeUs * 1e-6, nowS);
                }
                // 1) interpolacao entre os dois pacotes mais recentes (atraso ~1 pacote)
                ori::Q qi{ hd.q.w, hd.q.x, hd.q.y, hd.q.z };
                const bool hLive = hd.qb.fresh(nowS);
                const double hHold[3] = { 0, 0, 0 };
                const double* hw = hLive ? hd.av.w : hHold;       // sem pacotes novos: segura a ultima orientacao (sem extrapolar)
                hd.qb.sample(nowS, hw, qi);
                // 2) suavizacao adaptativa (One Euro): parada = sem tremor; girando = quase sem atraso
                if (cfg.headSmooth > 0.5f) qi = hd.qf.filter(qi, std::max((double)dt, 1e-4), ori::cutoffFromPercent(cfg.headSmooth), 8.0);
                else { hd.qf.y = qi; hd.qf.init = true; }
                // 3) previsao para compensar a latencia ate o pixel (configuravel)
                if (cfg.headPredictMs > 0.f) {
                    const double t = std::min(0.08, (double)cfg.headPredictMs / 1000.0);
                    qi = ori::normalize(ori::mul(qi, ori::expMap(hw[0] * t, hw[1] * t, hw[2] * t)));
                }
                qUse = { qi.w, qi.x, qi.y, qi.z };
            }
            HmdQuaternion_t out = hd.have ? QuatMul(QuatAxisY(-hd.yaw0), qUse) : HmdQuaternion_t{ 1, 0, 0, 0 };
            if (cfg.hmdMonitor) out = HmdQuaternion_t{ 1, 0, 0, 0 };                      // MODO TV: o monitor e o headset e ele fica PARADO (sem movimento)
            out = QuatMul(QuatAxisY(cfg.worldYaw), out);                                  // gira o mundo (aba Headset: "Girar o mundo")
            if (cfg.headRoll != 0.0) out = QuatMul(out, QuatAxisZ(cfg.headRoll));
            if (cfg.headFlip) out = QuatMul(out, QuatAxisZ(3.14159265358979));      // opcional: gira 180 graus no eixo da frente
            // Antes do primeiro pacote a pose e valida (identidade) para o SteamVR iniciar; depois de um
            // timeout fica invalida (rastreamento perdido) mantendo a ultima orientacao.
            // posicao: 6DoF real quando o celular a envia (gira com o mesmo yaw manual); senao altura fixa (3DoF)
            double outPos[3] = { 0, 0, 0 };
            const bool usePos = hd.mode6 && hd.havePos && GetI(kTrackMode) == 1;   // 6DoF so quando escolhido na aba Headset
            if (usePos) {
                const double a = -hd.yaw0, c = std::cos(a), sn = std::sin(a);
                outPos[0] = hd.pos[0] * c + hd.pos[2] * sn;
                outPos[1] = hd.pos[1];
                outPos[2] = -hd.pos[0] * sn + hd.pos[2] * c;
            }
            { const double cw = std::cos(cfg.worldYaw), sw = std::sin(cfg.worldYaw); const double px = outPos[0], pz = outPos[2]; outPos[0] = px * cw + pz * sw; outPos[2] = -px * sw + pz * cw; }
            double fp[3] = { usePos ? outPos[0] : 0.0, cfg.userHeight + (usePos ? outPos[1] : 0.0) + (((GetTickCount64() / 500) & 1) ? 0.0003 : 0.0), usePos ? outPos[2] : 0.0 };   // 0,3 mm a cada 0,5 s: o SteamVR nao considera o headset parado
            if (!usePos && hd.haveExtra && !cfg.hmdMonitor) {   // 3DoF + inclinacao lateral / barometro (agachar) vindos do celular
                const double fx = -(2 * (out.x * out.z + out.w * out.y)), fz = -(1 - 2 * (out.x * out.x + out.y * out.y));
                const double n = std::sqrt(fx * fx + fz * fz);
                if (n > 1e-3) { const double rx = -fz / n, rz = fx / n; fp[0] += hd.extra[0] * rx; fp[2] += hd.extra[0] * rz; }
                fp[1] += hd.extra[1];
            }
            fp[0] += cfg.headOff[0]; fp[1] += cfg.headOff[1] + cfg.headHeightOff; fp[2] += cfg.headOff[2];     // aba Coordenadas
            if (cfg.headRot[0] != 0.0 || cfg.headRot[1] != 0.0 || cfg.headRot[2] != 0.0)
                out = QuatMul(out, QuatMul(QuatMul(QuatAxisY(cfg.headRot[1] * kDeg2Rad), QuatAxisX(cfg.headRot[0] * kDeg2Rad)), QuatAxisZ(cfg.headRot[2] * kDeg2Rad)));
            hmd_->Push(out, cfg.hmdMonitor || !hd.have || live, fp);
            if (g_live && tick % 8 == 0) {
                Live& L = *g_live;
                const double fx = -(2 * (out.x * out.z + out.w * out.y)), fy = 2 * (out.w * out.x - out.y * out.z), fz = -(1 - 2 * (out.x * out.x + out.y * out.y));
                const double rx = 1 - 2 * (out.y * out.y + out.z * out.z), ry = 2 * (out.x * out.y + out.w * out.z);
                const double uy = 1 - 2 * (out.x * out.x + out.z * out.z);
                L.headLink = !hd.have ? 0u : (live ? 1u : 2u);
                L.headRate = hi.rate;
                L.headYpr[0] = (float)(std::atan2(-fx, -fz) / kDeg2Rad);
                L.headYpr[1] = (float)(std::asin(std::max(-1.0, std::min(1.0, fy))) / kDeg2Rad);
                L.headYpr[2] = (float)(std::atan2(ry, uy) / kDeg2Rad); (void)rx;
                std::memcpy(L.headIp, hi.ip, sizeof(L.headIp));
            }
        }

        // Pose do headset (para os controles seguirem a direcao dele)
        double origin[3] = { 0, cfg.fixedHeight, 0 };
        double headYaw = cfg.worldYaw;          // sem headset valido: gira so pelo yaw do mundo
        if (cfg.follow) {
            static TrackedDevicePose_t poses[k_unMaxTrackedDeviceCount];
            VRServerDriverHost()->GetRawTrackedDevicePoses(0.f, poses, k_unMaxTrackedDeviceCount);
            const TrackedDevicePose_t& hp = poses[k_unTrackedDeviceIndex_Hmd];
            if (hp.bPoseIsValid) {
                const auto& m = hp.mDeviceToAbsoluteTracking.m;
                origin[0] = m[0][3]; origin[1] = m[1][3]; origin[2] = m[2][3];
                headYaw = std::atan2(m[0][2], m[2][2]);   // frente = -z => atan2(-fx,-fz) = atan2(m02, m22)
            }
        }

        // Seguir o headset = acompanhar o DESLOCAMENTO. Os eixos de rotacao do controle vem do IMU; so entram na direcao do
        // headset se "girar junto" estiver ligado.
        const double rotYaw = cfg.followRot ? headYaw : cfg.worldYaw;
        const bool keys = cfg.keysEnabled;
        const bool modMove = keys && KeyDown(cfg.kMove);
        const bool modDepth = keys && KeyDown(cfg.kDepth);

        // ------------------------------------------------ Maos rastreadas pela camera do celular (Sala VR)
        bool handPosOk[HAND_COUNT] = {}, handAimOk[HAND_COUNT] = {}, handPinchOk[HAND_COUNT] = {}, handTracked[HAND_COUNT] = {}, handPinchRaw[HAND_COUNT] = {};
        bool posCtl[HAND_COUNT] = {};          // posicao vinda do PROPRIO controle (celular 6DoF): referencial do controle, nao o do corpo/cabeca
        float handPos[HAND_COUNT][3] = {}, handAim[HAND_COUNT][3] = {};
        const HandReceiver::Info hdi = hand_.Get();
        const bool handLive = hdi.any && hdi.age < cfg.handTimeout;
        if (handLive) {
            for (int h = 0; h < HAND_COUNT; ++h) {
                const KbtHandSide& sd = hdi.pkt.side[h == HAND_RIGHT ? 1 : 0];       // no pacote: [0] = esquerda, [1] = direita
                const bool tr = (sd.flags & KBT_HS_TRACKED) != 0;
                handTracked[h] = tr;
                handPinchRaw[h] = tr && (sd.flags & KBT_HS_PINCH);
                handPosOk[h] = tr && (hdi.pkt.flags & KBT_HAND_F_POS) && cfg.handsPos[h];
                handAimOk[h] = tr && (hdi.pkt.flags & KBT_HAND_F_POS) && cfg.handsAim[h];
                handPinchOk[h] = handPinchRaw[h] && (hdi.pkt.flags & KBT_HAND_F_PINCH) && cfg.handsPinch[h];
                for (int i = 0; i < 3; ++i) { handPos[h][i] = sd.pos[i]; handAim[h][i] = sd.aim[i]; }
            }
        }
        // Celular em modo CONTROLE 6DoF (ARCore): a posicao dele vira a posicao da mao (mesmo caminho/filtro das maos da camera).
        // Ponto de partida = mao "em repouso" na frente do corpo; o celular soma o deslocamento desde o recentrar. Referencial do corpo: x direita, y cima, z tras.
        for (int h = 0; h < HAND_COUNT; ++h) {
            const auto& e6 = es[h];
            if (!(e6.linked && e6.snap.hasPos && (e6.snap.pkt.flags & KBT_FLAG_POS))) continue;
            const float side = (h == HAND_RIGHT) ? 1.f : -1.f;
            handPosOk[h] = true; posCtl[h] = true;
            // saidas x (direita), y (cima), z (tras) <- entradas do celular (0 frente, 1 esquerda, 2 cima), com inversao e escala escolhidas no menu
            float o3[3];
            for (int a = 0; a < 3; ++a) { const float v = e6.snap.pos[std::max(0, std::min(2, cfg.pos6Src[a]))] * cfg.pos6Scale; o3[a] = cfg.pos6Inv[a] ? -v : v; }
            handPos[h][0] = 0.20f * side + o3[0];
            handPos[h][1] = -0.30f + o3[1];                     // (a mao em repouso fica 20 cm ao lado e 30 cm a frente/abaixo da cabeca)
            handPos[h][2] = -0.30f + o3[2];
        }
        if (handLive != handLiveWas) {
            handLiveWas = handLive;
            if (handLive) DiagLog("Maos rastreadas: recebendo de %s", hdi.ip); else if (hdi.any) DiagLog("Maos rastreadas: SEM PACOTES");
        }

        for (int h = 0; h < HAND_COUNT; ++h) {
            auto& st = hs[h];
            auto K = [&](int a) { return keys && KeyDown(cfg.key[h][a]); };

            // A orientacao desta mao vem do controle real (Auto: so enquanto conectado; "So controles": depois de conectar uma vez)
            Esp& e = es[h];
            const bool espCtl = cfg.srcMode == 1 ? (e.linked || e.everLinked) : (cfg.srcMode == 0 && e.linked);

            const bool up = K(A_AIM_UP), dn = K(A_AIM_DOWN), lf = K(A_AIM_LEFT), rt = K(A_AIM_RIGHT);
            bool moved = false;
            if (modDepth) {
                float dz = (dn ? 1.f : 0.f) - (up ? 1.f : 0.f);          // cima = para frente (-z)
                if (dz != 0) { st.off[2] = Clampf(st.off[2] + dz * cfg.moveSpeed * dt, -3.f, 3.f); moved = true; }
            } else if (modMove) {
                float dx = (rt ? 1.f : 0.f) - (lf ? 1.f : 0.f);
                float dy = (up ? 1.f : 0.f) - (dn ? 1.f : 0.f);
                if (dx != 0) { st.off[0] = Clampf(st.off[0] + dx * cfg.moveSpeed * dt, -3.f, 3.f); moved = true; }
                if (dy != 0) { st.off[1] = Clampf(st.off[1] + dy * cfg.moveSpeed * dt, -3.f, 3.f); moved = true; }
            } else if (!espCtl) {
                const double r = cfg.rotSpeed * kDeg2Rad * dt;
                st.yaw   += ((lf ? 1 : 0) - (rt ? 1 : 0)) * r;
                st.pitch += ((up ? 1 : 0) - (dn ? 1 : 0)) * r;
                const double lim = 89.0 * kDeg2Rad;
                if (st.pitch > lim) st.pitch = lim;
                if (st.pitch < -lim) st.pitch = -lim;
            }

            // Resetar posicao/orientacao
            bool rs = K(A_RESET);
            if (rs && !st.prevReset) {
                st.yaw = st.pitch = 0;
                for (int i = 0; i < 3; ++i) st.off[i] = kOffset[h][i].def;
                moved = true;
            }
            st.prevReset = rs;

            // Grava deslocamento de volta nas configuracoes (no maximo 2x por segundo)
            if (moved) st.dirty = true;
            if (st.dirty && !moved && std::chrono::duration<float>(now - st.lastWrite).count() > 0.5f) {
                for (int i = 0; i < 3; ++i) { SetF(kOffset[h][i], st.off[i]); st.seen[i] = st.off[i]; }
                st.dirty = false; st.lastWrite = now;
            }

            // Previsao de movimento do controle (mesma ideia): entre pacotes a pose seguiria parada ("baixo FPS")
            for (int a = 0; a < C_COUNT; ++a) {
                const float d = Wrap180(e.out[a] - e.lastOut[a]);
                if (std::fabs(d) > 1e-4f) {
                    const float dtc = std::max(0.004f, std::min(0.1f, std::chrono::duration<float>(now - e.chg[a]).count()));
                    const float vn = std::fabs(d) > 30.f ? 0.f : std::max(-1500.f, std::min(1500.f, d / dtc));
                    e.vel[a] += (vn - e.vel[a]) * 0.6f;
                    e.lastOut[a] = e.out[a]; e.chg[a] = now;
                }
                const float age = std::chrono::duration<float>(now - e.chg[a]).count();
                if (age > 0.05f) e.vel[a] = 0.f;                   // sem pacote novo: nao extrapola, segura a ultima posicao
                e.pred[a] = cfg.predictMs > 0.f ? Wrap180(e.out[a] + e.vel[a] * (std::min(age, 0.05f) + cfg.predictMs / 1000.f)) : e.out[a];
            }

            // Posicao no mundo
            const double c = std::cos(headYaw), s = std::sin(headYaw);
            PadState ps;
            ps.pos[0] = origin[0] + st.off[0] * c + st.off[2] * s;
            ps.pos[1] = origin[1] + st.off[1];
            ps.pos[2] = origin[2] - st.off[0] * s + st.off[2] * c;
            auto espRot = [&]() -> HmdQuaternion_t {
                if (e.useQ) return QuatMul(QuatAxisY(rotYaw), HmdQuaternion_t{ e.qOut.w, e.qOut.x, e.qOut.y, e.qOut.z });      // quaternion: sem Euler
                return QuatMul(QuatMul(QuatAxisY(rotYaw + e.pred[C_YAW] * kDeg2Rad), QuatAxisX(e.pred[C_PITCH] * kDeg2Rad)),
                               QuatAxisZ(e.pred[C_ROLL] * kDeg2Rad));
            };
            if (espCtl)
                ps.rot = espRot();
            else
                ps.rot = QuatMul(QuatAxisY(rotYaw + st.yaw), QuatAxisX(st.pitch));
            // Mao em 6DoF (posicao real vinda do celular-controle ou das bolinhas/maos da camera): o Arm Model fica DESLIGADO so para ESTA mao,
            // enquanto ela estiver em 6DoF (a outra mao continua com o Arm Model normal).
            const bool pos6 = handPosOk[h] || (espCtl && es[h].linked && es[h].snap.hasPos && (es[h].snap.pkt.flags & KBT_FLAG_POS));
            const bool armOn = cfg.armEnabled && !pos6;
            // Arm Model (opcional): ESTIMA a posicao da mao a partir da orientacao do controle e da cabeca.
            // Nao e rastreamento real do braco (ver ArmSolve em common.h).
            if (armOn && cfg.armMode == 1) {
                // Arm Model SIMPLES (esfera): o CENTRO DA ESFERA E O COTOVELO. O cotovelo fica parado abaixo do ombro (arm_simple_elbow) e o controle
                // e uma bola presa a ele por um cabo de comprimento fixo (arm_simple_len = antebraco + mao).
                // mao = cotovelo + comprimento * direcao em que o controle aponta: girar o controle 30 graus leva a mao 30 graus sobre a esfera.
                const double qx = ps.rot.x, qy = ps.rot.y, qz = ps.rot.z, qw = ps.rot.w;
                const double fwd[3] = { -2 * (qx * qz + qw * qy), 2 * (qw * qx - qy * qz), -(1 - 2 * (qx * qx + qy * qy)) };
                const double chh = std::cos(headYaw), shh = std::sin(headYaw);
                const double side = h == HAND_RIGHT ? 1.0 : -1.0;
                const double bx = side * cfg.arm.shX, by = -cfg.arm.shDrop;
                const double pv[3] = { bx * chh, by, -bx * shh };                           // ombro, relativo a cabeca (girado com a cabeca)
                const double pe[3] = { pv[0], pv[1] - cfg.armSimpleElbow, pv[2] };           // cotovelo = centro da esfera
                double ha[3]; ori::armSphere(pe, cfg.armSimpleLen, fwd, ha);
                const double adj0 = st.off[0] - kOffset[h][0].def, adj1 = st.off[1] - kOffset[h][1].def, adj2 = st.off[2] - kOffset[h][2].def;
                ps.pos[0] = origin[0] + ha[0] + adj0 * chh + adj2 * shh;
                ps.pos[1] = origin[1] + ha[1] + adj1;
                ps.pos[2] = origin[2] + ha[2] - adj0 * shh + adj2 * chh;
                if (espCtl) {
                    auto body = [&](const double* v, float* o3) { o3[0] = (float)(v[0] * chh - v[2] * shh); o3[1] = (float)v[1]; o3[2] = (float)(v[0] * shh + v[2] * chh); };
                    body(pv, armJoints[h][0]); body(pe, armJoints[h][1]); body(ha, armJoints[h][2]);        // ombro, cotovelo (fixo), mao
                    armNow[h] = true;
                }
            } else
            if (armOn) {
                const double qx = ps.rot.x, qy = ps.rot.y, qz = ps.rot.z, qw = ps.rot.w;
                const double fwd[3] = { -2 * (qx * qz + qw * qy), 2 * (qw * qx - qy * qz), -(1 - 2 * (qx * qx + qy * qy)) };
                const double ctlYaw = std::atan2(-fwd[0], -fwd[2]);
                const double ctlPitch = std::asin(std::max(-1.0, std::min(1.0, fwd[1])));
                double sh[3], el[3], ha[3];
                {   // tronco: segue devagar (tau ~0,35 s) o yaw alvo, em vez de tremer junto com o controle
                    double rel = ctlYaw - headYaw; const double PI2 = 6.283185307179586;
                    while (rel > PI2 / 2) rel -= PI2; while (rel < -PI2 / 2) rel += PI2;
                    const double target = headYaw + rel * cfg.arm.torso;
                    if (!st.torsoInit) { st.torsoYaw = target; st.torsoInit = true; }
                    double d = target - st.torsoYaw; while (d > PI2 / 2) d -= PI2; while (d < -PI2 / 2) d += PI2;
                    st.torsoYaw += d * (1.0 - std::exp(-dt / 0.35));
                }
                ArmSolve(cfg.arm, headYaw, ctlYaw, ctlPitch, fwd, h == HAND_RIGHT ? 1.0 : -1.0, sh, el, ha, st.torsoYaw);
                {   // suavizacao ADAPTATIVA da mao: parada = estavel; movimento rapido = quase sem atraso
                    if (!st.armInit) { for (int i = 0; i < 3; ++i) st.armHand[i] = ha[i]; st.armInit = true; }
                    double dx = ha[0] - st.armHand[0], dy = ha[1] - st.armHand[1], dz = ha[2] - st.armHand[2];
                    const double speed = std::sqrt(dx * dx + dy * dy + dz * dz) / std::max((double)dt, 1e-3);      // m/s
                    const double tau = 0.10 / (1.0 + speed * 5.0);                                           // 100 ms parado ... ~10 ms rapido
                    const double al = 1.0 - std::exp(-dt / std::max(tau, 0.004));
                    for (int i = 0; i < 3; ++i) { st.armHand[i] += (ha[i] - st.armHand[i]) * al; el[i] += st.armHand[i] - ha[i]; ha[i] = st.armHand[i]; }
                }
                const double chh = std::cos(headYaw), shh = std::sin(headYaw);
                const double adj0 = st.off[0] - kOffset[h][0].def, adj1 = st.off[1] - kOffset[h][1].def, adj2 = st.off[2] - kOffset[h][2].def;
                ps.pos[0] = origin[0] + ha[0] + adj0 * chh + adj2 * shh;
                ps.pos[1] = origin[1] + ha[1] + adj1;
                ps.pos[2] = origin[2] + ha[2] - adj0 * shh + adj2 * chh;
                if (espCtl) {                                                              // juntas p/ a previa do menu (referencial do corpo)
                    auto body = [&](const double* v, float* o3) {
                        o3[0] = (float)(v[0] * chh - v[2] * shh); o3[1] = (float)v[1]; o3[2] = (float)(v[0] * shh + v[2] * chh);
                    };
                    body(sh, armJoints[h][0]); body(el, armJoints[h][1]); body(ha, armJoints[h][2]);
                    armNow[h] = true;
                }
            }
            if (armOn && espCtl)   // o Arm Model so move a POSICAO: a rotacao (inclusive o giro em Z / roll) segue integral do controle
                ps.rot = espRot();
            // Mao rastreada pela camera: posicao (e, sem controle real nessa mao, a direcao) vem do celular.
            // Referencial do corpo: x direita, y cima, z para tras; girado pelo yaw da cabeca (headYaw) como o Arm Model.
            if (handPosOk[h]) {
                if (!st.hpInit) { for (int i = 0; i < 3; ++i) st.hp[i] = handPos[h][i]; st.hpInit = true; }
                else {   // filtro ADAPTATIVO (aba Maos: "Suavizacao"): parada = estavel; quanto mais longe o alvo, menos suavizacao (quase sem atraso)
                    const double ex = handPos[h][0] - st.hp[0], ey = handPos[h][1] - st.hp[1], ez = handPos[h][2] - st.hp[2];
                    const double err = std::sqrt(ex * ex + ey * ey + ez * ez);
                    const double tauMax = cfg.handsSmooth / 100.0 * 0.25;                // 0 .. 250 ms
                    const double tau = tauMax / (1.0 + err * 45.0);                       // erro de 2 cm: -50% ; 10 cm: -85%
                    const double al = tau < 0.002 ? 1.0 : 1.0 - std::exp(-(double)dt / tau);
                    for (int i = 0; i < 3; ++i) st.hp[i] += (float)((handPos[h][i] - st.hp[i]) * al);
                }
                // Posicao da CAMERA do headset (maos/bolinhas): referencial do corpo, gira com o yaw da cabeca. Posicao do PROPRIO controle (6DoF do celular):
                // referencial FIXO do controle (yaw 0 = a "frente" do recentrar), o mesmo da rotacao dele (rotYaw): girar a cabeca NAO pode girar o deslocamento.
                const double pyaw = posCtl[h] ? rotYaw : headYaw;
                const double chh = std::cos(pyaw), shh = std::sin(pyaw);
                const double adj0 = st.off[0] - kOffset[h][0].def, adj1 = st.off[1] - kOffset[h][1].def, adj2 = st.off[2] - kOffset[h][2].def;   // ajuste fino do menu/teclado
                const double bx = st.hp[0] + adj0, by = st.hp[1] + adj1, bz = st.hp[2] + adj2;
                ps.pos[0] = origin[0] + bx * chh + bz * shh;
                ps.pos[1] = origin[1] + by;
                ps.pos[2] = origin[2] - bx * shh + bz * chh;
                if (!espCtl && handAimOk[h]) {
                    const double yawLeft = -std::atan2((double)handAim[h][0], -(double)handAim[h][2]);
                    const double pitchUp = std::asin(std::max(-1.0, std::min(1.0, (double)handAim[h][1])));
                    ps.rot = QuatMul(QuatAxisY(rotYaw + yawLeft), QuatAxisX(pitchUp));
                }
            } else st.hpInit = false;
            // vecVelocity = 0 DE PROPOSITO: o SteamVR extrapola a pose ate o instante da tela com essa velocidade. Calculada como (pos - posAnterior)/dt com
            // dt de ~2 ms (que oscila 1,5-4 ms), ela vira ruido de varios m/s e a extrapolacao faz o laser/controle dar "pulinhos" (pior no Arm Model,
            // onde a posicao depende da orientacao). A previsao ja e feita aqui, na orientacao.
            for (int i = 0; i < 3; ++i) ps.vel[i] = 0.0;
            for (int i = 0; i < 3; ++i) st.prevPos[i] = ps.pos[i];
            st.havePrev = true;

            // Entradas dos controles reais para esta mao. Padrao: tabelas de botoes de cada controle (as funcoes dos dois somam).
            // Perfil Generico: botoes fisicos 1:1 do controle que ocupa esta mao.
            bool eTrig = false, eGrip = false, eA = false, eB = false, eMenu = false, eClick = false, eGx = false, eGy = false;
            float esx = 0, esy = 0;
            bool eBump = false, eSys = false;
            if (profile_ == PF_FRAME) {
                // Steam Frame: cada botao fisico aciona o que a tabela do perfil diz (aba Mapa); direito = A/B/X/Y/Menu, esquerdo = D-pad/View
                const bool* o = e.outs;
                eTrig = o[O_FM_TRIGGER]; eGrip = o[O_FM_GRIP]; eBump = o[O_FM_BUMPER]; eClick = o[O_FM_STICK]; eSys = o[O_FM_SYSTEM];
                if (h == HAND_RIGHT) { eA = o[O_FM_A]; eB = o[O_FM_B]; eGx = o[O_FM_X]; eGy = o[O_FM_Y]; eMenu = o[O_FM_MENU]; }
                else { eA = o[O_FM_DDOWN]; eB = o[O_FM_DRIGHT]; eGx = o[O_FM_DLEFT]; eGy = o[O_FM_DUP]; eMenu = o[O_FM_VIEW]; }
            } else if (profile_ == PF_GENERIC) {
                if (e.linked) {
                    const uint16_t bt = e.btnEff;
                    eTrig = (bt & KBT_BTN_TRIGGER) || (bt & KBT_BTN_R2); eGrip = bt & KBT_BTN_GRIP; eA = bt & KBT_BTN_A; eB = bt & KBT_BTN_B;
                    eGx = bt & KBT_BTN_X; eGy = bt & KBT_BTN_Y; eMenu = bt & KBT_BTN_START; eClick = bt & KBT_BTN_L3;
                }
            } else if (h == HAND_RIGHT) {
                eTrig = outAny[O_R_TRIGGER]; eGrip = outAny[O_R_GRIP]; eA = outAny[O_R_A]; eB = outAny[O_R_B];
                eMenu = outAny[O_R_SYSTEM]; eClick = outAny[O_R_CLICK];
            } else {
                eA = outAny[O_L_X]; eB = outAny[O_L_Y]; eMenu = outAny[O_L_MENU];
                eTrig = outAny[O_L_TRIGGER]; eGrip = outAny[O_L_GRIP]; eClick = outAny[O_L_CLICK];
            }
            if (handPinchOk[h]) eTrig = true;                                                // pinca = trigger (nao altera o mapeamento dos botoes)
            if (e.linked) { esx = e.sx; esy = e.sy; }                                      // o analogico vai a mao do proprio controle

            // Botoes e analogicos (teclado + controle real)
            const float rampT = cfg.ctl[h].trigRamp * dt, rampG = cfg.ctl[h].gripRamp * dt;     // rampas separadas
            // UM TOQUE BASTA: botao digital (ex.: volume do celular) solto antes da rampa chegar ao limiar de clique ainda assim completa o clique.
            // O gatilho/grip sobem rapido (no minimo 40 /s) e ficam apertados por ao menos kMinPressMs desde o instante em que foram apertados.
            const bool wantTrig = (K(A_TRIGGER) || eTrig), wantGrip = (K(A_GRIP) || eGrip);
            const auto tNow = clock::now();
            const auto minHold = std::chrono::milliseconds(kMinPressMs);
            if (wantTrig && !st.trigWas) st.trigUntil = tNow + minHold;
            if (wantGrip && !st.gripWas) st.gripUntil = tNow + minHold;
            st.trigWas = wantTrig; st.gripWas = wantGrip;
            float tTrig = (wantTrig || tNow < st.trigUntil) ? 1.f : 0.f, tGrip = (wantGrip || tNow < st.gripUntil) ? 1.f : 0.f;
            const float rampTu = std::max(rampT, 40.f * dt), rampGu = std::max(rampG, 40.f * dt);
            st.trig += Clampf(tTrig - st.trig, -(tTrig > st.trig ? rampTu : rampT), rampTu);
            st.grip += Clampf(tGrip - st.grip, -(tGrip > st.grip ? rampGu : rampG), rampGu);
            ps.trig = st.trig; ps.grip = st.grip;
            for (int fi = 0; fi < 5; ++fi) {                                             // dedos do Index: botao mapeado fecha o dedo (rampa)
                const float tf = outAny[kFingerOut[h][fi]] ? 1.f : 0.f;
                st.fing[fi] += Clampf(tf - st.fing[fi], -rampG, rampG);
                ps.fing[fi] = st.fing[fi];
            }
            float sx = (K(A_STICK_RIGHT) ? 1.f : 0.f) - (K(A_STICK_LEFT) ? 1.f : 0.f);
            float sy = (K(A_STICK_UP) ? 1.f : 0.f) - (K(A_STICK_DOWN) ? 1.f : 0.f);
            if (std::fabs(esx) > 0.001f || std::fabs(esy) > 0.001f) { sx = esx; sy = esy; }
            float len = std::sqrt(sx * sx + sy * sy);
            if (len > 1.f) { sx /= len; sy /= len; }
            ps.sx = sx; ps.sy = sy;
            ps.click = K(A_STICK_CLICK) || eClick;
            ps.a = K(A_BTN_A) || eA; ps.b = K(A_BTN_B) || eB; ps.menu = K(A_MENU) || eMenu;
            ps.gx = eGx; ps.gy = eGy; ps.bump = eBump; ps.sys = eSys;
            ps.clickThr = cfg.ctl[h].clickThr;
            ps.connected = cfg.enabled[h] && !(cfg.hideLost && cfg.srcMode == 1 && e.everLinked && !e.linked);

            pad_[h]->Push(ps);
        }

        // Telemetria para o app de configuracao (~60x/s)
        if (g_live && tick % 8 == 0) {
            Live& L = *g_live;
            L.profile = (uint32_t)profile_;
            L.outBits = outBitsAll;
            L.srcSeen = (uint32_t)nSrc;
            L.srcUnassigned = unassignedSeen;
            L.handLink = !hdi.any ? 0u : (handLive ? 1u : 2u);
            L.handRate = hdi.rate;
            std::memcpy(L.handIp, hdi.ip, sizeof(L.handIp));
            for (int h = 0; h < HAND_COUNT; ++h) { L.handTracked[h] = handTracked[h]; L.handPinchNow[h] = handPinchRaw[h]; L.handPosUsed[h] = handPosOk[h]; }
            std::memcpy(L.extraIp, extraIpSeen, sizeof(L.extraIp));
            for (int h = 0; h < HAND_COUNT; ++h) {
                const Esp& e = es[h];
                CtlLive& C = L.ctl[h];
                const KbtPacket& pk = e.snap.pkt;
                const bool any = e.snap.used;
                C.link = !e.linked ? 0u : (e.calibrating ? 2u : 1u);
                C.kind = !any ? 0u : ((pk.rssi != 0 || pk.gyro[0] != 0.f || pk.gyro[1] != 0.f || pk.gyro[2] != 0.f) ? 1u : 2u);
                C.pktRate = e.snap.rate;
                C.rssi = any ? pk.rssi : 0;
                C.buttons = e.btn;
                C.ageMs = any ? e.snap.age * 1000.f : 999999.f;
                C.lost = e.snap.lost; C.dropped = e.snap.dropped;
                C.flags = any ? pk.flags : 0;
                C.outBits = e.outBits;
                C.orient = (cfg.srcMode == 1 ? (e.linked || e.everLinked) : (cfg.srcMode == 0 && e.linked)) ? 1u : 0u;
                for (int i = 0; i < 3; ++i) { C.rawRpy[i] = e.raw[i]; C.mpu[i] = e.mpu[i]; C.gyro[i] = any ? pk.gyro[i] : 0.f; }
                for (int a = 0; a < C_COUNT; ++a) C.out[a] = e.out[a];
                C.joyRaw[0] = any ? pk.joyX : 0; C.joyRaw[1] = any ? pk.joyY : 0;
                C.stick[0] = e.sx; C.stick[1] = e.sy;
                C.stickNorm[0] = e.nx; C.stickNorm[1] = e.ny;
                C.uptimeMs = any ? pk.uptimeMs : 0;
                std::memcpy(C.ip, e.snap.ipStr, sizeof(C.ip));
                C.armOn = armNow[h] ? 1u : 0u;
                std::memcpy(C.armJ, armJoints[h], sizeof(C.armJ));
            }
            if (hmd_) {
                hmd_->LastPose(L.hmdPos, L.hmdUp);
                for (int hh = 0; hh < HAND_COUNT; ++hh) if (pad_[hh]) pad_[hh]->LastPos(L.ctlPosW[hh]);
                L.hmdWin[0] = hmd_->WinX(); L.hmdWin[1] = hmd_->WinY(); L.hmdWin[2] = (int32_t)hmd_->WinW(); L.hmdWin[3] = (int32_t)hmd_->WinH();
                L.hmdStatus = hmd_->Status(); L.fsFixes = fs_.Fixes(); L.madePrimary = g_madePrimary ? 1u : 0u;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
}

// --------------------------------------------------------------- entrada
static Provider g_provider;

extern "C" __declspec(dllexport) void* HmdDriverFactory(const char* name, int* returnCode) {
    if (name && strcmp(name, IServerTrackedDeviceProvider_Version) == 0) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
