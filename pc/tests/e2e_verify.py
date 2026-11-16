"""End-to-End Verification Suite for Win10-PhoneLink-WebCam.

Tests:
1. Portable distribution folder completeness.
2. DirectShow virtual camera registration & FFmpeg dshow enumeration.
3. Standby baseline frame capture from "Win10 PhoneLink Virtual Camera".
4. QR code JSON/PNG generation + OpenCV decode round-trip & invalid token rejection.
5. Full live H.264 pipeline: ffmpeg Annex-B -> PLWC TCP -> PhoneCamReceiver (NVDEC h264_cuvid)
   -> Local\\PhoneLinkCam_Shm -> DirectShow virtual camera -> ffmpeg PNG capture + pixel/motion verification.
6. Full live HEVC pipeline: ffmpeg Annex-B -> PLWC TCP -> PhoneCamReceiver (NVDEC hevc_cuvid)
   -> Local\\PhoneLinkCam_Shm -> DirectShow virtual camera -> ffmpeg PNG capture + pixel/motion verification.
7. Android companion project completeness & wire protocol compatibility audit.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wintypes
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import winreg
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


TESTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TESTS_DIR.parent.parent
PORTABLE_DIR = REPO_ROOT / "dist" / "Portable-Win10-PhoneLink-WebCam"
ANDROID_DIR = REPO_ROOT / "android"
PC_PROTOCOL_CS = REPO_ROOT / "pc" / "receiver-host" / "Protocol.cs"

RECEIVER_EXE = PORTABLE_DIR / "PhoneCamReceiver.exe"
VCAM_DLL = PORTABLE_DIR / "vcam-dshow.dll"
VCAM_DLL32 = PORTABLE_DIR / "vcam-dshow32.dll"
REG_BAT = PORTABLE_DIR / "register_camera.bat"
UNREG_BAT = PORTABLE_DIR / "unregister_camera.bat"
INSTALL_ALL_BAT = PORTABLE_DIR / "install_all.bat"
PORTABLE_APK = PORTABLE_DIR / "Win10-PhoneCam.apk"
PORTABLE_FFMPEG = PORTABLE_DIR / "ffmpeg.exe"
SYSTEM_FFMPEG = Path(r"D:\ffmpeg\bin\ffmpeg.exe")

VCAM_NAME = "Win10 PhoneLink Virtual Camera"
VCAM_CLSID = "{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}"

SHM_NAME = "Local\\PhoneLinkCam_Shm"
SHM_MAGIC = 0x504C434D  # 'PLCM'
SHM_HEADER_SIZE = 64
SHM_SLOT_SIZE = 1920 * 1080 * 4
SHM_TOTAL_SIZE = SHM_HEADER_SIZE + 3 * SHM_SLOT_SIZE

TEST_PORT = 19876
TEST_TOKEN = "TESTTOKEN12345678"

START_CODE_RE = re.compile(rb"(?:\x00\x00\x00\x01|\x00\x00\x01)")

FILE_MAP_READ = 0x0004
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
kernel32.OpenFileMappingW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
kernel32.OpenFileMappingW.restype = wintypes.HANDLE
kernel32.MapViewOfFile.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t]
kernel32.MapViewOfFile.restype = ctypes.c_void_p
kernel32.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
kernel32.UnmapViewOfFile.restype = wintypes.BOOL
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.CloseHandle.restype = wintypes.BOOL


@dataclass
class ShmHeaderSnapshot:
    magic: int
    version: int
    write_seq: int
    active_slot: int
    frame_state: int
    width: int
    height: int
    pixel_format: int
    stride: int
    timestamp_100ns: int
    payload_size: int
    flip_flags: int = 0
    orientation_hint: int = 0


def read_shm_header() -> ShmHeaderSnapshot:
    h_map = kernel32.OpenFileMappingW(FILE_MAP_READ, False, SHM_NAME)
    if not h_map:
        err = ctypes.get_last_error()
        raise AssertionError(f"Failed to open {SHM_NAME} (Win32 error {err})")
    try:
        ptr = kernel32.MapViewOfFile(h_map, FILE_MAP_READ, 0, 0, SHM_HEADER_SIZE)
        if not ptr:
            err = ctypes.get_last_error()
            raise AssertionError(f"MapViewOfFile failed (Win32 error {err})")
        try:
            raw = ctypes.string_at(ptr, SHM_HEADER_SIZE)
            fields = struct.unpack("<IIQIIIIIIqI12s", raw)
            return ShmHeaderSnapshot(
                magic=fields[0],
                version=fields[1],
                write_seq=fields[2],
                active_slot=fields[3],
                frame_state=fields[4],
                width=fields[5],
                height=fields[6],
                pixel_format=fields[7],
                stride=fields[8],
                timestamp_100ns=fields[9],
                payload_size=fields[10],
                flip_flags=fields[11][0],
                orientation_hint=fields[11][1],
            )
        finally:
            kernel32.UnmapViewOfFile(ptr)
    finally:
        kernel32.CloseHandle(h_map)


def wait_for_tcp_port(host: str, port: int, timeout_s: float = 8.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.4):
                return
        except OSError:
            time.sleep(0.1)
    raise AssertionError(f"Timed out waiting for {host}:{port} to accept connections")


def connect_plwc(host: str, port: int, token: str, codec: str, encoder: str, width: int, height: int, fps: int) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(5.0)
    sock.connect((host, port))

    handshake = json.dumps({
        "token": token,
        "device": "Android Phone (E2E Sim)",
        "codec": codec,
        "encoder": encoder,
        "width": width,
        "height": height,
        "fps": fps,
    }).encode("utf-8")

    sock.sendall(b"PLWC" + struct.pack(">I", len(handshake)) + handshake)
    reply = sock.recv(4)
    if reply != b"OK\r\n":
        sock.close()
        raise AssertionError(f"Expected b'OK\\r\\n' handshake reply, got {reply!r}")
    return sock


def send_plwc_packet(sock: socket.socket, pkt_type: int, flags: int, pts_us: int, payload: bytes) -> None:
    header = struct.pack(">BBHIq", pkt_type, flags & 0xFF, 0, len(payload), pts_us)
    sock.sendall(header + payload)


def _read_pe_machine(path: Path) -> int:
    data = path.read_bytes()
    assert data[:2] == b"MZ", f"{path.name} missing MZ header"
    pe_off = struct.unpack_from("<I", data, 0x3C)[0]
    assert data[pe_off:pe_off + 4] == b"PE\x00\x00", f"{path.name} missing PE signature"
    return struct.unpack_from("<H", data, pe_off + 4)[0]


def check_portable_folder() -> None:
    print("[1/11] Verifying portable folder contents...")
    required = [RECEIVER_EXE, VCAM_DLL, VCAM_DLL32, REG_BAT, UNREG_BAT, INSTALL_ALL_BAT, PORTABLE_APK, PORTABLE_FFMPEG]
    for p in required:
        assert p.is_file(), f"Missing portable file: {p}"
        size = p.stat().st_size
        assert size > 0, f"Empty portable file: {p}"
        print(f"  OK: {p.name} ({size:,} bytes)")
    assert _read_pe_machine(VCAM_DLL) == 0x8664, f"{VCAM_DLL.name} is not x64 PE32+"
    assert _read_pe_machine(VCAM_DLL32) == 0x014C, f"{VCAM_DLL32.name} is not x86 PE32"
    print("  OK: PE headers verified (vcam-dshow.dll=0x8664 x64, vcam-dshow32.dll=0x014C x86)")


def _find_active_vcam_hive() -> tuple[int, str, str] | None:
    clsid_subkey = rf"Software\Classes\CLSID\{VCAM_CLSID}\InprocServer32"
    cat_subkey = rf"Software\Classes\CLSID\{{860BB310-5D01-11d0-BD3B-00A0C911CE86}}\Instance\{VCAM_CLSID}"
    for hive, name in ((winreg.HKEY_LOCAL_MACHINE, "HKLM"), (winreg.HKEY_CURRENT_USER, "HKCU")):
        try:
            with winreg.OpenKey(hive, clsid_subkey) as k:
                val, _ = winreg.QueryValueEx(k, None)
            with winreg.OpenKey(hive, cat_subkey):
                pass
            if val:
                return hive, name, val
        except OSError:
            continue
    return None


REG_APP_SUBKEY = r"Software\Win10PhoneLinkWebCam"


def _get_app_dword_reg(value_name: str) -> int | None:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_APP_SUBKEY) as k:
            val, _ = winreg.QueryValueEx(k, value_name)
            return int(val)
    except OSError:
        return None


def _set_app_dword_reg(value_name: str, val: int | None) -> None:
    if val is None:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_APP_SUBKEY, 0, winreg.KEY_SET_VALUE) as k:
                winreg.DeleteValue(k, value_name)
        except OSError:
            pass
    else:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, REG_APP_SUBKEY) as k:
            winreg.SetValueEx(k, value_name, 0, winreg.REG_DWORD, int(val))


def _get_orientation_hint_reg() -> int | None:
    return _get_app_dword_reg("OrientationHint")


def _set_orientation_hint_reg(val: int | None) -> None:
    _set_app_dword_reg("OrientationHint", val)


def _get_flip_flags_reg() -> int | None:
    return _get_app_dword_reg("FlipFlags")


def _set_flip_flags_reg(val: int | None) -> None:
    _set_app_dword_reg("FlipFlags", val)


def check_dshow_registration() -> None:
    print("[2/11] Verifying vcam-dshow.dll registration and DirectShow enumeration...")
    active = _find_active_vcam_hive()
    if active is None or Path(active[2]).resolve() != VCAM_DLL.resolve():
        reg_res = subprocess.run(["cmd.exe", "/c", str(REG_BAT), "--no-elevate"], capture_output=True, text=True, check=False)
        assert reg_res.returncode == 0, f"register_camera.bat failed ({reg_res.returncode}): {reg_res.stderr}"
        active = _find_active_vcam_hive()
    assert active is not None, f"CLSID {VCAM_CLSID} not found in HKLM/HKCU registry"
    hive, hive_name, reg_path = active
    assert Path(reg_path).resolve() == VCAM_DLL.resolve(), f"Registry points to {reg_path}, expected {VCAM_DLL}"
    print(f"  OK: {hive_name} InprocServer32 -> {reg_path}")

    cat_subkey = rf"Software\Classes\CLSID\{{860BB310-5D01-11d0-BD3B-00A0C911CE86}}\Instance\{VCAM_CLSID}"
    with winreg.OpenKey(hive, cat_subkey) as ck:
        friendly_val, _ = winreg.QueryValueEx(ck, "FriendlyName")
        desc_val, _ = winreg.QueryValueEx(ck, "Description")
        filter_data, _ = winreg.QueryValueEx(ck, "FilterData")
    assert friendly_val == VCAM_NAME, f"{hive_name} FriendlyName mismatch: {friendly_val!r}"
    assert desc_val == VCAM_NAME, f"{hive_name} Description mismatch: {desc_val!r}"
    assert len(filter_data) >= 88, f"{hive_name} FilterData unexpectedly short ({len(filter_data)}B)"
    print(f'  OK: {hive_name} category instance FriendlyName & Description == "{VCAM_NAME}" (FilterData={len(filter_data)}B)')

    enum_res = subprocess.run(
        [str(SYSTEM_FFMPEG), "-hide_banner", "-f", "dshow", "-list_devices", "true", "-i", "dummy"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    combined = enum_res.stdout + "\n" + enum_res.stderr
    assert f'"{VCAM_NAME}" (video)' in combined, f'"{VCAM_NAME}" (video) not found in dshow list:\n{combined}'
    print(f'  OK: DirectShow listed "{VCAM_NAME}" (video)')

    # 1) Fresh/portrait orientation isolation (OrientationHint unset and =2) must expose ONLY portrait caps (no 1280x720/640x480 leak)
    for hint_label, hint_val in (("unset", None), ("portrait=2", 2)):
        _set_orientation_hint_reg(hint_val)
        opts_res = subprocess.run(
            [str(SYSTEM_FFMPEG), "-hide_banner", "-f", "dshow", "-list_options", "true", "-i", f"video={VCAM_NAME}"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        opts_text = opts_res.stdout + "\n" + opts_res.stderr
        assert "max s=1080x1920 fps=60" in opts_text, f"[{hint_label}] Expected 1080x1920 60fps portrait cap:\n{opts_text}"
        assert "max s=720x1280 fps=60" in opts_text, f"[{hint_label}] Expected 720x1280 60fps portrait cap:\n{opts_text}"
        assert "s=1280x720" not in opts_text and "s=640x480" not in opts_text, (
            f"[{hint_label}] Landscape capability leaked into portrait idle options:\n{opts_text}"
        )

    # 2) Landscape orientation hint (=1) must expose landscape caps
    _set_orientation_hint_reg(1)
    opts_land = subprocess.run(
        [str(SYSTEM_FFMPEG), "-hide_banner", "-f", "dshow", "-list_options", "true", "-i", f"video={VCAM_NAME}"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    opts_land_text = opts_land.stdout + "\n" + opts_land.stderr
    assert "max s=1920x1080 fps=60" in opts_land_text, f"Expected 1920x1080 60fps capability:\n{opts_land_text}"
    assert "max s=1280x720 fps=60" in opts_land_text, f"Expected 1280x720 60fps capability:\n{opts_land_text}"
    assert "s=720x1280" not in opts_land_text, f"Portrait capability leaked into landscape options:\n{opts_land_text}"
    _set_orientation_hint_reg(2)
    print("  OK: DirectShow orientation isolation verified (portrait idle/unset=720x1280/1080x1920 only; landscape=1280x720/1920x1080 only)")


def _assert_standby_unflipped(arr: np.ndarray, label: str) -> None:
    """Verify standby baseline is RAW (unflipped): top-only corner brackets & bottom-left progress knob."""
    h, w = arr.shape[:2]
    card_w = (w * 3) // 4
    card_h = h // 2
    card_x = (w - card_w) // 2
    card_y = (h - card_h) // 2
    bar_track_w = card_w - 80
    bar_x = card_x + 40
    bar_y = card_y + card_h - 36
    knob_w = max(36, bar_track_w // 6)

    top_bracket_max_b = int(arr[20:23, 20:36, 2].max())
    bot_bracket_max_b = int(arr[h - 23:h - 20, 20:36, 2].max())
    assert top_bracket_max_b >= 190 and bot_bracket_max_b <= 110, (
        f"[{label}] Standby vertical flip detected (top_b={top_bracket_max_b}, bot_b={bot_bracket_max_b})"
    )

    left_bar = arr[bar_y + 2:bar_y + 6, bar_x:bar_x + knob_w + bar_track_w // 4, 2]
    right_bar = arr[bar_y + 2:bar_y + 6, bar_x + bar_track_w - knob_w:bar_x + bar_track_w, 2]
    left_max_b = int(left_bar.max())
    right_max_b = int(right_bar.max())
    assert left_max_b >= 190 and right_max_b <= 110, (
        f"[{label}] Standby horizontal flip detected (left_b={left_max_b}, right_b={right_max_b})"
    )


def capture_standby_baseline() -> tuple[Path, Path]:
    print("[3/11] Capturing portrait (default) & landscape standby baselines from DirectShow (FlipFlags=0 RAW)...")
    portrait_png = TESTS_DIR / "standby_portrait_baseline.png"
    landscape_png = TESTS_DIR / "standby_baseline.png"
    for p in (portrait_png, landscape_png):
        if p.exists():
            p.unlink()

    prev_flip = _get_flip_flags_reg()
    _set_flip_flags_reg(0)
    try:
        _set_orientation_hint_reg(2)
        cmd_p = [
            str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "dshow",
            "-i", f"video={VCAM_NAME}",
            "-frames:v", "1", str(portrait_png),
        ]
        res_p = subprocess.run(cmd_p, capture_output=True, text=True, timeout=15, check=False)
        assert res_p.returncode == 0 and portrait_png.is_file(), f"Portrait standby capture failed: {res_p.stderr}"
        arr_p = np.array(Image.open(portrait_png).convert("RGB"))
        assert arr_p.shape == (1280, 720, 3), f"Expected default standby to be 720x1280 portrait, got {arr_p.shape}"
        _assert_standby_unflipped(arr_p, "portrait")

        _set_orientation_hint_reg(1)
        cmd_l = [
            str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "dshow", "-video_size", "1280x720",
            "-i", f"video={VCAM_NAME}",
            "-frames:v", "1", str(landscape_png),
        ]
        res_l = subprocess.run(cmd_l, capture_output=True, text=True, timeout=15, check=False)
        assert res_l.returncode == 0 and landscape_png.is_file(), f"Landscape standby capture failed: {res_l.stderr}"
        arr_l = np.array(Image.open(landscape_png).convert("RGB"))
        assert arr_l.shape == (720, 1280, 3), f"Unexpected landscape standby dimensions: {arr_l.shape}"
        _assert_standby_unflipped(arr_l, "landscape")
    finally:
        _set_flip_flags_reg(prev_flip)
        _set_orientation_hint_reg(2)

    print(f"  OK: Standby baselines captured unflipped RAW (portrait={arr_p.shape}, landscape={arr_l.shape})")
    return landscape_png, portrait_png


def check_qr_and_token_rejection() -> None:
    print("[4/11] Verifying QR payload generation, OpenCV QR decode, and token rejection...")
    test_ip = "192.168.50.10"
    cli_json_res = subprocess.run(
        [str(RECEIVER_EXE), "--print-qr-json", "--ip", test_ip, "--port", str(TEST_PORT), "--token", TEST_TOKEN],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    )
    qr_obj = json.loads(cli_json_res.stdout.strip())
    assert qr_obj["v"] == 1
    assert qr_obj["ip"] == test_ip
    assert qr_obj["port"] == TEST_PORT
    assert qr_obj["token"] == TEST_TOKEN
    assert isinstance(qr_obj["name"], str) and len(qr_obj["name"]) > 0

    qr_png = TESTS_DIR / "qr_verify.png"
    save_res = subprocess.run(
        [str(RECEIVER_EXE), "--save-qr", str(qr_png), "--ip", test_ip, "--port", str(TEST_PORT), "--token", TEST_TOKEN],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    )
    assert qr_png.is_file() and qr_png.stat().st_size > 500
    img = cv2.imread(str(qr_png))
    decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    assert decoded, "Failed to decode QR PNG with cv2.QRCodeDetector"
    decoded_obj = json.loads(decoded)
    assert decoded_obj == json.loads(save_res.stdout.strip()), f"QR mismatch: {decoded_obj}"
    print(f"  OK: QR PNG decoded cleanly -> {decoded}")

    # Verify wrong token rejection
    proc = subprocess.Popen(
        [str(RECEIVER_EXE), "--headless-test", "--port", str(TEST_PORT + 1), "--token", TEST_TOKEN, "--decoder", "nvdec", "--duration", "4"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        wait_for_tcp_port("127.0.0.1", TEST_PORT + 1, timeout_s=6.0)
        bad_sock = socket.create_connection(("127.0.0.1", TEST_PORT + 1), timeout=3.0)
        bad_handshake = json.dumps({"token": "WRONG_TOKEN_9999", "device": "BadClient", "codec": "h264"}).encode("utf-8")
        bad_sock.sendall(b"PLWC" + struct.pack(">I", len(bad_handshake)) + bad_handshake)
        reply = bad_sock.recv(16)
        bad_sock.close()
        assert reply == b"", f"Expected closed socket on invalid token, got {reply!r}"
        print("  OK: Invalid pairing token rejected cleanly (socket closed without OK)")
    finally:
        proc.wait(timeout=10)


def _nal_info(codec: str, nal_bytes: bytes) -> tuple[bool, bool, bool]:
    if codec == "h264":
        nt = nal_bytes[0] & 0x1F
        return nt in (7, 8), nt in (1, 5), nt == 5
    nt = (nal_bytes[0] >> 1) & 0x3F
    return nt in (32, 33, 34), nt < 32, nt in (19, 20, 21)


def _split_annexb_access_units(codec: str, raw: bytes) -> tuple[bytes | None, list[tuple[int, bytes]]]:
    """Parse complete Annex-B bitstream into optional initial config packet + per-frame (flags, payload) pairs."""
    matches = list(START_CODE_RE.finditer(raw))
    pending_prefix = bytearray()
    pending_config = False
    initial_config: bytes | None = None
    units: list[tuple[int, bytes]] = []

    for idx, m in enumerate(matches):
        sc = raw[m.start():m.end()]
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(raw)
        nal_bytes = raw[m.end():end]
        if not nal_bytes:
            continue
        is_cfg, is_vcl, is_idr = _nal_info(codec, nal_bytes)
        full_nal = sc + nal_bytes
        if is_cfg:
            pending_config = True
            pending_prefix.extend(full_nal)
            continue
        if not is_vcl:
            pending_prefix.extend(full_nal)
            continue
        if pending_config and initial_config is None:
            initial_config = bytes(pending_prefix)
        flags = 0x01 if is_idr else 0x00
        if pending_prefix:
            payload = bytes(pending_prefix) + full_nal
            if pending_config:
                flags |= 0x02
            pending_prefix.clear()
            pending_config = False
        else:
            payload = full_nal
        units.append((flags, payload))
    return initial_config, units


def stream_live_ffmpeg_to_plwc(
    codec: str,
    bg_hex: str,
    tl_hex: str,
    br_hex: str,
    box_hex: str,
    port: int,
    token: str,
    duration_s: int,
    capture_pattern: str,
    standby_png: Path,
    extra_receiver_args: list[str] | None = None,
    expected_tl_hex: str | None = None,
    expected_br_hex: str | None = None,
    expected_flip_flags: int = 0x80,
) -> tuple[list[Path], str]:
    assert codec in ("h264", "hevc")
    expected_cuvid = "h264_cuvid" if codec == "h264" else "hevc_cuvid"
    encoder_label = "e2e-ffmpeg-libx264" if codec == "h264" else "e2e-ffmpeg-libx265"

    receiver_cmd = [
        str(RECEIVER_EXE),
        "--headless-test",
        "--port", str(port),
        "--token", token,
        "--decoder", "nvdec",
        "--duration", str(duration_s),
        *(extra_receiver_args or []),
    ]
    receiver_proc = subprocess.Popen(
        receiver_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    if codec == "h264":
        enc_args = [
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-threads", "1", "-profile:v", "baseline", "-g", "15", "-bf", "0",
        ]
    else:
        enc_args = [
            "-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency",
            "-x265-params", "keyint=15:min-keyint=15:bframes=0:repeat-headers=1:pools=none:frame-threads=1",
            "-threads", "1",
        ]

    ffmpeg_gen_cmd = [
        str(SYSTEM_FFMPEG),
        "-hide_banner", "-loglevel", "error",
        "-re",
        "-f", "lavfi", "-i", f"color=c={bg_hex}:s=1280x720:r=30:d={duration_s}",
        "-f", "lavfi", "-i", f"color=c={box_hex}:s=200x200:r=30:d={duration_s}",
        "-filter_complex",
        f"[0:v]drawbox=x=40:y=40:w=180:h=120:color={tl_hex}:t=fill,"
        f"drawbox=x=1060:y=560:w=180:h=120:color={br_hex}:t=fill[bg];"
        f"[bg][1:v]overlay=x='mod(n*28,1080)':y=260:shortest=1",
        *enc_args,
        "-pix_fmt", "yuv420p",
        "-flush_packets", "1",
        "-f", codec,
        "pipe:1",
    ]
    gen_proc = None
    sock = None
    captured_files = [TESTS_DIR / (capture_pattern % i) for i in range(1, 6)]
    for p in captured_files:
        if p.exists():
            p.unlink()

    try:
        wait_for_tcp_port("127.0.0.1", port, timeout_s=8.0)
        sock = connect_plwc("127.0.0.1", port, token, codec, encoder_label, 1280, 720, 30)

        ctrl_json = json.dumps({"rotation": 0, "camera": "back"}).encode("utf-8")
        send_plwc_packet(sock, 2, 0, 0, ctrl_json)

        gen_proc = subprocess.Popen(
            ffmpeg_gen_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )

        import threading

        stop_streaming = threading.Event()
        stream_errors: list[str] = []
        sent_video_frames = 0

        def pump_annexb() -> None:
            nonlocal sent_video_frames
            assert gen_proc is not None and gen_proc.stdout is not None and sock is not None
            fd = gen_proc.stdout.fileno()
            buf = bytearray()
            pending_prefix = bytearray()
            pending_config = False
            sent_initial_config = False
            pts_us = 0

            def emit_nal(start_code: bytes, nal_bytes: bytes) -> None:
                nonlocal sent_video_frames, pending_config, sent_initial_config, pts_us
                if stop_streaming.is_set():
                    return
                is_cfg, is_vcl, is_idr = _nal_info(codec, nal_bytes)
                full_nal = start_code + nal_bytes
                if is_cfg:
                    pending_config = True
                    pending_prefix.extend(full_nal)
                    return
                if not is_vcl:
                    pending_prefix.extend(full_nal)
                    return

                if pending_config and not sent_initial_config:
                    send_plwc_packet(sock, 1, 0x02, pts_us, bytes(pending_prefix))
                    sent_initial_config = True

                flags = 0x01 if is_idr else 0x00
                if pending_prefix:
                    payload = bytes(pending_prefix) + full_nal
                    if pending_config:
                        flags |= 0x02
                    pending_prefix.clear()
                    pending_config = False
                else:
                    payload = full_nal

                send_plwc_packet(sock, 1, flags, pts_us, payload)
                sent_video_frames += 1
                pts_us += 33333

            try:
                while not stop_streaming.is_set():
                    chunk = os.read(fd, 8192)
                    if not chunk:
                        break
                    buf.extend(chunk)
                    while True:
                        m1 = START_CODE_RE.search(buf)
                        if not m1:
                            break
                        m2 = START_CODE_RE.search(buf, m1.end())
                        if not m2:
                            break
                        sc = bytes(buf[m1.start():m1.end()])
                        nal = bytes(buf[m1.end():m2.start()])
                        del buf[:m2.start()]
                        if nal:
                            emit_nal(sc, nal)
                # Flush remaining NAL on EOF
                if buf and not stop_streaming.is_set():
                    m1 = START_CODE_RE.search(buf)
                    if m1:
                        sc = bytes(buf[m1.start():m1.end()])
                        nal = bytes(buf[m1.end():])
                        if nal:
                            emit_nal(sc, nal)
            except Exception as exc:
                if not stop_streaming.is_set():
                    stream_errors.append(str(exc))

        pump_thread = threading.Thread(target=pump_annexb, daemon=True)
        pump_thread.start()

        # Wait until SHM reports LIVE with advancing write_seq
        shm_deadline = time.monotonic() + 7.0
        shm1: ShmHeaderSnapshot | None = None
        while time.monotonic() < shm_deadline:
            try:
                snap = read_shm_header()
                if snap.magic == SHM_MAGIC and snap.frame_state == 1 and snap.write_seq > 5:
                    shm1 = snap
                    break
            except AssertionError:
                pass
            time.sleep(0.15)
        assert shm1 is not None, f"[{codec}] Local\\PhoneLinkCam_Shm did not enter LIVE state in time"
        assert shm1.version == 1
        assert shm1.active_slot in (0, 1, 2)
        assert shm1.width == 1280 and shm1.height == 720, f"Unexpected SHM size: {shm1.width}x{shm1.height}"
        assert shm1.pixel_format == 2, f"Expected BGRA32 (2), got {shm1.pixel_format}"
        assert shm1.stride == 1280 * 4
        assert shm1.payload_size == 1280 * 720 * 4
        assert shm1.flip_flags == expected_flip_flags, (
            f"[{codec}] Unexpected flip_flags: got 0x{shm1.flip_flags:02X}, expected 0x{expected_flip_flags:02X}"
        )

        time.sleep(0.6)
        shm2 = read_shm_header()
        assert shm2.frame_state == 1
        assert shm2.write_seq > shm1.write_seq, f"[{codec}] write_seq did not advance: {shm1.write_seq} -> {shm2.write_seq}"
        print(
            f"  OK: [{codec}] SHM verified (magic=0x{shm2.magic:08X}, state={shm2.frame_state}, "
            f"{shm2.width}x{shm2.height}, write_seq {shm1.write_seq} -> {shm2.write_seq})"
        )

        # Capture 5 frames via DirectShow virtual camera
        cap_out = str(TESTS_DIR / capture_pattern)
        dshow_cmd = [
            str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "dshow", "-video_size", "1280x720",
            "-i", f"video={VCAM_NAME}",
            "-frames:v", "5", cap_out,
        ]
        cap_res = subprocess.run(dshow_cmd, capture_output=True, text=True, timeout=15, check=False)
        assert cap_res.returncode == 0, f"[{codec}] DirectShow capture failed: {cap_res.stderr}"

        # Send a heartbeat packet to confirm type=3 framing works cleanly
        send_plwc_packet(sock, 3, 0, int(time.monotonic() * 1_000_000), b"")

        # Keep streaming briefly, then wait for PhoneCamReceiver headless duration to finish
        stop_streaming.set()
        if gen_proc.poll() is None:
            gen_proc.terminate()
        pump_thread.join(timeout=2.0)
        assert not stream_errors, f"[{codec}] Stream pump errors: {stream_errors}"
        assert sent_video_frames >= 30, f"[{codec}] Too few frames streamed: {sent_video_frames}"

        stdout_text, stderr_text = receiver_proc.communicate(timeout=20)
        assert receiver_proc.returncode == 0, (
            f"[{codec}] PhoneCamReceiver exited with {receiver_proc.returncode}\nSTDOUT:\n{stdout_text}\nSTDERR:\n{stderr_text}"
        )
        assert expected_cuvid in stdout_text, f"[{codec}] Expected {expected_cuvid} in receiver output:\n{stdout_text}"
        m_frames = re.search(r"TotalFrames=(\d+)", stdout_text)
        assert m_frames and int(m_frames.group(1)) >= 30, f"[{codec}] Unexpected TotalFrames in output:\n{stdout_text}"
        print(f"  OK: [{codec}] Receiver NVDEC ({expected_cuvid}) decoded {m_frames.group(1)} frames, exited 0")

        # Verify all 5 captured PNGs
        standby_arr = np.array(Image.open(standby_png).convert("RGB"), dtype=np.int16)
        frames_rgb: list[np.ndarray] = []
        for idx, png in enumerate(captured_files, start=1):
            assert png.is_file(), f"[{codec}] Missing captured PNG: {png}"
            assert png.stat().st_size > 2_000, f"[{codec}] Captured PNG too small: {png} ({png.stat().st_size} bytes)"
            arr = np.array(Image.open(png).convert("RGB"), dtype=np.int16)
            assert arr.shape == (720, 1280, 3), f"[{codec}] Frame {idx} wrong shape: {arr.shape}"
            frames_rgb.append(arr)

        # Verify pixel colors and orientation (not standby, not inverted)
        f0 = frames_rgb[0]
        f4 = frames_rgb[4]
        standby_diff = float(np.mean(np.abs(f0 - standby_arr)))
        assert standby_diff > 40.0, f"[{codec}] Captured frame too close to standby baseline (diff={standby_diff:.2f})"

        tl_rgb = f0[80:120, 80:120].mean(axis=(0, 1))
        br_rgb = f0[600:640, 1120:1160].mean(axis=(0, 1))
        bg_rgb = f0[80:120, 600:680].mean(axis=(0, 1))

        def hex_to_rgb(h: str) -> np.ndarray:
            h = h.lstrip("#")
            return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], dtype=float)

        for label, actual, expected_hex in [
            ("top-left marker", tl_rgb, expected_tl_hex or tl_hex),
            ("bottom-right marker", br_rgb, expected_br_hex or br_hex),
            ("background", bg_rgb, bg_hex),
        ]:
            exp = hex_to_rgb(expected_hex)
            color_err = float(np.linalg.norm(actual - exp))
            assert color_err < 55.0, (
                f"[{codec}] {label} color mismatch: got RGB={actual.round(1).tolist()}, "
                f"expected ~{exp.tolist()} (err={color_err:.1f})"
            )

        # Verify motion across frame 1 -> frame 5 in the y=260..460 moving box band
        band_diff = np.abs(f4[280:440, :, :] - f0[280:440, :, :]).max(axis=2)
        changed_pixels = int(np.sum(band_diff > 40))
        assert changed_pixels > 4000, (
            f"[{codec}] Insufficient motion between frame 1 and frame 5 in moving-box band "
            f"(changed_pixels={changed_pixels}; static fallback suspected)"
        )
        print(
            f"  OK: [{codec}] Captured 5 PNGs (1280x720), standby_diff={standby_diff:.1f}, "
            f"moving_pixels(f1->f5)={changed_pixels}, colors verified (TL={tl_rgb.round(0).tolist()}, "
            f"BR={br_rgb.round(0).tolist()}, BG={bg_rgb.round(0).tolist()})"
        )
        return captured_files, stdout_text
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if gen_proc is not None and gen_proc.poll() is None:
            gen_proc.terminate()
            try:
                gen_proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                gen_proc.kill()
        if receiver_proc.poll() is None:
            receiver_proc.terminate()
            try:
                receiver_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                receiver_proc.kill()


def verify_60fps_1080p_rotated(codec: str, port: int, token: str) -> None:
    """Stream 1080p60 rotated 270deg video to PhoneCamReceiver and verify NVDEC decodes >=50 fps with 0 FFmpeg drops."""
    assert codec in ("h264", "hevc")
    enc_args = (
        ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-threads", "1", "-profile:v", "baseline", "-g", "15", "-bf", "0"]
        if codec == "h264"
        else ["-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency",
              "-x265-params", "keyint=15:min-keyint=15:bframes=0:repeat-headers=1:pools=none:frame-threads=1",
              "-threads", "1"]
    )
    pre_cmd = [
        str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=60:d=0.5",
        *enc_args, "-pix_fmt", "yuv420p", "-f", codec, "pipe:1",
    ]
    pre_res = subprocess.run(pre_cmd, capture_output=True, timeout=30, check=False)
    assert pre_res.returncode == 0 and len(pre_res.stdout) > 1000, (
        f"[{codec}-60fps] Pre-encode failed ({pre_res.returncode}): {pre_res.stderr.decode('utf-8', 'replace')}"
    )
    initial_config, units = _split_annexb_access_units(codec, pre_res.stdout)
    assert len(units) >= 28, f"[{codec}-60fps] Expected >=28 pre-encoded frames, got {len(units)}"

    receiver_cmd = [
        str(RECEIVER_EXE),
        "--headless-test",
        "--port", str(port),
        "--token", token,
        "--decoder", "nvdec",
        "--width", "1920",
        "--height", "1080",
        "--duration", "6",
    ]
    receiver_proc = subprocess.Popen(
        receiver_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    winmm = ctypes.WinDLL("winmm")
    timer_started = False
    sock: socket.socket | None = None
    try:
        wait_for_tcp_port("127.0.0.1", port, timeout_s=8.0)
        sock = connect_plwc("127.0.0.1", port, token, codec, f"e2e-60fps-{codec}", 1920, 1080, 60)
        send_plwc_packet(sock, 2, 0, 0, json.dumps({"rotation": 270, "camera": "back"}).encode("utf-8"))
        if initial_config:
            send_plwc_packet(sock, 1, 0x02, 0, initial_config)

        winmm.timeBeginPeriod(1)
        timer_started = True
        start_t = time.perf_counter()
        total_send = 288  # ~4.8s at 60 fps
        for frame_idx in range(total_send):
            flags, payload = units[frame_idx % len(units)]
            send_plwc_packet(sock, 1, flags, frame_idx * 16666, payload)
            target = start_t + (frame_idx + 1) / 60.0
            rem = target - time.perf_counter()
            if rem > 0.002:
                time.sleep(rem - 0.002)
            while time.perf_counter() < target:
                pass

        shm = read_shm_header()
        assert (
            shm.frame_state == 1
            and shm.width == 1080
            and shm.height == 1920
            and shm.stride == 1080 * 4
            and shm.payload_size == 1080 * 1920 * 4
        ), (
            f"[{codec}-60fps] Unexpected SHM state (expected 1080x1920 portrait): {shm}"
        )

        stdout_text, stderr_text = receiver_proc.communicate(timeout=15)
        assert receiver_proc.returncode == 0, (
            f"[{codec}-60fps] PhoneCamReceiver exited {receiver_proc.returncode}\nSTDOUT:\n{stdout_text}\nSTDERR:\n{stderr_text}"
        )
        drops = [int(m) for m in re.findall(r"\[FFmpeg\][^\r\n]*\bdrop=(\d+)", stdout_text)]
        assert all(d == 0 for d in drops), f"[{codec}-60fps] FFmpeg dropped frames (drops={drops}):\n{stdout_text}"
        fps_samples = [float(m) for m in re.findall(r"\[Headless\][^\r\n]*\bfps=([0-9.]+)", stdout_text)]
        peak_fps = max(fps_samples) if fps_samples else 0.0
        assert peak_fps >= 50.0, f"[{codec}-60fps] Peak FPS {peak_fps:.1f} < 50.0:\n{stdout_text}"
        m_frames = re.search(r"TotalFrames=(\d+)", stdout_text)
        total_frames = int(m_frames.group(1)) if m_frames else 0
        assert total_frames >= 200, f"[{codec}-60fps] TotalFrames={total_frames} < 200:\n{stdout_text}"
        print(
            f"  OK: [{codec}-60fps] 1080x1920 portrait (rot=270deg) peak_fps={peak_fps:.1f}, "
            f"TotalFrames={total_frames}, FFmpeg drops={drops or [0]}"
        )
    finally:
        if timer_started:
            winmm.timeEndPeriod(1)
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if receiver_proc.poll() is None:
            receiver_proc.terminate()
            try:
                receiver_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                receiver_proc.kill()


def verify_high_bitrate_1080p60(port: int, token: str) -> None:
    """Stream >100 Mbps 1080p60 H.264 video to PhoneCamReceiver and verify NVDEC sustains >=55 fps with 0 FFmpeg drops."""
    pre_cmd = [
        str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=60:d=0.5,noise=alls=40:allf=t",
        "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
        "-b:v", "140M", "-maxrate", "180M", "-bufsize", "60M",
        "-threads", "1", "-g", "30", "-bf", "0", "-pix_fmt", "yuv420p", "-f", "h264", "pipe:1",
    ]
    pre_res = subprocess.run(pre_cmd, capture_output=True, timeout=45, check=False)
    assert pre_res.returncode == 0 and len(pre_res.stdout) > 100_000, (
        f"[h264-140M] Pre-encode failed ({pre_res.returncode}): {pre_res.stderr.decode('utf-8', 'replace')}"
    )
    initial_config, units = _split_annexb_access_units("h264", pre_res.stdout)
    assert len(units) >= 28, f"[h264-140M] Expected >=28 pre-encoded frames, got {len(units)}"
    clip_mbps = sum(len(u[1]) for u in units) * 8.0 / (len(units) / 60.0) / 1e6
    assert clip_mbps >= 100.0, f"[h264-140M] Expected >=100 Mbps bitstream, got {clip_mbps:.1f} Mbps"

    receiver_cmd = [
        str(RECEIVER_EXE),
        "--headless-test",
        "--port", str(port),
        "--token", token,
        "--decoder", "nvdec",
        "--width", "1920",
        "--height", "1080",
        "--duration", "5",
    ]
    receiver_proc = subprocess.Popen(
        receiver_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    winmm = ctypes.WinDLL("winmm")
    timer_started = False
    sock: socket.socket | None = None
    try:
        wait_for_tcp_port("127.0.0.1", port, timeout_s=8.0)
        sock = connect_plwc("127.0.0.1", port, token, "h264", "e2e-140M-h264", 1920, 1080, 60)
        send_plwc_packet(sock, 2, 0, 0, json.dumps({"rotation": 0, "camera": "back"}).encode("utf-8"))
        if initial_config:
            send_plwc_packet(sock, 1, 0x02, 0, initial_config)

        winmm.timeBeginPeriod(1)
        timer_started = True
        start_t = time.perf_counter()
        total_send = 240  # ~4.0s at 60 fps
        for frame_idx in range(total_send):
            flags, payload = units[frame_idx % len(units)]
            send_plwc_packet(sock, 1, flags, frame_idx * 16666, payload)
            target = start_t + (frame_idx + 1) / 60.0
            rem = target - time.perf_counter()
            if rem > 0.002:
                time.sleep(rem - 0.002)
            while time.perf_counter() < target:
                pass

        shm = read_shm_header()
        assert shm.frame_state == 1 and shm.width == 1920 and shm.height == 1080, (
            f"[h264-140M] Unexpected SHM state: {shm}"
        )

        stdout_text, stderr_text = receiver_proc.communicate(timeout=15)
        assert receiver_proc.returncode == 0, (
            f"[h264-140M] PhoneCamReceiver exited {receiver_proc.returncode}\nSTDOUT:\n{stdout_text}\nSTDERR:\n{stderr_text}"
        )
        drops = [int(m) for m in re.findall(r"\[FFmpeg\][^\r\n]*\bdrop=(\d+)", stdout_text)]
        assert all(d == 0 for d in drops), f"[h264-140M] FFmpeg dropped frames (drops={drops}):\n{stdout_text}"
        fps_samples = [float(m) for m in re.findall(r"\[Headless\][^\r\n]*\bfps=([0-9.]+)", stdout_text)]
        peak_fps = max(fps_samples) if fps_samples else 0.0
        assert peak_fps >= 55.0, f"[h264-140M] Peak FPS {peak_fps:.1f} < 55.0:\n{stdout_text}"
        m_frames = re.search(r"TotalFrames=(\d+)", stdout_text)
        total_frames = int(m_frames.group(1)) if m_frames else 0
        assert total_frames >= 180, f"[h264-140M] TotalFrames={total_frames} < 180:\n{stdout_text}"
        print(
            f"  OK: [h264-140M] {clip_mbps:.1f} Mbps 1080p60 peak_fps={peak_fps:.1f}, "
            f"TotalFrames={total_frames}, FFmpeg drops={drops or [0]}"
        )
    finally:
        if timer_started:
            winmm.timeEndPeriod(1)
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if receiver_proc.poll() is None:
            receiver_proc.terminate()
            try:
                receiver_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                receiver_proc.kill()


def verify_portrait_dshow_pipeline(
    codec: str,
    rotation_deg: int,
    port: int,
    token: str,
    capture_pattern: str,
    standby_png: Path,
    target_width: int = 0,
    target_height: int = 0,
    expected_out_w: int = 720,
    expected_out_h: int = 1280,
    decoder: str = "nvdec",
    extra_receiver_args: list[str] | None = None,
    dshow_pixel_format: str | None = None,
    expected_tr_hex: str | None = None,
    expected_bl_hex: str | None = None,
    src_w: int = 1280,
    src_h: int = 720,
) -> None:
    """Verify portrait phone streaming outputs pure portrait frames (no black pillarboxes/letterboxes) in SHM and DirectShow."""
    assert codec in ("h264", "hevc")
    assert rotation_deg in (90, 270)

    bg_hex = "#108820"
    tl_hex = "#FFFF00"
    br_hex = "#00FFFF"
    box_hex = "#FF2020"
    duration_s = 12

    receiver_cmd = [
        str(RECEIVER_EXE),
        "--headless-test",
        "--port", str(port),
        "--token", token,
        "--decoder", decoder,
        "--duration", str(duration_s),
        *(extra_receiver_args or []),
    ]
    if target_width > 0 and target_height > 0:
        receiver_cmd.extend(["--width", str(target_width), "--height", str(target_height)])

    receiver_proc = subprocess.Popen(
        receiver_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    if codec == "h264":
        enc_args = [
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-threads", "1", "-profile:v", "baseline", "-g", "15", "-bf", "0",
        ]
    else:
        enc_args = [
            "-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency",
            "-x265-params", "keyint=15:min-keyint=15:bframes=0:repeat-headers=1:pools=none:frame-threads=1",
            "-threads", "1",
        ]

    msx = src_w / 1280.0
    msy = src_h / 720.0
    ffmpeg_gen_cmd = [
        str(SYSTEM_FFMPEG),
        "-hide_banner", "-loglevel", "error",
        "-re",
        "-f", "lavfi", "-i", f"color=c={bg_hex}:s={src_w}x{src_h}:r=30:d={duration_s}",
        "-f", "lavfi", "-i", f"color=c={box_hex}:s={int(200*msx)}x{int(200*msy)}:r=30:d={duration_s}",
        "-filter_complex",
        f"[0:v]drawbox=x={int(40*msx)}:y={int(40*msy)}:w={int(180*msx)}:h={int(120*msy)}:color={tl_hex}:t=fill,"
        f"drawbox=x={int(1060*msx)}:y={int(560*msy)}:w={int(180*msx)}:h={int(120*msy)}:color={br_hex}:t=fill[bg];"
        f"[bg][1:v]overlay=x='mod(n*{int(28*msx)},{int(1080*msx)})':y={int(260*msy)}:shortest=1",
        *enc_args,
        "-pix_fmt", "yuv420p",
        "-flush_packets", "1",
        "-f", codec,
        "pipe:1",
    ]

    captured_files = [TESTS_DIR / (capture_pattern % i) for i in range(1, 6)]
    for p in captured_files:
        if p.exists():
            p.unlink()

    gen_proc = None
    sock = None
    try:
        wait_for_tcp_port("127.0.0.1", port, timeout_s=8.0)
        sock = connect_plwc("127.0.0.1", port, token, codec, f"e2e-portrait-{codec}", src_w, src_h, 30)
        send_plwc_packet(sock, 2, 0, 0, json.dumps({"rotation": rotation_deg, "camera": "back"}).encode("utf-8"))

        gen_proc = subprocess.Popen(
            ffmpeg_gen_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )

        import threading

        stop_streaming = threading.Event()
        stream_errors: list[str] = []
        rot_code = 1 if rotation_deg == 90 else 3
        rot_flag_bits = (rot_code & 0x03) << 2

        def pump_annexb() -> None:
            assert gen_proc is not None and gen_proc.stdout is not None and sock is not None
            fd = gen_proc.stdout.fileno()
            buf = bytearray()
            pending_prefix = bytearray()
            pending_config = False
            sent_initial_config = False
            pts_us = 0

            def emit_nal(start_code: bytes, nal_bytes: bytes) -> None:
                nonlocal pending_config, sent_initial_config, pts_us
                if stop_streaming.is_set():
                    return
                is_cfg, is_vcl, is_idr = _nal_info(codec, nal_bytes)
                full_nal = start_code + nal_bytes
                if is_cfg:
                    pending_config = True
                    pending_prefix.extend(full_nal)
                    return
                if not is_vcl:
                    pending_prefix.extend(full_nal)
                    return
                if pending_config and not sent_initial_config:
                    send_plwc_packet(sock, 1, 0x02 | rot_flag_bits, pts_us, bytes(pending_prefix))
                    sent_initial_config = True
                flags = (0x01 if is_idr else 0x00) | rot_flag_bits
                if pending_prefix:
                    payload = bytes(pending_prefix) + full_nal
                    if pending_config:
                        flags |= 0x02
                    pending_prefix.clear()
                    pending_config = False
                else:
                    payload = full_nal
                send_plwc_packet(sock, 1, flags, pts_us, payload)
                pts_us += 33333

            try:
                while not stop_streaming.is_set():
                    chunk = os.read(fd, 8192)
                    if not chunk:
                        break
                    buf.extend(chunk)
                    while True:
                        m1 = START_CODE_RE.search(buf)
                        if not m1:
                            break
                        m2 = START_CODE_RE.search(buf, m1.end())
                        if not m2:
                            break
                        sc = bytes(buf[m1.start():m1.end()])
                        nal = bytes(buf[m1.end():m2.start()])
                        del buf[:m2.start()]
                        if nal:
                            emit_nal(sc, nal)
            except Exception as exc:
                if not stop_streaming.is_set():
                    stream_errors.append(str(exc))

        pump_thread = threading.Thread(target=pump_annexb, daemon=True)
        pump_thread.start()

        shm_deadline = time.monotonic() + 7.0
        shm1: ShmHeaderSnapshot | None = None
        while time.monotonic() < shm_deadline:
            try:
                snap = read_shm_header()
                if snap.magic == SHM_MAGIC and snap.frame_state == 1 and snap.write_seq > 5:
                    shm1 = snap
                    break
            except AssertionError:
                pass
            time.sleep(0.15)
        assert shm1 is not None, f"[{codec}-portrait] SHM did not enter LIVE state in time"
        assert shm1.width == expected_out_w and shm1.height == expected_out_h, (
            f"[{codec}-portrait] Expected SHM {expected_out_w}x{expected_out_h}, got {shm1.width}x{shm1.height}"
        )
        assert shm1.stride == expected_out_w * 4
        assert shm1.payload_size == expected_out_w * expected_out_h * 4
        assert shm1.orientation_hint == 2, f"[{codec}-portrait] Expected orientation_hint=2, got {shm1.orientation_hint}"

        # Verify DirectShow only advertises portrait capabilities while live portrait stream is active (prevents Discord/WebRTC picking 1280x720)
        opts_res = subprocess.run(
            [str(SYSTEM_FFMPEG), "-hide_banner", "-f", "dshow", "-list_options", "true", "-i", f"video={VCAM_NAME}"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        opts_text = opts_res.stdout + "\n" + opts_res.stderr
        assert f"s={expected_out_w}x{expected_out_h}" in opts_text, (
            f"[{codec}-portrait] Expected {expected_out_w}x{expected_out_h} in live dshow options:\n{opts_text}"
        )
        assert "s=1280x720" not in opts_text and "s=1920x1080" not in opts_text, (
            f"[{codec}-portrait] Landscape capability leaked into live portrait dshow options:\n{opts_text}"
        )

        # Capture 5 frames WITHOUT -video_size (mirrors OBS Device Default / GetFormat negotiation)
        cap_out = str(TESTS_DIR / capture_pattern)
        dshow_cmd = [
            str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "dshow",
            *(["-pixel_format", dshow_pixel_format] if dshow_pixel_format else []),
            "-i", f"video={VCAM_NAME}",
            "-frames:v", "5", cap_out,
        ]
        cap_res = subprocess.run(dshow_cmd, capture_output=True, text=True, timeout=15, check=False)
        assert cap_res.returncode == 0, f"[{codec}-portrait] DirectShow capture failed: {cap_res.stderr}"

        stop_streaming.set()
        if gen_proc.poll() is None:
            gen_proc.terminate()
        pump_thread.join(timeout=2.0)
        assert not stream_errors, f"[{codec}-portrait] Stream pump errors: {stream_errors}"

        stdout_text, stderr_text = receiver_proc.communicate(timeout=20)
        assert receiver_proc.returncode == 0, (
            f"[{codec}-portrait] PhoneCamReceiver exited {receiver_proc.returncode}\nSTDOUT:\n{stdout_text}\nSTDERR:\n{stderr_text}"
        )

        frames_rgb: list[np.ndarray] = []
        for idx, png in enumerate(captured_files, start=1):
            assert png.is_file(), f"[{codec}-portrait] Missing captured PNG: {png}"
            arr = np.array(Image.open(png).convert("RGB"), dtype=np.int16)
            assert arr.shape == (expected_out_h, expected_out_w, 3), (
                f"[{codec}-portrait] Frame {idx} expected portrait shape ({expected_out_h}, {expected_out_w}, 3), got {arr.shape}"
            )
            frames_rgb.append(arr)

        f0 = frames_rgb[0]
        f4 = frames_rgb[4]
        if standby_png.is_file():
            standby_arr = np.array(Image.open(standby_png).convert("RGB"), dtype=np.int16)
            if standby_arr.shape == f0.shape:
                standby_diff = float(np.mean(np.abs(f0 - standby_arr)))
                assert standby_diff > 40.0, f"[{codec}-portrait] Captured frame too close to standby baseline (diff={standby_diff:.2f})"
        bg_exp = np.array([16, 136, 32], dtype=float)
        tl_exp = np.array([255, 255, 0], dtype=float)
        br_exp = np.array([0, 255, 255], dtype=float)

        # Check all 4 border strips are non-black background (zero letterbox/pillarbox margins anywhere)
        sx = expected_out_w / 720.0
        sy = expected_out_h / 1280.0
        edge_w = max(4, int(12 * sx))
        edge_h = max(4, int(12 * sy))
        corner_span = max(20, int(160 * sx))
        for border_name, patch in [
            ("left edge", f0[:, :edge_w, :]),
            ("right edge", f0[:, -edge_w:, :]),
            ("top-left margin", f0[:edge_h, :corner_span, :]),
            ("bottom-right margin", f0[-edge_h:, -corner_span:, :]),
        ]:
            mean_rgb = patch.mean(axis=(0, 1))
            err = float(np.linalg.norm(mean_rgb - bg_exp))
            assert err < 55.0, (
                f"[{codec}-portrait] {border_name} is not background (letterbox/black bar suspected): "
                f"got RGB={mean_rgb.round(1).tolist()}, expected ~{bg_exp.tolist()}"
            )

        # Check rotated marker corner colors
        def _hex_rgb(h: str) -> np.ndarray:
            h = h.lstrip("#")
            return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], dtype=float)

        tr_patch = f0[int(80 * sy):int(180 * sy), int(590 * sx):int(650 * sx)].mean(axis=(0, 1))
        bl_patch = f0[int(1100 * sy):int(1200 * sy), int(70 * sx):int(130 * sx)].mean(axis=(0, 1))
        if rotation_deg == 90:
            exp_tr, exp_bl = tl_exp, br_exp
        else:
            exp_tr, exp_bl = br_exp, tl_exp
        if expected_tr_hex:
            exp_tr = _hex_rgb(expected_tr_hex)
        if expected_bl_hex:
            exp_bl = _hex_rgb(expected_bl_hex)
        assert float(np.linalg.norm(tr_patch - exp_tr)) < 60.0, (
            f"[{codec}-portrait] TR marker mismatch: got {tr_patch.round(1).tolist()}, expected {exp_tr.tolist()}"
        )
        assert float(np.linalg.norm(bl_patch - exp_bl)) < 60.0, (
            f"[{codec}-portrait] BL marker mismatch: got {bl_patch.round(1).tolist()}, expected {exp_bl.tolist()}"
        )

        # Verify motion along vertical moving-box strip
        band_diff = np.abs(f4[:, int(280 * sx):int(440 * sx), :] - f0[:, int(280 * sx):int(440 * sx), :]).max(axis=2)
        changed_pixels = int(np.sum(band_diff > 40))
        assert changed_pixels > int(2500 * sx * sy), (
            f"[{codec}-portrait] Insufficient motion in portrait strip: changed_pixels={changed_pixels}"
        )
        print(
            f"  OK: [{codec}-portrait/{decoder}] rot={rotation_deg}deg -> {expected_out_w}x{expected_out_h} pure portrait "
            f"(no letterbox borders, motion={changed_pixels}px, TR={tr_patch.round(0).tolist()}, BL={bl_patch.round(0).tolist()})"
        )
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if gen_proc is not None and gen_proc.poll() is None:
            gen_proc.terminate()
            try:
                gen_proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                gen_proc.kill()
        if receiver_proc.poll() is None:
            receiver_proc.terminate()
            try:
                receiver_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                receiver_proc.kill()


def check_android_completeness() -> None:
    print("[11/11] Auditing Android companion project & wire compatibility...")
    required_files = [
        ANDROID_DIR / "settings.gradle.kts",
        ANDROID_DIR / "build.gradle.kts",
        ANDROID_DIR / "gradle.properties",
        ANDROID_DIR / "gradlew.bat",
        ANDROID_DIR / "build_apk.bat",
        ANDROID_DIR / "gradle" / "wrapper" / "gradle-wrapper.properties",
        ANDROID_DIR / "app" / "build.gradle.kts",
        ANDROID_DIR / "app" / "src" / "main" / "AndroidManifest.xml",
    ]
    for f in required_files:
        assert f.is_file(), f"Missing Android build file: {f}"

    manifest_path = ANDROID_DIR / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_root = ET.parse(manifest_path).getroot()
    android_ns = "{http://schemas.android.com/apk/res/android}"
    perms = {el.get(f"{android_ns}name") for el in manifest_root.findall("uses-permission")}
    expected_perms = {
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
    }
    missing_perms = expected_perms - perms
    assert not missing_perms, f"Missing manifest permissions: {missing_perms}"

    services = manifest_root.findall("./application/service")
    assert any(
        s.get(f"{android_ns}foregroundServiceType") == "camera"
        for s in services
    ), "Missing camera foregroundServiceType on CameraStreamService"

    # Parse all Android XML resources
    xml_files = list((ANDROID_DIR / "app" / "src" / "main" / "res").rglob("*.xml"))
    assert len(xml_files) >= 10, f"Unexpectedly few resource XML files: {len(xml_files)}"
    for xf in xml_files:
        ET.parse(xf)

    gradle_app = (ANDROID_DIR / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    for token in (
        'namespace = "com.win10phonelink.webcam"',
        'applicationId = "com.win10phonelink.webcam"',
        "minSdk = 28",
        "targetSdk = 34",
        "compileSdk = 34",
        "androidx.camera:camera-core",
        "com.google.zxing:core",
    ):
        assert token in gradle_app, f"Missing '{token}' in app/build.gradle.kts"

    kt_root = ANDROID_DIR / "app" / "src" / "main" / "java" / "com" / "win10phonelink" / "webcam"
    encoder_kt = (kt_root / "codec" / "VideoEncoderCatalog.kt").read_text(encoding="utf-8")
    for token in (
        "MediaCodecList(MediaCodecList.ALL_CODECS)",
        "isHardwareAccelerated",
        "COLOR_FormatSurface",
        "MIMETYPE_VIDEO_AVC",
        "MIMETYPE_VIDEO_HEVC",
        '"h264"',
        '"hevc"',
    ):
        assert token in encoder_kt, f"Missing '{token}' in VideoEncoderCatalog.kt"

    qr_kt = (kt_root / "QrScannerActivity.kt").read_text(encoding="utf-8")
    pairing_kt = (kt_root / "net" / "PairingPayload.kt").read_text(encoding="utf-8")
    assert "MultiFormatReader" in qr_kt and "BarcodeFormat.QR_CODE" in qr_kt
    for key in ('"v"', '"ip"', '"port"', '"token"', '"name"'):
        assert key in pairing_kt, f"Missing QR field {key} in PairingPayload.kt"

    stream_kt = (kt_root / "net" / "StreamClient.kt").read_text(encoding="utf-8")
    protocol_cs = PC_PROTOCOL_CS.read_text(encoding="utf-8")
    for wire_token in (
        "0x50, 0x4C, 0x57, 0x43",
        "0x4F, 0x4B, 0x0D, 0x0A",
        "PACKET_TYPE_VIDEO: Byte = 1",
        "PACKET_TYPE_CONTROL: Byte = 2",
        "PACKET_TYPE_HEARTBEAT: Byte = 3",
        "FLAG_KEYFRAME: Int = 0x01",
        "FLAG_CODEC_CONFIG: Int = 0x02",
        'put("token"',
        'put("device"',
        'put("codec"',
        'put("encoder"',
        'put("width"',
        'put("height"',
        'put("fps"',
    ):
        assert wire_token in stream_kt, f"Missing wire protocol token '{wire_token}' in StreamClient.kt"

    for cs_token in ('"PLWC"', '"OK\\r\\n"', "Video = 1", "Control = 2", "Heartbeat = 3", "PacketHeaderSize = 16"):
        assert cs_token in protocol_cs, f"Missing PC protocol token '{cs_token}' in Protocol.cs"

    print(f"  OK: Android project verified ({len(xml_files)} resource XMLs valid, permissions & wire protocol matched)")


def main() -> int:
    start_ts = time.monotonic()
    orig_hint = _get_orientation_hint_reg()
    orig_flip = _get_flip_flags_reg()
    try:
        check_portable_folder()
        check_dshow_registration()
        standby_png, standby_portrait_png = capture_standby_baseline()
        check_qr_and_token_rejection()

        print("[5/11] Running E2E live H.264 pipeline (ffmpeg -> PLWC -> NVDEC h264_cuvid -> SHM -> DShow)...")
        stream_live_ffmpeg_to_plwc(
            codec="h264",
            bg_hex="#108820",
            tl_hex="#FFFF00",
            br_hex="#00FFFF",
            box_hex="#FF2020",
            port=TEST_PORT,
            token=TEST_TOKEN,
            duration_s=12,
            capture_pattern="captured_frame_%d.png",
            standby_png=standby_png,
        )

        print("[6/11] Running E2E live HEVC pipeline (ffmpeg -> PLWC -> NVDEC hevc_cuvid -> SHM -> DShow)...")
        stream_live_ffmpeg_to_plwc(
            codec="hevc",
            bg_hex="#2030C0",
            tl_hex="#FF00FF",
            br_hex="#FFA500",
            box_hex="#00FF00",
            port=TEST_PORT,
            token=TEST_TOKEN,
            duration_s=12,
            capture_pattern="captured_hevc_frame_%d.png",
            standby_png=standby_png,
        )

        print("[7/11] Running 60fps 1080p rotated (270deg) stream verification (h264 & hevc NVDEC)...")
        verify_60fps_1080p_rotated("h264", TEST_PORT, TEST_TOKEN)
        verify_60fps_1080p_rotated("hevc", TEST_PORT, TEST_TOKEN)

        print("[8/11] Running >100 Mbps 1080p60 high-bitrate verification (h264 NVDEC)...")
        verify_high_bitrate_1080p60(TEST_PORT, TEST_TOKEN)

        print("[9/11] Running E2E portrait (no-letterbox) DirectShow verification (H.264/HEVC, 1080x1920, 640x480->360x640, D3D11VA/CPU, flip)...")
        verify_portrait_dshow_pipeline(
            codec="h264",
            rotation_deg=90,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_h264_%d.png",
            standby_png=standby_portrait_png,
            target_width=1280,
            target_height=720,
            expected_out_w=720,
            expected_out_h=1280,
        )
        verify_portrait_dshow_pipeline(
            codec="hevc",
            rotation_deg=270,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_hevc_%d.png",
            standby_png=standby_portrait_png,
            target_width=0,
            target_height=0,
            expected_out_w=720,
            expected_out_h=1280,
        )
        verify_portrait_dshow_pipeline(
            codec="h264",
            rotation_deg=90,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_1080p_%d.png",
            standby_png=standby_portrait_png,
            target_width=1920,
            target_height=1080,
            expected_out_w=1080,
            expected_out_h=1920,
            src_w=1920,
            src_h=1080,
        )
        verify_portrait_dshow_pipeline(
            codec="h264",
            rotation_deg=90,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_360x640_%d.png",
            standby_png=standby_portrait_png,
            target_width=640,
            target_height=480,
            expected_out_w=360,
            expected_out_h=640,
        )
        verify_portrait_dshow_pipeline(
            codec="h264",
            rotation_deg=90,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_d3d11_fliphv_%d.png",
            standby_png=standby_portrait_png,
            target_width=1280,
            target_height=720,
            expected_out_w=720,
            expected_out_h=1280,
            decoder="d3d11va",
            extra_receiver_args=["--flip-h", "--flip-v"],
            expected_tr_hex="#00FFFF",
            expected_bl_hex="#FFFF00",
        )
        verify_portrait_dshow_pipeline(
            codec="h264",
            rotation_deg=270,
            port=TEST_PORT,
            token=TEST_TOKEN,
            capture_pattern="captured_portrait_cpu_bgr0_%d.png",
            standby_png=standby_portrait_png,
            target_width=1280,
            target_height=720,
            expected_out_w=720,
            expected_out_h=1280,
            decoder="cpu",
            dshow_pixel_format="bgr0",
        )

        print("[10/11] Running E2E horizontal + vertical flip verification (--flip-h --flip-v)...")
        stream_live_ffmpeg_to_plwc(
            codec="h264",
            bg_hex="#108820",
            tl_hex="#FFFF00",
            br_hex="#00FFFF",
            box_hex="#FF2020",
            port=TEST_PORT,
            token=TEST_TOKEN,
            duration_s=10,
            capture_pattern="captured_flip_frame_%d.png",
            standby_png=standby_png,
            extra_receiver_args=["--flip-h", "--flip-v"],
            expected_tl_hex="#00FFFF",
            expected_br_hex="#FFFF00",
            expected_flip_flags=0x83,
        )

        check_android_completeness()
        elapsed = time.monotonic() - start_ts
        print(f"\nALL E2E VERIFICATION CHECKS PASSED in {elapsed:.1f}s.")
        return 0
    finally:
        _set_orientation_hint_reg(orig_hint if orig_hint is not None else 2)
        _set_flip_flags_reg(orig_flip)


if __name__ == "__main__":
    sys.exit(main())
