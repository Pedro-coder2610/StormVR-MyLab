// Definicoes compartilhadas entre o driver (driver_stormvr.dll) e o
// aplicativo de configuracao (stormvr_config.exe).
// Os dois conversam pelo VRSettings (secao "driver_stormvr").
#pragma once
#include <cmath>
#include <limits>
#include <cstdio>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include "platform.h"
#endif
#include "protocol.h"
#include "orient.h"

namespace kbt {

constexpr const char* kSection = "driver_stormvr";

enum Hand { HAND_RIGHT = 0, HAND_LEFT = 1, HAND_COUNT = 2 };

enum Action {
    A_AIM_UP, A_AIM_DOWN, A_AIM_LEFT, A_AIM_RIGHT,
    A_TRIGGER, A_GRIP,
    A_BTN_A,          // A (direito) / X (esquerdo)
    A_BTN_B,          // B (direito) / Y (esquerdo)
    A_STICK_CLICK,
    A_STICK_UP, A_STICK_DOWN, A_STICK_LEFT, A_STICK_RIGHT,
    A_MENU,           // Sistema (direito) / Menu (esquerdo)
    A_RESET,
    A_COUNT
};

struct ActionInfo {
    const char*    id;
    const wchar_t* labelRight;
    const wchar_t* labelLeft;
    int            defRight;
    int            defLeft;
};

inline const ActionInfo kActions[A_COUNT] = {
    { "aim_up",      L"Apontar para cima",     L"Apontar para cima",     VK_UP,      'W' },
    { "aim_down",    L"Apontar para baixo",    L"Apontar para baixo",    VK_DOWN,    'S' },
    { "aim_left",    L"Apontar p/ esquerda",   L"Apontar p/ esquerda",   VK_LEFT,    'A' },
    { "aim_right",   L"Apontar p/ direita",    L"Apontar p/ direita",    VK_RIGHT,   'D' },
    { "trigger",     L"Gatilho (trigger)",     L"Gatilho (trigger)",     VK_HOME,    VK_INSERT },
    { "grip",        L"Grip",                  L"Grip",                  VK_END,     VK_DELETE },
    { "btn_a",       L"Botão A",               L"Botão X",               VK_PRIOR,   'Z' },
    { "btn_b",       L"Botão B",               L"Botão Y",               VK_NEXT,    'X' },
    { "stick_click", L"Analógico: clique",     L"Analógico: clique",     VK_NUMPAD5, 'C' },
    { "stick_up",    L"Analógico: cima",       L"Analógico: cima",       VK_NUMPAD8, 'T' },
    { "stick_down",  L"Analógico: baixo",      L"Analógico: baixo",      VK_NUMPAD2, 'G' },
    { "stick_left",  L"Analógico: esquerda",   L"Analógico: esquerda",   VK_NUMPAD4, 'F' },
    { "stick_right", L"Analógico: direita",    L"Analógico: direita",    VK_NUMPAD6, 'H' },
    { "menu",        L"Sistema (abre dashboard)", L"Menu",               VK_ADD,     'V' },
    { "reset",       L"Resetar posição",       L"Resetar posição",       VK_NUMPAD0, 'R' },
};

inline std::string KeySetting(int hand, int action) {
    return std::string("key_") + (hand == HAND_RIGHT ? "right" : "left") + "_" + kActions[action].id;
}
inline int DefaultKey(int hand, int action) {
    return hand == HAND_RIGHT ? kActions[action].defRight : kActions[action].defLeft;
}

// Teclas globais
constexpr const char* kKeyMoveMod  = "key_move_mod";   // segurar + apontar = mover (x/y)
constexpr const char* kKeyDepthMod = "key_depth_mod";  // segurar + cima/baixo = frente/tras
constexpr const char* kKeyToggle   = "key_toggle";     // liga/desliga o teclado
constexpr int kDefMoveMod  = VK_SHIFT;
constexpr int kDefDepthMod = VK_CONTROL;
constexpr int kDefToggle   = VK_SCROLL;

// Booleans
constexpr const char* kKeysEnabled   = "keys_enabled";
constexpr const char* kFollowHmd     = "follow_hmd";        // controles seguem o DESLOCAMENTO do headset
constexpr const char* kFollowHmdRot  = "follow_hmd_rot";    // (opcional) a rotacao tambem gira com a direcao do headset; padrao: so o IMU manda
constexpr const char* kEnabled[HAND_COUNT] = { "right_enabled", "left_enabled" };
constexpr const char* kConfigOverlay = "config_overlay";   // driver abre o app de config
constexpr const char* kWebAutostart  = "web_autostart";    // driver abre o painel WEB (web/stormvr_web.py) junto com o SteamVR e o fecha ao sair

// Floats
struct FloatSetting { const char* key; float def, mn, mx; };
inline const FloatSetting kMoveSpeed   = { "move_speed",   0.5f,  0.05f, 2.0f };
inline const FloatSetting kRotSpeed    = { "rotate_speed", 90.f,  10.f,  360.f };
inline const FloatSetting kFixedHeight = { "fixed_height", 1.3f,  0.5f,  2.2f };
inline const FloatSetting kOffset[HAND_COUNT][3] = {
    { { "right_x",  0.20f, -1.0f, 1.0f }, { "right_y", -0.30f, -1.0f, 1.0f }, { "right_z", -0.35f, -1.5f, 0.5f } },
    { { "left_x",  -0.20f, -1.0f, 1.0f }, { "left_y",  -0.30f, -1.0f, 1.0f }, { "left_z",  -0.35f, -1.5f, 0.5f } },
};

// ============================================================ ESP32 / MPU6050
// Eixos do MPU (indices de KbtPacket::rpy) e eixos do controle no SteamVR
enum MpuAxis { M_ROLL = 0, M_PITCH = 1, M_YAW = 2, M_NONE = 3 };
enum CtlAxis { C_YAW = 0, C_PITCH = 1, C_ROLL = 2, C_COUNT = 3 };

struct IntSetting  { const char* key; int  def, mn, mx; };
struct BoolSetting { const char* key; bool def; };

// Inteiros
inline const IntSetting kSrcMode   = { "src_mode",  0, 0, 2 };      // 0=Auto (ESP32, senao teclado) 1=So ESP32 2=So teclado
inline const IntSetting kUdpPort   = { "udp_port",  KBT_PORT_PC, 1024, 65535 };           // controle DIREITO (e legado: mao pela flag)
inline const IntSetting kUdpPortL  = { "udp_port_left", KBT_PORT_PC_LEFT, 1024, 65535 };  // controle ESQUERDO (mao sempre esquerda)
// Centro do analogico POR CONTROLE (cada joystick tem o seu centro): [0] = direito (chaves antigas stick_cx/stick_cy), [1] = esquerdo
inline const IntSetting kStickC[HAND_COUNT][2] = {
    { { "stick_cx", 2048, 0, 4095 },      { "stick_cy", 2048, 0, 4095 } },
    { { "stick_cx_left", 2048, 0, 4095 }, { "stick_cy_left", 2048, 0, 4095 } } };
// Booleanos
inline const BoolSetting kComboRecenter  = { "combo_recenter", true };   // L3 + Start recentra o yaw
inline const BoolSetting kAutoCenter     = { "auto_center", true };      // recentra o yaw ao conectar
inline const BoolSetting kAutoZeroConn   = { "auto_zero_connect", true }; // zera TODOS os eixos (yaw/pitch/roll) ao conectar: corrige o controle "de cabeca pra baixo" a cada reconexao
// Floats
inline const FloatSetting kEspTimeout = { "esp_timeout",  0.5f, 0.1f, 3.0f };

// ============================================================ Headset (HMD) StormVR
// Geometria (lida na ativacao do driver; mudar exige reiniciar o SteamVR)
inline const BoolSetting  kHmdEnabled = { "hmd_enabled", true };      // false = nao cria o headset (usa so os controles)
inline const IntSetting   kHmdWinX = { "hmd_win_x", 0, -32768, 32768 };   // janela do compositor na area de trabalho
inline const IntSetting   kHmdWinY = { "hmd_win_y", 0, -32768, 32768 };   // (coloque sobre o MONITOR VIRTUAL capturado pelo Sunshine)
inline const IntSetting   kHmdWinW = { "hmd_win_w", 1920, 640, 7680 };    // largura total = 2 olhos lado a lado
inline const IntSetting   kHmdWinH = { "hmd_win_h", 1080, 360, 4320 };
inline const IntSetting   kHeadPort = { "head_port", KBT_PORT_HEAD, 1024, 65535 };
inline const FloatSetting kHmdFovH = { "hmd_fov_h", 90.f, 40.f, 130.f };  // FOV horizontal de CADA olho (graus)
inline const FloatSetting kHmdFovV = { "hmd_fov_v", 90.f, 40.f, 130.f };
inline const FloatSetting kHmdIpd  = { "hmd_ipd", 0.063f, 0.050f, 0.080f };   // distancia entre os olhos (m): profundidade 3D
inline const FloatSetting kHmdSep  = { "hmd_sep", 0.0f, -10.f, 10.f };        // distancia entre os CENTROS das 2 imagens (% da largura de um olho; + = mais afastadas)
inline const FloatSetting kHmdHz   = { "hmd_hz", 60.f, 30.f, 120.f };
inline const FloatSetting kHmdRenderScale = { "hmd_render_scale", 1.0f, 0.5f, 2.0f };
inline const FloatSetting kHmdK1   = { "hmd_k1", 0.34f, -1.f, 2.f };     // distorcao radial (estilo Cardboard)
inline const FloatSetting kHmdK2   = { "hmd_k2", 0.55f, -1.f, 2.f };
// Dinamico
inline const FloatSetting kUserHeight  = { "user_height", 1.65f, 1.0f, 2.6f };   // altura da cabeca (m) no 3DoF
// ---- aba COORDENADAS: posicao/rotacao livres da cabeca e posicao dos controles (metros / graus), somadas ao que o driver calcula
inline const FloatSetting kHeadOff[3] = { { "head_off_x", 0.f, -3.f, 3.f }, { "head_off_y", 0.f, -3.f, 3.f }, { "head_off_z", 0.f, -3.f, 3.f } };
inline const FloatSetting kHeadRot[3] = { { "head_rot_pitch", 0.f, -180.f, 180.f }, { "head_rot_yaw", 0.f, -180.f, 180.f }, { "head_rot_roll", 0.f, -180.f, 180.f } };
inline const FloatSetting kCtlOff[2][3] = {
    { { "ctl_off_right_x", 0.f, -3.f, 3.f }, { "ctl_off_right_y", 0.f, -3.f, 3.f }, { "ctl_off_right_z", 0.f, -3.f, 3.f } },
    { { "ctl_off_left_x",  0.f, -3.f, 3.f }, { "ctl_off_left_y",  0.f, -3.f, 3.f }, { "ctl_off_left_z",  0.f, -3.f, 3.f } } };
inline const FloatSetting kHeadHeightOff = { "head_height_off", 0.0f, -1.5f, 2.0f };   // ajuste fino SOMADO a altura (m): + sobe, - desce (corrige "dentro do chao")
inline const FloatSetting kHeadTimeout = { "head_timeout", 0.5f, 0.1f, 3.0f };  // s sem pose -> rastreamento perdido
// Etapa 31: o headset so e declarado "sem rastreamento" depois de head_hold segundos SEM pacotes. Antes eram 0,5 s: qualquer soluco do Wi-Fi deixava a pose
// invalida, o SteamVR mostrava a tela CINZA piscando e o Beat Saber pausava. Ate head_hold a ultima orientacao e mantida (a pose continua valida).
inline const FloatSetting kHeadHold    = { "head_hold", 5.0f, 0.5f, 30.0f };
// ---- Maos rastreadas pela camera do celular (Sala VR em "modo PC"): posicao e pinca. Ligavel por mao.
inline const IntSetting   kHandPort    = { "hand_port", KBT_PORT_HAND, 1024, 65535 };
inline const FloatSetting kHandTimeout = { "hand_timeout", 0.4f, 0.1f, 2.0f };      // s sem pacote de maos = maos perdidas
inline const BoolSetting  kHandsPos[HAND_COUNT]   = { { "hands_pos_right", true },   { "hands_pos_left", true } };    // posicao da mao -> posicao do controle
inline const BoolSetting  kHandsPinch[HAND_COUNT] = { { "hands_pinch_right", true }, { "hands_pinch_left", true } };  // pinca -> trigger
inline const BoolSetting  kHandsAim[HAND_COUNT]   = { { "hands_aim_right", true },   { "hands_aim_left", true } };    // direcao da mao -> orientacao (so sem controle real nessa mao)

// ---- Escolha do monitor / resolucao / taxa / rastreamento do headset (aba "Headset")
constexpr const char* kHmdMonitorKey = "hmd_monitor";          // string: nome do dispositivo, ex. "\\.\DISPLAY2" ("" = usa hmd_win_*)
inline const IntSetting kHmdResMode  = { "hmd_res_mode", 0, 0, 4 };   // 0 = monitor inteiro, 1..4 = tabela abaixo
inline const IntSetting kHmdHzMode   = { "hmd_hz_mode",  0, 0, 3 };
inline const IntSetting kTrackMode   = { "track_mode",   1, 0, 1 };   // 0 = 3DoF (altura fixa, padrao seguro), 1 = 6DoF (usa a posicao enviada pelo app)
inline const int   kResW[5] = { 0, 1280, 1920, 2560, 3840 };
inline const int   kResH[5] = { 0,  720, 1080, 1440, 2160 };
inline const float kHzTable[4] = { 60.f, 72.f, 90.f, 120.f };

#ifdef _WIN32
struct MonitorInfo {
    std::string dev, name;      // "\\.\DISPLAY2", nome amigavel
    int x = 0, y = 0, w = 0, h = 0;   // posicao/tamanho em pixels FISICOS na area de trabalho virtual
    bool primary = false;
};
// Monitores ativos na area de trabalho (inclui monitores virtuais). Usa EnumDisplaySettings: valores fisicos,
// independentes do escalonamento DPI do processo.
inline std::vector<MonitorInfo> ListMonitors() {
    std::vector<MonitorInfo> v;
    for (DWORD i = 0;; ++i) {
        DISPLAY_DEVICEA dd = {}; dd.cb = sizeof(dd);
        if (!EnumDisplayDevicesA(nullptr, i, &dd, 0)) break;
        if (!(dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) || (dd.StateFlags & DISPLAY_DEVICE_MIRRORING_DRIVER)) continue;
        DEVMODEA dm = {}; dm.dmSize = sizeof(dm);
        if (!EnumDisplaySettingsA(dd.DeviceName, ENUM_CURRENT_SETTINGS, &dm)) continue;
        MonitorInfo m;
        m.dev = dd.DeviceName;
        m.x = dm.dmPosition.x; m.y = dm.dmPosition.y; m.w = (int)dm.dmPelsWidth; m.h = (int)dm.dmPelsHeight;
        m.primary = (dd.StateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0;
        DISPLAY_DEVICEA mon = {}; mon.cb = sizeof(mon);
        m.name = EnumDisplayDevicesA(dd.DeviceName, 0, &mon, 0) ? mon.DeviceString : dd.DeviceString;
        v.push_back(m);
    }
    return v;
}

#endif  // _WIN32 (monitores)

// ============================================================ Mapeamento de botoes / perfis / eixos / braco
// Botoes fisicos do controle (indice = bit de KbtPacket::buttons). P_R2 = botao extra do GPIO4 (D4) do ESP32.
enum Phys { P_GRIP = 0, P_TRIGGER, P_A, P_B, P_X, P_Y, P_START, P_L3, P_R2, P_COUNT };
// Entradas do controle virtual SteamVR (o que cada botao fisico pode acionar)
enum Out { O_NONE = 0, O_R_TRIGGER, O_R_GRIP, O_R_A, O_R_B, O_R_SYSTEM, O_R_CLICK,
           O_L_X, O_L_Y, O_L_MENU, O_L_TRIGGER, O_L_GRIP, O_L_CLICK,
           // ---- ACOES do driver (qualquer botao fisico pode dispara-las; varios botoes podem ter a mesma funcao)
           O_ACT_RECENTER_CTL, O_ACT_RECENTER_HEAD, O_ACT_RECENTER_ALL, O_ACT_ZERO, O_ACT_CALIBRATE, O_ACT_ARM_TOGGLE,
           O_ACT_CLUTCH,        // segurar = congela a orientacao do controle (reposicione a mao e solte, sem salto)
           // ---- DEDOS do Valve Index: qualquer botao fisico pode FECHAR qualquer dedo (curvatura 0..1, com rampa)
           O_FR_THUMB, O_FR_INDEX, O_FR_MIDDLE, O_FR_RING, O_FR_PINKY,      // dedos do controle DIREITO
           O_FL_THUMB, O_FL_INDEX, O_FL_MIDDLE, O_FL_RING, O_FL_PINKY,      // dedos do controle ESQUERDO
           // ---- Perfil STEAM FRAME: botoes do PROPRIO controle onde o botao fisico esta (a mao e a do controle)
           O_FM_TRIGGER, O_FM_GRIP, O_FM_BUMPER, O_FM_STICK,                 // os dois controles
           O_FM_A, O_FM_B, O_FM_X, O_FM_Y, O_FM_MENU,                        // so no direito
           O_FM_VIEW, O_FM_DUP, O_FM_DRIGHT, O_FM_DDOWN, O_FM_DLEFT,         // so no esquerdo (View + D-pad)
           O_FM_SYSTEM,                                                      // os dois
           O_COUNT };
constexpr int kFingerOut[2][5] = { { O_FR_THUMB, O_FR_INDEX, O_FR_MIDDLE, O_FR_RING, O_FR_PINKY },
                                   { O_FL_THUMB, O_FL_INDEX, O_FL_MIDDLE, O_FL_RING, O_FL_PINKY } };    // [mao][dedo]
// Mapeamento POR CONTROLE: kBind[mao][botao fisico]. "mao" = a mao que o controle ocupa (direita/esquerda), qualquer que seja a origem
// (ESP32 ou celular). Cada botao fisico aciona UMA funcao, mas VARIOS botoes (do mesmo controle ou de controles diferentes)
// podem acionar a MESMA funcao: elas se somam (OU), sem clique duplicado.
inline const IntSetting kBind[HAND_COUNT][P_COUNT] = {
    { // controle na mao DIREITA (padrao = ligacao original; R2 tambem e Gatilho)
      { "bind_right_grip",    O_R_GRIP,    0, O_COUNT - 1 }, { "bind_right_trigger", O_R_TRIGGER, 0, O_COUNT - 1 },
      { "bind_right_a",       O_R_A,       0, O_COUNT - 1 }, { "bind_right_b",       O_R_B,       0, O_COUNT - 1 },
      { "bind_right_x",       O_L_X,       0, O_COUNT - 1 }, { "bind_right_y",       O_L_Y,       0, O_COUNT - 1 },
      { "bind_right_start",   O_R_SYSTEM,  0, O_COUNT - 1 }, { "bind_right_l3",      O_R_CLICK,   0, O_COUNT - 1 },
      { "bind_right_r2",      O_R_TRIGGER, 0, O_COUNT - 1 } },
    { // controle na mao ESQUERDA
      { "bind_left_grip",     O_L_GRIP,    0, O_COUNT - 1 }, { "bind_left_trigger",  O_L_TRIGGER, 0, O_COUNT - 1 },
      { "bind_left_a",        O_L_X,       0, O_COUNT - 1 }, { "bind_left_b",        O_L_Y,       0, O_COUNT - 1 },
      { "bind_left_x",        O_NONE,      0, O_COUNT - 1 }, { "bind_left_y",        O_NONE,      0, O_COUNT - 1 },
      { "bind_left_start",    O_L_MENU,    0, O_COUNT - 1 }, { "bind_left_l3",       O_L_CLICK,   0, O_COUNT - 1 },
      { "bind_left_r2",       O_L_TRIGGER, 0, O_COUNT - 1 } } };
// Mapeamento do perfil STEAM FRAME (tabela propria; o perfil ativo decide qual tabela vale)
inline const IntSetting kBindFrame[HAND_COUNT][P_COUNT] = {
    { { "fbind_right_grip", O_FM_GRIP, 0, O_COUNT - 1 }, { "fbind_right_trigger", O_FM_TRIGGER, 0, O_COUNT - 1 },
      { "fbind_right_a", O_FM_A, 0, O_COUNT - 1 },       { "fbind_right_b", O_FM_B, 0, O_COUNT - 1 },
      { "fbind_right_x", O_FM_X, 0, O_COUNT - 1 },       { "fbind_right_y", O_FM_Y, 0, O_COUNT - 1 },
      { "fbind_right_start", O_FM_SYSTEM, 0, O_COUNT - 1 }, { "fbind_right_l3", O_FM_STICK, 0, O_COUNT - 1 },
      { "fbind_right_r2", O_FM_BUMPER, 0, O_COUNT - 1 } },
    { { "fbind_left_grip", O_FM_GRIP, 0, O_COUNT - 1 },  { "fbind_left_trigger", O_FM_TRIGGER, 0, O_COUNT - 1 },
      { "fbind_left_a", O_FM_DDOWN, 0, O_COUNT - 1 },    { "fbind_left_b", O_FM_DRIGHT, 0, O_COUNT - 1 },
      { "fbind_left_x", O_FM_DLEFT, 0, O_COUNT - 1 },    { "fbind_left_y", O_FM_DUP, 0, O_COUNT - 1 },
      { "fbind_left_start", O_FM_VIEW, 0, O_COUNT - 1 }, { "fbind_left_l3", O_FM_STICK, 0, O_COUNT - 1 },
      { "fbind_left_r2", O_FM_BUMPER, 0, O_COUNT - 1 } } };
// Regra obrigatoria: sempre existe pelo menos um botao fisico (de qualquer controle) acionando o Trigger direito (selecao principal).
inline bool BindHasTrigger(const int m[HAND_COUNT][P_COUNT]) {
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) if (m[h][i] == O_R_TRIGGER) return true;
    return false;
}

// ---- Dois controles ao mesmo tempo (ESP32 e/ou celular)
// Como o driver decide qual fonte (IP) fica em qual mao:
//   0 Automatico : cada controle diz sua mao (flag do pacote; no ESP32 e a mao gravada nele). Se dois pedirem a MESMA mao,
//                  o segundo vai para a mao livre.
//   1 Ordem      : 1o controle que conectar = direita, 2o = esquerda.
//   2 IP fixo    : usa os IPs gravados em ctl_ip_right / ctl_ip_left (botao "Fixar IP" na aba Controles).
inline const IntSetting  kAssign = { "ctl_assign", 0, 0, 2 };
inline const BoolSetting kSwap   = { "ctl_swap", false };           // troca direita <-> esquerda (qualquer modo)
constexpr const char* kCtlIp[HAND_COUNT] = { "ctl_ip_right", "ctl_ip_left" };

// ============================================================ AJUSTES POR CONTROLE (cada mao tem os seus)
// Tudo que depende do hardware de UM controle (montagem do MPU, joystick, botoes) e separado por mao: o controle DIREITO usa as
// chaves antigas (sem sufixo, preservando o que voce ja ajustou) e o ESQUERDO usa o mesmo nome + "_left". Assim e possivel inverter
// um eixo num controle sem inverter no outro.
struct CtlSettings {
    IntSetting   axSrc[C_COUNT];        // qual eixo do MPU comanda cada eixo do controle
    BoolSetting  axEn[C_COUNT];
    BoolSetting  axInv[C_COUNT];
    FloatSetting axGain[C_COUNT];
    FloatSetting axSmooth[C_COUNT];     // suavizacao individual (ms)
    FloatSetting mpuDz[3];              // zona morta (graus) de cada eixo do MPU: roll, pitch, yaw
    FloatSetting stickDz[2];
    BoolSetting  stickInv[2];           // Y invertido no hardware do ESP32 (padrao)
    BoolSetting  stickSwap;
    FloatSetting stickGamma, stickEdge, stickSens;
    FloatSetting trigRamp, gripRamp;    // rampas de resposta do Trigger/Grip
    FloatSetting clickThr;              // valor do Trigger/Grip a partir do qual conta como "clique"
    FloatSetting yawDrift;              // compensa a deriva do yaw do MPU (graus/min)
};
#define KBT_CTL_SETTINGS(SUF) { \
    { { "ax_src_yaw" SUF, M_YAW, 0, 3 }, { "ax_src_pitch" SUF, M_PITCH, 0, 3 }, { "ax_src_roll" SUF, M_ROLL, 0, 3 } }, \
    { { "ax_en_yaw" SUF, true }, { "ax_en_pitch" SUF, true }, { "ax_en_roll" SUF, true } }, \
    { { "ax_inv_yaw" SUF, false }, { "ax_inv_pitch" SUF, false }, { "ax_inv_roll" SUF, false } }, \
    { { "ax_gain_yaw" SUF, 1.0f, 0.1f, 3.0f }, { "ax_gain_pitch" SUF, 1.0f, 0.1f, 3.0f }, { "ax_gain_roll" SUF, 1.0f, 0.1f, 3.0f } }, \
    { { "ax_smooth_yaw" SUF, 25.f, 0.f, 250.f }, { "ax_smooth_pitch" SUF, 25.f, 0.f, 250.f }, { "ax_smooth_roll" SUF, 25.f, 0.f, 250.f } }, \
    { { "mpu_dz_roll" SUF, 0.3f, 0.f, 15.f }, { "mpu_dz_pitch" SUF, 0.3f, 0.f, 15.f }, { "mpu_dz_yaw" SUF, 1.0f, 0.f, 15.f } }, \
    { { "stick_dz_x" SUF, 0.10f, 0.f, 0.6f }, { "stick_dz_y" SUF, 0.10f, 0.f, 0.6f } }, \
    { { "stick_inv_x" SUF, false }, { "stick_inv_y" SUF, true } }, \
    { "stick_swap" SUF, false }, \
    { "stick_gamma" SUF, 1.0f, 0.5f, 3.0f }, { "stick_edge" SUF, 0.95f, 0.6f, 1.0f }, { "stick_sens" SUF, 1.0f, 0.5f, 2.0f }, \
    { "trig_ramp" SUF, 12.f, 2.f, 60.f }, { "grip_ramp" SUF, 12.f, 2.f, 60.f }, \
    { "click_thr" SUF, 0.9f, 0.3f, 1.0f }, \
    { "yaw_drift" SUF, 0.f, -15.f, 15.f } }
inline const CtlSettings kCtl[HAND_COUNT] = { KBT_CTL_SETTINGS(""), KBT_CTL_SETTINGS("_left") };

// Rotacao do MODELO/ponteiro por perfil (graus, aplicada localmente ao controle): corrige o modelo Genérico/Index girado.
// Chaves: rot_off_<perfil>_<pitch|yaw|roll>; vale para as duas maos (yaw/roll com sinal espelhado na esquerda).
inline const FloatSetting kRotOff[5][3] = {
    { { "rot_off_oculus_pitch",  0.f, -180.f, 180.f }, { "rot_off_oculus_yaw",  0.f, -180.f, 180.f }, { "rot_off_oculus_roll",  0.f, -180.f, 180.f } },
    { { "rot_off_vive_pitch",    0.f, -180.f, 180.f }, { "rot_off_vive_yaw",    0.f, -180.f, 180.f }, { "rot_off_vive_roll",    0.f, -180.f, 180.f } },
    { { "rot_off_index_pitch",   0.f, -180.f, 180.f }, { "rot_off_index_yaw",   0.f, -180.f, 180.f }, { "rot_off_index_roll",   0.f, -180.f, 180.f } },
    { { "rot_off_generic_pitch", 0.f, -180.f, 180.f }, { "rot_off_generic_yaw", 0.f, -180.f, 180.f }, { "rot_off_generic_roll", 0.f, -180.f, 180.f } },
    { { "rot_off_frame_pitch",   0.f, -180.f, 180.f }, { "rot_off_frame_yaw",   0.f, -180.f, 180.f }, { "rot_off_frame_roll",   0.f, -180.f, 180.f } } };

// Perfil de entrada do controle virtual (trocar exige reiniciar o SteamVR)
enum Profile { PF_OCULUS = 0, PF_VIVE, PF_INDEX, PF_GENERIC, PF_FRAME, PF_COUNT };   // PF_FRAME = controle do Steam Frame (driver {frame_controller} do SteamVR)
inline const IntSetting kProfile = { "ctl_profile", PF_OCULUS, 0, PF_COUNT - 1 };

// Eixos do MPU -> eixos de saida: ativacao, suavizacao individual, ganho ja existe (kAxGain)

// Gatilho / grip: rampa de resposta separada; analogico: sensibilidade

// Arm Model (estimativa do braco a partir da orientacao do controle; NAO e rastreamento real)
inline const BoolSetting  kArmEnabled = { "arm_enabled", false };
inline const FloatSetting kArmUpper   = { "arm_upper",   0.30f, 0.15f, 0.50f };   // ombro -> cotovelo (m)
inline const FloatSetting kArmFore    = { "arm_fore",    0.32f, 0.15f, 0.50f };   // cotovelo -> mao (m)
inline const FloatSetting kArmShX     = { "arm_sh_x",    0.17f, 0.05f, 0.30f };   // ombro para o lado (m)
inline const FloatSetting kArmShDrop  = { "arm_sh_drop", 0.20f, 0.05f, 0.40f };   // ombro abaixo da cabeca (m)
inline const FloatSetting kArmBendUp   = { "arm_bend_up",   0.35f, 0.f, 1.f };   // mao para CIMA: cotovelo recua (fracao do pitch)
inline const FloatSetting kArmBendDown = { "arm_bend_down", 0.55f, 0.f, 1.f };   // mao para BAIXO: cotovelo vai para FRENTE
inline const FloatSetting kArmSideReach = { "arm_side_reach", 0.60f, 0.f, 2.f }; // alcance extra ao apontar para os lados
inline const FloatSetting kArmTorso    = { "arm_torso",     0.25f, 0.f, 1.f };   // quanto o tronco/ombro gira junto com o controle
inline const FloatSetting kArmReach    = { "arm_reach",     1.00f, 0.6f, 1.6f }; // escala geral do alcance do braco
// Previa manual do Arm Model no menu (so interface)
inline const BoolSetting  kPrevManual  = { "ui_prev_manual", false };
inline const FloatSetting kPrevPitch   = { "ui_prev_pitch", 0.f, -90.f, 90.f };
inline const FloatSetting kPrevYaw     = { "ui_prev_yaw",   0.f, -120.f, 120.f };

// ---- Outras opcoes
inline const IntSetting   kEspChannel = { "esp_channel", 0, 0, 2 };               // canal do ESP32: 0 Auto, 1 SteamVR (Wi-Fi), 2 Android (Bluetooth)
inline const BoolSetting  kHideLost  = { "hide_lost", false };                   // modo "So ESP32": esconde o controle se o ESP32 cair
inline const BoolSetting  kForceFs   = { "hmd_force_fs", true };
// Monitor comum como headset: a janela inteira mostra UMA imagem (sem lado a lado, sem distorcao de lente, IPD 0, FOV pela proporcao da tela).
// A pose fica sempre valida (monitor parado), mesmo sem celular conectado.
inline const BoolSetting  kHmdMonitor = { "hmd_monitor_mode", false };
// Impede a tela preta quando o headset fica PARADO: (1) o Windows nao apaga/dorme a tela do headset (ES_DISPLAY_REQUIRED) e (2) um micro-movimento de
// ~1 mm a cada poucos segundos mantem o SteamVR de fora do standby por "headset ocioso" (sem proximidade, o SteamVR usa o movimento para saber se ha alguem).
inline const BoolSetting  kAntiIdle   = { "hmd_anti_idle", true };
inline const FloatSetting kPredictMs = { "predict_ms", 15.0f, 0.0f, 60.0f };      // previsao de movimento (ms): suaviza o "baixo FPS" entre pacotes e compensa a latencia (0 = desliga)
// ---- Etapa 20: cabeca (suavizacao/previsao), mundo, quaternion dos controles, Arm Model simples, maos
inline const FloatSetting kHeadSmooth    = { "head_smooth", 35.f, 0.f, 100.f };     // suavizacao da cabeca (%): 0 = desligada, 100 = bem suave. Filtro adaptativo (One Euro): parada = estavel, girando = quase sem atraso
inline const FloatSetting kHeadPredictMs = { "head_predict_ms", 20.f, 0.f, 60.f };  // previsao da cabeca (ms) com a velocidade angular medida (0 = desliga)
inline const FloatSetting kWorldYaw      = { "world_yaw", 0.f, -180.f, 180.f };   // gira o mundo todo (cabeca + controles) em volta da vertical: corrige o "180 graus em relacao ao Beat Saber"
inline const FloatSetting kHeadRoll      = { "head_roll", 0.f, -180.f, 180.f };      // gira a imagem do headset em volta do eixo da frente (180 = corrige mundo de cabeca para baixo)
inline const BoolSetting  kCtlQuat       = { "ctl_quat", true };                    // orientacao dos controles por QUATERNION (sem limite de +-89 graus nem pulos ao apontar para cima/baixo)
inline const FloatSetting kCtlSmooth     = { "ctl_smooth", 25.f, 0.f, 100.f };      // suavizacao dos controles (%): mesma ideia da cabeca (so no modo quaternion)
inline const IntSetting   kArmMode       = { "arm_mode", 0, 0, 1 };                 // 0 = completo (ombro/cotovelo), 1 = SIMPLES (esfera: controle preso por um cabo de comprimento fixo)
inline const FloatSetting kArmSimpleLen  = { "arm_simple_len", 0.30f, 0.05f, 1.00f };  // raio da esfera do modo simples (m) = antebraco+mao
// Posicao 6DoF do celular-controle (KbtPacketP): mapeamento/inversao/escala aplicados NO DRIVER (alem dos do app). Entradas: 0 = frente, 1 = esquerda, 2 = cima.
// Saidas (referencial do corpo do SteamVR): x = direita, y = cima, z = tras. Padrao: x <- esquerda invertida, y <- cima, z <- frente invertida.
inline const IntSetting   kPos6SrcX = { "pos6_src_x", 1, 0, 2 };
inline const IntSetting   kPos6SrcY = { "pos6_src_y", 2, 0, 2 };
inline const IntSetting   kPos6SrcZ = { "pos6_src_z", 0, 0, 2 };
inline const BoolSetting  kPos6InvX = { "pos6_inv_x", true };
inline const BoolSetting  kPos6InvY = { "pos6_inv_y", false };
inline const BoolSetting  kPos6InvZ = { "pos6_inv_z", true };
inline const FloatSetting kPos6Scale = { "pos6_scale", 1.0f, 0.25f, 3.0f };
inline const FloatSetting kArmSimpleElbow = { "arm_simple_elbow", 0.28f, 0.00f, 0.70f };  // modo simples: quanto o COTOVELO (centro da esfera) fica abaixo do ombro (m)
inline const FloatSetting kHandsSmooth   = { "hands_smooth", 45.f, 0.f, 100.f };    // suavizacao da POSICAO das maos rastreadas (%); 0 = sem filtro
inline const BoolSetting  kHeadFlip  = { "head_flip_180", false };            // gira a cabeca 180 graus no eixo da frente (corrige "de cabeca pra baixo")
inline const BoolSetting  kMakePrimary = { "hmd_make_primary", true };           // torna o monitor do headset o principal do Windows enquanto o SteamVR estiver aberto                 // mantem a janela do compositor em tela cheia (anti erro -202)

// ---- Arm Model: resolvedor compartilhado (driver e previa do menu usam o MESMO codigo)
struct ArmParams { double upper = .30, fore = .32, shX = .17, shDrop = .20, bendUp = .35, bendDown = .55, sideReach = .6, torso = .25, reach = 1.0; };
// Vetores em metros, referencial do mundo (y para cima, frente = -z), relativos a posicao da cabeca.
// ctlYaw/ctlPitch: direcao do controle; fwd: vetor frente do controle; side: +1 direita, -1 esquerda.
// torsoYaw: yaw do tronco ja suavizado pelo chamador (NaN = calcula na hora, sem suavizar)
inline void ArmSolve(const ArmParams& p, double headYaw, double ctlYaw, double ctlPitch, const double fwd[3], double side,
                     double sh[3], double el[3], double hand[3], double torsoYaw = std::numeric_limits<double>::quiet_NaN()) {
    const double PI = 3.14159265358979;
    double rel = ctlYaw - headYaw;
    while (rel > PI) rel -= 2 * PI;
    while (rel < -PI) rel += 2 * PI;
    const double ty = std::isnan(torsoYaw) ? headYaw + rel * p.torso : torsoYaw;   // o tronco gira com parte do controle (suavizado)
    const double shx = side * p.shX, shy = -p.shDrop;
    sh[0] = shx * std::cos(ty); sh[1] = shy; sh[2] = -shx * std::sin(ty);
    // cotovelo: desce do ombro; vai para FRENTE quando o controle aponta para baixo e recua um pouco quando aponta para cima
    const double ratio = ctlPitch >= 0 ? p.bendUp : p.bendDown;
    const double th = -ratio * ctlPitch;
    // o cotovelo fica um pouco PARA FORA quando a mao esta a frente do corpo e se aproxima do tronco ao cruzar os bracos
    const double ux = side * p.upper * 0.16 * (1.0 - std::fabs(std::sin(rel))) - side * p.upper * 0.10 * std::max(0.0, std::sin(rel) * side);
    const double uy = -p.upper * std::cos(th), uz = -p.upper * std::sin(th);
    const double ca = std::cos(ctlYaw), sa = std::sin(ctlYaw);
    double e[3] = { ux * ca + uz * sa, uy, -ux * sa + uz * ca };
    double h[3] = { e[0] + fwd[0] * p.fore, e[1] + fwd[1] * p.fore, e[2] + fwd[2] * p.fore };
    // alcance: ao apontar para os lados o braco "estica" (horizontalmente)
    const double kh = p.reach * (1.0 + p.sideReach * std::fabs(std::sin(rel)));
    e[0] *= kh; e[2] *= kh; e[1] *= p.reach; h[0] *= kh; h[2] *= kh; h[1] *= p.reach;
    // limite de alcance: o braco nunca estica alem do comprimento real (com folga p/ o alcance lateral configurado)
    const double maxL = (p.upper + p.fore) * 1.12 * std::max(1.0, p.reach);
    const double hl = std::sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]);
    if (hl > maxL) { const double k = maxL / hl; for (int i = 0; i < 3; ++i) { h[i] *= k; e[i] *= k; } }
    for (int i = 0; i < 3; ++i) { el[i] = sh[i] + e[i]; hand[i] = sh[i] + h[i]; }
}

#ifdef _WIN32
// ---- Tornar um monitor o PRINCIPAL do Windows (e restaurar depois)
// O SteamVR so se comporta bem em modo estendido quando a janela do compositor esta no monitor principal.
// O layout original e salvo em %LOCALAPPDATA%\StormVR\display_restore.txt e restaurado ao fechar o SteamVR
// (ou na proxima abertura, se o SteamVR tiver fechado de forma abrupta, ou pelo botao do menu).
inline std::string DisplayRestorePath(bool create) {
    char base[MAX_PATH] = {};
    const DWORD n = GetEnvironmentVariableA("LOCALAPPDATA", base, MAX_PATH);
    const std::string dir = (n ? std::string(base) : std::string(".")) + "\\StormVR";
    if (create) CreateDirectoryA(dir.c_str(), nullptr);
    return dir + "\\display_restore.txt";
}
inline bool DisplayLayoutPending() { return GetFileAttributesA(DisplayRestorePath(false).c_str()) != INVALID_FILE_ATTRIBUTES; }
// Posiciona todos os monitores de 'mons' de modo que 'primaryDev' fique em (0,0) e seja o principal.
inline bool ApplyDisplayLayout(const std::vector<MonitorInfo>& mons, const std::string& primaryDev) {
    int ox = 0, oy = 0; bool found = false;
    for (const MonitorInfo& m : mons) if (m.dev == primaryDev) { ox = m.x; oy = m.y; found = true; }
    if (!found) return false;
    for (int pass = 0; pass < 2; ++pass)                       // o novo principal primeiro, depois os demais
        for (const MonitorInfo& m : mons) {
            if ((m.dev == primaryDev) != (pass == 0)) continue;
            DEVMODEA dm = {}; dm.dmSize = sizeof(dm);
            if (!EnumDisplaySettingsA(m.dev.c_str(), ENUM_CURRENT_SETTINGS, &dm)) continue;
            dm.dmPosition.x = m.x - ox; dm.dmPosition.y = m.y - oy; dm.dmFields |= DM_POSITION;
            DWORD fl = CDS_UPDATEREGISTRY | CDS_NORESET;
            if (m.dev == primaryDev) fl |= CDS_SET_PRIMARY;
            ChangeDisplaySettingsExA(m.dev.c_str(), &dm, nullptr, fl, nullptr);
        }
    return ChangeDisplaySettingsExA(nullptr, nullptr, nullptr, 0, nullptr) == DISP_CHANGE_SUCCESSFUL;
}
inline bool MakeMonitorPrimary(const std::string& dev) {
    const std::vector<MonitorInfo> mons = ListMonitors();
    const MonitorInfo* t = nullptr;
    for (const MonitorInfo& m : mons) if (m.dev == dev) t = &m;
    if (!t) return false;
    if (t->primary) return true;
    if (!DisplayLayoutPending()) {                              // guarda o layout ORIGINAL (nao sobrescreve um anterior)
        FILE* f = fopen(DisplayRestorePath(true).c_str(), "w");
        if (!f) return false;
        for (const MonitorInfo& m : mons) fprintf(f, "%s\t%d\t%d\t%d\n", m.dev.c_str(), m.x, m.y, m.primary ? 1 : 0);
        fclose(f);
    }
    return ApplyDisplayLayout(mons, dev);
}
// Restaura o layout salvo. Devolve true se havia algo a restaurar e deu certo.
inline bool RestoreDisplayLayout() {
    const std::string path = DisplayRestorePath(false);
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return false;
    std::vector<MonitorInfo> saved; std::string orig;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char dev[64] = {}; int x = 0, y = 0, pr = 0;
        if (sscanf(line, "%63[^\t]\t%d\t%d\t%d", dev, &x, &y, &pr) == 4) {
            MonitorInfo m; m.dev = dev; m.x = x; m.y = y; m.primary = pr != 0; saved.push_back(m);
            if (pr) orig = dev;
        }
    }
    fclose(f);
    bool ok = false;
    if (!orig.empty()) {
        std::vector<MonitorInfo> now = ListMonitors();           // so mexe nos monitores que ainda existem
        std::vector<MonitorInfo> use;
        for (const MonitorInfo& m : saved) for (const MonitorInfo& n : now) if (n.dev == m.dev) use.push_back(m);
        ok = ApplyDisplayLayout(use, orig);
    }
    DeleteFileA(path.c_str());
    return ok;
}

#endif  // _WIN32 (layout de monitores)

// ---- Memoria compartilhada driver <-> app de configuracao (telemetria ao vivo + comandos)
enum { CMD_RECENTER_YAW = 0, CMD_ZERO_ALL = 1, CMD_GYRO_CAL = 2, CMD_STICK_CENTER = 3, CMD_RECENTER_HEAD = 4, CMD_RESTART_UDP = 5,
       CMD_FLIP_HAND_R = 6,     // pede ao controle que esta na mao DIREITA para virar ESQUERDA (grava no ESP32) e vice-versa
       CMD_FLIP_HAND_L = 7,
       CMD_PIN_R = 8,           // grava o IP do controle atual da mao direita em ctl_ip_right (modo IP fixo)
       CMD_PIN_L = 9,
       CMD_RECENTER_YAW_R = 10, // recentra so o yaw do controle DIREITO
       CMD_RECENTER_YAW_L = 11, // recentra so o yaw do controle ESQUERDO
       CMD_RESET_CHAPERONE = 12,// tratado pelo app da dashboard (stormvr_config): reseta piso/espaco de jogo (painel web usa este comando)
       CMD_RESTART_STEAMVR = 13,// tratado pelo app da dashboard: reinicia o SteamVR
       CMD_COUNT = 14 };
constexpr const char* kLiveName = "Local\\StormVRLive";
// Telemetria de UM controle (a mao que ele ocupa: 0 = direita, 1 = esquerda)
struct CtlLive {
    uint32_t link;                 // 0 = sem controle nesta mao, 1 = conectado, 2 = calibrando giroscopio
    uint32_t kind;                 // 0 = desconhecido, 1 = ESP32 (tem RSSI/giroscopio), 2 = celular
    float    pktRate;              // pacotes/s
    int32_t  rssi;
    uint32_t buttons;              // KBT_BTN_* (fisicos, antes do combo)
    float    ageMs;                // ms desde o ultimo pacote (999999 = nunca)
    uint32_t lost, dropped;        // pacotes perdidos (lacunas de seq) / descartados (velhos)
    uint32_t flags;                // KBT_FLAG_* enviados pelo controle
    uint64_t outBits;              // bit n = Out n acionada agora por ESTE controle (depois do mapeamento)
    uint32_t orient;               // 1 = a orientacao desta mao vem do controle, 0 = teclado
    float    rawRpy[3];            // roll, pitch, yaw brutos
    float    mpu[3];               // roll, pitch, yaw depois do recentrar + zona morta
    float    out[C_COUNT];         // yaw, pitch, roll enviados ao controle virtual (depois do mapeamento)
    float    gyro[3];              // graus/s
    uint16_t joyRaw[2];
    float    stickNorm[2];         // analogico normalizado, antes de inverter/zona morta
    float    stick[2];             // analogico final (-1..1)
    uint32_t uptimeMs;
    char     ip[16];
    // ---- Arm Model, relativo a cabeca, referencial do corpo: x direita, y cima, z para tras
    uint32_t armOn;
    float    armJ[3][3];           // ombro, cotovelo, mao
};
struct Live {
    uint32_t version;
    LONG     cmd[CMD_COUNT];       // contadores incrementados pelo app
    uint32_t profile;              // perfil de entrada ATIVO (Profile)
    uint64_t outBits;              // bit n = Out n acionada agora (soma dos dois controles)
    // ---- controles
    CtlLive  ctl[HAND_COUNT];
    uint32_t srcSeen;              // quantas fontes (IPs) enviaram pacotes recentemente
    uint32_t srcUnassigned;        // fontes vivas que nao ocuparam nenhuma mao (ja ha dois controles, ou IP fixo sem correspondencia)
    char     extraIp[16];          // IP de uma fonte sem mao (diagnostico)
    // ---- maos rastreadas (celular)
    uint32_t handLink;             // 0 = nunca recebeu, 1 = recebendo, 2 = sem pacotes
    float    handRate;
    char     handIp[16];
    uint32_t handTracked[HAND_COUNT];   // a camera ve a mao
    uint32_t handPinchNow[HAND_COUNT];  // pinca fechada
    uint32_t handPosUsed[HAND_COUNT];   // a posicao esta sendo aplicada ao controle virtual
    // ---- cabeca (celular)
    uint32_t headLink;             // 0=nunca recebeu, 1=recebendo, 2=sem pacotes (rastreamento perdido)
    float    headRate;
    float    headYpr[3];
    char     headIp[16];
    // ---- janela do headset (anti erro -202)
    int32_t  hmdWin[4];            // x, y, largura, altura pedidos ao SteamVR
    uint32_t hmdStatus;            // 0 = monitor escolhido (inteiro), 1 = escolha automatica, 2 = ajustado/sem monitor, 3 = resolucao do monitor alterada
    uint32_t fsFixes;              // vezes que a janela do compositor foi recolocada em tela cheia
    float    hmdPos[3];            // posicao FINAL da cabeca enviada ao SteamVR (m; x direita, y cima, -z frente)
    float    hmdUp[3];             // para onde o TOPO da cabeca aponta (y < 0 = de cabeca pra baixo)
    float    ctlPosW[HAND_COUNT][3]; // posicao FINAL de cada controle enviada ao SteamVR
    uint32_t madePrimary;          // 1 = o driver tornou o monitor do headset o principal (sera restaurado ao fechar)
    // ---- diagnostico: ultimas mensagens do driver
    uint32_t logSeq;
    char     log[8][96];
};

} // namespace kbt
