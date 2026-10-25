@echo off
cd /d "%~dp0"
echo WarioRecomp: quick rebuild (no downloads, no new translation) and a 60 s test run.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-wario.ps1" -SkipTools -SkipTranslate
echo.
pause
