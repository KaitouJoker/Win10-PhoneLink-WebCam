@echo off
setlocal

set "MODE=%~1"
set "BUNDLE_DIR=%~dp0"
if "%BUNDLE_DIR:~-1%"=="\" set "BUNDLE_DIR=%BUNDLE_DIR:~0,-1%"
set "DLL64=%BUNDLE_DIR%\vcam-dshow.dll"
set "DLL32=%BUNDLE_DIR%\vcam-dshow32.dll"

if /I "%MODE%"=="--no-elevate" goto :do_unregister
if /I "%MODE%"=="--elevated" goto :do_unregister

net session >nul 2>&1
if not errorlevel 1 goto :do_unregister

echo [INFO] Requesting administrator elevation to unregister system-wide camera keys...
set "__VCAM_BAT=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $p = Start-Process -FilePath $env:ComSpec -ArgumentList '/c', ('""""' + $env:__VCAM_BAT + '"" --elevated"') -Verb RunAs -Wait -PassThru; exit $p.ExitCode } catch { exit 1223 }"
if not errorlevel 1 exit /b 0

echo [WARN] Elevation canceled or failed [code %ERRORLEVEL%]. Attempting current-user unregister...

:do_unregister
set "REG64=%SystemRoot%\System32\regsvr32.exe"
if exist "%SystemRoot%\Sysnative\regsvr32.exe" set "REG64=%SystemRoot%\Sysnative\regsvr32.exe"
set "REG32=%SystemRoot%\SysWOW64\regsvr32.exe"

set "FINAL_RC=0"
if exist "%DLL64%" (
    "%REG64%" /u /s "%DLL64%"
    if errorlevel 1 set "FINAL_RC=%ERRORLEVEL%"
)
if exist "%DLL32%" if exist "%REG32%" (
    "%REG32%" /u /s "%DLL32%"
    if errorlevel 1 if "%FINAL_RC%"=="0" set "FINAL_RC=%ERRORLEVEL%"
)
exit /b %FINAL_RC%
