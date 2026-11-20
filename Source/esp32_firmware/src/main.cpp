// Controle VR com ESP32 + MPU6050: le botoes, analogico e orientacao e envia
//   - por Wi-Fi UDP para o driver StormVR (SteamVR) e
//   - por Bluetooth LE para o app StormVR no celular (modo Android stand-alone).
// Veja include/config.h para pinos, Wi-Fi e Bluetooth. Dois controles (direita/esquerda) usam o MESMO firmware:
// a mao de cada um fica gravada na memoria do ESP32 (segure GRIP + TRIGGER ao ligar para trocar).
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <Preferences.h>
#include <esp_system.h>
#include "config.h"
#include "protocol.h"
#include "imu_fusion.h"
#if BLE_ENABLED
#include <BLEDevice.h>      // BLE nativo do core Arduino-ESP32 (sem biblioteca externa)
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#endif

static WiFiUDP udp;
static Madgwick filt;
static IPAddress dest;
static Preferences prefs;

// ---------------------------------------------------------------- estado de boot / mao / modo seguro
static uint8_t crashCount = 0;                       // reinicios seguidos por erro (gravado na memoria interna: sobrevive a reset e a falta de energia)
static bool bleBlocked = false;                      // true = Bluetooth desligado por ter travado antes (veja bleInitGuard)
static bool handLeft = HAND_LEFT_DEFAULT != 0;       // mao deste controle (gravada na memoria do ESP32)
static bool safeMode = false;                        // true = sem Bluetooth neste boot (protecao contra travamento)
static bool bootConfirmed = false;
static bool gamepadMode = false;                     // alternado por X+Y+B (GAMEPAD_HOLD_MS); enviado em cada pacote (KBT_FLAG_GAMEPAD)
static uint32_t gpChordSince = 0, ledFlashUntil = 0; static bool gpArmed = true;

static const char* resetName(esp_reset_reason_t r) {
    switch (r) {
    case ESP_RST_POWERON:   return "energia ligada";
    case ESP_RST_EXT:       return "pino EN / reset externo";
    case ESP_RST_SW:        return "reinicio por software";
    case ESP_RST_PANIC:     return "ERRO (panic/excecao)";
    case ESP_RST_INT_WDT:   return "ERRO (watchdog de interrupcao)";
    case ESP_RST_TASK_WDT:  return "ERRO (watchdog de tarefa)";
    case ESP_RST_WDT:       return "ERRO (watchdog)";
    case ESP_RST_DEEPSLEEP: return "saiu do deep sleep";
    case ESP_RST_BROWNOUT:  return "QUEDA DE TENSAO (brownout): fonte/USB fraco?";
    default:                return "outro";
    }
}

#define LOG(...) do { if (SERIAL_DEBUG) { Serial.printf(__VA_ARGS__); } } while (0)

// ---------------------------------------------------------------- botoes
struct Btn { uint8_t pin; uint16_t mask; bool state; bool raw; uint32_t since; };
static Btn btns[] = {
    { PIN_GRIP,    KBT_BTN_GRIP,    false, false, 0 },
    { PIN_TRIGGER, KBT_BTN_TRIGGER, false, false, 0 },
    { PIN_A,       KBT_BTN_A,       false, false, 0 },
    { PIN_B,       KBT_BTN_B,       false, false, 0 },
    { PIN_X,       KBT_BTN_X,       false, false, 0 },
    { PIN_Y,       KBT_BTN_Y,       false, false, 0 },
    { PIN_START,   KBT_BTN_START,   false, false, 0 },
    { PIN_L3,      KBT_BTN_L3,      false, false, 0 },
    { PIN_R2,      KBT_BTN_R2,      false, false, 0 },      // D4 (GPIO4)
};

static void buttonsInit() {
    for (auto& b : btns) pinMode(b.pin, BTN_ACTIVE_LOW ? INPUT_PULLUP : INPUT);
}
static bool pinPressed(uint8_t pin) { return digitalRead(pin) == (BTN_ACTIVE_LOW ? LOW : HIGH); }
static uint16_t buttonsRead() {
    uint32_t now = millis();
    uint16_t mask = 0;
    for (auto& b : btns) {
        bool r = pinPressed(b.pin);
        if (r != b.raw) { b.raw = r; b.since = now; }
        if (b.raw != b.state && now - b.since >= BTN_DEBOUNCE_MS) b.state = b.raw;
        if (b.state) mask |= b.mask;
    }
    return mask;
}

// ---------------------------------------------------------------- MPU6050
static bool mpuWrite(uint8_t reg, uint8_t v) {
    Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.write(v);
    return Wire.endTransmission() == 0;
}
static bool wireStarted = false;
static bool mpuInit() {
    if (!wireStarted) {
        Wire.begin(PIN_SDA, PIN_SCL);
        Wire.setClock(400000);
        Wire.setTimeOut(10);               // ms: um barramento I2C travado nao pode congelar o loop (watchdog)
        wireStarted = true;
    }
    Wire.beginTransmission(MPU_ADDR);
    if (Wire.endTransmission() != 0) return false;
    mpuWrite(0x6B, 0x01);   // acorda, relogio = PLL do giroscopio X
    delay(50);
    mpuWrite(0x1A, 0x03);   // filtro digital ~44 Hz
    mpuWrite(0x19, 0x00);   // divisor de amostragem (1 kHz)
    mpuWrite(0x1B, 0x10);   // giroscopio +-1000 dps (32.8 LSB/(dps))
    mpuWrite(0x1C, 0x00);   // acelerometro +-2 g (16384 LSB/g)
    return true;
}
static bool mpuRead(float a[3], float g[3]) {   // a em g, g em graus/s (eixos do sensor)
    Wire.beginTransmission(MPU_ADDR); Wire.write(0x3B);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((int)MPU_ADDR, 14) != 14) return false;
    int16_t v[7];
    for (int i = 0; i < 7; ++i) { uint8_t h = Wire.read(), l = Wire.read(); v[i] = (int16_t)((h << 8) | l); }
    a[0] = v[0] / 16384.0f; a[1] = v[1] / 16384.0f; a[2] = v[2] / 16384.0f;
    g[0] = v[4] / 32.8f;    g[1] = v[5] / 32.8f;    g[2] = v[6] / 32.8f;
    return true;
}
static inline float pick(int m, const float v[3]) {
    float r = v[(m < 0 ? -m : m) - 1];
    return m < 0 ? -r : r;
}

// ---------------------------------------------------------------- estado
static float gyroBias[3] = { 0, 0, 0 };
static bool  calibrating = true;
static int   calTarget = CAL_SAMPLES_BOOT, calCount = 0;
static double calSum[3] = {};
static uint32_t warmupUntil = 0;
static uint16_t seq = 0;
static bool mpuError = false, calibratedOnce = false;
static int mpuFails = 0;

static void startCalibration(int samples) {
    calibrating = true; calTarget = samples; calCount = 0;
    calSum[0] = calSum[1] = calSum[2] = 0;
}

static void ledSet(bool on) { if (PIN_LED >= 0) digitalWrite(PIN_LED, on ? HIGH : LOW); }

static void updateDest() {
    IPAddress ip;
    if (strlen(PC_IP) > 0 && ip.fromString(PC_IP)) dest = ip;
    else dest = WiFi.broadcastIP();
}

static void wifiBegin() {
    WiFi.persistent(false);                              // nao grava a rede na flash a cada boot
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(handLeft ? "StormVR-L" : "StormVR-R");
    // Com Bluetooth ligado, o Wi-Fi precisa poder "dormir" entre os beacons (coexistencia); o ENVIO nao e atrasado por isso.
    // Sem Bluetooth, desliga a economia de energia para o menor atraso possivel.
    WiFi.setSleep((BLE_ENABLED && !safeMode) ? WIFI_PS_MIN_MODEM : WIFI_PS_NONE);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
}

// ---------------------------------------------------------------- Bluetooth LE
// O ESP32 e o periferico (GATT): o celular conecta, assina as notificacoes e recebe KbtBlePacket (14 bytes).
#if BLE_ENABLED
static BLEServer* bleServer = nullptr;
static BLECharacteristic* bleData = nullptr;
static bool bleReady = false;
static volatile int bleConns = 0;
#endif
static volatile uint8_t pendingCmd = 0, pendingArg = 0;     // comando vindo do Bluetooth (tratado no loop)
static uint8_t chMode = KBT_CH_AUTO;                         // canal escolhido (menu do SteamVR ou app do celular)
#if BLE_ENABLED
static uint8_t bleSeq = 0;

class BleServerCb : public BLEServerCallbacks {
    void onConnect(BLEServer* s) override { ++bleConns; LOG("[ble] celular conectado\n"); }
    void onDisconnect(BLEServer* s) override {
        if (bleConns > 0) --bleConns;
        LOG("[ble] celular desconectado\n");
        BLEDevice::startAdvertising();                       // volta a anunciar para reconectar
    }
};
class BleCmdCb : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        std::string v = c->getValue();
        if (v.size() >= 1) { pendingArg = v.size() >= 2 ? (uint8_t)v[1] : 0; pendingCmd = (uint8_t)v[0]; }
    }
};
static void bleInit() {
    std::string name = std::string(KBT_BLE_NAME) + (handLeft ? "-L" : "-R");
    BLEDevice::init(name);
    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new BleServerCb());
    BLEService* svc = bleServer->createService(KBT_BLE_SERVICE_UUID);
    bleData = svc->createCharacteristic(KBT_BLE_DATA_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
    bleData->addDescriptor(new BLE2902());
    BLECharacteristic* cmd = svc->createCharacteristic(KBT_BLE_CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    cmd->setCallbacks(new BleCmdCb());
    svc->start();
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(KBT_BLE_SERVICE_UUID);
    adv->setScanResponse(true);
    BLEDevice::startAdvertising();
    bleReady = true;
    LOG("[ble] anunciando como \"%s\"\n", name.c_str());
}
static inline bool bleLinked() { return bleReady && bleConns > 0; }
#else
static inline bool bleLinked() { return false; }
#endif

// ---------------------------------------------------------------- mao (direita / esquerda) gravada no ESP32
static void saveHand(bool left) {
    handLeft = left;
    prefs.putUChar("hand", left ? 1 : 0);
    LOG("[mao] este controle agora e a mao %s (gravado; reinicie para o nome Bluetooth/Wi-Fi atualizar)\n", left ? "ESQUERDA" : "DIREITA");
}
static void blinkHand() {                                    // 1 piscada = direita, 2 = esquerda
    if (PIN_LED < 0) return;
    for (int i = 0; i < (handLeft ? 2 : 1); ++i) { ledSet(true); delay(180); ledSet(false); delay(180); }
}

void setup() {
    Serial.begin(115200);
    delay(300);
    const esp_reset_reason_t rr = esp_reset_reason();
    const bool crashed = rr == ESP_RST_PANIC || rr == ESP_RST_INT_WDT || rr == ESP_RST_TASK_WDT || rr == ESP_RST_WDT || rr == ESP_RST_BROWNOUT;

    // Memoria interna (NVS): mao, contador de erros e a trava do Bluetooth
    const bool nvsOk = prefs.begin("stormvr", false);
    if (nvsOk) {
        const String build = String(__DATE__) + " " + String(__TIME__);
        if (prefs.getString("build", "") != build) {               // firmware novo gravado: tenta o Bluetooth de novo
            prefs.putString("build", build);
            prefs.putUChar("bleoff", 0); prefs.putUChar("bletry", 0); prefs.putUChar("crash", 0);
        }
        crashCount = prefs.getUChar("crash", 0);
        if (prefs.getUChar("bletry", 0)) {                          // ficou marcado "iniciando Bluetooth" => o boot anterior travou la dentro
            prefs.putUChar("bleoff", 1); prefs.putUChar("bletry", 0);
        }
        bleBlocked = prefs.getUChar("bleoff", 0) != 0;
    }
    if (rr == ESP_RST_POWERON || rr == ESP_RST_EXT) { /* energia/botao EN: mantem o contador (o crash pode ser repetivel) */ }
    if (crashed) { if (crashCount < 250) ++crashCount; if (nvsOk) prefs.putUChar("crash", crashCount); }
    safeMode = bleBlocked || crashCount >= SAFE_MODE_AFTER_CRASHES;
    LOG("\n\n[controle-vr] iniciando | motivo do reset: %s | erros seguidos: %u | heap livre %u\n",
        resetName(rr), (unsigned)crashCount, (unsigned)ESP.getFreeHeap());
    if (bleBlocked) LOG("[boot] O Bluetooth travou o ESP32 antes: ficara DESLIGADO (so Wi-Fi). Para tentar de novo: segure A + B ao ligar, ou grave um firmware novo.\n");
    else if (safeMode) LOG("[boot] MODO SEGURO: Bluetooth DESLIGADO neste boot (o ESP32 reiniciou por erro %u vezes).\n", (unsigned)crashCount);

    if (PIN_LED >= 0) pinMode(PIN_LED, OUTPUT);
    buttonsInit();
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);
    pinMode(PIN_JOY_X, INPUT);
    pinMode(PIN_JOY_Y, INPUT);

    // mao gravada na memoria do ESP32 (a primeira vez usa HAND_LEFT_DEFAULT)
    if (nvsOk) {
        if (prefs.isKey("hand")) handLeft = prefs.getUChar("hand", HAND_LEFT_DEFAULT) != 0;
        else prefs.putUChar("hand", HAND_LEFT_DEFAULT ? 1 : 0);
    } else LOG("[nvs] memoria interna indisponivel: a mao vale o padrao de config.h\n");
    // A + B segurados ao ligar = libera o Bluetooth de novo (depois de ele ter travado)
    if (pinPressed(PIN_A) && pinPressed(PIN_B)) {
        uint32_t t0 = millis();
        while (pinPressed(PIN_A) && pinPressed(PIN_B) && millis() - t0 < 1000) delay(10);
        if (millis() - t0 >= 1000 && nvsOk) { prefs.putUChar("bleoff", 0); prefs.putUChar("crash", 0); bleBlocked = false; crashCount = 0; safeMode = false; LOG("[boot] Bluetooth liberado de novo\n"); }
    }
    // GRIP + TRIGGER segurados ao ligar = troca a mao (direita <-> esquerda) e grava
    if (pinPressed(PIN_GRIP) && pinPressed(PIN_TRIGGER)) {
        uint32_t t0 = millis();
        while (pinPressed(PIN_GRIP) && pinPressed(PIN_TRIGGER) && millis() - t0 < HAND_SWAP_HOLD_MS) delay(10);
        if (millis() - t0 >= HAND_SWAP_HOLD_MS) { saveHand(!handLeft); }
    }
    LOG("[mao] este controle: %s\n", handLeft ? "ESQUERDA" : "DIREITA");
    blinkHand();

    bool ok = mpuInit();
    mpuError = !ok;
    LOG("%s\n", ok ? "[mpu] ok" : "[mpu] NAO ENCONTRADO - confira SDA=21 SCL=22 3V3 GND (os botoes continuam funcionando)");
#if BLE_ENABLED
    if (!safeMode) {
        LOG("[ble] iniciando...\n"); Serial.flush();
        if (nvsOk) prefs.putUChar("bletry", 1);                 // se travar aqui dentro, o proximo boot sobe sem Bluetooth
        bleInit();
        if (nvsOk) prefs.putUChar("bletry", 0);
    }
#endif
    LOG("[wifi] iniciando (%s)...\n", WIFI_SSID);
    wifiBegin();
    udp.begin(KBT_PORT_ESP);
    filt.beta = 2.0f;                  // convergencia rapida no inicio
    startCalibration(CAL_SAMPLES_BOOT);
    LOG("[boot] pronto | heap livre %u\n", (unsigned)ESP.getFreeHeap());
}

void loop() {
    static uint32_t lastUs = micros(), lastSendUs = 0, lastWifiTry = 0, lastLog = 0, lastBlink = 0;
    static bool wasConnected = false, blink = false;
    static float roll = 0, pitch = 0, yaw = 0, gyroOut[3] = {};
    static uint32_t nSent = 0;

    // --- Wi-Fi
    bool connected = WiFi.status() == WL_CONNECTED;
    uint32_t nowMs = millis();
    if (!bootConfirmed && nowMs > BOOT_OK_AFTER_MS) { bootConfirmed = true; if (crashCount) { crashCount = 0; prefs.putUChar("crash", 0); } LOG("[boot] estavel ha %u s\n", (unsigned)(BOOT_OK_AFTER_MS / 1000)); }
    if (connected && !wasConnected) {
        updateDest();
        LOG("[wifi] conectado, IP %s -> destino %s:%d\n", WiFi.localIP().toString().c_str(), dest.toString().c_str(), KBT_PORT_PC);
    }
    wasConnected = connected;
    if (!connected && nowMs - lastWifiTry > 8000) { lastWifiTry = nowMs; WiFi.reconnect(); }
    if (ledFlashUntil && nowMs < ledFlashUntil) ledSet(((nowMs / 80) & 1) != 0);       // alternou VR <-> gamepad: pisca rapido por 0,5 s
    else if (connected) ledSet(!safeMode || ((nowMs / 600) & 1));      // modo seguro: LED piscando devagar mesmo conectado
    else if (nowMs - lastBlink > 250) { lastBlink = nowMs; blink = !blink; ledSet(blink); }

    // --- comandos vindos do celular (Bluetooth)
    if (pendingCmd) {
        const uint8_t c0 = pendingCmd, a0 = pendingArg; pendingCmd = 0;
        if (c0 == KBT_CMD_CALIBRATE_GYRO) startCalibration(CAL_SAMPLES_CMD);
        else if (c0 == KBT_CMD_SET_MODE && a0 <= KBT_CH_ANDROID) chMode = a0;
        else if (c0 == KBT_CMD_SET_HAND && a0 <= 1) saveHand(a0 != 0);
    }

    // --- comandos vindos do PC
    int n = udp.parsePacket();
    if (n > 0) {
        KbtCommand c;
        if (n == (int)sizeof(c) && udp.read((uint8_t*)&c, sizeof(c)) == (int)sizeof(c) && c.magic == KBT_MAGIC) {
            if (c.cmd == KBT_CMD_CALIBRATE_GYRO) startCalibration(CAL_SAMPLES_CMD);
            else if (c.cmd == KBT_CMD_SET_MODE && c.arg[0] <= KBT_CH_ANDROID) chMode = c.arg[0];
            else if (c.cmd == KBT_CMD_SET_HAND && c.arg[0] <= 1) saveHand(c.arg[0] != 0);
        } else udp.flush();
    }

    // --- leitura + fusao em LOOP_HZ
    uint32_t nowUs = micros();
    if (nowUs - lastUs >= 1000000UL / LOOP_HZ) {
        float dt = (nowUs - lastUs) * 1e-6f;
        lastUs = nowUs;
        if (dt > 0.05f) dt = 0.05f;

        float a[3], g[3];
        const bool readOk = mpuRead(a, g);
        if (readOk) { mpuFails = 0; mpuError = false; }
        else if (++mpuFails > 25) {                       // ~50 ms sem resposta: erro; tenta reiniciar o sensor a cada 2 s
            mpuError = true;
            static uint32_t lastTry = 0;
            if (nowMs - lastTry > 2000) { lastTry = nowMs; mpuInit(); }
        }
        // Sem MPU nao ha como calibrar: encerra a calibracao (senao o driver ficaria esperando para sempre)
        if (calibrating && mpuError) { calibrating = false; LOG("[mpu] calibracao cancelada: MPU nao responde\n"); }
        if (readOk) {
            float ab[3] = { pick(MPU_MAP_X, a), pick(MPU_MAP_Y, a), pick(MPU_MAP_Z, a) };
            float gb[3] = { pick(MPU_MAP_X, g), pick(MPU_MAP_Y, g), pick(MPU_MAP_Z, g) };
            if (calibrating) {
                for (int i = 0; i < 3; ++i) calSum[i] += gb[i];
                if (++calCount >= calTarget) {
                    for (int i = 0; i < 3; ++i) gyroBias[i] = (float)(calSum[i] / calCount);
                    calibrating = false; calibratedOnce = true;
                    filt.reset();
                    filt.beta = 2.0f; warmupUntil = nowMs + 1000;   // reconverge rapido por 1 s
                    LOG("[mpu] bias giro: %.3f %.3f %.3f dps\n", gyroBias[0], gyroBias[1], gyroBias[2]);
                }
            } else {
                for (int i = 0; i < 3; ++i) {
                    float v = gb[i] - gyroBias[i];
                    if (fabsf(v) < GYRO_STILL_DPS) v = 0;
                    gyroOut[i] = v;
                }
                if (warmupUntil && nowMs > warmupUntil) { filt.beta = MADGWICK_BETA; warmupUntil = 0; }
                const float D2R = 0.017453292519943295f;
                filt.update(gyroOut[0] * D2R, gyroOut[1] * D2R, gyroOut[2] * D2R, ab[0], ab[1], ab[2], dt);
            }
        }
    }

    // --- envio em SEND_HZ pelo canal ativo
    //   Auto   : Bluetooth se houver celular conectado, senao Wi-Fi
    //   SteamVR: so Wi-Fi          Android: Bluetooth se conectado; sem Bluetooth cai para o Wi-Fi (SteamVR continua funcionando)
    // Agenda por PERIODO ACUMULADO (lastSendUs += periodo): com "lastSendUs = agora" o loop (~1-1,5 ms por volta) arredondava 10 ms para 10-11,5 ms
    // e a taxa real ficava em ~87-95 Hz, nunca chegando a 100.
    static uint32_t sendTick = 0;
    const uint32_t sendPeriodUs = 1000000UL / SEND_HZ;
    if ((uint32_t)(nowUs - lastSendUs) >= sendPeriodUs) {
        lastSendUs += sendPeriodUs;
        if ((uint32_t)(nowUs - lastSendUs) > 4 * sendPeriodUs) lastSendUs = nowUs;   // ficou muito atrasado (reconexao etc.): nao dispara rajada
        ++sendTick;
        const bool useBle = BLE_ENABLED && bleLinked() && chMode != KBT_CH_STEAMVR;
        const bool useWifi = connected && !useBle;
        const uint16_t btn = buttonsRead();                // sempre le (mantem o debounce em dia)
        if (useBle || useWifi) {
            uint32_t jx = 0, jy = 0;
            for (int i = 0; i < 4; ++i) { jx += analogRead(PIN_JOY_X); jy += analogRead(PIN_JOY_Y); }
            jx /= 4; jy /= 4;
            // atalho de modo gamepad: segurar X + Y + B por GAMEPAD_HOLD_MS alterna VR <-> gamepad (uma vez por aperto)
            const uint16_t chord = KBT_BTN_X | KBT_BTN_Y | KBT_BTN_B;
            if ((btn & chord) == chord) {
                if (!gpChordSince) gpChordSince = nowMs;
                if (gpArmed && nowMs - gpChordSince >= GAMEPAD_HOLD_MS) {
                    gpArmed = false; gamepadMode = !gamepadMode; ledFlashUntil = nowMs + 500;
                    LOG("[gamepad] modo %s\n", gamepadMode ? "GAMEPAD" : "VR");
                }
            } else { gpChordSince = 0; gpArmed = true; }
            const uint8_t fl = (calibrating ? KBT_FLAG_CALIBRATING : 0) | (mpuError ? KBT_FLAG_MPU_ERROR : 0) |
                               (calibratedOnce ? KBT_FLAG_CALIBRATED : 0) | (handLeft ? KBT_FLAG_HAND_LEFT : 0) |
                               (gamepadMode ? KBT_FLAG_GAMEPAD : 0);
            if (useWifi) {
                KbtPacketQ p;
                p.magic = KBT_MAGIC_Q;
                p.seq = seq++;
                p.buttons = btn;
                p.joyX = jx; p.joyY = jy;
                p.quat[0] = filt.q0; p.quat[1] = filt.q1; p.quat[2] = filt.q2; p.quat[3] = filt.q3;
                p.gyro[0] = gyroOut[0]; p.gyro[1] = gyroOut[1]; p.gyro[2] = gyroOut[2];
                p.uptimeMs = nowMs;
                p.rssi = (int8_t)WiFi.RSSI();
                p.flags = fl;
                udp.beginPacket(dest, handLeft ? KBT_PORT_PC_LEFT : KBT_PORT_PC);
                udp.write((const uint8_t*)&p, sizeof(p));
                udp.endPacket();
                ++nSent;
            }
#if BLE_ENABLED
            if (useBle && (sendTick % BLE_DECIM) == 0) {
                KbtBlePacketQ b;
                auto q16 = [](float v) -> int16_t { long r = lroundf(v * 32767.f); return (int16_t)(r > 32767 ? 32767 : (r < -32767 ? -32767 : r)); };
                b.buttons = btn; b.joyX = (uint16_t)jx; b.joyY = (uint16_t)jy;
                b.qw = q16(filt.q0); b.qx = q16(filt.q1); b.qy = q16(filt.q2); b.qz = q16(filt.q3);
                b.flags = fl; b.seq = bleSeq++; b.t16 = (uint16_t)nowMs;      // carimbo de tempo da amostra (o celular repassa ao PC)
                bleData->setValue((uint8_t*)&b, sizeof(b));
                bleData->notify();
                ++nSent;
            }
#endif
        }
    }

    if (SERIAL_DEBUG && nowMs - lastLog > 2000) {
        lastLog = nowMs;
        filt.euler(roll, pitch, yaw);                    // so para o log (o envio usa o quaternion)
        LOG("[%s|%s|%s|%s] rpy %.1f %.1f %.1f | joy %d %d | enviados %u | heap %u\n", handLeft ? "ESQ" : "DIR", gamepadMode ? "GAMEPAD" : "VR", connected ? "wifi ok" : "sem wifi",
            bleLinked() ? "ble" : "-", roll, pitch, yaw, analogRead(PIN_JOY_X), analogRead(PIN_JOY_Y), (unsigned)nSent, (unsigned)ESP.getFreeHeap());
    }
    delay(1);   // cede tempo as tarefas do Wi-Fi/Bluetooth e ao watchdog (o loop continua > 500 Hz)
}
