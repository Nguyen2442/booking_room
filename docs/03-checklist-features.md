# Checklist tính năng chi tiết

Ký hiệu: **P0** bắt buộc · **P1** nên có · **P2** mở rộng.

## 1. Hạ tầng & nền tảng
- [ ] P0 Skeleton Spring Boot 3 / Java 21, Maven wrapper
- [ ] P0 Dockerfile multi-stage, `docker-compose.yml` (app + postgres + healthcheck)
- [ ] P0 Flyway migration chạy khi khởi động
- [ ] P0 Cấu hình qua env (`application.yml` + `.env.example`)
- [ ] P0 Swagger UI `/swagger-ui.html`
- [ ] P1 Actuator health, request-id trong log
- [ ] P1 Profile `demo` seed dữ liệu

## 2. Xác thực & phân quyền
- [ ] P0 `POST /auth/login` → JWT
- [ ] P0 `GET /auth/me`
- [ ] P0 Seed admin mặc định từ env
- [ ] P0 BCrypt hash mật khẩu
- [ ] P0 Phân quyền: endpoint ADMIN trả 403 với USER
- [ ] P0 Token hết hạn/sai → 401 chuẩn problem+json
- [ ] P1 ADMIN tạo/sửa/khóa user, reset mật khẩu
- [ ] P1 User đổi mật khẩu
- [ ] P2 Refresh token, rate-limit login

## 3. Quản lý phòng
- [ ] P0 ADMIN tạo phòng (name unique, capacity > 0, vị trí, thiết bị, mô tả)
- [ ] P0 Xem danh sách phòng (phân trang)
- [ ] P0 Lọc theo capacity tối thiểu, thiết bị, trạng thái active
- [ ] P0 Xem chi tiết phòng
- [ ] P0 ADMIN cập nhật phòng
- [ ] P0 ADMIN vô hiệu hóa phòng; chặn nếu còn booking tương lai (409)
- [ ] P1 ADMIN kích hoạt lại phòng
- [ ] P1 Phòng inactive không xuất hiện khi tìm phòng trống / không đặt được

## 4. Đặt phòng
- [ ] P0 Tạo booking: room, title, start, end (ISO-8601)
- [ ] P0 **Từ chối trùng lịch → 409 `BOOKING_CONFLICT`** (check ở service + exclusion constraint ở DB)
- [ ] P0 Cho phép back-to-back (`[start, end)`)
- [ ] P0 Quy tắc: end > start
- [ ] P0 Quy tắc: không đặt trong quá khứ
- [ ] P0 Quy tắc: bước 15 phút
- [ ] P0 Quy tắc: thời lượng tối thiểu 15’ / tối đa 4h
- [ ] P0 Quy tắc: trong giờ làm việc (08:00–20:00, múi giờ công ty), cùng ngày
- [ ] P0 Quy tắc: đặt trước tối đa 90 ngày
- [ ] P0 Quy tắc: phòng phải active
- [ ] P0 Xem booking của tôi (lọc from/to/status, phân trang)
- [ ] P0 Xem chi tiết booking (chủ hoặc ADMIN)
- [ ] P0 Hủy booking (chủ hoặc ADMIN), lưu lý do, soft-cancel
- [ ] P0 Không hủy được booking đã kết thúc; hủy lặp lại idempotent/409 rõ ràng
- [ ] P0 Sửa booking (giờ/tiêu đề/mô tả), kiểm tra xung đột lại, loại trừ chính nó
- [ ] P0 USER không sửa/hủy booking của người khác (403)
- [ ] P1 ADMIN xem tất cả booking, lọc theo room/user/time
- [ ] P1 Giới hạn số booking tương lai đang hoạt động / user (cấu hình)
- [ ] P2 Đặt lịch lặp (hàng ngày/tuần), hủy cả chuỗi
- [ ] P2 Người tham dự, thông báo email
- [ ] P2 Check-in & tự giải phóng no-show

## 5. Xem lịch
- [ ] P0 Lịch 1 phòng theo ngày / khoảng from–to (sắp xếp theo giờ)
- [ ] P0 Lịch tất cả phòng trong 1 ngày
- [ ] P0 Chỉ hiển thị booking CONFIRMED
- [ ] P1 Hiển thị người đặt + tiêu đề (cân nhắc ẩn chi tiết với user khác – ghi trong README)
- [ ] P2 Export iCal

## 6. Tìm phòng trống
- [ ] P0 `GET /rooms/available?start&end` trả các phòng active không có booking chồng lấn
- [ ] P0 Lọc `capacity` (≥) và `equipment`
- [ ] P0 Áp dụng cùng validate thời gian như đặt phòng
- [ ] P1 `GET /rooms/{id}/free-slots?date=` liệt kê khoảng trống trong giờ làm việc
- [ ] P1 Sắp xếp kết quả theo độ phù hợp (capacity nhỏ nhất đủ chỗ)

## 7. Xử lý lỗi & validation
- [ ] P0 Bean Validation cho mọi request
- [ ] P0 `@RestControllerAdvice` → RFC 7807 với `code`, `detail`, `errors[]`
- [ ] P0 Đúng mã: 400/401/403/404/409/422
- [ ] P0 Không lộ stack trace

## 8. Kiểm thử
- [ ] P0 Unit test rule validator & overlap
- [ ] P0 Integration test (Testcontainers) cho auth, room, booking, availability
- [ ] P0 **Concurrency test**: N request cùng slot → đúng 1 thành công
- [ ] P0 Test phân quyền (USER vs ADMIN, chủ sở hữu)
- [ ] P1 Test biên: back-to-back, đúng ranh giờ làm việc, phòng inactive
- [ ] P1 CI (GitHub Actions) chạy build + test

## 9. Tài liệu
- [ ] P0 README: chạy, thiết kế, quyết định + lý do, giả định, hạn chế
- [ ] P0 Sơ đồ ERD & luồng đặt phòng (mermaid trong README)
- [ ] P0 Ví dụ curl cho các luồng chính
- [ ] P1 Postman/HTTP collection (`docs/requests.http`)
