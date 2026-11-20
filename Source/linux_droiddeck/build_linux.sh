#!/usr/bin/env bash
# Compila o driver StormVR para Linux. Saida: dist/stormvr/ (pronto para copiar) e dist/stormvr-linux-<arch>.tar.gz
#   ./build_linux.sh            -> aarch64 (nativo se estiver num ARM64, senao cross-compila com aarch64-linux-gnu-g++)
#   ./build_linux.sh x86        -> x86_64 (so para testar no PC)
#   OPENVR_DIR=/caminho/headers ./build_linux.sh    -> usa o openvr_driver.h do seu SDK (padrao: third_party/openvr_driver.h)
set -euo pipefail
cd "$(dirname "$0")"
target=${1:-aarch64}
inc=${OPENVR_DIR:-third_party}
[ -f "$inc/openvr_driver.h" ] || { echo "openvr_driver.h nao encontrado em $inc (use OPENVR_DIR=<pasta headers do OpenVR>)"; exit 1; }

case "$target" in
  aarch64)
    arch=linuxarm64
    if [ "$(uname -m)" = aarch64 ]; then cxx=${CXX:-g++}; else cxx=${CXX:-aarch64-linux-gnu-g++}; fi ;;
  x86)
    arch=linux64; cxx=${CXX:-g++} ;;
  *) echo "alvo desconhecido: $target (use aarch64 ou x86)"; exit 1 ;;
esac
command -v "$cxx" >/dev/null || { echo "compilador '$cxx' nao encontrado. Ubuntu/WSL: sudo apt install g++-aarch64-linux-gnu  (ou use build_docker.bat / build_docker.sh)"; exit 1; }

out=dist/stormvr
rm -rf "$out"; mkdir -p "$out/bin/$arch"
cp -r package_linux/stormvr/. "$out/"
# painel WEB e painel Python vao junto: o driver abre o painel WEB sozinho quando o SteamVR inicia (web_autostart)
mkdir -p "$out/web" "$out/painel_python"
cp web/stormvr_web.py web/index.html web/manifest.json web/gen_manifest.py web/favicon.png web/logo.png "$out/web/"
cp painel_python/stormvr_painel.py "$out/painel_python/"
cp config/config_app.cpp "$out/web/config_app.cpp" 2>/dev/null || true
"$cxx" -std=c++17 -O2 -Wall -Wno-unused-parameter -Wno-unused-function -fPIC -shared -fvisibility=hidden -pthread \
    -static-libstdc++ -static-libgcc -Wl,-z,defs -I"$inc" -Icommon \
    -o "$out/bin/$arch/driver_stormvr.so" driver/driver.cpp -ldl
echo "OK: $out/bin/$arch/driver_stormvr.so"
file "$out/bin/$arch/driver_stormvr.so" 2>/dev/null || true
tar -C dist -czf "dist/stormvr-$arch.tar.gz" stormvr
echo "Pacote: dist/stormvr-$arch.tar.gz"
