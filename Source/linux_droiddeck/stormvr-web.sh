#!/usr/bin/env bash
# Painel web do StormVR (ajustes em tempo real). Abra http://127.0.0.1:8787 (ou o IP do aparelho na rede, porta 8787).
exec python3 "$(dirname "$0")/web/stormvr_web.py" "$@"
