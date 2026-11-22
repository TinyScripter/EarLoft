#!/usr/bin/env bash
# Puts the demo library (built by build.py) onto the phone emulator and starts the podcast feed server.
#
# Usage: install.sh [--wipe]
#   --wipe  first clears the debug app's data and deletes everything in Music/, Audiobooks/ and Podcasts/ on the
#           device (internal storage and SD card). Only for emulators: there is no undo.
#
# Environment: PHONE_SERIAL (default emulator-5554), DEMO_DIR (default build/demo-library), DEMO_PORT (8765).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PHONE="${PHONE_SERIAL:-emulator-5554}"
DEMO="${DEMO_DIR:-$REPO/build/demo-library}"
PORT="${DEMO_PORT:-8765}"
PKG="de.kleinabersmart.earloft.debug"

[[ -d "$DEMO/device" && -d "$DEMO/feeds" ]] || { echo "Run tools/demo-library/build.py first" >&2; exit 1; }
case "$(adb -s "$PHONE" emu avd name 2>/dev/null | head -1 | tr -d '\r')" in
  "") echo "$PHONE is not an emulator; refusing to touch it" >&2; exit 1 ;;
esac

if [[ "${1:-}" == "--wipe" ]]; then
  adb -s "$PHONE" shell pm clear "$PKG"
  adb -s "$PHONE" shell 'for root in /sdcard /storage/????-????; do
      for dir in Music Audiobooks Podcasts; do [ -d "$root/$dir" ] && find "$root/$dir" -mindepth 1 -maxdepth 1 ! -name ".*" -exec rm -rf {} +; done
    done'
fi

adb -s "$PHONE" push "$DEMO/device/Music/." /sdcard/Music/ > /dev/null
adb -s "$PHONE" push "$DEMO/device/Audiobooks/." /sdcard/Audiobooks/ > /dev/null
adb -s "$PHONE" push "$DEMO/feeds/demo-podcasts.opml" /sdcard/Download/demo-podcasts.opml > /dev/null
adb -s "$PHONE" shell content call --uri content://media/ --method scan_volume --arg external_primary > /dev/null
echo "Pushed music, audiobooks and Download/demo-podcasts.opml to $PHONE"

if ! curl -sf "http://127.0.0.1:$PORT/demo-podcasts.opml" > /dev/null; then
  setsid nohup python3 "$REPO/tools/demo-library/serve.py" "$DEMO" "$PORT" > /tmp/demo-feed-server.log 2>&1 < /dev/null &
  disown
  sleep 1
fi
adb -s "$PHONE" reverse "tcp:$PORT" "tcp:$PORT" > /dev/null
echo "Feeds: http://127.0.0.1:$PORT/<slug>/feed.xml (server log /tmp/demo-feed-server.log)"
