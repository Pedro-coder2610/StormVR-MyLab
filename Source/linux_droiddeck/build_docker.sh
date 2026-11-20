#!/usr/bin/env bash
# Cross-compila para aarch64 dentro de um container Ubuntu (nao precisa instalar nada alem do Docker).
set -euo pipefail
cd "$(dirname "$0")"
docker run --rm -v "$PWD":/src -w /src ubuntu:24.04 bash -c \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq g++-aarch64-linux-gnu file >/dev/null && ./build_linux.sh aarch64"
