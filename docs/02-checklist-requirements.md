# Checklist sẵn sàng vận hành (Release readiness)

Hệ thống đặt phòng họp nội bộ: ~10 phòng, ~200 nhân viên. Mục tiêu nghiệp vụ: **thay việc nhắn tin xin phòng, chấm dứt
tình trạng trùng lịch**.

Quy ước: `[x]` đã làm và đã kiểm chứng (cách kiểm chứng ghi trong ngoặc) · `[ ]` chưa làm, kèm bộ phận phụ trách.
Danh mục tính năng chi tiết: [03-checklist-features.md](03-checklist-features.md). Ghi chép rà soát và quyết định:
[04-review-log.md](04-review-log.md).

---

## 1. Mục tiêu nghiệp vụ

- [x] Không bao giờ có hai lịch đặt chồng giờ trên cùng một phòng, kể cả khi nhiều người đặt cùng lúc (ràng buộc loại trừ ở PostgreSQL; test 20 luồng tranh một khung giờ → đúng 1 thành công)
- [x] Nhân viên tự đặt, sửa, hủy lịch của mình; xem lịch của tất cả phòng theo ngày
- [x] Tìm phòng trống theo ngày/giờ, số người, thiết bị
- [x] Quản trị viên quản lý phòng, người dùng, mọi lịch đặt, và chặn phòng khi bảo trì
- [x] Hai vai trò rõ ràng (nhân viên / quản trị), phân quyền được kiểm tra ở server
- [x] Họp nhạy cảm có thể đặt ở chế độ riêng tư (người khác chỉ thấy phòng đã có người)
- [x] Giao diện tiếng Việt và tiếng Anh (mặc định tiếng Anh)

## 2. Kiến trúc và triển khai

- [x] Backend Java 21 / Spring Boot 3, PostgreSQL 16, giao diện React phục vụ qua nginx
- [x] Chạy toàn bộ bằng `docker compose up` (db → app → web, có healthcheck và thứ tự khởi động) (clone sạch, chạy từ đầu)
- [x] Ứng dụng không giữ trạng thái: chạy được nhiều bản sau load balancer
- [x] Cấu hình hoàn toàn qua biến môi trường; có `.env.example`
- [x] Cổng quản trị (health, metrics) tách riêng, không publish; cổng API chỉ mở ở `127.0.0.1`, người dùng đi qua nginx
- [x] Container backend chạy bằng user không phải root; log container có xoay vòng
- [x] Tắt êm (graceful shutdown), tự khởi động lại khi lỗi

## 3. Dữ liệu và migration

- [x] Schema quản lý bằng Flyway; ứng dụng kiểm tra schema khi khởi động (`ddl-auto=validate`)
- [x] Migration chỉ thêm cột/bảng, tương thích ngược với bản đang chạy (đã áp V2–V4 lên CSDL có dữ liệu)
- [x] Chỉ mục cho các truy vấn chính (lịch theo phòng/ngày, lịch của người dùng, kiểm tra chồng lấn)
- [x] Hủy mềm: giữ lịch sử đặt phòng; phòng chỉ vô hiệu hóa, không xóa
- [x] Thời gian lưu dạng UTC, quy tắc nghiệp vụ tính theo múi giờ công ty (cấu hình được)

## 4. Bảo mật

- [x] Mật khẩu BCrypt; mật khẩu quá 72 byte bị từ chối thay vì bị cắt ngầm
- [x] Phiên đăng nhập thu hồi được: đổi/đặt lại mật khẩu, đổi vai trò, khóa tài khoản có hiệu lực ngay (test)
- [x] Khóa tạm khi đăng nhập sai nhiều lần; chống dò mật khẩu qua chức năng đổi mật khẩu
- [x] Chống giả `X-Forwarded-For` (nginx ghi đè, không nối thêm) (thử 6 IP giả vẫn bị khóa)
- [x] Giới hạn tốc độ thay đổi lịch đặt theo người dùng; giới hạn theo IP ở nginx; body tối đa 64 KB
- [x] Hạn mức cứng chống ôm phòng: 8 giờ/ngày/người, 20 lịch sắp tới/người (test song song)
- [x] Che dữ liệu họp riêng tư ở server, không chỉ ở giao diện (test không rò rỉ qua mọi endpoint)
- [x] Luôn còn ít nhất một quản trị viên hoạt động
- [x] Không có secret trong mã nguồn; profile `prod` từ chối khởi động nếu còn giá trị mẫu (đã thử)
- [x] Header bảo mật và CSP ở nginx; không trả stack trace; `requestId` được làm sạch
- [x] Nhật ký kiểm toán cho thao tác nhạy cảm (ai làm gì, đã chống giả dòng log)
- [ ] TLS và HSTS tại reverse proxy / load balancer của công ty (Hạ tầng)
- [ ] Rà soát bảo mật độc lập trước khi mở rộng phạm vi sử dụng (An toàn thông tin)
- [ ] Chuyển token sang cookie `HttpOnly` kèm CSRF nếu chính sách bảo mật yêu cầu (Phát triển)

## 5. Độ tin cậy và hiệu năng

- [x] Khóa DB và truy vấn có giới hạn thời gian (5 s / 15 s); khi bận trả 503 kèm `Retry-After` thay vì treo (test giữ khóa thật)
- [x] Phân trang cho mọi danh sách, giới hạn kích thước trang, danh sách cột được phép sắp xếp
- [x] Chống N+1 khi liệt kê phòng và lịch
- [x] Đặt phòng an toàn khi đóng phòng đồng thời (khóa phòng khi đặt và khi vô hiệu hóa) (test)
- [ ] Kiểm thử tải: ~200 người dùng, đỉnh đầu giờ sáng và trước giờ họp (QA / Phát triển)
- [ ] Giới hạn tốc độ dùng chung nếu chạy nhiều replica (hiện tính theo từng instance) (Hạ tầng)

## 6. Vận hành và giám sát

- [x] Health: `/actuator/health/liveness`, `/actuator/health/readiness`
- [x] Số liệu Prometheus: lịch đã đặt/bị từ chối/đã hủy, lỗi theo mã (`api_errors_total{code}`)
- [x] Log có `requestId` và `userId`; log kiểm toán có logger riêng `AUDIT`
- [x] Khôi phục khi mất hết quản trị viên: đặt `ADMIN_EMAIL`/`ADMIN_PASSWORD` rồi khởi động lại
- [ ] Dashboard và cảnh báo (đột biến `RATE_LIMITED`, `DAILY_LIMIT_REACHED`, `SERVICE_BUSY`, lỗi 5xx) (Vận hành)
- [ ] Gom log (đặc biệt `AUDIT`) về hệ thống tập trung, đặt thời gian lưu (Vận hành)
- [ ] Quy trình sao lưu PostgreSQL theo lịch và diễn tập khôi phục (Vận hành)
- [ ] Quy trình nâng cấp phiên bản PostgreSQL và quy trình quay lui bản phát hành (Vận hành)

## 7. Chất lượng và kiểm thử

- [x] Test đơn vị cho quy tắc thời gian (có đồng hồ giả lập, không phụ thuộc giờ chạy test)
- [x] Test tích hợp trên PostgreSQL thật: xác thực, phòng, đặt phòng, lịch, riêng tư, hạn mức, lịch lặp, chặn phòng
- [x] Test đồng thời: tranh một khung giờ, khung giờ gối nhau, đóng phòng khi đang đặt, vượt hạn mức song song, hai admin hạ quyền nhau
- [x] Kiểm thử giao diện bằng trình duyệt thật (Playwright) cho các luồng chính, ngôn ngữ, di động, tình huống lỗi
- [x] CI: build và chạy test backend, build giao diện, build ảnh Docker
- [ ] Đưa kiểm thử giao diện (`e2e/`) vào CI (Phát triển)

## 8. Tài liệu

- [x] README: cách chạy, kiến trúc, mô hình dữ liệu, luồng chống trùng lịch, quyết định thiết kế và lý do, giới hạn đã biết
- [x] Tài liệu API (Swagger UI) và bộ yêu cầu mẫu `docs/requests.http`
- [x] Ghi chép rà soát: các tình huống xấu đã cân nhắc, cách xử lý hoặc lý do chấp nhận
- [ ] Hướng dẫn sử dụng ngắn cho nhân viên (đặt phòng, lịch lặp, họp riêng tư) và thông báo triển khai (Quản lý sản phẩm)
- [ ] Sổ tay vận hành cho quản trị viên (tạo tài khoản, chặn phòng khi bảo trì, xử lý lạm dụng) (Quản lý sản phẩm)

---

## 9. Trước lần triển khai đầu tiên

Làm lần lượt; mục nào chưa xong thì chưa mở cho nhân viên.

- [ ] Sinh `JWT_SECRET` ngẫu nhiên (`openssl rand -base64 48`), đặt `DB_PASSWORD`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` mạnh trong `.env`
- [ ] Đặt `APP_PROFILE=prod` và `DEMO_HINT=false` (không dữ liệu mẫu, không gợi ý tài khoản ở trang đăng nhập)
- [ ] Đặt `CORS_ALLOWED_ORIGINS` nếu giao diện chạy ở tên miền khác (thường để trống vì cùng origin)
- [ ] Đặt hệ thống sau reverse proxy có TLS; chỉ mở cổng của nginx
- [ ] Kiểm tra múi giờ công ty (`APP_TIMEZONE`) và giờ làm việc (`WORKDAY_START`, `WORKDAY_END`)
- [ ] Xác nhận hạn mức phù hợp với thực tế (`MAX_BOOKED_MINUTES_PER_DAY`, `MAX_ACTIVE_BOOKINGS`, `MAX_ADVANCE_DAYS`)
- [ ] Đăng nhập bằng quản trị viên, tạo 10 phòng (tên, vị trí, sức chứa, thiết bị) và tài khoản nhân viên
- [ ] Cấu hình sao lưu CSDL và kiểm tra khôi phục thử một lần
- [ ] Chạy kiểm tra nhanh sau triển khai: đăng nhập, đặt/hủy một lịch, thử đặt trùng (phải bị từ chối), xem health và số liệu
- [ ] Thông báo cho nhân viên: địa chỉ truy cập, cách lấy tài khoản, quy tắc đặt phòng (giờ làm việc, 15 phút/bước, hạn mức)

## 10. Rủi ro đã chấp nhận

Các điểm này được cân nhắc có chủ đích; chi tiết và hướng nâng cấp ở [04-review-log.md](04-review-log.md).

- Token đăng nhập lưu ở `localStorage` (giảm thiểu bằng CSP chặt và không hiển thị HTML do người dùng nhập)
- Giới hạn tốc độ và khóa đăng nhập tính theo từng instance
- Họp riêng tư không giấu được việc phòng đang bận (cần thiết để chống trùng lịch); quản trị viên xem được chi tiết
- Chưa có thông báo email: người đặt được báo trong ứng dụng khi quản trị viên hủy lịch của họ
- Sửa lịch lặp theo từng buổi, hủy theo chuỗi (chưa sửa cả chuỗi một lần)
