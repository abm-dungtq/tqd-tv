#!/usr/bin/env zsh
set -euo pipefail

# Cài đặt bộ ứng dụng xem phim giải trí lên Xiaomi Mi TV 4A (Android 6.0.1 - API 23, armeabi-v7a)
TV_ADDR="${1:-192.168.100.4:5555}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$(cd "${SCRIPT_DIR}/../apks" && pwd)"

echo "========================================================"
echo "  TQD-TV Movie APK Installer — Target: ${TV_ADDR}"
echo "========================================================"

adb connect "${TV_ADDR}" || true
sleep 1

if ! adb -s "${TV_ADDR}" get-state >/dev/null 2>&1; then
  echo "[!] Không kết nối được với Tivi tại ${TV_ADDR}."
  echo "    Hãy đảm bảo Tivi đang bật và kết nối chung mạng Wi-Fi/LAN."
  exit 1
fi

CORE_APKS=(
  "phim4k.apk:4K ULTRA HD (Phim4K v2.7.4)"
  "nvcphim_v7a.apk:NVC PHIM (v2.4.7)"
  "nvcmovie.apk:NVC MOVIE (v3.6.1)"
  "rapnha.apk:Rạp Nhà (v1.5.2)"
  "chophim.apk:ChợPhim - Trăm phim một chợ (v1.1.0)"
  "film4k_net.apk:Film4K.Net (v1.0.8 armeabi-v7a)"
  "rapphim.apk:RẠP PHIM (v1.1.0)"
  "sgphim.apk:Sài Gòn Phim (v1.7)"
  "cobephimtv.apk:Rổ Phim Cô Be (v1.0.0)"
  "ximaofilm.apk:Ximao Film (v1.0.1)"
)

INSTALLED=0
FAILED=0

for entry in "${CORE_APKS[@]}"; do
  apk_file="${entry%%:*}"
  apk_label="${entry#*:}"
  full_path="${APK_DIR}/${apk_file}"

  if [[ ! -f "${full_path}" ]]; then
    echo "[-] Bỏ qua ${apk_label}: không tìm thấy ${full_path}"
    continue
  fi

  echo ""
  echo "[*] Đang cài đặt: ${apk_label} (${apk_file})..."
  if adb -s "${TV_ADDR}" install -r -d "${full_path}"; then
    echo "[+] Thành công: ${apk_label}"
    INSTALLED=$((INSTALLED + 1))
  else
    echo "[!] Lỗi khi cài: ${apk_label}"
    FAILED=$((FAILED + 1))
  fi
done

echo ""
echo "========================================================"
echo "  Hoàn tất! Đã cài thành công: ${INSTALLED} ứng dụng (Lỗi: ${FAILED})"
echo "  Bấm nút Xuống trên điều khiển Tivi để mở ngăn ứng dụng."
echo "========================================================"
