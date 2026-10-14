# Nhật Ký Thực Thi Prompt 21: M2-E7B — Non-Transactional Migration, INVALID Index & Phục Hồi Có Kiểm Chứng

**Thời gian thực thi:** 14/10/2026
**Mục tiêu:** Thực hiện toàn diện module M2-E7B: Khôi phục văn bản và đính chính kỹ thuật từ Prompt 20, xây dựng lab kiểm thử non-transactional migration (`CREATE INDEX CONCURRENTLY`), khảo sát trạng thái catalog INVALID index, chứng minh giới hạn của `flyway.repair()`, kiểm chứng quy trình phục hồi an toàn và hoàn tất hệ thống tài liệu/bài tập.

---

## 1. Tóm Tắt Nhiệm Vụ & Phân Chia Các Phần

1. **Part A (Khôi phục văn bản & Tinh chỉnh bằng chứng sẵn có):**
   - Khôi phục 100% các file bị lỗi Unicode mang dấu `?` do piping PowerShell: `M2-E7A.md`, `M2-E7A-exercise.md`, `prompt20-execution-notes.md`, `M2-E6A.md`, `prompt19-execution-notes.md`, `prediction-log.md`.
   - Đính chính các khái niệm kỹ thuật cốt lõi:
     * Checksum của Flyway 10.10.0 là số nguyên CRC32 (`java.util.zip.CRC32`), phân biệt với mã băm SHA-256 byte-level trong `computeV6ResourceHash()` của lab harness.
     * Tên constraint trong SQL V5/V6 là `chk_idempotency_state_shape` (không dùng `chk_market_idempotency_state_shape`).
     * Luận lý học 3-valued logic: Phép so sánh `= NULL` sinh ra `UNKNOWN`; `IS NULL` trên NULL sinh ra `TRUE`; `IS NOT NULL` trên NULL sinh ra `FALSE`. Mệnh đề `CHECK` chấp nhận cả `TRUE` và `UNKNOWN`.
     * Timeout trong kịch bản ID3 được thiết lập cụ thể: `lock_timeout = '10s'`, `statement_timeout = '15s'`.
     * Nhấn mạnh việc xóa bản ghi malformed chỉ thực hiện trên fixture của lab, không phải khuyến nghị production tổng quát.
   - Nâng cấp `MarketIdempotencyMigrationTest.java`:
     * Đọc an toàn giá trị SQL NULL của cột checksum trong `flyway_schema_history` (`rs.getObject("checksum") != null ? ((Number) rs.getObject("checksum")).intValue() : null`).
     * Xuất đầy đủ raw snapshots trong MG1 (`historyBefore`, `historyAfter`) và MG3 (`historyBefore`, `historyAfter`, `rowsBefore`, `rowsAfter`, `v5ConstraintDef`, `restoredConstraintDef`).
     * Trong MG3, assert equality của `rowsBefore` và `rowsAfter` sau DDL rollback, và assert SQLSTATE `23514` cùng tên constraint `chk_idempotency_state_shape` khi chèn bản ghi vi phạm V5 shape.
   - Trong `MarketIdempotencyIntegrationTest.java`: Bổ sung `arr.free()` trong khối `finally` cho `java.sql.Array` để tránh rò rỉ JDBC resource.
   - Trong `scripts/collect_lab_evidence.py`: Bổ sung kiểm tra mảng `truthTable` của MG2 có đúng 8 tổ hợp đầu vào duy nhất.
   - Trong `scripts/tests/test_collect_lab_evidence.py`: Bổ sung negative test từ chối payload nếu `truthTable` chứa variant trùng lặp.

2. **Part B (Thiết kế & Thực thi Lab Non-Transactional Migration NC1–NC3):**
   - Tạo các tài nguyên migration dành riêng cho test tại `backend-app/src/test/resources/lab-migrations/nontransactional-index/`:
     * `V1__create_index_probe.sql`: Tạo bảng `index_probe` và seed 3 dòng `(1, 'IRON'), (2, 'IRON'), (3, 'GOLD')`.
     * `V2__create_unique_token_index.sql`: `CREATE UNIQUE INDEX CONCURRENTLY uq_index_probe_token ON index_probe (token);`.
     * `V2__create_unique_token_index.sql.conf`: `executeInTransaction=false`.
   - Tuyệt đối không can thiệp hay thay đổi các migration chính V1–V6 của ứng dụng.
   - Xây dựng suite `NonTransactionalIndexMigrationTest.java` sử dụng Testcontainers PostgreSQL 16.15 và Flyway 10.10.0:
     * Cấu hình đồng thời `executeInTransaction=false` và `flyway.postgresql.transactional.lock=false` (session-level advisory lock).
     * Thiết lập timeout hữu hạn cho kết nối: `lock_timeout = '5s'`, `statement_timeout = '20s'`.
     * **NC1:** Chạy `CREATE INDEX CONCURRENTLY` trong transaction block (`conn.setAutoCommit(false)`). Bị PostgreSQL từ chối fail-fast với SQLSTATE `25001` (`active_sql_transaction`). Rollback xác nhận 0 index tồn tại, 3 dòng dữ liệu nguyên vẹn.
     * **NC2:** Chạy migration V2 non-transactional trên bảng có dữ liệu trùng lặp (`IRON`). Ném `FlywayException` với root cause SQLSTATE `23505` (`unique_violation`). Kiểm tra catalog `pg_index` xác nhận index tồn tại nhưng mang trạng thái **`indisvalid = false`** (INVALID index)! Bảng `flyway_schema_history` lưu V2 với `success = false`. Bảng dữ liệu giữ nguyên 3 dòng. `flyway.validate()` thất bại do có migration hỏng.
     * **NC3:** Khảo sát quy trình phục hồi qua 4 Checkpoint:
       - Checkpoint 1 (Trước repair): V2 failed trong history, index INVALID trong catalog, 1 nhóm trùng lặp.
       - Checkpoint 2 (Sau repair): `flyway.repair()` xóa dòng V2 failed khỏi history; nhưng index trong catalog **vẫn tồn tại và vẫn INVALID** (`indisvalid = false`), dữ liệu trùng lặp vẫn còn nguyên. Chứng minh `repair` không sửa objects hay dữ liệu nghiệp vụ.
       - Checkpoint 3 (Dọn dẹp): Xóa index hỏng bằng `DROP INDEX nc3_schema.uq_index_probe_token;`, chuẩn hóa dữ liệu dòng 2 thành `'SILVER'`. Đo lường hash SHA-256 của file V1, V2, V2.conf hoàn toàn không đổi.
       - Checkpoint 4 (Retry): `flyway.migrate()` thành công. Catalog ghi nhận index mới **`indisvalid = true`**, `indisunique = true`, `indisready = true`. Thử chèn trùng lặp bị chặn với SQLSTATE `23505`; chèn hợp lệ `'DIAMOND'` thành công đạt 4 dòng. `flyway.validate()` thành công, lặp lại migrate chạy 0 script.
   - Xuất dữ liệu quan sát sang `nontransactional-index-observations.json`.

3. **Part C (Collector & Negative Unit Tests):**
   - Nâng cấp `scripts/collect_lab_evidence.py`:
     * Thêm required set `nontransactional-index-observations.json` cho `--prompt prompt21`.
     * Xây dựng hàm `_validate_nontransactional_index_scenario` xác thực chặt chẽ NC1, NC2, NC3 (kiểm tra SQLSTATE, cờ catalog `indisvalid`, tính toàn vẹn 4 checkpoint, `fileHashesUnchanged`).
   - Cập nhật `scripts/tests/test_collect_lab_evidence.py`:
     * Bổ sung đầy đủ các negative mutations: NC1 sai SQLSTATE hoặc còn index sau rollback; NC2 index bị giả mạo thành valid hoặc history sửa thành success; NC3 sau repair index tự biến mất/thành valid; NC3 retry sai hash hoặc insert trùng không bị chặn; thiếu caseId hoặc lệch runId.
     * Toàn bộ 22 unit tests của collector: **PASS 100%**.

4. **Part D (Tài liệu, Reverse Learning & Mapping):**
   - Biên soạn `docs/experiments/M2-E7B.md`: So sánh chi tiết Transactional vs Non-transactional migration, phân tích cơ chế INVALID index, hai loại cấu hình transaction, và giới hạn của `flyway.repair()`.
   - Biên soạn `docs/exercises/M2-E7B-exercise.md`: Bài tập tình huống bảng `guild_member` trùng email, câu hỏi phân tích catalog, đề xuất quy trình xử lý dữ liệu có phê duyệt, 3 câu oral interview 60–90 giây kèm rubric chi tiết; giữ nguyên trạng thái `[AWAITING_CANDIDATE]`.
   - Cập nhật `docs/prediction-log.md` bổ sung 3 bản ghi dự đoán NC1, NC2, NC3.
   - Cập nhật `docs/question-tracker.md`:
     * **SB59:** Nâng lên `VERIFIED` kỹ thuật cho cả transactional và non-transactional Flyway.
     * **SQL69:** Nâng lên `PARTIAL` (kiểm chứng CONCURRENTLY, SQLSTATE 25001, INVALID index build failure và recovery; tải lớn chưa đo).
     * **SQL68:** Nâng lên `PARTIAL` bổ sung duplicate data unique index failure & cleanup.
     * **SB60:** Giữ nguyên `PARTIAL`.

---

## 2. Bảng Đối Soát Nghiệm Thu Gates

| Gate | Điều kiện nghiệm thu | Trạng thái thực tế | Bằng chứng kiểm tra |
|:---:|---|:---:|---|
| **Gate A** | Docs bị mất dấu Unicode đã phục hồi; checksum/name/timeout đúng | **PASSED** | Đã phục hồi nguyên vẹn UTF-8 trong `M2-E7A.md`, `M2-E7A-exercise.md`, `prompt20-execution-notes.md`, `M2-E6A.md`, `prompt19-execution-notes.md`, `prediction-log.md`; đính chính CRC32 vs SHA-256, `chk_idempotency_state_shape`, 10s/15s timeout. |
| **Gate B** | Main migrations V1–V6 không đổi; test-only locations cô lập | **PASSED** | V1–V6 giữ nguyên 100%; test migrations cô lập tại `backend-app/src/test/resources/lab-migrations/nontransactional-index/`. |
| **Gate C** | NC1 đo 25001/no index; NC2 đo 23505/INVALID/failed history | **PASSED** | NC1 đo SQLSTATE `25001`, catalog `exists = false`; NC2 đo SQLSTATE `23505`, catalog `indisvalid = false`, history `success = false`. |
| **Gate D** | NC3 repair không đổi object/data; cleanup fixture; retry cùng script success | **PASSED** | NC3 đo checkpoint 2 index vẫn `indisvalid = false` và duplicate `IRON` còn nguyên sau repair; checkpoint 3 dọn dẹp không đổi file hash; checkpoint 4 retry thành công và index thành `indisvalid = true`. |
| **Gate E** | Evidence có raw checkpoints; validator từ chối mutations; MG snapshots hữu ích | **PASSED** | `nontransactional-index-observations.json` lưu đủ 4 checkpoints; validator kiểm tra nghiêm ngặt; 22 unit tests của collector PASS. |
| **Gate F** | Full Java/Python regression pass, không failure/error/skip | **PASSED** | 14/14 Java test methods PASS (`NonTransactionalIndexMigrationTest`, `MarketIdempotencyMigrationTest`, `MarketIdempotencyIntegrationTest`); 22/22 Python tests PASS. |
| **Gate G** | Learning docs/bài tập sẵn sàng; giữ nguyên `AWAITING_CANDIDATE` | **PASSED** | `M2-E7B.md` và `M2-E7B-exercise.md` hoàn thiện; candidate answer giữ `[AWAITING_CANDIDATE]`. |
