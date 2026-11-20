// config.h - ajustes do firmware. Edite o Wi-Fi antes de gravar.
#pragma once

// ---------------------------------------------------------------- Wi-Fi
#define WIFI_SSID  "Rb_lucas"
#define WIFI_PASS  "lucasgabriel"

// IP do PC onde roda o SteamVR. Deixe "" para usar broadcast (255.255.255.x da rede) - funciona
// na maioria dos roteadores; se nao conectar, ou para menos latencia, coloque o IP fixo do PC.
#define PC_IP      ""

// ---------------------------------------------------------------- Pinos
#define PIN_GRIP     13
#define PIN_TRIGGER  14
#define PIN_A        25
#define PIN_B        26
#define PIN_X        27
#define PIN_Y        32
#define PIN_START    33
#define PIN_L3       23
#define PIN_R2       4      // D4: botao extra "R2" (reprogramavel no driver SteamVR). Liga o pino ao GND ao apertar.
#define PIN_JOY_X    34
#define PIN_JOY_Y    35
#define PIN_SDA      21
#define PIN_SCL      22
#define PIN_LED      2      // LED da placa: pisca ligando ao Wi-Fi, aceso = conectado (-1 desliga)

// Botoes ligados entre o pino e o GND (INPUT_PULLUP, pressionado = nivel baixo).
// Se os seus botoes ligam ao 3V3, troque para 0 e use resistores pull-down externos.
#define BTN_ACTIVE_LOW 1
#define BTN_DEBOUNCE_MS 4

// ---------------------------------------------------------------- MPU6050 / fusao
#define MPU_ADDR        0x68
// Como o sensor esta montado: qual eixo do SENSOR vira X/Y/Z do "corpo" (1=X, 2=Y, 3=Z; negativo inverte).
// Com a placa do MPU deitada (Z do chip para cima) deixe 1,2,3. Ajuste so se o chip estiver montado de
// outro jeito; ajustes finos (inverter/trocar eixos) tambem podem ser feitos no menu do SteamVR.
// Etapa 30: X e Z INVERTIDOS por padrao (-1, 2, -3 = giro de 180 graus em torno de Y, uma rotacao propria: nao espelha nada). Corrige o ESP32 que girava
// "para o lado contrario" em roll (X) e yaw (Z); o pitch (Y) ja estava certo. Vale para acelerometro E giroscopio, entao a fusao continua coerente.
#define MPU_MAP_X       -1
#define MPU_MAP_Y       2
#define MPU_MAP_Z       -3

#define LOOP_HZ         200     // taxa de leitura/fusao do MPU (era 500: o ESP32 gastava tempo demais no I2C e no Madgwick)
#define SEND_HZ         100     // pacotes por segundo enviados ao PC (Wi-Fi): 50 nao sobrecarrega o ESP32 (o driver interpola entre pacotes). O agendamento acumula o periodo
#define BLE_DECIM       2       // Bluetooth envia 1 a cada N pacotes: com SEND_HZ 100 isso da ~50/s por BLE (o intervalo de conexao BLE suporta ~60-90 notificacoes/s)
#define MADGWICK_BETA   0.06f   // maior = corrige mais rapido pelo acelerometro, mais ruido
#define GYRO_STILL_DPS  0.15f   // abaixo disso (apos remover o bias) o giroscopio e tratado como parado (reduz deriva do yaw)
#define CAL_SAMPLES_BOOT 280    // amostras de calibracao do giroscopio ao ligar (~1.4 s a 200 Hz, deixe PARADO)
#define CAL_SAMPLES_CMD  160    // idem, quando pedido pelo menu do SteamVR

// ---------------------------------------------------------------- Bluetooth LE (celular, modo Android stand-alone)
#define BLE_ENABLED       1     // 1 = anuncia "StormVR-Ctl-R/-L" por Bluetooth LE e envia a pose/botoes ao app do celular
// Mao deste controle na PRIMEIRA gravacao (0 = direita, 1 = esquerda). Depois disso vale o que esta na memoria do ESP32,
// que pode ser trocado SEM regravar: (a) segure GRIP + TRIGGER ao ligar (~1 s), ou (b) pelo menu do SteamVR / app do celular.
#define HAND_LEFT_DEFAULT 0
#define HAND_SWAP_HOLD_MS 1000  // quanto tempo segurar GRIP + TRIGGER ao ligar para trocar a mao

// Protecao contra travamento no boot: se o ESP32 reiniciar por erro (panic / watchdog / queda de tensao) 2 vezes seguidas,
// o proximo boot liga em MODO SEGURO (sem Bluetooth) e avisa na serial. Desligar a energia volta ao normal.
#define SAFE_MODE_AFTER_CRASHES 2
#define BOOT_OK_AFTER_MS        15000

// Atalho de MODO GAMEPAD: segure X + Y + B juntos por GAMEPAD_HOLD_MS para alternar entre modo VR e modo gamepad (Xbox virtual no PC).
// O estado vai em CADA pacote (flag KBT_FLAG_GAMEPAD) e o driver muda de modo sozinho. Ao ligar o controle, o modo e sempre VR.
#define GAMEPAD_HOLD_MS 600

#define SERIAL_DEBUG    1       // imprime status na serial (115200)
