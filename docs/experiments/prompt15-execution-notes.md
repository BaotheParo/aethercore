# Ghi chép Thực thi Prompt 15 (Execution Notes)

**Mã phiên:** Prompt 15 — M2-E4B: Collection fetch, phân trang và truy vấn hai bước
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Base Git HEAD:** `81de50fd1eb602edc79fe7476f612f8201dc7503`

---

## 1. Đính chính và Làm rõ Báo cáo Prompt 14

1. **Về Git SHA:**
   - Git commit HEAD sau Prompt 14 trên repository là `81de50fd1eb602edc79fe7476f612f8201dc7503`. Toàn bộ thao tác Prompt 15 tiếp nối chính xác từ commit này, không reset hay viết đè lịch sử.
2. **Về Metadata Runtime (Mục A1):**
   - Trong Prompt 14, summary in literal `PostgreSQL 16.15` dù container có thể là tag chung. Trong Prompt 15, collector đã tách bạch rõ ràng giữa cấu hình định danh (`postgres:16-alpine (configured)`) và thông tin runtime được truy vấn động ghi nhận trong từng file observation (`SHOW server_version`).
   - Các framework được đánh dấu rõ là `pom resolved`, tránh ngộ nhận gán nhãn runtime framework ngoài phạm vi thực thi.
3. **Về ResourceWarning trong Python Runner:**
   - Đã xử lý triệt để cảnh báo `ResourceWarning: unclosed file` bằng cách gọi `process.stdout.close()` ngay sau khi stream log trong `collect_lab_evidence.py`.

---

## 2. Hoàn thiện Nền tảng & Evidence Pipeline (Mục 4)

- **Test Failure Path (A2):**
  - Cập nhật test `test_runner_failure_preserves_log_and_does_not_promote_pass`:
    1. Tạo sẵn bằng chứng PASS giả lập tại `docs/evidence/test-prompt` với file `sentinel.txt` mang nội dung `PREVIOUS_PASS_SENTINEL`.
    2. Stub lệnh runner mô phỏng xóa thực tế `backend-app/target`, in thông báo lỗi và thoát với mã 2.
    3. Mock `get_runtime_info` để test chạy độc lập hoàn toàn với môi trường máy chủ.
    4. Xác nhận staging directory được tạo lại, log đọc được thông điệp lỗi, manifest trong staging ghi nhận đúng `exitCode = 2` và `failureReason`.
    5. Xác nhận thư mục đích không bị ghi đè, file `sentinel.txt` vẫn nguyên vẹn.
- **Kết quả Suite Python:** 11 unit tests chạy thành công trong 0.395s, 0 lỗi, 0 cảnh báo.

---

## 3. Triển khai Lab M2-E4B (Mục 5, 6, 7)

1. **Entity `FetchBuyer`:**
   - Bổ sung `@OneToMany(mappedBy = "buyer", fetch = FetchType.LAZY) private List<FetchOrder> orders = new ArrayList<>();` và getter `getOrders()`.
   - Giữ nguyên các bất biến khác; kiểm tra hồi quy xác nhận suite NQ1..NQ5 trong `JpaFetchExperimentsTest` vẫn chạy thành công 100%.
2. **Cấu trúc Gói `com.aethercore.backend.pagination`:**
   - `PaginationStatementInspector.java`: Inspector cấp instance, độc lập cho từng EntityManagerFactory.
   - `BuyerOrdersView.java`: Record DTO chứa `buyerId`, `buyerName` và danh sách `OrderLine(orderId, quantity)` độc lập với Hibernate session.
   - `BuyerPageReader.java`: Thuật toán phân trang hai bước: Bước 1 lấy ID có database limit/offset, Bước 2 fetch collection bằng `IN :ids`, và khôi phục thứ tự theo mảng ID bước 1.
3. **Persistence Unit:**
   - Bổ sung `aethercore-pagination-jpa-test` trong `persistence.xml` (RESOURCE_LOCAL, Hibernate 6.5, `batch_fetch_size = 0`, tắt L2 cache).
4. **Test Suite `JpaCollectionPaginationExperimentsTest`:**
   - Khởi tạo 2 EMF riêng biệt: `unsafeEmf` (`fail_on_pagination_over_collection_fetch=false`) và `strictEmf` (`fail_on_pagination_over_collection_fetch=true`).
   - Thực thi thành công 5 kịch bản (PG1..PG5).

---

## 4. Số liệu Đo đạc Thực tế (PG1 – PG5)

- **Run ID:** `prompt15-20261013-103129-8d8ca1`
- **Thời gian chạy:** 2026-10-13T10:31:29.215736+07:00

| Case | Chiến lược | Chính sách | Statements | Buyer Loads | Order Loads | Returned IDs | Outcome | Assertions |
|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **PG1** | In-memory pagination | Lenient (`fail=false`) | 1 | 5 | 10 | `[1, 2]` | RETURNED_PAGE | **PASS** |
| **PG2** | Fail-fast guard | Strict (`fail=true`) | 0 | 0 | 0 | `[]` | EXPECTED_REJECTION | **PASS** |
| **PG3** | Two-step (trang 0) | Strict (`fail=true`) | 2 | 2 | 3 | `[1, 2]` | RETURNED_PAGE | **PASS** |
| **PG4** | Two-step (trang 1) | Strict (`fail=true`) | 2 | 2 | 3 | `[3, 4]` | RETURNED_PAGE | **PASS** |
| **PG5** | Two-step (trang 99 rỗng) | Strict (`fail=true`) | 1 | 0 | 0 | `[]` | RETURNED_PAGE | **PASS** |

---

## 5. Bằng chứng Thực nghiệm & Kết quả Reactor

- **Maven Reactor Result:** Total **93 Tests Run** (34 core-lab + 59 backend-app), **0 Failures**, **0 Errors**, **0 Skipped** (Elapsed: 34.7s).
- **Thư mục Evidence:** `docs/evidence/prompt15/`
  - `manifest.json`: Lưu gitHead `81de50f...`, runtime info, và danh sách 5 observation files được validate.
  - `test-summary.txt`: Tóm tắt kết quả Surefire 93 tests.
  - `maven-test-output.txt`: Toàn bộ raw log console của lần chạy Maven clean test.
  - 5 tập tin JSON: `proxy-observations.json`, `propagation-observations.json`, `deadlock-observations.json`, `fetch-observations.json`, và `pagination-observations.json`.

---

## 6. Đối chiếu Các Gate Nghiệm thu

- **Gate A — Baseline:** Git HEAD chính xác, không sửa code production, dependency giữ nguyên.
- **Gate B — Sửa nhỏ:** Regression test xóa target thật và bảo toàn sentinel evidence PASS cũ; runtime info minh bạch.
- **Gate C — PG1/PG2:** Chứng minh hiện tượng nạp toàn bộ dataset vào bộ nhớ khi phân trang collection (5 buyers, 10 orders); chứng minh strict guard ném `HibernateException` từ chối query ngay tại chỗ.
- **Gate D — PG3/PG4:** Chứng minh thuật toán hai bước áp dụng database limit ở Bước 1; chỉ nạp đúng 2 buyers và 3 orders mỗi trang; khôi phục thứ tự chính xác.
- **Gate E — PG5:** Chứng minh ngắt mạch khi ID rỗng; chỉ tốn 1 statement; không phát sinh câu query IN rỗng; 0 entity loads.
- **Gate F — Regression:** NQ1..NQ5 giữ nguyên số đo; 93 tests toàn reactor đều PASS.
- **Gate G — Docs/evidence:** Đầy đủ `M2-E4B.md`, `M2-E4B-exercise.md`, cập nhật `prediction-log.md` và `question-tracker.md`; giữ nguyên `[AWAITING_CANDIDATE]`.
