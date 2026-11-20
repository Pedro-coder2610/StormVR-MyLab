@echo off
rem StormVR - painel WEB (abra no celular o endereco que aparecer). Opcional: iniciar_web.bat --pin 1234
where py >nul 2>nul && (py -3 "%~dp0stormvr_web.py" %*) || (python "%~dp0stormvr_web.py" %*)
if errorlevel 1 pause
