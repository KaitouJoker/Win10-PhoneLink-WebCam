# Win10-PhoneLink-WebCam

Windows 10 환경에서 **Android 스마트폰** 카메라를 PC의 표준 DirectShow 웹캠(`"Win10 PhoneLink Virtual Camera"`)으로 사용하기 위한 저지연 로컬 스트리밍 브리지입니다.

Windows 11의 Phone Link(휴대폰과 연결) 네이티브 웹캠은 Windows 11 전용 API(`MFCreateVirtualCamera`, `CrossDeviceService.exe`)에 의존하므로 Windows 10에서는 동작하지 않습니다. 본 프로젝트는 커널 드라이버나 EV 코드 서명 없이 동작하는 사용자 모드 DirectShow 가상 카메라(`vcam-dshow.dll` / `vcam-dshow32.dll`), .NET 10 포터블 수신기(`PhoneCamReceiver.exe`), Android 컴패니언 앱(`Win10-PhoneCam.apk`)으로 구성됩니다.

---

## 주요 기능

- **범용 Android 기기 지원**: 특정 스마트폰 모델에 종속되지 않고 Android 카메라 장치 전반에서 동작합니다(`Win10 PhoneLink Virtual Camera`).
- **앱 실행 시 HW 가속 인코더 자동 탐색·선택**: `MediaCodecList`로 기기가 지원하는 H.264 / HEVC 하드웨어 가속 인코더(`Qualcomm c2.qti.*`, `Exynos c2.exynos.*`, `Samsung c2.sec.*`, `MediaTek c2.mtk.*` 등)와 `1080p60` 지원 여부를 자동 탐색해 최초 실행 시 선택 대화상자를 표시하며, 설정 버튼으로 언제든 전환할 수 있습니다.
- **QR 코드 간편 연결**: PC 수신기 화면의 QR 코드를 스캔하면 IP, 포트, 토큰뿐 아니라 PC에서 설정한 **해상도·코덱**까지 스마트폰 앱과 자동 동기화됩니다.
- **PC GPU 하드웨어 디코딩 가속**: **NVIDIA NVDEC** (`h264_cuvid` / `hevc_cuvid`, 다중 GPU 선택 지원), **Direct3D 11 (`D3D11VA`)**, **Software CPU** 선택 가능.
- **미리보기 마우스 우클릭 ON/OFF (리소스 절감 모드)**: 연결 중 실시간 미리보기 창에서 **마우스 우클릭 → 실시간 미리보기 화면 끄기**를 선택하면 비트맵 복사·UI 렌더링을 즉시 중단해 CPU/메모리를 절약하면서도 가상 카메라 출력(`Local\PhoneLinkCam_Shm`, 최대 60fps)은 정상 유지됩니다.
- **60fps 저지연 파이프라인 및 Discord/Chrome/OBS 호환**: FFmpeg `-framerate` + `-fps_mode passthrough` 및 DirectShow x64/x86(`HKLM` + `WOW6432Node`) 이중 등록, `WM_DEVICECHANGE` 브로드캐스트를 지원합니다.

---

## 1. 포터블 배포 폴더 (`dist\Portable-Win10-PhoneLink-WebCam\`)

- [`install_all.bat`](dist/Portable-Win10-PhoneLink-WebCam/install_all.bat) — **통합 설치·관리 스크립트** (가상 카메라 등록, Android APK ADB 설치, USB 포트 포워딩, 환경 점검, 수신기 실행)
- `register_camera.bat` / `unregister_camera.bat` — 관리자 권한(UAC)으로 x64(`vcam-dshow.dll`) + x86(`vcam-dshow32.dll`) 가상 카메라를 `HKLM`·`WOW6432Node`·`HKCU`에 등록/해제
- `PhoneCamReceiver.exe` — PC 수신기 및 설정 GUI (.NET 10 `net10.0-windows` win-x64 자체 포함 단일 실행 파일)
- `vcam-dshow.dll` / `vcam-dshow32.dll` — Windows 10 DirectShow 가상 카메라 COM 필터 (x64 / x86)
- `Win10-PhoneCam.apk` — Android 컴패니언 앱 (`com.win10phonelink.webcam`)
- `ffmpeg.exe` *(별도 배치 권장)* — GitHub 단일 파일 100MB 용량 제한으로 저장소에서 제외되어 있습니다.

> **참고 (`ffmpeg.exe` 위치)**: `dist\Portable-Win10-PhoneLink-WebCam\ffmpeg.exe`에 두거나 `D:\ffmpeg\bin\ffmpeg.exe` 또는 시스템 `PATH`에 FFmpeg가 설치되어 있으면 자동 탐색됩니다(`install_all.bat` 실행 시 `D:\ffmpeg\bin\ffmpeg.exe`가 존재하면 번들 폴더로 자동 복사).

### 빠른 설치 및 실행

1. **통합 설치 및 가상 카메라 등록**:
   - [`dist\Portable-Win10-PhoneLink-WebCam\install_all.bat`](dist/Portable-Win10-PhoneLink-WebCam/install_all.bat) 실행 (또는 `--all` / `--register-vcam`) 후 **UAC 관리자 권한 승인**.
   - CLI 옵션:
     - `install_all.bat --all` : 가상 카메라 등록 + USB 연결 Android 기기에 APK 설치 + `PhoneCamReceiver.exe` 실행
     - `install_all.bat --register-vcam` / `--unregister-vcam` : 가상 카메라 등록/해제
     - `install_all.bat --install-apk` : `Win10-PhoneCam.apk` USB ADB 설치
     - `install_all.bat --adb-reverse` : USB 유선 폴백 (`adb reverse tcp:9876 tcp:9876`, 앱 수동 입력 IP `127.0.0.1`)
     - `install_all.bat --check` : 포터블 파일 및 DirectShow 등록 상태 일괄 점검
2. **Discord / Chrome / OBS에서 사용하기**:
   - 관리자 권한(UAC 승인)으로 `register_camera.bat` 1회 실행 후 Discord가 열려 있었다면 **Discord를 재시작하거나 창에서 `Ctrl+R`** 을 눌러 카메라 목록을 새로고침하세요.
   - 카메라 선택 목록에서 **`Win10 PhoneLink Virtual Camera`** 를 선택합니다.
3. **PC 미리보기 화면 끄기/켜기 (리소스 절감)**:
   - `PhoneCamReceiver.exe`의 `실시간 화면 (Live Stream)` 미리보기 영역에서 **마우스 우클릭** → **`실시간 미리보기 화면 끄기 - 리소스 절감 (Preview OFF)`** / **`켜기 (Preview ON)`** 선택 (`settings.json`에 상태 저장).

---

## 2. 설정 구성

### PC 수신기 설정 (`설정 (해상도 / 코덱 / 디코더 장치)` 탭)
- **해상도 설정**: `1280×720 (HD 720p - 권장)`, `1920×1080 (Full HD 1080p)`, `960×540 (qHD 540p)`, `640×480 (VGA 480p)`, `640×360 (nHD 360p)`, `Auto (휴대폰 전송 원본 해상도 유지)`
- **코덱 설정**: `H.264 / AVC`, `HEVC / H.265`, `Auto`
- **디코더 장치 설정**: `NVIDIA NVDEC (CUDA / CUVID)`, `Direct3D 11 (D3D11VA)`, `Software CPU` 및 GPU 장치 선택

### Android 컴패니언 앱 (`Win10 PhoneCam`)
- 최초 실행 시 **기기 지원 HW 가속 인코더 목록** 대화상자에서 코덱/벤더/`1080p60` 지원 여부를 확인해 바로 선택할 수 있으며, 메인 화면의 **`장치 지원 HW 가속 목록 확인 / 선택`** 버튼 또는 스피너에서 카메라 렌즈, 해상도, FPS(30/60), 코덱, 인코더 장치, 비트레이트, 회전 모드를 변경할 수 있습니다.

---

## 3. 소스에서 직접 빌드 및 검증

- **DirectShow 필터 (x64 + x86)**:
  ```bat
  pc\vcam-dshow\build.bat
  ```
- **PC 수신기 (`PhoneCamReceiver.exe`)**:
  ```bat
  dotnet publish pc\receiver-host\PhoneCamReceiver.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o dist\Portable-Win10-PhoneLink-WebCam
  ```
- **Android APK (`Win10-PhoneCam.apk`)**:
  ```bat
  cd android
  build_apk.bat
  ```
- **E2E 자동 검증**:
  ```bat
  python pc\tests\e2e_verify.py
  ```

---

## 4. 지원 앱 및 제한 사항

- **지원 앱**: Discord, Google Chrome, Microsoft Edge(WebRTC), Zoom, OBS Studio 등 데스크톱 DirectShow 지원 앱.
- **제한 사항**: Windows 10 정책상 커널 AVStream 드라이버 없이는 **UWP 앱(Windows 기본 카메라 앱)** 에 표시되지 않으며, 가상 마이크(오디오)는 포함되지 않습니다.
