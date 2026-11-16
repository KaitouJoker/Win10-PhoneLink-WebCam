# Win10 PhoneCam (Win10-PhoneLink-WebCam)

> **[한국어 문서 (README_KR.md)](README_KR.md)** | **English**

Wireless Android-to-Windows 10/11 Virtual Camera solution — **zero Phone Link app required**.  
Scan a QR code from the portable Windows receiver with your Android phone to stream hardware-encoded low-latency H.264/HEVC video directly into **Discord**, **OBS**, **Zoom**, **Teams**, and **Chrome/Edge** as a native DirectShow webcam (`Win10 PhoneLink Virtual Camera`).

---

## Key Features

- **QR Code Instant Pairing**: Auto-syncs IP, TCP port (`9876`), pairing token, target codec, and resolution between PC and phone (also supports manual IP & ADB `127.0.0.1` tunneling).
- **Hardware-Accelerated Encode & Decode Pipeline**:
  - **Android**: Inspects on-device `MediaCodec` encoders and lets you choose supported hardware accelerators (`H.264 / AVC` or `HEVC / H.265`), lens (`Wide`, `Ultra-Wide`, `Tele`, `Front`), FPS (`30` / `60 fps`), resolution (`1080p` / `720p` / `540p` / `360p`), bitrate (`4`–`200 Mbps`), and rotation lock.
  - **Windows PC**: Hardware decoding via **NVIDIA NVDEC** (`h264_cuvid` / `hevc_cuvid`), **Direct3D 11 (`d3d11va`)**, or **CPU** fallback, with selectable GPU index.
- **Native DirectShow Virtual Camera (`x64` & `x86`)**:
  - Registers both 64-bit (`vcam-dshow.dll`) and 32-bit (`vcam-dshow32.dll`) filters into `HKLM` so desktop apps including **Discord** recognize `Win10 PhoneLink Virtual Camera`.
  - Automatically matches native **Portrait** (`720x1280` / `1080x1920`) and **Landscape** (`1280x720` / `1920x1080`) orientations without black letterbox or pillarbox bars in **Discord**, **OBS**, and browsers.
  - Outputs an **unflipped RAW standby image by default**, with instant **Horizontal Flip** and **Vertical Flip** toggles in Settings, the quick bar, and the right-click context menu.
- **Resource-Saver Preview Toggle**:
  - Right-click the PC preview pane to turn Live Preview **ON / OFF** at any time while keeping virtual camera output running.
- **Bilingual UI (`English / 한국어`)**:
  - Auto-detects the OS system language on first launch on both Windows and Android, and lets you switch anytime from **Settings** (PC Settings Tab -> Card 5, Android **Settings** header button or **Language** dropdown).
- **Portable Deployment**:
  - No installer wizard required — run straight from `dist/Portable-Win10-PhoneLink-WebCam/`.

---

## Portable Quick Start

All runtime files are collected under `dist/Portable-Win10-PhoneLink-WebCam/`:

1. **Ensure FFmpeg is present** (if building from source): place `ffmpeg.exe` in `dist/Portable-Win10-PhoneLink-WebCam/` (next to `PhoneCamReceiver.exe`).
2. **Run setup as Administrator**:
   - Execute `install_all.bat` (right-click -> **Run as administrator**) to register `vcam-dshow.dll` & `vcam-dshow32.dll` (`HKLM`) and install `Win10-PhoneCam.apk` over USB ADB if connected; or
   - Run `register_camera.bat` as Administrator to register only the DirectShow virtual camera, and sideload `Win10-PhoneCam.apk` onto your Android device (`Android 9+`).
3. **Launch `PhoneCamReceiver.exe`** on Windows 10/11.
4. **Open `Win10 PhoneCam`** on Android, tap **Scan QR**, and point at the QR code in `PhoneCamReceiver.exe`.
5. **Select `Win10 PhoneLink Virtual Camera`** in **Discord**, **OBS**, **Zoom**, or your browser.

---

## Dedicated Settings Pages

### Windows PC (`PhoneCamReceiver.exe`)
Open the **Settings** tab (or click **Settings** in the bottom quick bar):
1. **Resolution**: `1920x1080`, `1280x720`, `960x540`, `640x360`, or `Auto (match phone)`.
2. **Video Codec**: `H.264 / AVC`, `HEVC / H.265`, or `Auto` (auto-synced in QR payload).
3. **Decoder Backend & GPU**: `NVIDIA NVDEC`, `Direct3D 11 (d3d11va)`, or `CPU`, plus multi-GPU device selector.
4. **Orientation Flip**: Independent **Horizontal Flip** and **Vertical Flip** toggles (also accessible via the quick bar and right-click context menu).
5. **Language**: Switch between **한국어 (Korean)** and **English** (auto-detected from Windows locale on first launch and persisted in `settings.json`).

### Android (`Win10 PhoneCam.apk`)
- Tap **Settings** in the top-right header (or configure inline in the **Stream Settings** card):
  - **Language**: `한국어 (Korean)` / `English` (auto-detected from Android OS locale on first run).
  - **Inspect / Select HW Encoders**: View all detected hardware/software `MediaCodec` encoders with vendor, CBR/VBR, and `1080p60` support flags.
  - **Camera Lens / Codec / Resolution / FPS / Bitrate / Orientation**: Saved automatically and applied seamlessly.

---

## Building from Source

### 1. PC Host (`PhoneCamReceiver.exe`)
Requires **.NET 8 SDK**:
```powershell
dotnet publish "pc/receiver-host/PhoneCamReceiver.csproj" `
  -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true `
  -p:IncludeNativeLibrariesForSelfExtract=true `
  -p:EnableCompressionInSingleFile=true `
  -o "dist/Portable-Win10-PhoneLink-WebCam"
```

### 2. Android APK (`Win10-PhoneCam.apk`)
Requires **JDK 17–21** and **Android SDK (API 34)**:
```cmd
android\build_apk.bat
```
Outputs the APK to both `dist/Portable-Win10-PhoneLink-WebCam/Win10-PhoneCam.apk` and the repository root `Win10-PhoneCam.apk`.

### 3. DirectShow Virtual Camera DLLs (`vcam-dshow.dll` / `vcam-dshow32.dll`)
Built with **CMake + MSVC (x64 & Win32)** from `pc/vcam-dshow/`.

### 4. Automated Verification
```powershell
python pc/tests/e2e_verify.py
```

---

## Notes & Known Limitations
- **Administrator Privileges for Discord**: Apps like Discord require `HKLM` COM registration of `vcam-dshow.dll` / `vcam-dshow32.dll`. If status shows `HKCU Only`, click **Register VCam** in an elevated `PhoneCamReceiver.exe` or run `register_camera.bat` as Administrator.
- **`ffmpeg.exe` Excluded from Git**: `ffmpeg.exe` is gitignored due to GitHub file size limits; include your own `ffmpeg.exe` build (with `h264_cuvid`/`hevc_cuvid` if using NVDEC) next to `PhoneCamReceiver.exe`.
- **Same Subnet Required for Wi-Fi**: Ensure PC and phone are on the same local network (or forward TCP port `9876` via `adb reverse tcp:9876 tcp:9876` and connect to `127.0.0.1`).
