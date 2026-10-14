# Nhật Ký Thực Thi Prompt 20: M2-E7A — CHECK/NULL, Flyway Forward Migration & DDL Rollback

**Thời gian thực thi:** 14/10/2026
**Mục tiêu:** Thực hiện toàn diện module thực nghiệm M2-E7A: Tái hiện lỗ hổng 3-valued logic trên CHECK constraint V5, nâng cấp Forward Migration V6 bảo toàn dữ liệu, kiểm chứng DDL Rollback của PostgreSQL khi gặp dữ liệu cũ không hợp lệ, và củng cố harness kiểm thử tự động.

---

## 1. Tóm Tắt Nhiệm Vụ & Phân Chia Các Phần

1. **Part A (Technical Refinements & Harness Solidification):**
   - Bổ sung đính chính kỹ thuật vào `docs/experiments/M2-E6A.md` và `docs/experiments/prompt19-execution-notes.md`.
   - Cập nhật `MarketIdempotencyIntegrationTest.java`: Cải tiến kịch bản ID3 sử dụng hai đối tượng service riêng biệt (`serviceA`, `serviceB`) và truy vấn trực tiếp mảng số nguyên từ `pg_blocking_pids(?::int)` để xác nhận `workerAPid` đang chặn `workerBPid`. Khung thời gian worker thiết lập `lock_timeout = '10s'` và `statement_timeout = '15s'`.
   - Cập nhật ID5 và ID7: Bổ sung snapshot đối chứng dữ liệu và idempotency claim sau khi transaction rollback, đảm bảo 0 dòng claim và 0 purchase tồn tại dở dang.
   - Nâng cấp schema validator trong `scripts/collect_lab_evidence.py` để xác thực toàn bộ các trường quan sát của ID3, ID5, ID7 và bổ sung negative unit tests tương ứng trong `scripts/tests/test_collect_lab_evidence.py`.

2. **Part B (Forward Migration V6):**
   - Tạo file migration `backend-app/src/main/resources/db/migration/V6__fix_market_idempotency_state_shape.sql`.
   - Giữ nguyên vẹn 100% các file migration V1 đến V5 đã commit từ trước.
   - Định nghĩa lại ràng buộc `chk_idempotency_state_shape`: Thêm điều kiện `result_code IS NOT NULL` ở nhánh `COMPLETED` để triệt tiêu giá trị `UNKNOWN`.

3. **Part C (PostgreSQL Migration Integration Tests):**
   - Xây dựng suite kiểm thử `MarketIdempotencyMigrationTest.java` sử dụng Testcontainers PostgreSQL 16.15 và Flyway 10.10.0 độc lập:
     * **MG1:** Tái hiện lỗi V5 (chấp nhận `COMPLETED/null/null`), migrate lên V6 thành công, bảo toàn nguyên vẹn 3 dòng dữ liệu hợp lệ (`PROCESSING`, `COMPLETED/NOT_AVAILABLE`, `COMPLETED/SUCCESS`), lặp lại `migrate()` chạy 0 script và history giữ nguyên 6 bản ghi.
     * **MG2:** Lập bảng chân lý 8 tổ hợp đầu vào: chấp nhận đúng 3 trạng thái hợp lệ; từ chối dứt khoát 5 trạng thái sai với SQLSTATE `23514` (`check_violation`). Thử nghiệm lệnh UPDATE dòng hợp lệ thành `COMPLETED/null/null` bị từ chối với SQLSTATE `23514`, dữ liệu cũ giữ nguyên.
     * **MG3:** Tái hiện DDL rollback khi bảng có dữ liệu cũ không hợp lệ: migration V6 thất bại với SQLSTATE `23514`, PostgreSQL kích hoạt rollback hoàn tác lệnh DROP CONSTRAINT trước đó; constraint V5 ban đầu được bảo toàn; bảng `flyway_schema_history` không chứa dòng V6 failed; xóa fixture lỗi trên lab và retry cùng file V6 (hash không đổi) thành công 100%.

4. **Part D (Collector & Evidence Promotion):**
   - Xuất dữ liệu quan sát sang `migration-observations.json`.
   - Cập nhật collector hỗ trợ required set `migration-observations.json` gồm MG1..MG3.
   - Thêm negative unit tests cho collector từ chối payload sai lệch của migration.
   - Chạy full reactor sạch sẽ và promote evidence sang `docs/evidence/prompt20/`.

---

## 2. Bảng Đối Soát Nghiệm Thu Gates

| Gate | Điều kiện nghiệm thu | Trạng thái thực tế | Bằng chứng |
|:---:|---|:---:|---|
| **Gate A** | Sửa các nhầm lẫn kỹ thuật Prompt 19 trong docs | **PASSED** | Đã bổ sung mục 9 trong `M2-E6A.md` và mục 8 trong `prompt19-execution-notes.md` |
| **Gate B** | ID3 đo `pg_blocking_pids` thật, ID5/ID7 assert sau rollback | **PASSED** | `MarketIdempotencyIntegrationTest.java` đo mảng PID, kiểm tra clean rollback; 8/8 test PASS |
| **Gate C** | Migration V6 forward độc lập, V1–V5 giữ nguyên | **PASSED** | File `V6__fix_market_idempotency_state_shape.sql` mới; V1..V5 không thay đổi dù chỉ 1 byte |
| **Gate D** | MG1..MG3 chứng minh flaw, shape fix, DDL rollback và retry | **PASSED** | `MarketIdempotencyMigrationTest.java` 3/3 test PASS; đo thật trên PostgreSQL 16.15 |
| **Gate E** | Collector có required set mới, negative tests kiểm tra schema | **PASSED** | 22/22 unit tests của `test_collect_lab_evidence.py` PASS; bắt lỗi payload sai lệch |
| **Gate F** | Full Maven reactor PASS; evidence promoted đầy đủ | **PASSED** | Full clean test reactor PASS; bằng chứng lưu tại `docs/evidence/prompt20/` |
| **Gate G** | Tài liệu M2-E7A, bài tập và tracker cập nhật | **PASSED** | Hoàn thành `M2-E7A.md`, `M2-E7A-exercise.md`, `prediction-log.md`, `question-tracker.md` |
