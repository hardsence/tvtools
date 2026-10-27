# Build the launcher and install it on the TV as the default home screen.
#   .\install.ps1               build + install + set as home
#   .\install.ps1 -SkipBuild    install the last built APK
#   .\install.ps1 -Device emulator-5554   install on the Google TV emulator instead (see emulator.ps1)
# Needs the toolchain\ folder (JDK 17, Android SDK, Gradle 8.7) next to this script.
param([string]$TvHost = "192.168.10.44", [string]$Device = "", [switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$adb = "$here\platform-tools\adb.exe"
$serial = if ($Device) { $Device } else { "${TvHost}:5555" }
$apk = "$here\launcher\app\build\outputs\apk\debug\app-debug.apk"
$home_ = "com.clean.tvhome/.HomeActivity"

if (-not $SkipBuild) {
    $env:JAVA_HOME = (Get-ChildItem "$here\toolchain\jdk-x" -Directory)[0].FullName
    & "$here\toolchain\gradle-8.7\bin\gradle.bat" -p "$here\launcher" --no-daemon -q assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}

if (-not $Device) { & $adb connect $serial | Out-Null }
# adb install can hang on Android 14 waiting for an on-screen confirmation: push + pm install instead.
& $adb -s $serial push $apk /data/local/tmp/cleanhome.apk | Out-Null
& $adb -s $serial shell pm install -r /data/local/tmp/cleanhome.apk
& $adb -s $serial shell rm /data/local/tmp/cleanhome.apk
# Android only shows the Wi-Fi name to apps holding location permission.
& $adb -s $serial shell pm grant com.clean.tvhome android.permission.ACCESS_FINE_LOCATION
& $adb -s $serial shell pm grant com.clean.tvhome android.permission.ACCESS_COARSE_LOCATION
# Lets the home screen re-enable its key service after a force-stop.
& $adb -s $serial shell pm grant com.clean.tvhome android.permission.WRITE_SECURE_SETTINGS
# Remote-button helper (accessibility service): append to whatever is already enabled.
$svc = "com.clean.tvhome/com.clean.tvhome.KeyService"
$cur = (& $adb -s $serial shell settings get secure enabled_accessibility_services).Trim()
if ($cur -notmatch [regex]::Escape($svc)) {
    $new = if ($cur -eq "null" -or $cur -eq "") { $svc } else { "${cur}:$svc" }
    & $adb -s $serial shell settings put secure enabled_accessibility_services "$new"
}
& $adb -s $serial shell settings put secure accessibility_enabled 1
# Continue watching: let the launcher see other apps' media sessions.
& $adb -s $serial shell cmd notification allow_listener com.clean.tvhome/com.clean.tvhome.MediaWatcher
& $adb -s $serial shell cmd package set-home-activity $home_
& $adb -s $serial shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
Start-Sleep 2
& $adb -s $serial shell "dumpsys activity activities | grep mFocusedApp"
