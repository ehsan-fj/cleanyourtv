#!/usr/bin/env bash
# Configure the idle dock auto-hide delay on the TV without rebuilding the APK.
#
# Usage:
#   TV_HOST=192.168.1.40 tools/set_dock_hide.sh 60        # 60 seconds (1 minute)
#   TV_HOST=192.168.1.40 tools/set_dock_hide.sh 2m        # 2 minutes
#   TV_HOST=192.168.1.40 tools/set_dock_hide.sh 0         # Disable dock auto-hide
#   TV_HOST=192.168.1.40 tools/set_dock_hide.sh --get     # Check current setting

set -euo pipefail

TV_HOST="${TV_HOST:-192.168.1.40}"
export TV_HOST

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PY="$PROJECT_ROOT/tools/venv/bin/python3"
if [ ! -x "$PY" ]; then
    PY="python3"
fi

ARG="${1:-}"

if [ "$ARG" = "--help" ] || [ "$ARG" = "-h" ]; then
    echo "Usage: $0 [seconds|minutes|0|--get]"
    echo ""
    echo "Examples:"
    echo "  $0 60      # Set to 60 seconds (1 minute)"
    echo "  $0 30      # Set to 30 seconds"
    echo "  $0 2m      # Set to 2 minutes (120 seconds)"
    echo "  $0 0       # Disable dock auto-hide"
    echo "  $0 --get   # View current dock auto-hide setting"
    exit 0
fi

# Run python helper to query or update config.json over ADB
"$PY" - <<EOF
import os, sys, json, tempfile

tools_dir = "$SCRIPT_DIR"
sys.path.insert(0, tools_dir)
import tv

PKG = "com.gathin.tvhome"
DIR = f"/sdcard/Android/data/{PKG}/files"
CONFIG_PATH = f"{DIR}/config.json"

arg = "$ARG".strip()

dev = tv.connect()

# Read current config.json from TV if it exists
raw = dev.shell(f"cat {CONFIG_PATH} 2>/dev/null").strip()
cfg = {}
if raw and not raw.startswith("cat:"):
    try:
        cfg = json.loads(raw)
    except Exception:
        cfg = {}

current_val = cfg.get("dock_hide_seconds")
if current_val is None and "dock_hide_minutes" in cfg:
    try:
        current_val = int(cfg["dock_hide_minutes"]) * 60
    except Exception:
        pass

if not arg or arg == "--get":
    if current_val is None:
        print("Dock auto-hide delay: 60 seconds (1 minute) [default from config.xml]")
    elif current_val == 0:
        print("Dock auto-hide: DISABLED (0s) [configured in config.json]")
    else:
        mins = current_val / 60.0
        if current_val % 60 == 0:
            print(f"Dock auto-hide delay: {current_val} seconds ({int(mins)} min) [configured in config.json]")
        else:
            print(f"Dock auto-hide delay: {current_val} seconds ({mins:.1f} min) [configured in config.json]")
    sys.exit(0)

# Parse requested value
val_seconds = None
if arg.endswith("m") or arg.endswith("min") or arg.endswith("mins"):
    clean_num = arg.rstrip("mins")
    try:
        val_seconds = int(float(clean_num) * 60)
    except ValueError:
        pass
elif arg.endswith("s") or arg.endswith("sec") or arg.endswith("secs"):
    clean_num = arg.rstrip("secs")
    try:
        val_seconds = int(clean_num)
    except ValueError:
        pass
else:
    try:
        val_seconds = int(arg)
    except ValueError:
        pass

if val_seconds is None or val_seconds < 0:
    print(f"Error: Invalid delay '{arg}'. Provide a non-negative number of seconds or minutes (e.g. 60, 30s, 2m, 0).", file=sys.stderr)
    sys.exit(1)

# Remove legacy/conflicting key if present
cfg.pop("dock_hide_minutes", None)
cfg["dock_hide_seconds"] = val_seconds

# Ensure remote directory exists
dev.shell(f"mkdir -p {DIR}")

# Write to temp file and push
with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
    json.dump(cfg, f, indent=2)
    temp_path = f.name

try:
    dev.push(temp_path, CONFIG_PATH, transport_timeout_s=30.0, read_timeout_s=30.0)
finally:
    if os.path.exists(temp_path):
        os.remove(temp_path)

# Restart Home activity
dev.shell(f"am force-stop {PKG}; am start -n {PKG}/.MainActivity")

if val_seconds == 0:
    print("Dock auto-hide has been DISABLED (dock will stay visible continuously).")
elif val_seconds % 60 == 0:
    print(f"Dock auto-hide delay set to {val_seconds} seconds ({val_seconds // 60} minute{'s' if val_seconds != 60 else ''}) and applied to TV.")
else:
    print(f"Dock auto-hide delay set to {val_seconds} seconds and applied to TV.")
EOF
