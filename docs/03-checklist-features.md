# Checklist tính năng chi tiết

Ký hiệu: **P0** bắt buộc · **P1** nên có · **P2** mở rộng.

## 1. Hạ tầng & nền tảng
- [x] P0 Skeleton Spring Boot 3 / Java 21, Maven wrapper
- [x] P0 Dockerfile multi-stage, `docker-compose.yml` (app + postgres + healthcheck)
- [x] P0 Flyway migration chạy khi khởi động
- [x] P0 Cấu hình qua env (`application.yml` + `.env.example`)
- [x] P0 Swagger UI `/swagger-ui.html`
- [x] P1 Actuator health, request-id trong log
- [x] P1 Profile `demo` seed dữ liệu

## 2. Xác thực & phân quyền
- [x] P0 `POST /auth/login` → JWT
- [x] P0 `GET /auth/me`
- [x] P0 Seed admin mặc định từ env
- [x] P0 BCrypt hash mật khẩu
- [x] P0 Phân quyền: endpoint ADMIN trả 403 với USER
- [x] P0 Token hết hạn/sai → 401 chuẩn problem+json
- [x] P1 ADMIN tạo/sửa/khóa user, reset mật khẩu
- [x] P1 User đổi mật khẩu
- [ ] P2 Refresh token, rate-limit login

## 3. Quản lý phòng
- [x] P0 ADMIN tạo phòng (name unique, capacity > 0, vị trí, thiết bị, mô tả)
- [x] P0 Xem danh sách phòng (phân trang)
- [x] P0 Lọc theo capacity tối thiểu, thiết bị, trạng thái active
- [x] P0 Xem chi tiết phòng
- [x] P0 ADMIN cập nhật phòng
- [x] P0 ADMIN vô hiệu hóa phòng; chặn nếu còn booking tương lai (409)
- [x] P1 ADMIN kích hoạt lại phòng
- [x] P1 Phòng inactive không xuất hiện khi tìm phòng trống / không đặt được

## 4. Đặt phòng
- [x] P0 Tạo booking: room, title, start, end (ISO-8601)
- [x] P0 **Từ chối trùng lịch → 409 `BOOKING_CONFLICT`** (check ở service + exclusion constraint ở DB)
- [x] P0 Cho phép back-to-back (`[start, end)`)
- [x] P0 Quy tắc: end > start
- [x] P0 Quy tắc: không đặt trong quá khứ
- [x] P0 Quy tắc: bước 15 phút
- [x] P0 Quy tắc: thời lượng tối thiểu 15’ / tối đa 4h
- [x] P0 Quy tắc: trong giờ làm việc (08:00–20:00, múi giờ công ty), cùng ngày
- [x] P0 Quy tắc: đặt trước tối đa 90 ngày
- [x] P0 Quy tắc: phòng phải active
- [x] P0 Xem booking của tôi (lọc from/to/status, phân trang)
- [x] P0 Xem chi tiết booking (chủ hoặc ADMIN)
- [x] P0 Hủy booking (chủ hoặc ADMIN), lưu lý do, soft-cancel
- [x] P0 Không hủy được booking đã kết thúc; hủy lặp lại idempotent/409 rõ ràng
- [x] P0 Sửa booking (giờ/tiêu đề/mô tả), kiểm tra xung đột lại, loại trừ chính nó
- [x] P0 USER không sửa/hủy booking của người khác (403)
- [x] P1 ADMIN xem tất cả booking, lọc theo room/user/time
- [x] P1 Giới hạn số booking tương lai đang hoạt động / user (cấu hình)
- [x] P2 Đặt lịch lặp (hằng tuần / 2 tuần / ngày làm việc), hủy từ buổi này trở đi
- [ ] P2 Người tham dự, thông báo email
- [ ] P2 Check-in & tự giải phóng no-show

## 5. Xem lịch
- [x] P0 Lịch 1 phòng theo ngày / khoảng from–to (sắp xếp theo giờ)
- [x] P0 Lịch tất cả phòng trong 1 ngày
- [x] P0 Chỉ hiển thị booking CONFIRMED
- [x] P1 Hiển thị người đặt + tiêu đề (cân nhắc ẩn chi tiết với user khác – ghi trong README)
- [x] P2 Thêm vào lịch (.ics, tạo ở trình duyệt cho từng booking). Chưa có feed đăng ký lịch

## 6. Tìm phòng trống
- [x] P0 `GET /rooms/available?start&end` trả các phòng active không có booking chồng lấn
- [x] P0 Lọc `capacity` (≥) và `equipment`
- [x] P0 Áp dụng cùng validate thời gian như đặt phòng
- [x] P1 `GET /rooms/{id}/free-slots?date=` liệt kê khoảng trống trong giờ làm việc
- [x] P1 Sắp xếp kết quả theo độ phù hợp (capacity nhỏ nhất đủ chỗ)

## 7. Xử lý lỗi & validation
- [x] P0 Bean Validation cho mọi request
- [x] P0 `@RestControllerAdvice` → RFC 7807 với `code`, `detail`, `errors[]`
- [x] P0 Đúng mã: 400/401/403/404/409/422
- [x] P0 Không lộ stack trace

## 8. Kiểm thử
- [x] P0 Unit test rule validator & overlap
- [x] P0 Integration test (Testcontainers) cho auth, room, booking, availability
- [x] P0 **Concurrency test**: N request cùng slot → đúng 1 thành công
- [x] P0 Test phân quyền (USER vs ADMIN, chủ sở hữu)
- [x] P1 Test biên: back-to-back, đúng ranh giờ làm việc, phòng inactive
- [x] P1 CI (GitHub Actions) chạy build + test

## 9. Tài liệu
- [x] P0 README: chạy, thiết kế, quyết định + lý do, giả định, hạn chế
- [x] P0 Sơ đồ ERD & luồng đặt phòng (mermaid trong README)
- [x] P0 Ví dụ curl cho các luồng chính
- [x] P1 Postman/HTTP collection (`docs/requests.http`)

## 10. Frontend (bổ sung sau kế hoạch ban đầu)
- [x] P0 Đăng nhập, đăng xuất, tự đăng xuất khi token hết hạn, route theo vai trò
- [x] P0 Timeline lịch theo ngày, bấm ô trống để đặt, màu "của tôi / người khác / đã qua", vạch "bây giờ"
- [x] P0 Hộp thoại đặt/sửa phòng: giờ theo quy tắc từ `/config`, cảnh báo phòng bận trước khi gửi, gợi ý giờ trống khi xung đột
- [x] P0 Tìm phòng trống (ngày, giờ, số chỗ, thiết bị) → đặt ngay
- [x] P0 Booking của tôi (sắp tới / đã qua / đã hủy), hủy kèm lý do
- [x] P0 Admin: phòng (thêm/sửa/vô hiệu/kích hoạt), người dùng (tạo/sửa/khóa/đặt lại mật khẩu), tất cả booking (lọc, type-ahead người dùng)
- [x] P0 Trạng thái loading (skeleton), rỗng, lỗi (có Thử lại), toast, xác nhận hành động nguy hiểm
- [x] P0 Responsive (thanh điều hướng dưới trên mobile), dark mode, focus rõ ràng, nhãn form truy cập được
- [x] P1 E2E giao diện bằng Playwright trong `e2e/` (chạy tay trên stack đang chạy; chưa đưa vào CI)

## 11. Bổ sung sau vòng review
- [x] P0 Số người tham dự bắt buộc, không vượt sức chứa (`OVER_CAPACITY`)
- [x] P0 Đổi phòng khi sửa booking (kiểm tra xung đột/sức chứa ở phòng mới)
- [x] P0 Chặn phòng theo khoảng thời gian (admin), tùy chọn hủy booking trùng; hiển thị trên lịch; gỡ chặn
- [x] P0 Trang Tài khoản: đổi mật khẩu
- [x] P0 Giao diện tiếng Anh (mặc định) và tiếng Việt, lưu lựa chọn, lỗi server dịch theo mã
- [ ] Không làm (đã loại): SSO, email/thông báo, mời người tham dự/khách, mọi tích hợp dịch vụ ngoài
- [ ] P2 Báo cáo sử dụng, audit log, no-show/check-in (chưa làm)
- [x] P0 Họp riêng tư: che title/mô tả/người đặt/số người/chuỗi lặp ở server; chủ và admin thấy đủ; có test không rò rỉ
