# Nhật ký tự review: câu hỏi use case → bad case → cải thiện

Sau **mỗi phần code**, tôi tự đặt câu hỏi "chuyện gì xảy ra nếu…", rồi sửa hoặc ghi lại rủi ro.
Bảng dưới là các phát hiện thực tế (không phải checklist lý thuyết). Cột **Cách phát hiện** cho biết do đọc code,
do test tự động hay do chạy thử bằng trình duyệt thật.

## Backend

| # | Câu hỏi / bad case | Hậu quả nếu bỏ qua | Cách phát hiện | Cải thiện |
|---|---|---|---|---|
| 1 | Cấu hình sai: `slot-minutes=7`, giờ làm việc kết thúc trước giờ bắt đầu, `JWT_SECRET` ngắn hoặc thiếu? | Quy tắc đặt phòng sai âm thầm, JWT yếu | Review | `@Validated` trên `AppProperties` → **app dừng ngay lúc khởi động** với thông báo rõ |
| 2 | Tầng security trả 401/403 có cùng định dạng lỗi với API không? | Client phải xử lý 2 kiểu lỗi; `code` sai | Review (hàm `problem()` nhận `code` nhưng bỏ qua) | Sửa; mọi lỗi đều `application/problem+json` + `code` + `requestId` |
| 3 | User bị khóa nhưng JWT còn hạn (60’)? | Người bị khóa vẫn đặt phòng được | Review | `BookingService.create` kiểm tra `active` từ DB (test `disabledUserWithStillValidTokenCannotBook`) |
| 4 | Mật khẩu tiếng Việt có dấu (2–3 byte/ký tự) vượt 72 byte của BCrypt? | BCrypt **cắt ngầm** → mật khẩu yếu hơn người dùng tưởng | Review | `PasswordPolicy` kiểm tra theo **byte**, trả 422 `PASSWORD_TOO_LONG` |
| 5 | `@Validated` ở class + `@Min` trên tham số? | Trả **500** thay vì 400 | Review | Bỏ `@Validated`, dùng method validation của Spring 6.1 (test `invalidParameterTypesReturn400`) |
| 6 | `?sort=passwordHash` / `?sort=equipment`? | 500 hoặc dò thông tin cột ẩn | Review | `SortGuard` whitelist theo từng endpoint → 400 `INVALID_SORT` |
| 7 | 20 người cùng đặt 1 slot? Slot gối nhau? Đóng phòng đúng lúc có người đặt? | Double booking / booking trong phòng đã đóng | Test đồng thời | Exclusion constraint + `FOR SHARE`/`FOR UPDATE` trên phòng; 3 test concurrency |
| 8 | Brute-force đăng nhập; phân biệt "sai email" và "sai mật khẩu" qua thời gian? | Dò tài khoản | Review | Throttle 5 lần/15’ theo (IP,email), so khớp BCrypt giả khi email không tồn tại, cùng một thông báo lỗi |
| 9 | Test throttle làm khóa luôn test khác | Test flaky | Test | Dùng email riêng; thêm kiểm tra "tài khoản khác cùng IP không bị ảnh hưởng" |
| 10 | Trùng lịch là chuyện bình thường nhưng Hibernate log `ERROR` kèm SQL mỗi lần | Log ngập, alert giả | Quan sát log test | Tắt `SqlExceptionHelper`; lỗi DB bất ngờ vẫn được log (stack trace) ở `GlobalExceptionHandler` |
| 11 | `pg_isready` báo sẵn sàng khi database chưa được tạo (server tạm lúc init) | `docker compose up` lần đầu **crash ngẫu nhiên** | Test (database "booking_test" does not exist) | Healthcheck chạy `select 1` trên đúng database |
| 12 | Compose của test và compose chính cùng project name + service `db` | `down -v` của test **xóa DB đang chạy** | Review | `name: daoukiwoom-test` |
| 13 | Dockerfile dùng `RUN --mount` | Máy không có BuildKit **không build được** | Chạy thật | Bỏ `--mount`; `.dockerignore` loại `frontend/`, `target/` |
| 14 | Tab "Past" dùng `to=now` thì buổi họp đang diễn ra nằm ở cả Upcoming và Past | UX gây nhầm | Review UI | Thêm tham số `endedBefore` (có test) |
| 15 | Client gửi `+07:00` không mã hóa trong query string (curl) → `+` thành khoảng trắng | 400 khó hiểu | Review | Converter chấp nhận dạng này (test `unencodedPlusInOffsetIsTolerated`) |
| 16 | Máy test thiếu RAM (exit 137) | CI cục bộ chập chờn | Chạy thật | Giới hạn heap JVM cho test |

## Frontend

| # | Câu hỏi / bad case | Hậu quả nếu bỏ qua | Cách phát hiện | Cải thiện |
|---|---|---|---|---|
| 17 | Trình duyệt gửi `Origin` kèm cổng, nginx dùng `Host $host` (mất cổng) | Spring coi là cross-origin → **403, không đăng nhập được** | Chạy bằng trình duyệt thật (curl vẫn OK) | `Host $http_host` + `X-Forwarded-*` |
| 18 | `<label>` bao quanh ô mật khẩu **và** nút "hiện mật khẩu" | Screen reader đọc "Password Show password" | Playwright (strict locator) | `Field` dùng `htmlFor` + `useId`, tự gắn `aria-invalid`/`aria-describedby` |
| 19 | Đóng modal bằng code (đổi sang hộp thoại xác nhận hủy) cũng bắn `close` → cha tưởng người dùng đóng | **Luồng hủy booking bị đứng** | Playwright | `Modal` chỉ gọi `onClose` khi người dùng đóng |
| 20 | Trang Find a room mặc định 09:00 hôm nay (đã qua) | Mở trang là thấy lỗi | Playwright (0 kết quả) | Mặc định là khung giờ kế tiếp còn trống |
| 21 | Chưa đăng nhập vẫn gọi `/config` | 401 thừa trong console | Playwright console | Chỉ gọi khi đã có user |
| 22 | Hai người đặt cùng lúc: người sau chỉ thấy "lỗi" | Phải đoán giờ khác | Thiết kế UX | Cảnh báo **trước khi gửi** ("phòng bận 15:00–16:00 – Alice") + gợi ý khung giờ trống bấm 1 chạm + link tìm phòng khác |
| 23 | Màn hình mobile ở "hôm nay": cả màn là vùng đã qua | Phải vuốt tìm giờ hiện tại | Ảnh chụp mobile | Timeline tự cuộn tới vạch "bây giờ"; độ rộng giờ co giãn theo màn hình |
| 24 | Múi giờ của máy người dùng khác múi giờ công ty | Đặt nhầm giờ | Review | Mọi phép tính giờ theo múi giờ công ty lấy từ `/config` |
| 25 | Danh sách người dùng 200+ không vừa `<select>` | Admin không lọc được | Review | `UserPicker` type-ahead |

## Rủi ro đã biết (chấp nhận có chủ đích – xem README)

- Token vẫn hợp lệ tới hết TTL sau khi **đổi mật khẩu / hạ quyền admin** (khóa tài khoản thì đã chặn ở thao tác đặt phòng). Giảm thiểu: TTL mặc định 60’. Hướng tiếp theo: `token_version` hoặc refresh token.
- Throttle đăng nhập nằm trong bộ nhớ từng instance (N instance → giới hạn thực tế N×5). Hướng tiếp theo: giới hạn ở API gateway/Redis.
- Giới hạn "tối đa 50 booking tương lai/user" là giới hạn mềm (không chống được race hai request song song); **chống trùng lịch thì là bảo đảm cứng** ở DB.
- Token JWT lưu `localStorage` (dễ bị XSS đánh cắp hơn cookie `HttpOnly`). Giảm thiểu: CSP nghiêm ngặt ở nginx, không render HTML từ người dùng. Hướng tiếp theo: cookie `HttpOnly` + CSRF token.
