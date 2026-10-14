# Nhật Ký Thực Thi Prompt 19: M2-E6A — Transactional Purchase Idempotency

**Mã phiên:** Prompt 19 — M2-E6A
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Git Baseline HEAD:** `6f661f20fef923912e8a0723a086ff6fbd4f0a78` (Commit `feat(prompt-18): verify predicate and expression index mechanics`)
**Evidence Run ID:** `prompt19-20261013-210040-b68afb`
**Kết quả kiểm thử:**
- Python collector: 18/18 tests PASS
- Targeted suite (RequestFingerprintTest + MarketIdempotencyIntegrationTest): 11/11 tests PASS (13,1s)
- Full Reactor: 116/116 tests PASS (Core Lab: 34 tests, Backend App: 82 tests, 0 failures, 0 errors, 0 skipped, 51,3s)

---

## 1. Đính Chính Kỹ Thuật Prompt 18 (Phần A)

1. **Chuẩn hóa mô tả Fixture Generator trong `M2-E5C.md` và `prompt18-execution-notes.md`:**
   - Cập nhật mô tả để khớp 100% với mã nguồn trong `PostgresPredicateIndexExperimentsTest.java`:
     - `g % 1000 = 0`: Tiền tố `Redstone-<06d>` (100 dòng: IDs `1000, 2000, ..., 100000`).
     - `g % 1000 = 1`: Hậu tố `Guild-<06d>-Redstone` (100 dòng: IDs `1, 1001, 2001, ..., 99001`).
     - Còn lại: `Guild-<06d>` (99.800 dòng).
     - ID 100001: `rEdStOnE-050000` (mixed-case xen kẽ, cùng giá trị chữ thường với ID 50000 `Redstone-050000`).
   - Xóa bỏ các mô tả nhầm lẫn cũ (`1..100`, `101..200`, `Mid-Redstone`).
2. **Tiết giảm kết luận tuyệt đối:**
   - Làm rõ rằng ký tự đại diện `%` đứng đầu không tạo được prefix range seek hữu ích trên B-tree trong ca thử nghiệm này, nhưng không suy diễn rằng PostgreSQL luôn bắt buộc `Seq Scan` cho mọi truy vấn contains LIKE trên mọi tập dữ liệu và cấu hình.
   - Không đồng nhất thời gian chạy của một lần test đơn lẻ với thông lượng (throughput) môi trường production.
3. **Đính chính ghi chú con trỏ Keyset:**
   - Làm rõ rằng trong Prompt 17, sự thay đổi là chuẩn hóa danh sách `expectedIds` độc lập cho các assertions kiểm thử, không phải thay đổi cách lấy con trỏ biên của Keyset Seek (vốn luôn được lấy độc lập từ bản ghi biên ID 20001).

---

## 2. Thiết Kế & Triển Khai Idempotency (Phần B)

### 2.1. Migration V5
Tạo `backend-app/src/main/resources/db/migration/V5__create_market_idempotency.sql`:
- Bảng `market_idempotency` với Composite Primary Key `(caller_id, operation, idempotency_key)`.
- Cột `request_sku` không có FK tới `market_stock` (cho phép ghi nhận trạng thái `NOT_AVAILABLE` cho SKU không tồn tại).
- Cột `purchase_id` có FK tới `market_purchase(id)`.
- Ràng buộc hình dạng trạng thái (shape check constraint) kiểm soát chặt chẽ máy trạng thái giữa `PROCESSING` và `COMPLETED`.

### 2.2. Dấu Vân Tay Chuẩn Tắc (Request Fingerprint)
- Triển khai trong `RequestFingerprint.java` sử dụng JDK tiêu chuẩn:
  - Versioned Big-Endian byte encoding: `[version=1, skuLength, skuBytes, quantity]`.
  - SHA-256 digested thành 64 ký tự lowercase hexadecimal.
  - Được kiểm chứng độc lập bởi 3 unit tests trong `RequestFingerprintTest.java`.

### 2.3. Dịch Vụ Idempotent Purchase
- Triển khai trong `IdempotentMarketPurchaseService.java`:
  - `TransactionTemplate` với `PROPAGATION_REQUIRED` và `ISOLATION_READ_COMMITTED`.
  - Kiểm tra kết nối tham gia giao dịch: từ chối các mức cô lập khác `READ_COMMITTED` (Isolation Guard).
  - Claim bằng `INSERT ... ON CONFLICT DO NOTHING`.
  - Khi xung đột xảy ra (0 dòng chèn): thực thi một câu lệnh `SELECT` độc lập mới, đối chiếu payload/fingerprint (ném `IdempotencyConflictException` nếu sai lệch), và trả về kết quả đã lưu.

### 2.4. Bảy Kịch Bản Kiểm Thử Chấp Nhận (ID1–ID7)
Triển khai trong `MarketIdempotencyIntegrationTest.java` trên Testcontainers PostgreSQL 16 thật:
- **ID1:** Replay tuần tự mô phỏng phản hồi bị mất sau commit; trả về cùng `purchaseId`, không trừ kho lần 2.
- **ID2:** Tái sử dụng key với SKU hoặc quantity khác ném `IdempotencyConflictException`; input sai bị chặn trước khi claim.
- **ID3:** Hai request đồng thời chờ nhau trong DB; kết nối observer đo được Worker B bị chặn bởi Worker A qua `pg_blocking_pids`; cả hai trả về cùng `purchaseId`.
- **ID4:** Phân tách không gian định danh (Scope Isolation): khác caller hoặc key mới tạo 3 đơn hàng độc lập, trừ kho 3 lần.
- **ID5:** Lỗi SQL sau khi trừ kho (ràng buộc kiểm tra `CHECK (quantity != 2)`) rollback sạch sẽ cả claim, stock và purchase; retry sau khi gỡ ràng buộc thành công.
- **ID6:** Replay kết quả `NOT_AVAILABLE` được bảo toàn sau khi restock; key mới mua thành công; bảo toàn tổng supply = 5.
- **ID7:** Outer transaction rollback hủy bỏ toàn bộ claim nội bộ; retry độc lập ngoài transaction thành công.
- **Isolation Guard:** Kiểm thử từ chối giao dịch có mức cô lập ngoài `READ_COMMITTED` (ví dụ `SERIALIZABLE`).

---

## 3. Evidence Collection & Kiểm Chứng Toàn Diện (Phần C)

1. Cập nhật `scripts/collect_lab_evidence.py` để bổ sung xác thực cho `idempotency-observations.json` (kiểm tra đầy đủ 7 scenarios, trường `observed`, blocking evidence ID3, SQLSTATE 23514 trong ID5, bảo toàn stock trong ID6).
2. Mở rộng `scripts/tests/test_collect_lab_evidence.py` lên 18 tests (toàn bộ PASS).
3. Chạy `python scripts/collect_lab_evidence.py --run --prompt prompt19`:
   - Maven reactor hoàn thành 100%: 34 tests trong Core Lab + 82 tests trong Backend App = 116 tests PASS.
   - Evidence bundle được quảng bá thành công vào `docs/evidence/prompt19/` với đầy đủ 9 file observations, manifest, summary, và log.

---

## 4. Nghiệm Thu Các Acceptance Gates (A–G)

- [x] **Gate A — Baseline/Diff:** Hoàn thành sửa đổi tài liệu Prompt 18 theo đúng source/evidence; không sửa code đang đúng để khớp tài liệu sai.
- [x] **Gate B — Schema/Service:** Scope key, canonical fingerprint, chính sách kết quả, ràng buộc CHECK và ranh giới `PROPAGATION_REQUIRED` được phân định rõ ràng.
- [x] **Gate C — Runtime:** Toàn bộ 7 kịch bản ID1..ID7 được kiểm chứng với assertions nghiêm ngặt trên PostgreSQL thật (0 tests skipped).
- [x] **Gate D — Concurrency:** ID3 bắt được bằng chứng chờ khóa thật qua `pg_blocking_pids` (`[workerAPid]`), các worker đóng an toàn, không rò rỉ luồng.
- [x] **Gate E — Atomicity:** ID5 và ID7 chứng minh cơ chế rollback sạch sẽ cả claim, stock và purchase; các lần retry tiếp theo thành công.
- [x] **Gate F — Evidence/Regression:** Run ID thống nhất; validator Python và full reactor 116 tests đều PASS.
- [x] **Gate G — Learning:** Tài liệu kỹ thuật `M2-E6A.md` và bài tập `M2-E6A-exercise.md` đầy đủ; trạng thái học viên giữ nguyên `[AWAITING_CANDIDATE]`.

---

## 8. Bổ Sung Đính Chính Từ Review Prompt 20 (Audit Findings)

1. **Chuẩn hóa danh xưng bảng:** Bảng idempotency trong V5 tên chính xác là `market_idempotency`, bảng đơn hàng trong V3 tên là `market_purchase`. Không dùng `_record` hay `_order`.
2. **Khái niệm Caller & Security:** `caller_id` là input đáng tin cậy ở tầng giao tiếp application; hệ thống chưa có lớp xác thực token / authorization.
3. **Phân biệt xung đột dữ liệu và tấn công:** Việc gửi sai payload với cùng key là xung đột logic (`IdempotencyConflictException`), không vội vàng coi là tấn công an ninh mạng.
4. **Giới hạn băm & nghiệp vụ:** Hàm băm `request_fingerprint` không đảm bảo tính độc nhất tuyệt đối về mặt toán học; service luôn so sánh đối chứng cả SKU và quantity.
5. **Cơ chế đo lường Lock:** Trong kịch bản ID3, phép đo thực tế là kiểm tra `workerAPid in pg_blocking_pids(workerBPid)` với `lock_timeout = '10s'`, `statement_timeout = '15s'`. Không tự suy đoán lock mode `ExclusiveLock` trên index tuple khi chưa truy vấn catalog `pg_locks`.
6. **Kích hoạt rollback ID7:** Rollback được kích hoạt do ném `RuntimeException` trong outer callback, không thông qua cờ `setRollbackOnly()`.
7. **Lỗ hổng CHECK V5:** Xác nhận V5 thiếu `result_code IS NOT NULL` dẫn tới việc PostgreSQL chấp nhận bản ghi `COMPLETED / null / null` do cơ chế 3-valued logic. Vấn đề được giải quyết triệt để tại Prompt 20 qua migration V6.
