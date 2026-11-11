#!/usr/bin/env python3
"""Measures the packaged desktop app and, when a phone is connected, the installed Android app.

Run through `python scripts/verify.py perf`, which builds the package first. Standard library only.
Desktop runs use a scratch folder with synthetic chats, never the real data folder.
"""

from __future__ import annotations

import ctypes
import json
import os
import re
import shutil
import statistics
import subprocess
import tempfile
import time
from ctypes import wintypes
from pathlib import Path

PACKAGE = "io.parchment.ai"
APP_DIR_PARTS = ("composeApp", "build", "compose", "binaries", "main-release", "app", "Parchment")
APK_PARTS = ("androidApp", "build", "outputs", "apk", "release", "androidApp-release.apk")
CHATS, MESSAGES = 300, 40
IDLE_SECONDS = 10
TIMEOUT_SECONDS = 60


def folder_mb(path: Path, skip_suffix: str = "") -> float:
    total = sum(f.stat().st_size for f in path.rglob("*") if f.is_file() and not (skip_suffix and f.name.endswith(skip_suffix)))
    return round(total / 1048576, 1)


def seed_chats(data: Path) -> None:
    """Deterministic history: CHATS chats of MESSAGES replies, about 15 MB, like a long-used install."""
    reply = "This synthetic reply measures start-up with a realistic amount of history. " * 12
    for c in range(CHATS):
        cid = f"perf-{c:03d}"
        messages = [{"id": f"{cid}-m{i}", "role": "USER" if i % 2 == 0 else "ASSISTANT",
                     "variants": [{"id": f"{cid}-v{i}", "content": f"Message {i}. {reply}"}]} for i in range(MESSAGES)]
        chat = {"id": cid, "title": f"Chat {c}", "createdAt": 1700000000000 + c, "updatedAt": 1700000000000 + c,
                "profileId": "prof-gemini-interactions", "assistantId": "asst-default", "messages": messages}
        folder = data / "chats" / cid
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "chat.json").write_text(json.dumps(chat), encoding="utf-8")


# ---- Windows process and window inspection (ctypes) -------------------------------------------

user32 = ctypes.WinDLL("user32", use_last_error=True) if os.name == "nt" else None
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True) if os.name == "nt" else None
psapi = ctypes.WinDLL("psapi", use_last_error=True) if os.name == "nt" else None


class ProcessMemoryCounters(ctypes.Structure):
    _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t), ("PrivateUsage", ctypes.c_size_t)]


def process_path(pid: int) -> str:
    handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        return ""
    try:
        size = wintypes.DWORD(1024)
        buffer = ctypes.create_unicode_buffer(size.value)
        return buffer.value if kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)) else ""
    finally:
        kernel32.CloseHandle(handle)


def private_mb(pid: int) -> float:
    handle = kernel32.OpenProcess(0x1000 | 0x0010, False, pid)  # query + PROCESS_VM_READ
    if not handle:
        return -1.0
    try:
        counters = ProcessMemoryCounters()
        counters.cb = ctypes.sizeof(counters)
        ok = psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
        return round(counters.PrivateUsage / 1048576, 1) if ok else -1.0
    finally:
        kernel32.CloseHandle(handle)


def app_windows(app_dir: Path) -> list[tuple[int, int, str]]:
    """Visible top-level windows owned by an executable inside app_dir: (hwnd, pid, title)."""
    found: list[tuple[int, int, str]] = []
    prefix = str(app_dir).lower()

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if process_path(pid.value).lower().startswith(prefix):
                length = user32.GetWindowTextLengthW(hwnd)
                title = ctypes.create_unicode_buffer(length + 1)
                user32.GetWindowTextW(hwnd, title, length + 1)
                found.append((hwnd, pid.value, title.value))
        return True

    user32.EnumWindows(visit, 0)
    return found


def close_app(app_dir: Path) -> None:
    for hwnd, _, _ in app_windows(app_dir):
        user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE: a clean exit, so the class archive can be written
    deadline = time.time() + 30
    while app_windows(app_dir) and time.time() < deadline:
        time.sleep(0.2)


def desktop_run(exe: Path, work: Path) -> dict[str, float]:
    started = time.perf_counter()
    subprocess.Popen([str(exe)], cwd=work)
    window_ms = title_ms = -1.0
    pid = 0
    while time.perf_counter() - started < TIMEOUT_SECONDS and title_ms < 0:
        for _, owner, title in app_windows(exe.parent):
            if window_ms < 0:
                window_ms = (time.perf_counter() - started) * 1000
            if title.endswith("Parchment") and title != "Parchment":
                title_ms, pid = (time.perf_counter() - started) * 1000, owner
        time.sleep(0.01)
    time.sleep(IDLE_SECONDS)
    memory = private_mb(pid) if pid else -1.0
    close_app(exe.parent)
    return {"window_ms": round(window_ms), "title_ms": round(title_ms), "idle_private_mb": memory}


def measure_desktop(repo: Path, runs: int = 3) -> dict[str, object]:
    app_dir = repo.joinpath(*APP_DIR_PARTS)
    exe = app_dir / "Parchment.exe"
    if os.name != "nt" or not exe.is_file():
        return {"desktop": "skipped: needs Windows and a built package"}
    close_app(app_dir)
    work = Path(tempfile.mkdtemp(prefix="parchment-perf-"))
    try:
        seed_chats(work / "parchment-portable-data")
        # The first launch after a build records the class archive; it is reported apart from steady launches.
        first = desktop_run(exe, work)
        steady = [desktop_run(exe, work) for _ in range(runs)]
    finally:
        close_app(app_dir)
        shutil.rmtree(work, ignore_errors=True)
    return {
        "desktop_first_launch_title_ms": first["title_ms"],
        "desktop_window_ms": statistics.median(r["window_ms"] for r in steady),
        "desktop_title_ms": statistics.median(r["title_ms"] for r in steady),
        "desktop_idle_private_mb": statistics.median(r["idle_private_mb"] for r in steady),
        "desktop_app_image_mb": folder_mb(app_dir, skip_suffix=".jsa"),
        "desktop_runtime_mb": folder_mb(app_dir / "runtime"),
    }


# ---- Android, through adb --------------------------------------------------------------------

def adb() -> str:
    sdk = Path(os.environ.get("ANDROID_HOME") or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    tool = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(tool) if tool.is_file() else (shutil.which("adb") or "")


def adb_run(*args: str) -> str:
    return subprocess.run([adb(), *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def parse_total_time(output: str) -> int:
    match = re.search(r"TotalTime:\s*(\d+)", output)
    return int(match.group(1)) if match else -1


def parse_total_pss_mb(output: str) -> float:
    match = re.search(r"TOTAL PSS:\s*(\d+)", output) or re.search(r"^\s*TOTAL\s+(\d+)", output, re.M)
    return round(int(match.group(1)) / 1024, 1) if match else -1.0


def measure_android(repo: Path, install: bool, runs: int = 3) -> dict[str, object]:
    apk = repo.joinpath(*APK_PARTS)
    result: dict[str, object] = {"apk_mb": round(apk.stat().st_size / 1048576, 1) if apk.is_file() else "not built"}
    if not adb() or "\tdevice" not in adb_run("devices"):
        result["android"] = "skipped: no phone connected"
        return result
    if install:
        if not apk.is_file():
            result["android"] = "skipped: --android-install needs a release APK"
            return result
        adb_run("install", "-r", str(apk))
    if PACKAGE not in adb_run("shell", "pm", "list", "packages", PACKAGE):
        result["android"] = "skipped: Parchment is not installed on the phone"
        return result
    times = []
    for _ in range(runs):
        adb_run("shell", "am", "force-stop", PACKAGE)
        time.sleep(1)
        times.append(parse_total_time(adb_run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")))
    time.sleep(IDLE_SECONDS)
    result["android_cold_start_ms"] = statistics.median(times)
    result["android_idle_pss_mb"] = parse_total_pss_mb(adb_run("shell", "dumpsys", "meminfo", PACKAGE))
    return result


def measure(repo: Path, android_install: bool = False) -> dict[str, object]:
    return {**measure_desktop(repo), **measure_android(repo, android_install)}
