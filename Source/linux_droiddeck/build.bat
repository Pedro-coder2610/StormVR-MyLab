@echo off
REM Compila o driver + app de configuracao (Visual Studio 2019/2022, x64).
REM Uso: build.bat  [caminho_do_SDK]
setlocal
set SDK=%~1
if "%SDK%"=="" set SDK=Z:/Ferramentas/openvr-2.15.6
cmake -S . -B C:\dev\stormvr_build -A x64 -DOPENVR_SDK="%SDK%" || goto :err
cmake --build C:\dev\stormvr_build --config Release || goto :err
echo.
echo Compilado! Pasta pronta para instalar: %~dp0package\stormvr
goto :eof
:err
echo.
echo ERRO na compilacao - veja as mensagens acima.
exit /b 1
