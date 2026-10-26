# Checklist yêu cầu đề bài (theo test.md)

Đánh dấu `[x]` khi hoàn thành và đã kiểm chứng.

## A. Chức năng bắt buộc
- [ ] Quản lý phòng (tạo / xem / sửa / vô hiệu hóa)
- [ ] Đặt phòng
- [ ] Xem lịch (theo phòng, theo ngày)
- [ ] Tìm phòng trống (theo khoảng thời gian, sức chứa, thiết bị)
- [ ] Có 2 loại người dùng: người dùng thường & quản trị
- [ ] **Giải quyết vấn đề gốc: không bao giờ trùng lịch** (kể cả request đồng thời)

## B. Công nghệ
- [ ] Java 17+ (dùng 21)
- [ ] Spring Boot 3
- [ ] PostgreSQL
- [ ] Docker: `docker compose up` chạy toàn bộ (app + db, tự migrate)
- [ ] Thư viện bổ sung hợp lý (Flyway, Security/JWT, springdoc, Testcontainers…)

## C. Nộp bài
- [ ] Git repository, **giữ nguyên lịch sử commit** (commit nhỏ, message rõ, không squash/force-push)
- [ ] README.md: cách chạy
- [ ] README.md: thiết kế (kiến trúc, DB, API)
- [ ] README.md: các quyết định quan trọng và lý do
- [ ] README.md: giả định nghiệp vụ & hạn chế đã biết
- [ ] Link repo gửi công ty

## D. "Như hệ thống thật" (tiêu chí chất lượng ngầm)
- [ ] Chống race condition ở tầng DB (exclusion constraint)
- [ ] Xác thực + phân quyền đúng (USER vs ADMIN, quyền sở hữu booking)
- [ ] Validation đầu vào đầy đủ, thông báo lỗi nhất quán (RFC 7807)
- [ ] Migration có version (Flyway), không dùng `ddl-auto=update`
- [ ] Không hard-code secret; cấu hình qua biến môi trường
- [ ] Phân trang & index cho truy vấn chính
- [ ] Soft-cancel, giữ lịch sử booking
- [ ] Xử lý múi giờ rõ ràng
- [ ] Test tự động (unit + integration + concurrency) chạy xanh
- [ ] Health check, logging hợp lý
- [ ] Tài liệu API (Swagger/OpenAPI)
- [ ] Dữ liệu demo/seed để chấm bài chạy nhanh (admin mặc định, 10 phòng)
- [ ] `.gitignore`, `.dockerignore`, không commit file build/secret

## E. Rà soát trước khi nộp
- [ ] Clone repo mới → `docker compose up` → chạy được từ đầu
- [ ] Chạy toàn bộ test thành công
- [ ] Kịch bản demo trong README (curl/Swagger) chạy đúng
- [ ] Lịch sử git sạch, có nhiều commit có ý nghĩa
