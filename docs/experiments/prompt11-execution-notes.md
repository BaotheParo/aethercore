# NHẬT KÝ THỰC THI PROMPT 11 (M2-E3A & REFINEMENT LAB)

**Dự án:** AetherCore — Module M2: Database & Concurrency
**Base Commit:** `0712c08` (`feat(prompt-10): refine M2-E1B contracts and implement M2-E1C JPA @Version test lab`)
**Môi trường:** Java 22, Maven 3.9.12, Docker Desktop 29.5.3 (API 1.54), PostgreSQL 16.15 alpine Testcontainers

---

## 1. Đính chính & Tinh chỉnh Phần A (Refinements)

### A1. Đính chính Bằng chứng Lịch sử (Evidence Summary)
- Đã ghi nhận đính chính trong `docs/experiments/prompt10-execution-notes.md` và thêm header lưu ý trong `docs/evidence/prompt10/test-summary.txt`.
- Danh mục 4 lớp test core thực tế: `PlayerKeyExperimentsTest`, `WrapperAndPrecisionExperimentsTest`, `RecordExperimentsTest`, `DamageStrategyExperimentsTest`.
- Mọi dữ liệu evidence từ Prompt 11 được trích xuất tự động và nguyên bản từ Surefire XML kết quả thực tế.

### A2. Refinement Kiểm thử JPA (@Version & Lifecycle)
- **JPA-1:** Chuẩn hóa câu lệnh SQL thu được từ `TestStatementInspector`. Kiểm tra chặt chẽ:
  - Mệnh đề SET có cập nhật version (`version=?` hoặc `version = ?`) và trường dữ liệu (`available=?`).
  - Mệnh đề WHERE có điều kiện version (`version=?` hoặc `version = ?`) và khóa chính (`sku=?`).
- **JPA-2 & JPA-3:** Tách biệt rõ ràng ranh giới lỗi:
  - JPA-2: Bắt lỗi `OptimisticLockException` trực tiếp tại thời điểm gọi `em.flush()`.
  - JPA-3: Bắt lỗi `RollbackException` tại ranh giới commit (`tx.commit()`), xác minh `OptimisticLockException` nằm trong chuỗi nguyên nhân (cause chain).
- **JPA-4:** Bổ sung `assertTrue(observerFuture.get(5, TimeUnit.SECONDS))` để bảo đảm mọi ngoại lệ trên luồng observer được truyền về test thread.
- **JPA-5:** Tinh chỉnh cơ chế retry:
  - Attempt 1 bắt buộc phải kiểm tra ngoại lệ ném ra đúng là `OptimisticLockException`. Nếu gặp lỗi khác (kết nối, timeout, constraint), test fail-fast ngay lập tức và không chuyển sang retry.
  - Khẳng định số lần thực thi đúng bằng 2 attempts.
  - Attempt 2 mở `EntityManager` mới, query snapshot đã commit và hoàn tất thành công.

### A3. Ràng buộc Danh tính Worker C4 (`pg_blocking_pids`)
- Trong `MarketLockingConcurrencyTest`, test C4 được tái cấu trúc:
  - Worker T1 chiếm khóa hàng độc quyền bằng `SELECT ... FOR UPDATE` và công bố PID `t1Pid`.
  - Worker T2 chạy trong một `TransactionTemplate` (REQUIRED) outer, truy vấn `SELECT pg_backend_pid()` trực tiếp từ connection của mình, công bố `t2Pid`.
  - Kiểm tra `assertNotEquals(t1Pid, t2Pid)`.
  - Observer thực hiện query: `SELECT ?::int = ANY(pg_blocking_pids(?::int))` với tham số `t1Pid` và `t2Pid`.
  - Engine PostgreSQL 16 xác nhận `Boolean.TRUE.equals(isBlocked)`.
  - Sửa Javadoc trong `PessimisticMarketPurchaseService.java` để phản ánh đúng bản chất: khóa hàng giữ cho đến khi outer transaction kết thúc, không có khái niệm "rollback việc giải phóng lock".

---

## 2. Triển khai Phần B: Lab M2-E3A (Spring @Transactional Proxy)

Xây dựng lớp kiểm thử `TransactionProxyExperimentsTest` trong gói `com.aethercore.backend.transaction`:
- Cấu hình 2 Spring Bean: `ProbeWriter` và `ProbeOrchestrator` qua `@TestConfiguration`.
- Xác minh Spring AOP Proxying: Cả hai bean đều là CGLIB class-based proxy (`AopUtils.isCglibProxy == true`).
- Hoàn thành đầy đủ 8 kịch bản bắt buộc (TX1 – TX8):
  1. **TX1:** Lời gọi ngoài qua proxy, commit thành công 1 dòng (`txActive=true`, `autoCommit=false`).
  2. **TX2:** Lời gọi ngoài qua proxy, ném `RuntimeException` $
ightarrow$ rollback 0 dòng.
  3. **TX3:** Lời gọi `this.innerAnnotated()` từ outer không annotated $
ightarrow$ bypass proxy, `txActive=false`, `autoCommit=true` tự commit 1 dòng.
  4. **TX4:** Lời gọi từ outer không annotated sang bean được inject có annotation $
ightarrow$ proxy chặn, rollback 0 dòng.
  5. **TX5:** Checked exception với cấu hình mặc định $
ightarrow$ Spring coi là luồng nghiệp vụ hợp lệ và **COMMIT** 1 dòng.
  6. **TX6:** Checked exception với `rollbackFor = BusinessCheckedException.class` $
ightarrow$ rollback 0 dòng.
  7. **TX7:** Nuốt exception cục bộ trong `try-catch` $
ightarrow$ Interceptor thấy return bình thường, commit 1 dòng.
  8. **TX8:** Gọi `this.innerAnnotated()` từ outer có `@Transactional` $
ightarrow$ Dù inner bị bypass nhưng outer transaction vẫn active, rollback 0 dòng.

---

## 3. Tổng kết Kiểm thử Toàn hệ thống (Reactor Test Results)

Chạy lệnh kiểm thử toàn diện:
```powershell
mvn clean test
```

**Kết quả:**
- `AetherCore Parent`: SUCCESS
- `AetherCore :: Core Lab`: **34 tests PASS** (0 failures, 0 errors, 0 skipped)
- `AetherCore :: Backend App`: **40 tests PASS** (0 failures, 0 errors, 0 skipped)
- **Tổng cộng Reactor:** **74 tests PASS 100%**, không có bất kỳ regression nào.
---

## ĐÍNH CHÍNH PHÂN BỔ TEST XML VÀ DAMAGE STRATEGY WIRING (PROMPT 12)
> **Ghi chú đối chiếu Surefire XML thực tế:**
> - Module `core-lab` (34 tests): `PlayerKeyExperimentsTest` (8 tests), `WrapperAndPrecisionExperimentsTest` (13 tests: gồm 8 phương thức test và các invocation tham số hóa), `RecordExperimentsTest` (5 tests), `DamageStrategyExperimentsTest` (8 tests). Tổng: 34 tests.
> - Module `backend-app` (40 tests): `LabEntryIntegrationTest` (3 tests), `DamageStrategyWiringTest` (2 tests) (không gộp thành LabEntry = 5), `MarketLockingConcurrencyTest` (11 tests), `MarketPurchaseConcurrencyTest` (9 tests), `JpaVersionExperimentsTest` (6 tests), `TransactionProxyExperimentsTest` (9 tests). Tổng: 40 tests.
