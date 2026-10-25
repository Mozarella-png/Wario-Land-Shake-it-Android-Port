@echo off
cd /d "%~dp0"
echo WarioRecomp: full build (the first run downloads the tools and takes a while).
echo Progress is written to build-logs\status.txt.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-wario.ps1" %*
echo.
pause
