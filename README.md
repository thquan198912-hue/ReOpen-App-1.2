# Game Watchdog

Ứng dụng Android giám sát nhiều app cùng lúc. Khi một app bị thoát hoặc văng, app tự mở lại đúng app đó, chờ tải xong, rồi chạy macro (chạm/vuốt) riêng của app đó.

## Cách hoạt động
- Dịch vụ trợ năng (AccessibilityService) đọc danh sách cửa sổ đang hiển thị để biết app nào còn trên màn hình (hỗ trợ chia đôi màn hình, cửa sổ nổi).
- App nào vắng mặt quá N giây → mở lại bằng launch intent, chờ tải, rồi chạy macro của chính app đó.
- Nhiều app cùng lỗi: các macro chạy lần lượt, không chồng nhau.
- Toạ độ lưu theo % màn hình nên không phụ thuộc độ phân giải.

## Cài đặt trên điện thoại
1. Cài APK (cho phép cài từ nguồn ngoài).
2. Mở app → **Bật dịch vụ trợ năng** → bật *Game Watchdog*.
   - Android 13+ nếu nút bị mờ: **Mở thông tin ứng dụng** → ⋮ → *Cho phép cài đặt bị hạn chế*, rồi bật lại.
   - Sau khi cập nhật bản mới: tắt rồi bật lại dịch vụ.
3. **＋ Thêm app** cho từng app cần giám sát.
4. Ghi macro cho từng app bằng nút nổi (xem dưới), bấm **Thử** để kiểm tra.
5. Bật công tắc **Bật giám sát** và bấm **Tắt tối ưu pin**.

## Nút nổi
- `≡` kéo để di chuyển, chạm để thu gọn thành chấm nhỏ
- `🟢/⚪` bật / tắt giám sát
- `[tên app]` chạm để đổi app đang chọn
- `⏺` ghi macro cho app đang chọn, `⏹` kết thúc và lưu (ghi mới thay macro cũ của app đó)
- `▶` chạy macro của app đang chọn ngay trên màn hình hiện tại (đang chạy thì thành `🛑`, bấm để dừng)

Khi ghi, thao tác của bạn vẫn được chuyển vào app như bình thường và được lưu cả thời gian chờ giữa các bước.

## Số lần chạy macro
Bấm **Macro** của từng app để đặt *Số lần chạy macro* (1–9999) và *Nghỉ giữa các lần chạy (giây)*. Mặc định chạy 1 lần.

## Số lần chạy macro
Trong nút **Macro** của từng app: *Số lần chạy macro* (1–9999) và *Nghỉ giữa các lần (giây)*. Nút nổi `▶` đổi thành `🛑` khi macro đang chạy, bấm để dừng.

## Chống vòng lặp
Nếu một app bị tự mở lại quá 3 lần trong 5 phút, app tự tắt giám sát app đó và ghi lý do vào nhật ký (thường do không nhận ra app đang chạy).

## Định dạng macro (có thể sửa tay trong nút "Macro")
```
tap,x,y,chờ_ms,giữ_ms
swipe,x1,y1,x2,y2,chờ_ms,thời_gian_vuốt_ms
```
x, y là % màn hình. Dạng cũ `x,y,chờ_ms` vẫn dùng được.

## Build APK
Đẩy code lên GitHub nhánh `main`. Workflow `.github/workflows/build.yml` tự build và đưa `app-debug.apk` vào:
- **Releases → Bản build mới nhất** (tải thẳng trên điện thoại)
- hoặc tab **Actions → artifact `GameWatchdog-apk`**

## Giới hạn
- Nếu một app bị tự mở lại 3 lần trong 5 phút, giám sát app đó tự tắt và ghi lý do vào nhật ký (thường do không nhận ra app đang chạy).
- Không phân biệt được app *văng* hay người dùng *chủ động thoát*: khi bật giám sát, rời app quá N giây là bị mở lại. Tắt công tắc khi muốn dùng máy bình thường.
- Khi mở lại một app trong lúc app khác đang chia đôi màn hình, tuỳ hãng máy mà bố cục có thể bị đổi; macro ghi theo vị trí trên màn hình nên cần giữ cùng bố cục lúc ghi và lúc chạy.
- Một số app có cửa sổ không đọc được tên; khi đó app dùng sự kiện chuyển cửa sổ nên chỉ nhận biết chính xác với app đang ở trên cùng.
- App có anti-cheat có thể phát hiện dịch vụ trợ năng/auto-click. Hãy đọc điều khoản và thử trên tài khoản phụ.
