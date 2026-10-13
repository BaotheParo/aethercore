# Bài Tập M2-E6A: Thực Hành Transactional Idempotency & Phân Tích Ranh Giới Giao Dịch

**Mã bài tập:** M2-E6A-EXERCISE
**Mục tiêu:** Tự dự đoán và kiểm chứng các bất biến idempotency (replay, payload conflict, scope isolation, transaction rollback) và rèn luyện tư duy phản biện phỏng vấn kiến trúc backend.
**Trạng thái học viên:** `[AWAITING_CANDIDATE]`

---

## 1. Bài Tập Tự Code Duy Nhất (Hands-on Coding Exercise)

> [!IMPORTANT]
> **Quy định làm bài:**
> - Học viên tự viết mã nguồn kiểm thử và điền dự đoán của chính mình.
> - Tuyệt đối không sinh sẵn đáp án hoặc giải hộ vào tài liệu bài tập.
> - Toàn bộ các trường phản hồi của học viên ghi nhận trạng thái `[AWAITING_CANDIDATE]`.

### Kịch bản thực nghiệm:
Hệ thống khởi tạo với một mặt hàng:
- `SKU`: `"EXERCISE_ARMOR"`
- Số lượng tồn kho ban đầu: **`available = 8`**

Chuỗi 4 bước gọi dịch vụ tuần tự:
1. **Bước 1:** `Caller A` dùng `Key K` gửi lệnh mua số lượng `qty = 3`.
2. **Bước 2 (Retry):** `Caller A` dùng `Key K` gửi lại chính xác cùng lệnh mua (`qty = 3`).
3. **Bước 3 (Key Reuse với Payload khác):** `Caller A` dùng `Key K` gửi lệnh mua số lượng `qty = 2`.
4. **Bước 4 (Khác Caller):** `Caller B` dùng cùng `Key K` gửi lệnh mua số lượng `qty = 2`.

---

### Bảng Dự Đoán Của Học Viên (Trước Khi Chạy Test)

| Bước | Lệnh gửi | Kết quả dự đoán (Outcome) | Tồn kho sau bước (`available`) | Tổng đơn mua (`purchaseCount`) | Số bản ghi Idempotency | Ghi chú cơ chế kỹ thuật |
|:---:|---|:---:|:---:|:---:|:---:|---|
| **1** | Caller A / Key K / qty 3 | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **2** | Caller A / Key K / qty 3 | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **3** | Caller A / Key K / qty 2 | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| **4** | Caller B / Key K / qty 2 | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |

---

### Yêu Cầu Tự Code Integration Test (Mã Nguồn Của Học Viên)
Học viên viết test method `@Test void testExerciseFourSteps()` để kiểm chứng dự đoán trên:

```java
// [AWAITING_CANDIDATE: Mã nguồn test kiểm chứng của học viên]
```

### Câu Hỏi Phân Tích Logic:
1. *Tại sao ở Bước 3 hệ thống ném ngoại lệ xung đột (`IdempotencyConflictException`) nhưng không làm thay đổi trạng thái tồn kho hoặc đơn hàng đã tạo ở Bước 1?*
   - **Phân tích của học viên:** `[AWAITING_CANDIDATE]`

2. *Tại sao ở Bước 4 cùng dùng `Key K` nhưng `Caller B` vẫn mua thành công và tạo ra một `purchase_id` hoàn toàn mới?*
   - **Phân tích của học viên:** `[AWAITING_CANDIDATE]`

---

## 2. Ba Câu Hỏi Phỏng Vấn Chuyên Sâu (Oral Interview Questions — 60–90 giây)

### Câu 1: Cơ chế chờ khóa (Lock Wait) giữa hai transaction đồng thời (ID3)
> *"Khi hai request cùng mang một idempotency key gửi đến server gần như đồng thời, tại sao Worker B lại bị chặn lại ở cấp cơ sở dữ liệu thay vì trả về lỗi ngay? Làm thế nào chúng ta chứng minh được điều này bằng `pg_blocking_pids` trong PostgreSQL?"*

- **Gợi ý tư duy:** Phân tích cơ chế Unique Index check trong PostgreSQL khi transaction của Worker A chưa commit; ý nghĩa của hàm `pg_blocking_pids(B_pid)`.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 2: Phân biệt mất phản hồi sau commit (ID1) và Outer Transaction Rollback (ID7)
> *"Tại sao ở cả hai kịch bản ID1 và ID7, method của service đều có lúc trả về `PurchaseResult.SUCCESS`, nhưng ở ID1 dữ liệu đã bền vững vĩnh viễn còn ở ID7 toàn bộ thay đổi bị biến mất? Ranh giới nào quyết định tính durability?"*

- **Gợi ý tư duy:** Phân biệt ranh giới commit của transaction nội bộ vs transaction ngoài cùng dưới cơ chế `PROPAGATION_REQUIRED`.
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`

---

### Câu 3: Phản biện nhận định "Có Idempotency Key là đạt Exactly-Once Processing"
> *"Một kỹ sư nói rằng: 'Chỉ cần gắn idempotency key vào request và lưu kết quả vào PostgreSQL là hệ thống đã giải quyết triệt để bài toán exactly-once processing'. Bạn hãy phản biện nhận định này bằng các ranh giới thất bại ngoài cơ sở dữ liệu."*

- **Gợi ý tư duy:** Nêu rõ các ranh giới: process crash khi đang ở trạng thái `PROCESSING`, các tác vụ non-transactional (gửi email, gọi payment API bên thứ ba, phát event ra Kafka), và bài toán giải phóng bộ nhớ (retention window / TTL).
- **Trả lời của học viên:**
  `[AWAITING_CANDIDATE]`
