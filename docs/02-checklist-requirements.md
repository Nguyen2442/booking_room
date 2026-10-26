# Checklist yêu cầu đề bài (theo test.md)

Đánh dấu `[x]` khi hoàn thành và đã kiểm chứng.

## A. Chức năng bắt buộc
- [x] Quản lý phòng (tạo / xem / sửa / vô hiệu hóa)
- [x] Đặt phòng
- [x] Xem lịch (theo phòng, theo ngày)
- [x] Tìm phòng trống (theo khoảng thời gian, sức chứa, thiết bị)
- [x] Có 2 loại người dùng: người dùng thường & quản trị
- [x] **Giải quyết vấn đề gốc: không bao giờ trùng lịch** (kể cả request đồng thời)

## B. Công nghệ
- [x] Java 17+ (dùng 21)
- [x] Spring Boot 3
- [x] PostgreSQL
- [x] Docker: `docker compose up` chạy toàn bộ (app + db, tự migrate)
- [x] Thư viện bổ sung hợp lý (Flyway, Security/JWT, springdoc, Testcontainers…)

## C. Nộp bài
- [x] Git repository, **giữ nguyên lịch sử commit** (commit nhỏ, message rõ, không squash/force-push)
- [x] README.md: cách chạy
- [x] README.md: thiết kế (kiến trúc, DB, API)
- [x] README.md: các quyết định quan trọng và lý do
- [x] README.md: giả định nghiệp vụ & hạn chế đã biết
- [ ] Link repo gửi công ty (việc của bạn: push repo lên GitHub/GitLab rồi gửi link, giữ nguyên lịch sử commit)

## D. "Như hệ thống thật" (tiêu chí chất lượng ngầm)
- [x] Chống race condition ở tầng DB (exclusion constraint)
- [x] Xác thực + phân quyền đúng (USER vs ADMIN, quyền sở hữu booking)
- [x] Validation đầu vào đầy đủ, thông báo lỗi nhất quán (RFC 7807)
- [x] Migration có version (Flyway), không dùng `ddl-auto=update`
- [x] Không hard-code secret; cấu hình qua biến môi trường
- [x] Phân trang & index cho truy vấn chính
- [x] Soft-cancel, giữ lịch sử booking
- [x] Xử lý múi giờ rõ ràng
- [x] Test tự động (unit + integration + concurrency) chạy xanh
- [x] Health check, logging hợp lý
- [x] Tài liệu API (Swagger/OpenAPI)
- [x] Dữ liệu demo/seed để chấm bài chạy nhanh (admin mặc định, 10 phòng)
- [x] `.gitignore`, `.dockerignore`, không commit file build/secret

## E. Rà soát trước khi nộp
- [x] Clone repo mới → `docker compose up` → chạy được từ đầu
- [x] Chạy toàn bộ test thành công
- [x] Kịch bản demo trong README (curl/Swagger) chạy đúng
- [x] Lịch sử git sạch, có nhiều commit có ý nghĩa
