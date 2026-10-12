# AETHERCORE — PROMPT 10 EXECUTION NOTES & VERIFICATION LOG

**Ngày thực thi:** 12/10/2026 (Prompt 10)  
**Mục tiêu:** 
1. Khớp lại M2-E1B (JDBC lab): loại bỏ retry khỏi production service, điều phối xung đột qua purchase() thật và snapshot thật, kiểm tra typed `pg_blocking_pids()`, bổ sung mixed-writer test chứng minh version increment.
2. Triển khai M2-E1C (JPA lab): bootstrap Java SE JPA / Hibernate 6.5 test-only, kiểm chứng `@Version`, persistence context, dirty checking, sự khác biệt giữa `flush()` và `commit()`, rollback atomicity và fresh retry.
**Trạng thái toàn bộ Reactor:** `BUILD SUCCESS` — 65/65 tests PASS (100%) trên PostgreSQL 16.15 thật qua Testcontainers 1.21.4.

---

## 1. Môi trường Thực thi & Công cụ

| Công cụ / Thành phần | Phiên bản thực tế | Trạng thái xác minh | Ghi chú kỹ thuật |
|---|---|---|---|
| **Host Java Runtime** | Oracle JDK 22 (build 22+36-2370) | `java -version` | Trình biên dịch sử dụng `--release 21` để tương thích bytecode Java 21. |
| **Apache Maven** | 3.9.12 | `mvn -v` | Build thành công toàn bộ reactor `core-lab` và `backend-app`. |
| **Docker Engine** | 29.5.3 (API 1.54, desktop-linux) | `docker version` | Khởi chạy độc lập qua WMI service, named pipe `npipe:////./pipe/docker_engine`. |
| **Testcontainers** | 1.21.4 (BOM root) | `mvn dependency:tree` | Quản lý vòng đời container PostgreSQL 16.15 tự động. |
| **PostgreSQL Container** | `postgres:16-alpine` (PostgreSQL 16.15) | Runtime test container | Khởi tạo schema qua Flyway migrations V1 đến V4. |
| **Hibernate ORM** | 6.5.3.Final (`scope: test`) | `mvn dependency:tree` | Quản lý qua Spring Boot BOM, không đưa vào production classpath. |
| **Jakarta Persistence** | 3.1.0 (`scope: test`) | `mvn dependency:tree` | Jakarta Persistence API cho lab M2-E1C. |

---

## 2. Phần A: Sửa các điểm lệch của P09 (M2-E1B Refinements)

### 2.1. Thu hồi retry và test hook khỏi production service
- Loại bỏ `purchaseWithRetry` và thuật toán backoff/jitter khỏi `OptimisticMarketPurchaseService.java`. Retry là trách nhiệm của caller/test harness trong transaction mới.
- Loại bỏ `purchaseWithExpectedSnapshot` khỏi `OptimisticMarketPurchaseService.java`.
- Cập nhật JavaDoc ghi rõ hợp đồng `PROPAGATION_REQUIRED`: `PurchaseResult.SUCCESS` bên trong outer transaction chỉ đại diện cho thao tác thành công trong context hiện tại, tính bền vững phụ thuộc outer commit.
- Thêm template hook được bảo vệ `onSnapshotRead(snapshot)` và `onLockAcquired(locked)` để test harness có thể can thiệp điều phối mà không sửa đổi public API nghiệp vụ.

### 2.2. Điều phối xung đột qua `purchase()` thật và snapshot thật
- Trong `C2` và `C3`: cả hai worker đều gọi `service.purchase()`, mở transaction riêng trên backend PID riêng, đọc snapshot riêng từ DB.
- Lớp con `HookedOptimisticService` (non-static) chặn tại `onSnapshotRead()` bằng `CyclicBarrier` để đồng bộ hai worker cùng giữ `version = 0`.
- Worker 1 commit thành công $\rightarrow$ version lên 1. Worker 2 thực thi câu lệnh UPDATE với `WHERE version = 0` $\rightarrow$ 0 rows affected $\rightarrow$ ném `StockVersionConflictException` $\rightarrow$ rollback.

### 2.3. Sửa `SELECT ... FOR UPDATE` & `pg_blocking_pids`
- Kiểm tra lock wait với câu lệnh SQL tường minh:
  ```sql
  SELECT ?::int = ANY(pg_blocking_pids(?::int))
  ```
- Ép kiểu `::int` giải quyết triệt để lỗi kiểu dữ liệu `pg_blocking_pids(bigint) does not exist`.
- Worker 1 và 2 chạy trên stock 1: T1 mua 1 thành công; T2 bị block ở PostgreSQL level; observer xác nhận boolean `true`; T1 giải phóng lock; T2 unblock và nhận `NOT_AVAILABLE`.

### 2.4. Bổ sung các contract còn thiếu
- **Mixed Writer Invariant (C5):** Worker A giữ snapshot version 0; Writer B (Atomic Conditional UPDATE) commit 1 unit $\rightarrow$ stock còn 4, version lên 1. Worker A cố thực thi $\rightarrow$ bị xung đột phiên bản dù stock (4) vẫn còn đủ. Điều này chứng minh Writer B đã tăng version đúng chuẩn.
- **Rollback on Duplicate UUID (C6, C7):** Cả hai service Optimistic và Pessimistic đều rollback stock và version khi câu lệnh INSERT bị trùng khóa chính UUID (`SQLSTATE 23505`).
- **Validation (C8) & Missing/Insufficient (C9):** Kiểm tra từ chối tham số không hợp lệ và trả về `NOT_AVAILABLE`.
- **Outer Rollback (C10, C11):** Khôi phục cả stock và version khi outer transaction rollback.

---

## 3. Phần B: M2-E1C — JPA Test Lab Độc Lập

### 3.1. Cấu hình & Bootstrap
- `backend-app/pom.xml`: Thêm `hibernate-core:6.5.3.Final` và `jakarta.persistence-api:3.1.0` với `<scope>test</scope>`.
- `backend-app/src/test/resources/META-INF/persistence.xml`: Persistence unit `aethercore-market-jpa-test` (`RESOURCE_LOCAL`, `hbm2ddl.auto=validate`).
- Thực thể JPA: `JpaMarketStock` (`@Version` trên field version) và `JpaMarketPurchase`.
- `TestStatementInspector`: Quan sát các câu lệnh SQL thật do Hibernate sinh ra.

### 3.2. Sáu bài thực nghiệm JPA (`JpaVersionExperimentsTest`)
1. **JPA-1 (Dirty Checking & First-level Cache):**
   - Hai lần gọi `em.find()` trả về cùng một instance trên Heap (`s1 == s2`).
   - Gọi `s1.decrementStock(2)` (không set version) $\rightarrow$ Hibernate tự sinh câu lệnh:
     `update market_stock set available=?,version=? where sku=? and version=?`
   - Version tự động tăng từ 0 lên 1 trên DB.
2. **JPA-2 (Xung đột tại Flush tường minh):**
   - Hai `EntityManager` cùng đọc version 0 trong hai transaction sống đồng thời.
   - Worker 1 flush & commit thành công (version lên 1).
   - Worker 2 gọi `em2.flush()` $\rightarrow$ Hibernate phát hiện 0 dòng cập nhật và ném `OptimisticLockException` ngay tại thời điểm `flush()`.
   - Worker 2 rollback an toàn.
3. **JPA-3 (Xung đột tại Commit Boundary khi không explicit flush):**
   - Worker 2 sửa entity nhưng không gọi `flush()`.
   - Worker 1 commit thành công.
   - Worker 2 gọi `em2.getTransaction().commit()` $\rightarrow$ Hibernate tự động flush khi commit và ném `RollbackException` bọc `OptimisticLockException`.
4. **JPA-4 (Flush không phải Commit):**
   - Worker A gọi `emA.flush()` $\rightarrow$ SQL đã gửi xuống database socket.
   - Observer trên kết nối khác (isolation `READ COMMITTED`) truy vấn và xác nhận dữ liệu chưa commit hoàn toàn không hiển thị (stock vẫn là 2, version 0, count 0).
   - Worker A rollback $\rightarrow$ dữ liệu phục hồi nguyên vẹn.
5. **JPA-5 (Retry với EntityManager / Transaction mới):**
   - Sau khi Worker 2 bị xung đột ở JPA-2, Worker 2 đóng EM cũ, mở `EntityManager` mới và transaction mới.
   - Đọc committed snapshot mới (version = 1, available = 1), trừ kho và commit thành công (stock = 0, version = 2).
6. **JPA-6 (Tính nguyên tố khi INSERT lỗi sau Stock Flush):**
   - Giảm stock và gọi `em.flush()` (UPDATE đã gửi xuống DB).
   - Persist purchase với UUID trùng lặp $\rightarrow$ commit thất bại với `SQLSTATE 23505`.
   - Rollback phục hồi nguyên vẹn cả stock và version ban đầu.

---

## 4. Kết Quả Kiểm Thử Toàn Bộ Reactor (65/65 PASS)

- **`core-lab`:** 34/34 tests PASS
- **`backend-app`:** 31/31 tests PASS
  - `JpaVersionExperimentsTest`: 6 tests PASS
  - `MarketLockingConcurrencyTest`: 11 tests PASS
  - `MarketPurchaseConcurrencyTest`: 9 tests PASS
  - `LabEntryIntegrationTest`: 3 tests PASS
  - `DamageStrategyWiringTest`: 2 tests PASS
- **Tổng cộng:** **65 tests PASS, 0 failure, 0 error, 0 skipped**.