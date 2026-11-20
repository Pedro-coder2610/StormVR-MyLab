// test_host.cpp - "SteamVR de mentira" para testar o driver_stormvr.so sem o SteamVR.
// Carrega o .so, faz o papel do vrserver (settings, propriedades, entradas, lista de dispositivos com um HMD fixo),
// manda pacotes UDP de um ESP32 simulado e confere o que chegou no SteamVR.
//   g++ -std=c++17 -O1 -pthread -I<openvr headers> -I../common -o test_host test_host.cpp -ldl
//   ./test_host ./driver_stormvr.so
#include <openvr_driver.h>
#include <dlfcn.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <chrono>
#include "../common/protocol.h"

using namespace vr;

static std::mutex g_mx;

struct MockSettings : IVRSettings {
    std::map<std::string, double> num;
    std::map<std::string, std::string> str;
    static std::string K(const char* s, const char* k) { return std::string(s) + "/" + k; }
    const char* GetSettingsErrorNameFromEnum(EVRSettingsError) override { return "err"; }
    void SetBool(const char* s, const char* k, bool v, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); num[K(s, k)] = v; if (e) *e = VRSettingsError_None; }
    void SetInt32(const char* s, const char* k, int32_t v, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); num[K(s, k)] = v; if (e) *e = VRSettingsError_None; }
    void SetFloat(const char* s, const char* k, float v, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); num[K(s, k)] = v; if (e) *e = VRSettingsError_None; }
    void SetString(const char* s, const char* k, const char* v, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); str[K(s, k)] = v; if (e) *e = VRSettingsError_None; }
    bool Has(const char* s, const char* k) { auto i = num.find(K(s, k)); return i != num.end(); }
    bool GetBool(const char* s, const char* k, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); auto i = num.find(K(s, k)); if (e) *e = i == num.end() ? VRSettingsError_UnsetSettingHasNoDefault : VRSettingsError_None; return i != num.end() && i->second != 0; }
    int32_t GetInt32(const char* s, const char* k, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); auto i = num.find(K(s, k)); if (e) *e = i == num.end() ? VRSettingsError_UnsetSettingHasNoDefault : VRSettingsError_None; return i == num.end() ? 0 : (int32_t)i->second; }
    float GetFloat(const char* s, const char* k, EVRSettingsError* e) override { std::lock_guard<std::mutex> l(g_mx); auto i = num.find(K(s, k)); if (e) *e = i == num.end() ? VRSettingsError_UnsetSettingHasNoDefault : VRSettingsError_None; return i == num.end() ? 0.f : (float)i->second; }
    void GetString(const char* s, const char* k, char* out, uint32_t n, EVRSettingsError* e) override {
        std::lock_guard<std::mutex> l(g_mx); auto i = str.find(K(s, k));
        if (i == str.end()) { if (n) out[0] = 0; if (e) *e = VRSettingsError_UnsetSettingHasNoDefault; return; }
        snprintf(out, n, "%s", i->second.c_str()); if (e) *e = VRSettingsError_None;
    }
    void RemoveSection(const char*, EVRSettingsError*) override {}
    void RemoveKeyInSection(const char*, const char*, EVRSettingsError*) override {}
};

struct MockProps : IVRProperties {
    std::map<uint64_t, std::map<int, std::string>> strs;
    std::map<uint64_t, std::map<int, int>> ints;
    ETrackedPropertyError ReadPropertyBatch(PropertyContainerHandle_t, PropertyRead_t* b, uint32_t n) override {
        for (uint32_t i = 0; i < n; ++i) b[i].eError = TrackedProp_UnknownProperty;
        return TrackedProp_UnknownProperty;
    }
    ETrackedPropertyError WritePropertyBatch(PropertyContainerHandle_t c, PropertyWrite_t* b, uint32_t n) override {
        std::lock_guard<std::mutex> l(g_mx);
        for (uint32_t i = 0; i < n; ++i) {
            b[i].eError = TrackedProp_Success;
            if (b[i].writeType != PropertyWrite_Set) continue;
            if (b[i].unTag == k_unStringPropertyTag) strs[c][b[i].prop] = std::string((const char*)b[i].pvBuffer);
            else if (b[i].unTag == k_unInt32PropertyTag && b[i].unBufferSize == 4) ints[c][b[i].prop] = *(int32_t*)b[i].pvBuffer;
        }
        return TrackedProp_Success;
    }
    const char* GetPropErrorNameFromEnum(ETrackedPropertyError) override { return "perr"; }
    PropertyContainerHandle_t TrackedDeviceToPropertyContainer(TrackedDeviceIndex_t d) override { return 1000 + d; }
};

struct MockInput : IVRDriverInput {
    uint64_t next = 1;
    std::map<uint64_t, std::string> names;
    std::map<std::string, double> value;
    EVRInputError mk(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h) {
        std::lock_guard<std::mutex> l(g_mx);
        *h = next++; names[*h] = std::to_string(c - 1000) + ":" + n; return VRInputError_None;
    }
    EVRInputError CreateBooleanComponent(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h) override { return mk(c, n, h); }
    EVRInputError UpdateBooleanComponent(VRInputComponentHandle_t h, bool v, double) override { std::lock_guard<std::mutex> l(g_mx); value[names[h]] = v; return VRInputError_None; }
    EVRInputError CreateScalarComponent(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h, EVRScalarType, EVRScalarUnits) override { return mk(c, n, h); }
    EVRInputError UpdateScalarComponent(VRInputComponentHandle_t h, float v, double) override { std::lock_guard<std::mutex> l(g_mx); value[names[h]] = v; return VRInputError_None; }
    EVRInputError CreateHapticComponent(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h) override { return mk(c, n, h); }
    EVRInputError CreateSkeletonComponent(PropertyContainerHandle_t c, const char* n, const char*, const char*, EVRSkeletalTrackingLevel, const VRBoneTransform_t*, uint32_t, VRInputComponentHandle_t* h) override { return mk(c, n, h); }
    EVRInputError UpdateSkeletonComponent(VRInputComponentHandle_t, EVRSkeletalMotionRange, const VRBoneTransform_t*, uint32_t) override { return VRInputError_None; }
    EVRInputError CreatePoseComponent(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h) override { return mk(c, n, h); }
    EVRInputError UpdatePoseComponent(VRInputComponentHandle_t, const HmdMatrix34_t*, double) override { return VRInputError_None; }
    EVRInputError CreateEyeTrackingComponent(PropertyContainerHandle_t c, const char* n, VRInputComponentHandle_t* h) override { return mk(c, n, h); }
    EVRInputError UpdateEyeTrackingComponent(VRInputComponentHandle_t, const VREyeTrackingData_t*, double) override { return VRInputError_None; }
};

struct MockLog : IVRDriverLog {
    void Log(const char* m) override { std::lock_guard<std::mutex> l(g_mx); fputs(m, stdout); size_t n = strlen(m); if (!n || m[n - 1] != '\n') fputc('\n', stdout); }
};

struct MockHost : IVRServerDriverHost {
    std::map<uint32_t, std::string> serial;
    std::map<uint32_t, DriverPose_t> pose;
    std::map<uint32_t, int> poseCount;
    uint32_t next = 1;
    double headYaw = 0.5;                                   // rad: o "headset do DroidDeck" olhando um pouco para a esquerda
    bool TrackedDeviceAdded(const char* s, ETrackedDeviceClass, ITrackedDeviceServerDriver* d) override {
        uint32_t id; { std::lock_guard<std::mutex> l(g_mx); id = next++; serial[id] = s; }
        d->Activate(id); return true;
    }
    void TrackedDevicePoseUpdated(uint32_t i, const DriverPose_t& p, uint32_t) override { std::lock_guard<std::mutex> l(g_mx); pose[i] = p; poseCount[i]++; }
    void VsyncEvent(double) override {}
    void VendorSpecificEvent(uint32_t, EVREventType, const VREvent_Data_t&, double) override {}
    bool IsExiting() override { return false; }
    bool PollNextEvent(VREvent_t*, uint32_t) override { return false; }
    void GetRawTrackedDevicePoses(float, TrackedDevicePose_t* out, uint32_t n) override {
        std::memset(out, 0, sizeof(TrackedDevicePose_t) * n);
        TrackedDevicePose_t& h = out[0];                    // o HMD (de outro driver) esta sempre no indice 0
        h.bPoseIsValid = true; h.bDeviceIsConnected = true; h.eTrackingResult = TrackingResult_Running_OK;
        const double c = std::cos(headYaw), s = std::sin(headYaw);
        float m[3][4] = { { (float)c, 0, (float)s, 0.f }, { 0, 1, 0, 1.7f }, { (float)-s, 0, (float)c, 0.f } };
        std::memcpy(h.mDeviceToAbsoluteTracking.m, m, sizeof(m));
    }
    void RequestRestart(const char*, const char*, const char*, const char*) override {}
    uint32_t GetFrameTimings(Compositor_FrameTiming*, uint32_t) override { return 0; }
    void SetDisplayEyeToHead(uint32_t, const HmdMatrix34_t&, const HmdMatrix34_t&) override {}
    void SetDisplayProjectionRaw(uint32_t, const HmdRect2_t&, const HmdRect2_t&) override {}
    void SetRecommendedRenderTargetSize(uint32_t, uint32_t, uint32_t) override {}
};

struct MockMgr : IVRDriverManager {
    uint32_t GetDriverCount() const override { return 1; }
    uint32_t GetDriverName(DriverId_t, char* b, uint32_t n) override { snprintf(b, n, "stormvr"); return 8; }
    DriverHandle_t GetDriverHandle(const char*) override { return 1; }
    bool IsEnabled(DriverId_t) const override { return true; }
};
struct MockRes : IVRResources {
    uint32_t LoadSharedResource(const char*, char*, uint32_t) override { return 0; }
    uint32_t GetResourceFullPath(const char*, const char*, char*, uint32_t) override { return 0; }
};

struct MockCtx : IVRDriverContext {
    MockMgr mgr; MockRes res;
    MockSettings settings; MockProps props; MockInput input; MockLog log; MockHost host;
    void* GetGenericInterface(const char* n, EVRInitError* e) override {
        if (e) *e = VRInitError_None;
        if (!strcmp(n, IVRSettings_Version)) return &settings;
        if (!strcmp(n, IVRProperties_Version)) return &props;
        if (!strcmp(n, IVRDriverInput_Version)) return &input;
        if (!strcmp(n, IVRDriverLog_Version)) return &log;
        if (!strcmp(n, IVRServerDriverHost_Version)) return &host;
        if (!strcmp(n, IVRDriverManager_Version)) return &mgr;
        if (!strcmp(n, IVRResources_Version)) return &res;
        if (e) *e = VRInitError_Init_InterfaceNotFound;
        return nullptr;
    }
    DriverHandle_t GetDriverHandle() override { return 1; }
};

static int g_fail = 0;
#define CHECK(cond, ...) do { if (cond) printf("  ok    "); else { printf("  FALHOU "); ++g_fail; } printf(__VA_ARGS__); printf("\n"); } while (0)

static double yawOfPose(const DriverPose_t& p) {
    const HmdQuaternion_t& q = p.qRotation;
    return std::atan2(2 * (q.x * q.z + q.w * q.y), 1 - 2 * (q.x * q.x + q.y * q.y));
}

int main(int argc, char** argv) {
    const char* so = argc > 1 ? argv[1] : "./driver_stormvr.so";
    void* lib = dlopen(so, RTLD_NOW);
    if (!lib) { printf("dlopen: %s\n", dlerror()); return 2; }
    typedef void* (*Factory)(const char*, int*);
    Factory f = (Factory)dlsym(lib, "HmdDriverFactory");
    CHECK(f != nullptr, "HmdDriverFactory exportada");
    int rc = 0;
    auto* prov = (IServerTrackedDeviceProvider*)f(IServerTrackedDeviceProvider_Version, &rc);
    CHECK(prov != nullptr, "provider devolvido");
    CHECK(f("interface_que_nao_existe", &rc) == nullptr && rc == VRInitError_Init_InterfaceNotFound, "interface desconhecida recusada");

    MockCtx ctx;
    ctx.settings.num["driver_stormvr/web_autostart"] = getenv("STORM_TEST_WEB") ? 1 : 0;      // por padrao o teste NAO sobe o painel web
    if (const char* pr = getenv("STORM_TEST_PROFILE")) ctx.settings.num["driver_stormvr/ctl_profile"] = atoi(pr);
    EVRInitError ie = prov->Init(&ctx);
    CHECK(ie == VRInitError_None, "Init() = %d", (int)ie);
    CHECK(ctx.host.serial.size() == 2, "2 dispositivos criados (%zu)", ctx.host.serial.size());
    uint32_t idR = 0, idL = 0;
    for (auto& kv : ctx.host.serial) { if (kv.second == "STORM-CTRL-R") idR = kv.first; if (kv.second == "STORM-CTRL-L") idL = kv.first; }
    CHECK(idR && idL, "seriais STORM-CTRL-R/L (ids %u/%u)", idR, idL);
    if (idR) {
        auto& s = ctx.props.strs[1000 + idR];
        printf("  info  perfil direito: tipo=%s  entrada=%s  modelo=%s\n", s[Prop_ControllerType_String].c_str(), s[Prop_InputProfilePath_String].c_str(), s[Prop_RenderModelName_String].c_str());
        CHECK(ctx.props.ints[1000 + idR][Prop_ControllerRoleHint_Int32] == TrackedControllerRole_RightHand, "papel = mao direita");
    }
    CHECK(ctx.host.poseCount[0] == 0, "driver NAO cria headset (indice 0 livre)");
    auto portOpen = [](int port) {
        int s = socket(AF_INET, SOCK_STREAM, 0); sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_port = htons(port); inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
        timeval tv = { 0, 300000 }; setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        const bool ok = connect(s, (sockaddr*)&a, sizeof(a)) == 0; close(s); return ok;
    };
    if (getenv("STORM_TEST_WEB")) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2500));
        CHECK(portOpen(8787), "painel WEB subiu junto com o driver (porta 8787)");
    }

    // ---- ESP32 simulado -> UDP 5555
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    sockaddr_in to = {}; to.sin_family = AF_INET; to.sin_port = htons(KBT_PORT_PC); inet_pton(AF_INET, "127.0.0.1", &to.sin_addr);
    uint16_t seq = 0; uint32_t up = 1000;
    auto sendPkt = [&](double yawDeg, uint16_t buttons, uint16_t jx, uint16_t jy) {
        KbtPacketQ p = {}; p.magic = KBT_MAGIC_Q; p.seq = ++seq; p.buttons = buttons; p.joyX = jx; p.joyY = jy;
        const double a = yawDeg * M_PI / 180.0 / 2;                    // giro em Z (mundo do MPU: Z = cima)
        p.quat[0] = (float)std::cos(a); p.quat[1] = 0; p.quat[2] = 0; p.quat[3] = (float)std::sin(a);
        p.uptimeMs = up; up += 20; p.rssi = -50; p.flags = KBT_FLAG_CALIBRATED;
        sendto(sock, &p, sizeof(p), 0, (sockaddr*)&to, sizeof(to));
    };
    auto frames = [&](int n) { for (int i = 0; i < n; ++i) prov->RunFrame(); };

    printf("-- parado, sem botoes (1,5 s)\n");
    for (int i = 0; i < 75; ++i) { sendPkt(0, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
    DriverPose_t p0; { std::lock_guard<std::mutex> l(g_mx); p0 = ctx.host.pose[idR]; }
    const double y0 = yawOfPose(p0);
    CHECK(ctx.host.poseCount[idR] > 20, "pose do controle direito atualizada (%d vezes)", ctx.host.poseCount[idR]);
    CHECK(p0.poseIsValid && p0.deviceIsConnected, "pose valida e conectada");
    printf("  info  posicao = (%.3f, %.3f, %.3f)  yaw = %.1f graus (headset do mock: y=1.7 m)\n", p0.vecPosition[0], p0.vecPosition[1], p0.vecPosition[2], y0 * 180 / M_PI);
    CHECK(std::fabs(p0.vecPosition[1] - 1.7) < 0.6, "controle perto da altura do headset (y=%.2f)", p0.vecPosition[1]);

    printf("-- trigger + A + grip + analogico para cima/direita (1 s)\n");
    for (int i = 0; i < 50; ++i) { sendPkt(0, KBT_BTN_TRIGGER | KBT_BTN_A | KBT_BTN_GRIP, 4095, 0); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
    {
        std::lock_guard<std::mutex> l(g_mx);
        auto& v = ctx.input.value; const std::string r = std::to_string(idR) + ":";
        for (auto& kv : v) if (kv.first.rfind(r, 0) == 0) printf("  info  %-34s = %.2f\n", kv.first.c_str(), kv.second);
        CHECK(v[r + "/input/trigger/click"] == 1.0, "trigger/click ligado");
        CHECK(v[r + "/input/trigger/value"] > 0.9, "trigger/value ~1 (%.2f)", v[r + "/input/trigger/value"]);
        CHECK(v[r + "/input/a/click"] == 1.0, "botao A ligado");
        const double jx = v[r + "/input/joystick/x"], jy = v[r + "/input/joystick/y"];
        CHECK(std::fabs(jx) > 0.5 || std::fabs(jy) > 0.5, "joystick mexeu (x=%.2f y=%.2f)", jx, jy);
    }

    printf("-- giro de 90 graus no yaw do ESP32 (1 s)\n");
    for (int i = 0; i < 50; ++i) { sendPkt(90.0 * i / 49.0, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
    for (int i = 0; i < 25; ++i) { sendPkt(90, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); }
    DriverPose_t p1; { std::lock_guard<std::mutex> l(g_mx); p1 = ctx.host.pose[idR]; }
    double dy = (yawOfPose(p1) - y0) * 180 / M_PI; while (dy > 180) dy -= 360; while (dy < -180) dy += 360;
    CHECK(std::fabs(std::fabs(dy) - 90.0) < 8.0, "yaw do controle girou %.1f graus (esperado +-90)", dy);

    printf("-- celular REINICIADO: silencio de 1,3 s e o contador seq volta a 0 (o relogio do remetente continua)\n");
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(1300)); frames(3);
        seq = 0;                                                       // remetente reiniciou
        for (int i = 0; i < 25; ++i) { sendPkt(0, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
        DriverPose_t q0; { std::lock_guard<std::mutex> l(g_mx); q0 = ctx.host.pose[idR]; }
        for (int i = 0; i < 50; ++i) { sendPkt(90.0 * i / 49.0, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
        for (int i = 0; i < 25; ++i) { sendPkt(90, 0, 2048, 2048); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
        DriverPose_t q1; { std::lock_guard<std::mutex> l(g_mx); q1 = ctx.host.pose[idR]; }
        double d2 = (yawOfPose(q1) - yawOfPose(q0)) * 180 / M_PI; while (d2 > 180) d2 -= 360; while (d2 < -180) d2 += 360;
        CHECK(std::fabs(std::fabs(d2) - 90.0) < 10.0, "depois do reinicio o controle VOLTOU a responder (girou %.1f graus)", d2);
    }

    printf("-- 6DoF do celular-controle: KbtPacketP (posicao em metros: frente, esquerda, cima)\n");
    {
        auto sendPos = [&](float f, float l, float u) {
            KbtPacketP pp = {}; KbtPacket& b = pp.base; b.magic = KBT_MAGIC; b.seq = ++seq; b.joyX = 2048; b.joyY = 2048;
            b.uptimeMs = up; up += 20; b.flags = KBT_FLAG_POS | (1u << 2); pp.pos[0] = f; pp.pos[1] = l; pp.pos[2] = u;
            sendto(sock, &pp, sizeof(pp), 0, (sockaddr*)&to, sizeof(to));
        };
        for (int i = 0; i < 90; ++i) { sendPos(0.f, 0.f, 0.f); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
        DriverPose_t a0; { std::lock_guard<std::mutex> l(g_mx); a0 = ctx.host.pose[idR]; }
        for (int i = 0; i < 150; ++i) { sendPos(0.30f, 0.f, 0.f); std::this_thread::sleep_for(std::chrono::milliseconds(20)); frames(1); }
        DriverPose_t a1; { std::lock_guard<std::mutex> l(g_mx); a1 = ctx.host.pose[idR]; }
        const double mv = std::sqrt(std::pow(a1.vecPosition[0] - a0.vecPosition[0], 2) + std::pow(a1.vecPosition[1] - a0.vecPosition[1], 2) + std::pow(a1.vecPosition[2] - a0.vecPosition[2], 2));
        printf("  info  deslocamento da mao com +30 cm a frente: dx=%.3f dy=%.3f dz=%.3f (|d|=%.3f)\n", a1.vecPosition[0] - a0.vecPosition[0], a1.vecPosition[1] - a0.vecPosition[1], a1.vecPosition[2] - a0.vecPosition[2], mv);
        CHECK(std::fabs(mv - 0.30) < 0.08, "a posicao 6DoF move a mao ~30 cm");
    }

    printf("-- painel: external.ini -> VRSettings, keys.ini -> state.ini\n");
    {
        std::string dir = getenv("STORMVR_DIR") ? getenv("STORMVR_DIR") : "";
        FILE* fx = fopen((dir + "/external.ini").c_str(), "w"); if (!fx) { printf("  FALHOU nao abri external.ini em %s\n", dir.c_str()); return 1; } fputs("f:move_speed=0.8\nb:keys_enabled=0\n", fx); fclose(fx);
        FILE* fk = fopen((dir + "/keys.ini").c_str(), "w"); fputs("f:move_speed\nb:keys_enabled\n", fk); fclose(fk);
        std::this_thread::sleep_for(std::chrono::milliseconds(1400)); frames(2);
        std::lock_guard<std::mutex> l(g_mx);
        CHECK(std::fabs(ctx.settings.num["driver_stormvr/move_speed"] - 0.8) < 1e-4, "external.ini aplicou move_speed=0.8");
        FILE* fs = fopen((dir + "/state.ini").c_str(), "r"); char buf[256] = {}; if (fs) { fread(buf, 1, sizeof(buf) - 1, fs); fclose(fs); }
        printf("  info  state.ini: %s\n", buf);
        CHECK(strstr(buf, "f:move_speed=0.8") != nullptr, "state.ini publicou move_speed");
    }

    printf("-- memoria compartilhada (painel)\n");
    {
        char path[128]; snprintf(path, sizeof(path), "/dev/shm/stormvr-live-%u", (unsigned)getuid());
        FILE* fl = fopen(path, "rb"); CHECK(fl != nullptr, "arquivo %s existe", path); if (fl) fclose(fl);
    }

    prov->Cleanup();
    if (getenv("STORM_TEST_WEB")) { std::this_thread::sleep_for(std::chrono::milliseconds(800)); CHECK(!portOpen(8787), "painel WEB encerrado junto com o driver"); }
    close(sock);
    dlclose(lib);
    printf("\n%s (%d falhas)\n", g_fail ? "RESULTADO: COM FALHAS" : "RESULTADO: TUDO OK", g_fail);
    return g_fail ? 1 : 0;
}
