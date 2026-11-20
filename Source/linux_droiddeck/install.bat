@echo off
REM Copia a pasta do driver para SteamVR\drivers (feche o SteamVR antes).
REM Uso: install.bat ["C:\caminho\para\SteamVR"]
set "STEAMVR=%~1"
if "%STEAMVR%"=="" set "STEAMVR=Z:\Jogos\Steam\steamapps\common\SteamVR"
if not exist "%STEAMVR%\drivers" set "STEAMVR=C:\Program Files (x86)\Steam\steamapps\common\SteamVR"
if not exist "%STEAMVR%\drivers" goto :nosteam
if not exist "%~dp0package\stormvr\bin\win64\driver_stormvr.dll" goto :nobuild
xcopy /E /I /Y "%~dp0package\stormvr" "%STEAMVR%\drivers\stormvr"
echo.
echo Instalado em "%STEAMVR%\drivers\stormvr"
echo Se existir a pasta antiga drivers\keyboardtouch, APAGUE-A (senao aparecem controles duplicados).
goto :eof
:nosteam
echo Pasta do SteamVR nao encontrada:
echo "%STEAMVR%"
echo Uso: install.bat "D:\Steam\steamapps\common\SteamVR"
exit /b 1
:nobuild
echo Rode o build.bat primeiro.
exit /b 1
