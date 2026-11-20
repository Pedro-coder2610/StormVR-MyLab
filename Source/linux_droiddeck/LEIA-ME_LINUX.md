# StormVR no SteamVR do DroidDeck (Linux aarch64) — só controles

> **Etapa 30:** esta versão foi atualizada para o mesmo nível da do Windows (tudo que é compatível com Linux aarch64).
> **Passo a passo para compilar e instalar dentro do próprio Droid Deck: veja `COMPILAR_NO_DROIDDECK.md`.**
>
> Novidades em relação à versão anterior: controle que reinicia/desliga **volta a conectar sozinho** (antes só reiniciando o SteamVR);
> pose segura na última posição quando faltam pacotes e tempo de "sem sinal" adaptado à taxa real; Arm Model "simples" com o cotovelo
> como centro da esfera; **6DoF do celular-controle** (pacote de posição, com eixos/inversão/escala ajustáveis e Arm Model desligado só na mão
> em 6DoF); suporte ao carimbo de tempo do ESP32 por Bluetooth; **painel web abre junto com o SteamVR**; painel e logo em roxo.

O headset continua sendo o do DroidDeck. Este driver cria apenas os dois controles (direito = ESP32 por UDP),
que seguem a pose do headset do DroidDeck. O DroidDeck desliga os "pad hands" dele sozinho quando vê controles de outro driver.

## 1) Compilar (no seu PC)
Escolha um:
- **Windows + Docker Desktop:** dê dois cliques em `build_docker.bat`
- **WSL/Ubuntu/Linux:** `sudo apt install g++-aarch64-linux-gnu` e depois `./build_linux.sh`
- **Docker no Linux/WSL:** `./build_docker.sh`

Saída: `dist/stormvr/` e `dist/stormvr-linuxarm64.tar.gz`.
Usa o `openvr_driver.h` que veio do DroidDeck (`third_party/`). Para usar o do seu SDK 2.15.6:
`OPENVR_DIR=/mnt/z/Ferramentas/openvr-2.15.6/headers ./build_linux.sh`

## 2) Instalar no DroidDeck
Copie a pasta `dist/stormvr` + `install_droiddeck.sh` para o Ubuntu do DroidDeck e rode `./install_droiddeck.sh`
(copia para `/opt/steamvr/drivers/stormvr`). Feche e abra o SteamVR.
Se reinstalar o SteamVR, rode o instalador de novo.

Opção permanente: `./install_droiddeck.sh --user` + aplicar `droiddeck-steamvr-stormvr.patch` no repositório do DroidDeck
(`patch -p1 < droiddeck-steamvr-stormvr.patch`) e rebuildar o DroidDeck; o script passa a listar o driver em `external_drivers`.

## 3) Ajustes
`./stormvr-web.sh` e abra `http://127.0.0.1:8787` (ou o IP do aparelho). Os ajustes ficam em `~/.config/StormVR/`.
No painel, as opções de HMD/janela (inclusive monitor como headset e anti tela preta) não têm efeito no Linux: o headset é o do Droid Deck. O ESP32 continua mandando UDP para a porta 5555 do IP do aparelho.

## Diferenças em relação ao Windows
- Sem headset próprio, sem janela/monitor, sem app de config GDI (use o painel web).
- Sem leitura de teclado: a mão sem controle real fica parada.
- Perfil de entrada padrão = Index (`ctl_profile` 2), que o SteamVR ARM64 do DroidDeck usa. Perfil Oculus (0) só funciona se `{oculus}/input/touch_profile.json` existir nesse SteamVR; 3 = genérico (sem bindings de jogos).
- Se um controle tiver `left_enabled=false`, o DroidDeck volta a usar o pad dele nessa mão.

## O que foi testado
- Compila em x86_64 (g++ 13) sem erros. Teste automático (agora com: reinício do remetente, posição 6DoF e painel web junto com o driver) `tools/test_host.cpp` (SteamVR simulado): Init, 2 controles, botões, analógico, giro de 90°, external.ini/state.ini, memória compartilhada, painel Python/Web lendo o driver — tudo OK.
- NÃO testado: build aarch64 real, SteamVR ARM64 real, ESP32 real. Se o SteamVR não listar os controles, mande o log `~/.local/share/Steam/logs/vrserver.txt` (procure por "stormvr").
