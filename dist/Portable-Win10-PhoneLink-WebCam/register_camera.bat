@echo off
setlocal

set "MODE=%~1"
set "BUNDLE_DIR=%~dp0"
if "%BUNDLE_DIR:~-1%"=="\" set "BUNDLE_DIR=%BUNDLE_DIR:~0,-1%"
set "DLL64=%BUNDLE_DIR%\vcam-dshow.dll"
set "DLL32=%BUNDLE_DIR%\vcam-dshow32.dll"

if not exist "%DLL64%" (
    echo [ERROR] Missing "%DLL64%"
    exit /b 1
)

if /I "%MODE%"=="--no-elevate" goto :do_register
if /I "%MODE%"=="--elevated" goto :do_register

net session >nul 2>&1
if not errorlevel 1 goto :do_register

echo [INFO] Requesting administrator elevation for system-wide camera registration [HKLM + WOW6432Node]...
set "__VCAM_BAT=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $p = Start-Process -FilePath $env:ComSpec -ArgumentList '/c', ('""""' + $env:__VCAM_BAT + '"" --elevated"') -Verb RunAs -Wait -PassThru; exit $p.ExitCode } catch { exit 1223 }"
if not errorlevel 1 exit /b 0

echo [WARN] Elevation canceled or failed [code %ERRORLEVEL%]. Falling back to current-user [HKCU] registration.
echo [WARN] Discord and 32-bit apps may not detect the virtual camera without Admin HKLM registration.

:do_register
set "REG64=%SystemRoot%\System32\regsvr32.exe"
if exist "%SystemRoot%\Sysnative\regsvr32.exe" set "REG64=%SystemRoot%\Sysnative\regsvr32.exe"
set "REG32=%SystemRoot%\SysWOW64\regsvr32.exe"

"%REG64%" /s "%DLL64%"
set "RC64=%ERRORLEVEL%"
if not "%RC64%"=="0" (
    echo [ERROR] 64-bit regsvr32 failed for "%DLL64%" [exit %RC64%].
    exit /b %RC64%
)

if exist "%DLL32%" if exist "%REG32%" (
    "%REG32%" /s "%DLL32%"
    if errorlevel 1 echo [WARN] 32-bit regsvr32 failed for "%DLL32%".
)

net session >nul 2>&1
if not errorlevel 1 (
    echo [OK] Registered 64-bit and 32-bit Virtual Camera in HKLM.
) else (
    echo [OK] Registered Virtual Camera in HKCU only.
)

tasklist /FI "IMAGENAME eq Discord.exe" 2>nul | find /I "Discord.exe" >nul
if not errorlevel 1 (
    echo [INFO] Discord is currently running. Restart Discord or press Ctrl+R inside Discord to refresh cameras.
)
exit /b 0
