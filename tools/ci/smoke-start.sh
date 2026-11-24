#!/usr/bin/env bash
# Start test of a release APK on an emulator: install, start, poke around a little, and fail if the app crashed.
# Catches crashes at run time that a successful R8 build doesn't show (review2 Z21 B).
#
#   tools/ci/smoke-start.sh <apk> [package]
#
# An unsigned APK (CI without keystore.properties) is signed with the debug key first. Only runs on an emulator: it uninstalls
# the package before installing.
set -euo pipefail

apk="$1"
package="${2:-de.kleinabersmart.earloft}"
seconds_after_start=15
monkey_events=300

adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
    echo "Not an emulator – refusing to uninstall $package there." >&2
    exit 2
fi

echo "Waiting for the emulator to finish booting…"
boot_deadline=$((SECONDS + 600))
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
    if [ "$SECONDS" -gt "$boot_deadline" ]; then
        echo "Emulator did not boot within 10 minutes." >&2
        exit 2
    fi
    sleep 2
done

if [[ "$apk" == *-unsigned.apk ]]; then
    build_tools="$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)"
    keystore="$HOME/.android/debug.keystore"
    if [ ! -f "$keystore" ]; then
        mkdir -p "$(dirname "$keystore")"
        keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias androiddebugkey \
            -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
    fi
    signed="${apk%-unsigned.apk}-smoke.apk"
    "$build_tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android --out "$signed" "$apk"
    apk="$signed"
fi

adb uninstall "$package" >/dev/null 2>&1 || true
adb install "$apk"
adb logcat -c
adb logcat -b crash -c

crashed() {
    adb logcat -b crash -d | grep -qF "Process: $package, PID"
}

report() {
    echo "--- crash buffer"
    adb logcat -b crash -d || true
    echo "--- last app log lines"
    adb logcat -d | grep -E "AndroidRuntime|$package" | tail -80 || true
}

activity="$(adb shell cmd package resolve-activity --brief "$package" | tr -d '\r' | tail -1)"
echo "Starting $activity"
adb shell am start -W -n "$activity"
sleep "$seconds_after_start"

if crashed || [ -z "$(adb shell pidof "$package" | tr -d '\r')" ]; then
    echo "FAILED: $package did not survive its start." >&2
    report
    exit 1
fi

# A fixed seed, so a failure can be repeated; only touches and navigation inside the app.
echo "Touching around ($monkey_events events)…"
adb shell monkey -p "$package" -s 4711 --throttle 100 --pct-syskeys 0 --pct-appswitch 0 \
    --ignore-timeouts --ignore-security-exceptions --kill-process-after-error "$monkey_events" >/dev/null 2>&1 || true
sleep 3

if crashed; then
    echo "FAILED: $package crashed while being used." >&2
    report
    exit 1
fi
echo "OK: $package started and survived $monkey_events events."
