# AETHERCORE — PROMPT 09 EXECUTION NOTES & VERIFICATION LOG

**Ngày thực thi:** 12/10/2026 (Prompt 09)  
**Mục tiêu:** M2-E1B — So sánh 3 chiến lược concurrency control (Atomic Conditional UPDATE, Optimistic Version Check, Pessimistic Row Locking `FOR UPDATE`), Flyway Migration V4 version tracking, quan sát `pg_blocking_pids()`, và loại bỏ hoàn toàn errata harness.  
**Trạng thái toàn bộ Reactor:** `BUILD SUCCESS` — 56/56 tests PASS (100%) trên PostgreSQL 16.15 thật qua Testcontainers 1.21.4.

---

## 1. Môi trường Thực thi & Công cụ

| Công cụ / Thành phần | Phiên bản thực tế | Trạng thái xác minh | Ghi chú kỹ thuật |
|---|---|---|---|
| **Host Java Runtime** | Oracle JDK 22 (build 22+36-2370) | `java -version` | Trình biên dịch sử dụng `--release 21` để tương thích bytecode Java 21. |
| **Apache Maven** | 3.9.12 | `mvn -v` | Build thành công toàn bộ reactor `core-lab` và `backend-app`. |
| **Docker Desktop / Engine** | 29.5.3 (API 1.54, context `desktop-linux`) | `docker version` | Khởi chạy qua WMI service độc lập, daemon duy trì kết nối ổn định. |
| **Testcontainers** | 1.21.4 (BOM root) | `mvn dependency:tree` | Kết nối qua Windows named pipe mặc định (`npipe:////./pipe/docker_engine`). |
| **PostgreSQL Container** | `postgres:16-alpine` (PostgreSQL 16.15) | Runtime test container | Khởi tạo schema qua Flyway migrations V1 đến V4. |
| **Database Migrations** | Flyway 10.10.0 (V1, V2, V3, V4) | Runtime log | Áp dụng thành công 4 migration, nâng schema public lên v4. |

---

## 2. Các Thay Đổi & Thành Phần Đã Triển Khai

### 2.1. Migration V4 (`V4__add_market_stock_version.sql`)
Thêm cột `version` cho bảng `market_stock`:
```sql
ALTER TABLE market_stock
ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
```

### 2.2. Cập nhật `FixedMarketPurchaseService`
Cập nhật câu lệnh UPDATE để bảo đảm tính nhất quán phiên bản (Version Invariant):
```sql
UPDATE market_stock 
SET available = available - ?, version = version + 1 
WHERE sku = ? AND available >= ?
```

### 2.3. Các lớp sản phẩm mới (`com.aethercore.backend.market`)
1. **`StockSnapshot` (Java record):**
   Chứa `sku`, `available`, `version`. Xác thực không null, không rỗng và không âm.
2. **`StockVersionConflictException` (extends `RuntimeException`):**
   Ném ra khi cập nhật lạc quan gặp xung đột phiên bản, mang theo thông tin `sku` và `expectedVersion`.
3. **`OptimisticMarketPurchaseService`:**
   - Đọc snapshot không lock.
   - UPDATE có điều kiện `WHERE sku = ? AND version = ? AND available >= ?`.
   - Ném `StockVersionConflictException` khi `updated == 0` để rollback transaction.
   - Cung cấp `purchaseWithRetry` với **Exponential Backoff and Full Jitter** nhằm ngăn chặn thundering herd và livelock dưới tải cạnh tranh cao.
4. **`PessimisticMarketPurchaseService`:**
   - Thực thi `SELECT sku, available, version FROM market_stock WHERE sku = ? FOR UPDATE`.
   - Giữ exclusive row lock trong suốt transaction, tuần tự hóa các truy cập đồng thời.
   - Cung cấp hook `purchaseWithLockHook` để phục vụ quan sát `pg_blocking_pids()` trong test suite.

### 2.4. Khắc phục dứt điểm Errata Prompt 08 trong Test Harness
- Cập nhật `safeShutdownExecutor`: khi bắt `InterruptedException`, luôn khôi phục cờ ngắt `Thread.currentThread().interrupt()`, shutdown executor và ném `AssertionError` có đính kèm nguyên nhân ban đầu.
- Tất cả các bài kiểm thử đa luồng đều được bao bọc trong khối `try ... finally { safeShutdownExecutor(...); }`, triệt tiêu hoàn toàn nguy cơ worker mồ côi làm ảnh hưởng các test sau.

---

## 3. Kết Quả Kiểm Thử Thực Tế (56/56 PASS)

### 3.1. `backend-app` (22/22 tests PASS)
- **`MarketLockingConcurrencyTest` (8 tests):**
  - `C1`: Mua đơn lẻ với Optimistic Service, tăng version 0 -> 1 (`PASS`).
  - `C2`: Xung đột có kiểm soát, worker 1 thành công, worker 2 nhận `StockVersionConflictException` và rollback (`PASS`).
  - `C3`: Worker gặp xung đột retry trong transaction mới thành công (`PASS`).
  - `C4`: Quan sát lock wait của PostgreSQL qua `pg_blocking_pids()` xác nhận T2 bị chặn bởi PID của T1 (`PASS`).
  - `C5`: Tải hỗn hợp 30 worker (10 Atomic, 10 Optimistic with retry, 10 Pessimistic) trên stock 30 đạt 100% bảo toàn dữ liệu và version = 30 (`PASS`).
  - `C6`: Outer transaction rollback hoàn trả stock và version cho Optimistic Service (`PASS`).
  - `C7`: Outer transaction rollback hoàn trả stock và version cho Pessimistic Service (`PASS`).
  - `C8`: Thiếu tồn kho trả về `NOT_AVAILABLE` không làm đổi stock và version (`PASS`).
- **`MarketPurchaseConcurrencyTest` (9 tests):**
  - Session timeouts (lock_timeout 5s, statement_timeout 10s) (`PASS`).
  - Tái hiện Lost Update trên Broken Service (`PASS`).
  - Atomic Conditional Update giải quyết Lost Update (`PASS`).
  - Mua nhiều unit tuần tự (`PASS`).
  - Validation quantity (`PASS`).
  - Thiếu tồn kho / không tồn tại SKU (`PASS`).
  - Rollback fault injection duplicate UUID (`PASS`).
  - Tải đồng thời 20 lượt (`PASS`).
  - Outer transaction boundary rollback (`PASS`).
- **`LabEntryIntegrationTest` (3 tests):** Flyway migration v4, GET /lab/entries (`PASS`).
- **`DamageStrategyWiringTest` (2 tests):** Spring context injection (`PASS`).

### 3.2. `core-lab` (34/34 tests PASS)
- Tất cả 34 unit tests (PlayerKey, Wrapper, BigDecimal, Record, Strategy) duy trì trạng thái 100% PASS, zero regression.