#!/usr/bin/env bash
# Undoes prepare.sh and the per-app language that capture.py sets on both emulators.
set -euo pipefail
PHONE="${PHONE_SERIAL:-emulator-5554}"
WATCH="${WATCH_SERIAL:-emulator-5556}"
PKG="de.kleinabersmart.earloft.debug"
adb -s "$PHONE" shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null
adb -s "$PHONE" shell settings put global sysui_demo_allowed 0
adb -s "$PHONE" shell wm size reset
adb -s "$PHONE" shell wm density reset
adb -s "$PHONE" shell cmd locale set-app-locales "$PKG" --locales ""
adb -s "$WATCH" shell cmd locale set-app-locales "$PKG" --locales ""
