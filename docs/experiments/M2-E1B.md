# M2-E1B: Optimistic vs Pessimistic Locking & Concurrency Control Experiment

**Module:** M2 — Database & Concurrency  
**Lab Code:** M2-E1B  
**Chủ đề:** So sánh 3 chiến lược điều khiển đồng thời (Atomic Conditional UPDATE, Optimistic Version Check OCC, Pessimistic Row Locking FOR UPDATE), cơ chế version tracking, rollback boundary, quan sát pg_blocking_pids(), và giải pháp chống retry storms.  
**Trạng thái kiểm chứng Runtime:** VERIFIED trên PostgreSQL 16.15 thật (Docker Desktop 29.5.3 via Testcontainers 1.21.4).

---

## 1. Nghiệp vụ, Schema & Cơ chế Version Tracking

### 1.1. Invariants của hệ thống
Hệ thống duy trì hai bất biến tuyệt đối qua mọi chuỗi transaction:

1. **Bất biến số lượng (Stock Conservation Invariant):**
   \text{stock}_{\text{cuối}} + \sum \text{quantity}_{\text{purchases đã commit}} = \text{stock}_{\text{ban đầu}}
   Đồng thời: $\text{available} \ge 0$ (bảo vệ bởi CHECK (available >= 0)).

2. **Bất biến phiên bản (Version Invariant):**
   \text{version}_{\text{cuối}} = \text{version}_{\text{ban đầu}} + \text{số lần purchase commit thành công}
   Đồng thời: $\text{version} \ge 0$ (bảo vệ bởi CHECK (version >= 0)).

### 1.2. Schema Migration (V4__add_market_stock_version.sql)
`sql
-- Migration V4: Add version column to market_stock for optimistic locking and state evolution tracking
ALTER TABLE market_stock
ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
`

Tất cả các transaction ghi hợp lệ (FixedMarketPurchaseService, OptimisticMarketPurchaseService, PessimisticMarketPurchaseService) đều bắt buộc tăng version:
\text{version} = \text{version} + 1

---

## 2. So sánh 3 Chiến lược Điều khiển Đồng thời

| Tiêu chí | 1. Atomic Conditional UPDATE | 2. Optimistic Locking (OCC) | 3. Pessimistic Locking (FOR UPDATE) |
|---|---|---|---|
| **Cơ chế cốt lõi** | Câu lệnh UPDATE ... WHERE available >= ? đơn lẻ thực thi ở tầng DB. DB tự serialize row write lock ngầm trong thời gian thực thi câu lệnh UPDATE. | Đọc snapshot không lock (SELECT available, version), kiểm tra nghiệp vụ trên app, cập nhật có điều kiện WHERE version = ?. Nếu updated == 0, ném StockVersionConflictException để rollback transaction. | Bắt đầu transaction, giữ row-level exclusive lock ngay lập tức bằng SELECT ... FOR UPDATE, kiểm tra và cập nhật, giữ lock cho tới khi commit/rollback. |
| **Loại lock** | Implicit short-lived row write lock (chỉ tồn tại trong vài microsecond của câu lệnh UPDATE). | Không giữ lock qua bước đọc. Không lock ở tầng DB trong suốt pha tính toán nghiệp vụ. | Explicit row-level lock (RowShareLock/Exclusive trên row) giữ liên tục suốt thời gian transaction mở. |
| **Thời gian chiếm giữ lock** | Cực ngắn (micro-transactions). | Không có thời gian giữ lock trước khi UPDATE. | Dài (từ lúc SELECT FOR UPDATE đến khi toàn bộ logic nghiệp vụ hoàn tất và transaction kết thúc). |
| **Xử lý xung đột** | DB xử lý tuần tự; worker nào đến sau mà không đủ điều kiện vailable >= qty thì nhận ows_affected = 0 (trả về NOT_AVAILABLE). | Worker phát hiện version đã bị thay đổi (stale version), rollback transaction hiện tại, caller bắt exception và retry trong transaction mới. | Các worker đến sau bị **chặn hoàn toàn** (blocked) ở tầng PostgreSQL engine cho tới khi transaction trước giải phóng lock (quan sát được qua pg_blocking_pids()). |
| **Nguy cơ Deadlock** | Không có (chỉ cập nhật 1 row theo PK). | Không có ở tầng database. | Có nguy cơ deadlock nếu nhiều transaction lock nhiều tài nguyên khác nhau theo thứ tự không nhất quán. Với 1 row duy nhất thì không deadlock nhưng có nguy cơ connection pool starvation. |
| **Hành vi khi tải cao** | Throughput cao nhất, độ trễ thấp và ổn định. Thích hợp nhất cho các thao tác counter/inventory trừ dần đơn giản. | Tỉ lệ conflict tăng vọt, nguy cơ retry storms/livelock nếu không có exponential backoff và jitter. Thích hợp cho read-heavy, ít xung đột. | Hàng đợi lock dài, độ trễ tăng tuyến tính theo số lượng worker đồng thời, chiếm giữ kết nối DB lâu. Thích hợp khi logic tính toán phức tạp trước khi ghi và tỉ lệ xung đột cao cần tránh lãng phí công việc (avoid wasted work). |

---

## 3. Bảng Đối Chiếu Bằng Chứng Thực Nghiệm (Observed on PostgreSQL 16.15)

*Tất cả 8 test trong MarketLockingConcurrencyTest và 9 test trong MarketPurchaseConcurrencyTest đều PASS 100% trên container:*

| Mã Test | Kịch bản kiểm thử | Stock / Version ban đầu | Hành vi & Cơ chế xác thực | Kết quả quan sát thực tế (PostgreSQL 16.15) | Trạng thái Bất biến |
|---|---|:---:|---|---|:---:|
| **C1** | Optimistic: Mua đơn lẻ | Stock 5, Ver 0 | Mua 2 unit. UPDATE tăng version và giảm stock. | SUCCESS. Stock = 3, Ver = 1, Count = 1, SumQty = 2. | BẢO TOÀN ( + 2 = 5$) |
| **C2** | Optimistic: Controlled Conflict | Stock 1, Ver 0 | 2 worker cùng giữ snapshot Ver 0. W1 commit trước, W2 cố commit Ver 0 cũ. | W1: SUCCESS. W2: ném StockVersionConflictException (sku='NETHER_STAR', expectedVersion=0). Transaction W2 rollback hoàn toàn. Stock = 0, Ver = 1, Count = 1. | BẢO TOÀN ( + 1 = 1$, Ver = 1) |
| **C3** | Optimistic: Retry trong TX mới | Stock 2, Ver 0 | 2 worker đọc Ver 0. W1 mua 1 unit. W2 bị conflict, bắt exception, mở transaction mới, đọc Ver 1 và mua thành công. | Cả W1 và W2 đều SUCCESS. Stock = 0, Ver = 2, Count = 2, SumQty = 2. | BẢO TOÀN ( + 2 = 2$, Ver = 2) |
| **C4** | Pessimistic: Quan sát Lock Wait via pg_blocking_pids() | Stock 2, Ver 0 | T1 chạy SELECT FOR UPDATE giữ lock và capture pg_backend_pid(). T2 chạy câu lệnh tương tự trên SKU đó và bị block ở PostgreSQL. Observer độc lập kiểm tra pg_blocking_pids(). | **Xác nhận 100%:** Observer phát hiện PID của T2 xuất hiện trong pg_stat_activity với pg_blocking_pids(t2_pid) chứa chính xác PID của T1! T1 giải phóng lock $\rightarrow$ T2 unblock và commit thành công. Stock = 0, Ver = 2. | BẢO TOÀN ( + 2 = 2$, Ver = 2) |
| **C5** | Mixed Writers Stress: Cạnh tranh 3 chiến lược | Stock 30, Ver 0 | 30 worker đồng thời: 10 Atomic, 10 Optimistic (kèm backoff jitter), 10 Pessimistic cùng tranh chấp SKU ANCIENT_DEBRIS. | 30/30 SUCCESS. Stock = 0, Ver = 30, Count = 30, SumQty = 30. Không có deadlock, không vi phạm khóa ngoại. | BẢO TOÀN ( + 30 = 30$, Ver = 30) |
| **C6** | Optimistic: Outer TX Rollback | Stock 5, Ver 0 | Mua 2 unit thành công bên trong outer TX, sau đó outer TX ném exception rollback. | Stock khôi phục về 5, Version khôi phục về 0, 0 purchase record. | BẢO TOÀN ( + 0 = 5$, Ver = 0) |
| **C7** | Pessimistic: Outer TX Rollback | Stock 5, Ver 0 | Mua 2 unit thành công dưới pessimistic lock, sau đó outer TX ném exception rollback. | Stock khôi phục về 5, Version khôi phục về 0, 0 purchase record. | BẢO TOÀN ( + 0 = 5$, Ver = 0) |
| **C8** | Pessimistic: Thiếu tồn kho | Stock 1, Ver 0 | Mua 5 unit với SELECT FOR UPDATE. Nhận thấy vailable < qty. | Trả về NOT_AVAILABLE. Stock giữ nguyên 1, Version giữ nguyên 0, 0 purchase record. | BẢO TOÀN ( + 0 = 1$, Ver = 0) |

---

## 4. Phân tích Hiện tượng & Thiết kế Chống Lỗi (Engineering Deep Dive)

### 4.1. Quan sát Lock Wait với pg_blocking_pids()
Trong test C4, việc quan sát lock wait không sử dụng phỏng đoán thời gian (Thread.sleep) mà truy vấn trực tiếp bảng quản trị của PostgreSQL engine:
`sql
SELECT pid, pg_blocking_pids(pid) AS blockers 
FROM pg_stat_activity 
WHERE pid != ? AND cardinality(pg_blocking_pids(pid)) > 0;
`
Hàm pg_blocking_pids(int) trả về mảng các PID đang giữ lock mà tiến trình con bị chặn. Kết quả kiểm thử chứng minh:
- T1 gọi SELECT ... FOR UPDATE chiếm giữ ExclusiveLock trên tuple của bảng market_stock.
- T2 gửi câu lệnh tương tự trên cùng tuple bị kernel PostgreSQL đưa vào trạng thái chờ (wait_event_type = 'Lock').
- Tiến trình kiểm thử quan sát thấy mảng lockers của T2 chứa đúng PID của T1.
- Sau khi T1 commit, kernel PostgreSQL đánh thức T2 tiếp tục thực thi mà không gây bất kỳ deadlock nào.

### 4.2. Xử lý Thundering Herd & Retry Storms trong Optimistic Locking
Trong kịch bản C5 (Mixed Writers với 30 worker đồng thời), các worker Optimistic nếu retry liên tục (tight spin loop) sẽ gặp hiện tượng **livelock / retry storm**:
- Mỗi khi một worker khác (Atomic hoặc Pessimistic) commit, version của row tăng lên.
- Các worker Optimistic liên tục đọc snapshot cũ và ném StockVersionConflictException.
- **Giải pháp áp dụng:** Cài đặt cơ chế **Exponential Backoff kèm Full Jitter**:
  \text{backoffMs} = \min\left(50, 2^{\min(\text{attempt}, 5)} + \text{random}(0, 4)\right)
  Nhờ có jitter, các worker phân tán thời điểm gửi transaction mới lên database, giúp 100% 10 worker Optimistic commit thành công mà không làm cạn kiệt số lần retry.

### 4.3. Ranh giới Transaction Rollback & Harness Safety
Toàn bộ harness kiểm thử đa luồng tuân thủ nguyên tắc cách ly tài nguyên:
1. Mọi luồng worker và executor đều được đóng gói trong khối 	ry ... finally { safeShutdownExecutor(executor, gate, futures); }.
2. Khi bị ngắt hoặc shutdown, harness luôn khôi phục cờ ngắt (Thread.currentThread().interrupt()) và đính kèm AssertionError với nguyên nhân bị ngắt làm cause để không làm mất dấu vết lỗi.
3. Không bao giờ để worker mồ côi (orphaned workers) chạy ngầm xuyên qua @BeforeEach cleanTables(), triệt tiêu hoàn toàn lỗi vi phạm khóa ngoại market_purchase_sku_fkey.