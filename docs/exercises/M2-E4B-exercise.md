# M2-E4B-EXERCISE: Bài tập Tự Code — Collection Fetch & Two-Step Pagination

**Mục tiêu:** Tự triển khai kịch bản phân trang an toàn trên thực thể có quan hệ một-nhiều (One-to-Many), kiểm soát chính xác số lượng prepared statements và entity materialization, xử lý trường hợp entity không có bản ghi con.

---

## Phần 1: Dự đoán Thực nghiệm trên Fixture Mới

Cho cơ sở dữ liệu với 6 buyers (ID 1 đến 6) đều có tên `"Trader"`, số lượng đơn hàng tương ứng với từng buyer là:
- Buyer 1: 0 orders
- Buyer 2: 2 orders
- Buyer 3: 4 orders
- Buyer 4: 1 order
- Buyer 5: 0 orders
- Buyer 6: 3 orders

Hệ thống yêu cầu lấy trang dữ liệu với tham số: **`offset = 3`**, **`pageSize = 3`**, sắp xếp theo `name ASC, id ASC`.

### Bảng Dự đoán (Dành cho Học viên)

| Chỉ số cần dự đoán | Giá trị dự đoán | Giải thích cơ chế kỹ thuật |
|:---|:---:|:---|
| **Danh sách Buyer IDs trả về của trang** | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **Tổng số orders thuộc về trang này** | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **Số Prepared Statements phát sinh (Thuật toán Hai bước)** | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **Số lượng Buyer entities được nạp vào Session** | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **Số lượng Order entities được nạp vào Session** | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **Nếu dùng 1 câu HQL fetch join + limit (Lenient policy)**: Số entities bị nạp vào RAM là bao nhiêu? | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |

---

## Phần 2: Yêu cầu Tự Viết Code Kiểm thử

Học viên tự viết một phương thức test hoàn chỉnh đáp ứng các yêu cầu sau:
1. **Seed fixture 6 buyers** như trên bằng JDBC trước khi chạy.
2. Áp dụng thuật toán phân trang hai bước (`BuyerPageReader` hoặc tự viết logic tương đương).
3. **Assert kết quả nghiệp vụ:**
   - Trang trả về đủ 3 buyers theo đúng thứ tự ID.
   - Buyer có 0 orders vẫn xuất hiện trong trang và có collection rỗng.
   - Tổng số đơn hàng trên trang khớp với dự đoán.
4. **Assert metrics Hibernate Statistics:**
   - Prepared statement count.
   - Entity load counts của Buyer và Order.
5. **Mở rộng hợp đồng (Câu hỏi thiết kế):**
   - Nếu phía Client yêu cầu hiển thị tổng số bản ghi (`totalElements`) và tổng số trang (`totalPages`), bạn cần thêm câu query nào? Số lượng statement tăng lên bao nhiêu và cần lưu ý điều gì về performance của câu count?
   - `[AWAITING_CANDIDATE: Học viên điền câu trả lời thiết kế tại đây]`

### Không gian nộp code của Học viên:
```java
// [AWAITING_CANDIDATE: Học viên tự viết mã nguồn test tại đây]
```

---

## Phần 3: Phỏng vấn Nhanh (Oral Defense — 60 đến 90 giây mỗi câu)

1. **Một câu query HQL trả về đúng danh sách 2 buyers trên trang: Vì sao hệ thống vẫn có thể đang âm thầm tải toàn bộ hàng triệu dòng dữ liệu của cả bảng vào RAM?**
2. **Vì sao ta không thể đơn giản đặt `setFirstResult(offset)` và `setMaxResults(size)` trực tiếp lên câu truy vấn fetch collection ở bước thứ hai?**
3. **Tại sao trong kịch bản này bắt buộc phải sử dụng `LEFT JOIN FETCH` thay vì `INNER JOIN FETCH`?**
4. **Vì sao mệnh đề `WHERE b.id IN (:ids)` trong SQL không bảo đảm các dòng trả về theo đúng thứ tự mảng `:ids` truyền vào? Làm thế nào để giải quyết triệt để vấn đề này?**
5. **Hai câu lệnh SELECT trong cùng một transaction ở mức cô lập mặc định (READ COMMITTED) có luôn luôn nhìn thấy cùng một snapshot dữ liệu không? Kịch bản nào có thể gây sai lệch?**
6. **Giả sử một khách hàng trong trang có 1.000.000 đơn hàng lịch sử: Phương án phân trang hai bước này còn tồn tại rủi ro gì và hướng khắc phục là gì?**

---

## Phần 4: Khung Đánh giá (Rubric dành cho Mentor)

*Không tự điền điểm. Khung chấm dùng khi mentor phỏng vấn học viên:*

| Mức điểm | Tiêu chí đánh giá |
|:---:|:---|
| **0** | Nhầm lẫn giữa phân trang Database và phân trang bộ nhớ; không phân biệt được số rows của SQL JOIN và số root entity. |
| **1** | Nêu đúng hiện tượng in-memory pagination và cảnh báo HHH000104, nhưng chưa giải thích được vì sao SQL không thể mang mệnh đề LIMIT khi JOIN collection. |
| **2** | Giải thích chính xác luồng 2 bước, hiểu tại sao phải dùng ID query có database limit, vì sao cần LEFT JOIN và code khôi phục thứ tự IN predicate. |
| **3** | Phân tích sâu sắc các ranh giới thực tế: Non-repeatable read giữa 2 câu SELECT trong READ COMMITTED, rủi ro con quá lớn (unbounded child collection), và hướng tiếp cận subselect / batch fetching hoặc keyset pagination. |
