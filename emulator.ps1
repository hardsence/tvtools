# Google TV emulator (Android 14, 1920x1080) with the launcher, for trying UI changes without the TV.
#   .\emulator.ps1            start the emulator (window with a remote: arrows/Enter/Esc on the keyboard)
#   .\emulator.ps1 -Setup     first run: create the AVD, wait for boot, install the launcher and set it as home
# Afterwards push new builds with:  .\install.ps1 -Device emulator-5554
# It is stock Google TV: no Xiaomi firmware (picture settings, HDMI inputs), apps from the TV or cameras over HDMI.
param([switch]$Setup)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$sdk = "$here\toolchain\sdk"
$env:ANDROID_SDK_ROOT = $sdk
$env:JAVA_HOME = (Get-ChildItem "$here\toolchain\jdk-x" -Directory)[0].FullName
$adb = "$here\platform-tools\adb.exe"
$dev = "emulator-5554"
$image = "system-images;android-34;google-tv;x86"

if ($Setup) {
    & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk emulator $image | Out-Null
    "no" | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n MiTV -k $image -d tv_1080p --force | Out-Null
    $cfg = "$env:USERPROFILE\.android\avd\MiTV.avd\config.ini"
    $lines = Get-Content $cfg | Where-Object { $_ -notmatch '^(hw\.ramSize|hw\.keyboard|hw\.gpu\.enabled|hw\.gpu\.mode|disk\.dataPartition\.size)=' }
    $lines += "hw.ramSize=4096", "hw.keyboard=yes", "hw.gpu.enabled=yes", "hw.gpu.mode=host", "disk.dataPartition.size=8G"
    Set-Content $cfg $lines -Encoding ascii
}

Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "MiTV", "-no-boot-anim"
if (-not $Setup) { return }

do { Start-Sleep 5; $booted = (& $adb -s $dev shell getprop sys.boot_completed 2>$null) } until ("$booted".Trim() -eq "1")
& "$here\install.ps1" -Device $dev -SkipBuild
# Google's home screen would keep HOME, as on the TV
& $adb -s $dev shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
& $adb -s $dev shell cmd package set-home-activity com.clean.tvhome/.HomeActivity
# match the TV: Polish launcher, Warsaw time, 24 h clock (the image has no root, so the locale is per app)
& $adb -s $dev shell service call alarm 3 s16 Europe/Warsaw | Out-Null
& $adb -s $dev shell settings put system time_12_24 24
& $adb -s $dev shell cmd locale set-app-locales com.clean.tvhome --user 0 --locales pl-PL
& $adb -s $dev shell input keyevent KEYCODE_HOME
