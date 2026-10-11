# M2-E1A: Market Purchase Concurrency & Lost Update Experiment

**Module:** M2 — Database & Concurrency  
**Lab Code:** M2-E1A  
**Chủ đề:** Stale read-then-write (Lost Update), Atomic Conditional UPDATE, Transaction Boundaries với Spring `TransactionTemplate` trên PostgreSQL READ COMMITTED.  
**Trạng thái kiểm chứng Runtime:** `BLOCKED_RUNTIME` (Docker Desktop daemon chưa bật trên máy host Windows). Code và schema đã hoàn thiện 100% và vượt qua `mvn -pl backend-app -am test-compile`.

---

## 1. Nghiệp vụ, Schema & Invariant

### 1.1. Invariant hệ thống
Đối với mọi chuỗi transaction đã commit, hệ thống phải bảo đảm bất biến toàn vẹn:

$$\text{stock}_{\text{cuối}} + \sum \text{quantity}_{\text{purchase đã commit}} = \text{stock}_{\text{ban đầu}}$$

Đồng thời:
- $\text{available} \ge 0$ (được bảo vệ ở tầng DB bằng CHECK constraint).
- Mỗi kết quả `SUCCESS` trả về phải tương ứng với đúng một bản ghi trong `market_purchase`.

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

> **Lưu ý cốt lõi:** Ràng buộc `CHECK (available >= 0)` chỉ ngăn chặn giá trị âm được ghi vào DB. Ràng buộc này **hoàn toàn bất lực** trước lỗi Lost Update (ghi đè giá trị cũ không âm, ví dụ ghi đè 0 lên 0).

---

## 2. Ví dụ đời thường & Phân tích cơ chế

### 2.1. Phép ẩn dụ đời thường
Tưởng tượng một cửa hàng chỉ còn **1 món đồ duy nhất** trên kệ (stock = 1):
1. **Nhân viên A** nhìn vào sổ kho thấy ghi "Còn 1 món".
2. **Nhân viên B** gần như cùng lúc nhìn vào sổ kho cũng thấy ghi "Còn 1 món".
3. **Nhân viên A** bán cho Khách 1, nhẩm tính $1 - 1 = 0$, lấy bút ghi vào sổ: "Còn 0 món", xuất hóa đơn 1 cho Khách 1.
4. **Nhân viên B** (vẫn cầm snapshot cũ trong đầu là 1) bán cho Khách 2, nhẩm tính $1 - 1 = 0$, lấy bút ghi đè vào sổ: "Còn 0 món", xuất hóa đơn 2 cho Khách 2.

**Hậu quả:** Sổ kho vẫn ghi "Còn 0 món" (hợp lệ $\ge 0$), nhưng đã xuất ra **2 hóa đơn** cho chỉ 1 món hàng ban đầu! Bất biến $0 + 2 = 2 \ne 1$ bị phá vỡ hoàn toàn.

### 2.2. Ánh xạ kỹ thuật (Broken Schedule vs Fixed Schedule)

```
[Broken Schedule: Stale Read-then-Write]
Thread T1 (TX 1)                     Thread T2 (TX 2)                      PostgreSQL (READ COMMITTED)
----------------                     ----------------                      ---------------------------
SELECT available -> 1                                                      Snapshot đọc = 1
                                     SELECT available -> 1                 Snapshot đọc = 1
[CyclicBarrier Synchronization]      [CyclicBarrier Synchronization]
Java: 1 - 1 = 0                      Java: 1 - 1 = 0
UPDATE stock SET available = 0                                             Acquires Row Lock, ghi 0
INSERT purchase (ID1, qty 1)                                               Ghi purchase 1
COMMIT                                                                     Release Row Lock
                                     UPDATE stock SET available = 0        Acquires Row Lock, ghi đè 0!
                                     INSERT purchase (ID2, qty 1)          Ghi purchase 2
                                     COMMIT                                Release Row Lock
------------------------------------------------------------------------------------------------------
Kết quả: Stock = 0, Purchases = 2, SUM(quantity) = 2. Invariant bị vi phạm!
```

```
[Fixed Schedule: Atomic Conditional UPDATE]
Thread T1 (TX 1)                     Thread T2 (TX 2)                      PostgreSQL (READ COMMITTED)
----------------                     ----------------                      ---------------------------
UPDATE market_stock                  UPDATE market_stock
SET available = available - 1        SET available = available - 1
WHERE sku='RED' AND available >= 1;  WHERE sku='RED' AND available >= 1;
(Được cấp Row Lock trước)            (Bị block chờ Row Lock của T1)
Affected rows = 1                                                          Row updated: available = 0
INSERT purchase (ID1, qty 1)                                               
COMMIT                                                                     T1 commit, release lock!
                                     (T2 được đánh thức)
                                     PostgreSQL re-evaluates WHERE clause trên row version mới nhất!
                                     Row version mới có available = 0 < 1 => Condition FALSE!
                                     Affected rows = 0
                                     Rollback/No insert => Trả về NOT_AVAILABLE
------------------------------------------------------------------------------------------------------
Kết quả: Stock = 0, Purchases = 1, SUM(quantity) = 1. Invariant được bảo toàn!
```

---

## 3. Ranh giới Transaction & Thiết kế Implementation

### 3.1. Spring `TransactionTemplate`
Thay vì dùng `@Transactional` (dựa trên Spring AOP Proxy, dễ bị lỗi nuốt proxy/self-invocation hoặc cấu hình che giấu ranh giới), M2-E1A sử dụng `TransactionTemplate` với cấu hình tường minh:
```java
this.transactionTemplate = new TransactionTemplate(transactionManager);
this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
```

### 3.2. Mã nguồn triển khai
- **Fixed Service:** `FixedMarketPurchaseService.java`
  Thực hiện validation tham số ($quantity > 0$, null checks).
  Thực hiện atomic conditional update:
  `UPDATE market_stock SET available = available - ? WHERE sku = ? AND available >= ?`
  Nếu affected rows == 1: ghi log vào `market_purchase` trong cùng transaction, trả về `PurchaseResult.SUCCESS`.
  Nếu affected rows == 0: không insert, trả về `PurchaseResult.NOT_AVAILABLE`.
- **Broken Service:** `BrokenMarketPurchaseService.java` (chỉ nằm trong test source)
  Sử dụng `CyclicBarrier(2)` đặt ngay sau `SELECT available` và trước `UPDATE available = 0` để ép 2 connection đọc cùng giá trị snapshot ban đầu.

---

## 4. Trạng thái kiểm chứng & Bằng chứng thực thi

### 4.1. Kết quả kiểm tra
1. **Biên dịch mã nguồn:**
   - Lệnh: `mvn -pl backend-app -am test-compile`
   - Kết quả: `BUILD SUCCESS` (0 compiler warnings/errors).
2. **Thực thi trên Docker / PostgreSQL Testcontainers:**
   - Trạng thái: `BLOCKED_RUNTIME`
   - Nguyên nhân cụ thể: Docker Desktop engine chưa được bật trên máy Windows (`npipe:////./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`).
   - Lệnh chạy lại khi Docker sẵn sàng:
     ```bash
     mvn -pl backend-app -am "-Dtest=MarketPurchaseConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
     ```

---

## 5. Câu hỏi Oral Interview & Đáp án tham khảo

### Câu 1: Vì sao có transaction mà read–then–write vẫn sai ở READ COMMITTED?
*Đáp án tham khảo:*
Mức cô lập READ COMMITTED chỉ cam kết không đọc dữ liệu chưa commit (no dirty read) và bảo vệ atomic write cho từng câu lệnh đơn lẻ. Tuy nhiên, mỗi câu lệnh `SELECT` trong một transaction ở mức READ COMMITTED tạo một snapshot mới tại thời điểm câu lệnh đó thực thi. Nếu hai transaction cùng đọc snapshot cũ (ví dụ stock = 1), sau đó tính toán trong bộ nhớ Java ($1 - 1 = 0$) rồi ghi đè (`UPDATE available = 0`), transaction sau sẽ ghi đè kết quả của transaction trước (Lost Update). Transaction không tự động khóa dòng đã đọc nếu không có `FOR UPDATE`.

### Câu 2: Vì sao CHECK stock >= 0 không phát hiện broken case này?
*Đáp án tham khảo:*
Ràng buộc `CHECK (available >= 0)` là một constraint kiểm tra giá trị của cột tại thời điểm ghi dòng đó vào đĩa. Trong kịch bản broken, worker 1 ghi giá trị $0 \ge 0$ (hợp lệ), worker 2 sau đó cũng ghi giá trị $0 \ge 0$ (vẫn hợp lệ). DB chỉ thấy hai lần ghi giá trị 0 hợp lệ, hoàn toàn không biết rằng logic ứng dụng đã bán vượt quá 1 sản phẩm.

### Câu 3: Điều kiện WHERE và affected rows giúp quyết định bán như thế nào?
*Đáp án tham khảo:*
Câu lệnh `UPDATE market_stock SET available = available - ? WHERE sku = ? AND available >= ?` thực hiện kiểm tra và trừ tồn kho nguyên tử ngay trong engine database dưới row-level exclusive lock. Ở READ COMMITTED của PostgreSQL, khi transaction thứ hai chờ và lấy được lock của dòng, nó sẽ đọc phiên bản mới nhất vừa commit của dòng đó để đánh giá lại mệnh đề `WHERE`. Nếu tồn kho không còn đủ ($available < quantity$), mệnh đề `WHERE` trả về false và số dòng bị ảnh hưởng (`affected rows`) bằng 0. Ứng dụng dựa vào `affected rows == 1` để biết việc trừ kho thành công hay thất bại mà không cần SELECT trước.

### Câu 4: Vì sao UPDATE kho và INSERT purchase phải cùng transaction?
*Đáp án tham khảo:*
Để bảo đảm tính nguyên tử (Atomicity) theo ACID. Nếu trừ kho thành công nhưng việc insert purchase bị lỗi (ví dụ lỗi mạng, trùng khóa, sập server) mà hai thao tác nằm ở hai transaction riêng biệt, kho sẽ bị trừ vĩnh viễn mà không có hóa đơn chứng từ nào được ghi nhận. Ngược lại, nếu nằm chung transaction, khi insert purchase thất bại, toàn bộ thao tác trừ kho sẽ được rollback về trạng thái ban đầu.

### Câu 5: Barrier nên đặt ở đâu? Đặt sau UPDATE có thể khiến test treo ra sao?
*Đáp án tham khảo:*
Trong kịch bản tái hiện race condition, `CyclicBarrier` phải đặt **sau SELECT và trước UPDATE**. Nếu đặt sau UPDATE, worker thứ nhất sẽ chạy câu lệnh UPDATE và giữ row lock của dòng `market_stock`. Worker thứ hai khi gọi UPDATE sẽ bị PostgreSQL chặn (block) để chờ worker thứ nhất nhả lock (commit). Do worker 2 bị block ở tầng JDBC/DB và không bao giờ chạm tới điểm `barrier.await()`, worker 1 cũng sẽ chờ vô tận tại barrier $\rightarrow$ toàn bộ test rơi vào deadlock/treo vĩnh viễn (harness deadlock).

### Câu 6: Test này đã và chưa chứng minh điều gì về hệ thống nhiều instance, idempotency và hiệu năng?
*Đáp án tham khảo:*
- **Đã chứng minh:** Chứng minh tính đúng đắn về mặt dữ liệu (concurrency correctness & ACID) của atomic conditional update và rollback trong một cơ sở dữ liệu PostgreSQL quan hệ duy nhất, độc lập với số lượng thread hay instance ứng dụng gọi vào.
- **Chưa chứng minh:** Chưa chứng minh tính lũy kế/idempotency của API khi client retry do timeout mạng; chưa đo đạc throughput, độ trễ p99 khi có lock contention lớn; và chưa giải quyết bài toán phân tán nhiều database sharding/partitioning.
