#!/usr/bin/env bash
# Script khôi phục toàn bộ hoặc từng phần cho Tivi Xiaomi Mi TV 4A 49" (matrix - Android 6.0.1)
# Cách dùng:
#   ./restore-my-tv.sh                   # Hiển thị hướng dẫn & menu khôi phục
#   ./restore-my-tv.sh launcher          # Khôi phục Launcher cũ (com.xiaomi.hometv & Leanback)
#   ./restore-my-tv.sh apks              # Cài lại toàn bộ các file APK gốc đã sao lưu trong backup/apks/
#   ./restore-my-tv.sh settings          # Khôi phục tốc độ hiệu ứng 1.0x và appops mặc định
set -euo pipefail

cd "$(dirname "$0")"
export TV_HOST="${TV_HOST:-192.168.100.4}"
PY="tools/venv/bin/python"

tv() {
  "$PY" -I tools/tv.py "$@"
}

push_and_install() {
  local apk_file="$1"
  if [ ! -f "$apk_file" ]; then
    echo "Không tìm thấy file: $apk_file"
    return 1
  fi
  echo "Đang đẩy và cài đặt lại: $(basename "$apk_file") ..."
  "$PY" -I tools/b64push.py "$apk_file" /data/local/tmp/restore.apk
  tv 'pm install -r /data/local/tmp/restore.apk; rm -f /data/local/tmp/restore.apk'
}

MODE="${1:-help}"

case "$MODE" in
  launcher)
    echo "=== Khôi phục Launcher cũ (com.xiaomi.hometv & com.amazon.tv.leanbacklauncher) ==="
    push_and_install "backup/apks/com.xiaomi.hometv.apk"
    push_and_install "backup/apks/com.amazon.tv.leanbacklauncher.apk"
    push_and_install "backup/apks/com.xiaomi.mitv.voicecontrol.apk"
    echo "=== Gỡ CleanYourTV Launcher (com.gathin.tvhome) để quay về Launcher cũ ==="
    tv 'pm uninstall com.gathin.tvhome || true'
    tv 'settings put secure enabled_accessibility_services com.xiaomi.mitv.voicecontrol/com.xiaomi.mitv.voicecontrol.VoiceAccessibilityService; settings put secure accessibility_enabled 1'
    tv 'input keyevent HOME'
    echo "Đã khôi phục Launcher cũ thành công!"
    ;;
  apks)
    echo "=== Cài đặt lại toàn bộ các gói APK gốc từ backup/apks/ ==="
    for f in backup/apks/*.apk; do
      push_and_install "$f"
    done
    echo "Đã cài lại toàn bộ APK trong backup/apks/!"
    ;;
  settings)
    echo "=== Khôi phục cài đặt hiệu ứng và appops mặc định ==="
    tv '
      settings put global window_animation_scale 1.0
      settings put global transition_animation_scale 1.0
      settings put global animator_duration_scale 1.0
      appops set com.xiaomi.mitv.tvpush.tvpushservice POST_NOTIFICATION allow 2>/dev/null || true
      appops set com.xiaomi.mitv.tvmanager POST_NOTIFICATION allow 2>/dev/null || true
      appops set com.xiaomi.mitv.tvmanager WAKE_LOCK allow 2>/dev/null || true
      appops write-settings 2>/dev/null || true
    '
    echo "Đã khôi phục cài đặt hệ thống!"
    ;;
  *)
    cat << 'EOF'
Hướng dẫn sử dụng script khôi phục Tivi Xiaomi Mi TV 4A 49" (192.168.100.4):

  1. Khôi phục về Launcher cũ:
     ./restore-my-tv.sh launcher

  2. Cài lại một ứng dụng cụ thể từ bản sao lưu (ví dụ YouTube cũ hoặc Xiaomi Voice):
     TV_HOST=192.168.100.4 tools/venv/bin/python -I tools/b64push.py backup/apks/com.google.android.youtube.tv.apk /data/local/tmp/r.apk
     TV_HOST=192.168.100.4 tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/r.apk; rm -f /data/local/tmp/r.apk'

  3. Khôi phục toàn bộ APK gốc đã gỡ:
     ./restore-my-tv.sh apks

  4. Khôi phục hiệu ứng 1.0x và quyền thông báo gốc:
     ./restore-my-tv.sh settings
EOF
    ;;
esac
