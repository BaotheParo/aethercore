# Ghi Chú Thực Thi: Prompt 06 (Detail Mode)

**Ngày thực thi:** 11/10/2026  
**Commit cơ sở:** `ccb7f71`  
**Môi trường:**
- Windows 11 x64
- Oracle OpenJDK 22 (compiler flag: `--release 21`)
- Apache Maven 3.9.12
- Docker Desktop: Tắt (Docker daemon stopped / named pipe không khả dụng)

---

## 1. Bảng Nghiệm Thu Acceptance (A1–A5 & B1–B4)

| Mã | Hạng mục yêu cầu | File / Test liên quan | Kết quả thực tế | Phạm vi chưa xác minh / Giới hạn |
|:---:|---|---|:---:|---|
| **A1** | Bỏ assertion đồng nhất tham chiếu wrapper ngoài cache; chỉ giữ `Objects.equals` | `WrapperAndPrecisionExperimentsTest.java` | **ĐÃ SỬA & TEST PASS** (34/34 core-lab tests) | Không khẳng định cơ chế heap ngoài JLS spec chuẩn. |
| **A2** | Chuẩn hóa công thức DamageStrategy (Physical: `2*str + agi`, Magical: `2*int + agi`), thêm `@FunctionalInterface`, chuyển `DispatchDemonstrator` sang test source | `PhysicalDamageStrategy.java`, `MagicalDamageStrategy.java`, `DamageStrategy.java`, `DamageStrategyExperimentsTest.java` | **ĐÃ SỬA & TEST PASS** | Không khẳng định bao phủ toàn bộ các class functional interfaces khác của JDK. |
| **A3** | Refactor `DamageStrategyWiringTest` dùng thuần `AnnotationConfigApplicationContext`, test `@Qualifier` thành công và ambiguous injection ném `NoUniqueBeanDefinitionException`. Xóa damage classes khỏi `backend-app/src/main` | `DamageStrategyWiringTest.java`, xoá `DamageCalculationService`, `DamageConfig` khỏi `backend-app/src/main` | **ĐÃ SỬA & TEST PASS** (2/2 test passes) | Chưa kiểm chứng các ngữ cảnh phức tạp đa profile hoặc Spring Boot AutoConfiguration. |
| **A4** | Sửa các tuyên bố quá đà trong tài liệu & tracker | `docs/question-tracker.md`, `docs/prediction-log.md` | **ĐÃ CẬP NHẬT** | - |
| **A5** | Khôi phục đúng mapping câu hỏi phỏng vấn chuẩn | `docs/question-tracker.md` | **ĐÃ CẬP NHẬT** | Trạng thái người học giữ `AWAITING_CANDIDATE`. |
| **B1** | Flyway migration V3: bảng `market_stock` (CHECK $\ge 0$) và `market_purchase` (UUID PK, FK, quantity $> 0$) | `V3__create_market_tables.sql` | **HOÀN THÀNH** | Migration sẵn sàng trong source code. |
| **B2** | Broken service tái hiện stale read-then-write (lost update) có kiểm soát với `CyclicBarrier` | `BrokenMarketPurchaseService.java` (test source) | **HOÀN THÀNH (Compiled)** | Chưa chạy runtime do Docker daemon tắt. |
| **B3** | Fixed service: atomic conditional `UPDATE ... WHERE available >= ?` + `INSERT` trong cùng `TransactionTemplate` (READ COMMITTED) | `FixedMarketPurchaseService.java` | **HOÀN THÀNH (Compiled)** | Chưa chạy runtime do Docker daemon tắt. |
| **B4** | Concurrency test suite bao phủ lost update, serialization đúng, rollback lỗi insert, validation và 20 attempts | `MarketPurchaseConcurrencyTest.java` | **HOÀN THÀNH (Compiled)** | `BLOCKED_RUNTIME` trên máy Windows do Docker Desktop engine không chạy. |

---

## 2. Chi Tiết Lệnh & Kết Quả Chạy

1. **Kiểm tra cú pháp và unit tests `core-lab`:**
   ```bash
   mvn -pl core-lab -am test
   ```
   - **Kết quả:** `BUILD SUCCESS`
   - **Số lượng test:** 34 tests run, 0 failures, 0 errors, 0 skipped (Thời gian: ~2.7s).
2. **Kiểm tra DI Wiring test trong `backend-app`:**
   ```bash
   mvn -pl backend-app -am "-Dtest=DamageStrategyWiringTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
   ```
   - **Kết quả:** `BUILD SUCCESS`
   - **Số lượng test:** 2 tests run, 0 failures, 0 errors, 0 skipped.
3. **Biên dịch toàn bộ `backend-app` bao gồm module market và test concurrency:**
   ```bash
   mvn -pl backend-app -am test-compile
   ```
   - **Kết quả:** `BUILD SUCCESS` (0 compilation error).
4. **Kiểm tra Docker Daemon & Runtime Database:**
   ```bash
   docker info
   ```
   - **Kết quả:** `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`
   - **Ghi nhận:** Trạng thái M2-E1A runtime được đánh dấu chính xác là `IMPLEMENTED_UNVERIFIED / BLOCKED_RUNTIME (Docker daemon stopped)`. Không làm giả dữ liệu qua H2 hay Mock JDBC.
