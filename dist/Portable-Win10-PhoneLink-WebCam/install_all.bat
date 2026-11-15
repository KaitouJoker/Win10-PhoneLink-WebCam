@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "BUNDLE_DIR=%~dp0"
if "%BUNDLE_DIR:~-1%"=="\" set "BUNDLE_DIR=%BUNDLE_DIR:~0,-1%"

set "RECEIVER_EXE=%BUNDLE_DIR%\PhoneCamReceiver.exe"
set "VCAM_DLL=%BUNDLE_DIR%\vcam-dshow.dll"
set "VCAM_DLL32=%BUNDLE_DIR%\vcam-dshow32.dll"
set "REG_BAT=%BUNDLE_DIR%\register_camera.bat"
set "UNREG_BAT=%BUNDLE_DIR%\unregister_camera.bat"
set "FFMPEG_EXE=%BUNDLE_DIR%\ffmpeg.exe"
set "APK_FILE=%BUNDLE_DIR%\Win10-PhoneCam.apk"

call :locate_adb
if not exist "%FFMPEG_EXE%" if exist "D:\ffmpeg\bin\ffmpeg.exe" copy /Y "D:\ffmpeg\bin\ffmpeg.exe" "%FFMPEG_EXE%" >nul

if /i "%~1"=="--all" goto :do_all
if /i "%~1"=="--register-vcam" goto :do_register
if /i "%~1"=="--unregister-vcam" goto :do_unregister
if /i "%~1"=="--install-apk" goto :do_install_apk
if /i "%~1"=="--adb-reverse" goto :do_adb_reverse
if /i "%~1"=="--check" goto :do_check

:menu
cls
echo ============================================================================
echo   Win10-PhoneLink-WebCam All-in-One Installer (Portable Edition)
echo ============================================================================
echo   Bundle Folder: "%BUNDLE_DIR%"
echo.
echo   [1] One-Click Setup (Register VCam + Install Android APK + Start Receiver)
echo   [2] Register PC Virtual Camera (vcam-dshow.dll)
echo   [3] Unregister PC Virtual Camera
echo   [4] Install Android Phone APK via USB ADB
echo   [5] Setup USB ADB Reverse Port Forward (adb reverse tcp:9876 tcp:9876)
echo   [6] Check Portable Files and Registration Status (--check)
echo   [7] Launch PhoneCamReceiver.exe
echo   [0] Exit
echo ============================================================================
set /p "CHOICE=Select option [1-7, 0]: "
if "%CHOICE%"=="1" goto :do_all_interactive
if "%CHOICE%"=="2" (call :do_register_sub & pause & goto :menu)
if "%CHOICE%"=="3" (call :do_unregister_sub & pause & goto :menu)
if "%CHOICE%"=="4" (call :do_install_apk_sub & pause & goto :menu)
if "%CHOICE%"=="5" (call :do_adb_reverse_sub & pause & goto :menu)
if "%CHOICE%"=="6" (call :do_check_sub & pause & goto :menu)
if "%CHOICE%"=="7" (start "" "%RECEIVER_EXE%" & goto :eof)
if "%CHOICE%"=="0" goto :eof
goto :menu

:do_all_interactive
call :do_all_sub
pause
goto :menu

:do_all
call :do_all_sub
exit /b %ERRORLEVEL%

:do_register
call :do_register_sub
exit /b %ERRORLEVEL%

:do_unregister
call :do_unregister_sub
exit /b %ERRORLEVEL%

:do_install_apk
call :do_install_apk_sub
exit /b %ERRORLEVEL%

:do_adb_reverse
call :do_adb_reverse_sub
exit /b %ERRORLEVEL%

:do_check
call :do_check_sub
exit /b %ERRORLEVEL%

:do_all_sub
echo.
echo [Step 1/3] Registering Windows 10 DirectShow Virtual Camera...
call :do_register_sub
if errorlevel 1 exit /b 1

echo.
echo [Step 2/3] Checking Android device ADB connection for APK install...
set "HAS_ADB_DEVICE="
if defined ADB_EXE (
    for /f "skip=1 tokens=1,2" %%A in ('"%ADB_EXE%" devices 2^>nul') do (
        if "%%B"=="device" set "HAS_ADB_DEVICE=1"
    )
)
if defined HAS_ADB_DEVICE (
    call :do_install_apk_sub
) else (
    echo [INFO] No USB-debugging Android device attached. Skipping automatic APK install.
    echo        Copy "%APK_FILE%" to your Android phone or connect USB and select [4].
)

echo.
echo [Step 3/3] Launching PhoneCamReceiver.exe...
if exist "%RECEIVER_EXE%" (
    start "" "%RECEIVER_EXE%"
    echo [OK] PhoneCamReceiver.exe started.
) else (
    echo [ERROR] PhoneCamReceiver.exe not found: "%RECEIVER_EXE%"
    exit /b 1
)
exit /b 0

:do_register_sub
if not exist "%VCAM_DLL%" (
    echo [ERROR] vcam-dshow.dll not found: "%VCAM_DLL%"
    exit /b 1
)
call "%REG_BAT%"
exit /b %ERRORLEVEL%

:do_unregister_sub
if not exist "%VCAM_DLL%" (
    echo [WARN] vcam-dshow.dll not found: "%VCAM_DLL%"
    exit /b 1
)
call "%UNREG_BAT%"
if errorlevel 1 (
    echo [ERROR] Unregister failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)
echo [OK] Virtual Camera unregistered.
exit /b 0

:do_install_apk_sub
if not exist "%APK_FILE%" (
    echo [ERROR] APK not found: "%APK_FILE%"
    echo         Run android\build_apk.bat first.
    exit /b 1
)
if not defined ADB_EXE (
    echo [ERROR] adb.exe not found. Install Android SDK Platform-Tools or place at D:\platform-tools\adb.exe.
    exit /b 1
)
echo [ADB] "%ADB_EXE%" install -r "%APK_FILE%"
"%ADB_EXE%" install -r "%APK_FILE%"
if errorlevel 1 (
    echo [ERROR] APK installation failed. Check USB Debugging prompt on your phone.
    exit /b 1
)
echo [OK] Installed APK onto Android device.
exit /b 0

:do_adb_reverse_sub
if not defined ADB_EXE (
    echo [ERROR] adb.exe not found.
    exit /b 1
)
"%ADB_EXE%" reverse tcp:9876 tcp:9876
if errorlevel 1 (
    echo [ERROR] adb reverse failed. Check USB connection and USB Debugging.
    exit /b 1
)
echo [OK] USB port forwarding enabled (enter 127.0.0.1 : 9876 in Manual IP on phone).
exit /b 0

:do_check_sub
echo.
echo === Portable Bundle Check ===
for %%F in ("%RECEIVER_EXE%" "%VCAM_DLL%" "%VCAM_DLL32%" "%FFMPEG_EXE%" "%APK_FILE%") do (
    if exist "%%~F" (
        echo   [OK] %%~nxF ^(%%~zF bytes^)
    ) else (
        echo   [MISSING] %%~F
    )
)
if defined ADB_EXE (
    echo   [OK] adb.exe: "%ADB_EXE%"
) else (
    echo   [WARN] adb.exe not found
)
reg query "HKLM\SOFTWARE\Classes\CLSID\{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}\InprocServer32" >nul 2>&1
if not errorlevel 1 (
    echo   [OK] DirectShow x64 registered ^(HKLM - Discord/Chrome^)
) else (
    echo   [WARN] DirectShow x64 NOT in HKLM ^(run register_camera.bat as Admin^)
)
reg query "HKLM\SOFTWARE\WOW6432Node\Classes\CLSID\{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}\InprocServer32" >nul 2>&1
if not errorlevel 1 (
    echo   [OK] DirectShow x86 registered ^(WOW6432Node^)
) else (
    echo   [WARN] DirectShow x86 NOT in WOW6432Node ^(run register_camera.bat as Admin^)
)
reg query "HKCU\Software\Classes\CLSID\{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}\InprocServer32" >nul 2>&1
if not errorlevel 1 (
    echo   [OK] DirectShow registered ^(HKCU fallback^)
) else (
    echo   [WARN] DirectShow not registered in HKCU
)
exit /b 0

:locate_adb
set "ADB_EXE="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if exist "D:\platform-tools\adb.exe" set "ADB_EXE=D:\platform-tools\adb.exe"
if not defined ADB_EXE (
    for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%A"
)
exit /b 0
