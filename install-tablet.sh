#!/bin/zsh
# ==============================================================================
# TQD Tablet Guard — Cài đặt & Cấp quyền tự động qua ADB (USB hoặc Wi-Fi)
# Hỗ trợ: Mọi máy tính bảng Android (Android 6.0 -> Android 14+)
# ==============================================================================
set -e

SCRIPT_DIR="${0:A:h}"
APK_PATH="${SCRIPT_DIR}/releases/tqd-tablet-guard.apk"
ADB_BIN="/Users/dungtq/AGY/cleanyourtv/work/toolchain/sdk/platform-tools/adb"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi

if [[ -z "$ADB_BIN" ]]; then
  echo "❌ Không tìm thấy công cụ adb."
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "❌ Không tìm thấy file APK tại: $APK_PATH"
  exit 1
fi

TARGET_SERIAL="${1:-}"

if [[ -z "$TARGET_SERIAL" ]]; then
  # Tự động tìm thiết bị Android không phải Tivi 192.168.100.4:5555
  TARGET_SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" && $1!="192.168.100.4:5555" {print $1; exit}')"
fi

if [[ -z "$TARGET_SERIAL" ]]; then
  echo "⚠️ Chưa phát hiện Máy Tính Bảng qua ADB."
  echo "👉 Cách 1 (Không cần cáp USB): Mở trình duyệt Chrome trên Máy Tính Bảng và truy cập:"
  echo "   https://tv.tqd.me/tablet.apk"
  echo "   Tải file về, bấm Mở để cài đặt và bật 3 nút quyền trên màn hình ứng dụng."
  echo ""
  echo "👉 Cách 2 (Qua cáp USB hoặc ADB Wi-Fi):"
  echo "   1. Trên Máy Tính Bảng: Vào Cài đặt -> Giới thiệu về máy tính bảng -> Chạm 7 lần vào 'Số bản dựng' (Build number) hoặc 'Phiên bản MIUI/OS'."
  echo "   2. Vào Cài đặt bổ sung -> Tùy chọn nhà phát triển -> Bật 'Gỡ lỗi USB' (USB Debugging)."
  echo "   3. Cắm cáp USB có truyền dữ liệu (hoặc chạy: $0 <IP_MÁY_TÍNH_BẢNG>:5555)."
  exit 1
fi

# Nếu truyền vào địa chỉ IP chưa kết nối, tự động adb connect
if [[ "$TARGET_SERIAL" == *.* && "$("$ADB_BIN" devices | grep -c "$TARGET_SERIAL")" -eq 0 ]]; then
  if [[ "$TARGET_SERIAL" != *:* ]]; then
    TARGET_SERIAL="${TARGET_SERIAL}:5555"
  fi
  echo "🔌 Đang kết nối ADB Wi-Fi tới ${TARGET_SERIAL}..."
  "$ADB_BIN" connect "$TARGET_SERIAL"
fi

echo "✅ Đã kết nối Máy Tính Bảng: ${TARGET_SERIAL}"
MODEL="$("$ADB_BIN" -s "$TARGET_SERIAL" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VER="$("$ADB_BIN" -s "$TARGET_SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
echo "📱 Thiết bị: ${MODEL} (Android ${ANDROID_VER})"

echo "📦 Đang cài đặt TQD Tablet Guard..."
"$ADB_BIN" -s "$TARGET_SERIAL" install -r "$APK_PATH"

PKG="com.gathin.tabletguard"

echo "🔑 Đang cấp toàn bộ quyền giám sát & khóa màn hình tự động..."
# 1. Quyền WRITE_SECURE_SETTINGS & PACKAGE_USAGE_STATS
"$ADB_BIN" -s "$TARGET_SERIAL" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
"$ADB_BIN" -s "$TARGET_SERIAL" shell pm grant "$PKG" android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
"$ADB_BIN" -s "$TARGET_SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 2. AppOps cho Usage Stats & Hiển thị trên ứng dụng khác (Overlay)
"$ADB_BIN" -s "$TARGET_SERIAL" shell appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null || true
"$ADB_BIN" -s "$TARGET_SERIAL" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

# 3. Bật Notification Listener (đọc tên video YouTube/SmartTube/TikTok đang phát)
CUR_NOTIF="$("$ADB_BIN" -s "$TARGET_SERIAL" shell settings get secure enabled_notification_listeners | tr -d '\r')"
NOTIF_COMP="${PKG}/${PKG}.TabletMonitorService"
if [[ "$CUR_NOTIF" == "null" || -z "$CUR_NOTIF" ]]; then
  "$ADB_BIN" -s "$TARGET_SERIAL" shell settings put secure enabled_notification_listeners "$NOTIF_COMP"
elif [[ "$CUR_NOTIF" != *"$NOTIF_COMP"* ]]; then
  "$ADB_BIN" -s "$TARGET_SERIAL" shell settings put secure enabled_notification_listeners "${CUR_NOTIF}:${NOTIF_COMP}"
fi

# 4. Bật Accessibility Service (nhận diện tức thì ứng dụng & tiêu đề video trên màn hình)
CUR_ACC="$("$ADB_BIN" -s "$TARGET_SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')"
ACC_COMP="${PKG}/${PKG}.TabletAccessibilityService"
if [[ "$CUR_ACC" == "null" || -z "$CUR_ACC" ]]; then
  "$ADB_BIN" -s "$TARGET_SERIAL" shell settings put secure enabled_accessibility_services "$ACC_COMP"
elif [[ "$CUR_ACC" != *"$ACC_COMP"* ]]; then
  "$ADB_BIN" -s "$TARGET_SERIAL" shell settings put secure enabled_accessibility_services "${CUR_ACC}:${ACC_COMP}"
fi
"$ADB_BIN" -s "$TARGET_SERIAL" shell settings put secure accessibility_enabled 1

# 5. Bỏ giới hạn tối ưu hóa pin để dịch vụ chạy bền bỉ 24/7
"$ADB_BIN" -s "$TARGET_SERIAL" shell dumpsys deviceidle whitelist "+${PKG}" >/dev/null 2>&1 || true

# 6. Khởi chạy ứng dụng và dịch vụ ngầm
"$ADB_BIN" -s "$TARGET_SERIAL" shell am start -n "${PKG}/${PKG}.MainActivity" >/dev/null 2>&1 || true

TABLET_IP="$("$ADB_BIN" -s "$TARGET_SERIAL" shell ip -4 addr show wlan0 2>/dev/null | awk '/inet / {split($2,a,"/"); print a[1]; exit}' | tr -d '\r')"

echo ""
echo "🎉 HOÀN TẤT CÀI ĐẶT TQD TABLET GUARD TRÊN MÁY TÍNH BẢNG (${MODEL})!"
echo "🌐 Quản lý từ xa qua Internet: https://tv.tqd.me (chọn tab '📱 Máy Tính Bảng')"
if [[ -n "$TABLET_IP" ]]; then
  echo "🏠 Quản lý qua Wi-Fi nội bộ:  http://${TABLET_IP}:8686"
fi
