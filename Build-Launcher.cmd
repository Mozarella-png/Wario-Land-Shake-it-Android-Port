@echo off
rem Builds the WarioRecomp launcher into Play\WarioLauncher.exe
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-launcher.ps1"
if errorlevel 1 (
  echo.
  echo FAILED: see build-logs\launcher.log
) else (
  echo.
  echo DONE: open Play\WarioLauncher.exe
)
pause
