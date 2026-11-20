// Ponte "controle do StormVR -> Xbox 360 virtual" (ViGEmBus). Usada pelo stormvr_gamepad.exe e pelo stormvr_config.exe (modo automatico
// no SteamVR). O ViGEmClient.dll e carregado em tempo de execucao (ao lado do .exe); sem ele/sem o ViGEmBus, Open() devolve a mensagem de erro.
#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <cstdint>
#include <string>
#include "protocol.h"

namespace gp {
#pragma pack(push, 1)
struct XUSB_REPORT { USHORT wButtons; BYTE bLeftTrigger, bRightTrigger; SHORT sThumbLX, sThumbLY, sThumbRX, sThumbRY; };
#pragma pack(pop)
enum { XB_UP = 0x0001, XB_DOWN = 0x0002, XB_LEFT = 0x0004, XB_RIGHT = 0x0008, XB_START = 0x0010, XB_BACK = 0x0020, XB_LTHUMB = 0x0040,
       XB_RTHUMB = 0x0080, XB_LB = 0x0100, XB_RB = 0x0200, XB_GUIDE = 0x0400, XB_A = 0x1000, XB_B = 0x2000, XB_X = 0x4000, XB_Y = 0x8000 };

struct Src { bool alive = false; uint16_t btn = 0; float x = 0, y = 0; };   // x,y: analogico final -1..1 (y positivo = cima)

inline SHORT Axis(float v) { if (v > 1.f) v = 1.f; if (v < -1.f) v = -1.f; return (SHORT)(v * 32767.f); }

// UM controle: layout Xbox completo. DOIS: esquerdo = LS/LT/LB/D-pad, direito = RS/RT/RB/A B X Y.
inline XUSB_REPORT Build(const Src& R, const Src& L) {
    XUSB_REPORT r = {};
    auto has = [](const Src& s, unsigned b) { return (s.btn & b) != 0; };
    auto trig = [](const Src& s, unsigned b) -> BYTE { return (s.btn & b) ? 255 : 0; };
    if (R.alive && L.alive) {
        r.sThumbLX = Axis(L.x); r.sThumbLY = Axis(L.y); r.sThumbRX = Axis(R.x); r.sThumbRY = Axis(R.y);
        r.bLeftTrigger = trig(L, KBT_BTN_TRIGGER); r.bRightTrigger = trig(R, KBT_BTN_TRIGGER);
        if (has(L, KBT_BTN_GRIP)) r.wButtons |= XB_LB;     if (has(R, KBT_BTN_GRIP)) r.wButtons |= XB_RB;
        if (has(L, KBT_BTN_A)) r.wButtons |= XB_DOWN;      if (has(L, KBT_BTN_B)) r.wButtons |= XB_RIGHT;
        if (has(L, KBT_BTN_X)) r.wButtons |= XB_LEFT;      if (has(L, KBT_BTN_Y)) r.wButtons |= XB_UP;
        if (has(L, KBT_BTN_START)) r.wButtons |= XB_BACK;  if (has(L, KBT_BTN_L3)) r.wButtons |= XB_LTHUMB;
        if (has(R, KBT_BTN_A)) r.wButtons |= XB_A;         if (has(R, KBT_BTN_B)) r.wButtons |= XB_B;
        if (has(R, KBT_BTN_X)) r.wButtons |= XB_X;         if (has(R, KBT_BTN_Y)) r.wButtons |= XB_Y;
        if (has(R, KBT_BTN_START)) r.wButtons |= XB_START; if (has(R, KBT_BTN_L3)) r.wButtons |= XB_RTHUMB;
        if (has(R, KBT_BTN_R2) || has(L, KBT_BTN_R2)) r.wButtons |= XB_GUIDE;
    } else if (R.alive || L.alive) {
        const Src& s = R.alive ? R : L;
        r.sThumbLX = Axis(s.x); r.sThumbLY = Axis(s.y);
        r.bRightTrigger = trig(s, KBT_BTN_TRIGGER); r.bLeftTrigger = trig(s, KBT_BTN_R2);
        if (has(s, KBT_BTN_GRIP)) r.wButtons |= XB_RB;
        if (has(s, KBT_BTN_A)) r.wButtons |= XB_A; if (has(s, KBT_BTN_B)) r.wButtons |= XB_B;
        if (has(s, KBT_BTN_X)) r.wButtons |= XB_X; if (has(s, KBT_BTN_Y)) r.wButtons |= XB_Y;
        if (has(s, KBT_BTN_START)) r.wButtons |= XB_START; if (has(s, KBT_BTN_L3)) r.wButtons |= XB_LTHUMB;
    }
    return r;
}

class Bridge {
public:
    bool isOpen() const { return pad_ != nullptr; }
    // dir: pasta onde procurar o ViGEmClient.dll (com "\" no fim). Devolve false e preenche err em portugues.
    bool Open(const std::string& dir, std::string& err) {
        if (pad_) return true;
        HMODULE dll = LoadLibraryA((dir + "ViGEmClient.dll").c_str());
        if (!dll) dll = LoadLibraryA("ViGEmClient.dll");
        if (!dll) { err = "ViGEmClient.dll nao encontrado (copie-o para a pasta do StormVR)"; return false; }
        alloc_ = (PAlloc)GetProcAddress(dll, "vigem_alloc");
        connect_ = (PConnect)GetProcAddress(dll, "vigem_connect");
        x360_ = (PX360Alloc)GetProcAddress(dll, "vigem_target_x360_alloc");
        add_ = (PAdd)GetProcAddress(dll, "vigem_target_add");
        upd_ = (PUpd)GetProcAddress(dll, "vigem_target_x360_update");
        if (!alloc_ || !connect_ || !x360_ || !add_ || !upd_) { err = "ViGEmClient.dll incompativel"; return false; }
        void* cl = alloc_();
        if (!cl || connect_(cl) != 0x20000000) { err = "ViGEmBus nao instalado (instale o driver ViGEmBus e reinicie)"; return false; }
        void* pad = x360_();
        if (!pad || add_(cl, pad) != 0x20000000) { err = "nao foi possivel criar o controle Xbox virtual"; return false; }
        client_ = cl; pad_ = pad; return true;
    }
    void Send(const XUSB_REPORT& r) { if (pad_) upd_(client_, pad_, r); }
private:
    typedef void* (*PAlloc)(); typedef uint32_t (*PConnect)(void*); typedef void* (*PX360Alloc)();
    typedef uint32_t (*PAdd)(void*, void*); typedef uint32_t (*PUpd)(void*, void*, XUSB_REPORT);
    PAlloc alloc_ = nullptr; PConnect connect_ = nullptr; PX360Alloc x360_ = nullptr; PAdd add_ = nullptr; PUpd upd_ = nullptr;
    void* client_ = nullptr; void* pad_ = nullptr;
};
} // namespace gp
