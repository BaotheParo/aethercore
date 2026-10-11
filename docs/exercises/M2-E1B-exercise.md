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