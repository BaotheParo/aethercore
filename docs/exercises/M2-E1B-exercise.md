# M2-E1B EXERCISE: CONCURRENCY STRATEGIES & LOCKING CONTROL

**Học phần:** M2 — Database & Concurrency  
**Lab:** M2-E1B (Optimistic vs Pessimistic vs Atomic Conditional Update)  
**Tài liệu tham chiếu:** `docs/experiments/M2-E1B.md`, `MarketLockingConcurrencyTest.java`

---

## Phần 1: Câu hỏi Phỏng vấn & Kiến trúc (Conceptual Analysis)

### Câu 1: So sánh Tradeoffs của 3 chiến lược
Khi xử lý mua hàng/trừ tồn kho trong hệ thống e-commerce quy mô lớn:
- Khi nào bạn nên chọn **Atomic Conditional UPDATE**?
- Khi nào bắt buộc phải dùng **Pessimistic Locking (`SELECT ... FOR UPDATE`)**?
- Khi nào **Optimistic Locking (OCC)** là lựa chọn tối ưu, và rủi ro lớn nhất của nó là gì?

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Câu 2: Cơ chế Version Check và Livelock / Thundering Herd
Trong Optimistic Locking:
1. Tại sao nếu chỉ retry tức thì (`while(true) retry()`) khi gặp `StockVersionConflictException`, hệ thống có thể bị rơi vào hiện tượng livelock (retry storms)?
2. Công thức **Exponential Backoff with Full Jitter** giải quyết vấn đề này như thế nào?

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Câu 3: PostgreSQL Locking Internals & `pg_blocking_pids`
1. Khi câu lệnh `SELECT ... FOR UPDATE` chạy trên PostgreSQL, loại lock nào được tạo ra trên bảng và trên tuple?
2. Hàm `pg_blocking_pids(pid)` hoạt động như thế nào trong PostgreSQL engine để phát hiện tiến trình đang giữ lock?
3. Nếu hai giao dịch cùng lock 2 SKU A và B nhưng theo thứ tự ngược nhau (TX1: A -> B, TX2: B -> A), PostgreSQL sẽ xử lý như thế nào và timeout cấu hình nào sẽ can thiệp?

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

## Phần 2: Bài tập Thực hành (Hands-on Challenge)

### Bài tập 1: Batch Purchasing & Lock Ordering
Giả sử người chơi mua giỏ hàng gồm 3 SKU: `["SWORD", "SHIELD", "POTION"]` cùng lúc.
Nếu dùng Pessimistic Locking:
- Làm thế nào để đảm bảo không bao giờ xảy ra **Deadlock** giữa các người chơi cùng mua các tổ hợp mặt hàng giống hoặc giao nhau?
- Viết mã giả hoặc giải thích giải thuật sắp xếp khóa (Lock Ordering Invariant).

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Bài tập 2: Connection Pool Sizing & Pessimistic Lock Duration
Nếu HikariCP có `maximum-pool-size = 10`, nhưng có 50 yêu cầu đồng thời gọi vào service dùng `PessimisticMarketPurchaseService` với logic nghiệp vụ tốn 200ms bên trong transaction:
- Điều gì sẽ xảy ra với connection pool và thời gian phản hồi (latency) của toàn bộ ứng dụng?
- So sánh hiện tượng này với việc dùng `Atomic Conditional UPDATE`.

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Bài tập 3: Hands-on Challenge: Stock 5, Version 0 — Two Buyers Qty 2
**Tình huống thực nghiệm:**
- SKU `ELIXIR` khởi tạo với `available = 5, version = 0`.
- Hai buyer (Buyer 1 và Buyer 2) cùng đọc snapshot (`available = 5, version = 0`).
- Mỗi người muốn mua số lượng `qty = 2`.

**Yêu cầu người học tự thực hiện (Không có lời giải điền sẵn):**
1. **Giai đoạn 1 (Chạy Optimistic không retry):**
   - Buyer 1 commit trước.
   - Buyer 2 cố gắng commit với snapshot cũ.
   - Dự đoán chính xác: Trạng thái kết quả của Buyer 2? Giá trị `available`, `version`, số bản ghi purchase và `SUM(quantity)` trong DB lúc này?
2. **Giai đoạn 2 (Retry bằng transaction mới độc lập):**
   - Buyer 2 bắt lỗi conflict và mở transaction mới độc lập để thử lại.
   - Dự đoán: Snapshot Buyer 2 đọc được ở lần 2? Trạng thái `available`, `version`, số bản ghi purchase và `SUM(quantity)` sau khi Buyer 2 hoàn tất retry?
3. **Đối chiếu với Pessimistic Flow:**
   - Nếu cả 2 buyer dùng `PessimisticMarketPurchaseService` (`SELECT ... FOR UPDATE`), dòng thời gian diễn ra như thế nào?
   - Vì sao số lượng order (`COUNT(*)`) lại khác với tổng số lượng hàng bán ra (`SUM(quantity)`)?

**Dự đoán và Lời giải của ứng viên:**
`[AWAITING_CANDIDATE]`

---

## Phần 3: Bộ 3 Câu hỏi Phỏng vấn Oral (60–90 giây)

1. *“Optimistic locking có thật sự không khóa gì trong Database engine không?”*  
   **Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

2. *“SELECT FOR UPDATE xong rồi commit transaction ngay, sau đó mới trừ stock trong transaction khác: Bạn còn được bảo vệ không?”*  
   **Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

3. *“Nếu một service thứ ba quên tăng version (hoặc UPDATE trực tiếp không check version) thì các service còn lại dùng Optimistic Locking có bị ảnh hưởng không?”*  
   **Ứng viên trả lời:** `[AWAITING_CANDIDATE]`
