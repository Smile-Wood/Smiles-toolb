#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
无人值守验证 26.2：启动客户端 → 自检开关自动打开配置界面 → 截屏 → 关掉 → 读日志。

用法：python tools/run26_verify.py [等待秒数，默认 55]
产物：verify26.log（原始日志）、verify26.png（整屏截图）、verify26.hits.txt（关键日志行）
"""

import ctypes
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MC_HOME = Path(os.environ.get("MC_HOME", "C:/Apps/PCL2CE/.minecraft"))
VERSION = os.environ.get("MC_VERSION", "26.2")
VERSION_DIR = MC_HOME / "versions" / VERSION
CLIENT_JAR = VERSION_DIR / (VERSION + ".jar")
VERSION_JSON = VERSION_DIR / (VERSION + ".json")
JAVA = Path(os.environ.get("JAVA", "D:/Program Files/Java/jdk-26/bin/java.exe"))
NATIVES = VERSION_DIR / (VERSION + "-natives")

LOG = ROOT / "verify26.log"
SHOT = ROOT / "verify26.png"
HITS = ROOT / "verify26.hits.txt"

SECONDS = int(sys.argv[1]) if len(sys.argv) > 1 else 55
MINECRAFT_HWND = []


def windows_allows(rules):
    if not rules:
        return True
    allowed = not any(r.get("action") == "allow" for r in rules)
    for rule in rules:
        os_rule = rule.get("os") or {}
        name = os_rule.get("name")
        if name and name not in ("windows", "win"):
            continue
        allowed = rule.get("action") == "allow"
    return allowed


def classpath():
    data = json.loads(VERSION_JSON.read_text(encoding="utf-8"))
    entries = [str(CLIENT_JAR)]
    for lib in data.get("libraries", []):
        if not windows_allows(lib.get("rules")):
            continue
        artifact = (lib.get("downloads") or {}).get("artifact")
        if artifact and artifact.get("path"):
            path = MC_HOME / "libraries" / artifact["path"]
        else:
            # PCL2 写的 fabric-loader / mixin 条目只有 maven 坐标，没有 downloads 段
            parts = lib.get("name", "").split(":")
            if len(parts) < 3:
                continue
            group, artifact_id, ver = parts[0], parts[1], parts[2]
            rel = "%s/%s/%s/%s-%s.jar" % (group.replace(".", "/"), artifact_id, ver,
                                          artifact_id, ver)
            path = MC_HOME / "libraries" / rel
        if path.is_file():
            entries.append(str(path))
    return ";".join(entries)


def capture_window(hwnd, target):
    """PrintWindow 直接抓窗口内容，不需要把游戏抢到前台（桌面可能被别的全屏游戏占着）。"""
    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [("biSize", ctypes.c_uint32), ("biWidth", ctypes.c_int32),
                    ("biHeight", ctypes.c_int32), ("biPlanes", ctypes.c_uint16),
                    ("biBitCount", ctypes.c_uint16), ("biCompression", ctypes.c_uint32),
                    ("biSizeImage", ctypes.c_uint32), ("biXPelsPerMeter", ctypes.c_int32),
                    ("biYPelsPerMeter", ctypes.c_int32), ("biClrUsed", ctypes.c_uint32),
                    ("biClrImportant", ctypes.c_uint32)]

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    rect = RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    w, h = rect.right - rect.left, rect.bottom - rect.top
    if w <= 0 or h <= 0:
        return False
    hdc = user32.GetWindowDC(hwnd)
    memdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    gdi32.SelectObject(memdc, bmp)
    user32.PrintWindow(hwnd, memdc, 2)               # PW_RENDERFULLCONTENT
    bmi = BITMAPINFOHEADER()
    bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.biWidth, bmi.biHeight = w, h
    bmi.biPlanes, bmi.biBitCount = 1, 32
    bmi.biCompression = 0
    buf = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(memdc, bmp, 0, h, buf, ctypes.byref(bmi), 0)
    from PIL import Image
    Image.frombuffer("RGB", (w, h), buf, "raw", "BGRX", 0, -1).save(str(target))
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(memdc)
    user32.ReleaseDC(hwnd, hdc)
    return True


def bring_minecraft_to_front():
    """把游戏窗口提到最前面，否则截到的是桌面/编辑器。"""
    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def callback(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        if "minecraft" in buf.value.lower():
            found.append(hwnd)
        return True

    user32.EnumWindows(callback, None)
    MINECRAFT_HWND.clear()
    MINECRAFT_HWND.extend(found)
    # 只临时置顶、不改前台焦点：桌面上若有别的全屏程序，截完马上撤销置顶。
    for hwnd in found:
        user32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0040)
    return len(found)


def release_minecraft_topmost():
    user32 = ctypes.windll.user32
    for hwnd in MINECRAFT_HWND:
        user32.SetWindowPos(hwnd, -2, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0040)


def main():
    for p in (CLIENT_JAR, VERSION_JSON, JAVA):
        if not p.exists():
            raise SystemExit("缺少必需文件：" + str(p))
    cp = classpath()
    print("classpath 条目：%d" % (cp.count(";") + 1))

    args = [
        str(JAVA), "-Xmx4G",
        "-Djava.library.path=" + str(NATIVES),
        "-Dsmile_toolb.selftest.open_config=true",
        "-cp", cp,
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
    ]
    with LOG.open("wb") as log:
        proc = subprocess.Popen(args, cwd=str(VERSION_DIR), stdout=log,
                                stderr=subprocess.STDOUT)
    print("已启动 java pid=%d，等待 %d 秒" % (proc.pid, SECONDS))
    try:
        time.sleep(SECONDS)
        if os.environ.get("VERIFY_CAPTURE", "1") == "1":
            windows = bring_minecraft_to_front()
            time.sleep(2.0)
            from PIL import ImageGrab
            ImageGrab.grab().save(str(SHOT))
            release_minecraft_topmost()
            print("屏幕截图：%s（找到 %d 个 Minecraft 窗口）" % (SHOT, windows))
            if windows:
                win_shot = SHOT.with_name(SHOT.stem + "-window.png")
                if capture_window(MINECRAFT_HWND[0], win_shot):
                    print("窗口截图：%s" % win_shot)
        else:
            print("已跳过截屏（VERIFY_CAPTURE=0），不碰桌面窗口")
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                proc.kill()
        print("java 已结束，exit=%s" % proc.returncode)

    raw = LOG.read_bytes()
    for enc in ("utf-8", "gbk"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        text = raw.decode("utf-8", errors="replace")

    keys = ("Loading ", "smile_libbox", "smile_toolb", "Smile's toolb", "配置界面自检",
            "Mixin apply", "Exception", "Error", "Caused by", "Crash", "Sound engine started",
            "Mod Menu 集成", "假人")
    lines = [l for l in text.splitlines() if any(k in l for k in keys)]
    HITS.write_text("\n".join(lines), encoding="utf-8")
    print("关键日志 %d 行 → %s" % (len(lines), HITS))
    for l in lines[-25:]:
        print("  " + l)


if __name__ == "__main__":
    main()
