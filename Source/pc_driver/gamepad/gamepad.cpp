// StormVR Gamepad — usa o controle do StormVR (ESP32 ou celular em "Modo controle") como um controle de Xbox 360 virtual,
// para jogos de TELA PLANA (Cuphead etc.), como o Steam Frame Controller faz no desktop. Funciona SEM o SteamVR aberto.
//
// Requisitos (uma vez): driver ViGEmBus instalado e o arquivo ViGEmClient.dll na mesma pasta deste .exe.
// IMPORTANTE: este programa e o driver StormVR no SteamVR usam as mesmas portas UDP (5555 direita / 5558 esquerda).
//             Use um OU o outro: feche o SteamVR antes de abrir o Gamepad.
//
// Layout:
//   UM controle  : stick -> analogico esquerdo; A/B/X/Y -> A/B/X/Y; Trigger -> RT; R2 -> LT; Grip -> RB; Start -> START; L3 -> LS.
//   DOIS controles: esquerdo: stick -> LS, Trigger -> LT, Grip -> LB, A/B/X/Y -> D-pad (baixo/direita/esquerda/cima), Start -> BACK, L3 -> LS
//                   direito : stick -> RS, Trigger -> RT, Grip -> RB, A/B/X/Y -> A/B/X/Y, Start -> START, L3 -> RS, R2 -> GUIDE
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <string>
#include <algorithm>
#pragma comment(lib, "ws2_32.lib")

#include "gamepad_bridge.h"
using namespace gp;
struct SrcS { bool alive = false; DWORD last = 0; uint16_t btn = 0; float x = 0, y = 0; };
static SrcS g_src[2];                       // [0] direita (5555), [1] esquerda (5558)
static int g_center[2][2] = { { 2048, 2048 }, { 2048, 2048 } }; static int g_cn[2] = { 0, 0 }; static long g_acc[2][2] = {};

static float Stick(int h, int adc, bool inv) {
    float v = (float)(adc - g_center[h][inv ? 1 : 0]) / 1900.f; if (inv) v = -v;       // Y: ADC menor = cima
    const float dz = 0.12f; const float a = std::fabs(v);
    if (a < dz) return 0.f;
    return std::max(-1.f, std::min(1.f, (v > 0 ? 1.f : -1.f) * (a - dz) / (1.f - dz)));
}

static XUSB_REPORT BuildNow() {
    const DWORD now = GetTickCount();
    for (auto& s : g_src) if (s.alive && now - s.last > 500) s.alive = false;
    Src R, L;
    R.alive = g_src[0].alive; R.btn = g_src[0].btn; R.x = g_src[0].x; R.y = g_src[0].y;
    L.alive = g_src[1].alive; L.btn = g_src[1].btn; L.x = g_src[1].x; L.y = g_src[1].y;
    return Build(R, L);
}

int main() {
    SetConsoleOutputCP(65001);
    printf("StormVR Gamepad (Xbox 360 virtual)\n");
    char exe[MAX_PATH]; GetModuleFileNameA(nullptr, exe, MAX_PATH);
    std::string dir(exe); dir = dir.substr(0, dir.find_last_of("\\/") + 1);
    Bridge bridge; std::string err;
    if (!bridge.Open(dir, err)) { printf("ERRO: %s\n", err.c_str()); system("pause"); return 1; }

    WSADATA wd; WSAStartup(MAKEWORD(2, 2), &wd);
    SOCKET so[2]; const int ports[2] = { KBT_PORT_PC, KBT_PORT_PC_LEFT };
    for (int i = 0; i < 2; ++i) {
        so[i] = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        BOOL yes = TRUE; setsockopt(so[i], SOL_SOCKET, SO_REUSEADDR, (const char*)&yes, sizeof yes);
        sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_port = htons((u_short)ports[i]); a.sin_addr.s_addr = INADDR_ANY;
        if (bind(so[i], (sockaddr*)&a, sizeof a) != 0) { printf("ERRO: porta UDP %d ocupada (SteamVR aberto?). Feche o SteamVR.\n", ports[i]); system("pause"); return 1; }
    }
    printf("Pronto. Controle direito -> UDP %d, esquerdo -> UDP %d. Controle virtual Xbox 360 criado.\nCtrl+C para sair.\n", ports[0], ports[1]);
    DWORD lastSend = 0, lastPrint = 0;
    for (;;) {
        fd_set fds; FD_ZERO(&fds); FD_SET(so[0], &fds); FD_SET(so[1], &fds);
        timeval tv = { 0, 4000 };
        const int n = select(0, &fds, nullptr, nullptr, &tv);
        bool got = false;
        for (int i = 0; i < 2 && n > 0; ++i) if (FD_ISSET(so[i], &fds)) {
            char raw[128]; int len = recv(so[i], raw, sizeof raw, 0);
            KbtPacket pk = {};
            if (len == (int)sizeof(KbtPacketQ)) {                    // firmware novo (quaternion): aqui so botoes e analogico interessam
                KbtPacketQ q; memcpy(&q, raw, sizeof q);
                if (q.magic != KBT_MAGIC_Q) continue;
                pk.magic = KBT_MAGIC; pk.buttons = q.buttons; pk.joyX = q.joyX; pk.joyY = q.joyY; pk.flags = q.flags;
            } else if (len == (int)sizeof(KbtPacket)) {
                memcpy(&pk, raw, sizeof pk);
                if (pk.magic != KBT_MAGIC) continue;
            } else continue;
            int h = i;                                               // porta 5558 = esquerda; porta 5555 = pela flag do controle
            if (i == 0 && (pk.flags & KBT_FLAG_HAND_LEFT)) h = 1;
            SrcS& s = g_src[h];
            if (g_cn[h] < 30) { g_acc[h][0] += pk.joyX; g_acc[h][1] += pk.joyY; if (++g_cn[h] == 30) { g_center[h][0] = (int)(g_acc[h][0] / 30); g_center[h][1] = (int)(g_acc[h][1] / 30); } }
            s.alive = true; s.last = GetTickCount(); s.btn = pk.buttons;
            s.x = Stick(h, pk.joyX, false); s.y = Stick(h, pk.joyY, true); got = true;
        }
        const DWORD now = GetTickCount();
        if (got || now - lastSend > 16) { bridge.Send(BuildNow()); lastSend = now; }
        if (now - lastPrint > 1000) { printf("\rcontroles: direito=%s esquerdo=%s   ", g_src[0].alive ? "ok" : "--", g_src[1].alive ? "ok" : "--"); fflush(stdout); lastPrint = now; }
    }
}
