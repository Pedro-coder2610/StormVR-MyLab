@echo off
rem StormVR - abre o painel em Python (funciona com o SteamVR fechado; mudancas em tempo real)
where py >nul 2>nul && (py -3 "%~dp0stormvr_painel.py") || (python "%~dp0stormvr_painel.py")
if errorlevel 1 pause
