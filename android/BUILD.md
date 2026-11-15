# Android Companion App Build Guide (Generic Android Device)

## Target Configuration
- **Package**: `com.win10phonelink.webcam`
- **SDK**: `minSdk = 28` (Android 9), `targetSdk = 34`, `compileSdk = 34` (Android 14)
- **Tooling**: Gradle `8.7`, Android Gradle Plugin `8.5.2`, Kotlin `1.9.25`, Temurin JDK `21` (`.toolchain\jdk21`)

## One-Click Build via Windows CLI (`build_apk.bat`)
```bat
cd /d "D:\programming language\pythun\Github\Win10-PhoneLink-WebCam\android"
build_apk.bat
```
- `build_apk.bat` checks `.toolchain\jdk21` (JDK 17–21 required by Gradle 8.7) and `C:\Users\PC\AppData\Local\Android\Sdk` (Android SDK API 34), automatically bootstrapping either if missing, writes `local.properties`, builds `app\build\outputs\apk\debug\app-debug.apk`, and copies the APK to:
  - `dist\Portable-Win10-PhoneLink-WebCam\Win10-PhoneCam.apk`
  - `Win10-PhoneCam.apk` (repo root)

## Install onto Android Device
Use the consolidated installer in the portable folder:
```bat
cd /d "D:\programming language\pythun\Github\Win10-PhoneLink-WebCam\dist\Portable-Win10-PhoneLink-WebCam"
install_all.bat --install-apk
```
Or run `build_apk.bat --install` directly.

## USB ADB Wired Fallback (Optional)
If Wi-Fi AP isolation blocks LAN TCP port `9876`, connect your Android device via USB and run:
```bat
install_all.bat --adb-reverse
```
Then enter `127.0.0.1` (port `9876`) in **Manual IP** on the phone along with the pairing token shown in `PhoneCamReceiver.exe`.
