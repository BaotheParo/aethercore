# Bài Tập M2-E7B: Non-Transactional Migration, INVALID Index Và Phục Hồi Có Kiểm Chứng

**Học phần:** M2-E7B — Non-transactional migration, INVALID index và verified recovery
**Trạng thái học viên:** `[AWAITING_CANDIDATE]`
**Mục tiêu bài tập:** Đánh giá năng lực chẩn đoán trạng thái catalog PostgreSQL khi câu lệnh tạo index đồng thời gặp sự cố, hiểu rõ giới hạn của lệnh `flyway.repair()`, và xây dựng quy trình khắc phục sự cố dữ liệu có phê duyệt trên môi trường sản xuất.

---

## 1. Bối Cảnh Bài Tập: Sự Cố Unique Index Trên Bảng Guild Member

Hệ thống quản lý bang hội của AetherCore có bảng `guild_member`:
```sql
CREATE TABLE guild_member (
    id BIGINT PRIMARY KEY,
    guild_id UUID NOT NULL,
    account_email VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Hệ thống đang hoạt động với hàng trăm nghìn thành viên. Để đảm bảo mỗi tài khoản email chỉ được gia nhập một bang hội duy nhất tại một thời điểm, đội ngũ kỹ thuật quyết định thêm một ràng buộc duy nhất bằng câu lệnh:
```sql
CREATE UNIQUE INDEX CONCURRENTLY uq_guild_member_email ON guild_member (account_email);
```

File migration được đặt tên là `V12__create_unique_guild_member_email_idx.sql` kèm theo sidecar `V12__create_unique_guild_member_email_idx.sql.conf` với nội dung `executeInTransaction=false`.

Tuy nhiên, trong đợt deploy tối hôm qua lên môi trường Staging, tiến trình migration bị ném ngoại lệ và dừng lại giữa chừng. Một kỹ sư trực ca đã vội vã chạy lệnh `flyway repair` trên console và thông báo với toàn đội: *"Em đã chạy repair thành công rồi, lát nữa ứng dụng deploy lại là ổn!"*

Là kỹ sư phụ trách chính về cơ sở dữ liệu, em nhận thấy nguy cơ hệ thống đang rơi vào trạng thái cực kỳ nguy hiểm.

---

## 2. Phần Tự Làm: Chẩn Đoán Trạng Thái Catalog & Đề Xuất Quy Trình Phục Hồi

### Câu hỏi 1: Checklist Truy Vấn Catalog Phân Biệt 3 Trạng Thái
Em hãy viết các câu lệnh SQL truy vấn trực tiếp vào PostgreSQL system catalogs (`pg_class`, `pg_index`, `pg_namespace`, `flyway_schema_history`) để phân biệt rạch ròi 3 tình huống sau:
1. **Trường hợp A:** Index hoàn toàn chưa được tạo (chưa bắt đầu build hoặc đã bị từ chối trước khi scan).
2. **Trường hợp B:** Index đã được tạo dở dang nhưng bị lỗi dữ liệu và đang ở trạng thái **INVALID**.
3. **Trường hợp C:** Index đã được tạo thành công và đang **VALID**, nhưng bản ghi trong bảng lịch sử Flyway đang bị lệch hoặc chưa rõ ràng.

Ghi rõ tên các cột trong `pg_index` (`indisvalid`, `indisready`, `indisunique`) và ý nghĩa của từng giá trị tương ứng với mỗi trường hợp.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 2: Đánh Giá Phát Biểu Của Kỹ Sư Trực Ca
Phân tích chi tiết tại sao phát biểu *"Em đã chạy flyway repair thành công rồi, lát nữa deploy lại là ổn"* là một nhận định sai lầm tai hại.
Nếu ứng dụng deploy lại ngay lúc này mà không có hành động nào khác, những hậu quả kỹ thuật nào sẽ xảy ra:
- Đối với lần chạy `flyway migrate` tiếp theo?
- Đối với câu lệnh `CREATE UNIQUE INDEX CONCURRENTLY` trong file V12?
- Đối với hiệu năng và tính toàn vẹn của bảng `guild_member`?

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu hỏi 3: Quy Trình Xử Lý Dữ Liệu Trùng Lặp Có Phê Duyệt (Data Remediation Workflow)
Giả sử kết quả truy vấn cho thấy có đúng 2 bản ghi bị trùng email:
- Dòng 1: `id = 101, guild_id = 'G-AAA', account_email = 'warrior@aethercore.com', role = 'LEADER', created_at = '2026-01-10'`
- Dòng 2: `id = 508, guild_id = 'G-BBB', account_email = 'warrior@aethercore.com', role = 'MEMBER', created_at = '2026-08-15'`

Em hãy xây dựng quy trình 4 bước hoàn chỉnh để xử lý triệt để sự cố:
1. Xác định quy tắc phân xử nghiệp vụ (Ai phê duyệt? Căn cứ vào đâu để quyết định giữ dòng nào?).
2. Viết câu lệnh DDL để dọn dẹp database object bị hỏng.
3. Kịch bản cập nhật/chuẩn hóa dữ liệu có audit trail.
4. Kịch bản chạy lại migration và xác minh tính thực thi (enforcement).

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

## 3. Vấn Đáp Phỏng Vấn (Oral Exam — 60 Đến 90 Giây Mỗi Câu)

### Câu 1: "Vì sao SQLSTATE 25001 lại khác với 23505 dù cả hai đều xuất hiện khi chạy lệnh tạo unique index?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - **SQLSTATE 25001 (`active_sql_transaction`):** Lỗi vi phạm chế độ giao dịch. Xuất hiện khi cố tình chạy `CREATE INDEX CONCURRENTLY` bên trong một transaction block mở (`setAutoCommit(false)`). Lỗi này bị PostgreSQL từ chối fail-fast **ngay lập tức ở tầng phân tích cú pháp/giao dịch**, trước khi đọc bất kỳ dòng dữ liệu nào. Không có index nào được tạo ra.
  - **SQLSTATE 23505 (`unique_violation`):** Lỗi vi phạm tính duy nhất của dữ liệu. Xuất hiện khi lệnh tạo index chạy thành công ở chế độ non-transactional, tiến hành quét dữ liệu (scan) và phát hiện các dòng có giá trị trùng lặp. Khi gặp lỗi này, index đã được tạo dở dang trong catalog và bị bỏ lại ở trạng thái **INVALID**.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu 2: "Lệnh flyway repair chạy thành công rồi mà đợt deploy tiếp theo vẫn chưa an toàn: Em sẽ kiểm tra những gì?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - Hiểu rõ bản chất của `flyway repair`: Repair chỉ thao tác trên bảng metadata `flyway_schema_history` (xóa dòng failed để cho phép retry). Nó **hoàn toàn không dọn dẹp các database objects** bị bỏ lại dở dang và **không sửa dữ liệu nghiệp vụ**.
  - Hai hạng mục bắt buộc phải kiểm tra trong database:
    1. **Catalog database:** Kiểm tra xem index hỏng có còn tồn tại trong `pg_index` với `indisvalid = false` không. Nếu còn, bắt buộc phải `DROP INDEX` thủ công, vì nếu không lệnh `CREATE INDEX CONCURRENTLY` tiếp theo sẽ báo lỗi `relation already exists`.
    2. **Dữ liệu bảng nghiệp vụ:** Kiểm tra xem dữ liệu trùng lặp đã được xử lý chưa. Nếu nguyên nhân gốc rễ (duplicate rows) chưa được giải quyết thì lần migrate tiếp theo chắc chắn sẽ lại ném lỗi 23505.

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`

---

### Câu 3: "Giá trị CRC32 của Flyway khác với mã băm SHA-256 trong evidence ở mục đích và cách sử dụng nào?"
* **Gợi ý tiêu chuẩn chấm điểm (Rubric):**
  - **CRC32 của Flyway:** Là một số nguyên 32-bit (tính bằng `java.util.zip.CRC32`), được Flyway đọc tuần tự theo từng dòng văn bản của file migration và lưu vào cột `checksum` của `flyway_schema_history`. Mục đích của nó là kiểm tra tính toàn vẹn và bất biến của script (fail-fast nếu ai đó sửa file đã deploy).
  - **SHA-256 trong evidence:** Là chuỗi băm mật mã 256-bit (tính trên toàn bộ raw bytes của file vật lý). Đây là công cụ độc lập của bộ test/evidence harness nhằm chứng minh file migration hoàn toàn không bị sửa đổi hay vá víu tạm thời trong suốt các phase kiểm thử (trước khi fail, sau khi repair và khi retry).

> **Trả lời của học viên:**
> `[AWAITING_CANDIDATE]`
