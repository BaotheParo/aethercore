# Nhật Ký Thực Thi Prompt 22: M2-E8A — Security, JWT Resource Server, HTTP Market Purchase Endpoint & Owner Scoping

**Thời gian thực thi:** 14/10/2026
**Mục tiêu:** Hoàn thiện module M2-E8A:
1. Đính chính kỹ thuật các nhận định sai lệch từ Prompt 21 (Mục 1.2).
2. Xây dựng tầng HTTP API và Security cho AetherCore Market: OAuth2 Resource Server (Spring Security 6.3.3), NimbusJwtDecoder xác thực RS256 public key, claim validation nghiêm ngặt (iss, aud, exp, nbf với 60s clock skew, canonical UUID sub).
3. Triển khai phân quyền theo role (`ROLE_TRADER`) và kiểm soát sở hữu dữ liệu cấp SQL (`owner scoping` / `caller_id`).
4. Cấu hình Jackson ObjectMapper nghiêm ngặt: từ chối unknown properties và từ chối ép kiểu float sang integer.
5. Cung cấp bộ test toàn diện: Unit test cho `JwtRolesConverter` (7 tests) và Integration test `MarketSecurityIntegrationTest` phủ ma trận SEC1..SEC9 (9 tests) trên PostgreSQL 16 Testcontainers.
6. Cập nhật test harness `collect_lab_evidence.py` và bộ unit test Python (23 tests), đảm bảo thu thập đầy đủ 12 observation files với provenance chuẩn xác.

---

## 1. Đính Chính Kỹ Thuật (Prompt 22 - Mục 1.2)

Trước khi mở rộng module mới, các đính chính kỹ thuật sau đã được áp dụng trực tiếp vào `docs/experiments/M2-E7B.md` và `docs/experiments/prompt21-execution-notes.md`:
1. **Transaction Wrapping vs Internal Transaction:** `MarketIdempotencyService.execute()` tự quản lý transaction nội bộ theo state-machine (CLAIM -> WORK -> COMMIT/RELEASE), không đòi hỏi và không được để caller bên ngoài bao bọc trong một physical transaction lớn, tránh kéo dài thời gian giữ lock không cần thiết.
2. **Session Advisory Lock vs Transactional Advisory Lock:** `pg_try_advisory_lock` (phiên bản 1 tham số BIGINT) là session-level advisory lock, không tự động giải phóng khi COMMIT/ROLLBACK transaction, phân biệt hoàn toàn với `pg_advisory_xact_lock` (transaction-level).
3. **Session Close vs Unlock:** Cơ chế phòng vệ rò rỉ session lock khi JVM crash hoặc thread chết là do PostgreSQL backend process tự động dọn dẹp toàn bộ session locks khi connection/session bị đóng, chứ không phải do `pg_advisory_unlock` được gọi tự động.
4. **Sidecar Specificity:** Sidecar quét bản ghi treo là một worker/job chuyên biệt với logic domain rõ ràng (chỉ quét trạng thái PROCESSING vượt timeout, đối soát ngân hàng/kho), không phải là một "tiến trình daemon chung chung".
5. **Recovery Workflow Context:** Phục hồi sau crash/gián đoạn là quy trình đối soát nghiệp vụ có điều kiện (xác minh trạng thái downstream trước khi release hoặc mark failed), không phải unlock mù quáng.
6. **Distinction of Status:** Phân biệt rành mạch giữa kiểm chứng kỹ thuật trong lab (`VERIFIED` trên PostgreSQL 16), độ phủ câu hỏi phỏng vấn (`PARTIAL`), và trạng thái đánh giá ứng viên (`AWAITING_CANDIDATE`).
7. **Baseline Reactor:** Cập nhật số lượng test baseline trước Prompt 22 là 122 tests (34 core-lab + 88 backend-app).

---

## 2. Thiết Kế & Triển Khai Kỹ Thuật M2-E8A

### 2.1. Phụ Thuộc & Cấu Hình Bảo Mật
- Bổ sung `spring-boot-starter-validation`, `spring-boot-starter-oauth2-resource-server` và `spring-security-test` vào `backend-app/pom.xml`.
- Sử dụng BOM của Spring Boot 3.3.4, kéo về Spring Security 6.3.3 mà không override version tùy tiện.
- Cấu hình `application.properties`:
  * `aethercore.security.jwt.issuer=https://auth.aethercore.com`
  * `aethercore.security.jwt.audience=aethercore-market`
  * `aethercore.security.jwt.clock-skew-seconds=60`
  * `spring.jackson.deserialization.fail-on-unknown-properties=true`
  * `spring.jackson.deserialization.accept-float-as-int=false`

### 2.2. Kiến Trúc Token & Claims Validation
- **Public Key Cấu Hình Cố Định:** Đọc qua `AetherSecurityProperties` (hoặc test public key trong môi trường test), chuyển đổi X.509 PEM sang `RSAPublicKey`. Server tuyệt đối không tin cậy header `jwk` hay public key gửi từ client request.
- **AetherJwtValidators:** Kết hợp các validator tiêu chuẩn và tùy biến:
  * `JwtTimestampValidator(Duration.ofSeconds(60))`: bắt buộc có `exp`, kiểm tra `nbf`, dung sai đồng hồ 60 giây.
  * `JwtIssuerValidator`: bắt buộc khớp issuer cấu hình.
  * `JwtClaimValidator<List<String>>("aud")`: bắt buộc chứa audience `aethercore-market`.
  * Subject Validator: bắt buộc claim `sub` tồn tại và là chuỗi canonical UUID hợp lệ (`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`).
- **JwtRolesConverter:** Trích xuất mảng claim `roles`, chuẩn hóa và ánh xạ từng role thành `GrantedAuthority` với tiền tố `ROLE_` (ví dụ `TRADER` -> `ROLE_TRADER`). Từ chối các role rỗng, khoảng trắng hoặc không phải string.

### 2.3. HTTP Security Filter Chain
- Cấu hình bean `SecurityFilterChain` trong `SecurityConfig`:
  * Vô hiệu hóa CSRF (`csrf.disable()`) vì hệ thống thiết kế cho REST API stateless sử dụng Authorization Bearer header.
  * Quản lý session: `SessionCreationPolicy.STATELESS` (không tạo `JSESSIONID`).
  * Endpoint authorization matchers:
    - `GET /api/lab/entries/**`: `permitAll()` (cho phép truy cập công khai không cần token).
    - `/api/market/**`: `hasRole("TRADER")` (yêu cầu token hợp lệ mang authority `ROLE_TRADER`).
    - Tất cả các request còn lại: `denyAll()` (chặn mặc định theo nguyên tắc least privilege).
  * OAuth2 Resource Server: cấu hình `oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` sử dụng `NimbusJwtDecoder` đã gắn public key và validator chuỗi.

### 2.4. Endpoints & Data Ownership Scoping
- **`POST /api/market/purchases`:**
  * Header bắt buộc: `Idempotency-Key` (chuẩn UUID).
  * Caller identity: Trích xuất trực tiếp từ principal của token xác thực (`Jwt.getSubject()`). Tuyệt đối không đọc `callerId` từ query param hay JSON request body để chống spoofing.
  * Request Body: `{"sku": "IRON", "quantity": 2}`. Jackson reject nếu có unknown field hoặc float quantity.
  * Ủy thác xử lý cho `IdempotentMarketPurchaseService.executePurchase()`.
  * Kết quả: Trả về HTTP 200 kèm `MarketPurchaseResponse` (`resultCode`, `purchaseId`, `replayed`). Nếu trùng key nhưng sai payload, ném `IdempotencyConflictException` trả về HTTP 409 (`IDEMPOTENCY_CONFLICT`).
- **`GET /api/market/purchase-requests/{idempotencyKey}`:**
  * Caller identity: Trích xuất từ JWT subject.
  * `PurchaseRequestQueryService`: Thực thi câu lệnh SQL thuần có tham số:
    ```sql
    SELECT idempotency_key, caller_id, operation, state, response_payload, created_at, completed_at
    FROM market_idempotency
    WHERE caller_id = ?
      AND operation = 'PURCHASE_V1'
      AND idempotency_key = ?
      AND state = 'COMPLETED'
    ```
  * **Owner Scoping:** Mệnh đề `caller_id = ?` đảm bảo người dùng chỉ đọc được kết quả của chính mình. Nếu người dùng B (dù cùng có role `TRADER`) truy vấn key của người dùng A, câu lệnh SQL trả về 0 dòng, hệ thống ném `PurchaseRequestNotFoundException` và trả về HTTP 404 (`PURCHASE_REQUEST_NOT_FOUND`). Điều này ngăn ngừa Information Disclosure mà không cần phân quyền phức tạp.

---

## 3. Ma Trận Kiểm Thử Bảo Mật (SEC1..SEC9)

Tất cả 9 kịch bản kiểm thử trong `MarketSecurityIntegrationTest` đều được thực thi trên PostgreSQL 16 Testcontainers thật, không sử dụng H2 hay mock DB:

| Scenario | Mô tả Kịch Bản | Kết Quả Mong Đợi | Kết Quả Quan Sát Thực Tế | Trạng Thái |
|:---:|---|---|---|:---:|
| **SEC1** | Request không có Authorization Bearer header vào `/api/market/purchases` | 401 Unauthorized kèm header `WWW-Authenticate: Bearer` | HTTP 401, `WWW-Authenticate` header hiện diện | **PASS** |
| **SEC2** | Token ký bằng untrusted RSA private key (sai chữ ký) | 401 Unauthorized (`invalid_token`) | HTTP 401, từ chối giải mã chữ ký | **PASS** |
| **SEC3** | Token đã hết hạn (`exp` quá khứ vượt quá 60s clock skew) | 401 Unauthorized (`JwtValidationException: Jwt expired`) | HTTP 401, xác thực thời gian từ chối | **PASS** |
| **SEC4** | Token thiếu hoặc sai `aud` (không chứa `aethercore-market`) | 401 Unauthorized (`JwtValidationException: aud claim missing`) | HTTP 401, chặn truy cập tài nguyên | **PASS** |
| **SEC5** | Token hợp lệ nhưng chỉ có role `VIEWER` (thiếu `ROLE_TRADER`) | 403 Forbidden (chặn tại SecurityFilterChain trước controller) | HTTP 403, controller không hề được kích hoạt | **PASS** |
| **SEC6** | Truy cập `GET /api/lab/entries` không kèm token | 200 OK (nằm trong permitAll matcher) | HTTP 200, trả về dữ liệu lab public | **PASS** |
| **SEC7** | Client cố gắng spoof caller qua header `X-Caller-Id`, query param `?callerId=...`, hoặc JSON body | Header/query bị bỏ qua; JSON body lạ bị reject 400 | Caller lấy từ JWT `sub`; body lạ trả về 400 | **PASS** |
| **SEC8** | Vòng đời Idempotent Purchase qua HTTP API (Thiếu key, key sai format, fresh purchase, replay idempotent, conflict payload) | Validate 400 khi key sai; 200 cho fresh & replay; 409 cho conflict | Toàn bộ 11 subcases validation & state transition PASS | **PASS** |
| **SEC9** | Owner Scoping: User B đọc kết quả purchase của User A | 404 Not Found (SQL scope theo caller_id) | HTTP 404 `PURCHASE_REQUEST_NOT_FOUND` | **PASS** |

---

## 4. Tổng Kết Chỉ Số & Độ Bao Phủ Reactor

- **Test Suite Reactor:**
  * Module `core-lab`: 34 tests (17 test classes) -> 100% PASS.
  * Module `backend-app`: 104 tests (19 test classes, tăng 16 tests từ 88) -> 100% PASS.
  * **Tổng cộng Reactor:** **138 tests run, 0 failures, 0 errors, 0 skipped**.
- **Evidence Collection:**
  * Công cụ `scripts/collect_lab_evidence.py` hoàn thành thu thập dữ liệu với exit code 0.
  * Đã xác thực đầy đủ 12 observation files (`security-observations.json` cùng 11 files trước đó).
  * Bộ unit test Python (`scripts/tests/test_collect_lab_evidence.py`): **23/23 tests PASS**.
- **Flyway Migrations:** Giữ nguyên vẹn 100% các file migration gốc V1..V6, không sửa đổi hay tạo thêm migration tùy tiện.
