# Tạo workflow từ đầu trong môi trường local

Môi trường `dev` hiện seed dữ liệu tổ chức và nhân sự để trang `/organization` có dữ liệu minh họa. Các workflow mẫu không còn được seed mặc định, vì vậy bạn có thể tự tạo lại từ giao diện.

## Khởi động với dữ liệu sạch

Nếu database local đã từng chạy seed workflow cũ, việc tắt seed chỉ ngăn dữ liệu mới được tạo; nó không tự xóa dữ liệu đã có. Để bắt đầu hoàn toàn từ đầu:

```powershell
docker compose -f infra/compose.yaml down -v
docker compose -f infra/compose.yaml up -d postgres
```

Sau đó khởi động backend với profile `dev` và frontend như bình thường. Muốn bật lại các workflow fixture cũ cho mục đích demo, đặt:

```text
PLATFORM_SEED_DEMO_WORKFLOWS_ENABLED=true
```

Giá trị mặc định là `false`.

## Luồng tạo một workflow

1. Mở `/workflows` và chọn **Tạo quy trình**.
2. Nhập tên dễ đọc và khóa ổn định viết hoa, ví dụ `LEAVE_APPROVAL`.
3. Mở phiên bản bản nháp trong **Trình xây dựng**.
4. Kéo node từ danh mục vào bảng vẽ. Một workflow tối thiểu cần đúng một **Bắt đầu**, ít nhất một **Kết thúc**, và các node phải được nối liên tục.
5. Chọn từng node để cấu hình người xử lý, cổng kết quả, biểu mẫu hoặc điều kiện.
6. Nhấn **Lưu bản nháp**, sau đó **Kiểm tra**. Nếu không còn lỗi, dùng **Mô phỏng** để xem thứ tự node, người xử lý và nhánh được chọn trong bộ nhớ.
7. Nhấn **Phát hành**. Chỉ phiên bản `PUBLISHED` mới được chọn khi gắn vào Form hoặc Business Intent.

## Ba workflow nên tạo để làm quen

### 1. Phê duyệt nghỉ phép

Tạo workflow `LEAVE_APPROVAL` với các node:

```text
Bắt đầu → Phê duyệt quản lý → Kết thúc
```

Cấu hình node **Phê duyệt quản lý**:

- Người xử lý: `MANAGER_OF`, độ sâu `1`.
- Cổng: `APPROVED` và `REJECTED`.
- Nối `APPROVED` tới node **Kết thúc** với kết quả hoàn tất.
- Nối `REJECTED` tới một node **Kết thúc** khác với kết quả từ chối.

Workflow này giúp kiểm tra cây reporting trong `/organization`: nhân viên gửi yêu cầu phải có vị trí, và vị trí đó phải có quản lý ở cấp trên.

### 2. Mua sắm có điều kiện

Tạo workflow `PURCHASE_APPROVAL`:

```text
Bắt đầu → Điều kiện → Phê duyệt quản lý → Kết thúc
                    └→ Kết thúc nhanh
```

Tạo một input hoặc Form field kiểu số có khóa `amount`. Ở node **Điều kiện**, tạo nhánh cho khoản tiền lớn cần phê duyệt và nhánh cho khoản tiền nhỏ được hoàn tất nhanh. Kiểm tra cả hai trường hợp trong **Mô phỏng** trước khi phát hành.

### 3. Phê duyệt song song

Tạo workflow `PURCHASE_PARALLEL_REVIEW`:

```text
Bắt đầu → Tách song song → Tài chính ─┐
                         → Pháp chế ──┤→ Ghép tất cả → Kết thúc
```

Đặt chính sách của node **Ghép tất cả** là chờ đủ các nhánh. Workflow chỉ đi tiếp khi cả Tài chính và Pháp chế hoàn tất; nếu một nhánh bị từ chối, nối nhánh đó tới **Kết thúc** từ chối.

## Gắn Form và Business Intent

Sau khi workflow đã `PUBLISHED`:

1. Vào `/forms`, tạo và phát hành Form với các field có khóa rõ ràng như `amount`, `reason`, `department`.
2. Vào `/ticket-categories`, tạo Business Intent hoặc mở một bản nháp hiện có.
3. Chọn Form và Workflow đã phát hành.
4. Dùng **Tự động map** để ghép field theo semantic tag, khóa, nhãn và type. Những field không chắc chắn sẽ để trống để bạn chọn thủ công.
5. Nhấn **Kiểm tra**, lưu bản nháp rồi phát hành binding.

Nếu workflow có input bắt buộc nhưng chưa được map hoặc chưa có giá trị mặc định, kiểm tra sẽ báo lỗi và không cho phát hành binding.
