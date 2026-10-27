#!/usr/bin/env python3
"""Run a shell command on the TV, or take a screenshot.

  tv.py 'getprop ro.build.version.sdk'
  tv.py --screenshot shots/01.png
  tv.py --key DPAD_DOWN [--key ENTER ...]
Env: TV_HOST (default 192.168.10.44), TV_PORT (5555), ADB (path to adb.exe)
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.environ.get("ADB", os.path.join(HERE, "..", "platform-tools", "adb.exe"))
SERIAL = f"{os.environ.get('TV_HOST', '192.168.10.44')}:{os.environ.get('TV_PORT', '5555')}"


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", SERIAL, *args], **kw)


def ensure_connected():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if f"{SERIAL}\tdevice" not in out:
        subprocess.run([ADB, "connect", SERIAL], capture_output=True)


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return 2
    ensure_connected()
    if a[0] == "--screenshot":
        dest = a[1]
        os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
        adb("shell", "screencap -p /data/local/tmp/s.png")
        adb("pull", "/data/local/tmp/s.png", dest, capture_output=True)
        if not os.path.exists(dest) or os.path.getsize(dest) == 0:
            print("empty screenshot: Live TV/HDMI is protected; check "
                  "dumpsys activity activities | grep topResumedActivity")
            return 1
        print(dest); return 0
    if a[0] == "--key":
        keys = [a[i + 1] for i in range(0, len(a), 2) if a[i] == "--key"]
        for k in keys:
            adb("shell", f"input keyevent KEYCODE_{k}")
        return 0
    return adb("shell", " ".join(a)).returncode


if __name__ == "__main__":
    sys.exit(main())
