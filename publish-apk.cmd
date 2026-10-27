@echo off
setlocal
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\publish-apk.ps1" %*
) else (
    pwsh.exe -NoProfile -File "%~dp0scripts\publish-apk.ps1" %*
)
exit /b %errorlevel%
