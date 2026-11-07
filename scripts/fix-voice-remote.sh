#!/usr/bin/env bash
# Script nạp bản vá sửa lỗi "nói không nhận" trên điều khiển giọng nói Bluetooth Xiaomi Mi TV 4A 49" (matrix)
set -euo pipefail

cd "$(dirname "$0")"
PY="tools/venv/bin/python"

if [[ -z "${TV_HOST:-}" ]]; then
  echo "=== Đang tự động dò địa chỉ IP hiện tại của Tivi Xiaomi trên mạng LAN (192.168.100.x) ==="
  DETECTED_IP=$("$PY" -I -c '
import socket, concurrent.futures
def probe(ip):
    for port in (5555, 8686):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.35)
        try:
            s.connect((ip, port))
            s.close()
            return ip
        except Exception:
            pass
    return None
with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
    for r in ex.map(probe, [f"192.168.100.{i}" for i in range(2, 255)]):
        if r:
            print(r)
            break
')
  export TV_HOST="${DETECTED_IP:-192.168.100.4}"
fi

echo "=== 1. Kiểm tra kết nối ADB tới Tivi ($TV_HOST) ==="
"$PY" -I tools/tv.py 'echo "ADB_CONNECTED"'

echo "=== 2. Đẩy và cài đặt bản vá CleanYourTV Launcher (tvhome-sdk23.apk) ==="
"$PY" -I tools/b64push.py "work/apks/tvhome-sdk23.apk" "/data/local/tmp/tvhome-sdk23.apk"
"$PY" -I tools/tv.py "pm install -r /data/local/tmp/tvhome-sdk23.apk && rm -f /data/local/tmp/tvhome-sdk23.apk"

echo "=== 3. Cấu hình lại dịch vụ Accessibility & Google Voice Search (Katniss) ==="
"$PY" -I tools/tv.py "
  pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS &&
  pm grant com.google.android.katniss android.permission.RECORD_AUDIO &&
  settings put secure voice_recognition_service com.google.android.katniss/.search.serviceapi.KatnissRecognitionService &&
  settings put global mitv_voice_rc_available 1 &&
  settings put secure enabled_accessibility_services '' &&
  settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService:com.gathin.tvhome/.InputKeyService &&
  settings put secure accessibility_enabled 1 &&
  am startservice -n com.google.android.katniss/.search.serviceapi.KatnissRecognitionService &&
  am startservice -n com.xiaomi.mitv.remotecontroller.service/.voice.VoiceServer
"

echo "=== 4. Hoàn tất! ==="
echo "Cách dùng: Bấm 1 lần (hoặc 2 lần) nút Mic để mở thanh Google Voice Search, sau đó NHẤN GIỮ nút Mic và nói."
