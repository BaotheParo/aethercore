# M2-E1A: Market Purchase Concurrency & Lost Update Experiment

**Module:** M2 — Database & Concurrency  
**Lab Code:** M2-E1A  
**Chủ đề:** Stale read-then-write (Lost Update), Atomic Conditional UPDATE, Transaction Boundaries với Spring `TransactionTemplate` trên PostgreSQL READ COMMITTED.  
**Trạng thái kiểm chứng Runtime:** `VERIFIED` trên container PostgreSQL 16.15 thật (Docker Desktop 29.5.3 via Testcontainers 1.21.4).

---

## 1. Nghiệp vụ, Schema & Invariant

### 1.1. Invariant hệ thống
Đối với mọi chuỗi transaction đã commit, hệ thống bảo đảm bất biến toàn vẹn:

$$\text{stock}_{\text{cuối}} + \sum \text{quantity}_{\text{purchase đã commit}} = \text{stock}_{\text{ban đầu}}$$

Đồng thời:
- $\text{available} \ge 0$ (được bảo vệ ở tầng DB bằng CHECK constraint).
- Mỗi kết quả `SUCCESS` trả về trong một transaction commit thành công tương ứng với đúng một bản ghi trong `market_purchase`.

### 1.2. Schema (`V3__create_market_tables.sql`)
```sql
CREATE TABLE IF NOT EXISTS market_stock (
    sku VARCHAR(64) PRIMARY KEY,
    available BIGINT NOT NULL CHECK (available >= 0)
);

CREATE TABLE IF NOT EXISTS market_purchase (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL REFERENCES market_stock(sku),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

> **Lưu ý về ràng buộc CHECK:** Ràng buộc `CHECK (available >= 0)` được engine database đánh giá khi thực thi câu lệnh ghi/cập nhật dòng vào DB. Ràng buộc này ngăn chặn các giá trị âm, nhưng **hoàn toàn bất lực** trước lỗi Lost Update (ghi đè một giá trị không âm cũ, ví dụ ghi đè 0 lên 0).

---

## 2. Bảng Đối Chiếu Bằng Chứng Thực Nghiệm (Expected vs Observed)

*Toàn bộ các ca kiểm thử dưới đây đã chạy thực tế trên PostgreSQL 16.15 container (`MarketPurchaseConcurrencyTest` — 9/9 PASS):*

| Kịch bản kiểm thử | Stock đầu | Yêu cầu mua | Kết quả thiết kế (Expected) | Kết quả đo thực tế (Observed trên PostgreSQL 16.15) | Trạng thái Bất biến |
|---|:---:|:---:|---|---|:---:|
| **Broken Service** (Lost Update) | 1 | 2 workers (qty 1) | Cả hai commit: stock = 0, count = 2, sum = 2. | **Xác nhận 100%:** Hai worker có PID khác nhau (`pg_backend_pid`), isolation `read committed`, `lock_timeout=5s`, `statement_timeout=10s`. Cả 2 cùng đọc `available=1`, cùng ghi đè 0. Stock cuối: 0, 2 purchases, tổng qty: 2. | **VI PHẠM**: $0 + 2 = 2 \ne 1$ (Tái hiện lỗi Lost update thành công) |
| **Fixed Service** (Atomic Conditional) | 1 | 2 workers (qty 1) | 1 SUCCESS, 1 NOT_AVAILABLE: stock = 0, count = 1, sum = 1. | **Xác nhận 100%:** Đúng 1 worker nhận SUCCESS, 1 worker nhận NOT_AVAILABLE. Stock cuối: 0, 1 purchase, tổng qty: 1. | **BẢO TOÀN**: $0 + 1 = 1$ |
| **Sequential Units** | 5 | 2 đơn (qty 2, qty 3) | 2 SUCCESS: stock = 0, count = 2, sum = 5. | **Xác nhận 100%:** Đơn 1 trừ còn 3, đơn 2 trừ còn 0. Stock cuối: 0, 2 purchases, tổng qty: 5. | **BẢO TOÀN**: $0 + 5 = 5$ |
| **Invalid Quantity** | 10 | Qty 0 và Qty -5 | Ném `IllegalArgumentException`, DB không đổi. | **Xác nhận 100%:** Ném đúng exception; stock vẫn 10, count vẫn 0. | **BẢO TOÀN** |
| **Missing SKU / Insufficient** | 2 | Mua 5, hoặc SKU không tồn tại | Trả về `PurchaseResult.NOT_AVAILABLE`. | **Xác nhận 100%:** Trả về NOT_AVAILABLE; stock vẫn 2, count vẫn 0. | **BẢO TOÀN** |
| **Rollback Fault Injection** | 2 | 2 lần cùng UUID (qty 1) | Lần 2 lỗi PK; rollback khôi phục stock = 1, count = 1, sum = 1. | **Xác nhận 100%:** Lần 2 ném `DuplicateKeyException` với `SQLException` chứa SQLSTATE `"23505"`. Stock sau rollback vẫn là 1, count = 1, sum = 1. | **BẢO TOÀN**: $1 + 1 = 2$ |
| **Concurrent Load (20 attempts)** | 5 | 20 buyers (qty 1, pool 4) | 5 SUCCESS, 15 NOT_AVAILABLE; stock = 0, count = 5, sum = 5. | **Xác nhận 100%:** 5 worker nhận SUCCESS, 15 worker nhận NOT_AVAILABLE. Stock cuối: 0, 5 purchases, tổng qty: 5. Executor shutdown & terminate an toàn. | **BẢO TOÀN**: $0 + 5 = 5$ |
| **Outer Transaction Rollback** | 5 | 1 đơn (qty 2) trong outer TX | Inner trả SUCCESS, outer rollback $ightarrow$ stock về 5, count = 0, sum = 0. | **Xác nhận 100%:** Trong outer TX stock là 3; sau khi outer TX rollback, stock khôi phục về 5, 0 purchase. | **BẢO TOÀN**: $5 + 0 = 5$ |
| **Session Timeouts** | - | SHOW timeouts | `lock_timeout='5s'`, `statement_timeout='10s'`. | **Xác nhận 100%:** Cả trên luồng JUnit lẫn worker transaction đều có timeout 5s / 10s. | **ĐẠT** |

---

## 3. Ranh giới Transaction & Hợp đồng SUCCESS

### 3.1. Spring `TransactionTemplate` & Propagation
Service sử dụng `TransactionTemplate` với cấu hình tường minh:
```java
this.transactionTemplate = new TransactionTemplate(transactionManager);
this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
```

### 3.2. Hợp đồng SUCCESS và Transaction bên ngoài
- **Khi caller không có transaction:** `TransactionTemplate` mở transaction mới. Kết quả `PurchaseResult.SUCCESS` chỉ được trả về sau khi lệnh decrement và insert đã hoàn tất và transaction do template sở hữu đã commit thành công.
- **Khi caller đã có transaction (REQUIRED):** `FixedMarketPurchaseService` tham gia vào transaction của caller. Lúc này, `SUCCESS` chỉ xác nhận rằng thao tác trừ kho và insert purchase đã diễn ra hợp lệ trong transaction hiện tại. **Nếu outer transaction sau đó gặp lỗi và rollback, toàn bộ giao dịch mua hàng này cũng sẽ bị rollback theo.**
- **NOT_AVAILABLE:** Được trả về khi `affected rows == 0` (hết hàng hoặc sai SKU). Transaction kết thúc bình thường mà không ghi thêm dữ liệu (không bắt buộc phải rollback).

---

## 4. Câu hỏi Oral Interview & Đáp án tham khảo

### Câu 1: Vì sao có transaction mà read–then–write vẫn sai ở READ COMMITTED?
*Đáp án tham khảo:*
Mức cô lập READ COMMITTED chỉ cam kết không đọc dữ liệu chưa commit (no dirty read) và bảo vệ atomic write cho từng câu lệnh đơn lẻ. Tuy nhiên, mỗi câu lệnh `SELECT` trong một transaction ở mức READ COMMITTED tạo một snapshot mới tại thời điểm câu lệnh đó thực thi. Nếu hai transaction cùng đọc snapshot cũ (ví dụ stock = 1), sau đó tính toán trong bộ nhớ Java ($1 - 1 = 0$) rồi ghi đè (`UPDATE available = 0`), transaction sau sẽ ghi đè kết quả của transaction trước (Lost Update). Transaction không tự động khóa dòng đã đọc nếu không có `FOR UPDATE`.

### Câu 2: Vì sao CHECK stock >= 0 không phát hiện broken case này?
*Đáp án tham khảo:*
Ràng buộc `CHECK (available >= 0)` là một constraint kiểm tra giá trị của cột tại thời điểm ghi/cập nhật dòng đó vào DB. Trong kịch bản broken, worker 1 ghi giá trị $0 \ge 0$ (hợp lệ), worker 2 sau đó cũng ghi giá trị $0 \ge 0$ (vẫn hợp lệ). DB chỉ thấy hai lần ghi giá trị 0 hợp lệ, hoàn toàn không biết rằng logic ứng dụng đã bán vượt quá 1 sản phẩm do lost update.

### Câu 3: Điều kiện WHERE và affected rows giúp quyết định bán như thế nào?
*Đáp án tham khảo:*
Câu lệnh `UPDATE market_stock SET available = available - ? WHERE sku = ? AND available >= ?` thực hiện kiểm tra và trừ tồn kho nguyên tử ngay trong engine database dưới row-level exclusive lock. Ở READ COMMITTED của PostgreSQL, khi transaction thứ hai chờ và lấy được lock của dòng, nó sẽ đọc phiên bản mới nhất vừa commit của dòng đó để đánh giá lại mệnh đề `WHERE`. Nếu tồn kho không còn đủ ($available < quantity$), mệnh đề `WHERE` trả về false và số dòng bị ảnh hưởng (`affected rows`) bằng 0. Ứng dụng dựa vào `affected rows == 1` để biết việc trừ kho thành công hay thất bại mà không cần SELECT trước.

### Câu 4: Vì sao UPDATE kho và INSERT purchase phải cùng transaction?
*Đáp án tham khảo:*
Để bảo đảm tính nguyên tử (Atomicity) theo ACID. Nếu trừ kho thành công nhưng việc insert purchase bị lỗi (ví dụ lỗi mạng, trùng khóa, sập server) mà hai thao tác nằm ở hai transaction riêng biệt, kho sẽ bị trừ vĩnh viễn mà không có hóa đơn chứng từ nào được ghi nhận. Ngược lại, nếu nằm chung transaction, khi insert purchase thất bại, toàn bộ thao tác trừ kho sẽ được rollback về trạng thái ban đầu.

### Câu 5: Barrier nên đặt ở đâu? Đặt sau UPDATE có thể khiến test treo ra sao?
*Đáp án tham khảo:*
Trong kịch bản tái hiện race condition, `CyclicBarrier` phải đặt **sau SELECT và trước UPDATE**. Nếu đặt sau UPDATE, worker thứ nhất sẽ chạy câu lệnh UPDATE và giữ row lock của dòng `market_stock`. Worker thứ hai khi gọi UPDATE sẽ bị PostgreSQL chặn (block) để chờ worker thứ nhất nhả lock (commit). Do worker 2 bị block ở tầng JDBC/DB và không bao giờ chạm tới điểm `barrier.await()`, worker 1 cũng sẽ chờ tại barrier cho đến khi timeout (hoặc gây deadlock nếu không có timeout).

### Câu 6: Test này đã và chưa chứng minh điều gì về hệ thống nhiều instance, idempotency và hiệu năng?
*Đáp án tham khảo:*
- **Đã chứng minh:** Chứng minh tính đúng đắn về mặt dữ liệu (concurrency correctness & ACID) của atomic conditional update và rollback trong một cơ sở dữ liệu PostgreSQL quan hệ duy nhất, độc lập với số lượng thread gọi vào trong cùng một instance.
- **Chưa chứng minh:** Chưa chứng minh tính lũy kế/idempotency của API khi client retry do timeout mạng; chưa chứng minh khả năng chịu tải hoặc đo đạc throughput/p99 dưới độ tranh chấp cao; và chưa giải quyết bài toán phân tán nhiều database sharding/partitioning.
