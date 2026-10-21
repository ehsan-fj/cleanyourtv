# Sony KD-49X8000D CleanYourTV Setup & Maintenance Guide

This document summarizes the changes made to your **Sony KD-49X8000D** (`BRAVIA 4K GB`, Android TV 9 / API 28), and provides instructions for maintenance, customization, and full restoration.

---

## 1. Safety & USB Debugging Reminder

> [!WARNING]
> **Switch Off USB Debugging When Done:**
> Some Sony Android TV models cut or throttle power to the physical USB ports while USB debugging is active, which can cause connected USB flash drives, hard drives, or TV tuners to disconnect or misbehave. Additionally, leaving ADB open over Wi-Fi exposes a debugging interface on your local network.
>
> Once you are happy with the setup, turn off USB debugging on the TV:
> **Settings → Device Preferences → Developer options → USB debugging → OFF**
>
> *(The custom launcher, debloated state, and all changes will continue running normally without USB debugging!)*

---

## 2. Fast Restoration

If you ever want to revert back to the stock Sony Android TV launcher and re-enable all original system apps, run the provided restore script:

```bash
TV_HOST=192.168.1.40 backup/restore_backup.sh
```

### What `restore_backup.sh` does:
1. Re-enables all 25 debloated packages tracked in `backup/debloated_packages.txt`.
2. Re-enables the stock Sony Android TV launcher (`com.google.android.tvlauncher`).
3. Resets the default Home activity back to `com.google.android.tvlauncher/.MainActivity`.
4. Restores system animation scales to 1.0x.
5. Launches the stock home screen on the TV.

---

## 3. How to Re-Enable Any Individual App

If you want to bring back a specific app or service (such as Netflix, Apple TV, Spotify, or Samba TV):

### Method A: Over ADB / Python Tool
From your computer terminal in this repo:
```bash
# Example: Re-enable Netflix
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'pm enable com.netflix.ninja'

# Example: Re-enable Apple TV
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'pm enable com.apple.atve.androidtv.appletv'

# Example: Re-enable Spotify
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'pm enable com.spotify.tv.android'
```

### Method B: Directly on the TV (No Computer Needed)
1. Go to **Settings → Apps → See all apps**.
2. Scroll to the bottom to **Disabled apps**.
3. Select the app you wish to re-enable and click **Enable**.

---

## 4. Rebuilding & Installing the Launcher

The native launcher source code is located in `launcher/`. If you modify `config.xml` or source files, re-build and re-install with:

```bash
TV_HOST=192.168.1.40 INPUT_KEY_SERVICE=1 launcher/build-and-install.sh
```

---

## 5. Automatic LG Sound Bar / ARC Output Switching

Your Sony TV previously defaulted to TV speakers when powering on because the LG sound bar on HDMI 3/ARC was still waking up when the TV initially sent its CEC query.

The launcher now automates this:
- **How it works:** Communicates directly with Sony's internal `com.sony.dtv.braviasyncservice.BraviaSyncService` via IPC (`MSG_CHANGE_PEER_AUDIO_MODE`), sending mode `1` (Audio System) after a 3.5s and 7s delay once the sound bar is fully awake.
- **Triggers:**
  1. **Standby Wake:** Listens to `ACTION_SCREEN_ON` via `InputKeyService` (runs across all apps).
  2. **Cold Boot:** Listens to `BOOT_COMPLETED` via `AudioSyncReceiver`.
  3. **Launcher Resume:** Fired in `MainActivity.onResume()` whenever you return home.
- **Manual Trigger over ADB (for testing):**
  ```bash
  # Connect to LG Sound Bar:
  TV_HOST=192.168.1.40 tools/venv/bin/python tools/tv.py 'am broadcast -n com.gathin.tvhome/.AudioSyncReceiver -a com.gathin.tvhome.ACTION_SYNC_AUDIO --ei mode 1'

  # Switch to TV Speakers:
  TV_HOST=192.168.1.40 tools/venv/bin/python tools/tv.py 'am broadcast -n com.gathin.tvhome/.AudioSyncReceiver -a com.gathin.tvhome.ACTION_SYNC_AUDIO --ei mode 0'
  ```

---

## 6. Changing the Dock Order or Wallpaper

You can change the dock apps, grid columns, or set a custom wallpaper **without rebuilding the launcher** by pushing a theme configuration, or permanently in `config.xml`.

### Method A: Live Theme (No Rebuild Required)
Create a folder (e.g. `themes/mytheme/`) containing a `config.json`:

```json
{
  "dock_apps": [
    "com.google.android.youtube.tv",
    "org.jellyfin.androidtv",
    "com.vewd.core.integration.dia",
    "nz.co.tvnz.ondemand.tv"
  ],
  "columns": 4,
  "background_color": "#000000",
  "wallpaper": true
}
```

If using a custom wallpaper, drop a `wallpaper.jpg` (recommended 1920×1080 dark JPEG, under 300 KB) into `themes/mytheme/`.

Then apply it over ADB:
```bash
TV_HOST=192.168.1.40 tools/theme.sh themes/mytheme
```

To remove the theme overlay and revert to defaults:
```bash
TV_HOST=192.168.1.40 tools/theme.sh --reset
```

### Method B: In `config.xml` (Permanent)
Edit `launcher/app/src/main/res/values/config.xml`:
```xml
<string-array name="dock_apps">
    <item>com.google.android.youtube.tv</item>
    <item>org.jellyfin.androidtv</item>
    <item>com.vewd.core.integration.dia</item>
    <item>nz.co.tvnz.ondemand.tv</item>
</string-array>
```
Then reinstall using:
```bash
TV_HOST=192.168.1.40 launcher/build-and-install.sh
```

---

## 7. Dynamic Wallpaper Shuffle (Family Photos & Screen Saver)

The launcher includes an ultra-efficient, native wallpaper shuffle system designed specifically for the low-RAM hardware of the Sony Bravia:

### How it works:
- **Wake / Home Shuffle (Option A):** Whenever the TV turns on or you return to the home screen from an app or HDMI input, a new random wallpaper from the collection is displayed without repeating the last image.
- **Idle Shuffle (Option C):** When left idle on the home screen, the wallpaper automatically crossfades every **15 seconds** (configurable via `wallpaper_shuffle_seconds`).
- **Interactive Reset:** Pressing any button on the remote (D-pad, OK, etc.) immediately resets the 15-second countdown, ensuring photo transitions never interrupt browsing.
- **Pure Color Fidelity (Zero Tint):** The ambient accent glow from focused apps (e.g. YouTube red) is automatically disabled when wallpapers are active so photos appear with 100% natural, true colors.
- **Translucent Glass Dock & Lowered Profile:** The dock background is a subtle 27% translucent glass band with a frosted border instead of solid black, letting the bottom of the photo show through while browsing. The dock is also positioned closer to the bottom edge.
- **1-Minute Idle Auto-Hide (Configurable):** If the TV is left idle for **1 minute** (60 seconds, configurable), the dock, focused highlight, and app title smoothly fade out completely, giving you a full, unobstructed digital picture frame. Pressing any button on the remote instantly wakes up the UI without triggering accidental app launches.
- **Configuring Auto-Hide Delay:**
  - **Quick CLI Tool:**
    ```bash
    TV_HOST=192.168.1.40 tools/set_dock_hide.sh 60    # 1 minute (default)
    TV_HOST=192.168.1.40 tools/set_dock_hide.sh 2m    # 2 minutes
    TV_HOST=192.168.1.40 tools/set_dock_hide.sh 0     # Disable auto-hide (always visible)
    TV_HOST=192.168.1.40 tools/set_dock_hide.sh --get # View current setting
    ```
  - **In `config.json` (theme overlay):** Add `"dock_hide_seconds": 60` or `"dock_hide_minutes": 1` (or `0` to disable).
  - **In `config.xml` (built-in default):** Set `<integer name="dock_hide_seconds">60</integer>`.
- **Configuring Shuffle Interval:**
  - **In `config.json` (theme overlay):** Add `"wallpaper_shuffle_seconds": 15`.
  - **In `config.xml` (built-in default):** Set `<integer name="wallpaper_shuffle_seconds">15</integer>`.
- **Apple TV-Style Contrast Scrim:** A calibrated 25% dark scrim layer is placed over wallpapers so white text (clock, date) and frosted glass pill buttons remain crisp, readable, and high-contrast regardless of photo brightness.
- **Subtle Photo Caption (EXIF Date & Location):** When the dock auto-hides into digital photo frame mode, an unobtrusive caption (e.g. `24 December 2023`) automatically fades into the bottom-right corner. It extracts original photo capture dates directly from EXIF metadata (`DateTimeOriginal` / `DateTime`) and formats them cleanly. Pressing any remote button fades the caption out and brings the dock back.
- **Resource Efficiency & Zero Background Draw:**
  - Decodes images asynchronously on a single background worker thread using `RGB_565` format (~4 MB RAM footprint at rest, ~8 MB peak during crossfade).
  - Explicitly recycles previous bitmaps to avoid Android GC stutter.
  - Pauses all timers and threads completely (`onPause()`) when you open an app (YouTube, Jellyfin, Netflix) or switch to HDMI inputs — **0% CPU and 0 MB processing overhead while watching content**.
  - Handles photo rotation tags automatically via runtime EXIF transposition.

### Pushing or Updating Photos:

Use the bundled `tools/push_wallpapers.sh` script to optimize (auto-orient, Lanczos downscale to 4K [3840×2160], compress while preserving EXIF timestamps) and transfer photos in seconds:

```bash
# Push default family_photo/ directory:
TV_HOST=192.168.1.40 tools/push_wallpapers.sh

# Push from any custom directory:
TV_HOST=192.168.1.40 tools/push_wallpapers.sh /path/to/my/photos

# Replace existing photos completely (--clean flag):
TV_HOST=192.168.1.40 tools/push_wallpapers.sh /path/to/my/photos --clean
```

**Device Storage Locations:**
- Primary: `/sdcard/Android/data/com.gathin.tvhome/files/wallpapers/`
- Secondary / External: `/sdcard/Pictures/wallpapers/`

---

## 8. Ultra-Low-Power Weather Pill in Top Bar

A frosted glass pill in the top-right bar (e.g. `☀️ 15°C`) showing current temperature and weather conditions:
- **Default Location:** Configured for **Wellington, New Zealand** (`weather_city` in `config.xml`).
- **Zero Ongoing Power / Zero Wakeups:** Runs entirely on-demand with a 45-minute cache in `SharedPreferences`. When you are in YouTube, Netflix, or watching TV, the weather service performs **zero background polling**.
- **Instant Invalidation:** Changing the city immediately invalidates cached data so updates show up without waiting for the 45-minute window to lapse.
- **No API Keys or Paid Accounts:** Queries `wttr.in/Wellington%2C+New+Zealand?format=j1`.
- **Interactive Details:** Clicking or selecting the weather pill with the remote displays an instant Android Toast with full condition details (e.g. `Wellington, New Zealand: 15°C, Sunny`).
- **Configuration:**
  - Can be toggled with `"show_weather": true/false` in `config.json` or `config.xml`.
  - Specific city is set with `"weather_city": "Wellington, New Zealand"` (or left empty for auto-detect by IP).

---

## 9. Couch-Friendly Typography

The header typography has been enlarged and tuned for legibility from living room viewing distances:
- **Greeting:** `16sp` uppercase Outfit with subtle letter tracking (`GOOD AFTERNOON`).
- **Time Clock:** `52sp` light Outfit with clean AM/PM pill.
- **Date:** `16sp` medium weight Outfit (`TUESDAY 20 OCTOBER`), balanced with 4 top-bar pills without truncation.

---

## 10. Private DNS Settings

- Current mode: `opportunistic` (Automatic fallback mode).
- If your home network router or ISP does not block DoT (port 853), you can enforce strict ad-blocking DNS via:
  ```bash
  TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'settings put global private_dns_specifier dns.adguard-dns.com'
  TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'settings put global private_dns_mode hostname'
  ```
- To revert back if network connectivity drops:
  ```bash
  TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'settings put global private_dns_mode opportunistic'
  ```

---

## 11. Developer Guide: Architecture & Adding New Features

For future pair-programming or agent-driven sessions, follow these architecture patterns and constraints:

### Codebase Organization (`launcher/app/src/main/java/com/gathin/tvhome/`)

| File / Component | Responsibility |
|---|---|
| [`MainActivity.java`](launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java) | Root activity coordinator, lifecycle manager, DPAD key dispatching, idle timers (dock auto-hide, screen off), top bar views, and hero text transitions. |
| [`Config.java`](launcher/app/src/main/java/com/gathin/tvhome/Config.java) | Dual-source configuration engine. Merges compiled defaults from `res/values/config.xml` with dynamic runtime theme overlays from `/sdcard/Android/data/com.gathin.tvhome/files/config.json`. |
| [`WallpaperShuffleManager.java`](launcher/app/src/main/java/com/gathin/tvhome/WallpaperShuffleManager.java) | Background photo engine. Scales photos to fit the display (`ScaleType.FIT_CENTER`), decodes `RGB_565` bitmaps on a worker thread with 4K detection, performs crossfades, handles EXIF orientation and timestamp captions, and pauses completely when Home is in the background. |
| [`WeatherManager.java`](launcher/app/src/main/java/com/gathin/tvhome/WeatherManager.java) | Ultra-low-power weather pill. Queries `wttr.in`, caches responses in `SharedPreferences` for 45 minutes, handles manual click refreshes, and runs zero background processes. |
| [`DockDecoration.java`](launcher/app/src/main/java/com/gathin/tvhome/DockDecoration.java) | Custom `RecyclerView.ItemDecoration` rendering the translucent frosted glass dock band behind pinned apps. |
| [`AppAdapter.java`](launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java) | TV Leanback banner loader, focus scale animations, dynamic app launch intent construction. |
| [`AudioSystemEnforcer.java`](launcher/app/src/main/java/com/gathin/tvhome/AudioSystemEnforcer.java) | Connects to Sony's `BraviaSyncService` via IPC (`MSG_CHANGE_PEER_AUDIO_MODE`) to switch audio output to the external sound bar (mode 1) with 3.5s/7s waking delays. |
| [`AudioSyncReceiver.java`](launcher/app/src/main/java/com/gathin/tvhome/AudioSyncReceiver.java) | Broadcast receiver for boot and explicit audio sync intent triggers. |
| [`InputKeyService.java`](launcher/app/src/main/java/com/gathin/tvhome/InputKeyService.java) | Accessibility service that monitors remote hotkeys and screen wake events across third-party apps. |

### The 4 Golden Rules for the Sony Bravia (MT5891 / Low RAM)

1. **Zero Background CPU & Battery Draw:**
   - Any background animation, runnable, or handler must be paused or removed in `onPause()` / `onDestroy()`.
   - Never register persistent background polling, alarms, or wake locks.
2. **RAM Conservation (~1 GB Total Device RAM):**
   - Always decode images with `Bitmap.Config.RGB_565` (2 bytes/pixel instead of 4).
   - Downsample bitmaps to 1080p (`1920×1080`) before loading into memory.
   - Explicitly recycle bitmaps (`bitmap.recycle()`) when discarding replaced wallpapers.
   - Launcher process RSS must remain under ~40 MB.
3. **Non-Blocking UI Thread:**
   - Never perform network requests, disk file scanning, EXIF parsing, or IPC binding directly on the main thread.
   - Use dedicated background worker threads (`Executors.newSingleThreadExecutor()`) and dispatch UI changes via `activity.runOnUiThread()`.
4. **Resilient Defaults & Safe Overlays:**
   - Every feature must have a sensible fallback defined in `res/values/config.xml`.
   - Malformed `config.json` overlays, missing files, or network dropouts must fail silently without crashing the launcher.

### Checklist: How to Add a New Feature

When adding a new setting or feature:
1. **Define Default in `config.xml`:**
   Add attribute to `launcher/app/src/main/res/values/config.xml` with appropriate type (`<bool>`, `<integer>`, `<string>`).
2. **Expose in `Config.java`:**
   - Add field declaration: `final <type> myFeature;`
   - Add parameter to `Config(...)` constructor and assign `this.myFeature = myFeature;`
   - In `Config.load(Context ctx)`, read default from `res.get...()` and override from `json` if present.
3. **Implement Feature Logic:**
   - Implement either inside `MainActivity.java` or in a modular manager class (e.g. `MyFeatureManager.java`).
   - Tie into `onResume()`, `onPause()`, `onDestroy()`, and `dispatchKeyEvent()` as necessary.
4. **Document Options:**
   - Add parameter to `themes/README.md` key table.
   - Update `README_DEVICE.md` with instructions.
5. **Build, Deploy & Verify:**
   ```bash
   TV_HOST=192.168.1.40 INPUT_KEY_SERVICE=1 launcher/build-and-install.sh
   TV_HOST=192.168.1.40 ./tools/venv/bin/python3 tools/tv.py 'screencap -p /sdcard/screen.png' && TV_HOST=192.168.1.40 ./tools/venv/bin/python3 tools/tv.py --pull /sdcard/screen.png current_screen.png
   ```

---

## 12. Verification Artifacts & Screenshots

All screenshots taken directly from your Sony TV during installation, audio testing, and wallpaper verification are stored in `backup/` and the root workspace:

- `stock-home.png` — Original stock home screen with sponsored rows and recommendations.
- `batch1.png` to `batch4.png` — Progressive debloating batches.
- `launcher_first_open.png` — Fresh launch of the Apple TV 4K-style native launcher.
- `current_home.png` — Active home screen showing YouTube focus, dynamic red glow, Outfit typography, and frosted pills.
- `nav_right_jellyfin.png` — D-pad navigation to Jellyfin with dynamic blue glow.
- `nav_down_grid.png` & `nav_down_scroll.png` — 4-column app grid scrolling smoothly upwards.
- `nav_up_topbar.png`, `nav_topbar_left_end.png`, `nav_topbar_right_end.png` — Sticky top bar boundary check.
- `inputs_dropdown.png` — Compact frosted Inputs menu showing HDMI 1–4, Video 1–2, and Live TV.
- `launch_yt_app.png` & `return_home_from_yt.png` — Verified launching apps and instantaneous Home button return.
- `screen_fresh1.png` — Fresh wake with family photo wallpaper and legible white UI scrim.
- `screen_fresh2.png` — Automatic idle crossfade to next family photo.
- `screen_notint.png` — Pure natural colors with ambient theme tinting removed.
- `screen_translucent_dock.png` — Translucent glass dock band with lowered position, letting photos show through.
- `screen_nav_right.png` — D-pad navigation across the translucent dock.
- `current_screen.png` — Live production screenshot showing Wellington weather pill (`☀️ 15°C`), enlarged living-room typography, and 15-second family photo shuffle.
