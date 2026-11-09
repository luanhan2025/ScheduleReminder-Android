@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_APK_WINDOWS.ps1"
echo.
if errorlevel 1 echo Build failed. Please read the error above.
pause
