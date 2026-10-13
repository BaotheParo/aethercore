# Ghi chép Thực thi Prompt 14 (Execution Notes)

**Mã phiên:** Prompt 14 — M2-E4A: JPA Fetching, N+1, JOIN FETCH, EntityGraph, and DTO Projection
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15, Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Base Git HEAD:** `96ddc023a27d256d497c111c92266b34fe7971f4`

---

## 1. Tóm tắt Phạm vi và Mục tiêu Đạt được

Phiên làm việc hoàn thành đầy đủ tất cả các yêu cầu kỹ thuật và tài liệu theo hợp đồng Prompt 14:
1. **Hoàn thiện nền tảng & Evidence Pipeline (Mục 4):**
   - Sửa `scripts/collect_lab_evidence.py`: Đảm bảo tạo lại thư mục staging sau `mvn clean`, ghi nhận mã thoát (exit code) thực tế của Maven, không gán mặc định exitCode = 0, lưu log dưới dạng `maven-test-output.txt` để không bị `.gitignore` nuốt chửng, và bảo toàn log/metadata khi gặp lỗi mà không promote bừa bãi trạng thái PASS.
   - Bổ sung 3 regression test trong `scripts/tests/test_collect_lab_evidence.py` (tổng cộng 10 unit tests, 100% PASS).
   - Chuẩn hóa cleanup và bắt `InterruptedException` trong `PostgresDeadlockExperimentsTest.java` (hàm `cleanupExecutor`), rút ngắn observer deadline DL2 xuống 5s (< 10s gate hold), truy vấn động phiên bản PostgreSQL engine thay vì hardcode literal.
   - Chuẩn hóa truy vấn động phiên bản PostgreSQL trong `TransactionProxyExperimentsTest.java` và `TransactionPropagationExperimentsTest.java`.
2. **Triển khai Lab M2-E4A (Mục 5 & 6):**
   - Tạo DDL probe độc lập `backend-app/src/test/resources/sql/fetch-probe.sql` (`fetch_buyer`, `fetch_order`).
   - Cấu hình persistence unit biệt lập `aethercore-fetch-jpa-test` trong `persistence.xml` (RESOURCE_LOCAL, Hibernate 6.5, statistics enabled, 2nd-level cache disabled, batch fetch size = 1, statement inspector đăng ký độc lập).
   - Xây dựng các entity `FetchBuyer`, `FetchOrder` (ManyToOne LAZY), DTO projection `OrderSummary`, và `FetchStatementInspector`.
   - Viết test suite `JpaFetchExperimentsTest.java` với 5 kịch bản (NQ1..NQ5) đo đạc prepared statement counts và xuất dữ liệu `fetch-observations.json`.
3. **Thực thi Kiểm thử & Nghiệm thu Toàn diện (Mục 7 & 8):**
   - Chạy thành công suite unit test Python (10 tests PASS trong 0.9s).
   - Chạy targeted Maven test (`JpaFetchExperimentsTest` + `PostgresDeadlockExperimentsTest`: 8 tests PASS).
   - Chạy full suite evidence collection (`python scripts/collect_lab_evidence.py --run --prompt prompt14`): Full Maven reactor chạy thành công 88 tests (34 core-lab + 54 backend-app), 0 failures, 0 errors, 0 skipped.
   - Thẩm định và promote trọn vẹn 4 bộ observation files (`proxy-observations.json`, `propagation-observations.json`, `deadlock-observations.json`, `fetch-observations.json`) vào `docs/evidence/prompt14/`.
4. **Tài liệu Kỹ thuật & Theo dõi Học tập (Mục 9 & 10):**
   - Soạn thảo `docs/experiments/M2-E4A.md` phân tích chuyên sâu cơ chế và so sánh chiến lược.
   - Soạn thảo `docs/exercises/M2-E4A-exercise.md` với bài tập tự làm (dataset D3), 6 câu hỏi oral defense không kèm đáp án, và rubric dành cho mentor.
   - Cập nhật `docs/prediction-log.md` (NQ1–NQ5) và `docs/question-tracker.md` (SB50, SB52, SB53, SQL51, SB54).

---

## 2. Số liệu Đo đạc Thực tế (NQ1 – NQ5)

- **Run ID:** `prompt14-20261013-093356-4efe20`
- **Thời gian chạy:** 2026-10-13T09:33:56.578370+07:00

| Case | Dataset | Chiến lược | Root Stmts | Final Stmts | SQL Count | Result Rows | Assertions |
|:---|:---|:---|:---:|:---:|:---:|:---:|:---:|
| **NQ1** | D1 (6 buyers, 6 orders) | Baseline N+1 | 1 | 7 | 7 | 6 | TRUE |
| **NQ2** | D1 (6 buyers, 6 orders) | JOIN FETCH | 1 | 1 | 1 | 6 | TRUE |
| **NQ3** | D1 (6 buyers, 6 orders) | Dynamic EntityGraph | 1 | 1 | 1 | 6 | TRUE |
| **NQ4** | D1 (6 buyers, 6 orders) | JPQL Constructor Projection | 1 | 1 | 1 | 6 | TRUE |
| **NQ5** | D2 (3 buyers, 6 orders) | Baseline N+1 + 1st-level cache | 1 | 4 | 4 | 6 | TRUE |

---

## 3. Đối chiếu Các Gate Nghiệm thu

- **Gate A — Source:** Giữ nguyên baseline, JPA/Hibernate chỉ nằm trong test scope, không kéo thêm Spring Data JPA vào `pom.xml`, unit `aethercore-market-jpa-test` giữ nguyên vẹn.
- **Gate B — Failure evidence:** Regression test chứng minh Maven fail sau clean vẫn giữ log/metadata, exit code != 0, không publish PASS.
- **Gate C — Deadlock:** DL1..DL3 bảo toàn các bất biến cũ, worker cleanup được kiểm soát hữu hạn qua `cleanupExecutor` và `f.cancel(true)`.
- **Gate D — Fetch:** NQ1..NQ5 thỏa mãn hoàn toàn hợp đồng nghiệp vụ đọc lịch sử đơn hàng trên D1 và D2; prepared statements và SQL capture được đo đạc thực tế từ Hibernate Statistics & StatementInspector.
- **Gate E — Regression:** Reactor đạt 88 tests (34 core-lab + 54 backend-app), 0 failure, 0 error, 0 skipped.
- **Gate F — Artifact:** Run ID thống nhất xuyên suốt, đủ 4 file observation và log `maven-test-output.txt` được lưu trữ đầy đủ.
- **Gate G — Learning:** Tài liệu M2-E4A, exercise M2-E4A-exercise, prediction log và question tracker được cập nhật chuẩn xác; câu trả lời của ứng viên giữ nguyên `[AWAITING_CANDIDATE]`.
