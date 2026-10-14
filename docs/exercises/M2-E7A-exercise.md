# Bài Tập M2-E7A: Ràng Buộc CHECK, NULL Logic Và Rollback Khi Nâng Cấp Schema

**Học phần:** M2-E7A — CHECK/NULL, Flyway Forward Migration và DDL Rollback
**Trạng thái học viên:** `[AWAITING_CANDIDATE]`
**Mục tiêu bài tập:** Đánh giá năng lực thiết kế ràng buộc cơ sở dữ liệu với logic tam trị (3-valued logic), xây dựng kịch bản forward migration an toàn và xử lý sự cố khi dữ liệu cũ xung đột với quy định mới.

---

## 1. Bối Cảnh Bài Tập

Hệ thống thanh toán của AetherCore đang phát triển tính năng quản lý hoàn tiền (Refund). Bảng `market_refund` ghi nhận quy trình xử lý hoàn tiền với các yêu cầu nghiệp vụ sau:

### Khế ước nghiệp vụ (Business Contract):
1. Cột `state` nhận 1 trong 2 trạng thái: `'PENDING'` hoặc `'RESOLVED'`.
2. Cột `resolution` nhận 1 trong 2 kết quả: `'APPROVED'` hoặc `'REJECTED'`.
3. Cột `refund_transaction_id` (UUID) lưu mã giao dịch hoàn tiền từ cổng thanh toán bên thứ ba:
   - Khi `state = 'PENDING'`: Yêu cầu đang duyệt, cả `resolution` và `refund_transaction_id` **bắt buộc phải là `NULL`**.
   - Khi `state = 'RESOLVED'`:
     - Nếu `resolution = 'APPROVED'`: Hoàn tiền thành công, `refund_transaction_id` **bắt buộc phải có giá trị** (`NOT NULL`).
     - Nếu `resolution = 'REJECTED'`: Từ chối hoàn tiền, `refund_transaction_id` **bắt buộc phải là `NULL`**.
     - Bất kỳ trường hợp `RESOLVED` nào mà `resolution` bị để trống (`NULL`) đều là **dữ liệu rác không hợp lệ**.

---

## 2. Phần Tự Làm 1: Viết Ràng Buộc CHECK Và Lập Truth Table

### Câu hỏi 1.1:
Dưới đây là một mệnh đề `CHECK` do một lập trình viên tập sự soạn thảo:
```sql
CONSTRAINT chk_refund_shape CHECK (
    (state = 'PENDING' AND resolution IS NULL AND refund_transaction_id IS NULL)
    OR
    (state = 'RESOLVED' AND (
        (resolution = 'APPROVED' AND refund_transaction_id IS NOT NULL)
        OR
        (resolution = 'REJECTED' AND refund_transaction_id IS NULL)
    ))
)
```
Em hãy chỉ ra lỗ hổng của ràng buộc trên khi có dữ liệu `state = 'RESOLVED'`, `resolution = NULL`, `refund_transaction_id = NULL`. Phân tích chi tiết từng bước tính toán giá trị logic của PostgreSQL (`TRUE`, `FALSE`, `UNKNOWN`).

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 1.2:
Em hãy viết lại câu lệnh SQL định nghĩa ràng buộc `chk_refund_shape` đã được vá triệt để lỗ hổng trên, đảm bảo mọi trạng thái sai đều cho kết quả `FALSE` dứt khoát.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 1.3:
Lập bảng chân lý (Truth Table) gồm 8 tổ hợp đầu vào khả dĩ giữa `state`, `resolution`, và sự hiện diện của `refund_transaction_id` đối với ràng buộc đã sửa. Ghi rõ kết quả mong đợi (`ACCEPT` hay `REJECT`) và mã lỗi SQLSTATE tương ứng khi bị từ chối.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

## 3. Phần Tự Làm 2: Xử Lý Nâng Cấp Schema Trên Bảng Có Dữ Liệu Lỗi

### Câu hỏi 2.1:
Bảng `market_refund` trên môi trường Staging hiện đã có hàng triệu bản ghi, trong đó do lỗi code phiên bản cũ nên có khoảng 150 bản ghi mang trạng thái `state = 'RESOLVED'` nhưng `resolution` là `NULL`.
Nếu em tạo file migration `V7__fix_refund_shape.sql` để áp dụng ràng buộc mới bằng câu lệnh:
```sql
ALTER TABLE market_refund ADD CONSTRAINT chk_refund_shape CHECK (...);
```
Điều gì sẽ xảy ra khi Flyway chạy migration này trên PostgreSQL? Database sẽ ở trạng thái nào? Flyway history sẽ ghi nhận ra sao?

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 2.2:
Một đồng nghiệp đề xuất: *"Hãy viết thêm câu lệnh `DELETE FROM market_refund WHERE state = 'RESOLVED' AND resolution IS NULL;` vào đầu file migration V7 trước câu lệnh ADD CONSTRAINT để dọn dẹp cho sạch sẽ rồi migrate một thể."*
Em có đồng ý với giải pháp này không? Tại sao? Đưa ra ít nhất 2 rủi ro nghiêm trọng về mặt tài chính và kiểm toán.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 2.3:
Hãy trình bày quy trình chuẩn 3 bước để khắc phục sự cố và triển khai thành công ràng buộc mới trên môi trường Production mà không làm mất mát dữ liệu và không gây downtime.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

## 4. Vấn Đáp Phỏng Vấn (Oral Exam — 60 Đến 90 Giây Mỗi Câu)

### Câu 1: "Tại sao trong SQL, mệnh đề WHERE thì lọc bỏ NULL, nhưng CHECK constraint lại vẫn cho phép dòng chứa NULL đi qua?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - Trả lời đúng sự khác biệt cốt lõi: Mệnh đề `WHERE` tuân theo nguyên tắc "chỉ chấp nhận `TRUE`" (bỏ qua cả `FALSE` và `UNKNOWN`). Ngược lại, mệnh đề `CHECK` tuân theo nguyên tắc "chỉ từ chối khi khẳng định vi phạm chắc chắn là `FALSE`" (cho phép cả `TRUE` lẫn `UNKNOWN`).
  - Nêu được ví dụ: Khi so sánh `col = 'ABC'` với `col` là NULL, kết quả là `UNKNOWN`. `WHERE` sẽ không lấy dòng đó; nhưng `CHECK` sẽ cho phép insert dòng đó vào bảng.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu 2: "Tại sao khi phát hiện file migration V5 có lỗi constraint, em không sửa luôn nội dung file V5 rồi chạy lại cho sạch sẽ mà lại phải tạo file V6?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - Nêu được nguyên tắc bất biến (Immutability) của migration: Khi đã commit và deploy lên môi trường chung, migration là lịch sử không thể thay đổi.
  - Hiểu cơ chế hoạt động của Flyway: Flyway lưu giá trị checksum CRC32 của mỗi file script vào `flyway_schema_history`. Sửa file đã chạy sẽ làm sai lệch checksum, khiến `flyway validate` báo lỗi ném ngoại lệ và dừng ứng dụng ở lần khởi động sau.
  - Phân kỳ môi trường (Environment Drift): Database cũ đã chạy V5 sẽ không bao giờ chạy lại file V5 đã sửa; database mới dựng lại chạy theo file mới. Hai môi trường sẽ mang cấu trúc schema khác nhau.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu 3: "Sau khi một file migration Flyway bị fail giữa chừng trên PostgreSQL, em sẽ query những thông tin gì từ database trước khi quyết định thử lại?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - Query bảng `flyway_schema_history`: Kiểm tra xem migration lỗi có để lại dòng nào không, giá trị cột `success` là gì, version hiện tại là bao nhiêu. (Lưu ý: trên PostgreSQL có Transactional DDL, toàn bộ transaction bị rollback nên dòng failed thường bị xóa sạch).
  - Query PostgreSQL system catalogs (`pg_constraint`, `pg_class`, `pg_tables`): Kiểm tra xem các bảng/ràng buộc đã tạo dở dang có còn tồn tại không.
  - Query bảng dữ liệu nghiệp vụ: Tìm kiếm chính xác các dòng dữ liệu đang vi phạm ràng buộc mới để chuẩn bị kịch bản remediation.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`
