# Ghi Chú Thực Thi: Prompt 08 (Detail Mode)

**Ngày thực thi:** 11/10/2026  
**Commit cơ sở (HEAD trước sửa):** `0501adca69b2db5e3b8ea615fe4fc712b949673e`  
**Môi trường xác minh thực tế:**
- OS: Windows 11 x64 (`10.0.26100`)
- JDK: Oracle OpenJDK 22 (`build 22+36-2370`, compiler flag: `--release 21`)
- Maven: Apache Maven 3.9.12
- Docker Engine: Docker Desktop 29.5.3 (API 1.54, Linux WSL2)
- Testcontainers: **1.21.4** (upgraded from managed 1.19.8 via BOM reordering)
- PostgreSQL Container: `postgres:16-alpine` (PostgreSQL 16.15)

---

## 1. Nghiệm Thu Acceptance Gates (A–F)

| Gate | Tiêu chí nghiệm thu | Bằng chứng thực tế | Đánh giá |
|:---:|---|---|:---:|
| **A — Dependency** | Bảng before/after; Testcontainers resolve đúng 1.21.4; giải thích BOM bằng effective POM | `dependency:tree` trước: 1.19.8, sau: 1.21.4; `effective-pom-p08-before.xml` & `after.xml` | **ĐẠT (PASS)** |
| **B — Docker** | Java/Testcontainers chạy được trên local named pipe; không mở TCP không TLS | Testcontainers 1.21.4 kết nối qua `npipe:////./pipe/docker_engine`, pull `testcontainers/ryuk` & `postgres:16-alpine`, start container thành công | **ĐẠT (PASS)** |
| **C — Harness** | Exception typed/SQLSTATE thật; deadline đơn điệu `nanoTime`; cleanup kiểm chứng termination; observation worker trên đúng connection | `DuplicateKeyException` & SQLSTATE `23505`; `nanoTime()` remaining check; 2-phase termination; `WorkerObservation` đo trực tiếp PID/timeouts trong TX worker | **ĐẠT (PASS)** |
| **D — DB** | Các kịch bản M2-E1A và baseline integration thực chạy, không skipped, invariant có bằng chứng | `MarketPurchaseConcurrencyTest` (9 tests PASS), `LabEntryIntegrationTest` (3 tests PASS), Flyway V1–V3 applied | **ĐẠT (PASS)** |
| **E — Regression** | Kết quả `mvn test` toàn reactor chính xác theo Surefire report | `core-lab`: 34 tests PASS; `backend-app`: 14 tests PASS. Tổng cộng: **48/48 tests PASS**, 0 failures, 0 errors, 0 skipped | **ĐẠT (PASS)** |
| **F — Learning/Docs** | Expected/Observed tách biệt, tài liệu cập nhật, ứng viên giữ `AWAITING_CANDIDATE` | `M2-E1A.md` bảng Observed thật; `question-tracker.md` cập nhật `VERIFIED` kỹ thuật; giữ bài tập chờ học viên tự làm | **ĐẠT (PASS)** |

---

## 2. Bảng So Sánh Dependency & Môi Trường Trước/Sau

| Thành phần | Trước khi sửa (Commit 0501adc) | Sau khi sửa (Prompt 08) | Nguyên nhân / Cơ chế |
|---|---|---|---|
| **Testcontainers BOM** | Khai báo sau Spring Boot BOM trong root POM | Khai báo **trước** Spring Boot BOM trong root POM | Maven áp dụng quy tắc "First declaration wins" cho dependencyManagement BOM import. Đặt trước giúp override version của Spring Boot. |
| **Testcontainers Core Version** | `1.19.8` (do Spring Boot 3.3.4 BOM quản lý) | **`1.21.4`** | Được quản lý bởi `testcontainers-bom:1.21.4`. |
| **docker-java API Version** | `3.3.6` | **`3.4.2`** | Đi kèm Testcontainers 1.21.4, hỗ trợ Docker Engine API 1.54 / Docker 29+. |
| **Docker Engine Connection** | `BadRequestException Status 400` trên Windows named pipe | **Kết nối thành công (HTTP 200 / Connected)** | `docker-java 3.4.2` tương thích với giao thức named pipe của Docker Desktop 29.5.3. |
| **PostgreSQL Runtime** | `BLOCKED_RUNTIME` | **VERIFIED** trên PostgreSQL 16.15 container | Testcontainers khởi động container và áp dụng Flyway migration V1–V3. |

---

## 3. Nguyên Nhân Đã Chứng Minh vs Giả Thuyết

1. **Nguyên nhân đã chứng minh:**  
   Maven effective POM chứng minh rằng `testcontainers:1.19.8` được resolve thay vì `1.20.2` do Spring Boot BOM đứng trước trong root POM. Khi `docker-java 3.3.6` cũ gọi ping/info tới Docker Engine 29.5.3 (API 1.54), engine trả về `BadRequestException (Status 400)`. Khi nâng lên `1.21.4` (`docker-java 3.4.2`), kết nối qua Windows named pipe thành công ngay lập tức mà không cần thay đổi cấu hình daemon.
2. **Bác bỏ giả thuyết trước đây:**  
   Giả thuyết ở Prompt 07 cho rằng "named pipe của Windows bị lỗi và cần bật TCP 2375 không TLS" đã được bác bỏ. Named pipe của Windows hoàn toàn bình thường; nguyên nhân thuần túy nằm ở phiên bản thư viện client Java.

---

## 4. Giải Thích Cơ Chế Cốt Lõi (Core Technical Explanations)

1. **Thứ tự BOM trong Maven Dependency Management:**  
   Khi nhiều BOM cùng quản lý một dependency (ở đây là `org.testcontainers`), Maven ưu tiên BOM xuất hiện đầu tiên trong file POM. Khai báo property `<testcontainers.version>` trong POM con không thể ghi đè một dependency đã được managed bởi BOM đầu tiên trừ khi dependency đó được khai báo trực tiếp với thẻ `<version>` hoặc BOM mong muốn được đặt lên trước.
2. **Docker CLI vs Java Client (Transport vs API Version):**  
   Docker CLI giao tiếp trực tiếp qua socket/pipe với engine sử dụng binary Go client riêng (luôn đồng bộ phiên bản với engine). Java client (`docker-java`) là thư viện bên thứ ba phải tự thực hiện serialization/deserialization HTTP REST call qua pipe. Một sự thay đổi cấu trúc JSON trong phản hồi của Docker Engine API có thể khiến client cũ báo lỗi 400 Bad Request dù CLI vẫn hoạt động bình thường.
3. **PostgreSQL READ COMMITTED và Atomic Conditional UPDATE:**  
   Ở mức READ COMMITTED, một câu lệnh `SELECT` thông thường chỉ đọc snapshot tại thời điểm lệnh đó chạy. Nếu 2 worker cùng đọc stock = 1, tính toán trong Java rồi chạy `UPDATE market_stock SET available = 0`, worker 2 sẽ ghi đè giá trị cũ (Lost Update).  
   Ngược lại, `UPDATE market_stock SET available = available - 1 WHERE sku = ? AND available >= 1` thực hiện atomic check-and-decrement. Worker 2 bị block chờ worker 1 commit row-level lock. Khi worker 1 commit, PostgreSQL tự động đọc row version mới nhất vừa commit (available = 0) để đánh giá lại mệnh đề `WHERE` $ightarrow$ điều kiện $0 \ge 1$ trả về FALSE $ightarrow$ 0 rows updated.
4. **Vì sao CHECK available >= 0 không bảo vệ invariant:**  
   Constraint `CHECK (available >= 0)` chỉ kiểm tra giá trị tại thời điểm ghi dòng có âm hay không. Trong kịch bản broken, cả 2 worker đều ghi giá trị $0 \ge 0$ (hoàn toàn thỏa mãn CHECK constraint), nhưng logic ứng dụng đã bán vượt quá 1 sản phẩm.
5. **Ranh giới `PROPAGATION_REQUIRED`:**  
   Mặc định, `TransactionTemplate` tham gia vào transaction hiện có của caller. Nếu caller đã mở transaction, việc method `purchase()` trả về `SUCCESS` chỉ có nghĩa là thao tác đã thành công trong transaction cục bộ đó. Nếu outer transaction sau đó gặp lỗi và rollback, toàn bộ thay đổi của purchase sẽ bị hủy bỏ.
6. **Interrupt flag và JDBC Worker:**  
   Việc gọi `Future.get(timeout)` hoặc `executor.shutdownNow()` chỉ gửi tín hiệu interrupt tới thread Java của worker. Nếu worker đang bị block trong socket read hoặc chờ lock ở tầng JDBC/PostgreSQL mà driver chưa nhận diện interrupt, thread vẫn tiếp tục giữ connection và có thể tiếp tục ghi dữ liệu vào DB. Do đó cần session timeouts (`lock_timeout`, `statement_timeout`) ở tầng DB và xác nhận `awaitTermination()` ở tầng Executor.

---

## 5. Kết Quả Kiểm Thử Thực Tế

### 5.1. Lệnh kiểm thử mục tiêu backend
```powershell
mvn -pl backend-app -am "-Dtest=MarketPurchaseConcurrencyTest,LabEntryIntegrationTest,DamageStrategyWiringTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```
- **Kết quả:** `BUILD SUCCESS` (Tổng thời gian: 11.543 s)
- **Số lượng test:** 14 tests run, 0 failures, 0 errors, 0 skipped.
  - `DamageStrategyWiringTest`: 2 tests run, 0 failures
  - `LabEntryIntegrationTest`: 3 tests run, 0 failures (bao gồm DI isolation check)
  - `MarketPurchaseConcurrencyTest`: 9 tests run, 0 failures

### 5.2. Lệnh kiểm thử hồi quy toàn bộ reactor
```powershell
mvn test
```
- **Kết quả:** `BUILD SUCCESS` (Tổng thời gian: 12.381 s)
- **Tổng số test toàn dự án:** **48 tests run, 0 failures, 0 errors, 0 skipped**.
  - `core-lab`: 34 tests run, 0 failures
  - `backend-app`: 14 tests run, 0 failures

---

## 6. Ba Câu Hỏi Dành Cho Ứng Viên (Buổi Tiếp Theo)

> *(Bảo lưu ở trạng thái `AWAITING_CANDIDATE`; học viên tự suy ngẫm và trả lời)*

1. **“POM ghi version A nhưng dependency tree ra version B: em kiểm tra theo thứ tự nào?”**
2. **“Docker hello-world chạy được nhưng Testcontainers lỗi: vì sao em chưa thể kết luận daemon hỏng hoặc named pipe hỏng?”**
3. **“Hai Future đã trả về SUCCESS khác gì callback transaction đang trả SUCCESS khi vẫn còn outer transaction?”**
