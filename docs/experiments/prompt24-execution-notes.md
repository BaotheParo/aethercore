# Nhật Ký Thực Thi Prompt 24: M2-E8B — Method Security, Trusted Caller & Bẫy Self-Invocation

**Thời gian thực thi:** 14/10/2026  
**Mục tiêu:** Thực hiện toàn diện module M2-E8B:
1. Đính chính và xử lý dứt điểm các phát hiện từ baseline Prompt 22 (Section 1.2).
2. Xây dựng Application Facade (`MarketPurchaseFacade`) có gắn `@PreAuthorize("hasRole('TRADER')")`, kết nối HTTP layer với domain/JDBC services.
3. Trích xuất định danh caller tin cậy hoàn toàn từ `SecurityContextHolder`, không cho phép client spoofing callerId.
4. Triển khai và kiểm chứng 3 acceptance cases Method Security:
   - **MS1:** Lời gọi trực tiếp vào bean qua Java bị chặn bởi role gate (`MarketMethodSecurityIntegrationTest`).
   - **MS2:** Facade đảm bảo owner scoping và cách ly độc lập giữa các callers dùng chung idempotency key (`MarketMethodSecurityIntegrationTest`).
   - **MS3:** Minh chứng bẫy Self-Invocation (`this` call bypass) và cơ chế chặn của Spring AOP CGLIB proxy (`MethodSecurityProxyExperimentsTest`).
5. Nâng cấp bộ test và collector: thêm các edge cases SEC, bảo vệ snapshot no-write, kiểm tra duplicate variants, nâng tổng số test Python lên 26/26 tests PASS và Reactor Java lên 142/142 tests PASS.

---

## 1. Lưu Ý Về Số Thứ Tự (Numbering Note: Prompt 22 -> 24)

Người dùng yêu cầu thực thi "Prompt 24" ngay sau Prompt 22. Trên nhánh `main`, commit baseline là `68bd59e` (Prompt 22) và không có evidence hay commit nào cho "Prompt 23". Thực thi này kế thừa trực tiếp baseline đã kiểm chứng của Prompt 22, gắn nhãn **Prompt 24 theo yêu cầu người dùng**, và tuyệt đối không tạo lịch sử thực thi giả cho Prompt 23.

---

## 2. Tổng Hợp Các Phát Hiện Baseline & Biện Pháp Đã Xử Lý (Section 1.2)

| Phát hiện từ Reviewer | Bằng chứng thực tế tại Baseline | Biện pháp đã thực hiện trong Prompt 24 | File & Test xác minh |
|---|---|---|---|
| **Báo cáo mô tả sai SEC subcases count** | Báo cáo cũ ghi "23 subcases"; thực tế `security-observations.json` có **45 subcases** | Đính chính nhật ký và tài liệu theo số liệu thực của JSON | `prompt22-execution-notes.md`, `security-observations.json` |
| **SQL trong execution notes có cột không tồn tại** | Notes cũ ghi `response_payload`, `completed_at`; thực tế query dùng `result_code`, `purchase_id`, `request_sku`, `request_quantity` | Trích xuất chính xác câu SQL từ `PurchaseRequestQueryService.java` | `PurchaseRequestQueryService.java`, `prompt24-execution-notes.md` |
| **Audience cấu hình khác báo cáo** | Báo cáo ghi `aethercore-market`; file `application.properties` và test support dùng `aethercore` | Đồng bộ toàn bộ tài liệu và validator với giá trị thực `aethercore` | `application.properties`, `AetherSecurityProperties.java` |
| **Sidecar bị giải thích thành worker đối soát** | Notes cũ mô tả worker quét PROCESSING ngân hàng; thực tế sidecar lab là file cấu hình Flyway `V2...sql.conf` | Loại bỏ các chi tiết không có trong implementation thực tế | `prompt22-execution-notes.md`, `M2-E7B.md` |
| **Subject bị trim ở validator nhưng parse trực tiếp ở controller** | Validator dùng `sub.trim()`, controller dùng `UUID.fromString(jwt.getSubject())` | Thống nhất chuẩn: `sub` phải đúng 36 ký tự canonical, không trim; sub có whitespace bị reject 401 | `AetherJwtValidators.java`, `MarketSecurityIntegrationTest.java` (SEC2) |
| **SEC6 GET dùng chung biến snapshot 2 lần** | Code cũ gọi `recordSubcase(..., snap2, snap2)` cho cả 2 lượt đọc A và B | Chụp snapshot mới độc lập (`snap3`, `snap4`) sau mỗi lượt đọc | `MarketSecurityIntegrationTest.java` (SEC6) |
| **SEC9 chưa chứng minh session không giữ context** | Hai requests chạy độc lập không kiểm tra session/cookie | Thêm kiểm tra `getSession(false) == null`, không có `Set-Cookie`, gửi kèm `MockHttpSession` không Bearer vẫn bị 401 | `MarketSecurityIntegrationTest.java` (SEC9) |
| **Collector tin boolean và bỏ qua duplicate variant** | Đổi snapshot lệch vẫn pass nếu cờ `stateUnchanged=true`; variant trùng bị ghi đè | Thêm kiểm tra snapshot diff tự động, từ chối duplicate variant, bổ sung 3 unit tests negative | `collect_lab_evidence.py`, `test_collect_lab_evidence.py` |

---

## 3. Kiến Trúc & Luồng Thực Thi M2-E8B

### 3.1. Luồng Gọi 5 Tầng Từ HTTP Đến Database
```text
HTTP Client Request
  --> [1] SecurityFilterChain (Stateless, exact matchers, NimbusJwtDecoder RS256, JwtRolesConverter)
  --> [2] MarketPurchaseController (HTTP transport input validation, parseCanonicalUuid)
  --> [3] MarketPurchaseFacade CGLIB Proxy (AuthorizationManagerBeforeMethodInterceptor, @PreAuthorize)
  --> [4] MarketPurchaseFacade Target Bean (extracts callerId from SecurityContextHolder)
  --> [5] Domain/JDBC Services & PostgreSQL (IdempotentMarketPurchaseService, PurchaseRequestQueryService)
```

### 3.2. Năm File Cốt Lõi Nên Đọc Để Học Tập
1. [`MarketPurchaseFacade.java`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/main/java/com/aethercore/backend/market/facade/MarketPurchaseFacade.java): Application Facade bọc Spring AOP proxy `@PreAuthorize("hasRole('TRADER')")`, trích xuất caller tin cậy từ SecurityContext.
2. [`MethodSecurityProxyExperimentsTest.java`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/test/java/com/aethercore/backend/security/MethodSecurityProxyExperimentsTest.java): Pure Spring test minh chứng bẫy self-invocation với bộ đếm counter.
3. [`MarketMethodSecurityIntegrationTest.java`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/test/java/com/aethercore/backend/security/MarketMethodSecurityIntegrationTest.java): Integration test trực tiếp trên facade bean với PostgreSQL 16 Testcontainers (MS1 & MS2).
4. [`SecurityConfig.java`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/main/java/com/aethercore/backend/security/SecurityConfig.java): Bật `@EnableMethodSecurity`, cấu hình exact matchers và denyAll cho route lạ.
5. [`AetherJwtValidators.java`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/main/java/com/aethercore/backend/security/AetherJwtValidators.java): Validator chuỗi claims với định dạng 36 ký tự canonical UUID nghiêm ngặt cho claim `sub`.

---

## 4. Ma Trận Kết Quả Kiểm Thử (MS1–MS3 & SEC Edge Updates)

### 4.1. Method Security Cases (MS1–MS3)
- **MS1 (Direct Bean Call Blocked by Role Gate):**
  - Unauthenticated context -> Ném `AuthenticationCredentialsNotFoundException`, DB snapshot không đổi (`stateUnchanged=true`).
  - ROLE_VIEWER context -> Ném `AccessDeniedException`, DB snapshot không đổi (`stateUnchanged=true`).
  - ROLE_TRADER context -> Thành công (`SUCCESS`, `purchaseId` hợp lệ, kho giảm 2, idempotency tăng 1).
- **MS2 (Owner Scoping at Facade Level):**
  - Caller A mua hàng với key K thành công.
  - Caller B tra cứu key K nhận `Optional.empty()` (Owner scoping bảo vệ).
  - Caller B mua hàng với cùng key K nhận `SUCCESS` với `purchaseId` hoàn toàn khác Caller A.
  - Cả hai caller tra cứu key K đều nhận đúng purchaseId của mình; bảng `market_idempotency` lưu 2 dòng riêng biệt cho key K.
  - Caller A replay key K nhận kết quả cache (`replayed=true`), DB snapshot không đổi.
- **MS3 (Bẫy Self-Invocation & Proxy Interception):**
  - VIEWER gọi `guardedAction()` qua bean proxy: Bị chặn bởi `AccessDeniedException`, counter giữ nguyên `0 -> 0`.
  - VIEWER gọi `callGuardedInternally()` qua bean proxy: Phương thức ngoài unannotated gọi `this.guardedAction()`, **bỏ qua proxy advice**, counter tăng `0 -> 1`!
  - VIEWER gọi qua wrapper bean: Wrapper gọi proxy của guarded bean, bị chặn bởi `AccessDeniedException`, counter giữ nguyên `1 -> 1`.
  - TRADER gọi qua bean proxy: Hợp lệ, đi qua proxy advice, counter tăng `1 -> 2`.

### 4.2. SEC Edge Subcases Bổ Sung (SEC2, SEC3, SEC6, SEC9)
- **SEC2:** Thêm `sub_with_whitespace` (401), `sub_shortened_uuid` (401), `roles_array_non_string_element` (401).
- **SEC3:** Thêm `absent_roles_claim` (403 Forbidden).
- **SEC6:** Chụp snapshot độc lập giữa các lần đọc A và B.
- **SEC9:** Kiểm tra `getSession(false) == null`, không có cookie, gửi `MockHttpSession` không Bearer vẫn bị 401, gửi request tới route chưa khai báo với token TRADER bị 403 (`denyAll()`).

---

## 5. Tổng Kết Số Lượng Tests & Collector

- **Kiểm thử Python (`scripts/tests/test_collect_lab_evidence.py`):**
  - **26 tests run, 0 failures, 0 errors (100% PASS)** (Tăng 3 tests kiểm thử negative mutations cho snapshots và proxy counters).
- **Kiểm thử Java Toàn Reactor:**
  - `core-lab`: 17 test classes, **34 tests run, 0 failures, 0 errors, 0 skipped**.
  - `backend-app`: 21 test classes, **108 tests run, 0 failures, 0 errors, 0 skipped** (Tăng 4 tests: 2 từ `MarketMethodSecurityIntegrationTest`, 2 từ `MethodSecurityProxyExperimentsTest`).
  - **Tổng Reactor:** **142 tests run, 0 failures, 0 errors, 0 skipped (100% PASS)**.
- **Evidence Collector:**
  - Hỗ trợ `--prompt prompt24`.
  - Thu thập và kiểm chứng thành công toàn bộ 14 files observation (`security-observations.json`, `method-security-observations.json`, `method-security-proxy-observations.json` cùng 11 files baseline).
