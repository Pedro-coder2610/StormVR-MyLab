# Compilar e instalar o StormVR DENTRO do Droid Deck (passo a passo, só copiar e colar)

Tudo aqui roda no **terminal do Ubuntu do Droid Deck** (o mesmo onde você abre o SteamVR). O Droid Deck é aarch64, então o `g++` de lá
já compila direto para ele — não precisa de PC com Docker nem de cross-compilação.

> Seu PC (192.168.100.108) só é usado para entregar o arquivo .zip ao Droid Deck (passo 2). O Wi-Fi é o `Rb_lucas`.

---------------------------------------------------------------------------------------------------------------------------------------------
## 1) Instalar as ferramentas (uma vez só)

    sudo apt update
    sudo apt install -y g++ make python3 unzip wget

Conferir se é mesmo aarch64 (tem que aparecer `aarch64`):

    uname -m

---------------------------------------------------------------------------------------------------------------------------------------------
## 2) Levar o `StormVR_linux_DroidDeck.zip` para dentro do Droid Deck (escolha UMA forma)

**Forma A — pelo PC (a mais simples, os dois na mesma rede Rb_lucas).**
No **PC**, abra um terminal/PowerShell na pasta onde está o zip e rode:

    python -m http.server 8000

No **Droid Deck**:

    cd ~
    wget http://192.168.100.108:8000/StormVR_linux_DroidDeck.zip

(Depois, no PC, feche o servidor com Ctrl+C.) Se o Windows perguntar sobre o Firewall, permita redes privadas.

**Forma B — pela pasta Download do Android** (se o Ubuntu enxerga o armazenamento do celular):

    ls /sdcard/Download/ 2>/dev/null || ls /storage/emulated/0/Download/
    cp /sdcard/Download/StormVR_linux_DroidDeck.zip ~/ 2>/dev/null || cp /storage/emulated/0/Download/StormVR_linux_DroidDeck.zip ~/

---------------------------------------------------------------------------------------------------------------------------------------------
## 3) Descompactar e compilar

    cd ~
    rm -rf StormVR_linux && unzip -o StormVR_linux_DroidDeck.zip
    cd StormVR_linux
    chmod +x *.sh
    ./build_linux.sh

Deve terminar com `OK: dist/stormvr/bin/linuxarm64/driver_stormvr.so` e `Pacote: dist/stormvr-linuxarm64.tar.gz`.
(Avisos amarelos do tipo `-Wmisleading-indentation` / `-Warray-bounds` são normais.)

Se aparecer `openvr_driver.h nao encontrado`, aponte para os headers do seu SDK:

    OPENVR_DIR=/caminho/para/openvr/headers ./build_linux.sh

---------------------------------------------------------------------------------------------------------------------------------------------
## 4) (Recomendado) Rodar o teste automático antes de instalar
Ele carrega o driver recém-compilado num "SteamVR de mentira", manda pacotes de um ESP32 simulado e confere: botões, analógico, giro,
**controle que reinicia e volta a conectar**, **posição 6DoF** e o painel web.

    mkdir -p /tmp/stormdir
    g++ -std=c++17 -O1 -pthread -Ithird_party -Icommon -o /tmp/test_host tools/test_host.cpp -ldl
    STORMVR_DIR=/tmp/stormdir STORM_TEST_WEB=1 /tmp/test_host dist/stormvr/bin/linuxarm64/driver_stormvr.so | tail -25

O final tem que ser `RESULTADO: TUDO OK (0 falhas)`. (O painel web usa a porta 8787: feche qualquer painel aberto antes.)

---------------------------------------------------------------------------------------------------------------------------------------------
## 5) Instalar no SteamVR do Droid Deck

    ./install_droiddeck.sh

Ele copia para `/opt/steamvr/drivers/stormvr` (pede a senha do `sudo` se precisar). Depois **feche o SteamVR e abra de novo**.
Se você reinstalar o SteamVR, rode o `./install_droiddeck.sh` outra vez.

Conferir se carregou (procure "stormvr"):

    grep -i stormvr ~/.local/share/Steam/logs/vrserver.txt | tail -20

---------------------------------------------------------------------------------------------------------------------------------------------
## 6) Painel de ajustes (agora abre SOZINHO junto com o SteamVR)
Quando o SteamVR inicia, o driver abre o painel web na porta 8787 e o fecha quando o SteamVR sai. No navegador do Droid Deck:
`http://127.0.0.1:8787` — ou, de qualquer celular/PC na rede Rb_lucas, `http://IP-DO-DROID-DECK:8787`.

Para descobrir o IP do Droid Deck:

    hostname -I

Para desligar a abertura automática, no painel desmarque "web_autostart" (ou: `./stormvr-web.sh` para abrir na mão).

---------------------------------------------------------------------------------------------------------------------------------------------
## 7) ESP32 / celular-controle apontando para o Droid Deck
- ESP32 (Wi-Fi): em `esp32_firmware/include/config.h` (no projeto do ESP32) o `PC_IP` tem que ser o IP **do Droid Deck** (o `hostname -I` acima),
  porta 5555 (direita) / 5558 (esquerda). `WIFI_SSID "Rb_lucas"` já está configurado no firmware desta versão.
- Celular como controle: no app StormVR > Modo controle, IP do PC = IP do Droid Deck.

Se o firewall do Ubuntu estiver ligado:

    sudo ufw allow 5555/udp
    sudo ufw allow 5558/udp
    sudo ufw allow 8787/tcp

---------------------------------------------------------------------------------------------------------------------------------------------
## 8) Atualizar no futuro
Repita os passos 2, 3 e 5 com o zip novo. Para desinstalar:

    sudo rm -rf /opt/steamvr/drivers/stormvr

## Problemas comuns
- `g++: command not found` → passo 1.
- Controle não aparece no SteamVR → `grep -i stormvr ~/.local/share/Steam/logs/vrserver.txt` e me mande as linhas.
- Painel web não abriu → `python3 --version` (precisa do Python 3) e `./stormvr-web.sh`.
- Celular/ESP32 desligou e não voltou → nesta versão ele volta sozinho (corrigido); se não voltar, mande o log do vrserver.
