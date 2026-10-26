# Danh mục tính năng và trạng thái

Trạng thái hiện tại của từng tính năng. Ký hiệu: **P0** cần có để vận hành · **P1** nên có · **P2** mở rộng.
`[x]` đã hoàn thành và có kiểm thử · `[ ]` chưa làm.

Checklist sẵn sàng vận hành (bảo mật, hạ tầng, quy trình): [02-checklist-requirements.md](02-checklist-requirements.md).

---

## 1. Tài khoản và đăng nhập
- [x] P0 Đăng nhập bằng email/mật khẩu, nhận JWT (hiệu lực 8 giờ); đăng xuất; tự đăng xuất khi phiên hết hạn hoặc bị thu hồi
- [x] P0 Xem thông tin tài khoản hiện tại
- [x] P0 Tài khoản quản trị đầu tiên tạo từ biến môi trường (an toàn khi nhiều instance khởi động cùng lúc)
- [x] P0 Phiên đăng nhập bị thu hồi ngay khi đổi/đặt lại mật khẩu, đổi vai trò, khóa tài khoản
- [x] P0 Nhân viên tự đổi mật khẩu (trang Tài khoản); phiên khác bị đăng xuất, phiên hiện tại được cấp token mới
- [x] P0 Khóa tạm khi đăng nhập sai nhiều lần; thông báo giống nhau cho sai email / sai mật khẩu / tài khoản khóa
- [x] P1 Quản trị viên tạo, sửa, khóa tài khoản và đặt lại mật khẩu; tìm/lọc người dùng
- [x] P1 Khóa tài khoản tự hủy mọi lịch sắp tới của người đó
- [x] P1 Quản trị viên không tự khóa/hạ quyền chính mình; luôn còn ít nhất một quản trị viên hoạt động
- [ ] P2 Refresh token; đăng nhập một lần (SSO) (ngoài phạm vi hiện tại)

## 2. Phòng họp
- [x] P0 Tạo, sửa phòng: tên (không trùng, không phân biệt hoa thường), vị trí, sức chứa, thiết bị, mô tả
- [x] P0 Danh sách phòng có phân trang, lọc theo sức chứa tối thiểu, thiết bị, trạng thái; nhân viên chỉ thấy phòng đang hoạt động
- [x] P0 Vô hiệu hóa phòng (không xóa); bị từ chối nếu còn lịch sắp tới; kích hoạt lại được
- [x] P0 Không giảm sức chứa xuống dưới số người của lịch đã đặt
- [x] P0 Thiết bị chuẩn hóa (không phân biệt hoa thường), hiển thị theo ngôn ngữ

## 3. Đặt phòng
- [x] P0 Đặt phòng: phòng, tiêu đề, mô tả, giờ bắt đầu/kết thúc, số người tham dự
- [x] P0 Chống trùng lịch ở tầng cơ sở dữ liệu; hai lịch liền kề (10:00–11:00 và 11:00–12:00) không bị coi là trùng
- [x] P0 Quy tắc thời gian: bước 15 phút; 15 phút đến 4 giờ; trong giờ làm việc (08:00–20:00), cùng một ngày; không đặt giờ đã qua
- [x] P0 Khung giờ hiện tại vẫn đặt được trong 15 phút đầu
- [x] P0 Đặt trước tối đa 30 ngày (tính theo ngày dương lịch)
- [x] P0 Số người tham dự bắt buộc, không vượt sức chứa phòng
- [x] P0 Sửa lịch (giờ, tiêu đề, mô tả, số người) kèm kiểm tra xung đột lại; **đổi sang phòng khác** được
- [x] P0 Cuộc họp đang diễn ra: kéo dài hoặc kết thúc sớm để trả phòng (không đổi giờ bắt đầu và phòng)
- [x] P0 Hủy lịch (chủ lịch hoặc quản trị viên) kèm lý do; không hủy được lịch đã kết thúc; hủy lặp lại trả lỗi rõ
- [x] P0 Nhân viên chỉ sửa/hủy lịch của mình; xem chi tiết lịch của mình
- [x] P1 Đặt lịch lặp (hằng tuần, 2 tuần một lần, các ngày làm việc), tối đa 26 buổi, mỗi buổi qua đủ quy tắc
- [x] P1 Lịch lặp bị trùng: báo đúng ngày trùng; chọn hủy cả chuỗi hoặc bỏ qua các ngày đó
- [x] P1 Hủy một buổi hoặc "buổi này và các buổi sau"
- [x] P1 Họp riêng tư (xem mục 6)
- [x] P1 Thêm vào lịch cá nhân (.ics, tạo ngay trên trình duyệt)
- [ ] P2 Sửa cả chuỗi lặp một lần
- [ ] P2 Check-in và tự giải phóng phòng khi không ai đến
- [ ] P2 Mời người tham dự, thông báo email (phòng vật lý chỉ cần giữ chỗ; cần khi có họp trực tuyến)

## 4. Xem lịch và tìm phòng
- [x] P0 Lịch tất cả phòng theo ngày dạng dòng thời gian; bấm vào ô trống để đặt; vạch "bây giờ"; vùng đã qua
- [x] P0 Lịch của một phòng theo ngày hoặc khoảng ngày (tối đa 31 ngày)
- [x] P0 Chỉ hiển thị lịch còn hiệu lực; lịch nằm ngoài giờ làm việc cũ (khi đổi cấu hình) vẫn hiển thị, không bị ẩn
- [x] P0 Tìm phòng trống theo ngày/giờ, số người, thiết bị; áp dụng cùng quy tắc như đặt phòng
- [x] P1 Danh sách khoảng trống trong ngày của một phòng; gợi ý khung giờ trống khi vừa bị người khác đặt mất
- [x] P1 Cảnh báo phòng đang bận ngay trong lúc nhập, trước khi gửi
- [x] P1 Sắp xếp kết quả tìm phòng theo sức chứa nhỏ nhất đủ chỗ
- [x] P1 "Lịch của tôi": sắp tới / đã qua / đã hủy; hiển thị lý do hủy và việc quản trị viên đã hủy

## 5. Quản trị
- [x] P0 Quản lý phòng và người dùng (mục 1, 2)
- [x] P0 Xem mọi lịch đặt; lọc theo phòng, người đặt (tìm nhanh theo tên), loại, trạng thái, khoảng ngày; hủy lịch của người khác
- [x] P0 **Chặn phòng theo khoảng thời gian** (bảo trì, vệ sinh, sự kiện), có thể kéo dài nhiều ngày; hiển thị trên lịch; gỡ chặn
- [x] P0 Chặn phòng khi đang có lịch trùng: xem danh sách và chọn hủy các lịch đó rồi chặn, hoặc dừng lại
- [x] P0 Người đặt thấy thông báo trong ứng dụng khi quản trị viên hủy lịch của họ
- [ ] P2 Báo cáo sử dụng (tỷ lệ lấp đầy từng phòng, giờ cao điểm, lịch bị hủy)

## 6. Riêng tư và phân quyền
- [x] P0 Họp riêng tư: người khác chỉ thấy phòng đã có người và khung giờ; không thấy tiêu đề, mô tả, người đặt, số người, chuỗi lặp
- [x] P0 Việc che dữ liệu làm ở server (mọi endpoint); không xác định được người xem thì che
- [x] P0 Chủ lịch và quản trị viên xem đầy đủ
- [x] P0 Lịch chung không lộ email của người đặt
- [x] P0 Phân quyền: quản trị viên / nhân viên / chủ lịch được kiểm tra ở server, có test cho từng endpoint nhạy cảm

## 7. Hạn mức và chống lạm dụng
- [x] P0 Tối đa 8 giờ đặt mỗi người mỗi ngày, cộng dồn các phòng
- [x] P0 Tối đa 20 lịch sắp tới mỗi người trong cửa sổ đặt trước; các buổi xa của lịch lặp không chiếm hạn mức này
- [x] P0 Hạn mức là cứng: nhiều yêu cầu song song của một người không thể cùng vượt
- [x] P0 Tối đa 30 thay đổi lịch/phút/người (429 kèm `Retry-After`)
- [x] P0 Thông báo lỗi nêu rõ giới hạn và ngày vi phạm; có bản tiếng Việt
- [x] P0 Cấu hình được bằng biến môi trường

## 8. Giao diện
- [x] P0 Đăng nhập; điều hướng theo vai trò; trang quản trị chỉ hiện với quản trị viên
- [x] P0 Chọn ngôn ngữ tiếng Anh / tiếng Việt (mặc định tiếng Anh, nhớ lựa chọn); lỗi từ server được dịch theo mã lỗi
- [x] P0 Trạng thái đang tải, danh sách rỗng, lỗi kèm nút thử lại; thông báo ngắn sau thao tác; xác nhận khi hủy/khóa
- [x] P0 Dùng được trên điện thoại (thanh điều hướng dưới, bảng cuộn ngang); chế độ tối; dùng được bằng bàn phím và trình đọc màn hình
- [x] P0 Giờ hiển thị và nhập theo múi giờ công ty, không phụ thuộc đồng hồ máy người dùng
- [x] P1 Lịch tự làm mới định kỳ để thấy lịch người khác vừa đặt

## 9. Vận hành
- [x] P0 Cấu hình qua biến môi trường; profile `prod` từ chối giá trị mẫu; profile `demo` chỉ dành cho thử nghiệm
- [x] P0 Health liveness/readiness; số liệu Prometheus; log có `requestId`; nhật ký kiểm toán
- [x] P0 Migration tự chạy khi khởi động; CSDL và API có timeout; 503 kèm `Retry-After` khi quá tải
- [x] P1 Dữ liệu mẫu (10 phòng, 5 nhân viên, vài lịch) để chạy thử nhanh ở profile `demo`
- [ ] P1 Dashboard, cảnh báo, gom log, sao lưu CSDL (xem mục 6 của [02-checklist-requirements.md](02-checklist-requirements.md))

## 10. Kiểm thử và tài liệu
- [x] P0 Test đơn vị, tích hợp (PostgreSQL thật) và đồng thời cho nghiệp vụ lõi; chạy bằng `./scripts/test.sh`
- [x] P0 Kiểm thử giao diện bằng trình duyệt thật trong `e2e/`
- [x] P0 CI: test backend, build giao diện, build ảnh Docker
- [x] P0 README, tài liệu API (Swagger), bộ yêu cầu mẫu, ghi chép rà soát và quyết định
- [ ] P1 Kiểm thử giao diện chạy trong CI
- [ ] P1 Kiểm thử tải

## 11. Ngoài phạm vi hiện tại
Chủ động không làm; ghi rõ lý do để cân nhắc lại khi nhu cầu thay đổi.

| Hạng mục | Lý do | Khi nào xem lại |
|---|---|---|
| Phê duyệt trước khi đặt | Mất lợi thế đặt ngay, quản trị viên thành điểm nghẽn cho ~200 người; hạn mức và giới hạn tốc độ đã xử lý lạm dụng | Có phòng đặc biệt (họp hội đồng) cần duyệt riêng |
| SSO và mọi tích hợp dịch vụ ngoài | Cần dịch vụ danh tính ngoài; tài khoản hiện do quản trị viên cấp | Công ty chuẩn hóa một nhà cung cấp danh tính |
| Thông báo email | Cần hạ tầng gửi thư; đã có thông báo trong ứng dụng | Có yêu cầu nhắc lịch trước giờ họp |
| Mời người tham dự / khách | Phòng vật lý chỉ cần giữ chỗ | Có họp trực tuyến hoặc quy trình đón khách |
| Giới hạn tốc độ dùng chung giữa các replica | Hiện chạy một instance; giới hạn theo instance vẫn chặn dò mật khẩu | Chạy nhiều replica |
