# Prompt 12 Execution Notes: M2-E3B Transaction Propagation Lab & Evidence Collection

**Ngày thực hiện:** 12/10/2026  
**Môi trường thực thi:**  
- OS: Windows 11 / PowerShell  
- JDK: Oracle OpenJDK 22 (build 22+36-2370)  
- Maven: Apache Maven 3.9.12  
- Docker Engine: Docker Desktop 29.5.3 (API 1.54)  
- Database Testcontainers: PostgreSQL 16.15 alpine (Image `postgres:16-alpine`)  
- Framework: Spring Boot 3.3.4, Spring Framework 6.1.13, HikariCP 5.1.0, Hibernate 6.5.3.Final  

---

## 1. Mục tiêu và Phạm vi Phiên làm việc

Phiên Prompt 12 tập trung vào ba khối nhiệm vụ chính:
1. **Phần A (Baseline Refinement & Probe Isolation):**
   - Đính chính và giải thích sự khác biệt giữa số lượng test method và Surefire XML test count trong `docs/experiments/prompt11-execution-notes.md`.
   - Tách biệt hoàn toàn marker đo lường transaction khỏi các bảng nghiệp vụ (`market_purchase`, `market_stock`) bằng cách đưa vào bảng kiểm thử cô lập `tx_probe (id UUID PRIMARY KEY, case_id VARCHAR(40), phase VARCHAR(20))` trong `backend-app/src/test/resources/sql/tx-probe.sql`.
   - Cập nhật `TransactionProxyExperimentsTest.java` (TX1–TX8) sang sử dụng `tx_probe`.
   - Hiệu chỉnh tài liệu lý thuyết `M2-E3A.md` (chuẩn hóa thuật ngữ Spring behavior vs JLS, checked exception commit là mặc định theo đặc tả Spring chứ không phải tính đúng đắn nghiệp vụ, TX7 in-method catch) và bài tập `M2-E3A-exercise.md`.
2. **Phần B (M2-E3B Propagation Lab):**
   - Xây dựng bộ kiểm thử tự động `TransactionPropagationExperimentsTest.java` bao phủ 6 kịch bản lan truyền giao dịch kinh điển:
     - **PR1:** `REQUIRED` baseline (join logical transaction, commit đồng thời).
     - **PR2:** `REQUIRED` rollback-only (inner ném unchecked exception, outer catch nhưng vẫn nhận `UnexpectedRollbackException`, toàn bộ rollback).
     - **PR3:** `REQUIRES_NEW` commit độc lập (inner commit trên connection riêng, outer rollback nhưng inner vẫn tồn tại, kiểm chứng mid-flight qua raw JDBC `DriverManager`).
     - **PR4:** `REQUIRES_NEW` inner rollback độc lập (inner lỗi, outer catch và commit thành công phần của mình).
     - **PR5:** `NESTED` savepoint rollback (inner lỗi rollback về savepoint, outer catch và commit phần của mình trên cùng 1 connection).
     - **PR6:** `NESTED` outer rollback (inner thành công giải phóng savepoint nhưng outer lỗi sau đó khiến toàn bộ transaction bị hủy).
   - Đo đạc trực tiếp các chỉ số nội quan: `isNewTransaction()`, `hasSavepoint()`, `isRollbackOnly()`, và PostgreSQL Backend PID (`pg_backend_pid()`).
3. **Phần C (Provenance & Reproducible Evidence):**
   - Tạo script `scripts/collect_lab_evidence.py` phân tích Surefire XML và sinh báo cáo tổng hợp `docs/evidence/prompt12/test-summary.txt`.
   - Tự động xuất `docs/evidence/prompt12/propagation-observations.json` và `docs/evidence/prompt12/proxy-observations.json`.
   - Biên soạn tài liệu phân tích chuyên sâu `docs/experiments/M2-E3B.md` và bài tập thực hành `docs/exercises/M2-E3B-exercise.md`.
   - Đồng bộ `docs/prediction-log.md` và `docs/question-tracker.md` (nâng cấp SB27 và SQL53 lên FULL coverage).

---

## 2. Kết quả Thực thi Chi tiết

### 2.1. Phân bổ Kiểm thử (Surefire Test Counts)
Reactor hoàn thành 100% không có lỗi:

```
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for AetherCore Parent 0.0.1-SNAPSHOT:
[INFO] 
[INFO] AetherCore Parent .................................. SUCCESS
[INFO] AetherCore :: Core Lab ............................. SUCCESS [34 tests]
[INFO] AetherCore :: Backend App .......................... SUCCESS [46 tests]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total tests run: 80, Failures: 0, Errors: 0, Skipped: 0
```

- **Phân bổ Core Lab (34 tests thực tế từ Surefire XML):**
  - `PlayerKeyExperimentsTest` và nested (`VariantA`..`D`): 8 tests
  - `WrapperAndPrecisionExperimentsTest` và nested: 13 tests
  - `RecordExperimentsTest` và nested: 5 tests
  - `DamageStrategyExperimentsTest` và nested: 8 tests
- **Phân bổ Backend App (46 tests thực tế từ Surefire XML):**
  - `LabEntryIntegrationTest`: 3 tests
  - `DamageStrategyWiringTest`: 2 tests
  - `MarketLockingConcurrencyTest`: 11 tests
  - `MarketPurchaseConcurrencyTest`: 9 tests
  - `JpaVersionExperimentsTest`: 6 tests
  - `TransactionProxyExperimentsTest`: 9 tests (TX1..TX8 + AOP verification)
  - `TransactionPropagationExperimentsTest`: 6 tests (PR1..PR6)

### 2.2. Đo đạc Kịch bản Lan truyền Giao dịch (PR1 – PR6)

| Case ID | Outer Prop | Inner Prop | Outer PID | Inner PID | isNew (Out/In) | hasSavepoint | Exception ném ra | Outer Rows | Inner Rows |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PR1** | REQUIRED | REQUIRED | 61 | 61 | true / false | false | *(Không có)* | 1 | 1 |
| **PR2** | REQUIRED | REQUIRED | 61 | 61 | true / false | false | `UnexpectedRollbackException` | 0 | 0 |
| **PR3** | REQUIRED | REQUIRES_NEW | 61 | 62 | true / true | false | `OuterBusinessFailure` | 0 | 1 |
| **PR4** | REQUIRED | REQUIRES_NEW | 61 | 62 | true / true | false | *(Không có)* | 1 | 0 |
| **PR5** | REQUIRED | NESTED | 61 | 61 | true / false | true | *(Không có)* | 1 | 0 |
| **PR6** | REQUIRED | NESTED | 61 | 61 | true / false | true | `OuterBusinessFailure` | 0 | 0 |

### 2.3. Bằng chứng Mid-Flight Observation (PR3)
Trong kịch bản PR3, để chứng minh `REQUIRES_NEW` đã thực sự commit vật lý xuống PostgreSQL trước khi outer method ném ngoại lệ:
- Một kết nối raw JDBC hoàn toàn độc lập (`DriverManager.getConnection(...)`) được mở ngay sau khi inner method hoàn tất.
- Query trực tiếp bảng `tx_probe`:
  - `inner_rows` trả về `1` (đã commit vào DB, các kết nối khác đọc được ở mức Read Committed).
  - `outer_rows` trả về `0` (outer chưa commit nên isolation che giấu).
- Khi outer ném `OuterBusinessFailure`, outer transaction rollback: `outer_rows` vẫn là `0`, nhưng `inner_rows` đã là `1` vĩnh viễn.

---

## 3. Danh mục Tệp được Tạo và Cập nhật

1. `backend-app/src/test/resources/sql/tx-probe.sql`: Định nghĩa schema bảng `tx_probe`.
2. `backend-app/src/test/java/com/aethercore/backend/transaction/TransactionProxyExperimentsTest.java`: Refactor TX1–TX8 sang bảng `tx_probe`, bổ sung logic xuất evidence.
3. `backend-app/src/test/java/com/aethercore/backend/transaction/TransactionPropagationExperimentsTest.java`: Xây dựng lab PR1–PR6 với đầy đủ inner/outer service, mid-flight observer, và telemetry.
4. `scripts/collect_lab_evidence.py`: Script thu thập và phân tích kết quả Surefire XML.
5. `docs/evidence/prompt12/test-summary.txt`: Tổng hợp kết quả 80 test cases.
6. `docs/evidence/prompt12/propagation-observations.json`: Báo cáo JSON runtime của PR1–PR6.
7. `docs/evidence/prompt12/proxy-observations.json`: Báo cáo JSON runtime của TX1–TX8.
8. `docs/experiments/M2-E3A.md`: Hiệu chỉnh thuật ngữ chuẩn hóa.
9. `docs/exercises/M2-E3A-exercise.md`: Hiệu chỉnh bài tập proxy.
10. `docs/experiments/M2-E3B.md`: Tài liệu thực nghiệm chuyên sâu về Propagation và Rollback.
11. `docs/exercises/M2-E3B-exercise.md`: Bài tập Claim & Audit Trail và 4 câu hỏi phỏng vấn oral.
12. `docs/experiments/prompt11-execution-notes.md`: Đính chính test count baseline.
13. `docs/experiments/prompt12-execution-notes.md`: Tệp nhật ký này.
14. `docs/prediction-log.md`: Bổ sung 6 kịch bản PR1–PR6.
15. `docs/question-tracker.md`: Nâng cấp SB25, SB26, SB27, SQL53 lên FULL coverage.

---

## 4. Xác nhận Chất lượng (Quality Verification)

- **Zero Regression:** Toàn bộ 80 tests trên cả 2 module pass tuyệt đối.
- **Tách biệt dữ liệu:** Bảng `tx_probe` chỉ tồn tại trong test scope, không can thiệp vào production migrations (`V1..V4`).
- **Clean Diff:** Không có trailing whitespace (`git diff --check` sạch).
- **Candidate Integrity:** Giữ nguyên toàn bộ `[AWAITING_CANDIDATE]` trong các tệp bài tập và tracker.
