# Bài tập Thực hành M2-E2A: Tranh Chấp Khóa 3 Hàng & Chiến Lược Retry Toàn Transaction

**Mã bài tập:** M2-E2A-EX  
**Mục tiêu:** Nâng cấp từ mô hình 2 hàng lên mô hình 3 hàng ([1, 2, 3]), rèn luyện kỹ năng phân tích chu trình đồ thị chờ (Wait-for Graph), chuẩn hóa thứ tự khóa (Lock Ordering) khi nhận input không theo thứ tự, và thiết kế cơ chế retry toàn vẹn transaction.

---

## 1. Tình huống Nghiệp vụ (Business Challenge)

Hệ thống quản lý kho đồ chơi đa người chơi cần cập nhật đồng thời số lượng của 3 loại vật phẩm (Item IDs: 1, 2, 3) trong cùng một giao dịch chuyển đổi tài sản.
- Caller 1 gửi payload yêu cầu cập nhật theo thứ tự: `[3, 1, 2]`.
- Caller 2 gửi payload yêu cầu cập nhật theo thứ tự: `[2, 3, 1]`.
- Mỗi logical operation hợp lệ phải:
  1. Ghi nhận một marker `operation_id` (UUID).
  2. Tăng số lượng của từng ID đúng một lần.
  3. Commit trong cùng một transaction.

**Tính bất biến sau khi tất cả transaction kết thúc:**
$$\text{value}(1) = \text{value}(2) = \text{value}(3) = \text{Số operations đã commit}$$

---

## 2. Nhiệm vụ Tự Code Dành Cho Ứng Viên (Candidate Tasks)

### Nhiệm vụ 1: Dự đoán chu trình chờ khóa (Wait-for Cycle)
Nếu hai worker thực thi cập nhật các hàng theo đúng thứ tự input mà client gửi lên (`[3, 1, 2]` vs `[2, 3, 1]`):
- Hãy vẽ chu trình đồ thị chờ có thể xảy ra giữa hai Transaction.
- Transaction nào giữ khóa gì và đang chờ khóa gì?

*Trả lời của ứng viên:* `[AWAITING_CANDIDATE]`

---

### Nhiệm vụ 2: Chuẩn hóa thứ tự khóa và xử lý input trùng lặp
Viết phương thức tiền xử lý danh sách item IDs trước khi thực thi SQL:
- Sắp xếp thứ tự các ID tăng dần (ví dụ: `Collections.sort(ids)` hoặc `.distinct().sorted()`).
- Nếu danh sách input chứa ID trùng lặp (ví dụ: `[1, 2, 1]`), bạn xử lý như thế nào để tránh khóa 2 lần cùng một hàng trong cùng transaction?

```java
// TODO [CANDIDATE]: Viết mã chuẩn hóa danh sách IDs
public List<Integer> normalizeItemIds(List<Integer> rawItemIds) {
    // [AWAITING_CANDIDATE]
    return null;
}
```

---

### Nhiệm vụ 3: Thiết kế kiểm thử tính bất biến (Test Invariant)
Viết đoạn mã kiểm tra (assertion) đảm bảo tính bất biến sau khi cả hai worker kết thúc (kể cả khi có retry):

```java
// TODO [CANDIDATE]: Viết assertion kiểm tra tính bất biến
public void assertFinalInvariant(JdbcTemplate jdbcTemplate, int expectedCommittedOperations) {
    // [AWAITING_CANDIDATE]
}
```

---

### Nhiệm vụ 4: Giải thích lý do không thể chỉ retry câu lệnh lỗi
Tại sao khi gặp ngoại lệ với mã `40P01` (deadlock detected), lập trình viên không thể chỉ `try-catch` và chạy lại câu lệnh `UPDATE` vừa bị lỗi?

*Trả lời của ứng viên:* `[AWAITING_CANDIDATE]`

---

## 3. Câu Hỏi Phỏng Vấn Nhanh (Oral Interview Questions — 60 đến 90 giây)

### Câu hỏi 1 (60s):
**Chứng cứ nào ở tầng hệ thống và cơ sở dữ liệu cho thấy transaction thất bại do PostgreSQL Deadlock thực sự chứ không phải do Timeout của Java Future?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 2 (60s):
**Vì sao transaction nạn nhân (Victim) đã thực hiện thành công câu lệnh `UPDATE` hàng đầu tiên trước khi bị deadlock, nhưng sau khi kết thúc, hàng đó lại không giữ thay đổi của nạn nhân?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 3 (60s):
**Tại sao việc sắp xếp danh sách ID tăng dần (Lock Ordering) giúp loại bỏ deadlock trong bài toán này, nhưng trong thực tế doanh nghiệp ta vẫn không thể khẳng định hệ thống sẽ 'không bao giờ bị deadlock'?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 4 (90s):
**Khi thực hiện retry một transaction thất bại, hai lần chạy có thể có cùng PostgreSQL Backend PID không? Làm thế nào bạn chứng minh được lần retry thực sự chạy trong một Transaction mới hoàn toàn?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

## 4. Bảng Đánh Giá Kỹ Năng Ứng Viên

| Tiêu chí | Mức độ mong đợi | Kết quả Ứng viên |
| :--- | :--- | :--- |
| **Nhận diện SQLSTATE 40P01** | Phân biệt chính xác với 55P03 (lock timeout) và 57014 (statement timeout) | `[AWAITING_CANDIDATE]` |
| **Hiểu tính Nguyên tử (Atomicity)** | Giải thích được toàn bộ thay đổi trước đó của victim bị rollback sạch | `[AWAITING_CANDIDATE]` |
| **Tư duy Lock Ordering** | Nắm vững giải pháp sort ID và hiểu giới hạn trong hệ thống nhiều bảng | `[AWAITING_CANDIDATE]` |
| **Xác thực Fresh Transaction** | Biết cách đo đạc `txid_current()` độc lập với connection pooling | `[AWAITING_CANDIDATE]` |
