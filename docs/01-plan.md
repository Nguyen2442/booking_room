# Kế hoạch triển khai – Hệ thống đặt phòng họp (Meeting Room Booking API)

## 1. Bối cảnh và mục tiêu
- ~10 phòng họp, ~200 nhân viên; hiện đặt phòng qua tin nhắn → hay trùng lịch.
- Cần hệ thống (REST API + giao diện web): quản lý phòng, đặt phòng, xem lịch, tìm phòng trống.
- 2 vai trò: **USER** (nhân viên) và **ADMIN** (quản trị).
- Yêu cầu vận hành: đúng nghiệp vụ, an toàn khi nhiều người đặt cùng lúc, có kiểm thử, triển khai bằng Docker, có tài liệu.

## 2. Công nghệ & lý do
| Hạng mục | Lựa chọn | Lý do |
|---|---|---|
| Ngôn ngữ | Java 21 (LTS) | Bản hỗ trợ dài hạn, hiệu năng và cú pháp hiện đại |
| Framework | Spring Boot 3.x (Web, Validation, Security, Data JPA, Actuator) | Hệ sinh thái trưởng thành, đội ngũ quen thuộc |
| DB | PostgreSQL 16 | Hỗ trợ **exclusion constraint** chống trùng lịch ở tầng DB |
| Migration | Flyway | Schema có version, tái lập được |
| Auth | JWT (access token) + BCrypt | Stateless, đơn giản cho 200 user |
| API docs | springdoc-openapi (Swagger UI) | Tự sinh tài liệu API |
| Test | JUnit 5, Mockito, Testcontainers (Postgres thật) | Test concurrency/constraint trên DB thật |
| Build | Maven (wrapper) | Phổ biến |
| Container | Dockerfile multi-stage + docker-compose (app + postgres) | `docker compose up` chạy toàn bộ |
| Mapping | MapStruct hoặc mapper tay | Tách entity/DTO |

## 3. Quyết định thiết kế quan trọng
1. **Chống trùng lịch ở tầng DB**: cột `time_range tstzrange` + `EXCLUDE USING gist (room_id WITH =, time_range WITH &&) WHERE (status = 'CONFIRMED')`. Đây là nguồn chân lý duy nhất, an toàn khi nhiều request đồng thời; service bắt `DataIntegrityViolationException` → `409 Conflict`. Kiểm tra trước ở service chỉ để trả lỗi thân thiện.
2. **Khoảng thời gian nửa mở `[start, end)`**: 09:00–10:00 và 10:00–11:00 **không** xung đột.
3. **Lưu thời gian UTC (`timestamptz`)**, API nhận/trả ISO-8601 có offset; múi giờ nghiệp vụ cấu hình (`Asia/Ho_Chi_Minh`) dùng cho giờ làm việc.
4. **Hủy mềm (soft cancel)**: `status = CANCELLED`, giữ lịch sử; booking đã hủy không chiếm phòng.
5. **Vô hiệu hóa phòng thay vì xóa cứng** (`active=false`); không cho vô hiệu hóa/xóa nếu còn booking tương lai (hoặc yêu cầu `force` hủy kèm lý do – chọn: trả 409 và liệt kê).
6. **Quy tắc đặt phòng cấu hình được** (`application.yml`): bước 15 phút, tối thiểu 15 phút, tối đa 4 giờ, đặt trước tối đa 90 ngày, giờ làm việc 08:00–20:00, không đặt quá khứ.
7. **Phân quyền**: USER chỉ sửa/hủy booking của mình; ADMIN hủy/sửa mọi booking, quản lý phòng & user.
8. **Lỗi chuẩn RFC 7807** (`application/problem+json`) với `code` nghiệp vụ.
9. **Phân trang** cho danh sách; **index** cho truy vấn theo room/time.
10. **Mật khẩu**: BCrypt; admin mặc định seed qua biến môi trường.

## 4. Kiến trúc
Layered, package theo feature:
```
com.company.booking
 ├─ common/        (exception, problem handler, config, time utils)
 ├─ security/      (JWT filter, SecurityConfig, UserPrincipal)
 ├─ auth/          (login, register?, me)
 ├─ user/          (entity, repo, service, controller – admin quản lý)
 ├─ room/          (entity, repo, service, controller)
 ├─ booking/       (entity, repo, service, controller, rules/validator)
 └─ availability/  (tìm phòng trống, lịch theo phòng/ngày)
```
Controller (DTO + validation) → Service (nghiệp vụ, transaction) → Repository (JPA/native query).

## 5. Mô hình dữ liệu
**users**: id (uuid), email (unique), full_name, password_hash, role (USER|ADMIN), active, created_at
**rooms**: id (uuid), name (unique), location/floor, capacity (>0), equipment (text[]/jsonb: projector, whiteboard, video…), description, active, created_at, updated_at
**bookings**: id (uuid), room_id (fk), user_id (fk), title, description, start_time, end_time (timestamptz), time_range (tstzrange, generated), status (CONFIRMED|CANCELLED), cancelled_at, cancelled_by, cancel_reason, created_at, updated_at, version
- CHECK `end_time > start_time`
- EXCLUDE gist (room_id, time_range) WHERE status='CONFIRMED'
- INDEX (room_id, start_time), (user_id, start_time)
- (Tuỳ chọn) **attendees** nếu mở rộng.

## 6. Thiết kế API (prefix `/api/v1`)
| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | /auth/login | public | Đăng nhập → JWT |
| GET | /auth/me | auth | Thông tin user hiện tại |
| GET | /rooms | auth | Danh sách phòng (lọc capacity, equipment, active; phân trang) |
| GET | /rooms/{id} | auth | Chi tiết phòng |
| POST | /rooms | ADMIN | Tạo phòng |
| PUT | /rooms/{id} | ADMIN | Cập nhật phòng |
| DELETE | /rooms/{id} | ADMIN | Vô hiệu hóa phòng |
| POST | /bookings | auth | Đặt phòng |
| GET | /bookings/{id} | chủ/ADMIN | Chi tiết |
| GET | /bookings/my | auth | Booking của tôi (lọc from/to/status) |
| GET | /bookings | ADMIN | Tất cả booking (lọc room/user/time) |
| PUT | /bookings/{id} | chủ/ADMIN | Đổi giờ/tiêu đề (kiểm tra xung đột lại) |
| POST | /bookings/{id}/cancel | chủ/ADMIN | Hủy (kèm reason) |
| GET | /rooms/{id}/schedule?date= hoặc from&to | auth | Lịch của 1 phòng |
| GET | /schedule?date= | auth | Lịch tất cả phòng trong ngày |
| GET | /rooms/available?start&end&capacity&equipment | auth | Tìm phòng trống |
| GET | /rooms/{id}/free-slots?date=&minDuration= | auth | (Mở rộng) khoảng trống trong ngày |
| POST/GET/PUT | /admin/users … | ADMIN | Quản lý người dùng |

Mã lỗi: 400 validation, 401, 403, 404, 409 `BOOKING_CONFLICT` / `ROOM_HAS_FUTURE_BOOKINGS`, 422 vi phạm quy tắc nghiệp vụ (`OUTSIDE_WORKING_HOURS`, `DURATION_TOO_LONG`, `IN_THE_PAST`…).

## 7. Lộ trình (commit nhỏ, giữ lịch sử git rõ ràng)
| Giai đoạn | Nội dung | Commit gợi ý |
|---|---|---|
| 0 | Khởi tạo repo, docs (file này + checklist) | `docs: add plan and checklists` |
| 1 | Skeleton Spring Boot, Docker, compose, Flyway, healthcheck | `chore: bootstrap project with docker compose` |
| 2 | Schema + migration (users, rooms, bookings, exclusion constraint) | `feat(db): initial schema` |
| 3 | Security: JWT, user seed, login, role | `feat(auth): jwt login and roles` |
| 4 | CRUD phòng (ADMIN) + list/filter | `feat(room): room management` |
| 5 | Đặt phòng + quy tắc + xử lý xung đột | `feat(booking): create booking with conflict protection` |
| 6 | Hủy/sửa booking, phân quyền sở hữu | `feat(booking): update and cancel` |
| 7 | Lịch + tìm phòng trống (+ free-slots) | `feat(availability): schedule and room search` |
| 8 | Xử lý lỗi RFC7807, validation, OpenAPI | `feat: unified error handling and api docs` |
| 9 | Test (unit + integration + concurrency) | `test: ...` |
| 10 | README, hoàn thiện, rà soát checklist | `docs: readme` |

## 8. Chiến lược kiểm thử
- **Unit**: rule validator (giờ làm việc, độ dài, bước 15’, quá khứ), logic overlap.
- **Integration (Testcontainers)**: toàn bộ luồng API với Postgres thật + JWT.
- **Concurrency**: 20 thread cùng đặt 1 slot → đúng 1 thành công, 19 trả 409.
- **Biên**: back-to-back booking, qua nửa đêm, DST không áp dụng (VN), phòng bị vô hiệu hóa.

## 9. Vận hành thực tế
- Config bằng biến môi trường (`DB_URL`, `JWT_SECRET`, `ADMIN_EMAIL/PASSWORD`…), không commit secret.
- Actuator `/actuator/health`, log có request-id, healthcheck trong compose, `depends_on: condition: service_healthy`.
- Dữ liệu seed demo (10 phòng, vài user) bật bằng profile `demo`.
- CORS cấu hình; rate-limit đăng nhập (mở rộng).

## 10. Phạm vi mở rộng (nếu còn thời gian)
Đặt lịch lặp (recurring), thông báo email, check-in/auto-release no-show, thêm người tham dự, export iCal, audit log, đa múi giờ.

## 11. Giả định (ghi rõ trong README)
- Mỗi phòng chỉ có 1 booking tại một thời điểm; không có phòng ghép.
- Tài khoản do ADMIN tạo (công ty nội bộ); tuỳ chọn tự đăng ký bị tắt.
- Giờ làm việc và các giới hạn áp dụng chung mọi phòng, cấu hình được.
- Booking không được xuyên ngày (bắt đầu và kết thúc cùng ngày theo múi giờ công ty).

## 12. Thay đổi so với kế hoạch ban đầu (sau khi triển khai)
- Thêm frontend React + Vite (nginx cùng origin, proxy `/api`); thêm `GET /api/v1/config` để UI đọc quy tắc đặt phòng.
- Thêm tham số `endedBefore` cho `GET /bookings[/my]`; `GET /` của API chuyển tới Swagger UI.
- Test chạy trên PostgreSQL thật qua `docker-compose.test.yml` (không dùng Testcontainers để chạy được cả khi không mount docker.sock).
- Khóa user sẽ tự hủy booking tương lai; whitelist sort; throttle đăng nhập; cổng quản trị 8081 tách riêng.
- Danh sách phát hiện/cải thiện trong quá trình tự review: `docs/04-review-log.md`.
