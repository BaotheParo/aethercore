# Prompt 13 Execution Notes: Trustworthy Evidence & M2-E2A PostgreSQL Deadlock Lab

**Ngày thực hiện:** 12/10/2026  
**Môi trường thực thi:**  
- OS: Windows 11 / PowerShell  
- JDK: Oracle OpenJDK 22 (build 22+36-2370)  
- Maven: Apache Maven 3.9.12  
- Docker Engine: Docker Desktop 29.5.3 (API 1.54)  
- Database Testcontainers: PostgreSQL 16.15 alpine (Image `postgres:16-alpine`)  
- Framework: Spring Boot 3.3.4, Spring Framework 6.1.13, HikariCP 5.1.0, Hibernate 6.5.3.Final  

---

## 1. Mục Tiêu và Phạm Vi Thực Hiện

Phiên Prompt 13 giải quyết dứt điểm hai khối nhiệm vụ then chốt:
1. **Phần A (Trustworthy Evidence & Test Decoupling):**
   - Khép lại lỗ hổng của script collector cũ (`scripts/collect_lab_evidence.py`): Loại bỏ hoàn toàn các chuỗi hardcode `BUILD SUCCESS`, `0 Failures`, `[PASS]` vô điều kiện. Bổ sung chế độ `--run` chạy lệnh Maven thật, bắt exit code thật, bắt live log vào thư mục tạm ngoài target, kiểm tra tính hợp lệ của Surefire XML và schema/runId của các tệp observation JSON.
   - Viết bộ unit test chuyên biệt bằng Python (`scripts/tests/test_collect_lab_evidence.py`) với 7 test cases bao phủ mọi kịch bản lỗi cố ý (missing reports, malformed XML, failures/errors, skipped, nonzero exit code, missing/duplicate caseId, wrong runId).
   - Tách biệt hoàn toàn việc xuất observation JSON: Cả 3 test suites (`TransactionProxyExperimentsTest`, `TransactionPropagationExperimentsTest`, `PostgresDeadlockExperimentsTest`) không ghi trực tiếp vào `docs/evidence/` từ `@AfterAll` mà xuất vào `backend-app/target/lab-evidence/<runId>` sử dụng Jackson `ObjectMapper`. Nếu xảy ra lỗi ghi file, test ném `RuntimeException` để fail suite ngay lập tức.
   - Đính chính tên các class kiểm thử lịch sử trong các tệp notes bằng tên thật từ Surefire XML.
2. **Phần B (M2-E2A: PostgreSQL Deadlock, Lock Ordering & Retry):**
   - Xây dựng schema kiểm thử cô lập: `deadlock_counter (id INT, value BIGINT)` và `deadlock_operation (operation_id UUID)`.
   - Xây dựng suite tích hợp `PostgresDeadlockExperimentsTest.java` bao phủ 3 kịch bản:
     - **DL1 (Controlled Deadlock):** Tái hiện chu trình chờ khóa giữa 2 worker ngược thứ tự ([1, 2] vs [2, 1]) qua barrier. Xác minh SQLSTATE `40P01` trên victim, survivor commit, và tính nguyên tử (thao tác trước đó của victim bị rollback sạch; invariant $A=1, B=1, \text{marker}=1$).
     - **DL2 (Consistent Lock Ordering):** Chuẩn hóa thứ tự ID tăng dần `[1, 2]`. Xác minh contention thật sự qua `pg_blocking_pids` trước khi nhả chốt. Cả 2 worker commit thành công, không phát sinh `40P01` (invariant $A=2, B=2, \text{markers}=2$).
     - **DL3 (Whole-Transaction Retry):** Bắt lỗi `40P01` bên ngoài transaction boundary của victim, mở transaction mới độc lập (chứng minh bằng `txid_current` khác biệt: 751 vs 749), thực thi lại toàn bộ chu trình và phục hồi tính toàn vẹn (invariant $A=2, B=2, \text{markers}=2$).
3. **Phần C (Tài Liệu, Bài Tập & Mapping):**
   - Tạo tài liệu phân tích cơ chế `docs/experiments/M2-E2A.md` và bài tập thực hành 3 hàng `docs/exercises/M2-E2A-exercise.md`.
   - Cập nhật `docs/prediction-log.md` cho DL1..DL3 và `docs/question-tracker.md` (bổ sung SQL59, SQL60; liên kết SQL53, SQL54, SQL58).

---

## 2. Kết Quả Kiểm Thử Độc Lập

### 2.1. Python Collector Validation Tests (Gate A)
Chạy lệnh kiểm thử đơn vị Python:
```powershell
python -m unittest discover -s scripts/tests -p "test_*.py"
```
- Kết quả: **Ran 7 tests in 0.077s — OK (100% PASS)**.
- Các ca kiểm thử bao phủ:
  1. `test_missing_reports_dir_raises`: Thư mục rỗng ném `ValidationError`.
  2. `test_malformed_xml_raises`: XML hỏng cấu trúc ném `ValidationError` chỉ rõ file.
  3. `test_xml_with_failure_or_error_detected`: Phát hiện chính xác failures/errors và gắn nhãn FAIL.
  4. `test_xml_with_skipped_detected`: Phát hiện chính xác skipped và vi phạm strict acceptance.
  5. `test_nonzero_exit_code_in_manifest`: Exit code khác 0 kích hoạt BUILD FAILURE.
  6. `test_observation_missing_case_or_wrong_runid`: Bắt chính xác sai lệch `runId`, thiếu caseId (như thiếu TX8), hoặc trùng lặp caseId.
  7. `test_valid_input_with_nested_suites_rollup`: Tính toán chính xác tổng số tests với các nested suites.

### 2.2. Phân Bổ Test Counts Thực Tế Của Reactor (Gate G)
- **Module 1 (`core-lab`): 34 tests (100% PASS)**
  - `PlayerKeyExperimentsTest` & nested (`VariantA..D`): 8 tests
  - `WrapperAndPrecisionExperimentsTest` & nested: 13 tests
  - `RecordExperimentsTest` & nested: 5 tests
  - `DamageStrategyExperimentsTest` & nested: 8 tests
- **Module 2 (`backend-app`): 49 tests (100% PASS)**
  - `LabEntryIntegrationTest`: 3 tests
  - `DamageStrategyWiringTest`: 2 tests
  - `MarketLockingConcurrencyTest`: 11 tests
  - `MarketPurchaseConcurrencyTest`: 9 tests
  - `JpaVersionExperimentsTest`: 6 tests
  - `TransactionProxyExperimentsTest`: 9 tests (TX1..TX8 + AOP verification)
  - `TransactionPropagationExperimentsTest`: 6 tests (PR1..PR6)
  - `PostgresDeadlockExperimentsTest`: 3 tests (DL1..DL3)
- **Tổng cộng Reactor:** **83 tests PASS | 0 Failures | 0 Errors | 0 Skipped | ALL PASS (100%)**.

---

## 3. Danh Mục Tệp Tạo Mới và Cập Nhật

1. `backend-app/src/test/resources/sql/deadlock-probe.sql`: Schema bảng đếm và marker deadlock.
2. `backend-app/src/test/java/com/aethercore/backend/locking/PostgresDeadlockExperimentsTest.java`: Suite thực nghiệm DL1–DL3.
3. `backend-app/src/test/java/com/aethercore/backend/transaction/TransactionProxyExperimentsTest.java`: Xuất evidence vào target với Jackson ObjectMapper và runId.
4. `backend-app/src/test/java/com/aethercore/backend/transaction/TransactionPropagationExperimentsTest.java`: Xuất evidence vào target, reset state, assert TransactionManager capabilities.
5. `scripts/collect_lab_evidence.py`: Script thu thập evidence tin cậy hỗ trợ `--run` và strict validation.
6. `scripts/tests/test_collect_lab_evidence.py`: Bộ 7 unit tests cho collector.
7. `docs/experiments/M2-E2A.md`: Tài liệu thực nghiệm Deadlock, Lock Ordering và Retry.
8. `docs/exercises/M2-E2A-exercise.md`: Bài tập thực hành 3 hàng và 4 câu hỏi oral.
9. `docs/experiments/prompt12-execution-notes.md`: Đính chính tên class XML thực tế.
10. `docs/experiments/prompt13-execution-notes.md`: Tệp nhật ký này.
11. `docs/prediction-log.md`: Bổ sung kịch bản DL1–DL3.
12. `docs/question-tracker.md`: Bổ sung SQL59, SQL60 và tinh chỉnh liên kết SQL53, SQL54, SQL58.
