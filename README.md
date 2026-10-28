# Xiaomi TV MAX 85 (MiTV-MOEU0): debloat + clean home screen

What was done on 2026-10-21, how to change it, and how to undo it. Everything is reversible:
nothing was uninstalled, only disabled.

## What changed on the TV

- **Disabled packages**: listed with reasons in [debloat-list.txt](debloat-list.txt). That covers
  Xiaomi telemetry, PatchWall content and ads, the cleaner, the manual, Xiaomi's background
  app updater, Prime Video, YouTube, Google TV (movies), Mi Music, Mi Gallery, the Mi video player
  and Media Explorer, plus the replaced launchers: Google TV home, PatchWall, Projectivy and the
  Google setup wizard.
- **Kept on purpose**: Live TV and the tuners, HDMI/CEC, the remote and Bluetooth services, the
  keyboard, Play Store and Play Services, Chromecast, Google Assistant, Miracast, AirPlay, the
  firmware updater and Play Games.
- **Netflix**: disabled, but the firmware turns it back on whenever you press the remote's Netflix
  button. That was left as is.
- **Ad-blocking DNS**: Private DNS is set to `dns.adguard-dns.com`. It does not block YouTube ads.
- **YouTube links open in SmartTube**: youtube.com, www/m.youtube.com and youtu.be are approved
  for SmartTube (`pm set-app-links-user-selection`). The `vnd.youtube:` links already went there.
  Google voice-search video results still fail: the Google app sends them only to the official
  YouTube app, which is disabled. Search inside SmartTube instead, or re-enable YouTube with
  `python tools\restore.py com.google.android.youtube.tv` (results then play there).
- **Wi-Fi**: disabling the setup wizard deleted the Wi-Fi network saved at first setup. The TV is on
  Ethernet, so nothing dropped. If you ever switch to Wi-Fi, add the network again in Settings.
- **Home screen**: the custom launcher `com.clean.tvhome`, with its "Przyciski pilota" (remote
  buttons) accessibility service enabled. Android switches that service off whenever the app is
  force-stopped, so the launcher holds `WRITE_SECURE_SETTINGS` (granted over ADB) and switches it
  back on the next time the home screen appears.
- **Untouched**: the display override (3840x2160 shown at 1920x1080) was already there before this
  session.

The backup taken before any change (package lists, getprop, all settings) is in
`backup/20261021-1214/`. Screenshots of every step are in `shots/`.

## The home screen

From the top down: the top bar (clock, search, weather, Źródła, network, Ustawienia), the trending
slider's text and dots, **every app as a round icon** in one row that slides sideways, **Kontynuuj
oglądanie**, and below the screen three TMDB rows: **Popularne filmy w streamingu**, **Popularne
seriale w streamingu** and **Najnowsze zwiastuny**. Going down into the TMDB rows scrolls the screen
up and dims the backdrop, like Google TV.

| Button | Does |
|---|---|
| D-pad | Moves exactly one icon, card or row. The ends of each row stop. |
| OK | Opens the app / card, or the pill's action. On a film or series poster: Search for the title. On a trailer: plays it in SmartTube. |
| Back | Returns to the round app icons (or closes the open menu / window) |
| Home (while already home) | Returns to the round app icons. Coming back from an app keeps the one you left. |
| Menu | App info for the focused app (force stop, clear data, disable) |
| Hold OK on an app | Lifts it to move it (see below) |
| Remote: YouTube | SmartTube |
| Remote: Prime Video | Disney+ |
| Remote: Settings | TV settings |
| Remote: Apps | Opens the **Aplikacje** window: every app as a tile in a grid (Back closes it) |

The **Źródła** (Inputs) pill lists HDMI 1–3 and Telewizja (Live TV) and switches through the TV
input framework. The **Ethernet/Wi-Fi** pill shows the connection and opens network settings.
The **Ustawienia** pill opens a small menu: **Ustawienia telewizora** (the system settings) and
**Kolumny ‹ 5 ›**. On the Kolumny row, Left/Right picks 4–8 columns for the Aplikacje window's
grid and OK applies it. The remote's own Settings button still goes straight to the system settings.

This setting can't live under Settings → Wyświetlacz i dźwięk → Ekran: that entry is built into
Xiaomi's Settings app and always opens its picture-format screen. Other apps can't add options there
without root.

### Search (magnifier, first on the top bar)

OK on the magnifier opens search with the on-screen keyboard. Results appear as you type, after a
short pause:

- **Aplikacje:** installed apps whose name matches.
- **One row per app** with searchable content, with its icon and name. Each card shows a cover, a
  title and a subtitle (year, channel…); OK opens the item inside that app.

This follows developer.android.com/training/tv/discovery/searchable. Every app with an
`ACTION_SEARCH` activity and `android.app.searchable` metadata naming a `searchSuggestAuthority` is
queried at `content://<authority>[/path]/search_suggest_query`, the same request Android TV's own
global search makes. On this TV that returns **Stremio** (films and series) and **SmartTube**
(YouTube videos). VLC and the TCL browser lock their providers to the system-only `GLOBAL_SEARCH`
permission, so a launcher can't search them.

Search also checks matching films and series against TMDB's watch-provider data for Poland and shows
titles available on Disney+ in a **Disney+ · TMDB** row. OK opens TMDB's regional provider link for the
title, which can be handled by the Disney+ app. This uses the same TMDB API key as the trending and
streaming rows; TMDB is credited in the result subtitle.

### Trendy and the TMDB rows (The Movie Database)

Behind the home screen runs a slider of trending films and series: **today's** trending first, then
**this week's** titles that were not already in it (no duplicates, up to 15). A full-screen
backdrop, the title, rating, year, type and a short description on the left, one dot per title on
the right just above the app icons. The slides change by themselves every 9 seconds (not while you
browse them or a window is open).

- **Remote:** Down from the top bar (or Up from the app icons) selects the dots; Left/Right change
  the slide. OK opens Search with the title and searches the apps right away (results get the
  focus, no keyboard).
- **Popularne filmy / seriale w streamingu:** TMDB's "What's popular", Streaming tab: titles on
  subscription services in Poland (`discover/movie|tv` with `watch_region=PL` and
  `with_watch_monetization_types=flatrate`), 20 posters each. The header names the focused title,
  its year and rating. OK searches for it in the apps.
- **Najnowsze zwiastuny:** like TMDB's "Latest trailers": films in cinemas, coming soon and series on
  air, each with its newest YouTube trailer (Polish when there is one, else English), newest first.
  OK plays it in SmartTube.
- **Data:** everything in the TV's language with English overviews where the Polish one is missing.
  Lists and images are cached in the app's `files/tmdb/` and refreshed every 6 hours, so the screen
  is full at once after a restart and still works offline.
- **API key:** `launcher/app/src/main/res/values/tmdb.xml`. Leave it empty to turn all of this off.
  The file is not in Git; copy it from `launcher/config-examples/tmdb.xml` and add your key.

### Continue watching (Kontynuuj oglądanie)

Under the round app icons sits a row of small cards for what you watched recently, newest first.
Each card shows the cover, the title, a description (channel, episode…) with the app name, and a
progress bar at the point where you stopped.

- **Remote:** Down from the app icons selects the row; Left/Right move along
  it. OK reopens the item and Menu removes it from the list. Without the trending slider the
  background glow takes the colour of the selected card, or of the selected app.
- **Why it's the launcher's own list:** Android's system Continue Watching list
  (`WATCH_NEXT_TYPE_CONTINUE`) can only be read by privileged launchers.
  `ACCESS_ALL_EPG_DATA` is signature|privileged and `pm grant` refuses it. Instead, `MediaWatcher`
  (a notification listener, enabled by `install.ps1` with `cmd notification allow_listener`)
  watches the media sessions apps publish (SmartTube, VLC, Disney+, Max, Stremio, Spotify…) and
  records the title, description, cover, position and duration every 15 s and on pause.
- **Limits:** up to 12 items, and anything more than 95 % watched drops out. The link back into an
  item is the app's own session link; it lasts until the TV restarts, after which OK opens the app.
  Apps that publish no media session don't appear.

### Rearrange apps with the remote

Hold OK on any app (about half a second), on the round icons or in the Aplikacje window, and it
lifts: a brighter frame and a hint appear. Each arrow press swaps it with its neighbour. Press OK to
drop it there. Both places share one order, saved to `order.txt` on the TV, so it survives restarts.
Back or Home cancels and puts everything back.

The greyed-out **Ekran** entry under Settings → Wyświetlacz i dźwięk is Xiaomi's picture format
(aspect ratio). It only becomes active while you're watching an HDMI input or TV; it has nothing
to do with the home screen or the debloat.

### Change the app order from a computer (no rebuild)

The remote rearranging above writes this same file, so a file pushed from a computer replaces
whatever was arranged on the TV, and the other way round.

Create a text file with one package name per line: the apps come first in that order (round icons
left to right, grid row by row), and all other apps follow alphabetically. For example, `order.txt`:

```
org.smarttube.beta
org.videolan.vlc
com.nvidia.geforcenow
com.limelight.perf
com.disney.disneyplus
pl.cda.tv
```

Push it and restart the home screen (PowerShell, from this folder):

```powershell
.\platform-tools\adb.exe connect 192.168.10.44:5555
.\platform-tools\adb.exe shell mkdir -p /sdcard/Android/data/com.clean.tvhome/files
.\platform-tools\adb.exe push order.txt /sdcard/Android/data/com.clean.tvhome/files/order.txt
.\platform-tools\adb.exe shell am force-stop com.clean.tvhome
.\platform-tools\adb.exe shell input keyevent KEYCODE_HOME
```

To go back to the built-in order, delete the file (`adb shell rm` the same path) and restart the
home screen the same way. Package names are listed by `python tools\tv.py pm list packages -3`.

To make the order permanent, edit `dock_packages` and `grid_first` in
`launcher/app/src/main/res/values/config.xml` and run `.\install.ps1`.

### Change the wallpaper

The background is pure black by default. To use a picture, push a JPG, PNG or WebP named
`wallpaper.jpg`, `wallpaper.png` or `wallpaper.webp`:

```powershell
.\platform-tools\adb.exe push my-photo.jpg /sdcard/Android/data/com.clean.tvhome/files/wallpaper.jpg
.\platform-tools\adb.exe shell am force-stop com.clean.tvhome
.\platform-tools\adb.exe shell input keyevent KEYCODE_HOME
```

To return to black, delete it with `adb shell rm` on the same path and restart the home screen.
Dark photos work best, since the clock and pills are white.

### Remap remote buttons

Edit `key_actions` in `config.xml`. The actions are `settings`, `apps`, `inputs`, `home` and
`app:<package>`. To find a button's code, run
`.\platform-tools\adb.exe logcat -s CleanHomeKeys` and press the button. The YouTube and Prime
buttons send gamepad keycodes, so they are matched by scancode (`scan:425`, `scan:207`); that
keeps real gamepad buttons working in GeForce NOW and Steam Link.

### Home cameras (Źródła ▸ Kamery domowe)

The last entry in the **Źródła** menu opens a 2×2 live grid of the four Dahua cameras: .2, .61,
.62 and .65.

- Arrows pick a view and OK opens it full screen.
- In full screen, Left/Right switch cameras.
- Back returns to the grid, and Back again closes the screen.

The grid plays each camera's sub stream (704×576 H.265). Full screen plays the 4K main stream, and
the grid players are released first so the TV's video decoders stay free. Video plays without
sound and over RTSP/TCP. The players only run while the screen is open. A camera that drops shows
"Brak połączenia, ponawiam…" and reconnects every 5 seconds.

- **Settings:** addresses, user and password live in `launcher/app/src/main/res/values/cameras.xml`.
  Change them there and run `.\install.ps1`. Note that the password is stored in that file and in
  the app on the TV. The file is not in Git; copy it from `launcher/config-examples/cameras.xml`.
- **Change made on the cameras:** the sub stream (ExtraFormat) was disabled on all four and was
  switched on for this, via the camera's HTTP API
  (`configManager.cgi?action=setConfig&Encode[0].ExtraFormat[0].VideoEnable=true`). Recording and
  the main stream are unchanged. To undo it, set `VideoEnable=false` the same way, or switch the
  sub stream off in the camera's web page.
- **Screenshots:** on this TV, `screencap` can't see the hardware video layer. Screenshots of
  camera video can come out green or show a stale picture while the TV itself shows the image
  correctly.

### Weather (Ustawienia ▸ Pogoda)

The first pill on the top bar shows today's sky and the current temperature (e.g. ☀ 7°). OK on
it opens the forecast window under it, like the other menus, frosted underneath.

- **Days:** Left/Right walk through today and the next seven days. Today shows the current
  conditions and the next 24 hours; a later day shows its high and low, rain total and chance,
  humidity, peak wind and its own 00:00–24:00 curve. The strip at the bottom lists all six days.
- **Every 3 hours** on the curve: the sky for that hour (sun or moon, clouds, rain, snow, storm),
  a wind arrow pointing where the wind blows, with its speed in km/h, and the precipitation of
  those 3 hours in mm (1 mm = 1 litre per m²).
- **Precipitation marks:** under the curve, each hour with precipitation gets a mark: a blue drop
  for rain or drizzle, a white flake for snow and a yellow drop for a storm.
- **Closing:** Up or Back closes the window.

**Radar:** the window has a **Radar** button in its top-right corner; press OK to open a full-screen precipitation radar centred on the TV's location (red
dot). The map covers about 360 × 200 km on Full HD and shows a 50 km scale bar.

- **Frames:** the last 2 hours in 10-minute steps. While the home screen is open and weather is
  on, it keeps finished radar images in the app's cache on the TV (`cache/radar`, about 15 PNGs).
  It refreshes them every 10 minutes on a separate background thread. The radar therefore opens
  complete in about 2 seconds and only fetches what's still missing. Old frames and images for
  another location are deleted.
- **Top left:** the town, its coordinates, and the time of the frame shown (just "Teraz" for the
  newest one).
- **Bottom bar:** **+**, **−** and **▶/■**, then the timeline. Left/Right walk through all of them.
  - Stepping onto a time stops the animation and shows that frame, all the way to "Teraz".
  - OK on a time plays on from there.
  - **+ / −** step through four zoom levels: ~360 × 200 km, 720 × 400, 1440 × 800 and
    2900 × 1600 km (scale 50 / 100 / 200 / 400 km, updated by itself).
  - Back closes the radar.
- **Zoom without new downloads:** every time step is saved as two 960 × 540 images. One covers
  levels 1–2, the other levels 3–4 (from coarser zoom 5 tiles); the closer level of each pair shows
  its middle quarter. The map and town names are
  saved for both levels. RainViewer's free tiles stop at zoom 7, so the radar can't zoom in closer
  than the normal view.
- **Data:** RainViewer (free, no key; at most zoom 7 and no forecast frames on the free tier),
  over Esri's Dark Gray Canvas map. Town names are drawn above the rain so they stay readable.
- **Colours:** RainViewer's free tiles always use its blue palette, where everything below 15 dBZ
  is a beige haze. The app maps every pixel back to dBZ and repaints it (`RadarPalette.java`):
  - under 10 dBZ is hidden and drizzle is a faint hint
  - rain runs light green to dark green
  - heavy rain is yellow, then orange, then red
  - hail is magenta

**Ustawienia → Pogoda** (switch) shows or hides the weather pill and starts or stops fetching the forecast. Left/Right or OK switch it, and the
choice is remembered.

The data comes from Open-Meteo (free, no account or key). The location comes from the TV's public
IP via ipwho.is, re-checked once a day. The forecast refreshes at most every 30 minutes while the
home screen is open. The last forecast is cached, so the card appears at once after a restart.
Nothing is fetched while the card is off.

### TV usage timer (Ustawienia ▸ Timer użytkowania)

The window has hours : minutes (0–12 h, 5-minute steps) and four code boxes.

- **Controls:** Left/Right move between fields and Up/Down change a value. The remote's number
  keys also work if it has them. OK on **Uruchom timer** starts it.
- **Code:** it is remembered across timers and restarts until you change it. With no timer
  running, the boxes show it as dots, and typing new digits sets a new code. While a timer runs,
  the current code is needed to change or cancel it (00:00 + code = cancel), so it can't just be
  extended.
- **While running:** a countdown sits in the top-left corner over every app and turns amber in the
  last minute.
- **When time is up:** the TV goes to the home screen, playback stops, and a full-screen window asks
  for the code. Every button except power is ignored, including Home, Back and the app buttons. A
  wrong code clears the boxes; the right one unlocks and resets the timer.
- **Off and restart:** the end time is a clock time and the locked state is saved, so switching the
  TV off doesn't pause the timer. If it ran out while the TV was off, the lock appears right after
  boot. This was tested with a reboot.
- **How it works:** the countdown and lock are drawn by the remote-button accessibility service.
  If that service is switched off, there is no lock. The home screen switches it back on itself.

**Forgotten code** (needs ADB):

```powershell
python tools\tv.py "run-as com.clean.tvhome cat shared_prefs/settings.xml"   # shows timer_code
python tools\tv.py "run-as com.clean.tvhome sed -i 's/name=\"timer_locked\" value=\"true\"/name=\"timer_locked\" value=\"false\"/' shared_prefs/settings.xml"
python tools\tv.py "am force-stop com.clean.tvhome"; python tools\tv.py "input keyevent KEYCODE_HOME"
```

### Picture settings (Ustawienia ▸ Obraz)

The **Ustawienia** pill → **Obraz ›** opens a panel on the right. Up/Down picks a row,
Left/Right changes its value (applied immediately), OK opens a page or runs an action, Back goes
back or closes the panel.

- **Komfort**
  - **Tryb ciemny** drops the backlight to 0 and adds a dimming layer, so the screen gets darker
    than the TV's own minimum. **Przyciemnienie** sets how strong that layer is. Turning it off
    restores the previous backlight.
  - **Automatycznie wieczorem** switches dark mode on at the chosen hour and off at 7:00. A manual
    change in between is respected.
  - **Filtr światła niebieskiego** adds a warm tint at 10–80 %.
  - **Wyłącz ekran** turns the picture off while sound keeps playing. Any remote button brings it
    back.
- **Ukryte opcje Xiaomi**: Precyzja szczegółów, Inteligentne przystosowywanie, Wygładzanie ruchu,
  and the Płynny ruch / Wyostrzony ruch sliders. The sliders only have an effect when Wygładzanie
  ruchu is set to Własny.
- **Kalibracja**
  - **Tuner kolorów**: hue, saturation and gain for 7 colours. Turning it on switches AI PQ off,
    because Xiaomi doesn't allow both at once.
  - **Balans bieli (20 punktów)**: R/G/B per brightness level from 5 to 100 %.
  - **Zablokuj kalibrację** puts your tuner and white-balance values back whenever the firmware
    reloads them after a picture-mode change.

How it works: the panel calls Xiaomi's own picture API (`mitv.display.PictureSettingsManager` from
the `mitvmiddlewareimpl` system library) — the same one Xiaomi's Settings uses. No root is needed.
Only options this model supports are listed. Xiaomi's capability flags mark eye care, local dimming,
local contrast and peak brightness as unsupported on this panel, and neo-gamma, color booster, blue
stretch and dynamic contrast refuse every value, so those were left out. The dimming layer, the
filter and screen-off are drawn by the remote-button service, so that service must be on.

Picture changes are stored by the TV itself; `restore.py` does not undo them. To undo them, use
Settings → Wyświetlacz i dźwięk → Obraz → Zresetuj ustawienia obrazu, or the Resetuj rows in the
tuner and white-balance pages.

To log every picture value for troubleshooting (read only), run:
`.\platform-tools\adb.exe shell am start -n com.clean.tvhome/.HomeActivity --es com.clean.tvhome.ACTION pqdump`
and then `.\platform-tools\adb.exe logcat -s CleanHomePq`.

## Rebuild and reinstall

```powershell
.\install.ps1              # build, install, grant permissions, enable key service, set as Home
.\install.ps1 -SkipBuild   # reinstall the last build
```

`toolchain/` contains JDK 17, the Android SDK (platform 34, build-tools 34) and Gradle 8.7.
`install.ps1` uses `pm install` over the shell, because `adb install` can hang on Android 14.

## Try it on the PC (Google TV emulator)

```powershell
.\emulator.ps1 -Setup                      # once: AVD "MiTV" (Google TV, Android 14, 1920x1080), install, set as Home
.\emulator.ps1                             # later: just start it
.\install.ps1 -Device emulator-5554        # push a new build to the emulator instead of the TV
```

The emulator window has an on-screen remote (Ctrl+Shift+D, or "..." > TV remote); the keyboard works too:
arrows, Enter = OK, Esc or Ctrl+Backspace = Back, Ctrl+H = Home, Ctrl+M = Menu, Ctrl+= / Ctrl+- = volume. It runs stock Google TV with the same launcher, so layout, menus, weather, radar,
search and the timer behave as on the TV. What is missing: the Xiaomi picture settings (Obraz shows only what
stock Android has), the HDMI inputs, the apps installed on the TV (the app row shows the emulator's apps) and
the cameras unless the PC is on the home network. Uses Windows Hypervisor Platform (WHPX), already enabled.

## Re-enable an app

You need to turn ADB back on first (see the next section). Then run one of these:

```powershell
python tools\tv.py pm enable --user 0 com.amazon.amazonvideo.livingroom   # one app
python tools\restore.py com.netflix.ninja com.mitv.gallery                 # several
python tools\restore.py                                                    # undo everything
```

A full `restore.py` re-enables everything in `debloat-list.txt` (including Google TV home and the
setup wizard), gives Home back to Google TV, switches the key service off and resets Private DNS
to automatic. The custom launcher stays installed; remove it with
`python tools\tv.py pm uninstall com.clean.tvhome`.

To undo only the ad-blocking DNS:

```powershell
python tools\tv.py settings put global private_dns_mode opportunistic
```

## Turning ADB back on (it is off now)

1. Settings → System → About (Informacje): press OK 7 times on the Android TV OS build entry.
   Skip this if Developer options is already listed under System.
2. Developer options: turn on USB debugging (and Network/ADB debugging if it's listed).
3. Run `.\platform-tools\adb.exe connect 192.168.10.44:5555` and accept the prompt on the TV.

While USB debugging is on, the TV may cut power to the USB port, so the USB drive can disappear.
When you're done, switch debugging off again and run `python tools\adb-closed.py 192.168.10.44`.
Port 5555 is unencrypted: any device on your network could get a shell while it is open.

## Files

| Path | What |
|---|---|
| `debloat-list.txt` | Every disabled package, grouped, with notes |
| `tools/tv.py` | Run a shell command on the TV, send keys, or take a screenshot (`--screenshot`) |
| `tools/restore.py` | Undo script |
| `tools/adb-closed.py` | Checks that network ADB is closed |
| `install.ps1` | Build and install script (`-Device emulator-5554` for the emulator) |
| `emulator.ps1` | Start the Google TV emulator; `-Setup` creates it and installs the launcher |
| `launcher/` | Home screen source (Java, AndroidX RecyclerView, minSdk 26, target 34) |
| `launcher/OUTFIT-OFL.txt` | Licence of the Outfit typeface (SIL Open Font License) |
| `backup/` | State before any change |
| `shots/` | Screenshots taken to verify each step |
