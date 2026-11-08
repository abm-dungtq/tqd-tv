#!/usr/bin/env zsh
set -euo pipefail

TV_SERIAL="${1:-192.168.100.4:5555}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "${ROOT_DIR}/launcher"
echo "==> [1/3] Compiling TQD-TV Launcher APK (minSdkVersion=23 for Android 6+)..."
./gradlew assembleRelease -PlauncherMinSdk=23 -q --console=plain

APK_PATH="${ROOT_DIR}/launcher/app/build/outputs/apk/release/app-release.apk"
cp "${APK_PATH}" "${ROOT_DIR}/releases/tqd-tv-launcher-android6.apk"

echo "==> [2/3] Installing APK onto ${TV_SERIAL}..."
adb -s "${TV_SERIAL}" install -r "${APK_PATH}"

echo "==> [3/3] Starting Xiaomi EventInjector (:6095) & bringing TQD-TV Home Screen to front..."
adb -s "${TV_SERIAL}" shell "am startservice -n com.duokan.airkan.tvbox/com.duokan.httpserver.service.AirkanHttpService >/dev/null 2>&1 || true; am startservice -n com.duokan.airkan.tvbox/com.duokan.remotecontroller.service.RCDaemon >/dev/null 2>&1 || true; am start -n com.gathin.tvhome/.MainActivity"
echo "✅ Done! APK updated at releases/tqd-tv-launcher-android6.apk"
