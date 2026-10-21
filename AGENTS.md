# CleanYourTV — agent notes

Read this before changing anything. It is a map of the repo, not a replacement for the docs.

## What this project is

Two halves that work together:

1. **Debloat** Android TV / Google TV over ADB: back up, then `pm disable-user` the vendor's
   telemetry, ad and content packages. Nothing is uninstalled, so `tools/restore.sh` can undo it.
2. **Replace Home** with a small native launcher (`launcher/`) in an Apple TV 4K style: dock,
   app grid, glass highlight, ambient glow, pills, screensaver.

## Git setup

This checkout is the personal fork **`ehsan-fj/cleanyourtv`**:

- `origin` = `https://github.com/ehsan-fj/cleanyourtv.git` (the fork)
- `upstream` = `https://github.com/Gathin23/cleanyourtv.git`
- Working branch: **`sony-kd49x8000d`**, based on upstream commit `6bcd649` (launcher 2.3)
- `main` in this clone tracks the fork and is far ahead (launcher 2.4+). Merging it into this branch
  will conflict with the local changes; do that only when asked.

**All device-specific work is uncommitted on purpose.** Do not commit, stash, amend or push unless the
user explicitly asks. Treat the dirty working tree as the user's work in progress and never revert or
reformat it.

## Current local device

- **Sony KD-49X8000D** (`BRAVIA 4K GB`, Android TV 9 / API 28), IP `192.168.1.40`.
- Device guide, restore commands, audio-sync and wallpaper details: **`README_DEVICE.md`**.
- TV was **not reachable** on the network during the last setup check. If `TV_HOST` is unset or the
  TV is off, ask for the IP / wait for the TV; do not guess.
- Local WIP features: Sony Bravia Sync audio switching (`AudioSystemEnforcer`, `AudioSyncReceiver`),
  wallpaper shuffle + idle dock auto-hide (`WallpaperShuffleManager`, `DockDecoration`), weather pill
  (`WeatherManager`), larger typography, theme overlay additions.

## Layout

| Path | What lives there |
|---|---|
| `launcher/` | Android app, plain Java, package `com.gathin.tvhome` |
| `launcher/app/src/main/res/values/config.xml` | Per-TV configuration defaults |
| `launcher/app/src/main/java/com/gathin/tvhome/` | `MainActivity` (lifecycle, D-pad, layout), `Config` (config.xml + JSON overlay), adapters/decorations, managers |
| `tools/` | Pure-Python ADB client (`tv.py`), base64 push (`b64push.py`), disable/restore/themes/wallpapers, PowerShell twins |
| `themes/` | Runtime `config.json` overlays + optional `wallpaper.jpg`; keys documented in `themes/README.md` |
| `examples/` | Per-model debloat lists; add a row to `examples/README.md` |
| `tests/` | Python tests for examples, debloat paths, wallpaper scaling |
| `docs/` | Specs (`specs/card.md`), module cards, troubleshooting, images |
| `backup/` | **Gitignored** local dumps, screenshots and `restore_backup.sh` for this TV |
| `family_photo/` | Untracked local photo collection used by `tools/push_wallpapers.sh` |

## Commands

Python tooling (the venv already exists at `tools/venv`):

```bash
# one-time, if the venv is missing
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt

# tests (CI runs exactly this)
python3 -m unittest discover -s tests -v

# talk to the TV (TV_HOST required)
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py --probe
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py 'pm list packages'
```

Launcher (JDK 17, Android SDK platform 34 / build-tools 34.0.0):

```bash
cd launcher && ./gradlew testReleaseUnitTest assembleRelease

# install + set Home; start it before disabling the stock launcher
TV_HOST=192.168.1.40 INPUT_KEY_SERVICE=1 launcher/build-and-install.sh
```

Device operations:

```bash
TV_HOST=192.168.1.40 tools/disable.sh examples/<model>/debloat.txt
TV_HOST=192.168.1.40 tools/restore.sh examples/<model>/debloat.txt
TV_HOST=192.168.1.40 tools/theme.sh themes/aurora      # --reset to remove
TV_HOST=192.168.1.40 tools/push_wallpapers.sh          # family_photo/ by default, --clean to replace
TV_HOST=192.168.1.40 tools/set_dock_hide.sh 60         # --get, 0 disables
TV_HOST=192.168.1.40 backup/restore_backup.sh          # this TV back to stock
```

Screenshot loop used for verification:

```bash
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py \
  'input keyevent KEYCODE_DPAD_DOWN; screencap -p /sdcard/s.png'
TV_HOST=192.168.1.40 tools/venv/bin/python -I tools/tv.py --pull /sdcard/s.png s.png
```

## Hard constraints

**Launcher**

- Plain Java only. No Kotlin, no Compose. Dependencies stay at AndroidX RecyclerView + DynamicAnimation
  (JUnit is test-only). No new dependency without an issue first.
- Targets 1 GB-RAM TVs: no blur/video backgrounds, no polling, no background services, no wake locks.
  Decode bitmaps as `RGB_565`, downsample, recycle, do I/O and EXIF off the main thread, and stop all
  timers/threads in `onPause()`. Keep the process well under ~40 MB RSS.
- `config.xml` holds every per-TV value and a safe default. `Config.java` merges the optional
  `config.json` overlay; malformed/missing/unknown keys must fail silently. Document new keys in
  `themes/README.md`.
- D-pad navigation must stay deterministic from every edge. Verify on the real TV, not only an emulator.
- Release APK must stay small; CI fails above **400 KB**.

**Debloat / device work**

- **Never uninstall** system packages. Only `pm disable-user --user 0`, and only after checking what a
  package actually does (`dumpsys package`).
- Back up first, and keep `tools/restore.sh` able to undo everything.
- Never disable load-bearing pieces: TV/tuner app, input services, remote/Bluetooth pairing, keyboard,
  Play Store/Services. Ask before touching streaming apps, voice assistant, Chromecast, updates.
- Run one `pm` command per shell call (or use `tv.py --batch`); chained `pm` commands may stop early.
- The stock launcher is disabled **after** the replacement is installed, started and verified.

## Environment on this Mac

- JDK 17: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` (not the system Java).
- Android SDK: `/opt/homebrew/share/android-commandlinetools` (already in `launcher/local.properties`).
- `tools/venv` uses Python 3.14 and now has `Pillow` installed. Pillow is needed by
  `push_wallpapers.sh` and `tests/test_wallpapers.py` but is not listed in `tools/requirements.txt`,
  so a fresh venv will need it added.
- `backup/`, `family_photo/`, screenshots, `tools/venv/`, `tools/adbkey/` and builds are gitignored or
  untracked. Do not commit device dumps, serials or family photos.
- macOS may block the stock `adb` binary on the local network; `tools/tv.py` avoids it on purpose.

## Docs worth opening

- `CONTRIBUTING.md` — accepted changes and PR expectations.
- `README.md` — user-facing setup, supported TVs, manual commands.
- `README_DEVICE.md` — this Sony TV's setup, restore, audio sync, wallpapers, feature cookbook.
- `themes/README.md` — every runtime config key.
- `docs/TROUBLESHOOTING.md` — ADB/Home/screenshot failure modes already solved.
- `PROMPT.md` — the brand-neutral workflow the project ships.
