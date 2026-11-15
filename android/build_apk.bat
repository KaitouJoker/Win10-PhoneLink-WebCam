@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"
set "REPO_ROOT=%~dp0.."
for %%R in ("%REPO_ROOT%") do set "REPO_ROOT=%%~fR"
set "TOOLCHAIN_DIR=%REPO_ROOT%\.toolchain"
set "PORTABLE_DIR=%REPO_ROOT%\dist\Portable-Win10-PhoneLink-WebCam"

echo === Win10 PhoneLink WebCam - Android APK Builder ===

rem 1. Locate JDK 17 or 21 (check .toolchain\jdk21 first, then system installs)
set "FOUND_JDK="
for %%D in (
    "%TOOLCHAIN_DIR%\jdk21"
    "%TOOLCHAIN_DIR%\jdk17"
    "%JAVA_HOME%"
    "C:\Program Files\Android\Android Studio\jbr"
    "D:\Android\Android Studio\jbr"
    "C:\Program Files\Eclipse Adoptium\jdk-17*"
    "C:\Program Files\Eclipse Adoptium\jdk-21*"
    "C:\Program Files\Microsoft\jdk-17*"
    "C:\Program Files\Microsoft\jdk-21*"
    "C:\Program Files\Java\jdk-17*"
    "C:\Program Files\Java\jdk-21*"
    "D:\Java\jdk-17*"
    "D:\Java\jdk-21*"
) do (
    if not defined FOUND_JDK (
        for /d %%P in (%%D) do (
            if exist "%%~P\bin\java.exe" (
                "%%~P\bin\java.exe" -version 2>&1 | findstr /R /C:"\"17\." /C:"\"21\." >nul
                if !ERRORLEVEL! equ 0 set "FOUND_JDK=%%~P"
            )
        )
    )
)

if not defined FOUND_JDK (
    echo [INFO] JDK 17/21 not found locally. Bootstrapping portable Temurin JDK 21 into .toolchain\jdk21 ...
    if not exist "%TOOLCHAIN_DIR%" mkdir "%TOOLCHAIN_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
        "$zip=Join-Path $env:TEMP 'temurin21-boot.zip';" ^
        "$tmp=Join-Path $env:TEMP 'temurin21-boot-dir';" ^
        "if(Test-Path $tmp){Remove-Item -Recurse -Force $tmp};" ^
        "Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' -OutFile $zip -UseBasicParsing;" ^
        "Expand-Archive -Path $zip -DestinationPath $tmp -Force;" ^
        "$inner=Get-ChildItem $tmp -Directory | Select-Object -First 1;" ^
        "Move-Item -Path $inner.FullName -Destination '%TOOLCHAIN_DIR%\jdk21' -Force;" ^
        "Remove-Item -Force $zip -ErrorAction SilentlyContinue; Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue"
    if exist "%TOOLCHAIN_DIR%\jdk21\bin\java.exe" set "FOUND_JDK=%TOOLCHAIN_DIR%\jdk21"
)

if defined FOUND_JDK (
    set "JAVA_HOME=!FOUND_JDK!"
    set "PATH=!JAVA_HOME!\bin;!PATH!"
    echo [OK] Using JDK: !JAVA_HOME!
) else (
    echo [ERROR] Failed to locate or bootstrap JDK 17/21. Gradle 8.7 requires JDK 17..21.
    exit /b 1
)

rem 2. Locate Android SDK (or auto-bootstrap cmdline-tools + API 34)
set "SDK_DIR="
for %%S in (
    "%ANDROID_HOME%"
    "%ANDROID_SDK_ROOT%"
    "%LOCALAPPDATA%\Android\Sdk"
    "%TOOLCHAIN_DIR%\android-sdk"
    "C:\Android\Sdk"
    "D:\Android\Sdk"
) do (
    if not defined SDK_DIR (
        if exist "%%~S\platforms\android-34" set "SDK_DIR=%%~S"
    )
)

if not defined SDK_DIR (
    set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"
    echo [INFO] Android SDK API 34 not found. Installing into "!SDK_DIR!" ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
        "$sdk='!SDK_DIR!'; $latest=Join-Path $sdk 'cmdline-tools\latest';" ^
        "if(-not (Test-Path (Join-Path $latest 'bin\sdkmanager.bat'))){" ^
        "  $zip=Join-Path $env:TEMP 'android-cli-boot.zip'; $tmp=Join-Path $env:TEMP 'android-cli-dir';" ^
        "  if(Test-Path $tmp){Remove-Item -Recurse -Force $tmp};" ^
        "  Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip -UseBasicParsing;" ^
        "  Expand-Archive -Path $zip -DestinationPath $tmp -Force;" ^
        "  New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null;" ^
        "  Move-Item -Path (Join-Path $tmp 'cmdline-tools') -Destination $latest -Force;" ^
        "  Remove-Item -Force $zip -ErrorAction SilentlyContinue; Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue" ^
        "}"
    set "SDKMGR=!SDK_DIR!\cmdline-tools\latest\bin\sdkmanager.bat"
    if exist "!SDKMGR!" (
        echo y| call "!SDKMGR!" --sdk_root="!SDK_DIR!" --licenses >nul 2>&1
        call "!SDKMGR!" --sdk_root="!SDK_DIR!" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    )
)

if not exist "%SDK_DIR%\platforms\android-34" (
    echo [ERROR] Android SDK API 34 setup failed. Install Android Studio or set ANDROID_HOME.
    exit /b 1
)

set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"
echo [OK] Using Android SDK: %SDK_DIR%

set "SDK_PROP=%SDK_DIR:\=/%"
echo sdk.dir=%SDK_PROP%> local.properties
echo [OK] Synced local.properties

rem 3. Bootstrap gradle-wrapper.jar if absent
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    where gradle >nul 2>&1
    if !ERRORLEVEL! equ 0 (
        echo [INFO] Generating Gradle 8.7 wrapper via system gradle...
        call gradle wrapper --gradle-version 8.7
    ) else (
        echo [INFO] Downloading Gradle 8.7 to bootstrap wrapper...
        powershell -NoProfile -ExecutionPolicy Bypass -Command ^
            "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
            "$zip=Join-Path $env:TEMP 'gradle-8.7-bin.zip';" ^
            "$dir=Join-Path $env:TEMP 'gradle-8.7-boot';" ^
            "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' -OutFile $zip -UseBasicParsing;" ^
            "Expand-Archive -Path $zip -DestinationPath $dir -Force;" ^
            "& (Join-Path $dir 'gradle-8.7\bin\gradle.bat') wrapper --gradle-version 8.7"
    )
)

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERROR] Failed to initialize gradle-wrapper.jar.
    exit /b 1
)

rem 4. Build Debug APK
echo [BUILD] Running gradlew.bat assembleDebug ...
call gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Gradle build failed.
    exit /b %ERRORLEVEL%
)

set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK_PATH%" (
    echo [ERROR] Expected APK not found at %APK_PATH%
    exit /b 1
)

echo [SUCCESS] APK built: %CD%\%APK_PATH%

if not exist "%PORTABLE_DIR%" mkdir "%PORTABLE_DIR%"
copy /y "%APK_PATH%" "%PORTABLE_DIR%\Win10-PhoneCam.apk" >nul
copy /y "%APK_PATH%" "%REPO_ROOT%\Win10-PhoneCam.apk" >nul
echo [OK] Deployed APK to "%PORTABLE_DIR%\Win10-PhoneCam.apk"
echo [OK] Deployed APK to "%REPO_ROOT%\Win10-PhoneCam.apk"

if /i "%~1"=="--install" (
    set "ADB_EXE="
    if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
    if not defined ADB_EXE if exist "D:\platform-tools\adb.exe" set "ADB_EXE=D:\platform-tools\adb.exe"
    if not defined ADB_EXE (
        for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%A"
    )
    if defined ADB_EXE (
        echo [ADB] Installing APK onto connected Android device...
        "!ADB_EXE!" install -r "%PORTABLE_DIR%\Win10-PhoneCam.apk"
    ) else (
        echo [WARN] adb.exe not found; install manually via: adb install -r "%PORTABLE_DIR%\Win10-PhoneCam.apk"
    )
)

endlocal
