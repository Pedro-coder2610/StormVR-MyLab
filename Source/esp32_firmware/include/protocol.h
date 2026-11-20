// protocol.h - formato dos pacotes UDP entre o ESP32 e o driver SteamVR.
// ESTE ARQUIVO E IDENTICO nos dois projetos (stormvr/common e esp32_touch/include).
// Se mudar um, copie para o outro.
#pragma once
#include <stdint.h>

#define KBT_MAGIC        0x3154424Bu   // "KBT1" (little endian)
#define KBT_PORT_PC      5555          // porta UDP em que o DRIVER escuta
#define KBT_PORT_PC_LEFT 5558          // porta UDP do DRIVER so para o controle ESQUERDO (a direita usa KBT_PORT_PC). Pacotes aqui
                                       // SEMPRE ocupam a mao esquerda, mesmo se o controle disser outra coisa.
#define KBT_PORT_ESP     4210          // porta UDP local do ESP32 (recebe comandos)
#define KBT_PORT_HEAD    5556          // porta UDP do DRIVER para a pose da cabeca (celular). Canal separado
                                       // do vídeo e dos controles: a pose nunca espera por nada.
#define KBT_HEAD_MAGIC   0x31485453u   // "STH1" (little endian)

// Mascara de botoes
#define KBT_BTN_GRIP     (1u << 0)
#define KBT_BTN_TRIGGER  (1u << 1)
#define KBT_BTN_A        (1u << 2)
#define KBT_BTN_B        (1u << 3)
#define KBT_BTN_X        (1u << 4)
#define KBT_BTN_Y        (1u << 5)
#define KBT_BTN_START    (1u << 6)     // Start / System
#define KBT_BTN_L3       (1u << 7)     // clique do analogico
#define KBT_BTN_R2       (1u << 8)     // botao extra no GPIO4 (D4): "R2", reprogramavel no driver

// Flags
#define KBT_FLAG_CALIBRATING (1u << 0) // ESP32 esta calibrando o giroscopio (pose congelada)
#define KBT_FLAG_MPU_ERROR   (1u << 1) // MPU6050 nao responde (cabos SDA/SCL/alimentacao)
#define KBT_FLAG_CALIBRATED  (1u << 2) // o giroscopio ja foi calibrado desde que o ESP32 ligou
#define KBT_FLAG_HAND_LEFT   (1u << 3) // este controle e a mao ESQUERDA (padrao: direita)
#define KBT_FLAG_GAMEPAD     (1u << 4) // o controle esta em modo GAMEPAD (alternado no proprio ESP32: segure X+Y+B). O driver segue este estado.
#define KBT_FLAG_QUAT        (1u << 5) // so no KbtBlePacketQ: pacote com quaternion
#define KBT_FLAG_POS         (1u << 6) // so no KbtPacketP (celular 6DoF): o pacote traz a POSICAO do controle

// Comandos PC -> ESP32
#define KBT_CMD_CALIBRATE_GYRO 1
#define KBT_CMD_SET_MODE       2       // arg[0]: canal do ESP32 (KBT_CH_*)
#define KBT_CMD_SET_HAND       3       // arg[0]: 0 = este controle e a mao DIREITA, 1 = ESQUERDA (o ESP32 grava na memoria)

// Canal de saida do ESP32: Auto = Bluetooth se houver celular conectado, senao Wi-Fi.
// "Android" usa o Bluetooth quando conectado e cai para o Wi-Fi se o Bluetooth nao estiver conectado (SteamVR continua funcionando).
#define KBT_CH_AUTO    0
#define KBT_CH_STEAMVR 1               // so Wi-Fi/UDP (driver do PC)
#define KBT_CH_ANDROID 2               // Bluetooth LE (modo Android stand-alone); sem Bluetooth, cai para Wi-Fi

// Bluetooth LE (GATT): o ESP32 e o periferico, o celular e o central.
#define KBT_BLE_NAME         "StormVR-Ctl"        // o ESP32 acrescenta "-R" ou "-L" (mao); o app filtra pelo servico, nao pelo nome
#define KBT_BLE_SERVICE_UUID "7d0e0001-5354-4f52-4d56-525f43544c30"
#define KBT_BLE_DATA_UUID    "7d0e0002-5354-4f52-4d56-525f43544c30"   // NOTIFY: KbtBlePacket (14 bytes)
#define KBT_BLE_CMD_UUID     "7d0e0003-5354-4f52-4d56-525f43544c30"   // WRITE: [cmd, arg]

#pragma pack(push, 1)
struct KbtPacket {                 // ESP32 -> PC (~100 Hz)
    uint32_t magic;                // KBT_MAGIC
    uint16_t seq;
    uint16_t buttons;              // KBT_BTN_*
    uint16_t joyX, joyY;           // ADC bruto 0..4095 (GPIO34 / GPIO35)
    float    rpy[3];               // graus: [0]=roll (giro em X), [1]=pitch (giro em Y), [2]=yaw (giro em Z)
    float    gyro[3];              // graus/s (X,Y,Z), ja sem bias
    uint32_t uptimeMs;
    int8_t   rssi;                 // dBm
    uint8_t  flags;                // KBT_FLAG_*
};
// Mesmo conteudo do KbtPacket, mas com a orientacao em QUATERNION (sem os angulos de Euler). Os angulos de Euler (roll/pitch/yaw) ficam
// indeterminados a menos de ~1 grau do zenite (controle apontando reto para cima/baixo) e davam "pulos": o quaternion nao tem esse problema.
// Firmware novo envia SEMPRE este formato; o driver aceita os dois (KbtPacket antigo continua valendo).
#define KBT_MAGIC_Q      0x3151424Bu   // "KBQ1" (little endian)
struct KbtPacketQ {                // ESP32 -> PC (50 Hz)
    uint32_t magic;                // KBT_MAGIC_Q
    uint16_t seq;
    uint16_t buttons;              // KBT_BTN_*
    uint16_t joyX, joyY;           // ADC bruto 0..4095
    float    quat[4];              // w,x,y,z: orientacao do corpo do MPU (Madgwick, ja com MPU_MAP) em relacao ao mundo (Z = cima)
    float    gyro[3];              // graus/s (X,Y,Z), ja sem bias
    uint32_t uptimeMs;
    int8_t   rssi;                 // dBm
    uint8_t  flags;                // KBT_FLAG_*
};
// Celular no modo CONTROLE 6DoF (ARCore): KbtPacket + posicao do celular em metros, relativa ao ponto onde o usuario recentrou e no
// referencial do yaw do controle (yaw 0 = "para a frente"): pos[0] = frente, pos[1] = esquerda, pos[2] = cima. Tamanho 54 bytes.
struct KbtPacketP {
    KbtPacket base;                // flags com KBT_FLAG_POS
    float     pos[3];
};
struct KbtCommand {                // PC -> ESP32
    uint32_t magic;
    uint8_t  cmd;
    uint8_t  arg[3];
};

// Celular (StormVR Android) -> driver, ~100-200 Hz. Pacote com estado COMPLETO (nunca delta):
// o driver usa sempre o mais recente e descarta pacotes velhos/fora de ordem (seq 32 bits por sessao).
struct KbtHeadPacket {
    uint32_t magic;                // KBT_HEAD_MAGIC
    uint32_t deviceId;             // id estavel do celular
    uint32_t session;              // muda a cada vez que o app abre (reinicia seq)
    uint32_t seq;                  // cresce 1 a 1 dentro da sessao
    uint64_t timeUs;               // relogio monotonico do celular em microssegundos (latencia/idade)
    uint16_t flags;                // reservado
    uint8_t  trackingMode;         // 0 = 3DoF, 1 = 6DoF (posicao real em pos[])
    uint8_t  reserved;
    float    quat[4];              // w,x,y,z: orientacao da cabeca, eixos do SteamVR (x direita, y cima, -z frente)
    uint32_t recenterCount;        // incrementa quando o usuario pede recentralizar no celular
    float    pos[3];               // posicao da cabeca (m), eixos do SteamVR (x direita, y cima, -z frente), relativa ao ultimo recentrar.
                                   // So vale com KBT_HEAD_F_POS em flags (6DoF real: ARCore + IMU, o MESMO calculo da Sala 3D).
};
#define KBT_HEAD_F_POS   (1u << 0)     // flags: pos[] e uma posicao medida (trackingMode = 1)
// Celular (rastreamento de maos da Sala VR) -> driver, ~60 Hz. Estado COMPLETO (nunca delta), mesma regra de sessao/seq do KbtHeadPacket.
// Posicao e direcao no referencial do CORPO, relativo ao headset e alinhado so ao giro (yaw) da cabeca:
// x = direita, y = cima, z = para TRAS (metros / vetor unitario). side[0] = mao ESQUERDA, side[1] = mao DIREITA.
#define KBT_HAND_MAGIC   0x3148424Bu   // "KBH1"
#define KBT_PORT_HAND    5557
#define KBT_HAND_F_POS   (1u << 0)     // o app permite usar a POSICAO das maos
#define KBT_HAND_F_PINCH (1u << 1)     // o app permite usar a PINCA como trigger
#define KBT_HS_TRACKED   (1u << 0)     // mao vista pela camera neste quadro
#define KBT_HS_PINCH     (1u << 1)     // pinca fechada (polegar + indicador)
struct KbtHandSide {
    uint8_t flags;                 // KBT_HS_*
    uint8_t reserved[3];
    float   pos[3];                // centro da palma
    float   aim[3];                // direcao do pulso para a base do dedo medio (unitaria)
    float   ratio;                 // abertura da pinca (0 = fechada, ~1 = aberta)
};
struct KbtHandPacket {
    uint32_t magic;                // KBT_HAND_MAGIC
    uint32_t deviceId;
    uint32_t session;
    uint32_t seq;
    uint64_t timeUs;
    uint16_t flags;                // KBT_HAND_F_*
    uint16_t reserved;
    KbtHandSide side[2];
};
struct KbtBlePacket {              // ESP32 -> celular por Bluetooth LE (14 bytes: cabe no MTU padrao de 23)
    uint16_t buttons;              // KBT_BTN_*
    uint16_t joyX, joyY;           // 0..4095
    int16_t  roll100, pitch100, yaw100;   // graus x 100 (roll, pitch, yaw como em KbtPacket::rpy)
    uint8_t  flags;                // KBT_FLAG_*
    uint8_t  seq;
};
// Versao Bluetooth com quaternion (18 bytes: cabe nos 20 bytes uteis do MTU padrao). O app distingue pelo tamanho (14 = antigo, 16 = quaternion,
// 18 = quaternion + carimbo de tempo). O carimbo (millis() do ESP32, 16 bits) e o que permite ao driver interpolar com o ritmo REAL das amostras: sem ele
// o celular so sabia a hora em que o pacote CHEGOU, e o Bluetooth entrega em rajadas (=> "pulinhos" no SteamVR).
struct KbtBlePacketQ {
    uint16_t buttons;              // KBT_BTN_*
    uint16_t joyX, joyY;           // 0..4095
    int16_t  qw, qx, qy, qz;       // quaternion x 32767 (normalizado)
    uint8_t  flags;                // KBT_FLAG_*
    uint8_t  seq;
    uint16_t t16;                  // millis() do ESP32 modulo 65536 (o app desenrola o contador)
};
#pragma pack(pop)
static_assert(sizeof(KbtBlePacketQ) == 18, "KbtBlePacketQ deve ter 18 bytes");
static_assert(sizeof(KbtBlePacket) == 14, "KbtBlePacket deve ter 14 bytes");
static_assert(sizeof(KbtHeadPacket) == 60, "KbtHeadPacket deve ter 60 bytes");
static_assert(sizeof(KbtHandSide) == 32, "KbtHandSide deve ter 32 bytes");
static_assert(sizeof(KbtHandPacket) == 92, "KbtHandPacket deve ter 92 bytes");

static_assert(sizeof(KbtPacket) == 42, "KbtPacket deve ter 42 bytes");
static_assert(sizeof(KbtPacketQ) == 46, "KbtPacketQ deve ter 46 bytes");
static_assert(sizeof(KbtPacketP) == 54, "KbtPacketP deve ter 54 bytes");
static_assert(sizeof(KbtCommand) == 8, "KbtCommand deve ter 8 bytes");
