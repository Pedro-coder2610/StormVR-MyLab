@echo off
rem Cross-compila o driver StormVR para o DroidDeck (Linux aarch64) usando Docker Desktop.
cd /d "%~dp0"
docker run --rm -v "%cd%":/src -w /src ubuntu:24.04 bash -c "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq g++-aarch64-linux-gnu file dos2unix >/dev/null && dos2unix -q build_linux.sh && bash ./build_linux.sh aarch64"
pause
