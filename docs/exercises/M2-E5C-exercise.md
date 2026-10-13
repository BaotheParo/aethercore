# Bài Tập M2-E5C: Thực Hành LIKE, Wildcard Escaping, lower() và Expression Index

**Mã bài tập:** M2-E5C-EXERCISE
**Mục tiêu:** Nắm vững cơ chế tìm kiếm tiền tố và chứa chuỗi, phân biệt tham số hóa (parameter binding) với phân tích ký tự đại diện (wildcard interpretation), thiết kế Expression Index và đọc hiểu kế hoạch thực thi EXPLAIN.
**Trạng thái học viên:** `[AWAITING_CANDIDATE]`

---

## 1. Ngữ Cảnh và Dữ Liệu Thử Nghiệm Riêng (Dedicated Fixture)

Để rèn luyện tư duy kỹ thuật độc lập với bài lab chính, học viên làm việc trên một schema riêng biệt mô phỏng hệ thống mã bang hội và tag người chơi:

```sql
CREATE TABLE IF NOT EXISTS exercise_guild_tag (
    id         BIGINT PRIMARY KEY,
    guild_code TEXT COLLATE "C" NOT NULL,
    payload    TEXT NOT NULL
);

-- Dữ liệu thử nghiệm chứa cả mixed-case và các ký tự literal '%' và '_' trong chuỗi:
INSERT INTO exercise_guild_tag (id, guild_code, payload) VALUES
(1,  'PROMO_100%_ACTIVE',    'Promo tag with literal percent and underscore'),
(2,  'PROMO_1000_ACTIVE',    'Promo tag without percent'),
(3,  'PROMO_100_ACTIVE',     'Promo tag with underscore but no percent'),
(4,  'DEV_TEST_01',          'Developer test 1 with underscore'),
(5,  'DEV%TEST%01',          'Developer test with literal percent'),
(6,  'ALPHA_V1_0',           'Alpha upper case'),
(7,  'alpha_v1_0',           'Alpha lower case'),
(8,  'AlPhA_V1_0',           'Alpha mixed case'),
(9,  'BETA_NORMAL',          'Standard tag'),
(10, 'OMEGA_SPECIAL_%',      'Omega tag with trailing literal percent');
```

---

## 2. Bài Tập Tự Code (Hands-on Self-Coding Exercise)

> [!IMPORTANT]
> **Quy định làm bài:**
> - Học viên tự viết mã nguồn Java/SQL, tự chạy và kiểm chứng kết quả.
> - Tuyệt đối không sinh sẵn lời giải tự động hoặc hardcode danh sách kết quả.
> - Toàn bộ phần phản hồi của học viên ghi nhận trạng thái `[AWAITING_CANDIDATE]`.

### Yêu cầu 1: Phân biệt Parameter Binding vs Wildcard Interpretation
Một ứng dụng web nhận input từ người dùng qua ô tìm kiếm: chuỗi ký tự `"PROMO_100%_"`.
Lập trình viên sử dụng JDBC PreparedStatement:
```java
PreparedStatement ps = conn.prepareStatement("SELECT id FROM exercise_guild_tag WHERE guild_code LIKE ?");
ps.setString(1, userInput + "%");
```
- **Câu hỏi:** Việc bind tham số qua `setString()` có tự động biến các ký tự `%` và `_` trong `userInput` thành ký tự thông thường (literal) không?
- **Phân tích của học viên:**
  `[AWAITING_CANDIDATE]`

### Yêu cầu 2: Xây dựng Cơ chế Escape Ký Tự Đại Diện và Query An Toàn
- Viết một phương thức helper trong Java để escape các ký tự đặc biệt của LIKE (`%`, `_`, `\\`).
- Viết câu lệnh SQL có mệnh đề `ESCAPE` tường minh để tìm chính xác các tag **bắt đầu bằng chuỗi literal** mà người dùng nhập (ví dụ: chuỗi literal `"PROMO_100%_"`).
- **Mã nguồn giải pháp của học viên:**
  `[AWAITING_CANDIDATE]`

### Yêu cầu 3: Xác định Oracle Kết Quả Trước Khi Chạy
- Dựa trên fixture 10 dòng ở Mục 1, nếu người dùng nhập literal prefix `"PROMO_100%_"`, câu truy vấn đúng phải trả về những IDs nào? Nếu không escape, câu truy vấn sẽ trả về những IDs nào?
- **Dự đoán và kiểm chứng của học viên:**
  `[AWAITING_CANDIDATE]`

### Yêu cầu 4: Đề xuất Index và Giải thích Giới hạn Locale
- Đề xuất câu lệnh tạo index tối ưu cho khế ước tìm kiếm tiền tố trên.
- Nếu bảng `exercise_guild_tag` chạy trên môi trường production với collation `vi_VN.UTF-8` hoặc `en_US.UTF-8`, index B-tree thông thường có hoạt động hiệu quả cho toán tử `LIKE` không? Cần sử dụng cấu hình gì trong PostgreSQL?
- **Giải pháp của học viên:**
  `[AWAITING_CANDIDATE]`

### Yêu cầu 5: Đọc và Phân Tích Kế Hoạch EXPLAIN ANALYZE
- Chạy `EXPLAIN (ANALYZE, BUFFERS)` cho câu truy vấn tìm kiếm không phân biệt hoa thường:
  ```sql
  SELECT id FROM exercise_guild_tag WHERE lower(guild_code) = 'alpha_v1_0';
  ```
- Hãy phân tích các thông số `Actual Rows`, `Rows Removed by Filter`, `Shared Hit Blocks` trước và sau khi có Expression Index.
- **Phân tích của học viên:**
  `[AWAITING_CANDIDATE]`

---

## 3. Sáu Câu Hỏi Phỏng Vấn Chuyên Sâu (Oral Interview Questions)

Học viên trả lời trực tiếp từng câu hỏi dưới góc nhìn của một kỹ sư Backend chuyên sâu:

### Câu 1: Có Index Scan trong plan đã đủ kết luận query được tối ưu chưa?
- **Gợi ý tư duy:** Xem xét trường hợp Index Scan duyệt qua toàn bộ cây index nhưng lọc bỏ phần lớn kết quả bằng Filter, hoặc chi phí Random I/O trên heap table so với Sequential Scan.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 2: Prefix LIKE và contains LIKE khác yêu cầu nghiệp vụ và khoảng tìm kiếm như thế nào?
- **Gợi ý tư duy:** Dùng mô hình danh bạ điện thoại để giải thích về khoảng tìm kiếm có cận `[A, B)` so với việc không có điểm tựa định vị.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 3: Tại sao index trên `display_name` có thể không giúp lookup `lower(display_name)`?
- **Gợi ý tư duy:** Cấu trúc khóa lưu trong cây B-tree là giá trị gốc hay giá trị sau hàm? Database engine phải làm gì để so sánh khi không có expression index?
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 4: Bỏ `lower()` khỏi mệnh đề `WHERE` có giữ nguyên kết quả không? Hãy dùng fixture của lab để phản biện.
- **Gợi ý tư duy:** Nêu rõ số lượng dòng trả về (2 dòng vs 0 dòng) trên bản ghi có chữ hoa (`Redstone-050000`) và mixed-case (`rEdStOnE-050000`).
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 5: Collation và operator class có liên quan gì đến khả năng dùng B-tree cho LIKE?
- **Gợi ý tư duy:** Phân biệt Collation `"C"` (so sánh byte ASCII) với linguistic collations (quy tắc ngôn ngữ), và vai trò của operator class `text_pattern_ops`.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 6: Expression index mang lại lợi ích đọc và chi phí ghi nào? Số liệu nào trong bài lab chưa đo?
- **Gợi ý tư duy:** Lợi ích chuyển đổi từ Seq Scan sang Index Seek; chi phí tính toán hàm và ghi cây index trên mỗi thao tác DML; giới hạn bài lab chưa có write latency benchmark.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`