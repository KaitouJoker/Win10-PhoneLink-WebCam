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
    print("[1/8] Verifying portable folder contents...")
    required = [RECEIVER_EXE, VCAM_DLL, VCAM_DLL32, REG_BAT, UNREG_BAT, INSTALL_ALL_BAT, PORTABLE_APK, PORTABLE_FFMPEG]
    for p in required:
        assert p.is_file(), f"Missing portable file: {p}"
        size = p.stat().st_size
        assert size > 0, f"Empty portable file: {p}"
        print(f"  OK: {p.name} ({size:,} bytes)")
    assert _read_pe_machine(VCAM_DLL) == 0x8664, f"{VCAM_DLL.name} is not x64 PE32+"
    assert _read_pe_machine(VCAM_DLL32) == 0x014C, f"{VCAM_DLL32.name} is not x86 PE32"
    print("  OK: PE headers verified (vcam-dshow.dll=0x8664 x64, vcam-dshow32.dll=0x014C x86)")


def check_dshow_registration() -> None:
    print("[2/8] Registering vcam-dshow.dll and verifying DirectShow enumeration...")
    reg_res = subprocess.run(["cmd.exe", "/c", str(REG_BAT), "--no-elevate"], capture_output=True, text=True, check=False)
    assert reg_res.returncode == 0, f"register_camera.bat failed ({reg_res.returncode}): {reg_res.stderr}"

    reg_path = None
    for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        try:
            with winreg.OpenKey(hive, rf"Software\Classes\CLSID\{VCAM_CLSID}\InprocServer32") as k:
                val, _ = winreg.QueryValueEx(k, None)
                if val:
                    reg_path = val
                    break
        except OSError:
            continue
    assert reg_path is not None, f"CLSID {VCAM_CLSID} not found in HKCU/HKLM registry"
    assert Path(reg_path).resolve() == VCAM_DLL.resolve(), f"Registry points to {reg_path}, expected {VCAM_DLL}"
    print(f"  OK: Registry InprocServer32 -> {reg_path}")

    cat_subkey = rf"Software\Classes\CLSID\{{860BB310-5D01-11d0-BD3B-00A0C911CE86}}\Instance\{VCAM_CLSID}"
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, cat_subkey) as ck:
        friendly_val, _ = winreg.QueryValueEx(ck, "FriendlyName")
        desc_val, _ = winreg.QueryValueEx(ck, "Description")
    assert friendly_val == VCAM_NAME, f"HKCU FriendlyName mismatch: {friendly_val!r}"
    assert desc_val == VCAM_NAME, f"HKCU Description mismatch: {desc_val!r}"
    print(f'  OK: HKCU category instance FriendlyName & Description == "{VCAM_NAME}"')

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

    opts_res = subprocess.run(
        [str(SYSTEM_FFMPEG), "-hide_banner", "-f", "dshow", "-list_options", "true", "-i", f"video={VCAM_NAME}"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    opts_text = opts_res.stdout + "\n" + opts_res.stderr
    assert "max s=1920x1080 fps=60" in opts_text, f"Expected 1920x1080 60fps capability:\n{opts_text}"
    assert "max s=1280x720 fps=60" in opts_text, f"Expected 1280x720 60fps capability:\n{opts_text}"
    print("  OK: DirectShow capabilities verified (1920x1080 & 1280x720 advertise 60 fps)")


def capture_standby_baseline() -> Path:
    print("[3/7] Capturing standby baseline frame from DirectShow...")
    out_png = TESTS_DIR / "standby_baseline.png"
    if out_png.exists():
        out_png.unlink()
    cmd = [
        str(SYSTEM_FFMPEG), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "dshow", "-video_size", "1280x720",
        "-i", f"video={VCAM_NAME}",
        "-frames:v", "1", str(out_png),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=15, check=False)
    assert res.returncode == 0 and out_png.is_file(), f"Standby capture failed: {res.stderr}"
    arr = np.array(Image.open(out_png).convert("RGB"))
    assert arr.shape == (720, 1280, 3), f"Unexpected standby dimensions: {arr.shape}"
    print(f"  OK: Standby baseline captured ({out_png.stat().st_size:,} bytes, shape={arr.shape})")
    return out_png


def check_qr_and_token_rejection() -> None:
    print("[4/7] Verifying QR payload generation, OpenCV QR decode, and token rejection...")
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
            ("top-left marker", tl_rgb, tl_hex),
            ("bottom-right marker", br_rgb, br_hex),
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
        "-f", "lavfi", "-i", "color=c=#1840A0:s=1920x1080:r=60:d=0.5",
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
        assert shm.frame_state == 1 and shm.width == 1920 and shm.height == 1080, (
            f"[{codec}-60fps] Unexpected SHM state: {shm}"
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
            f"  OK: [{codec}-60fps] 1920x1080 rot=270deg peak_fps={peak_fps:.1f}, "
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


def check_android_completeness() -> None:
    print("[8/8] Auditing Android companion project & wire compatibility...")
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
    check_portable_folder()
    check_dshow_registration()
    standby_png = capture_standby_baseline()
    check_qr_and_token_rejection()

    print("[5/7] Running E2E live H.264 pipeline (ffmpeg -> PLWC -> NVDEC h264_cuvid -> SHM -> DShow)...")
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

    print("[6/7] Running E2E live HEVC pipeline (ffmpeg -> PLWC -> NVDEC hevc_cuvid -> SHM -> DShow)...")
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

    print("[7/8] Running 60fps 1080p rotated (270deg) stream verification (h264 & hevc NVDEC)...")
    verify_60fps_1080p_rotated("h264", TEST_PORT, TEST_TOKEN)
    verify_60fps_1080p_rotated("hevc", TEST_PORT, TEST_TOKEN)

    check_android_completeness()
    elapsed = time.monotonic() - start_ts
    print(f"\nALL E2E VERIFICATION CHECKS PASSED in {elapsed:.1f}s.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
