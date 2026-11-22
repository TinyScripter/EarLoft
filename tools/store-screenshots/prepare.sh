#!/usr/bin/env bash
# Prepares the phone emulator for store screenshots (`prepare.sh native` for promo.py): a 1080x1920 display (9:16; Google Play accepts at most 2:1, and
# a Pixel 10 Pro screenshot is 1280x2856) and a clean demo-mode status bar (9:41, full battery, Wi-Fi only).
set -euo pipefail
PHONE="${PHONE_SERIAL:-emulator-5554}"
# "native" keeps the display as it is: promo.py frames full-resolution shots itself.
if [[ "${1:-}" != "native" ]]; then
  adb -s "$PHONE" shell wm size 1080x1920
  adb -s "$PHONE" shell wm density 420
fi
adb -s "$PHONE" shell settings put global sysui_demo_allowed 1
demo() { adb -s "$PHONE" shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e plugged false -e level 100
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false
