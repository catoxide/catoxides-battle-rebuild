@echo off
rem ============================================================
rem  ContentPack Editor - one-click launcher
rem  Double-click to open the editor (auto-installs deps first run)
rem ============================================================
setlocal
cd /d "%~dp0"

rem Use npmmirror to avoid TLS certificate issues (CN network)
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/

rem First run: install dependencies if missing
if not exist "node_modules\electron\dist\electron.exe" (
    echo [first run] Installing dependencies, please wait...
    call npm install --no-fund --no-audit
    if errorlevel 1 (
        echo.
        echo [ERROR] Dependency install failed. Check network or run: npm install
        pause
        exit /b 1
    )
)

echo [launch] ContentPack Editor ...
call npm start
if errorlevel 1 (
    echo.
    echo [ERROR] Editor failed to start. See output above.
    pause
)
endlocal
