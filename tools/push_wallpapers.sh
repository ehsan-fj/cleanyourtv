#!/usr/bin/env bash
# Optimize and push wallpaper photos to the Android TV launcher.
#
# Usage:
#   TV_HOST=192.168.1.40 tools/push_wallpapers.sh [photo_directory] [--clean]
#
# Defaults to pushing photos from 'family_photo/'.
# Pre-scales images to 4K max dimensions (3840x2160) with quality 85 so they decode instantly on TV.
set -euo pipefail

cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.40 $0}"

PHOTO_DIR="family_photo"
CLEAN=0

for arg in "$@"; do
  if [ "$arg" = "--clean" ]; then
    CLEAN=1
  elif [ -d "$arg" ]; then
    PHOTO_DIR="$arg"
  fi
done

if [ ! -d "$PHOTO_DIR" ]; then
  echo "ERROR: Directory '$PHOTO_DIR' does not exist." >&2
  exit 1
fi

COUNT=$(find "$PHOTO_DIR" -maxdepth 1 -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.webp" \) | wc -l | tr -d ' ')
if [ "$COUNT" -eq 0 ]; then
  echo "ERROR: No supported photos (.jpg, .jpeg, .png, .webp) found in $PHOTO_DIR." >&2
  exit 1
fi

echo "Found $COUNT photos in $PHOTO_DIR. Optimizing for 4K TV display (EXIF auto-rotate & Lanczos downsampling)..."
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

python3 -c '
import sys, os, glob
from PIL import Image, ImageOps

src_dir = sys.argv[1]
dst_dir = sys.argv[2]

for f in sorted(glob.glob(os.path.join(src_dir, "*"))):
    if not os.path.isfile(f): continue
    ext = os.path.splitext(f)[1].lower()
    if ext not in (".jpg", ".jpeg", ".png", ".webp"): continue
    name = os.path.splitext(os.path.basename(f))[0]
    try:
        im = Image.open(f)
        im = ImageOps.exif_transpose(im)
        ex = im.getexif()
        im.thumbnail((3840, 2160), Image.Resampling.LANCZOS)
        if im.mode != "RGB":
            im = im.convert("RGB")
        save_kwargs = {"quality": 85, "optimize": True}
        if ex and len(ex) > 0:
            save_kwargs["exif"] = ex
        im.save(os.path.join(dst_dir, name + ".jpg"), "JPEG", **save_kwargs)
    except Exception as e:
        print(f"Warning: could not process {f}: {e}", file=sys.stderr)
' "$PHOTO_DIR" "$TMP_DIR"

TAR_FILE="$TMP_DIR/wallpapers.tar.gz"
tar -czf "$TAR_FILE" -C "$TMP_DIR" --exclude="*.tar.gz" .
ARCHIVE_SIZE=$(ls -lh "$TAR_FILE" | awk '{print $5}')
echo "Created optimized wallpaper archive: $ARCHIVE_SIZE."

DEST_DIR="/sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
ADB_BIN="/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
[ -x "$ADB_BIN" ] || ADB_BIN="$(command -v adb || true)"

if [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; then
  echo "Transferring via ADB..."
  ADB_VENDOR_KEYS="$PWD/tools/adbkey/adbkey" "$ADB_BIN" connect "${TV_HOST}:5555" >/dev/null 2>&1 || true
  if [ "$CLEAN" -eq 1 ]; then
    "$ADB_BIN" -s "${TV_HOST}:5555" shell "rm -rf $DEST_DIR && mkdir -p $DEST_DIR"
  else
    "$ADB_BIN" -s "${TV_HOST}:5555" shell "mkdir -p $DEST_DIR"
  fi
  "$ADB_BIN" -s "${TV_HOST}:5555" push "$TAR_FILE" /data/local/tmp/wallpapers.tar.gz
  "$ADB_BIN" -s "${TV_HOST}:5555" shell "tar -xzf /data/local/tmp/wallpapers.tar.gz -C $DEST_DIR && rm -f /data/local/tmp/wallpapers.tar.gz"
else
  echo "Transferring via tv.py..."
  PY="tools/venv/bin/python"
  [ -x "$PY" ] || PY="python3"
  if [ "$CLEAN" -eq 1 ]; then
    "$PY" -I tools/tv.py "rm -rf $DEST_DIR && mkdir -p $DEST_DIR"
  else
    "$PY" -I tools/tv.py "mkdir -p $DEST_DIR"
  fi
  "$PY" -I tools/b64push.py "$TAR_FILE" /data/local/tmp/wallpapers.tar.gz
  "$PY" -I tools/tv.py "tar -xzf /data/local/tmp/wallpapers.tar.gz -C $DEST_DIR && rm -f /data/local/tmp/wallpapers.tar.gz"
fi

echo "Wallpapers successfully installed in $DEST_DIR on the TV."
