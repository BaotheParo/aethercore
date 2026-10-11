# Ghi Chú Thực Thi: Prompt 07 (Detail Mode)

**Ngày thực thi:** 11/10/2026  
**Commit cơ sở (HEAD trước sửa):** `d9b43c5`  
**Môi trường:**
- OS: Windows 11 x64 (`10.0.26100`)
- JDK: Oracle OpenJDK 22 (`build 22+36-2370`, compiler target: `--release 21`)
- Maven: Apache Maven 3.9.12
- Docker Engine: Docker Desktop 29.5.3 (API 1.54, linux/amd64, containerd 1.9.3)
- Testcontainers: 1.20.1

---

## 1. Bảng Nghiệm Thu Acceptance (Prompt 07)

| Hạng mục | Yêu cầu kỹ thuật | File liên quan | Kết quả thực tế | Đánh giá & Giới hạn |
|---|---|---|:---:|---|
| **Docker / DB** | Chẩn đoán readiness, khởi động Docker Desktop có kiểm soát thời gian | `scratch/start_docker_desktop.py` | **ĐÃ CHẨN ĐOÁN & ENGINE RUNNING** | Docker CLI chạy được container Linux (`hello-world`), nhưng Testcontainers client gặp lỗi BadRequest 400 trên named pipe. |
| **DI Fixture Isolation** | Thay `@Configuration` bằng `@TestConfiguration(proxyBeanMethods = false)`, xác nhận context isolation | `DamageStrategyWiringTest.java`, `LabEntryIntegrationTest.java` | **VERIFIED (PASS)** | 2/2 tests trong `DamageStrategyWiringTest` pass độc lập; assertion trong test chứng minh bean không lọt vào context chính. |
| **Concurrency Harness** | Kiểm tra return boolean của timed await, global deadline, cleanup an toàn trong finally | `MarketPurchaseConcurrencyTest.java`, `BrokenMarketPurchaseService.java` | **VERIFIED (Compiled)** | Code harness hoàn thiện: ready latches, finite timeouts (lock 5s, statement 10s, pool 10), interrupt flag preservation. |
| **Market Correctness** | Tách biệt Expected vs Observed; không dùng expected điền vào observed | `prediction-log.md`, `M2-E1A.md` | **ĐÃ CẬP NHẬT** | Trạng thái runtime M2-E1A được ghi trung thực là `IMPLEMENTED_UNVERIFIED / BLOCKED_RUNTIME`. |
| **REQUIRED Boundary** | Outer rollback test và làm rõ contract SUCCESS khi caller có transaction | `FixedMarketPurchaseService.java`, `MarketPurchaseConcurrencyTest.java` | **VERIFIED (Compiled)** | JavaDoc làm rõ PROPAGATION_REQUIRED và ranh giới outer transaction. Test `fixedService_outerTransactionRollback_rollsBackPurchase` đã viết. |
| **Cache Variant** | Chạy core suite với flag HotSpot `-XX:AutoBoxCacheMax=2048` | Surefire argLine | **VERIFIED (PASS)** | Chạy thành công 34/34 tests với `-XX:AutoBoxCacheMax=2048`. |
| **Documentation & Mapping** | Khôi phục đúng mapping J15–J19, J101–J102, SB03, SB04, SB06, SB07, SB25, SB27 theo 500 câu hỏi | `question-tracker.md`, `prediction-log.md` | **ĐÃ CẬP NHẬT** | Mapping chuẩn xác 100% theo ngân hàng câu hỏi gốc. |
| **Learning Readiness** | Giữ bài tập và đánh giá người học ở `AWAITING_CANDIDATE` | `M2-E1A-exercise.md`, `question-tracker.md` | **BẢO LƯU** | Không điền trước đáp án của học viên. |

---

## 2. Chi Tiết Lệnh & Kết Quả Chạy

### 2.1. Kiểm thử Core Lab (M1-E1, M1-E3, M1-E4)
- **Lệnh thường:** `mvn -pl core-lab -am test`
  - Kết quả: `BUILD SUCCESS` (34 tests run, 0 failures, 0 errors, 0 skipped).
- **Lệnh với biến thể JVM Cache Max:** `mvn -pl core-lab -am "-DargLine=-XX:AutoBoxCacheMax=2048" test`
  - Kết quả: `BUILD SUCCESS` (34 tests run, 0 failures, 0 errors, 0 skipped).

### 2.2. Kiểm thử Spring DI Wiring
- **Lệnh:** `mvn -pl backend-app -am "-Dtest=DamageStrategyWiringTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - Kết quả: `BUILD SUCCESS` (2 tests run, 0 failures, 0 errors, 0 skipped).

### 2.3. Biên dịch toàn bộ Backend App
- **Lệnh:** `mvn -pl backend-app -am test-compile`
  - Kết quả: `BUILD SUCCESS` (0 compilation error).

### 2.4. Kiểm tra Môi trường Docker & Testcontainers
- **Docker CLI / Engine:**
  - `docker info`: Server Version 29.5.3, Linux kernel 6.18.40.1-microsoft-standard-WSL2.
  - `docker run --rm hello-world`: Thành công (Exit code 0).
- **Testcontainers PostgreSQL:**
  - `mvn -pl backend-app -am "-Dtest=MarketPurchaseConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - Lỗi gặp phải:
    ```text
    NpipeSocketClientProviderStrategy: failed with exception BadRequestException (Status 400: ... Labels:["com.docker.desktop.address=npipe://\\.\pipe\docker_cli"])
    ```
  - **Phân loại lỗi:** Lỗi không tương thích giao tiếp giữa Testcontainers 1.20 và Docker Desktop 29.5.3 trên Windows named pipe.

---

## 3. Câu Hỏi Dành Cho Người Học (Buổi Tiếp Theo)

1. **Vì sao `Future.get(timeout)` hoặc `executor.shutdownNow()` chưa chứng minh được JDBC worker thread đã thực sự dừng thao tác trên Database?**
2. **Vì sao một static nested configuration nằm trong `src/test/java` vẫn có thể ảnh hưởng đến một `@SpringBootTest` khác trong cùng runtime classpath?**
3. **Làm sao phương thức `purchase()` có thể trả về `PurchaseResult.SUCCESS` mà cuối cùng trong Database lại không có bản ghi purchase nào khi caller sử dụng outer transaction?**
