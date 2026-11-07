# Nhật Ký & Quy Trình Chi Tiết Các Bước Đã Thực Hiện (`TQD-TV`)

Tài liệu này ghi lại đầy đủ **10 bước thực thi kỹ thuật từ đầu đến cuối** khi hợp nhất 2 dự án gốc ([`vgscity/vgs-cleantv`](https://github.com/abm-dungtq/vgs-cleantv) / [`Gathin23/cleanyourtv`](https://github.com/abm-dungtq/cleanyourtv) và [`qqwersde/my-tv-signal-android6`](https://github.com/abm-dungtq/my-tv-signal-android6)) thành nền tảng **`TQD-TV`** dành cho **Xiaomi Mi TV 4A (Android 6.0.1 / API 23 - `matrix`)**.

---

## Bước 1: Kiểm Toán Thiết Bị, Sao Lưu Toàn Diện & Dọn Sạch Bloatware

1. **Kết nối ADB qua Wi-Fi (`192.168.100.4:5555`)** và trích xuất thông số phần cứng:
   - Model: **MiTV4A-43 (`matrix`)**, Android `6.0.1` (`SDK 23`), RAM `1.6 GB`, ROM trống `2.4 GB`.
2. **Sao lưu trạng thái gốc** (`pm list packages -f`, `settings list secure/global/system`) và tạo kịch bản khôi phục khẩn cấp [`scripts/restore-my-tv.sh`](../scripts/restore-my-tv.sh).
3. **Gỡ bỏ ứng dụng rác/quảng cáo ngầm của MIUI TV** nhưng **bảo toàn tuyệt đối các dịch vụ phần cứng**:
   - Giữ lại các dịch vụ hệ thống thiết yếu (Bluetooth BLE Remote, HDMI/AV TvInput, Cast/AirPlay).

---

## Bước 2: Khắc Phục Nút Giọng Nói Trên Điều Khiển Bluetooth Xiaomi (`scanCode=63`)

1. **Phát hiện đặc thù phần cứng**:
   - Điều khiển Bluetooth Xiaomi (`Vendor_2717_Product_32b8`) gửi phím Mic với mã phần cứng `scanCode=63`, nhưng `/system/usr/keylayout/Vendor_2717_Product_32b8.kl` không ánh xạ mã này nên Android nhận `keyCode=KEYCODE_UNKNOWN (0)`.
   - Đặc biệt, micro trên điều khiển BLE chỉ truyền âm thanh khi người dùng **nhấn giữ** nút Mic, và trên MIUI TV Android 6, `AccessibilityService` không nhận được sự kiện `TYPE_WINDOW_STATE_CHANGED` khi overlay `com.google.android.katniss` (Google Voice Search) bật lên.
2. **Giải pháp trong [`HomeKeyService.java`](../launcher/app/src/main/java/com/gathin/tvhome/HomeKeyService.java)**:
   - Bắt trực tiếp `scanCode == 63` bên trong `HomeKeyService.onKeyEvent()`.
   - Áp dụng cửa sổ chờ 8 giây (`!primedRecently`) độc lập để lần chạm đầu mở `android.intent.action.WEB_SEARCH` (`com.google.android.katniss`), còn khi người dùng nhấn giữ nút Mic để nói thì không gọi lại `startActivity` làm ngắt phiên thu âm `AudioRecord`.
   - Đóng gói script tự động kiểm tra và sửa lỗi tại [`scripts/fix-voice-remote.sh`](../scripts/fix-voice-remote.sh).

---

## Bước 3: Nâng Cấp SmartTube 32.71 (Chặn Quảng Cáo & SponsorBlock)

1. Cài đặt bản **SmartTube Stable `32.71` (`com.teamsmart.videomanager.tv`)** tương thích Android 6 (`armeabi-v7a`).
2. Đặt SmartTube làm ứng dụng YouTube mặc định trên cả màn hình chính Tivi và điều khiển từ xa trên điện thoại.

---

## Bước 4: Giữ ADB Luôn Bật Qua Khởi Động Lại (`Persistent ADB Keeper`)

1. Tích hợp `ParentalControlManager.ensureAdbAlwaysOn()` và `BootReceiver` trong Launcher:
   - Tự động bật lại `Settings.Global.ADB_ENABLED = 1` và `setprop service.adb.tcp.port 5555` ngay khi Tivi khởi động (`BOOT_COMPLETED`) và định kỳ mỗi 30 giây.
   - Hiển thị trạng thái `🟢 ADB: Luôn bật` trên Web Remote để có thể kiểm tra và kích hoạt từ xa bất cứ lúc nào.

---

## Bước 5: Xây Dựng Web Remote Nội Bộ (`:8686`) & Cloud 4G/5G (`https://tv.tqd.me`)

1. **Máy chủ HTTP nội bộ ([`WebRemoteServer.java`](../launcher/app/src/main/java/com/gathin/tvhome/WebRemoteServer.java))**:
   - Chạy trực tiếp trong tiến trình Launcher ở cổng `:8686` (`http://192.168.100.4:8686`).
   - Điều khiển phím bấm độ trễ cực thấp (`< 5ms`) qua Xiaomi Native EventInjector (`127.0.0.1:6095`), chỉnh âm lượng thực tế qua `AudioManager`, gõ tiếng Việt có dấu trực tiếp vào Tivi, mở nhanh YouTube & Truyền hình.
2. **Đường hầm Cloud MQTT không phụ thuộc thư viện ngoài ([`CloudTunnelClient.java`](../launcher/app/src/main/java/com/gathin/tvhome/CloudTunnelClient.java))**:
   - Kết nối TCP thuần từ Tivi tới `broker.emqx.io:1883` kèm cơ chế bắt `SocketTimeoutException` và gửi `PINGREQ` mỗi 12 giây để giữ kết nối bền bỉ 24/7.
   - Triển khai giao diện điều khiển toàn cầu tại **`https://tv.tqd.me`** trên Cloudflare Worker ([`cloudflare-worker/`](../cloudflare-worker/)) kết nối qua WebSocket Secure (`wss://broker.emqx.io:8084/mqtt`), có bảo vệ bằng mã PIN.

---

## Bước 6: Theo Dõi Con Đang Xem Gì & Quản Lý Giờ Xem Từ Xa (`Parental Control`)

1. **Giám sát thời gian thực ([`NowPlayingMonitor.java`](../launcher/app/src/main/java/com/gathin/tvhome/NowPlayingMonitor.java))**:
   - Đọc `MediaSession` kết hợp cơ sở dữ liệu lịch sử phát của SmartTube để hiển thị chính xác **tên video YouTube con đang xem, tên kênh, thời lượng và số phút đã xem** ngay trên điện thoại của bố mẹ dù đang ở ngoài mạng nhà (4G/5G).
2. **Cảnh báo & Hẹn giờ tắt Tivi ([`ParentalControlManager.java`](../launcher/app/src/main/java/com/gathin/tvhome/ParentalControlManager.java))**:
   - Gửi lời nhắn của bố mẹ hiện nổi lên màn hình Tivi (kèm âm báo nhẹ).
   - Hẹn giờ tắt sau `3 / 5 / 10 / 15 / 30 phút`, cộng thêm `5 phút`, tự động hiện đồng hồ đếm ngược 60 giây cuối, hoặc **Tắt & Khóa ngay lập tức** (chặn mở lại SmartTube cho đến khi bố mẹ bấm **Mở Khóa** trên điện thoại).
   - Lịch tự động khóa giờ đi ngủ (`21:30 → 06:30`).

---

## Bước 7: Thay Thế Thanh Kênh Truyền Hình Cũ Bằng Kệ Video YouTube Đề Xuất (`YoutubeShelf`)

1. Loại bỏ hoàn toàn dải kênh truyền hình cáp cũ (`U1 Kênh truyền hình`) không sử dụng.
2. Xây dựng [`YoutubeShelf.java`](../launcher/app/src/main/java/com/gathin/tvhome/YoutubeShelf.java):
   - Hiển thị trực tiếp các thẻ video YouTube có ảnh thumbnail lớn sắc nét ngay giữa màn hình chính Tivi.
   - Có 5 nút lọc chủ đề nhanh ngay trên Tivi và trên điện thoại: **Hoạt hình**, **Tiếng Anh**, **Khám phá**, **Nhạc bé**, **Gia đình** (hoặc nhập chủ đề tùy ý từ điện thoại để cập nhật kệ video trên Tivi).
   - Bấm vào bất kỳ video nào sẽ phát thẳng trên **SmartTube** (không quảng cáo).

---

## Bước 8: Thiết Kế Lại Giao Diện Thoáng Đãng — 5 Icon Chính & Ngăn Kéo Ứng Dụng Phím Xuống

1. **Màn hình chính thoáng đãng ([`MainActivity.java`](../launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java))**:
   - Chỉ hiển thị đúng **5 icon ứng dụng chính** trên thanh Dock trong suốt (`SmartTube`, `VTV Go`, `MKodi`, `AirPlay&UPnP`, `Aurora Store`).
   - Khi bấm phím **Xuống (`▼`)** trên điều khiển từ thanh Dock, Tivi tự động mở ngăn kéo hiển thị các ứng dụng phụ khác.
2. **Tối giản mục Mở nhanh trên Điện thoại**:
   - Thu gọn khu vực mở nhanh ứng dụng trên Web Remote chỉ còn đúng 2 nút: **▶️ YouTube (SmartTube)** và **📺 Xem Tivi (VTV Go)**.

---

## Bước 9: Bộ Ảnh Nền "Phong Cảnh Nổi Tiếng Thế Giới" Tự Đổi Theo Thời Gian

1. Tích hợp **8 bức ảnh nhiếp ảnh 16:9 HD** của các danh thắng nổi tiếng thế giới vào [`launcher/app/src/main/assets/themes/world/`](../launcher/app/src/main/assets/themes/world/) kèm [`captions.txt`](../launcher/app/src/main/assets/themes/world/captions.txt):
   - Vịnh Hạ Long (Việt Nam), Núi Phú Sĩ mùa hoa anh đào (Nhật Bản), Dãy Alps Lauterbrunnen (Thụy Sĩ), Hồ Moraine Banff (Canada), Vườn quốc gia Yosemite (Mỹ), Cầu Vasabron & Cảng Skeppsbron Stockholm (Thụy Điển), Bắc Cực Quang Abisko (Lapland).
2. Sửa lỗi tương thích Android 6 (API 23) trong [`WallpaperCaptions.java`](../launcher/app/src/main/java/com/gathin/tvhome/WallpaperCaptions.java) (thay `java.nio.file.Files.readAllLines(file.toPath())` của API 26 bằng `BufferedReader` + `FileInputStream`).
3. Xóa dòng chữ `"Điều khiển từ xa: tv.tqd.me"` ở chân màn hình Tivi và hiển thị tên địa danh thế giới tinh tế ở góc dưới bên phải.

---

## Bước 10: Tính Năng Upload Ảnh Từ Điện Thoại Làm Hình Nền Tivi (Qua Cả LAN & Cloud 4G/5G)

1. Bổ sung nút **`📷 Chọn Ảnh Từ Điện Thoại Làm Hình Nền TV`** (dùng `<label for="wpFile">` chuẩn trình duyệt iOS/Android kèm khung xem trước ảnh trực tiếp).
2. Tự động căn chỉnh ảnh tỷ lệ **16:9 HD** ngay trên trình duyệt điện thoại và truyền về Tivi:
   - Qua mạng LAN Wi-Fi nhà: gửi trực tiếp qua `POST /api/wallpaper`.
   - Qua mạng 4G/5G (`https://tv.tqd.me`): tự động nén chuẩn 16:9 (`720×405`, `<= 44KB` Base64) và chia thành **tối đa 3–4 gói (`14KB`/gói, cách nhau `320ms`)** qua `broker.emqx.io` để vượt qua giới hạn burst rate 10 gói/giây của broker công cộng, giúp đổi hình nền Tivi từ điện thoại chỉ trong **~1 giây**.
3. Nâng cấp nút **`⏭ Đổi Cảnh Kế Tiếp`** để dù đang ở ảnh tải lên từ điện thoại (`custom-photo`), chỉ cần bấm 1 chạm là Tivi quay lại xoay vòng bộ ảnh **Phong Cảnh Thế Giới (`phong-canh`)**.
