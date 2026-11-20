// stormvr_config.exe: aba "StormVR" na dashboard do SteamVR.
// Desenha a interface com GDI numa imagem e envia ao overlay com SetOverlayRaw.
// Le/grava as configuracoes do driver pelo VRSettings (secao driver_stormvr).
#define NOMINMAX
#include <windows.h>
#include <openvr.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <sstream>
#include <thread>
#include <string>
#include <vector>
#include "../common/common.h"

using namespace kbt;

// Se os cliques ficarem espelhados na vertical, troque para false.
static constexpr bool kFlipY = true;

static constexpr int W = 1160, H = 650;

// ------------------------------------------------------------- configuracoes
static bool  GetB(const char* k, bool d) { vr::EVRSettingsError e; bool v = vr::VRSettings()->GetBool(kSection, k, &e); return e == vr::VRSettingsError_None ? v : d; }
static int   GetI(const char* k, int d)  { vr::EVRSettingsError e; int v = vr::VRSettings()->GetInt32(kSection, k, &e); return e == vr::VRSettingsError_None ? v : d; }
static float GetF(const FloatSetting& s) { vr::EVRSettingsError e; float v = vr::VRSettings()->GetFloat(kSection, s.key, &e); return e == vr::VRSettingsError_None ? v : s.def; }
static bool  GetB(const BoolSetting& s)  { return GetB(s.key, s.def); }
static int   GetI(const IntSetting& s)   { int v = GetI(s.key, s.def); return v < s.mn ? s.mn : (v > s.mx ? s.mx : v); }
static void  SetB(const char* k, bool v) { vr::EVRSettingsError e; vr::VRSettings()->SetBool(kSection, k, v, &e); }
static void  SetI(const char* k, int v)  { vr::EVRSettingsError e; vr::VRSettings()->SetInt32(kSection, k, v, &e); }
static std::string GetS(const char* k) {
    char buf[160] = {}; vr::EVRSettingsError e;
    vr::VRSettings()->GetString(kSection, k, buf, sizeof(buf), &e);
    return e == vr::VRSettingsError_None ? std::string(buf) : std::string();
}
static void  SetS(const char* k, const std::string& v) { vr::EVRSettingsError e; vr::VRSettings()->SetString(kSection, k, v.c_str(), &e); }
static std::wstring WS(const std::string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_ACP, 0, s.c_str(), (int)s.size(), nullptr, 0);
    std::wstring w(n, L'\0'); MultiByteToWideChar(CP_ACP, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}
static void  SetF(const FloatSetting& s, float v) { vr::EVRSettingsError e; vr::VRSettings()->SetFloat(kSection, s.key, v, &e); }

// ---- telemetria / comandos (memoria compartilhada criada pelo driver)
static Live* g_live = nullptr;
static Live  g_L = {};
static bool  g_haveLive = false;
static void OpenLive() {
    if (g_live) return;
    HANDLE m = OpenFileMappingA(FILE_MAP_ALL_ACCESS, FALSE, kLiveName);
    if (!m) return;
    g_live = static_cast<Live*>(MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Live)));
}
static void FetchLive() {
    OpenLive();
    if (g_live) { memcpy(&g_L, g_live, sizeof(Live)); g_haveLive = true; }
    else { memset(&g_L, 0, sizeof(Live)); g_haveLive = false; }
}
static void SendCmd(int i) { OpenLive(); if (g_live) InterlockedIncrement(&g_live->cmd[i]); }

static std::string Snapshot() {
    std::string s;
    char b[64];
    for (const char* k : { kKeysEnabled, kFollowHmd, kFollowHmdRot, kEnabled[0], kEnabled[1], kWebAutostart }) s += GetB(k, true) ? '1' : '0';
    for (const FloatSetting* f : { &kMoveSpeed, &kRotSpeed, &kFixedHeight, &kEspTimeout, &kArmUpper, &kArmFore, &kArmShX, &kArmShDrop, &kArmBendUp, &kArmBendDown, &kArmSideReach, &kArmTorso, &kArmReach }) { snprintf(b, sizeof b, "%.3f;", GetF(*f)); s += b; }
    for (int h = 0; h < HAND_COUNT; ++h) {
        for (int i = 0; i < 3; ++i) { snprintf(b, sizeof b, "%.3f;", GetF(kOffset[h][i])); s += b; }
        for (int a = 0; a < A_COUNT; ++a) { snprintf(b, sizeof b, "%d;", GetI(KeySetting(h, a).c_str(), DefaultKey(h, a))); s += b; }
    }
    for (const char* k : { kKeyMoveMod, kKeyDepthMod, kKeyToggle }) { snprintf(b, sizeof b, "%d;", GetI(k, 0)); s += b; }
    for (int h = 0; h < HAND_COUNT; ++h) {
        const CtlSettings& k = kCtl[h];
        for (int i = 0; i < 3; ++i) { snprintf(b, sizeof b, "%.3f;", GetF(k.mpuDz[i])); s += b; }
        for (int a = 0; a < C_COUNT; ++a) {
            snprintf(b, sizeof b, "%d;%d;%d;%.3f;%.3f;", GetI(k.axSrc[a]), (int)GetB(k.axInv[a]), (int)GetB(k.axEn[a]), GetF(k.axGain[a]), GetF(k.axSmooth[a])); s += b;
        }
        for (int i = 0; i < 2; ++i) { snprintf(b, sizeof b, "%.3f;%d;", GetF(k.stickDz[i]), (int)GetB(k.stickInv[i])); s += b; }
        snprintf(b, sizeof b, "%.3f;%.3f;%.3f;%d;%.3f;%.3f;%.3f;%.3f;", GetF(k.stickGamma), GetF(k.stickEdge), GetF(k.stickSens), (int)GetB(k.stickSwap),
                 GetF(k.trigRamp), GetF(k.gripRamp), GetF(k.clickThr), GetF(k.yawDrift)); s += b;
    }
    s += GetS(kHmdMonitorKey) + ";";
    for (int h = 0; h < HAND_COUNT; ++h) for (int pb = 0; pb < P_COUNT; ++pb) { snprintf(b, sizeof b, "%d;", GetI(kBind[h][pb])); s += b; }
    snprintf(b, sizeof b, "%d;%d;%s;%s;", GetI(kAssign), (int)GetB(kSwap), GetS(kCtlIp[0]).c_str(), GetS(kCtlIp[1]).c_str()); s += b;
    s += GetB(kArmEnabled) ? "A" : "a";
    snprintf(b, sizeof b, "%d;%d;%d;%d;%d;%d;%d;", GetI(kSrcMode), GetI(kProfile), GetI(kStickC[0][0]), GetI(kStickC[0][1]),
             0, (int)GetB(kComboRecenter), (int)GetB(kAutoCenter));
    s += b;
    snprintf(b, sizeof b, "%d;%d;", GetI(kStickC[1][0]), GetI(kStickC[1][1])); s += b;
    return s;
}

// ------------------------------------------------------------------ nomes de tecla
static std::wstring KeyName(int vk) {
    wchar_t buf[64];
    switch (vk) {
    case 0: return L"—";
    case VK_UP: return L"Seta ↑";     case VK_DOWN: return L"Seta ↓";
    case VK_LEFT: return L"Seta ←";   case VK_RIGHT: return L"Seta →";
    case VK_HOME: return L"Home";     case VK_END: return L"End";
    case VK_PRIOR: return L"Page Up"; case VK_NEXT: return L"Page Down";
    case VK_INSERT: return L"Insert"; case VK_DELETE: return L"Delete";
    case VK_SHIFT: return L"Shift";   case VK_CONTROL: return L"Ctrl";
    case VK_MENU: return L"Alt";      case VK_SCROLL: return L"Scroll Lock";
    case VK_SPACE: return L"Espaço";  case VK_RETURN: return L"Enter";
    case VK_TAB: return L"Tab";       case VK_BACK: return L"Backspace";
    case VK_ADD: return L"Num +";     case VK_SUBTRACT: return L"Num -";
    case VK_MULTIPLY: return L"Num *"; case VK_DIVIDE: return L"Num /";
    case VK_DECIMAL: return L"Num .";
    }
    if (vk >= VK_NUMPAD0 && vk <= VK_NUMPAD9) { swprintf(buf, 64, L"Num %d", vk - VK_NUMPAD0); return buf; }
    if (vk >= VK_F1 && vk <= VK_F12)          { swprintf(buf, 64, L"F%d", vk - VK_F1 + 1); return buf; }
    if ((vk >= 'A' && vk <= 'Z') || (vk >= '0' && vk <= '9')) { buf[0] = (wchar_t)vk; buf[1] = 0; return buf; }
    UINT sc = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
    if (sc && GetKeyNameTextW((LONG)(sc << 16), buf, 64) > 0) return buf;
    swprintf(buf, 64, L"VK 0x%02X", vk);
    return buf;
}

// ------------------------------------------------------------------ desenho (GDI)
static HDC     g_dc = nullptr;
static HBITMAP g_bmp = nullptr;
static uint32_t* g_px = nullptr;
static HFONT   g_fTitle, g_fText, g_fSmall;
static std::vector<uint8_t> g_rgba;

// Paleta StormVR: roxo vivo + preto + branco (as cores de aviso/erro continuam amarelo/vermelho para leitura)
static const COLORREF cBg = RGB(9, 5, 16), cPanel = RGB(22, 13, 38), cPanel2 = RGB(38, 22, 66),
                      cAccent = RGB(157, 78, 255), cText = RGB(247, 243, 255), cMuted = RGB(166, 150, 198),
                      cOn = RGB(196, 148, 255), cWarn = RGB(255, 190, 60), cBad = RGB(240, 90, 110);

static void InitGfx() {
    HDC screen = GetDC(nullptr);
    g_dc = CreateCompatibleDC(screen);
    BITMAPINFO bi = {};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = W; bi.bmiHeader.biHeight = -H;   // top-down
    bi.bmiHeader.biPlanes = 1; bi.bmiHeader.biBitCount = 32; bi.bmiHeader.biCompression = BI_RGB;
    g_bmp = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, reinterpret_cast<void**>(&g_px), nullptr, 0);
    SelectObject(g_dc, g_bmp);
    ReleaseDC(nullptr, screen);
    g_fTitle = CreateFontW(34, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    g_fText  = CreateFontW(22, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    g_fSmall = CreateFontW(18, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    SetBkMode(g_dc, TRANSPARENT);
    g_rgba.resize((size_t)W * H * 4);
}

static void Rect(const RECT& r, COLORREF c, int radius = 0) {
    HBRUSH b = CreateSolidBrush(c);
    HPEN p = CreatePen(PS_NULL, 0, 0);
    HGDIOBJ ob = SelectObject(g_dc, b), op = SelectObject(g_dc, p);
    if (radius > 0) RoundRect(g_dc, r.left, r.top, r.right, r.bottom, radius, radius);
    else Rectangle(g_dc, r.left, r.top, r.right + 1, r.bottom + 1);
    SelectObject(g_dc, ob); SelectObject(g_dc, op);
    DeleteObject(b); DeleteObject(p);
}
static void Text(const std::wstring& s, RECT r, COLORREF c, HFONT f, UINT fmt = DT_LEFT | DT_VCENTER | DT_SINGLELINE) {
    SelectObject(g_dc, f); SetTextColor(g_dc, c);
    DrawTextW(g_dc, s.c_str(), -1, &r, fmt);
}

static RECT RC(int x, int y, int w, int h) { return RECT{ x, y, x + w, y + h }; }
static float Clamp01(float f) { return f < 0 ? 0 : (f > 1 ? 1 : f); }

static std::wstring Ang(float v) { wchar_t b[32]; swprintf(b, 32, L"%+.1f°", v); return b; }
static std::wstring Num(float v, int dec = 2) { wchar_t b[32]; swprintf(b, 32, L"%+.*f", dec, v); return b; }

// ------------------------------------------------------------------ widgets
enum WType { W_TAB, W_CHECK, W_SLIDER, W_KEY, W_BUTTON, W_CYCLE };
struct Widget {
    WType type; RECT r; std::wstring label;
    std::string key;                 // chave de config (check/key global)
    const FloatSetting* fs = nullptr;
    int hand = 0, action = -1;       // para W_KEY
    int id = 0;                      // tab / botao
    const IntSetting* is = nullptr;  // W_CYCLE
    const std::vector<std::wstring>* opts = nullptr;
    bool defB = true;                // valor padrao do W_CHECK
    int frac = 52;                   // % da largura reservada ao rotulo (W_CYCLE)
    bool sel = false;                // botao destacado (monitor escolhido / captura ativa)
    int shape = 0;                   // 0 = botao comum, 1 = entrada do desenho do controle
    bool lit = false;                // entrada acionada agora (tempo real)
    bool mapped = false;             // entrada com algum botao fisico associado
};
enum { BTN_RESET_POS = 1, BTN_RESET_ALL, BTN_RECENTER, BTN_ZERO, BTN_GYRO, BTN_STICKC, BTN_RECENTER_HEAD, BTN_MON_REFRESH,
       BTN_RESET_AXES, BTN_RESET_STICK, BTN_RESET_MAP, BTN_RESET_PROFILE, BTN_RESET_ARM, BTN_RESTART_UDP,
       BTN_SAVE1, BTN_SAVE2, BTN_SAVE3, BTN_LOAD1, BTN_LOAD2, BTN_LOAD3, BTN_IDENTIFY, BTN_RESTORE_PRIMARY,
       BTN_BINDHAND, BTN_VIEWHAND, BTN_SWAP, BTN_FLIP_R, BTN_FLIP_L, BTN_PIN_R, BTN_PIN_L, BTN_RESET_ASSIGN, BTN_COPY_AX, BTN_COPY_STICK, BTN_CHAPERONE, BTN_RESTARTVR, BTN_RESET_COORD, BTN_RECENTER_R, BTN_RECENTER_L, BTN_MON0 = 100, BTN_CAP0 = 200 };
static constexpr int BTN_ROW0 = 300, BTN_CAT0 = 320, BTN_COPYMAP = 330, BTN_FN0 = 340;   // aba Mapa: linhas, categorias, copiar, funcoes (+ saida)
static int g_pickRow = 0, g_pickCat = 0;
enum { PG_GERAL = 0, PG_ESP, PG_HEAD, PG_EIXOS, PG_ANALOG, PG_BTN, PG_PERFIL, PG_ARM, PG_DIAG, PG_TECLAS, PG_COORD, PG_COUNT };

static const std::vector<std::wstring> kOptSrc  = { L"Auto (controle se houver, senão teclado)", L"Só controles (ESP32/celular)", L"Só teclado" };
static const std::vector<std::wstring> kOptAssign = { L"Automático (mão de cada controle)", L"Ordem de conexão (1º = direita)", L"IP fixo por mão" };
static const std::vector<std::wstring> kOptRes  = { L"Resolução atual", L"1280×720", L"1920×1080", L"2560×1440", L"3840×2160" };
static const std::vector<std::wstring> kOptHz   = { L"60 Hz", L"72 Hz", L"90 Hz", L"120 Hz" };
static const std::vector<std::wstring> kOptArmMode = { L"Completo (ombro + cotovelo)", L"Simples (esfera / cabo)" };
static const wchar_t* kBuildTag = L"etapa 33";   // aparece no titulo: se nao mudar, o stormvr_config.exe instalado e antigo
static const std::vector<std::wstring> kOptTrack = { L"3DoF (ignorar posição)", L"Automático (segue o app)" };
static const std::vector<std::wstring> kOptMpu  = { L"MPU Roll (X)", L"MPU Pitch (Y)", L"MPU Yaw (Z)", L"Nenhum" };
static const std::vector<std::wstring> kOptOut  = { L"Nenhum (desativado)", L"Direito: Trigger", L"Direito: Grip", L"Direito: A", L"Direito: B",
    L"Direito: Sistema", L"Direito: Clique stick", L"Esquerdo: X", L"Esquerdo: Y", L"Esquerdo: Menu", L"Esquerdo: Trigger",
    L"Esquerdo: Grip", L"Esquerdo: Clique stick",
    L"Ação: Recentrar controle", L"Ação: Recentrar cabeça", L"Ação: Recentrar tudo", L"Ação: Zerar yaw/pitch/roll",
    L"Ação: Calibrar giroscópio", L"Ação: Ligar/desligar Arm Model", L"Ação: Embreagem (segurar)",
    L"Index direito: fechar Polegar", L"Index direito: fechar Indicador", L"Index direito: fechar Médio", L"Index direito: fechar Anelar", L"Index direito: fechar Mindinho",
    L"Index esquerdo: fechar Polegar", L"Index esquerdo: fechar Indicador", L"Index esquerdo: fechar Médio", L"Index esquerdo: fechar Anelar", L"Index esquerdo: fechar Mindinho",
    L"Trigger", L"Grip", L"Bumper", L"Clique do stick", L"A", L"B", L"X", L"Y", L"Menu", L"View", L"D-pad ↑", L"D-pad →", L"D-pad ↓", L"D-pad ←", L"Sistema" };
static const std::vector<std::wstring> kOptChannel = { L"Auto", L"SteamVR (Wi-Fi)", L"Android (Bluetooth)" };
static const std::vector<std::wstring> kOptHand = { L"Direita", L"Esquerda", L"Pelo controle" };
static const std::vector<std::wstring> kOptProfile = { L"Oculus Touch", L"HTC Vive", L"Valve Index (exp.)", L"Genérico StormVR (exp.)", L"Steam Frame Controller (exp.)" };
static const wchar_t* kPhysName[P_COUNT] = { L"Grip", L"Trigger", L"A", L"B", L"X", L"Y", L"Start / System", L"L3 (clique)", L"R2 (D4)" };
static const wchar_t* kPhysShort[P_COUNT] = { L"Grip", L"Trig", L"A", L"B", L"X", L"Y", L"Start", L"L3", L"R2" };
static std::vector<MonitorInfo> g_mons;

static int g_page = PG_GERAL;
static int g_rebind = -1;            // indice do widget em rebind
static int g_drag = -1;
static bool g_mouseDown = false;
static bool g_dirty = true;
static int g_bindHand = HAND_RIGHT;   // mao cujo mapeamento de botoes esta sendo editado
static int g_viewHand = HAND_RIGHT;   // mao cujos AJUSTES e telemetria aparecem (Eixos/Analogico/Perfil/Arm Model/Diagnostico)
static const CtlSettings& CS() { return kCtl[g_viewHand]; }
static int g_capture = 0;            // entrada (Out) esperando um botao fisico; 0 = nao
static uint32_t g_prevBtn = 0;
static std::wstring g_msg; static ULONGLONG g_msgUntil = 0;
static std::vector<Widget> g_w;
// Mostra por ~3 s um numero grande no centro de cada monitor (o mesmo numero do "DISPLAYn" da lista)
static LRESULT CALLBACK IdWndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_PAINT) {
        PAINTSTRUCT ps; HDC dc = BeginPaint(h, &ps);
        RECT r; GetClientRect(h, &r);
        HBRUSH b = CreateSolidBrush(RGB(88, 36, 170)); FillRect(dc, &r, b); DeleteObject(b);
        char t[16] = {}; GetWindowTextA(h, t, 16);
        HFONT f = CreateFontW(150, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
        HGDIOBJ of = SelectObject(dc, f); SetBkMode(dc, TRANSPARENT); SetTextColor(dc, RGB(255, 255, 255));
        DrawTextA(dc, t, -1, &r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        SelectObject(dc, of); DeleteObject(f); EndPaint(h, &ps);
        return 0;
    }
    return DefWindowProcA(h, m, w, l);
}
static void IdentifyMonitors() {
    std::thread([] {
        WNDCLASSA wc = {}; wc.lpfnWndProc = IdWndProc; wc.hInstance = GetModuleHandleA(nullptr); wc.lpszClassName = "StormVRIdent";
        RegisterClassA(&wc);
        std::vector<HWND> ws;
        for (const MonitorInfo& m : ListMonitors()) {
            std::string num; for (char c : m.dev) if (c >= '0' && c <= '9') num += c;      // "\\.\DISPLAY2" -> "2"
            ws.push_back(CreateWindowExA(WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, "StormVRIdent", num.c_str(), WS_POPUP | WS_VISIBLE,
                                         m.x + m.w / 2 - 150, m.y + m.h / 2 - 110, 300, 220, nullptr, nullptr, wc.hInstance, nullptr));
        }
        const ULONGLONG end = GetTickCount64() + 3000;
        MSG msg;
        while (GetTickCount64() < end) {
            while (PeekMessageA(&msg, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageA(&msg); }
            Sleep(20);
        }
        for (HWND w : ws) if (w) DestroyWindow(w);
        UnregisterClassA("StormVRIdent", wc.hInstance);
    }).detach();
}
static void Msg(const std::wstring& m) { g_msg = m; g_msgUntil = GetTickCount64() + 5000; g_dirty = true; }

// Mapeamento: troca com a regra obrigatoria (sempre um botao fisico, de qualquer controle, no Trigger direito)
// ---- Mapa: tabela do perfil ATIVO NA TELA (Steam Frame tem a sua propria), listas de funcoes por perfil/mao
static bool FrameSel() { return GetI(kProfile) == PF_FRAME; }
static bool IndexSel() { return GetI(kProfile) == PF_INDEX; }
static const IntSetting& BindOf(int hand, int pb) { return FrameSel() ? kBindFrame[hand][pb] : kBind[hand][pb]; }
static int Mirror(int o) {   // equivalente da funcao na OUTRA mao (para copiar o mapeamento)
    static const int pr[][2] = { { O_R_TRIGGER, O_L_TRIGGER }, { O_R_GRIP, O_L_GRIP }, { O_R_CLICK, O_L_CLICK }, { O_R_A, O_L_X }, { O_R_B, O_L_Y },
        { O_R_SYSTEM, O_L_MENU }, { O_FM_A, O_FM_DDOWN }, { O_FM_B, O_FM_DRIGHT }, { O_FM_X, O_FM_DLEFT }, { O_FM_Y, O_FM_DUP }, { O_FM_MENU, O_FM_VIEW },
        { O_FR_THUMB, O_FL_THUMB }, { O_FR_INDEX, O_FL_INDEX }, { O_FR_MIDDLE, O_FL_MIDDLE }, { O_FR_RING, O_FL_RING }, { O_FR_PINKY, O_FL_PINKY } };
    for (auto& q : pr) { if (q[0] == o) return q[1]; if (q[1] == o) return q[0]; }
    return o;
}
// funcoes oferecidas na categoria (0 = Controle, 1 = Dedos [so Index], 2 = Acoes) para a mao editada
static void FnList(int cat, int hand, std::vector<int>& v) {
    v.clear();
    if (cat == 0) {
        if (FrameSel()) {
            const int c[] = { O_FM_TRIGGER, O_FM_GRIP, O_FM_BUMPER, O_FM_STICK, O_FM_SYSTEM };
            for (int o : c) v.push_back(o);
            if (hand == HAND_RIGHT) { for (int o : { (int)O_FM_A, (int)O_FM_B, (int)O_FM_X, (int)O_FM_Y, (int)O_FM_MENU }) v.push_back(o); }
            else { for (int o : { (int)O_FM_DUP, (int)O_FM_DRIGHT, (int)O_FM_DDOWN, (int)O_FM_DLEFT, (int)O_FM_VIEW }) v.push_back(o); }
        } else for (int o = O_R_TRIGGER; o <= O_L_CLICK; ++o) v.push_back(o);
    } else if (cat == 1) { for (int f = 0; f < 5; ++f) v.push_back(kFingerOut[hand][f]); }
    else { for (int o = O_ACT_RECENTER_CTL; o <= O_ACT_CLUTCH; ++o) v.push_back(o); }
}

// Reinicia o SteamVR: um processo separado encerra vrmonitor/vrserver/vrcompositor e reabre pelo Steam (app 250820)
static void RestartSteamVR() {
    char cmd[] = "cmd.exe /c \"taskkill /F /IM vrmonitor.exe >nul 2>&1 & taskkill /F /IM vrserver.exe >nul 2>&1 & taskkill /F /IM vrcompositor.exe >nul 2>&1 & "
                 "taskkill /F /IM vrdashboard.exe >nul 2>&1 & timeout /t 4 /nobreak >nul & start \"\" steam://rungameid/250820\"";
    STARTUPINFOA si = {}; si.cb = sizeof si; PROCESS_INFORMATION pi = {};
    BOOL ok = CreateProcessA(nullptr, cmd, nullptr, nullptr, FALSE, CREATE_NO_WINDOW | CREATE_BREAKAWAY_FROM_JOB, nullptr, nullptr, &si, &pi);
    if (!ok) ok = CreateProcessA(nullptr, cmd, nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
    if (ok) { CloseHandle(pi.hProcess); CloseHandle(pi.hThread); Msg(L"Reiniciando o SteamVR..."); } else Msg(L"Não foi possível reiniciar o SteamVR.");
}
// Reseta piso/espaco de jogo do SteamVR (origem no piso, area 2x2 m). Chamado pelo botao da dashboard e pelo painel WEB (CMD_RESET_CHAPERONE).
static void ResetChaperone() {
    vr::IVRChaperoneSetup* cs = vr::VRChaperoneSetup();
    if (!cs) { Msg(L"SteamVR indisponível (Chaperone)."); return; }
    vr::HmdMatrix34_t id = { { { 1, 0, 0, 0 }, { 0, 1, 0, 0 }, { 0, 0, 1, 0 } } };
    cs->RevertWorkingCopy();
    cs->SetWorkingStandingZeroPoseToRawTrackingPose(&id);      // origem = piso (y = 0)
    cs->SetWorkingSeatedZeroPoseToRawTrackingPose(&id);
    cs->SetWorkingPlayAreaSize(2.0f, 2.0f);
    const vr::HmdVector2_t per[4] = { { -1.f, -1.f }, { -1.f, 1.f }, { 1.f, 1.f }, { 1.f, -1.f } };
    cs->SetWorkingPerimeter(per, 4);
    const bool ok = cs->CommitWorkingCopy(vr::EChaperoneConfigFile_Live);
    Msg(ok ? L"Piso/espaço de jogo resetados (origem no piso, área 2×2 m). Reabra o jogo." : L"Não foi possível gravar o espaço de jogo (veja o SteamVR).");
}
static bool SetBindAny(int hand, int pb, int out);
static bool AssignBind(int hand, int phys, int out) {
    int m[HAND_COUNT][P_COUNT];
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) m[h][i] = GetI(kBind[h][i]);
    m[hand][phys] = out;
    if (!BindHasTrigger(m)) {
        Msg(L"Mantenha ao menos um botão físico no \"Direito: Trigger\" (seleção principal). Associe outro botão ao Trigger antes.");
        return false;
    }
    SetI(kBind[hand][phys].key, out);
    return true;
}
static bool SetBindAny(int hand, int pb, int out) {
    if (FrameSel()) { SetI(kBindFrame[hand][pb].key, out); return true; }     // Frame: sem a regra do Trigger direito
    return AssignBind(hand, pb, out);
}
static bool OutMapped(int out) {
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) if (GetI(kBind[h][i]) == out) return true;
    return false;
}

// Posicoes dos botoes no desenho do controle (relativas ao canto do corpo)
struct HotDef { int out; int x, y, w, h; const wchar_t* name; };
static const HotDef kHotRight[6] = {
    { O_R_TRIGGER, 35, 92, 180, 30, L"Trigger" }, { O_R_GRIP, 6, 136, 50, 112, L"Grip" }, { O_R_CLICK, 66, 140, 90, 90, L"Stick" },
    { O_R_A, 168, 184, 48, 48, L"A" }, { O_R_B, 168, 132, 48, 48, L"B" }, { O_R_SYSTEM, 80, 250, 90, 32, L"Sistema" } };
static const HotDef kHotLeft[6] = {
    { O_L_TRIGGER, 35, 92, 180, 30, L"Trigger" }, { O_L_GRIP, 6, 136, 50, 112, L"Grip" }, { O_L_CLICK, 66, 140, 90, 90, L"Stick" },
    { O_L_X, 168, 184, 48, 48, L"X" }, { O_L_Y, 168, 132, 48, 48, L"Y" }, { O_L_MENU, 80, 250, 90, 32, L"Menu" } };
static constexpr int kBodyX[2] = { 590, 870 }, kBodyY = 140;       // [0] = direito, [1] = esquerdo


// ---- Rolagem e anti-sobreposicao: o conteudo de cada pagina desce kShift px (abaixo das abas) e pode rolar (roda do mouse / barra lateral)
static constexpr int kShift = 14, kTopY = 116;
static int g_scroll = 0, g_maxScroll = 0, g_sbDrag = -1;
static void LogOverlap(int page, const std::wstring& a, const std::wstring& b) {
    static unsigned seen = 0;
    if (seen & (1u << page)) return; seen |= (1u << page);
    char path[MAX_PATH] = {};
    if (GetEnvironmentVariableA("USERPROFILE", path, MAX_PATH)) {
        strcat_s(path, "\\Documents\\StormVR\\layout_sobreposicao.log");
        FILE* f = nullptr; fopen_s(&f, path, "a");
        if (f) { fprintf(f, "pagina %d: '%ls' e '%ls' se sobrepunham (empurrado para baixo)\n", page, a.c_str(), b.c_str()); fclose(f); }
    }
}
// Empurra para baixo qualquer controle que cobriria outro (nada fica escondido embaixo de outra opcao) e calcula o limite da rolagem.
static void ResolveOverlaps() {
    std::vector<size_t> ord;
    for (size_t i = 0; i < g_w.size(); ++i) if (g_w[i].type != W_TAB) ord.push_back(i);
    std::stable_sort(ord.begin(), ord.end(), [](size_t a, size_t b) {
        if (g_w[a].r.top != g_w[b].r.top) return g_w[a].r.top < g_w[b].r.top; return g_w[a].r.left < g_w[b].r.left; });
    std::vector<size_t> done; int bottom = 600;
    for (size_t i : ord) {
        Widget& w = g_w[i];
        for (int pass = 0; pass < 40; ++pass) {
            bool moved = false;
            for (size_t j : done) {
                const RECT& e = g_w[j].r;
                const bool hx = w.r.left < e.right - 4 && w.r.right > e.left + 4;
                const bool vy = w.r.top < e.bottom - 4 && w.r.bottom > e.top + 4;
                if (hx && vy) {
                    LogOverlap(g_page, w.label, g_w[j].label);
                    const int dy = e.bottom + 8 - w.r.top; w.r.top += dy; w.r.bottom += dy; moved = true;
                }
            }
            if (!moved) break;
        }
        done.push_back(i);
        if (w.r.bottom > bottom) bottom = w.r.bottom;
    }
    if (g_page == PG_PERFIL) bottom = std::max(bottom, 840);          // painel de texto do perfil (desenhado, nao e controle)
    g_maxScroll = std::max(0, bottom + kShift + 24 - H);
    if (g_scroll > g_maxScroll) g_scroll = g_maxScroll;
    if (g_scroll < 0) g_scroll = 0;
}

static void Layout() {
    FetchLive();
    g_w.clear();
    const wchar_t* tabs[PG_COUNT] = { L"Geral", L"Controles", L"Headset", L"Eixos", L"Analógico", L"Botões", L"Perfis", L"Arm Model", L"Diagnóstico", L"Teclas", L"Coordenadas" };
    for (int i = 0; i < PG_COUNT; ++i)
        g_w.push_back({ W_TAB, RC(30 + i * 102, 72, 100, 38), tabs[i], "", nullptr, 0, -1, i });

    auto check = [&](int x, int y, int w, const std::wstring& label, const char* key, bool def) {
        Widget k{ W_CHECK, RC(x, y, w, 36), label, key }; k.defB = def; g_w.push_back(k);
    };
    auto slider = [&](int x, int yy, const std::wstring& label, const FloatSetting* fs, int width = 500) {
        Widget w{ W_SLIDER, RC(x, yy, width, 58), label }; w.fs = fs; g_w.push_back(w);
    };
    auto cycle = [&](int x, int y, int w, const std::wstring& label, const IntSetting* is, const std::vector<std::wstring>* o, int frac) {
        Widget c{ W_CYCLE, RC(x, y, w, 40), label }; c.is = is; c.opts = o; c.frac = frac; g_w.push_back(c);
    };
    auto button = [&](int x, int y, int w, int h, const std::wstring& label, int id) {
        Widget b{ W_BUTTON, RC(x, y, w, h), label }; b.id = id; g_w.push_back(b);
    };

    if (g_page == PG_GERAL) {
        int y = 135;
        auto chk = [&](const std::wstring& label, const char* key) { check(30, y, 520, label, key, true); y += 42; };
        check(30, y, 520, L"MODO TV: monitor como headset, parado e sem estéreo (reinicie o SteamVR)", kHmdMonitor.key, kHmdMonitor.def); y += 42;
        chk(L"Zerar os eixos dos controles ao conectar (corrige \"de cabeça pra baixo\")", kAutoZeroConn.key);
        chk(L"Teclado ativo (atalho: " + KeyName(GetI(kKeyToggle, kDefToggle)) + L")", kKeysEnabled);
        chk(L"Controle direito conectado", kEnabled[HAND_RIGHT]);
        chk(L"Controle esquerdo conectado", kEnabled[HAND_LEFT]);
        button(590, 135, 540, 40, L"Recentralizar a cabeça", BTN_RECENTER_HEAD);
        button(590, 181, 264, 40, L"Recentralizar controle esquerdo", BTN_RECENTER_L);
        button(866, 181, 264, 40, L"Recentralizar controle direito", BTN_RECENTER_R);
        check(590, 236, 540, L"Girar a cabeça 180° (se o mundo está de cabeça pra baixo)", kHeadFlip.key, kHeadFlip.def);
        button(590, 284, 540, 40, L"Resetar piso / espaço de jogo do SteamVR", BTN_CHAPERONE);
        button(590, 384, 540, 40, L"Reiniciar o SteamVR (clique duas vezes)", BTN_RESTARTVR);
        slider(590, 436, L"Previsão de movimento dos controles (ms)", &kPredictMs, 540);
        check(590, 496, 540, L"Controles por quaternion (corrige apontar p/ cima e p/ baixo)", kCtlQuat.key, kCtlQuat.def);
        slider(590, 540, L"Suavização dos controles (%)", &kCtlSmooth, 540);
        chk(L"Controles seguem o deslocamento do headset (rotação vem do IMU)", kFollowHmd);
        chk(L"Girar também com a direção do headset (opcional)", kFollowHmdRot);
        chk(L"Abrir o painel WEB junto com o SteamVR (celular: http://IP-do-PC:8787)", kWebAutostart);
        y += 8;
        slider(30, y, L"Velocidade de movimento (m/s)", &kMoveSpeed);  y += 62;
        slider(30, y, L"Velocidade de rotação (°/s)", &kRotSpeed);     y += 62;
        slider(30, y, L"Altura no modo fixo (m)", &kFixedHeight);      y += 62;
        Text(L"Rampas, eixos, analógico e limiar de clique são de CADA controle: abas Eixos, Analógico e Perfil.", RC(30, y, 520, 44), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);

        const wchar_t* axes[3] = { L"X: esquerda ↔ direita", L"Y: altura", L"Z: profundidade (− = à frente)" };
        int y2 = 135;
        for (int h = 0; h < HAND_COUNT; ++h)
            for (int i = 0; i < 3; ++i) {
                std::wstring lbl = std::wstring(h == HAND_RIGHT ? L"Direito — " : L"Esquerdo — ") + axes[i];
                slider(600, y2, lbl, &kOffset[h][i]); y2 += 62;
            }
        button(600, 510, 245, 36, L"Resetar posições", BTN_RESET_POS);
        button(860, 510, 245, 36, L"Restaurar tudo", BTN_RESET_ALL);
        for (int i = 0; i < 3; ++i) {
            wchar_t b[32]; swprintf(b, 32, L"Salvar no slot %d", i + 1);
            button(600 + i * 168, 570, 160, 32, b, BTN_SAVE1 + i);
            swprintf(b, 32, L"Carregar slot %d", i + 1);
            button(600 + i * 168, 608, 160, 32, b, BTN_LOAD1 + i);
        }
    }
    else if (g_page == PG_ESP) {
        cycle(30, 326, 540, L"Quem fica em cada mão:", &kAssign, &kOptAssign, 40);
        cycle(30, 370, 540, L"Controle usa:", &kSrcMode, &kOptSrc, 30);
        check(30, 414, 540, L"Trocar mãos (direita ↔ esquerda)", kSwap.key, kSwap.def);
        check(30, 452, 540, L"Recentrar yaw ao conectar", kAutoCenter.key, kAutoCenter.def);
        check(30, 490, 540, L"Combo L3 + Start recentra yaw (controle e cabeça)", kComboRecenter.key, kComboRecenter.def);
        check(30, 528, 540, L"Esconder o controle se ele cair (modo Só controles)", kHideLost.key, kHideLost.def);
        slider(30, 570, L"Sem pacotes por quanto tempo = desconectado (s)", &kEspTimeout, 540);
        button(600, 118, 250, 40, L"Recentrar yaw", BTN_RECENTER);
        button(870, 118, 250, 40, L"Zerar yaw/pitch/roll", BTN_ZERO);
        button(600, 164, 250, 40, L"Calibrar giroscópios", BTN_GYRO);
        button(600, 214, 250, 38, L"Virar mão do controle DIREITO", BTN_FLIP_R);
        button(870, 214, 250, 38, L"Virar mão do controle ESQUERDO", BTN_FLIP_L);
        button(600, 258, 250, 38, L"Fixar IP atual na DIREITA", BTN_PIN_R);
        button(870, 258, 250, 38, L"Fixar IP atual na ESQUERDA", BTN_PIN_L);
        for (int h = 0; h < HAND_COUNT; ++h) {              // mãos rastreadas pela câmera do celular (Sala VR, modo PC)
            const int x = 600 + h * 270;
            const wchar_t* side = h == HAND_RIGHT ? L"dir." : L"esq.";
            check(x, 458, 250, std::wstring(L"Posição da mão ") + side, kHandsPos[h].key, kHandsPos[h].def);
            check(x, 496, 250, std::wstring(L"Pinça = trigger ") + side, kHandsPinch[h].key, kHandsPinch[h].def);
            check(x, 534, 250, std::wstring(L"Direção da mão ") + side, kHandsAim[h].key, kHandsAim[h].def);
        }
        cycle(600, 610, 520, L"Canal do ESP32:", &kEspChannel, &kOptChannel, 36);
        slider(600, 664, L"Suavização da posição das mãos rastreadas (%)", &kHandsSmooth, 520);
    }
    else if (g_page == PG_HEAD) {
        if (g_mons.empty()) g_mons = ListMonitors();
        const std::string cur = GetS(kHmdMonitorKey);
        const int n = (int)std::min<size_t>(g_mons.size(), 4);
        for (int i = 0; i < n; ++i) {
            const MonitorInfo& m = g_mons[i];
            std::string dev = m.dev; size_t bs = dev.find_last_of('\\'); if (bs != std::string::npos) dev = dev.substr(bs + 1);
            std::string nm = m.name.size() > 18 ? m.name.substr(0, 18) : m.name;
            wchar_t b[200];
            swprintf(b, 200, L"%ls · %ls · %d×%d%ls", WS(dev).c_str(), WS(nm).c_str(), m.w, m.h, m.primary ? L" · principal" : L"");
            button(30, 138 + i * 42, 540, 38, b, BTN_MON0 + i);
            g_w.back().sel = (m.dev == cur);
        }
        button(30, 310, 170, 34, L"Atualizar", BTN_MON_REFRESH);
        button(206, 310, 170, 34, L"Identificar", BTN_IDENTIFY);
        button(382, 310, 188, 34, L"Restaurar principal", BTN_RESTORE_PRIMARY);
        check(30, 392, 540, L"Tornar principal ao abrir o SteamVR (restaura ao fechar)", kMakePrimary.key, kMakePrimary.def);
        cycle(30, 436, 540, L"Resolução do monitor:", &kHmdResMode, &kOptRes, 50);
        cycle(30, 480, 540, L"Taxa de atualização:", &kHmdHzMode, &kOptHz, 50);
        cycle(30, 524, 540, L"Rastreamento:", &kTrackMode, &kOptTrack, 38);
        slider(30, 568, L"Altura do usuário (m)", &kUserHeight, 540);
        slider(30, 630, L"Suavização da cabeça (%) — tira o tremor", &kHeadSmooth, 540);
        slider(590, 630, L"Previsão da cabeça (ms) — compensa a latência", &kHeadPredictMs, 530);
        slider(30, 692, L"Girar o mundo (°) — 180 = corrige o Beat Saber", &kWorldYaw, 540);
        slider(590, 692, L"Rolagem da imagem (°) — 180 = se o jogo estiver de cabeça pra baixo", &kHeadRoll, 530);
        slider(590, 568, L"Ajuste de altura (m): + sobe, − desce", &kHeadHeightOff, 530);
        button(600, 196, 240, 40, L"Recentrar cabeça", BTN_RECENTER_HEAD);
        check(850, 196, 270, L"Forçar tela cheia (-202)", kForceFs.key, kForceFs.def);
        check(880, 232, 330, L"Monitor comum como headset (reinicie)", kHmdMonitor.key, kHmdMonitor.def);
        check(880, 268, 330, L"Anti tela preta (headset parado)", kAntiIdle.key, kAntiIdle.def);
        slider(600, 264, L"FOV horizontal (°)", &kHmdFovH);
        slider(600, 326, L"FOV vertical (°)", &kHmdFovV);
        slider(600, 388, L"Distância entre os olhos / IPD (m)", &kHmdIpd, 250);
        slider(870, 388, L"Distância entre as 2 imagens (%)", &kHmdSep, 250);
        slider(600, 450, L"Distorção k1", &kHmdK1);
        slider(600, 512, L"Distorção k2", &kHmdK2);
        slider(600, 574, L"Escala de renderização", &kHmdRenderScale);
    }
    else if (g_page == PG_EIXOS) {
        const wchar_t* mpuN[3] = { L"Roll (X)", L"Pitch (Y)", L"Yaw (Z)" };
        const wchar_t* ctl[3] = { L"Yaw", L"Pitch", L"Roll" };
        const CtlSettings& C = CS();
        for (int a = 0; a < C_COUNT; ++a) {
            const int x = 30 + a * 380;
            cycle(x, 138, 350, std::wstring(ctl[a]) + L" ←", &C.axSrc[a], &kOptMpu, 30);
            check(x, 184, 170, L"Ativo", C.axEn[a].key, C.axEn[a].def);
            check(x + 180, 184, 170, L"Inverter", C.axInv[a].key, C.axInv[a].def);
            slider(x, 226, std::wstring(L"Ganho — ") + ctl[a], &C.axGain[a], 350);
            slider(x, 288, std::wstring(L"Suavização (ms) — ") + ctl[a], &C.axSmooth[a], 350);
            slider(x, 350, std::wstring(L"Zona morta MPU ") + mpuN[a], &C.mpuDz[a == 0 ? M_ROLL : (a == 1 ? M_PITCH : M_YAW)], 350);
        }
        slider(30, 532, L"Deriva do yaw (°/min)", &C.yawDrift, 350);
        button(420, 541, 330, 40, L"Copiar eixos do OUTRO controle para este", BTN_COPY_AX);
        button(30, 600, 250, 40, L"Recentrar yaw", BTN_RECENTER);
        button(290, 600, 250, 40, L"Zerar yaw/pitch/roll", BTN_ZERO);
        button(550, 600, 250, 40, L"Calibrar giroscópio", BTN_GYRO);
        button(850, 600, 250, 40, L"Restaurar eixos deste", BTN_RESET_AXES);
    }
    else if (g_page == PG_ANALOG) {
        const CtlSettings& C = CS();
        slider(30, 138, L"Zona morta do eixo X", &C.stickDz[0], 540);
        slider(30, 200, L"Zona morta do eixo Y", &C.stickDz[1], 540);
        slider(30, 262, L"Sensibilidade (ganho da saída)", &C.stickSens, 540);
        slider(30, 324, L"Curva de resposta (1 = linear)", &C.stickGamma, 540);
        slider(30, 386, L"Borda: fração do curso = 100%", &C.stickEdge, 540);
        check(30, 452, 540, L"Inverter X (VRx)", C.stickInv[0].key, C.stickInv[0].def);
        check(30, 494, 540, L"Inverter Y (VRy) — invertido no hardware (padrão)", C.stickInv[1].key, C.stickInv[1].def);
        check(30, 536, 540, L"Trocar eixos X ↔ Y", C.stickSwap.key, C.stickSwap.def);
        button(30, 584, 340, 38, L"Calibrar centro (solte o analógico)", BTN_STICKC);
        button(380, 584, 190, 38, L"Restaurar este", BTN_RESET_STICK);
        slider(600, 470, L"Rampa do Trigger (1/s, maior = mais rápido)", &C.trigRamp, 520);
        slider(600, 532, L"Rampa do Grip (1/s, maior = mais rápido)", &C.gripRamp, 520);
        button(600, 592, 520, 38, L"Copiar analógico e rampas do OUTRO controle", BTN_COPY_STICK);
    }
    else if (g_page == PG_BTN) {
        button(30, 104, 300, 30, g_bindHand == HAND_RIGHT ? L"Controle editado: mão DIREITA (trocar)" : L"Controle editado: mão ESQUERDA (trocar)", BTN_BINDHAND);
        button(340, 104, 230, 30, L"Copiar para o outro controle", BTN_COPYMAP);
        if (g_pickRow < 0 || g_pickRow >= P_COUNT) g_pickRow = 0;
        for (int pb = 0; pb < P_COUNT; ++pb) {
            const int o = GetI(BindOf(g_bindHand, pb));
            std::wstring lab = std::wstring(kPhysName[pb]) + L"   →   " + (o > 0 && o < (int)kOptOut.size() ? kOptOut[o] : std::wstring(L"—"));
            Widget w{ W_BUTTON, RC(30, 142 + pb * 41, 500, 37), lab }; w.id = BTN_ROW0 + pb; w.sel = (g_pickRow == pb); g_w.push_back(w);
        }
        button(30, 518, 270, 34, L"Restaurar mapeamento padrão", BTN_RESET_MAP);
        // seletor de funcao (so o que existe neste controle/perfil)
        const bool fingers = IndexSel();
        if (g_pickCat == 1 && !fingers) g_pickCat = 0;
        static const wchar_t* catName[3] = { L"Botões do controle", L"Dedos (Index)", L"Ações do driver" };
        int tx = 590;
        for (int c = 0; c < 3; ++c) {
            if (c == 1 && !fingers) continue;
            Widget w{ W_BUTTON, RC(tx, 142, 176, 34), catName[c] }; w.id = BTN_CAT0 + c; w.sel = (g_pickCat == c); g_w.push_back(w); tx += 182;
        }
        std::vector<int> fl; FnList(g_pickCat, g_bindHand, fl);
        { Widget w{ W_BUTTON, RC(590, 188, 176, 36), L"Nenhuma (desativar)" }; w.id = BTN_FN0; w.sel = (GetI(BindOf(g_bindHand, g_pickRow)) == 0); g_w.push_back(w); }
        for (size_t i = 0; i < fl.size(); ++i) {
            const int slot = (int)i + 1, col = slot % 3, row = slot / 3;
            Widget w{ W_BUTTON, RC(590 + col * 182, 188 + row * 42, 176, 36), kOptOut[fl[i]] };
            w.id = BTN_FN0 + fl[i]; w.sel = (GetI(BindOf(g_bindHand, g_pickRow)) == fl[i]); w.mapped = OutMapped(fl[i]); g_w.push_back(w);
        }
    }
    else if (g_page == PG_COORD) {
        static const wchar_t* ax[3] = { L"X (direita +)", L"Y (cima +)", L"Z (trás +)" };
        static const wchar_t* rx[3] = { L"Inclinar (pitch °)", L"Girar (yaw °)", L"Rolar (roll °)" };
        int y = 124;
        for (int i = 0; i < 3; ++i) { slider(30, y, std::wstring(L"CABEÇA posição ") + ax[i] + L" (m)", &kHeadOff[i], 540); y += 58; }
        for (int i = 0; i < 3; ++i) { slider(30, y, std::wstring(L"CABEÇA rotação ") + rx[i], &kHeadRot[i], 540); y += 58; }
        for (int h = 0; h < 2; ++h) for (int i = 0; i < 3; ++i) {
            slider(30, y, std::wstring(h == 0 ? L"CONTROLE DIREITO " : L"CONTROLE ESQUERDO ") + ax[i] + L" (m)", &kCtlOff[h][i], 540); y += 58; }
        button(30, y + 6, 540, 40, L"Zerar todas as coordenadas", BTN_RESET_COORD);
    }
    else if (g_page == PG_PERFIL) {
        cycle(30, 138, 540, L"Perfil do controle:", &kProfile, &kOptProfile, 40);
        slider(30, 184, L"Limiar de clique do Trigger/Grip (do controle mostrado)", &CS().clickThr, 540);
        {   // correcao de rotacao do modelo/ponteiro (por perfil; vale na hora, sem reiniciar)
            const int pf = GetI(kProfile) % PF_COUNT;
            slider(30, 250, L"Rotação do controle: inclinar (pitch, °)", &kRotOff[pf][0], 540);
            slider(30, 312, L"Rotação do controle: girar (yaw, °)", &kRotOff[pf][1], 540);
            slider(30, 374, L"Rotação do controle: rolar (roll, °)", &kRotOff[pf][2], 540);
        }
        button(30, 450, 250, 36, L"Restaurar (Oculus)", BTN_RESET_PROFILE);
    }
    else if (g_page == PG_ARM) {
        check(30, 132, 555, L"Ativar modelo de braço virtual (estimativa)", kArmEnabled.key, kArmEnabled.def);
        // (estes dois ficavam em x=600,y=132..250, ESCONDIDOS atras do grafico de previa: nao dava para trocar o modo)
        cycle(315, 436, 270, L"Modo:", &kArmMode, &kOptArmMode, 24);
        slider(315, 490, L"Simples: antebraço / raio da esfera (m)", &kArmSimpleLen, 270);
        slider(315, 552, L"Simples: cotovelo (centro da esfera) abaixo do ombro (m)", &kArmSimpleElbow, 270);
        slider(30, 180, L"Braço: ombro→cotovelo (m)", &kArmUpper, 270);
        slider(30, 242, L"Antebraço (m)", &kArmFore, 270);
        slider(30, 304, L"Ombro lateral (m)", &kArmShX, 270);
        slider(30, 366, L"Ombro abaixo (m)", &kArmShDrop, 270);
        slider(30, 428, L"Alcance geral", &kArmReach, 270);
        slider(315, 180, L"Mão ↓: cotovelo à frente", &kArmBendDown, 270);
        slider(315, 242, L"Mão ↑: cotovelo atrás", &kArmBendUp, 270);
        slider(315, 304, L"Alcance extra (lados)", &kArmSideReach, 270);
        slider(315, 366, L"Giro do tronco", &kArmTorso, 270);
        button(30, 500, 250, 38, L"Restaurar Arm Model", BTN_RESET_ARM);
        check(600, 470, 300, L"Prévia manual (sem ESP32)", kPrevManual.key, kPrevManual.def);
        slider(600, 512, L"Prévia: pitch (°)", &kPrevPitch, 260);
        slider(870, 512, L"Prévia: yaw (°)", &kPrevYaw, 260);
    }
    else if (g_page == PG_DIAG) {
        button(30, 580, 260, 40, L"Reiniciar conexão UDP", BTN_RESTART_UDP);
        button(300, 580, 260, 40, L"Calibrar giroscópio", BTN_GYRO);
        button(570, 580, 260, 40, L"Recentrar yaw", BTN_RECENTER);
    }
    else if (g_page == PG_TECLAS) {
        for (int h = 0; h < HAND_COUNT; ++h) {
            int x = 30 + h * 540;
            for (int a = 0; a < A_COUNT; ++a) {
                Widget w{ W_KEY, RC(x, 150 + a * 27, 510, 25), h == HAND_RIGHT ? kActions[a].labelRight : kActions[a].labelLeft };
                w.hand = h; w.action = a; g_w.push_back(w);
            }
        }
        struct G { const wchar_t* l; const char* k; } gl[3] = {
            { L"Segurar p/ MOVER (+ apontar)", kKeyMoveMod }, { L"Segurar p/ frente/trás (+ ↑↓)", kKeyDepthMod }, { L"Liga/desliga teclado", kKeyToggle } };
        for (int i = 0; i < 3; ++i) {
            Widget w{ W_KEY, RC(30 + i * 360, 150 + A_COUNT * 27 + 34, 340, 25), gl[i].l, gl[i].k };
            w.action = -2; g_w.push_back(w);
        }
    }
    if (g_page == PG_EIXOS || g_page == PG_ANALOG || g_page == PG_ARM || g_page == PG_DIAG || g_page == PG_PERFIL)
        button(880, 110, 240, 26, g_viewHand == HAND_RIGHT ? L"Mostrando: controle DIREITO" : L"Mostrando: controle ESQUERDO", BTN_VIEWHAND);
    ResolveOverlaps();
}

static int KeyOf(const Widget& w) {
    if (w.action >= 0) return GetI(KeySetting(w.hand, w.action).c_str(), DefaultKey(w.hand, w.action));
    int def = w.key == kKeyMoveMod ? kDefMoveMod : (w.key == kKeyDepthMod ? kDefDepthMod : kDefToggle);
    return GetI(w.key.c_str(), def);
}

static RECT TrackRect(const Widget& w) { return RC(w.r.left, w.r.top + 38, (w.r.right - w.r.left) - 90, 10); }

static void Pill(const RECT& r, const wchar_t* t, bool on) {
    Rect(r, on ? cOn : cPanel, 10);
    Text(t, r, on ? RGB(16, 6, 32) : cMuted, g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}
// Barra bipolar: v em [-range, range]; texto livre a direita
static void BarT(const RECT& r, const std::wstring& label, float v, float range, const std::wstring& val, int labelW = 70) {
    RECT lab = r; lab.right = lab.left + labelW;
    Text(label, lab, cText, g_fSmall);
    RECT tr = RC(r.left + labelW + 6, r.top + (r.bottom - r.top) / 2 - 6, (r.right - r.left) - labelW - 6 - 78, 12);
    Rect(tr, cPanel2, 6);
    int cx = (tr.left + tr.right) / 2;
    int px = cx + (int)(std::max(-1.f, std::min(1.f, v / range)) * (tr.right - tr.left) / 2);
    RECT fill = RC(std::min(cx, px), tr.top, std::abs(px - cx), 12);
    if (fill.right > fill.left) Rect(fill, cAccent);
    Rect(RC(cx - 1, tr.top - 3, 2, 18), cMuted);
    RECT vr = r; vr.left = r.right - 76;
    Text(val, vr, cText, g_fSmall, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
}
static void Bar(const RECT& r, const std::wstring& label, float v, float range) { BarT(r, label, v, range, Ang(v)); }
static void Line(int x1, int y1, int x2, int y2, COLORREF c, int w) {
    HPEN pn = CreatePen(PS_SOLID, w, c);
    HGDIOBJ op = SelectObject(g_dc, pn);
    MoveToEx(g_dc, x1, y1, nullptr); LineTo(g_dc, x2, y2);
    SelectObject(g_dc, op); DeleteObject(pn);
}

static std::wstring Uptime(uint32_t ms) {
    uint32_t s = ms / 1000; wchar_t b[32];
    swprintf(b, 32, L"%02u:%02u:%02u", s / 3600, (s / 60) % 60, s % 60);
    return b;
}

// Desenhos de fundo (antes dos widgets)
static void PaintBack() {
    return;   // o desenho antigo do controle (cliques por 'aprender') foi substituido pelo seletor da aba Mapa; ele pintava POR CIMA dele
    if (g_page != PG_BTN) return;
    const wchar_t* title[2] = { L"Controle direito", L"Controle esquerdo" };
    for (int side = 0; side < 2; ++side) {
        const int x = kBodyX[side], y = kBodyY;
        Text(title[side], RC(x, y - 30, 250, 24), cAccent, g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        Rect(RC(x + 70, y - 2, 110, 110), cPanel2, 110);        // anel
        Rect(RC(x + 90, y + 18, 70, 70), cBg, 70);
        Rect(RC(x, y + 52, 250, 250), cPanel, 44);              // corpo
    }
}

static void PaintExtras() {
    const int vh = g_page == PG_BTN ? g_bindHand : g_viewHand;
    const CtlLive& CV = g_L.ctl[vh];                      // telemetria do controle mostrado nesta pagina
    const bool lk = g_haveLive && CV.link != 0;
    if (g_page == PG_ESP) {
        for (int h = 0; h < HAND_COUNT; ++h) {
            const CtlLive& C = g_L.ctl[h];
            const int y0 = 112 + h * 106;
            Rect(RC(30, y0, 540, 100), cPanel, 10);
            std::wstring st = !g_haveLive ? L"driver não respondeu ainda…" : (C.link == 1 ? L"conectado" : (C.link == 2 ? L"calibrando giroscópio (deixe parado)" : L"aguardando controle…"));
            COLORREF sc = !g_haveLive ? cMuted : (C.link == 1 ? cOn : (C.link == 2 ? cWarn : cBad));
            const wchar_t* kind = C.kind == 1 ? L"ESP32" : (C.kind == 2 ? L"celular" : L"—");
            Text(std::wstring(L"● Controle ") + (h == HAND_RIGHT ? L"DIREITO" : L"ESQUERDO") + L" (" + kind + L"): " + st, RC(46, y0 + 2, 510, 26), sc, g_fText);
            if (g_haveLive && C.link != 0) {
                wchar_t b[200];
                swprintf(b, 200, L"IP %hs  ·  %.0f pct/s  ·  há %.0f ms  ·  %ls", C.ip, C.pktRate, C.ageMs, C.kind == 1 ? L"Wi-Fi/Bluetooth" : L"Wi-Fi");
                Text(b, RC(46, y0 + 30, 510, 22), cText, g_fSmall);
                if (C.kind == 1) swprintf(b, 200, L"Wi-Fi %d dBm  ·  perdidos %u  ·  descartados %u  ·  ligado há %ls", (int)C.rssi, C.lost, C.dropped, Uptime(C.uptimeMs).c_str());
                else swprintf(b, 200, L"perdidos %u  ·  descartados (velhos) %u", C.lost, C.dropped);
                Text(b, RC(46, y0 + 54, 510, 22), cMuted, g_fSmall);
                Text(std::wstring(L"A mão deste controle está gravada como ") + ((C.flags & KBT_FLAG_HAND_LEFT) ? L"ESQUERDA" : L"DIREITA"), RC(46, y0 + 76, 510, 20), cMuted, g_fSmall);
            } else Text(L"Ligue o ESP32 (mesma rede Wi-Fi) ou use o modo controle do app no celular. UDP 5555 (direito) e 5558 (esquerdo) liberados no firewall.", RC(46, y0 + 32, 510, 60), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        }
        if (g_haveLive && g_L.srcUnassigned)
            Text(L"⚠ Há outro controle (IP " + WS(g_L.extraIp) + L") sem mão: já existem dois controles, ou o modo IP fixo não reconheceu este IP.", RC(30, 600, 540, 44), cWarn, g_fSmall, DT_LEFT | DT_WORDBREAK);
        else
            Text(L"Dois controles em qualquer combinação (ESP32 + celular, 2 celulares, 2 ESP32): cada um diz sua mão; se os dois disserem a mesma, o segundo vai para a mão livre.", RC(30, 600, 540, 44), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        Text(L"Botões em tempo real", RC(600, 308, 520, 24), cAccent, g_fText);
        for (int h = 0; h < HAND_COUNT; ++h) {
            const CtlLive& C = g_L.ctl[h];
            Text(h == HAND_RIGHT ? L"Dir." : L"Esq.", RC(600, 336 + h * 44, 44, 38), cMuted, g_fSmall);
            for (int i = 0; i < P_COUNT; ++i)
                Pill(RC(646 + i * 53, 336 + h * 44, 50, 38), kPhysShort[i], g_haveLive && C.link != 0 && ((C.buttons >> i) & 1u));
        }
        {   // mãos rastreadas pela câmera do celular
            wchar_t b[220];
            Text(L"Mãos rastreadas pelo celular (Sala VR > modo PC)", RC(600, 428, 520, 26), cAccent, g_fText);
            if (!g_haveLive || g_L.handLink == 0) swprintf(b, 220, L"Sem dados. Na Sala VR do celular ligue \"Modo PC\" (porta UDP 5557 liberada).");
            else swprintf(b, 220, L"%ls · %hs · %.0f pct/s · dir. %ls%ls · esq. %ls%ls", g_L.handLink == 1 ? L"recebendo" : L"SEM PACOTES", g_L.handIp, g_L.handRate,
                          g_L.handTracked[HAND_RIGHT] ? L"vista" : L"—", g_L.handPinchNow[HAND_RIGHT] ? L" (pinça)" : L"",
                          g_L.handTracked[HAND_LEFT] ? L"vista" : L"—", g_L.handPinchNow[HAND_LEFT] ? L" (pinça)" : L"");
            Text(b, RC(600, 574, 520, 30), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        }
    }
    else if (g_page == PG_HEAD) {
        Text(L"1) clique no monitor do headset   2) reinicie o SteamVR   — o resto é automático", RC(30, 112, 540, 24), cMuted, g_fSmall);
        const std::string cur = GetS(kHmdMonitorKey);
        bool found = cur.empty();
        for (const MonitorInfo& m : g_mons) if (m.dev == cur) found = true;
        {   // estado da janela pedida ao SteamVR
            wchar_t w[260];
            if (g_haveLive && g_L.hmdWin[2] > 0) {
                const wchar_t* st = g_L.hmdStatus == 0 ? L"monitor escolhido" : (g_L.hmdStatus == 1 ? L"escolha automática" : (g_L.hmdStatus == 3 ? L"resolução do monitor alterada" : L"SEM monitor"));
                swprintf(w, 260, L"%ls: janela %d×%d em %d,%d%ls%ls", st, g_L.hmdWin[2], g_L.hmdWin[3], g_L.hmdWin[0], g_L.hmdWin[1],
                         g_L.madePrimary ? L" · tornado principal (restaura ao fechar)" : L"", g_L.fsFixes ? L" · tela cheia recolocada" : L"");
                Text(w, RC(30, 348, 540, 40), g_L.hmdStatus == 2 ? cWarn : cOn, g_fSmall, DT_LEFT | DT_WORDBREAK);
            } else if (!found) Text(L"Monitor escolhido não encontrado!", RC(30, 348, 540, 40), cBad, g_fSmall, DT_LEFT | DT_WORDBREAK);
            else Text(cur.empty() ? L"Nenhum escolhido: o driver usa um monitor que não seja o principal (ex.: virtual), senão o principal." : L"Aplica ao reiniciar o SteamVR.", RC(30, 348, 540, 40), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
            if (DisplayLayoutPending()) Text(L"● Layout original de monitores salvo: será restaurado ao fechar o SteamVR.", RC(30, 612, 540, 22), cWarn, g_fSmall);
        }
        RECT panel = RC(600, 118, 520, 72);
        Rect(panel, cPanel, 10);
        const uint32_t hl = g_haveLive ? g_L.headLink : 0;
        std::wstring st = hl == 1 ? L"● Celular conectado (pose da cabeça)" : (hl == 2 ? L"● Pose perdida — sem pacotes" : L"● Aguardando o celular…");
        Text(st, RC(616, 122, 490, 30), hl == 1 ? cOn : (hl == 2 ? cWarn : cMuted), g_fText);
        if (hl) {
            wchar_t b[200];
            swprintf(b, 200, L"%hs · %.0f pct/s · yaw %+.0f°  pitch %+.0f°  roll %+.0f°", g_L.headIp, g_L.headRate, g_L.headYpr[0], g_L.headYpr[1], g_L.headYpr[2]);
            Text(b, RC(616, 154, 490, 26), cText, g_fSmall);
        } else Text(L"No app do celular, informe o IP deste PC (porta 5556).", RC(616, 154, 490, 26), cMuted, g_fSmall);
        Text(L"Monitor, resolução, taxa, FOV, IPD, distorção e escala: reinicie o SteamVR. A janela sempre cobre o monitor inteiro (exigência do SteamVR).", RC(600, 238, 520, 28), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
    }
    else if (g_page == PG_EIXOS) {
        Text(L"Cada eixo de saída do controle SteamVR escolhe qual eixo do MPU o comanda (ou Nenhum). Zona morta é por eixo do MPU.", RC(30, 110, 840, 24), cMuted, g_fSmall);
        const wchar_t* mpuN[3] = { L"Roll (X)", L"Pitch (Y)", L"Yaw (Z)" };
        const wchar_t* ctl[3] = { L"Yaw", L"Pitch", L"Roll" };
        const int mpuOf[3] = { M_ROLL, M_PITCH, M_YAW };
        for (int a = 0; a < C_COUNT; ++a) {
            const int x = 30 + a * 380;
            const int m = mpuOf[a];
            wchar_t b[160];
            swprintf(b, 160, L"MPU %ls: bruto %+.1f° → processado %+.1f°", mpuN[a], lk ? CV.rawRpy[m] : 0.f, lk ? CV.mpu[m] : 0.f);
            Text(b, RC(x, 414, 350, 22), cText, g_fSmall);
            Bar(RC(x, 438, 350, 28), L"proc.", lk ? CV.mpu[m] : 0.f, 90.f);
        }
        Text(L"Saída enviada ao controle virtual desta mão (depois de mapear, inverter, ganho e suavizar)", RC(30, 472, 1100, 22), cAccent, g_fText);
        for (int a = 0; a < C_COUNT; ++a) Bar(RC(30 + a * 380, 496, 350, 28), ctl[a], lk ? CV.out[a] : 0.f, a == C_YAW ? 180.f : 90.f);
    }
    else if (g_page == PG_ANALOG) {
        const int S = 300;
        RECT sq = RC(700, 150, S, S);
        Rect(sq, cPanel, 14);
        int cx = sq.left + S / 2, cy = sq.top + S / 2;
        float dx = GetF(CS().stickDz[0]), dy = GetF(CS().stickDz[1]);
        Rect(RC(cx - (int)(dx * S / 2), cy - (int)(dy * S / 2), (int)(dx * S), (int)(dy * S)), cPanel2, 6);
        Rect(RC(sq.left + 6, cy - 1, S - 12, 2), cMuted);
        Rect(RC(cx - 1, sq.top + 6, 2, S - 12), cMuted);
        const float nx = lk ? CV.stickNorm[0] : 0.f, ny = lk ? CV.stickNorm[1] : 0.f;
        const float sx = lk ? CV.stick[0] : 0.f, sy = lk ? CV.stick[1] : 0.f;
        const int R = S / 2 - 12;
        Rect(RC(cx + (int)(nx * R) - 9, cy + (int)(ny * R) - 9, 18, 18), cMuted, 18);                      // bruto (ADC: +Y para baixo)
        Rect(RC(cx + (int)(sx * R) - 11, cy - (int)(sy * R) - 11, 22, 22), lk ? cAccent : cMuted, 22);    // saída final (+Y para cima)
        wchar_t b[200];
        swprintf(b, 200, L"Saída  X %+.2f   Y %+.2f   (ponto azul, +Y = cima)", sx, sy);
        Text(b, RC(640, 462, 420, 24), cText, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        swprintf(b, 200, L"Bruto  X %+.2f   Y %+.2f   (ponto cinza, como o ADC)", nx, ny);
        Text(b, RC(640, 488, 420, 24), cMuted, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        swprintf(b, 200, L"ADC: %u / %u   centro: %d / %d", lk ? CV.joyRaw[0] : 0, lk ? CV.joyRaw[1] : 0, GetI(kStickC[g_viewHand][0]), GetI(kStickC[g_viewHand][1]));
        Text(b, RC(640, 514, 420, 24), cMuted, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        Text(L"Empurre para cima: o ponto azul deve subir. Se não, ajuste Inverter/Trocar. Quadrado = zona morta.", RC(640, 544, 420, 44), cMuted, g_fSmall, DT_CENTER | DT_WORDBREAK);
    }
    else if (g_page == PG_BTN) {
        Text(L"1) clique no botão físico   2) escolha a função à direita (o próximo botão é selecionado sozinho)", RC(30, 562, 540, 44), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        for (int pb = 0; pb < P_COUNT; ++pb) {           // luz de tempo real: botao fisico apertado agora
            const bool on = lk && ((CV.buttons >> pb) & 1u);
            Rect(RC(512, 142 + pb * 41 + 9, 14, 14), on ? cOn : cPanel2, 14);
        }
        std::vector<int> fl; FnList(g_pickCat, g_bindHand, fl);
        Text(std::wstring(L"Função para: ") + kPhysName[g_pickRow < P_COUNT ? g_pickRow : 0] + L"  —  " + (FrameSel() ? L"Steam Frame Controller" : IndexSel() ? L"Valve Index" : GetI(kProfile) == PF_GENERIC ? L"Genérico" : GetI(kProfile) == PF_VIVE ? L"HTC Vive" : L"Oculus Touch")
             + (g_bindHand == HAND_RIGHT ? L" (mão direita)" : L" (mão esquerda)"), RC(590, 112, 540, 24), cAccent, g_fSmall);
        int count[O_COUNT] = {};
        for (int pb = 0; pb < P_COUNT; ++pb) count[GetI(BindOf(g_bindHand, pb))]++;
        std::wstring conf;
        for (int o = 1; o < O_COUNT; ++o) if (count[o] > 1) { if (!conf.empty()) conf += L"; "; conf += kOptOut[o]; }
        const int endY = 188 + ((int)fl.size() / 3 + 1) * 42 + 8;
        Text(!conf.empty() ? L"ℹ Mais de um botão na mesma função (permitido, sem clique duplicado): " + conf
                           : std::wstring(L"Dica: vários botões podem acionar a mesma função (ex.: R2 e Trigger)."), RC(590, endY, 540, 44), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        Text(L"Verde = botão apertado agora. Contorno = função já usada em algum botão.", RC(590, endY + 46, 540, 24), cMuted, g_fSmall);
        if (FrameSel()) Text(L"Steam Frame: o controle direito tem A/B/X/Y/Menu; o esquerdo tem D-pad/View. Trigger, Grip, Bumper, Stick e Sistema existem nos dois.", RC(590, 560, 540, 48), cWarn, g_fSmall, DT_LEFT | DT_WORDBREAK);
        else if (GetI(kProfile) == PF_GENERIC) Text(L"Perfil Genérico: botões do controle são expostos 1:1 (a tabela só vale para as ações do driver).", RC(590, 560, 540, 48), cWarn, g_fSmall, DT_LEFT | DT_WORDBREAK);
        else Text(L"O analógico (X/Y) de cada controle vai ao stick da própria mão; ajustes na aba Analógico.", RC(590, 560, 540, 48), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
    }
    else if (g_page == PG_PERFIL) {
        const int sel = GetI(kProfile);
        const int act = g_haveLive ? (int)g_L.profile : sel;
        static const wchar_t* info[PF_COUNT] = {
            L"Oculus Touch — perfil nativo do SteamVR ({oculus}). Expõe: Trigger (valor/clique/toque), Grip (valor/clique), A/B no direito e X/Y/Menu no esquerdo, Sistema, joystick (X/Y/clique/toque).\n\nMelhor compatibilidade com jogos. É também o perfil para o qual o SteamVR remapeia o binding no Steam Frame.",
            L"HTC Vive — perfil nativo do SteamVR ({htc}). Expõe: Trigger (valor/clique), Grip (só clique), Menu de aplicativo, Sistema, Trackpad (X/Y/clique/toque).\n\nO analógico vira o trackpad. Limitações do Vive: não existe botão B/Y (o mapeado nele não aparece), o botão A/X vira Menu de aplicativo e o grip não tem valor analógico.",
            L"Valve Index (Knuckles) — perfil nativo ({indexcontroller}). EXPERIMENTAL e não validado.\n\nExpõe: A, B, Sistema, Trigger (valor/clique/toque), Grip (valor/toque), thumbstick. NÃO há esqueleto de dedos nem força: jogos que dependem da mão esquelética podem mostrar a mão parada ou ignorar o controle.",
            L"Genérico StormVR — perfil próprio (resources/input/stormvr_generic_profile.json). EXPERIMENTAL e não validado.\n\nExpõe todas as entradas físicas 1:1 no controle direito: Trigger, Grip, A, B, X, Y, Sistema e stick X/Y/clique/toque (a tabela da aba Botões é ignorada). Por ser um tipo novo, os jogos não trazem binding: crie um na tela de Bindings do SteamVR; jogos antigos podem não reconhecer." ,
            L"Steam Frame Controller — perfil do Valve ({frame_controller}, driver só de recursos que vem com o SteamVR). EXPERIMENTAL.\n\nControle esquerdo: View + D-pad (A→baixo, B→direita, X→esquerda, Y→cima) e Stick. Controle direito: Sistema (Start) + A, B, X, Y e Stick. Os dois: Trigger, Grip, Bumper (R2) e Stick (clique/toque).\n\nO botão Menu do direito e os dedos (esqueleto) ainda não estão ligados. Trocar de perfil exige reiniciar o SteamVR."
        };
        RECT panel = RC(30, 500, 540, 330);      // abaixo dos controles de rotacao (antes cobria os sliders); a pagina rola
        Rect(panel, cPanel, 10);
        Text(info[sel], RC(46, 508, 508, 314), cText, g_fSmall, DT_LEFT | DT_WORDBREAK);
        wchar_t b[200];
        swprintf(b, 200, L"Perfil ativo no SteamVR agora: %ls", kOptProfile[act].c_str());
        Text(b, RC(600, 138, 520, 28), act == sel ? cOn : cWarn, g_fText);
        if (act != sel) Text(L"Você escolheu outro perfil: REINICIE o SteamVR para aplicar.", RC(600, 170, 520, 40), cWarn, g_fSmall, DT_LEFT | DT_WORDBREAK);
        else Text(L"Trocar de perfil exige reiniciar o SteamVR (os componentes são criados na ativação).", RC(600, 170, 520, 40), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        Rect(RC(600, 222, 520, 348), cPanel, 10);
        Text(L"Steam Frame", RC(616, 228, 490, 28), cAccent, g_fText);
        Text(L"Agora existe o perfil \"Steam Frame Controller (exp.)\": usa o driver de recursos {frame_controller} que acompanha o SteamVR (precisa estar na pasta drivers do SteamVR). Escolha-o em \"Perfil do controle\" e reinicie o SteamVR. Quando o jogo não tem binding próprio do Frame, o SteamVR remapeia a partir do Oculus Touch. Não testado no hardware.",
             RC(616, 260, 490, 304), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
    }
    else if (g_page == PG_ARM) {
        RECT panel = RC(600, 118, 530, 344);
        Rect(panel, cPanel, 10);
        const bool manual = GetB(kPrevManual);
        const bool live = !manual && g_haveLive && CV.armOn;
        float J[3][3];                                           // ombro, cotovelo, mao: x direita, y cima, z TRAS (referencial do corpo)
        if (live) memcpy(J, CV.armJ, sizeof(J));
        else {                                                   // mesmo resolvedor do driver, com a pose da previa manual
            ArmParams ap;
            ap.upper = GetF(kArmUpper); ap.fore = GetF(kArmFore); ap.shX = GetF(kArmShX); ap.shDrop = GetF(kArmShDrop);
            ap.bendUp = GetF(kArmBendUp); ap.bendDown = GetF(kArmBendDown); ap.sideReach = GetF(kArmSideReach);
            ap.torso = GetF(kArmTorso); ap.reach = GetF(kArmReach);
            const double D = 3.14159265358979 / 180.0;
            const double pit = (manual ? GetF(kPrevPitch) : 0.f) * D, yaw = (manual ? GetF(kPrevYaw) : 0.f) * D;
            const double fwd[3] = { -std::sin(yaw) * std::cos(pit), std::sin(pit), -std::cos(yaw) * std::cos(pit) };
            double sh[3], el[3], ha[3];
            ArmSolve(ap, 0.0, yaw, pit, fwd, 1.0, sh, el, ha);
            for (int i = 0; i < 3; ++i) { J[0][i] = (float)sh[i]; J[1][i] = (float)el[i]; J[2][i] = (float)ha[i]; }
        }
        const COLORREF lc = live ? cAccent : cMuted;
        // vista lateral (frente para a direita)
        { const float S = 300.f; const int px0 = panel.left + 80, py0 = panel.top + 70;
          Text(L"Lateral", RC(panel.left + 12, panel.top + 4, 120, 22), cMuted, g_fSmall);
          auto P = [&](const float* j, int& X, int& Y) { X = px0 + (int)(-j[2] * S); Y = py0 - (int)(j[1] * S); };
          int sx, sy, ex, ey, mx, my; P(J[0], sx, sy); P(J[1], ex, ey); P(J[2], mx, my);
          Rect(RC(px0 - 24, py0 - 24, 48, 48), cPanel2, 48);
          Line(px0, py0 + 22, sx, sy, cPanel2, 4); Line(sx, sy, ex, ey, lc, 6); Line(ex, ey, mx, my, lc, 6);
          Rect(RC(sx - 6, sy - 6, 12, 12), cText, 12); Rect(RC(ex - 7, ey - 7, 14, 14), cText, 14); Rect(RC(mx - 10, my - 10, 20, 20), live ? cOn : cText, 20); }
        // vista de cima (frente para cima)
        { const float S = 170.f; const int px0 = panel.left + 395, py0 = panel.top + 215;
          Text(L"De cima", RC(panel.left + 300, panel.top + 4, 120, 22), cMuted, g_fSmall);
          auto P = [&](const float* j, int& X, int& Y) { X = px0 + (int)(j[0] * S); Y = py0 + (int)(j[2] * S); };
          int sx, sy, ex, ey, mx, my; P(J[0], sx, sy); P(J[1], ex, ey); P(J[2], mx, my);
          Rect(RC(px0 - 22, py0 - 22, 44, 44), cPanel2, 44);
          Line(px0, py0, sx, sy, cPanel2, 4); Line(sx, sy, ex, ey, lc, 5); Line(ex, ey, mx, my, lc, 5);
          Rect(RC(sx - 5, sy - 5, 10, 10), cText, 10); Rect(RC(ex - 6, ey - 6, 12, 12), cText, 12); Rect(RC(mx - 9, my - 9, 18, 18), live ? cOn : cText, 18); }
        Text(live ? L"Ao vivo (controle desta mão)." : (manual ? L"Prévia manual: ajuste pitch e yaw abaixo." : L"Pose de repouso — ative o Arm Model e mexa o controle."), RC(616, 440, 500, 22), cText, g_fSmall);
        Text(L"Isto é uma ESTIMATIVA pela orientação do controle e pela cabeça — não é rastreamento real do braço ou do cotovelo.", RC(600, 576, 530, 44), cWarn, g_fSmall, DT_LEFT | DT_WORDBREAK);
        Text(L"Mão p/ baixo: o cotovelo vai à FRENTE. Mão p/ cima: recua um pouco. Ao apontar para os lados o braço estica (alcance extra) e o ombro gira junto (tronco). Com o Arm Model ligado, os ajustes de posição das abas Geral/teclado viram deslocamento extra.", RC(30, 548, 560, 90), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
    }
    else if (g_page == PG_DIAG) {
        auto yesno = [](bool v) { return v ? L"sim" : L"não"; };
        RECT p1 = RC(30, 116, 560, 222); Rect(p1, cPanel, 10);
        Text(L"Conexão e controle", RC(46, 120, 520, 24), cAccent, g_fText);
        wchar_t b[220];
        for (int h = 0; h < HAND_COUNT; ++h) {
            const CtlLive& C = g_L.ctl[h];
            swprintf(b, 220, L"%ls: %ls · IP %hs · %.0f pct/s · há %.0f ms · perdidos %u", h == HAND_RIGHT ? L"Direita" : L"Esquerda",
                     !g_haveLive ? L"driver sem resposta" : (C.link == 1 ? (C.kind == 2 ? L"celular" : L"ESP32") : (C.link == 2 ? L"calibrando" : L"sem pacotes")),
                     g_haveLive ? C.ip : "-", g_haveLive ? C.pktRate : 0.f, g_haveLive ? C.ageMs : 0.f, g_haveLive ? C.lost : 0);
            Text(b, RC(46, 148 + h * 24, 530, 22), h == HAND_RIGHT ? cText : cText, g_fSmall);
        }
        swprintf(b, 220, L"Fontes UDP vistas: %u · sem mão: %u · perfil ativo: %ls", g_haveLive ? g_L.srcSeen : 0, g_haveLive ? g_L.srcUnassigned : 0, g_haveLive ? kOptProfile[g_L.profile % PF_COUNT].c_str() : L"-");
        Text(b, RC(46, 196, 530, 22), cMuted, g_fSmall);
        swprintf(b, 220, L"Cabeça (celular): %ls · %hs · %.0f pct/s", !g_haveLive || !g_L.headLink ? L"sem dados" : (g_L.headLink == 1 ? L"recebendo" : L"sem pacotes"), g_haveLive ? g_L.headIp : "-", g_haveLive ? g_L.headRate : 0.f);
        Text(b, RC(46, 220, 530, 22), cText, g_fSmall);
        swprintf(b, 220, L"Controle direito ligado: %ls · esquerdo ligado: %ls", yesno(GetB(kEnabled[HAND_RIGHT], true)), yesno(GetB(kEnabled[HAND_LEFT], true)));
        Text(b, RC(46, 244, 530, 22), cMuted, g_fSmall);
        Text(L"Arm Model: " + std::wstring(GetB(kArmEnabled) ? L"ligado" : L"desligado") + L" · MPU: eixos ativos Yaw " + yesno(GetB(CS().axEn[0])) + L", Pitch " + yesno(GetB(CS().axEn[1])) + L", Roll " + yesno(GetB(CS().axEn[2])), RC(46, 268, 530, 22), cMuted, g_fSmall);
        if (g_haveLive && g_L.hmdWin[2] > 0) {
            wchar_t w[200];
            swprintf(w, 200, L"Janela do headset: %d×%d em %d,%d · tela cheia recolocada %u×", g_L.hmdWin[2], g_L.hmdWin[3], g_L.hmdWin[0], g_L.hmdWin[1], g_L.fsFixes);
            Text(w, RC(46, 294, 530, 22), cMuted, g_fSmall);
        }
        // MPU6050
        RECT p2 = RC(30, 346, 560, 224); Rect(p2, cPanel, 10);
        Text(std::wstring(L"MPU6050 / sensores — controle ") + (g_viewHand == HAND_RIGHT ? L"DIREITO" : L"ESQUERDO"), RC(46, 350, 520, 24), cAccent, g_fText);
        const uint32_t fl = g_haveLive ? CV.flags : 0;
        std::wstring cal = !lk ? L"sem dados" : ((fl & KBT_FLAG_MPU_ERROR) ? L"ERRO: o MPU6050 não responde (SDA/SCL/3V3/GND)" : ((fl & KBT_FLAG_CALIBRATING) ? L"calibrando o giroscópio… deixe parado" : ((fl & KBT_FLAG_CALIBRATED) ? L"calibrado" : L"ainda não calibrado")));
        Text(L"Estado: " + cal, RC(46, 378, 530, 22), (fl & KBT_FLAG_MPU_ERROR) ? cBad : ((fl & KBT_FLAG_CALIBRATING) ? cWarn : cText), g_fSmall);
        swprintf(b, 220, L"Bruto  roll %+.1f°  pitch %+.1f°  yaw %+.1f°", lk ? CV.rawRpy[0] : 0.f, lk ? CV.rawRpy[1] : 0.f, lk ? CV.rawRpy[2] : 0.f);
        Text(b, RC(46, 402, 530, 22), cText, g_fSmall);
        swprintf(b, 220, L"Giroscópio  %+.1f  %+.1f  %+.1f °/s", lk ? CV.gyro[0] : 0.f, lk ? CV.gyro[1] : 0.f, lk ? CV.gyro[2] : 0.f);
        Text(b, RC(46, 426, 530, 22), cText, g_fSmall);
        swprintf(b, 220, L"Processado (saída)  yaw %+.1f°  pitch %+.1f°  roll %+.1f°", lk ? CV.out[C_YAW] : 0.f, lk ? CV.out[C_PITCH] : 0.f, lk ? CV.out[C_ROLL] : 0.f);
        Text(b, RC(46, 450, 530, 22), cText, g_fSmall);
        swprintf(b, 220, L"Analógico  ADC %u/%u · bruto %+.2f/%+.2f · saída %+.2f/%+.2f", lk ? CV.joyRaw[0] : 0, lk ? CV.joyRaw[1] : 0, lk ? CV.stickNorm[0] : 0.f, lk ? CV.stickNorm[1] : 0.f, lk ? CV.stick[0] : 0.f, lk ? CV.stick[1] : 0.f);
        Text(b, RC(46, 474, 530, 22), cText, g_fSmall);
        swprintf(b, 220, L"Botões físicos 0x%02X · entradas do controle virtual 0x%llX", lk ? CV.buttons : 0, g_haveLive ? (unsigned long long)CV.outBits : 0ull);
        Text(b, RC(46, 498, 530, 22), cMuted, g_fSmall);
        // registro
        RECT p3 = RC(610, 116, 520, 454); Rect(p3, cPanel, 10);
        Text(L"Registro de eventos do driver", RC(626, 120, 480, 24), cAccent, g_fText);
        if (g_haveLive) {
            const uint32_t n = std::min<uint32_t>(g_L.logSeq, 8);
            for (uint32_t i = 0; i < n; ++i) {
                const uint32_t idx = (g_L.logSeq - 1 - i) % 8;                        // mais recente primeiro
                char line[100]; memcpy(line, g_L.log[idx], 96); line[95] = 0;
                Text(WS(line), RC(626, 152 + (int)i * 50, 490, 46), i == 0 ? cText : cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
            }
            if (n == 0) Text(L"Nenhum evento ainda.", RC(626, 152, 480, 24), cMuted, g_fSmall);
        } else Text(L"Driver sem resposta.", RC(626, 152, 480, 24), cMuted, g_fSmall);
        Text(L"O texto completo fica em SteamVR\\logs\\vrserver.txt (procure por \"stormvr\").", RC(610, 540, 520, 30), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
    }
}

static void PaintCoordViz();
static void PaintImpl() {
    // fundo
    RECT all = RC(0, 0, W, H);
    Rect(all, cBg);
    Text(std::wstring(L"StormVR  ·  ") + kBuildTag, RC(30, 14, 900, 48), cText, g_fTitle);

    Layout();
    SetViewportOrgEx(g_dc, 0, kShift - g_scroll, nullptr);                      // conteudo rolavel (abaixo das abas)
    IntersectClipRect(g_dc, 0, kTopY - kShift + g_scroll, W, H - kShift + g_scroll);
    PaintBack();
    int idx = 0;
    for (const Widget& w : g_w) {
        switch (w.type) {
        case W_TAB: break;   // as abas sao desenhadas por ultimo, fixas no topo
        case W_CHECK: {
            bool on = GetB(w.key.c_str(), w.defB);
            Rect(w.r, cPanel, 10);
            RECT box = RC(w.r.left + 10, w.r.top + 6, 24, 24);
            Rect(box, on ? cOn : cPanel2, 6);
            if (on) Text(L"✓", box, RGB(16, 6, 32), g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            RECT t = w.r; t.left += 46;
            Text(w.label, t, cText, g_fText);
        } break;
        case W_SLIDER: {
            float v = GetF(*w.fs);
            Rect(w.r, cPanel, 10);
            RECT t = w.r; t.left += 12; t.bottom = t.top + 30; t.top += 4;
            Text(w.label, t, cText, g_fText);
            RECT tr = TrackRect(w); tr.left += 12; tr.right += 0;
            Rect(tr, cPanel2, 6);
            float f = Clamp01((v - w.fs->mn) / (w.fs->mx - w.fs->mn));
            int cx = tr.left + (int)(f * (tr.right - tr.left));
            RECT fill = tr; fill.right = cx; Rect(fill, cAccent, 6);
            RECT knob = RC(cx - 10, tr.top - 6, 20, 22); Rect(knob, RGB(240, 244, 255), 20);
            wchar_t b[32]; swprintf(b, 32, L"%.2f", v);
            RECT vr = RC(w.r.right - 80, w.r.top + 28, 70, 26);
            Text(b, vr, cText, g_fText, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
        } break;
        case W_CYCLE: {
            int v = GetI(*w.is);
            Rect(w.r, cPanel, 10);
            int split = w.r.left + (w.r.right - w.r.left) * w.frac / 100;
            RECT t = w.r; t.left += 12; t.right = split;
            Text(w.label, t, cText, g_fText);
            RECT btn = w.r; btn.left = split + 4; btn.top += 4; btn.bottom -= 4; btn.right -= 6;
            Rect(btn, cPanel2, 8);
            Text(v >= 0 && v < (int)w.opts->size() ? (*w.opts)[v] : L"?", btn, cText, g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        } break;
        case W_KEY: {
            bool rb = (g_rebind == idx);
            RECT lab = w.r; lab.right = lab.left + (w.action == -2 ? 170 : 320);
            Text(w.label, lab, cText, w.action == -2 ? g_fSmall : g_fText);
            RECT btn = w.r; btn.left = w.action == -2 ? w.r.left + 175 : w.r.left + 330;
            Rect(btn, rb ? cWarn : cPanel, 8);
            Text(rb ? L"Pressione uma tecla…" : KeyName(KeyOf(w)), btn, rb ? RGB(30, 20, 0) : cText, g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        } break;
        case W_BUTTON:
            if (w.shape == 1) {                                   // entrada do desenho do controle
                const int rad = std::min<int>(w.r.right - w.r.left, w.r.bottom - w.r.top);
                if (w.mapped || w.sel) Rect(RECT{ w.r.left - 3, w.r.top - 3, w.r.right + 3, w.r.bottom + 3 }, w.sel ? cWarn : cAccent, rad + 6);
                Rect(w.r, w.lit ? cOn : cPanel2, rad);
                Text(w.label, w.r, w.lit ? RGB(16, 6, 32) : cText, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            } else {
                Rect(w.r, w.sel ? cAccent : cPanel2, 10);
                Text(w.label, w.r, cText, g_fText, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            }
            break;
        }
        ++idx;
    }
    PaintExtras();
    if (g_page == PG_TECLAS) {
        Text(L"Mantenha o NumLock ligado para usar as teclas do teclado numérico.", RC(560, 86, 570, 24), cMuted, g_fSmall, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
        Text(L"Clique numa tecla e pressione a nova (Esc cancela, Backspace remove).", RC(30, 108, 1000, 20), cMuted, g_fSmall);
        Text(L"Direito", RC(30, 128, 200, 22), cAccent, g_fText);
        Text(L"Esquerdo", RC(570, 128, 200, 22), cAccent, g_fText);
        Text(L"Com a tecla de MOVER: apontar = mover o controle. Com a de frente/trás: ↑↓ = frente/trás.", RC(30, 150 + A_COUNT * 27 + 4, 1040, 26), cMuted, g_fSmall);
    } else if (g_page == PG_GERAL) {
        Text(L"Resetar piso: apaga o piso/posição salvos pelo Room Setup (corrige 'dentro do chão'). Reiniciar: fecha e reabre o SteamVR.", RC(590, 330, 540, 48), cMuted, g_fSmall, DT_LEFT | DT_WORDBREAK);
        Text(L"Salvar / carregar configurações (3 slots em Documentos\\StormVR)", RC(600, 546, 520, 22), cMuted, g_fSmall);
    }
    // ---- cabecalho fixo por cima do conteudo rolavel
    SelectClipRgn(g_dc, nullptr);
    SetViewportOrgEx(g_dc, 0, 0, nullptr);
    Rect(RC(0, 0, W, kTopY - 6), cBg);
    Text(std::wstring(L"StormVR  ·  ") + kBuildTag, RC(30, 14, 900, 48), cText, g_fTitle);
    for (const Widget& w : g_w) {
        if (w.type != W_TAB) continue;
        const bool sel = (w.id == g_page);
        Rect(w.r, sel ? cAccent : cPanel, 10);
        Text(w.label, w.r, sel ? RGB(255, 255, 255) : cMuted, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
    if (GetTickCount64() < g_msgUntil)
        Text(g_msg, RC(430, 10, 700, 56), cWarn, g_fSmall, DT_RIGHT | DT_VCENTER | DT_WORDBREAK);
    if (g_page == PG_COORD) PaintCoordViz();
    if (g_maxScroll > 0) {   // barra de rolagem (arraste a barra ou use a roda / o analogico do controle VR)
        const int top = kTopY, hgt = H - kTopY - 6;
        Rect(RC(W - 14, top, 8, hgt), cPanel2, 8);
        const int thumbH = std::max(40, hgt * hgt / (hgt + g_maxScroll));
        const int ty = top + (int)((long long)(hgt - thumbH) * g_scroll / g_maxScroll);
        Rect(RC(W - 14, ty, 8, thumbH), cAccent, 8);
    }
    GdiFlush();
}


// Linha vertical (x, y0..y1) - os 4 argumentos usados nos graficos da aba Coordenadas
// Confirmacao para opcoes que podem te deixar sem conseguir usar o driver / mexem nos monitores. Devolve true se o usuario confirmar.
static bool ConfirmRisky(const wchar_t* text) {
    return MessageBoxW(GetForegroundWindow(), text, L"StormVR - confirmar", MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2 | MB_TOPMOST | MB_SETFOREGROUND) == IDYES;
}
static void VLine(int x, int y0, int y1, COLORREF c);
// ---- Aba Coordenadas: plano cartesiano em tempo real (fixo na tela, nao rola). Mostra piso (y=0), area abaixo do piso, cabeca e controles.
static void VLine(int x0, int y0, int x1, int y1, COLORREF c, int w = 1) {
    HPEN pen = CreatePen(PS_SOLID, w, c); HGDIOBJ old = SelectObject(g_dc, pen);
    MoveToEx(g_dc, x0, y0, nullptr); LineTo(g_dc, x1, y1); SelectObject(g_dc, old); DeleteObject(pen);
}
static void VLine(int x, int y0, int y1, COLORREF c) { VLine(x, y0, x, y1, c, 1); }
static void VDot(int cx, int cy, int r, COLORREF c) {
    HBRUSH br = CreateSolidBrush(c); HPEN pen = CreatePen(PS_SOLID, 1, c);
    HGDIOBJ ob = SelectObject(g_dc, br), op = SelectObject(g_dc, pen);
    Ellipse(g_dc, cx - r, cy - r, cx + r + 1, cy + r + 1); SelectObject(g_dc, ob); SelectObject(g_dc, op); DeleteObject(br); DeleteObject(pen);
}
static void PaintCoordViz() {
    const float S = 80.f;                                           // pixels por metro
    const int px0 = 590, pw = 540;
    // ---------- FRENTE (X x Y)
    const int fy0 = 126, fh = 226;
    Rect(RC(px0, fy0, pw, fh), RGB(14, 9, 26), 10);
    Text(L"FRENTE (X × Y)  ·  verde = piso", RC(px0 + 8, fy0 + 2, 360, 18), cMuted, g_fSmall);
    const float ymin = -0.8f;
    auto fx = [&](float x) { return px0 + pw / 2 + (int)(x * S); };
    auto fy = [&](float y) { return fy0 + fh - 4 - (int)((y - ymin) * S); };
    const int floorY = fy(0.f);
    Rect(RC(px0 + 4, floorY, pw - 8, fy0 + fh - 4 - floorY), RGB(70, 18, 40), 0);            // abaixo do piso
    for (int k = -6; k <= 6; ++k) VLine(fx(k * 0.5f), fy0 + 20, fy0 + fh - 4, RGB(44, 30, 74));
    for (int k = -1; k <= 4; ++k) { VLine(px0 + 4, fy(k * 0.5f), px0 + pw - 4, fy(k * 0.5f), RGB(44, 30, 74));
        if (k % 2 == 0) { wchar_t b[16]; swprintf(b, 16, L"%.0f m", k * 0.5f); Text(b, RC(px0 + 6, fy(k * 0.5f) - 14, 40, 14), cMuted, g_fSmall); } }
    VLine(px0 + 4, floorY, px0 + pw - 4, floorY, cOn, 2);
    Text(L"ABAIXO DO PISO", RC(px0 + pw - 140, floorY + 4, 134, 16), RGB(200, 110, 110), g_fSmall, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
    // ---------- CIMA (X x Z)
    const int ty0 = fy0 + fh + 8, th = 188;
    Rect(RC(px0, ty0, pw, th), RGB(14, 9, 26), 10);
    Text(L"VISTA DE CIMA (X × Z)  ·  frente do SteamVR = para cima", RC(px0 + 8, ty0 + 2, 460, 18), cMuted, g_fSmall);
    auto tx = [&](float x) { return px0 + pw / 2 + (int)(x * S); };
    auto tz = [&](float z) { return ty0 + th / 2 + 8 + (int)(z * S); };
    for (int k = -6; k <= 6; ++k) VLine(tx(k * 0.5f), ty0 + 20, ty0 + th - 4, RGB(44, 30, 74));
    for (int k = -2; k <= 2; ++k) VLine(px0 + 4, tz(k * 0.5f), px0 + pw - 4, tz(k * 0.5f), RGB(44, 30, 74));
    VLine(tx(0), ty0 + 20, tx(0), ty0 + th - 4, RGB(104, 78, 150)); VLine(px0 + 4, tz(0), px0 + pw - 4, tz(0), RGB(104, 78, 150));
    // ---------- dados ao vivo
    wchar_t b[220];
    if (!g_haveLive) {
        Text(L"Sem dados do driver (abra o SteamVR com o StormVR ligado).", RC(px0 + 10, fy0 + fh / 2 - 10, pw - 20, 20), cWarn, g_fSmall, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        return;
    }
    const float* hp = g_L.hmdPos; const float* up = g_L.hmdUp;
    const bool upright = up[1] > 0.f;
    const COLORREF ac = upright ? cOn : cBad;
    VDot(fx(hp[0]), fy(hp[1]), 9, cText);
    VLine(fx(hp[0]), fy(hp[1]), fx(hp[0] + up[0] * 0.30f), fy(hp[1] + up[1] * 0.30f), ac, 3);          // seta do TOPO da cabeca
    VDot(fx(hp[0] + up[0] * 0.30f), fy(hp[1] + up[1] * 0.30f), 4, ac);
    VDot(tx(hp[0]), tz(hp[2]), 8, cText);
    const COLORREF hc[2] = { RGB(255, 140, 60), RGB(80, 170, 255) };
    for (int h = 0; h < HAND_COUNT; ++h) {
        const float* c = g_L.ctlPosW[h];
        VDot(fx(c[0]), fy(c[1]), 6, hc[h]); VDot(tx(c[0]), tz(c[2]), 6, hc[h]);
    }
    swprintf(b, 220, L"Cabeça  x %.2f  y %.2f  z %.2f   ·   topo da cabeça: %ls", hp[0], hp[1], hp[2], upright ? L"CERTO (para cima)" : L"INVERTIDO (para baixo)");
    Text(b, RC(px0, ty0 + th + 6, pw, 20), ac, g_fSmall, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
    swprintf(b, 220, L"Direito  x %.2f  y %.2f  z %.2f     Esquerdo  x %.2f  y %.2f  z %.2f", g_L.ctlPosW[0][0], g_L.ctlPosW[0][1], g_L.ctlPosW[0][2], g_L.ctlPosW[1][0], g_L.ctlPosW[1][1], g_L.ctlPosW[1][2]);
    Text(b, RC(px0, ty0 + th + 26, pw, 20), cMuted, g_fSmall, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
    Text(L"● cabeça (branco)   ● direito (laranja)   ● esquerdo (azul)", RC(px0, ty0 + th + 46, pw, 20), cMuted, g_fSmall, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
}

// Se uma pagina falhar ao desenhar, o app NAO morre (antes sumia tudo): registra em Documentos\StormVR\config_erro.log e segue.
static int g_paintFaults = 0;
static void PaintFault(unsigned code, int page) {
    ++g_paintFaults;
    char path[MAX_PATH] = {};
    if (GetEnvironmentVariableA("USERPROFILE", path, MAX_PATH)) {
        strcat_s(path, "\\Documents\\StormVR\\config_erro.log");
        FILE* f = nullptr; fopen_s(&f, path, "a");
        if (f) { fprintf(f, "pagina %d: excecao 0x%08X (falha n %d)\n", page, code, g_paintFaults); fclose(f); }
    }
}
static void Paint() {
    unsigned code = 0;
    __try { PaintImpl(); }
    __except (code = GetExceptionCode(), EXCEPTION_EXECUTE_HANDLER) { PaintFault(code, g_page); }
}

static uint64_t g_lastHash = 0;
static ULONGLONG g_userActiveUntil = 0;   // laser/mouse em uso na dashboard
static bool g_forceUpload = true;   // primeiro quadro e quando a dashboard reaparece

static void Upload(vr::VROverlayHandle_t h) {
    // So envia quando os pixels realmente mudaram (hash FNV-1a de 64 bits). Telemetria ao vivo (angulos, RSSI...) muda a imagem a cada ciclo:
    // com a dashboard parada no ultimo estado, o envio fica limitado a ~4/s (e imediato quando o usuario esta mexendo com o laser).
    static ULONGLONG lastUp = 0;
    const ULONGLONG tnow = GetTickCount64();
    if (!g_forceUpload && tnow - lastUp < (tnow < g_userActiveUntil ? 30u : 900u)) return;   // parada: a ultima imagem fica na tela; telemetria ao vivo so ~1x/s
    uint64_t hv = 1469598103934665603ULL;
    for (size_t i = 0; i < (size_t)W * H; ++i) { hv ^= g_px[i]; hv *= 1099511628211ULL; }
    if (!g_forceUpload && hv == g_lastHash) return;
    g_forceUpload = false; g_lastHash = hv; lastUp = tnow;
    for (size_t i = 0; i < (size_t)W * H; ++i) {
        uint32_t p = g_px[i];                       // 0x00RRGGBB (BGRA em memoria)
        g_rgba[i * 4 + 0] = (p >> 16) & 0xFF;
        g_rgba[i * 4 + 1] = (p >> 8) & 0xFF;
        g_rgba[i * 4 + 2] = p & 0xFF;
        g_rgba[i * 4 + 3] = 255;
    }
    vr::VROverlay()->SetOverlayRaw(h, g_rgba.data(), W, H, 4);
}

// ------------------------------------------------------------------ interacao
static bool Hit(const RECT& r, int x, int y) { return x >= r.left && x < r.right && y >= r.top && y < r.bottom; }
static ULONGLONG g_lastDragWrite = 0;

static void SetSliderFromX(const Widget& w, int x, bool force) {
    ULONGLONG t = GetTickCount64();
    if (!force && t - g_lastDragWrite < 50) return;
    g_lastDragWrite = t;
    RECT tr = TrackRect(w); tr.left += 12;
    float f = Clamp01((float)(x - tr.left) / (float)(tr.right - tr.left));
    SetF(*w.fs, w.fs->mn + f * (w.fs->mx - w.fs->mn));
    g_dirty = true;
}

static void ResetPositions() {
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < 3; ++i) SetF(kOffset[h][i], kOffset[h][i].def);
}
static void ResetAxes(int h) {
    const CtlSettings& k = kCtl[h];
    for (int a = 0; a < C_COUNT; ++a) {
        SetI(k.axSrc[a].key, k.axSrc[a].def); SetB(k.axInv[a].key, k.axInv[a].def); SetB(k.axEn[a].key, k.axEn[a].def);
        SetF(k.axGain[a], k.axGain[a].def); SetF(k.axSmooth[a], k.axSmooth[a].def);
    }
    for (int i = 0; i < 3; ++i) SetF(k.mpuDz[i], k.mpuDz[i].def);
    SetF(k.yawDrift, k.yawDrift.def);
}
static void ResetStick(int h) {
    const CtlSettings& k = kCtl[h];
    for (int i = 0; i < 2; ++i) { SetF(k.stickDz[i], k.stickDz[i].def); SetB(k.stickInv[i].key, k.stickInv[i].def); SetI(kStickC[h][i].key, kStickC[h][i].def); }
    for (const FloatSetting* f : { &k.stickGamma, &k.stickEdge, &k.stickSens, &k.trigRamp, &k.gripRamp }) SetF(*f, f->def);
    SetB(k.stickSwap.key, k.stickSwap.def);
}
static void CopyAxes(int from, int to) {
    const CtlSettings& a = kCtl[from]; const CtlSettings& b = kCtl[to];
    for (int i = 0; i < C_COUNT; ++i) {
        SetI(b.axSrc[i].key, GetI(a.axSrc[i])); SetB(b.axEn[i].key, GetB(a.axEn[i])); SetB(b.axInv[i].key, GetB(a.axInv[i]));
        SetF(b.axGain[i], GetF(a.axGain[i])); SetF(b.axSmooth[i], GetF(a.axSmooth[i]));
    }
    for (int i = 0; i < 3; ++i) SetF(b.mpuDz[i], GetF(a.mpuDz[i]));
    SetF(b.yawDrift, GetF(a.yawDrift));
}
static void CopyStick(int from, int to) {
    const CtlSettings& a = kCtl[from]; const CtlSettings& b = kCtl[to];
    for (int i = 0; i < 2; ++i) { SetF(b.stickDz[i], GetF(a.stickDz[i])); SetB(b.stickInv[i].key, GetB(a.stickInv[i])); }
    SetF(b.stickGamma, GetF(a.stickGamma)); SetF(b.stickEdge, GetF(a.stickEdge)); SetF(b.stickSens, GetF(a.stickSens));
    SetF(b.trigRamp, GetF(a.trigRamp)); SetF(b.gripRamp, GetF(a.gripRamp)); SetB(b.stickSwap.key, GetB(a.stickSwap));
}
static void ResetMap() { for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) { SetI(kBind[h][i].key, kBind[h][i].def); SetI(kBindFrame[h][i].key, kBindFrame[h][i].def); } }
static void ResetArm() {
    SetB(kArmEnabled.key, kArmEnabled.def);
    for (const FloatSetting* f : { &kArmUpper, &kArmFore, &kArmShX, &kArmShDrop, &kArmBendUp, &kArmBendDown, &kArmSideReach, &kArmTorso, &kArmReach }) SetF(*f, f->def);
}
static void ResetEspSettings() {
    for (int h = 0; h < HAND_COUNT; ++h) { ResetAxes(h); ResetStick(h); SetF(kCtl[h].clickThr, kCtl[h].clickThr.def); }
    ResetMap(); ResetArm();
    SetI(kSrcMode.key, kSrcMode.def); SetI(kProfile.key, kProfile.def);
    SetF(kEspTimeout, kEspTimeout.def);
    for (const BoolSetting* b : { &kComboRecenter, &kAutoCenter, &kAutoZeroConn, &kHideLost }) SetB(b->key, b->def);
    SetI(kAssign.key, kAssign.def); SetB(kSwap.key, kSwap.def); SetS(kCtlIp[0], std::string()); SetS(kCtlIp[1], std::string()); SetI(kEspChannel.key, kEspChannel.def);
}

// ---- Salvar / carregar: lista de TODAS as configuracoes do driver
struct Visitor {
    std::function<void(const FloatSetting&)> f;
    std::function<void(const IntSetting&)> i;
    std::function<void(const char*, bool)> b;
    std::function<void(const char*)> s;
};
static void VisitAll(const Visitor& v) {
    for (const FloatSetting* f : { &kMoveSpeed, &kRotSpeed, &kFixedHeight, &kEspTimeout,
                                   &kHmdFovH, &kHmdFovV, &kHmdIpd, &kHmdSep, &kHmdK1, &kHmdK2, &kHmdRenderScale, &kUserHeight, &kHeadHeightOff, &kPredictMs, &kHeadTimeout,
                                   &kArmUpper, &kArmFore, &kArmShX, &kArmShDrop, &kArmBendUp, &kArmBendDown, &kArmSideReach, &kArmTorso, &kArmReach,
                                   &kHeadSmooth, &kHeadPredictMs, &kWorldYaw, &kHeadRoll, &kCtlSmooth, &kArmSimpleLen, &kArmSimpleElbow, &kHandsSmooth }) v.f(*f);
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < 3; ++i) v.f(kOffset[h][i]);
    for (int h = 0; h < HAND_COUNT; ++h) {
        const CtlSettings& k = kCtl[h];
        for (int i = 0; i < 3; ++i) { v.f(k.mpuDz[i]); v.f(k.axGain[i]); v.f(k.axSmooth[i]); v.i(k.axSrc[i]); v.b(k.axInv[i].key, k.axInv[i].def); v.b(k.axEn[i].key, k.axEn[i].def); }
        for (int i = 0; i < 2; ++i) { v.f(k.stickDz[i]); v.b(k.stickInv[i].key, k.stickInv[i].def); }
        for (const FloatSetting* f : { &k.stickGamma, &k.stickEdge, &k.stickSens, &k.trigRamp, &k.gripRamp, &k.clickThr, &k.yawDrift }) v.f(*f);
        v.b(k.stickSwap.key, k.stickSwap.def);
    }
    for (const IntSetting* i : { &kSrcMode, &kProfile, &kStickC[0][0], &kStickC[0][1], &kStickC[1][0], &kStickC[1][1], &kAssign, &kHmdResMode, &kHmdHzMode, &kTrackMode,
                                 &kHmdWinX, &kHmdWinY, &kHmdWinW, &kHmdWinH, &kUdpPort, &kUdpPortL, &kHeadPort, &kEspChannel, &kArmMode }) v.i(*i);
    for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) v.i(kBind[h][i]);
    for (const char* k : { kKeysEnabled, kFollowHmd, kEnabled[0], kEnabled[1], kWebAutostart }) v.b(k, true);
    v.b(kFollowHmdRot, false);
    for (const BoolSetting* b : { &kSwap, &kComboRecenter, &kAutoCenter, &kAutoZeroConn, &kHmdEnabled, &kArmEnabled, &kHideLost, &kForceFs, &kMakePrimary, &kCtlQuat, &kHmdMonitor, &kAntiIdle }) v.b(b->key, b->def);
    for (int h = 0; h < HAND_COUNT; ++h) for (int a = 0; a < A_COUNT; ++a) {
        std::string k = KeySetting(h, a); IntSetting is = { k.c_str(), DefaultKey(h, a), 0, 255 }; v.i(is);
    }
    { IntSetting m = { kKeyMoveMod, kDefMoveMod, 0, 255 }, d = { kKeyDepthMod, kDefDepthMod, 0, 255 }, t = { kKeyToggle, kDefToggle, 0, 255 }; v.i(m); v.i(d); v.i(t); }
    v.s(kHmdMonitorKey); v.s(kCtlIp[0]); v.s(kCtlIp[1]);
}
static std::string SlotPath(int slot, bool create) {
    char base[MAX_PATH] = {};
    DWORD n = GetEnvironmentVariableA("USERPROFILE", base, MAX_PATH);
    std::string dir = (n ? std::string(base) : std::string(".")) + "\\Documents\\StormVR";
    if (create) { CreateDirectoryA((std::string(base) + "\\Documents").c_str(), nullptr); CreateDirectoryA(dir.c_str(), nullptr); }
    return dir + "\\slot" + std::to_string(slot) + ".cfg";
}
static void SaveSlot(int slot) {
    const std::string path = SlotPath(slot, true);
    std::ofstream f(path);
    if (!f) { Msg(L"Não consegui salvar em " + WS(path)); return; }
    f << "# StormVR - configuracoes do driver (tipo<TAB>chave<TAB>valor)\n";
    Visitor v;
    v.f = [&](const FloatSetting& x) { f << "f\t" << x.key << "\t" << GetF(x) << "\n"; };
    v.i = [&](const IntSetting& x) { f << "i\t" << x.key << "\t" << GetI(x.key, x.def) << "\n"; };
    v.b = [&](const char* k, bool d) { f << "b\t" << k << "\t" << (GetB(k, d) ? 1 : 0) << "\n"; };
    v.s = [&](const char* k) { f << "s\t" << k << "\t" << GetS(k) << "\n"; };
    VisitAll(v);
    wchar_t b[64]; swprintf(b, 64, L"Configurações salvas no slot %d.", slot); Msg(b);
}
static void LoadSlot(int slot) {
    const std::string path = SlotPath(slot, false);
    std::ifstream f(path);
    if (!f) { Msg(L"Slot vazio: nada salvo em " + WS(path)); return; }
    std::string line; int n = 0;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        size_t t1 = line.find('\t'), t2 = t1 == std::string::npos ? t1 : line.find('\t', t1 + 1);
        if (t2 == std::string::npos) continue;
        const char type = line[0]; const std::string key = line.substr(t1 + 1, t2 - t1 - 1), val = line.substr(t2 + 1);
        if (type == 'f') { FloatSetting fs = { key.c_str(), 0, 0, 0 }; SetF(fs, (float)atof(val.c_str())); }
        else if (type == 'i') SetI(key.c_str(), atoi(val.c_str()));
        else if (type == 'b') SetB(key.c_str(), val == "1");
        else if (type == 's') SetS(key.c_str(), val);
        ++n;
    }
    int m[HAND_COUNT][P_COUNT]; for (int h = 0; h < HAND_COUNT; ++h) for (int i = 0; i < P_COUNT; ++i) m[h][i] = GetI(kBind[h][i]);
    if (!BindHasTrigger(m)) { ResetMap(); Msg(L"O slot não tinha Trigger mapeado: mapeamento de botões restaurado ao padrão."); }
    else { wchar_t b[96]; swprintf(b, 96, L"Slot %d carregado (%d valores). Perfil/monitor/FOV pedem reiniciar o SteamVR.", slot, n); Msg(b); }
}
static void ResetHead() {
    SetS(kHmdMonitorKey, std::string());
    for (const IntSetting* i : { &kHmdResMode, &kHmdHzMode, &kTrackMode }) SetI(i->key, i->def);
    for (const FloatSetting* f : { &kHmdFovH, &kHmdFovV, &kHmdIpd, &kHmdSep, &kHmdK1, &kHmdK2, &kHmdRenderScale, &kUserHeight, &kHeadHeightOff, &kHeadSmooth, &kHeadPredictMs, &kWorldYaw, &kHeadRoll }) SetF(*f, f->def);
}
static void ResetAll() {
    ResetPositions(); ResetHead();
    SetF(kMoveSpeed, kMoveSpeed.def); SetF(kRotSpeed, kRotSpeed.def); SetF(kFixedHeight, kFixedHeight.def);
    SetB(kKeysEnabled, true); SetB(kFollowHmd, true); SetB(kFollowHmdRot, false); SetB(kWebAutostart, true); SetB(kEnabled[0], true); SetB(kEnabled[1], true);
    for (int h = 0; h < HAND_COUNT; ++h) for (int a = 0; a < A_COUNT; ++a) SetI(KeySetting(h, a).c_str(), DefaultKey(h, a));
    SetI(kKeyMoveMod, kDefMoveMod); SetI(kKeyDepthMod, kDefDepthMod); SetI(kKeyToggle, kDefToggle);
    ResetEspSettings();
}

static void OnDown(int x, int y) {
    g_mouseDown = true; g_drag = -1; g_sbDrag = -1;
    Layout();
    if (g_maxScroll > 0 && x >= W - 24 && y >= kTopY) {                         // barra de rolagem
        g_sbDrag = 1; const int hgt = H - kTopY - 6;
        g_scroll = std::max(0, std::min(g_maxScroll, (int)((long long)(y - kTopY) * g_maxScroll / hgt))); g_dirty = true; return;
    }
    int idx = 0;
    for (const Widget& w : g_w) {
        const bool tab = w.type == W_TAB;
        if (!tab && y < kTopY) { ++idx; continue; }                              // area das abas: so as abas respondem
        const int yy = tab ? y : y - kShift + g_scroll;                           // y do conteudo (desfaz a rolagem)
        if (Hit(w.r, x, yy)) {
            switch (w.type) {
            case W_TAB:   g_page = w.id; g_scroll = 0; g_rebind = -1; g_capture = 0; if (g_page == PG_HEAD) g_mons = ListMonitors(); break;
            case W_CHECK: {
                const bool turnOn = !GetB(w.key.c_str(), w.defB);
                if (turnOn && w.key == kMakePrimary.key && !ConfirmRisky(L"Isto muda o monitor PRINCIPAL do Windows enquanto o SteamVR estiver aberto (restaura ao fechar).\n\nSe o SteamVR fechar de forma abrupta o Windows pode ficar com a tela trocada. Continuar?")) break;
                if (!turnOn && w.key == kHmdEnabled.key && !ConfirmRisky(L"Desligar o headset virtual remove o visor do SteamVR (os controles continuam). Continuar?")) break;
                SetB(w.key.c_str(), turnOn);
            } break;
            case W_SLIDER: if (yy >= w.r.top + 28) { g_drag = idx; SetSliderFromX(w, x, true); } break;
            case W_KEY:   g_rebind = (g_rebind == idx) ? -1 : idx; break;
            case W_CYCLE: {
                int v = GetI(*w.is) + 1; if (v > w.is->mx) v = w.is->mn;
                if (w.is >= &kBind[0][0] && w.is < &kBind[0][0] + HAND_COUNT * P_COUNT) {                          // protege o Trigger
                    const int ix = (int)(w.is - &kBind[0][0]);
                    AssignBind(ix / P_COUNT, ix % P_COUNT, v);
                } else SetI(w.is->key, v);
            } break;
            case W_BUTTON:
                switch (w.id) {
                case BTN_RESET_POS: ResetPositions(); break;
                case BTN_RESET_ALL: if (ConfirmRisky(L"Restaurar TODAS as configuracoes para o padrao? Isso nao pode ser desfeito.")) ResetAll(); break;
                case BTN_RECENTER:  SendCmd(CMD_RECENTER_YAW); break;
                case BTN_ZERO:      SendCmd(CMD_ZERO_ALL); break;
                case BTN_GYRO:      SendCmd(CMD_GYRO_CAL); break;
                case BTN_STICKC:    SendCmd(CMD_STICK_CENTER); break;
                case BTN_RECENTER_HEAD: SendCmd(CMD_RECENTER_HEAD); break;
                case BTN_RECENTER_R:    SendCmd(CMD_RECENTER_YAW_R); Msg(L"Controle direito recentralizado."); break;
                case BTN_RECENTER_L:    SendCmd(CMD_RECENTER_YAW_L); Msg(L"Controle esquerdo recentralizado."); break;
                case BTN_RESET_AXES:    ResetAxes(g_viewHand); break;
                case BTN_RESET_STICK:   ResetStick(g_viewHand); break;
                case BTN_RESET_MAP:     ResetMap(); g_capture = 0; break;
                case BTN_RESET_PROFILE: SetI(kProfile.key, PF_OCULUS); break;
                case BTN_RESET_ARM:     ResetArm(); break;
                case BTN_COPY_AX:    CopyAxes(1 - g_viewHand, g_viewHand); Msg(L"Eixos copiados do outro controle para este."); break;
                case BTN_COPY_STICK: CopyStick(1 - g_viewHand, g_viewHand); Msg(L"Analógico e rampas copiados do outro controle para este."); break;
                case BTN_BINDHAND:  g_bindHand = 1 - g_bindHand; g_capture = 0; break;
                case BTN_RESET_COORD: {
                    for (int i = 0; i < 3; ++i) { SetF(kHeadOff[i], 0.f); SetF(kHeadRot[i], 0.f); SetF(kCtlOff[0][i], 0.f); SetF(kCtlOff[1][i], 0.f); }
                    Msg(L"Coordenadas zeradas."); } break;
                case BTN_RESTARTVR: {
                    static ULONGLONG armed = 0; const ULONGLONG t = GetTickCount64();
                    if (t - armed > 5000) { armed = t; Msg(L"Clique de novo em até 5 s para REINICIAR o SteamVR (o jogo atual será fechado)."); break; }
                    armed = 0; RestartSteamVR(); } break;
                case BTN_CHAPERONE: ResetChaperone(); break;
                case BTN_COPYMAP: {
                    const int from = g_bindHand, to = 1 - g_bindHand;
                    for (int pb = 0; pb < P_COUNT; ++pb) SetI(BindOf(to, pb).key, Mirror(GetI(BindOf(from, pb))));
                    Msg(L"Mapeamento copiado para o outro controle (funções equivalentes da outra mão)."); } break;
                case BTN_VIEWHAND:  g_viewHand = 1 - g_viewHand; break;
                case BTN_FLIP_R:    SendCmd(CMD_FLIP_HAND_R); Msg(L"Pedido enviado ao controle da mão DIREITA (ele grava a nova mão e passa a ser a esquerda)."); break;
                case BTN_FLIP_L:    SendCmd(CMD_FLIP_HAND_L); Msg(L"Pedido enviado ao controle da mão ESQUERDA (ele grava a nova mão e passa a ser a direita)."); break;
                case BTN_PIN_R:     SendCmd(CMD_PIN_R); Msg(L"IP do controle da mão direita fixado (use o modo \"IP fixo\")."); break;
                case BTN_PIN_L:     SendCmd(CMD_PIN_L); Msg(L"IP do controle da mão esquerda fixado (use o modo \"IP fixo\")."); break;
                case BTN_RESTART_UDP:   SendCmd(CMD_RESTART_UDP); Msg(L"Conexão UDP reiniciada."); break;
                case BTN_SAVE1: case BTN_SAVE2: case BTN_SAVE3: SaveSlot(w.id - BTN_SAVE1 + 1); break;
                case BTN_LOAD1: case BTN_LOAD2: case BTN_LOAD3: LoadSlot(w.id - BTN_LOAD1 + 1); break;
                case BTN_MON_REFRESH: g_mons = ListMonitors(); break;
                case BTN_IDENTIFY: IdentifyMonitors(); break;
                case BTN_RESTORE_PRIMARY:
                    if (RestoreDisplayLayout()) { g_mons = ListMonitors(); Msg(L"Monitor principal original restaurado."); }
                    else Msg(L"Nada a restaurar (o layout original não foi alterado pelo StormVR).");
                    break;
                default:
                    if (w.id >= BTN_ROW0 && w.id < BTN_ROW0 + P_COUNT) { g_pickRow = w.id - BTN_ROW0; break; }
                    if (w.id >= BTN_CAT0 && w.id < BTN_CAT0 + 3) { g_pickCat = w.id - BTN_CAT0; break; }
                    if (w.id >= BTN_FN0 && w.id <= BTN_FN0 + O_COUNT) {
                        if (SetBindAny(g_bindHand, g_pickRow, w.id - BTN_FN0) && g_pickRow < P_COUNT - 1) ++g_pickRow;   // avanca para o proximo botao
                        break;
                    }
                    if (w.id >= BTN_CAP0 && w.id < BTN_CAP0 + O_COUNT) {          // entrada do desenho: espera um botao fisico
                        g_capture = (g_capture == w.id - BTN_CAP0) ? 0 : w.id - BTN_CAP0;
                        FetchLive(); g_prevBtn = g_haveLive ? g_L.ctl[g_bindHand].buttons : 0;
                        break;
                    }
                    if (w.id >= BTN_MON0 && w.id < BTN_MON0 + 4 && w.id - BTN_MON0 < (int)g_mons.size()) {
                        const std::string dev = g_mons[w.id - BTN_MON0].dev;
                        SetS(kHmdMonitorKey, GetS(kHmdMonitorKey) == dev ? std::string() : dev);   // clicar de novo desmarca
                    }
                    break;
                }
                break;
            }
            g_dirty = true;
            return;
        }
        ++idx;
    }
    g_rebind = -1; g_dirty = true;
}
static void OnMove(int x, int y) {
    if (g_mouseDown && g_sbDrag >= 0 && g_maxScroll > 0) {
        const int hgt = H - kTopY - 6;
        g_scroll = std::max(0, std::min(g_maxScroll, (int)((long long)(y - kTopY) * g_maxScroll / hgt))); g_dirty = true; return;
    }
    if (g_mouseDown && g_drag >= 0 && g_drag < (int)g_w.size()) SetSliderFromX(g_w[g_drag], x, false);
}
static void OnUp(int x, int) {
    if (g_drag >= 0 && g_drag < (int)g_w.size()) SetSliderFromX(g_w[g_drag], x, true);
    g_mouseDown = false; g_drag = -1; g_sbDrag = -1;
}

// Captura: apos clicar numa entrada do desenho, o proximo botao fisico pressionado no ESP32 e associado a ela
static void PollCapture() {
    if (g_capture <= 0) return;
    if (GetAsyncKeyState(VK_ESCAPE) & 0x8000) { g_capture = 0; g_dirty = true; return; }
    FetchLive();
    if (!g_haveLive) return;
    const uint32_t cur = g_L.ctl[g_bindHand].buttons;
    const uint32_t nb = cur & ~g_prevBtn;
    g_prevBtn = cur;
    if (!nb) return;
    for (int i = 0; i < P_COUNT; ++i)
        if ((nb >> i) & 1u) {
            if (SetBindAny(g_bindHand, i, g_capture)) { wchar_t b[160]; swprintf(b, 160, L"%ls → %ls", kPhysName[i], kOptOut[g_capture].c_str()); Msg(b); }
            g_capture = 0; g_dirty = true; break;
        }
}


static void PollRebind() {
    if (g_rebind < 0) return;
    Layout();
    if (g_rebind >= (int)g_w.size()) { g_rebind = -1; return; }
    const Widget& w = g_w[g_rebind];
    if (GetAsyncKeyState(VK_ESCAPE) & 0x8000) { g_rebind = -1; g_dirty = true; return; }
    int found = -1;
    if (GetAsyncKeyState(VK_BACK) & 0x8000) found = 0;
    else for (int vk = 8; vk < 255; ++vk) {
        if (vk == VK_LBUTTON || vk == VK_RBUTTON || vk == VK_MBUTTON) continue;
        if (vk == VK_LSHIFT || vk == VK_RSHIFT || vk == VK_LCONTROL || vk == VK_RCONTROL || vk == VK_LMENU || vk == VK_RMENU) continue;
        if (vk == VK_BACK || vk == VK_ESCAPE) continue;
        if (GetAsyncKeyState(vk) & 0x8000) { found = vk; break; }
    }
    if (found < 0) return;
    std::string key = w.action >= 0 ? KeySetting(w.hand, w.action) : w.key;
    SetI(key.c_str(), found);
    g_rebind = -1; g_dirty = true;
}

// ------------------------------------------------------------------ main
int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) {
    SetProcessDPIAware();
    HANDLE mutex = CreateMutexA(nullptr, TRUE, "Local\\StormVRConfig");
    if (GetLastError() == ERROR_ALREADY_EXISTS) return 0;

    // Tenta conectar ao SteamVR (o app pode iniciar antes do vrserver ficar pronto)
    vr::EVRInitError err = vr::VRInitError_None;
    bool ok = false;
    for (int i = 0; i < 90 && !ok; ++i) {
        vr::VR_Init(&err, vr::VRApplication_Overlay);
        ok = (err == vr::VRInitError_None);
        if (!ok) Sleep(2000);
    }
    if (!ok) return 1;

    vr::VROverlayHandle_t hMain = vr::k_ulOverlayHandleInvalid, hThumb = vr::k_ulOverlayHandleInvalid;
    if (vr::VROverlay()->CreateDashboardOverlay("stormvr.config", "StormVR", &hMain, &hThumb) != vr::VROverlayError_None) {
        vr::VR_Shutdown();
        return 2;
    }
    char exe[MAX_PATH] = {};
    GetModuleFileNameA(nullptr, exe, MAX_PATH);
    if (char* s = strrchr(exe, '\\')) *s = 0;
    std::string icon = std::string(exe) + "\\icon.png";
    if (GetFileAttributesA(icon.c_str()) != INVALID_FILE_ATTRIBUTES) vr::VROverlay()->SetOverlayFromFile(hThumb, icon.c_str());

    vr::VROverlay()->SetOverlayWidthInMeters(hMain, 2.6f);
    vr::VROverlay()->SetOverlayInputMethod(hMain, vr::VROverlayInputMethod_Mouse);
    vr::HmdVector2_t scale; scale.v[0] = (float)W; scale.v[1] = (float)H;
    vr::VROverlay()->SetOverlayMouseScale(hMain, &scale);

    InitGfx();
    std::string snap = Snapshot();
    ULONGLONG lastSnap = GetTickCount64(), lastLive = lastSnap;
    bool running = true;

    while (running) {
        vr::VREvent_t ev;
        while (vr::VRSystem()->PollNextEvent(&ev, sizeof(ev))) {
            if (ev.eventType == vr::VREvent_Quit) {
                vr::VRSystem()->AcknowledgeQuit_Exiting();
                running = false;
            }
        }
        while (vr::VROverlay()->PollNextOverlayEvent(hMain, &ev, sizeof(ev))) {
            switch (ev.eventType) {
            case vr::VREvent_MouseButtonDown: case vr::VREvent_MouseMove: case vr::VREvent_MouseButtonUp: {
                g_userActiveUntil = GetTickCount64() + 600;
                int x = (int)ev.data.mouse.x;
                int y = (int)(kFlipY ? (H - ev.data.mouse.y) : ev.data.mouse.y);
                if (ev.eventType == vr::VREvent_MouseButtonDown) OnDown(x, y);
                else if (ev.eventType == vr::VREvent_MouseMove) OnMove(x, y);
                else OnUp(x, y);
            } break;
            case vr::VREvent_ScrollDiscrete: case vr::VREvent_ScrollSmooth: {
                g_scroll = std::max(0, std::min(g_maxScroll, g_scroll - (int)(ev.data.scroll.ydelta * 90.f))); g_dirty = true;
            } break;
            case vr::VREvent_OverlayShown: case vr::VREvent_DashboardActivated: g_dirty = true; g_forceUpload = true; break;
            default: break;
            }
        }
        PollRebind();
        PollCapture();
        {   // comandos que vieram do painel WEB (o driver nao os trata): piso e reiniciar o SteamVR
            static LONG lastReset = -1, lastRestart = -1;
            OpenLive();
            if (g_live) {
                const LONG a = InterlockedCompareExchange(&g_live->cmd[CMD_RESET_CHAPERONE], 0, 0), b = InterlockedCompareExchange(&g_live->cmd[CMD_RESTART_STEAMVR], 0, 0);
                if (lastReset < 0) { lastReset = a; lastRestart = b; }
                if (a != lastReset) { lastReset = a; ResetChaperone(); }
                if (b != lastRestart) { lastRestart = b; RestartSteamVR(); }
            }
        }

        ULONGLONG now = GetTickCount64();
        if (now - lastSnap > 400) {                 // mudancas feitas pelo driver (ex.: atalho do teclado)
            lastSnap = now;
            std::string s = Snapshot();
            if (s != snap) { snap = s; g_dirty = true; }
        }
        // Paginas com dados ao vivo (ESP32 / Eixos / Analogico): redesenha ~11x/s com a dashboard aberta
        if ((g_page == PG_ESP || g_page == PG_HEAD || g_page == PG_EIXOS || g_page == PG_ANALOG || g_page == PG_BTN || g_page == PG_ARM || g_page == PG_DIAG || g_page == PG_COORD) && now - lastLive > 100) {
            lastLive = now;
            if (vr::VROverlay()->IsDashboardVisible()) g_dirty = true;
        }
        static bool msgShown = false;
        const bool msgNow = GetTickCount64() < g_msgUntil;
        if (msgShown && !msgNow) g_dirty = true;
        msgShown = msgNow;
        if (g_dirty) {
            g_dirty = false;
            Paint();
            Upload(hMain);
        }
        Sleep(16);
    }
    vr::VR_Shutdown();
    if (mutex) CloseHandle(mutex);
    return 0;
}
