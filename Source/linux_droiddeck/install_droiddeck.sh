#!/usr/bin/env bash
# Rode DENTRO do Ubuntu do DroidDeck (terminal do DroidDeck), na pasta onde esta o stormvr/ (ou o .tar.gz).
#   ./install_droiddeck.sh            -> copia para /opt/steamvr/drivers/stormvr (o SteamVR enxerga sozinho; some se reinstalar o SteamVR)
#   ./install_droiddeck.sh --user     -> copia para ~/.local/share/stormvr/driver (precisa do patch droiddeck-steamvr-stormvr.patch)
set -euo pipefail
cd "$(dirname "$0")"
src=stormvr
if [ ! -d "$src" ]; then
  tgz=$(ls stormvr-linuxarm64.tar.gz dist/stormvr-linuxarm64.tar.gz 2>/dev/null | head -n1 || true)
  [ -n "$tgz" ] && tar -xzf "$tgz" && src=stormvr
fi
[ -f "$src/bin/linuxarm64/driver_stormvr.so" ] || { echo "nao achei stormvr/bin/linuxarm64/driver_stormvr.so (compile antes: build_linux.sh)"; exit 1; }
if [ "${1:-}" = "--user" ]; then
  dest=$HOME/.local/share/stormvr/driver
else
  dest=${STEAMVR:-/opt/steamvr}/drivers/stormvr
fi
SUDO=""; [ -w "$(dirname "$dest")" ] || SUDO=sudo
$SUDO rm -rf "$dest"; $SUDO mkdir -p "$(dirname "$dest")"; $SUDO cp -r "$src" "$dest"
echo "Driver instalado em $dest"
echo "Feche o SteamVR e abra de novo. O painel web abre sozinho com o SteamVR: http://127.0.0.1:8787 (ou ./stormvr-web.sh para abrir na mao)"
