#!/usr/bin/env python3
"""Undo the debloat: re-enable every package in debloat-list.txt, give Home back to the
stock Google TV launcher, switch off the remote-button helper and reset Private DNS.

  python tools/restore.py            # everything
  python tools/restore.py com.x.y    # re-enable just these packages
Needs USB/Network debugging switched on again on the TV (see README).
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TV = [sys.executable, os.path.join(HERE, "tv.py")]
KEY_SERVICE = "com.clean.tvhome/com.clean.tvhome.KeyService"


def sh(cmd):
    r = subprocess.run(TV + [cmd], capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    print(f"{cmd}: {out}")
    return out


def listed():
    with open(os.path.join(HERE, "..", "debloat-list.txt")) as f:
        return [l.strip() for l in f if l.strip() and not l.startswith("#")]


pkgs = sys.argv[1:] or listed()
for p in pkgs:  # one pm call per shell call: chained pm commands can stop early
    sh(f"pm enable --user 0 {p}")

if not sys.argv[1:]:
    # Otherwise the home screen would switch its key service straight back on.
    sh("pm revoke com.clean.tvhome android.permission.WRITE_SECURE_SETTINGS")
    services = sh("settings get secure enabled_accessibility_services")
    rest = [s for s in services.split(":") if s and s != "null" and s != KEY_SERVICE]
    if rest:
        sh("settings put secure enabled_accessibility_services " + ":".join(rest))
    else:
        sh("settings delete secure enabled_accessibility_services")
        sh("settings put secure accessibility_enabled 0")
    sh("cmd package set-home-activity com.google.android.apps.tv.launcherx/"
       "com.google.android.apps.tv.launcherx.home.HomeActivity")
    sh("pm set-app-links-user-selection --user 0 --package org.smarttube.beta false "
       "youtube.com www.youtube.com m.youtube.com youtu.be")
    sh("settings put global private_dns_mode opportunistic")
    sh("settings delete global private_dns_specifier")
    print("\nDone. The custom home screen is still installed; remove it with:\n"
          "  python tools/tv.py pm uninstall com.clean.tvhome")
