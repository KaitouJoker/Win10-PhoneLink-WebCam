# Win10 PhoneCam (Win10-PhoneLink-WebCam)

> **한국어** | **[English Documentation (README.md)](README.md)**

Windows 휴대폰과 연결(Phone Link) 앱 없이 동작하는 **Android ↔ Windows 10/11 무선 가상 웹캠** 솔루션입니다.  
안드로이드 스마트폰으로 PC 수신기의 QR 코드를 스캔하면 하드웨어 가속 H.264/HEVC 영상을 저지연으로 전송해 **Discord**, **OBS**, **Zoom**, **Teams**, **브라우저** 등에서 기본 DirectShow 웹캠(`Win10 PhoneLink Virtual Camera`)으로 바로 사용할 수 있습니다.

---

## 주요 기능

- **QR 코드 원클릭 연결**: IP, TCP 포트(`9876`), 보안 토큰, 코덱, 목표 해상도를 QR에 담아 자동 동기화하며, 수동 IP 입력 및 USB ADB(`127.0.0.1`) 연결도 지원합니다.
- **종단간 하드웨어 가속 파이프라인**:
  - **Android**: 기기에서 지원하는 `MediaCodec` 하드웨어 인코더 목록을 자동 스캔/표시하며 `H.264 / AVC`, `HEVC / H.265`, 카메라 렌즈, FPS(`30` / `60 fps`), 해상도(`1080p` / `720p` / `540p` / `360p`), 비트레이트(`4`~`200 Mbps`), 회전 모드를 선택할 수 있습니다.
  - **Windows PC**: **NVIDIA NVDEC**(`h264_cuvid` / `hevc_cuvid`), **Direct3D 11 (`d3d11va`)**, **CPU 소프트웨어 디코딩** 선택 및 다중 GPU 장치 지정을 지원합니다.
- **DirectShow 가상 카메라 (`64-bit` & `32-bit`)**:
  - `vcam-dshow.dll`(x64) 및 `vcam-dshow32.dll`(x86) 필터를 `HKLM`에 등록하여 **Discord**를 포함한 32/64비트 데스크톱 앱에서 `Win10 PhoneLink Virtual Camera`가 인식됩니다.
  - **세로(Portrait)** (`720x1280` / `1080x1920`) 및 **가로(Landscape)** (`1280x720` / `1920x1080`) 방향을 동적으로 일치시켜 **Discord**, **OBS**, 브라우저에서 좌/우·상/하 검정 래터박스 없이 영상 영역만 출력합니다.
  - 대기 화면 기본 출력은 **반전 없는 RAW 정방향**이며, 설정 및 실시간 하단 바/우클릭 메뉴에서 **좌/우 반전** 및 **상/하 반전**을 즉시 토글할 수 있습니다.
- **리소스 절감용 미리보기 ON/OFF**:
  - PC 미리보기 영역에서 **마우스 우클릭 → 실시간 미리보기 끄기/켜기**를 통해 가상 카메라 송출을 유지하면서 UI 리소스를 절약할 수 있습니다.
- **다국어 지원 (`한국어 / English`)**:
  - PC 및 Android 앱 모두 최초 실행 시 **시스템(OS) 언어를 자동 감지**하여 한국어/English로 시작하며, 각 앱의 **설정 페이지**에서 언제든지 언어를 전환할 수 있습니다.
- **포터블 설치 및 실행**:
  - 별도 인스톨러 없이 `dist/Portable-Win10-PhoneLink-WebCam/` 폴더만으로 즉시 실행할 수 있습니다.

---

## 포터블 빠른 시작

모든 실행 파일은 `dist/Portable-Win10-PhoneLink-WebCam/` 폴더에 정리되어 있습니다:

1. **`ffmpeg.exe` 준비** (소스에서 직접 클론한 경우): GitHub 용량 제한으로 제외된 `ffmpeg.exe`를 `PhoneCamReceiver.exe`와 같은 폴더에 배치합니다.
2. **관리자 권한으로 설치 스크립트 실행**:
   - `install_all.bat`을 **관리자 권한으로 실행**하면 `vcam-dshow.dll` / `vcam-dshow32.dll` 등록(`HKLM`)과 USB 연결된 기기에 `Win10-PhoneCam.apk` 설치까지 한 번에 진행됩니다.
   - 또는 `register_camera.bat`을 관리자 권한으로 실행해 가상 카메라만 등록하고, `Win10-PhoneCam.apk`를 스마트폰(`Android 9+`)에 직접 설치하세요.
3. **`PhoneCamReceiver.exe` 실행**
4. **스마트폰에서 `Win10 PhoneCam` 앱 실행 → `QR 스캔`**으로 PC 화면의 QR 코드를 스캔합니다.
5. **Discord / OBS / Zoom** 등의 카메라 설정에서 **`Win10 PhoneLink Virtual Camera`**를 선택합니다.

---

## 전용 설정 페이지 안내

### Windows PC (`PhoneCamReceiver.exe`)
상단 **설정** 탭(또는 하단 바의 **설정 열기** 버튼)에서 다음 항목을 설정할 수 있습니다:
1. **해상도 설정**: `1920x1080`, `1280x720`, `960x540`, `640x360`, `Auto (휴대폰 해상도 유지)`
2. **코덱 설정**: `H.264 / AVC`, `HEVC / H.265`, `Auto` (QR 코드에 포함되어 모바일 앱과 자동 동기화)
3. **디코더 장치 설정**: `NVIDIA NVDEC`, `Direct3D 11`, `Software CPU` 선택 및 GPU 장치 목록 새로고침
4. **화면 반전**: **좌/우 반전** / **상/하 반전** 독립 토글 (대기 화면 기본값은 반전 없는 RAW 정방향)
5. **언어 설정**: **한국어 (Korean)** / **English** 전환 (초기 실행 시 OS 로케일 자동 인식, `settings.json` 저장)

### Android (`Win10 PhoneCam.apk`)
- 우측 상단 **설정** 버튼 또는 하단 **스트리밍 설정 카드**에서 변경 가능:
  - **언어 설정 (Language)**: `한국어 (Korean)` / `English` (초기 실행 시 기기 OS 언어 자동 감지)
  - **장치 지원 HW 가속 목록 확인 / 선택**: 기기에서 지원하는 MediaCodec 인코더 목록, 벤더, CBR/VBR 및 `1080p60` 지원 여부 확인 및 선택
  - **카메라 렌즈 / 코덱 / 해상도 / FPS / 비트레이트 / 화면 방향**: 선택 즉시 저장 및 스트림 반영

---

## 소스에서 빌드하기

### 1. PC 수신기 (`PhoneCamReceiver.exe`)
**.NET 8 SDK** 필요:
```powershell
dotnet publish "pc/receiver-host/PhoneCamReceiver.csproj" `
  -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true `
  -p:IncludeNativeLibrariesForSelfExtract=true `
  -p:EnableCompressionInSingleFile=true `
  -o "dist/Portable-Win10-PhoneLink-WebCam"
```

### 2. Android APK (`Win10-PhoneCam.apk`)
**JDK 17–21** 및 **Android SDK (API 34)** 필요:
```cmd
android\build_apk.bat
```
빌드 완료 시 `dist/Portable-Win10-PhoneLink-WebCam/Win10-PhoneCam.apk`와 저장소 루트 `Win10-PhoneCam.apk`에 자동 복사됩니다.

### 3. DirectShow 가상 카메라 DLL (`vcam-dshow.dll` / `vcam-dshow32.dll`)
`pc/vcam-dshow/`에서 **CMake + MSVC (x64 & Win32)**로 빌드합니다.

### 4. 자동화 검증
```powershell
python pc/tests/e2e_verify.py
```

---

## 참고 및 제한사항
- **Discord 인식 권한**: Discord 등 데스크톱 앱은 `HKLM` 레지스트리 등록을 확인합니다. UI 상태가 `HKCU만 등록`으로 표시될 경우 관리자 권한으로 `register_camera.bat` 또는 `PhoneCamReceiver.exe`의 **가상 카메라 등록**을 실행하세요.
- **`ffmpeg.exe` 별도 포함 필요**: GitHub 파일 크기 제한(`100 MB`)으로 `ffmpeg.exe`는 저장소에 포함되지 않습니다. NVDEC를 사용하려면 `h264_cuvid` / `hevc_cuvid`가 포함된 `ffmpeg.exe`를 사용하세요.
- **네트워크 환경**: Wi-Fi 사용 시 PC와 스마트폰이 동일한 로컬 네트워크에 있어야 합니다. 유선 사용 시 `adb reverse tcp:9876 tcp:9876` 후 `127.0.0.1`로 연결할 수 있습니다.
