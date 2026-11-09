# Cài Schedule Reminder lên Android — cách miễn phí

## Không cần Android Studio / không cần Mac

1. Giải nén `ScheduleReminder_Android.zip`, đưa toàn bộ project lên GitHub (kể cả thư mục ẩn `.github`).
2. GitHub → repository → **Actions** → **Build Schedule Reminder Android APK** → **Run workflow**. Khi có dấu ✅, nhấn workflow run, tải artifact `ScheduleReminder-Android-APK` và giải nén file `ScheduleReminder-Android.apk`.
3. Gửi APK sang điện thoại Android qua cáp, Google Drive hoặc Telegram; mở APK, chọn cho phép **Cài đặt ứng dụng không rõ nguồn gốc** nếu Android hỏi, sau đó **Cài đặt**. Khi mở ứng dụng, cấp quyền **Thông báo** và **Báo thức & lời nhắc** để nhắc giờ chính xác.

Lưu ý: Không có file APK đã build sẵn trong ZIP; GitHub Actions sẽ biên dịch và ký bản debug bằng khóa tự sinh. Các lần cài đặt bản mới có thể cần gỡ bản cũ nếu chữ ký khác nhau, gây mất dữ liệu cũ.

## Tạo bằng Windows 11 tại nhà

Cài Android Studio (SDK 35 + NDK 27.2.12479018) và Rust, sau đó chạy `BUILD_APK_WINDOWS.bat`. APK sinh ra ở ngay thư mục gốc của project. Nếu gặp lỗi, chụp toàn bộ dòng đỏ để xác định phần nào cần sửa.
