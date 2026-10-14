# M2-E8A: Security, JWT Resource Server, HTTP Market Purchase Endpoint & Owner Scoping

**Module:** M2-E8A  
**Trạng thái:** VERIFIED (Acceptance Tests SEC1..SEC9 PASS 100%, 0 failures, 0 errors)  
**Phạm vi:** Spring Boot 3.3.4, Spring Security 6.3.3, OAuth2 Resource Server (Nimbus JWT), Endpoint POST `/api/market/purchases` và GET `/api/market/purchase-requests/{idempotencyKey}`, Phân quyền Role-based và Data Owner Scoping.

---

## 1. Tổng Quan Kiến Trúc & Vấn Đề Thực Tế

Trong các module trước (M2-E1 đến M2-E7B), các thao tác mua hàng (`purchase`) và cơ chế chống trùng lặp (`idempotency`) được kiểm thử ở tầng Service và Repository với `callerId` được truyền trực tiếp qua Java method parameter.

Trong môi trường thực tế qua giao thức HTTP:
1. **Tuyệt đối không tin tưởng client:** Client không được tự chọn hoặc khai báo danh tính của mình qua request body, query parameter hay custom header như `X-Caller-Id`.
2. **Xác thực phi tập trung nhưng tin cậy (Trusted Identity):** Sử dụng JSON Web Token (JWT) được ký số bằng thuật toán bất đối xứng **RS256** (RSA 2048-bit). Server chỉ tin tưởng khóa công khai (Public Key) được cấu hình nội bộ từ bên ngoài.
3. **Phân biệt rạch ròi Authentication vs Authorization vs Data Owner Scoping:**
   - **Authentication:** Xác thực token hợp lệ về chữ ký, thời gian, issuer, audience, và định dạng canonical UUID của `sub`.
   - **Authorization:** Kiểm tra vai trò nghiệp vụ (Role-based Access Control - RBAC) tại tầng Filter Chain: Người dùng phải có quyền `TRADER` (`ROLE_TRADER`) để tiếp cận API mua hàng.
   - **Data Owner Scoping:** Ngay cả khi người dùng B mang vai trò `TRADER`, người đó **hoàn toàn không có quyền** đọc hay can thiệp vào kết quả giao dịch của người dùng A. Quyền sở hữu được cưỡng chế trực tiếp bằng mệnh đề SQL Parameterized tại tầng dữ liệu.

---

## 2. Request Processing Flow (Luồng Xử Lý Chi Tiết)

Sơ đồ tuần tự xử lý một request gửi tới API Market Purchase:

```
[Client]
   │
   │ 1. HTTP POST /api/market/purchases
   │    Authorization: Bearer <compact-JWS>
   │    Idempotency-Key: <UUID>
   │    Content-Type: application/json
   ▼
[SecurityFilterChain]
   │
   │ 2. BearerTokenAuthenticationFilter: Trích xuất token từ Authorization header
   ▼
[NimbusJwtDecoder]
   │
   │ 3. Kiểm tra chữ ký RS256 bằng Trusted Public Key (loại bỏ key lạ, giả mạo)
   ▼
[AetherJwtValidators]
   │
   │ 4. Xác thực Claims:
   │    - iss == "https://auth.aethercore.com"
   │    - aud contains "aethercore"
   │    - exp tồn tại và chưa hết hạn (clock skew 60s)
   │    - nbf (nếu có) đã đến thời điểm hiệu lực
   │    - sub tồn tại và đúng định dạng canonical UUID
   │    - roles là JSON array of strings
   ▼
[JwtAuthenticationConverter]
   │
   │ 5. JwtRolesConverter ánh xạ:
   │    ["TRADER"] ──> GrantedAuthority("ROLE_TRADER")
   │    Tạo JwtAuthenticationToken lưu vào SecurityContextHolder
   ▼
[AuthorizationFilter]
   │
   │ 6. Kiểm tra quy tắc matchers:
   │    - /api/lab/entries/** ──> permitAll()
   │    - /api/market/**      ──> hasRole("TRADER") [tương đương hasAuthority("ROLE_TRADER")]
   │    (Nếu thiếu role TRADER ──> 403 Forbidden)
   ▼
[DispatcherServlet & Jackson]
   │
   │ 7. Deserialization & Strict Validation:
   │    - FAIL_ON_UNKNOWN_PROPERTIES: Từ chối trường lạ như callerId (400)
   │    - ACCEPT_FLOAT_AS_INT = false: Từ chối quantity thập phân như 1.5 (400)
   │    - @Valid: Kiểm tra sku non-blank, max 64; quantity > 0
   │    - Kiểm tra Idempotency-Key header đúng canonical UUID
   ▼
[MarketPurchaseController]
   │
   │ 8. Trích xuất callerId = UUID.fromString(jwt.getSubject())
   │    Tạo IdempotentPurchaseCommand(callerId, idempotencyKey, sku, quantity)
   │    (Không mở outer transaction tại controller)
   ▼
[IdempotentMarketPurchaseService]
   │
   │ 9. Thực thi claim và purchase trong READ COMMITTED transaction
   ▼
[PostgreSQL Database]
   │
   │ 10. Atomic conditional update & idempotency claim
   ▼
[Client Response]
   200 OK / 409 Conflict / 400 Bad Request
```

---

## 3. Bản Chất Kỹ Thuật: 8 Câu Hỏi Thiết Kế Cốt Lõi

### 3.1. Ai chọn SecurityFilterChain? Ai đọc Bearer header?
- `FilterChainProxy` của Spring Security so khớp request URL với các `SecurityFilterChain` đã đăng ký.
- Trong filter chain, `BearerTokenAuthenticationFilter` chịu trách nhiệm đọc header `Authorization`, kiểm tra prefix `Bearer `, và trích xuất chuỗi compact JWS.
- Token sau đó được chuyển tới `JwtDecoder` (hoặc `AuthenticationManager` / `JwtAuthenticationProvider`) để phân tích và xác thực.

### 3.2. Chữ ký hợp lệ khác gì issuer/audience/time hợp lệ?
- **Chữ ký (Signature):** Chứng minh tính toàn vẹn (integrity) và nguồn gốc (authenticity) của token — khẳng định token được sinh ra bởi bên nắm giữ Private Key tương ứng, nội dung header và payload không bị sửa đổi trên đường truyền.
- **Claims (iss, aud, exp, nbf):** Chứng minh ngữ cảnh sử dụng (contextual validity):
  * `iss` (Issuer): Xác nhận cơ quan phát hành đáng tin cậy.
  * `aud` (Audience): Xác nhận token được tạo ra cho đúng hệ thống này (tránh token của ứng dụng khác bị lạm dụng).
  * `exp` & `nbf` (Time): Xác nhận token đang trong thời hạn có hiệu lực.
- **Nguồn Public Key:** Public key phải được cấu hình tường minh từ bên ngoài (qua configuration properties / Vault / secret manager). Server **tuyệt đối không tin tưởng** header `jku` hoặc public key tự khai báo trong token, vì kẻ tấn công có thể tự sinh một key pair riêng và ký token bằng key đó.

### 3.3. `JwtAuthenticationToken` chứa gì? `sub` đi vào command tại đâu?
- `JwtAuthenticationToken` chứa:
  1. `principal`: Đối tượng `Jwt` (chứa toàn bộ claims, headers và token value).
  2. `credentials`: Token value string.
  3. `authorities`: Danh sách `GrantedAuthority` được chuyển đổi (ví dụ: `ROLE_TRADER`).
  4. `name`: Mặc định là giá trị của claim `sub`.
- Tại `MarketPurchaseController`, định danh caller được lấy trực tiếp từ `jwt.getSubject()` (hoặc `authentication.getName()`), được parse thành canonical `java.util.UUID` và truyền vào `IdempotentPurchaseCommand`. Định danh này là duy nhất và không thể bị client ghi đè.

### 3.4. Role check vs Data Owner Scoping: Hai bài toán khác biệt
- **Role check (`hasRole('TRADER')`):** Xảy ra tại HTTP boundary thông qua Spring Security `AuthorizationFilter`. Nó trả lời câu hỏi: *"Người dùng này có thuộc nhóm chức danh được phép sử dụng chức năng Market hay không?"*
- **Data Owner Scoping:** Xảy ra tại Repository/Service thông qua SQL Parameterized. Nó trả lời câu hỏi: *"Bản ghi này có thuộc quyền sở hữu của người dùng đang gửi request hay không?"*
- Hai người dùng A và B đều có role `TRADER` (cùng vượt qua tầng Authorization), nhưng A không có quyền xem hay sửa dữ liệu của B. Nếu chỉ dựa vào role check, hệ thống sẽ mắc lỗ hổng **BOLA (Broken Object Level Authorization)** / IDOR nghiêm trọng.

### 3.5. Vì sao token hợp lệ nhận 403? Vì sao cùng role nhận 404 khi đọc dữ liệu người khác?
- **Token hợp lệ nhận 403 (Forbidden):** Token có chữ ký đúng, còn hạn, đúng issuer/audience, nhưng danh sách `roles` rỗng hoặc chỉ có `["VIEWER"]`. User đã được xác thực danh tính (Authenticated) nhưng không đủ quyền hạn (Unauthorized Role).
- **Cùng role nhận 404 (Not Found):** Khi B gửi request `GET /api/market/purchase-requests/{key}`, câu truy vấn SQL tìm kiếm:
  ```sql
  WHERE caller_id = :caller_b AND operation = 'PURCHASE_V1' AND idempotency_key = :key AND state = 'COMPLETED'
  ```
  Vì bản ghi thuộc về `:caller_a`, SQL trả về 0 dòng. Server phản hồi **404 NOT_FOUND** (cùng error shape như khi key hoàn toàn chưa tồn tại), ngăn chặn kẻ tấn công dò đoán (enumerate) sự tồn tại của các idempotency key thuộc về người khác.

### 3.6. Hai người dùng chung UUID Idempotency Key: Vì sao không replay chéo?
- Trong bảng `market_idempotency`, khóa chính (Primary Key / Unique Constraint) là:
  ```sql
  PRIMARY KEY (caller_id, operation, idempotency_key)
  ```
- Khóa này kết hợp cả 3 thành phần: `caller_id`, `operation`, và `idempotency_key`.
- Khi A dùng key $K$, dòng claim được tạo với $(caller_A, 	ext{PURCHASE\_V1}, K)$.
- Khi B dùng cùng key $K$, dòng claim của B được tạo với $(caller_B, 	ext{PURCHASE\_V1}, K)$.
- Đây là hai dòng hoàn toàn độc lập trong database. Do đó, thao tác của B không bị chặn bởi A, không bị replay lại kết quả của A, và mỗi người nhận được một `purchaseId` riêng biệt.

### 3.7. Giới hạn của test Mock JWT vs Full Integration Test
- `with(jwt())` hoặc `@WithMockUser`: Chỉ tiêm giả định một `SecurityContext` vào thread, **hoàn toàn bỏ qua** `BearerTokenAuthenticationFilter`, `NimbusJwtDecoder`, chữ ký số RSA và `AetherJwtValidators`. Một cấu hình sai public key hoặc thuật toán giải mã vẫn có thể PASS nếu dùng mock!
- Test với `MockMvc` + Decoder thật + PostgreSQL container: Chứng minh toàn bộ pipeline mã hóa, giải mã, validate claim, role mapping và database isolation hoạt động chuẩn mực.
- **Giới hạn còn lại:** `MockMvc` chạy trong JVM servlet stack giả lập, chưa chứng minh việc mã hóa đường truyền TLS/HTTPS thực tế, chưa kiểm tra network latency, và chưa mô phỏng quy trình tự động xoay khóa (key rotation) hay revocation list.

### 3.8. Stateless JWT và các ngộ nhận bảo mật
- **"Token bị lộ là vô hại vì stateless":** Sai lầm nghiêm trọng. Nếu private key hoặc một token còn hạn bị lộ, kẻ tấn công có thể mạo danh caller cho đến khi token hết hạn. Stateless chỉ có nghĩa server không lưu session state, không có nghĩa token miễn nhiễm với tấn công đánh cắp.
- **"Stateless JWT tự động miễn mọi rủi ro CSRF":** Trong lab này, CSRF được tắt vì API chỉ nhận Bearer token qua HTTP header `Authorization` (trình duyệt không tự động đính kèm header này trong cross-site requests). Nếu trong tương lai token được lưu trong Cookie của trình duyệt, rủi ro CSRF sẽ lập tức xuất hiện trở lại bất kể hệ thống có stateless hay không.

---

## 4. Ma Trận Nghiệm Thu Thực Nghiệm (SEC1 – SEC9)

Tất cả 9 nhóm kịch bản chấp nhận đã được thực thi và xác thực trong `MarketSecurityIntegrationTest.java`:

| Scenario | Nội dung thực nghiệm | Kỳ vọng (Expected) | Thực tế đo lường (Observed) | Trạng thái |
|:---:|---|---|---|:---:|
| **SEC1** | Thiếu token, token sai format, hoặc ký bằng key B (untrusted) | HTTP 401, Bearer challenge (`WWW-Authenticate`) xuất hiện, 0 DB writes | Cả 3 subcases trả về HTTP 401, header `WWW-Authenticate: Bearer ...`, snapshot DB trước/sau giống nhau 100% | **PASSED** |
| **SEC2** | Ký đúng key nhưng sai issuer, thiếu/sai audience, expired, future nbf, thiếu exp, thiếu/sai sub UUID, roles sai kiểu | HTTP 401 cho từng biến thể; không ghi DB; không ném 500 | 10/10 subcases trả về HTTP 401 ổn định, không ném 500, snapshot DB bảo toàn | **PASSED** |
| **SEC3** | Roles absent/empty, hoặc role chỉ có VIEWER vs TRADER | Thiếu role TRADER ──> 403 Forbidden; có TRADER ──> 200 OK | Empty roles ──> 403; VIEWER ──> 403; TRADER ──> 200; stock bị trừ đúng 2 đơn vị | **PASSED** |
| **SEC4** | A mua qty 2 từ stock 5, replay cùng key/payload, reuse key với qty 3 | Fresh ──> 200; Replay ──> 200 cùng purchaseId; Reuse ──> 409 Conflict | Fresh purchaseId `P1`; Replay trả về đúng `P1` với `replayed=true`; Reuse trả về 409 `IDEMPOTENCY_CONFLICT`; Stock còn 3, 1 purchase | **PASSED** |
| **SEC5** | A mua key K; B đọc K; A đọc K; đọc key ngẫu nhiên | B đọc ──> 404; A đọc ──> 200 đúng result; Key lạ ──> 404; GET không đổi DB | B nhận 404 `PURCHASE_REQUEST_NOT_FOUND`; A nhận 200 đúng `P1`; Key lạ nhận 404; DB không đổi | **PASSED** |
| **SEC6** | B dùng cùng key K và payload sau A; A và B cùng đọc K | Hai scope độc lập, purchaseId khác nhau; mỗi bên chỉ đọc được kết quả của mình | A nhận `P_A`, B nhận `P_B` (`P_A != P_B`); GET A ra `P_A`, GET B ra `P_B`; stock giảm từ 10 xuống 6 | **PASSED** |
| **SEC7** | B gửi request kèm `X-Caller-Id=A`, `?callerId=A`, hoặc body chứa `callerId` | Header/query bị bỏ qua; body chứa trường lạ bị từ chối 400; không có claim cho A | Header/query không đổi caller (xử lý dưới B); body có `callerId` bị từ chối 400; DB xác nhận A có 0 claim | **PASSED** |
| **SEC8** | Validation: thiếu/sai key, body hỏng, qty 0/-1/null/1.5, sku rỗng/dài; NOT_AVAILABLE rồi restock | Tất cả lỗi input ──> 400 không ghi DB; Stock 0 ──> 200 NOT_AVAILABLE, purchaseId null; restock rồi replay vẫn NOT_AVAILABLE không trừ kho | 9 lỗi input đều trả về 400 (kể cả 1.5 thập phân); Stock 0 trả về NOT_AVAILABLE; sau khi restock replay cùng key vẫn trả về NOT_AVAILABLE, kho giữ nguyên | **PASSED** |
| **SEC9** | Request có token thành công, tiếp theo không token; public GET cũ; route lạ | Không lưu session (request sau ──> 401); public GET /entries trả về 200/404; route lạ bị deny | Request sau không token bị 401 ngay; GET /entries trả về 200/404 không cần token; route lạ trả về 401 | **PASSED** |

---

## 5. Ranh Giới Kỹ Thuật & Giới Hạn Của Module

Để đảm bảo tính trung thực kỹ thuật, các giới hạn sau được ghi nhận rõ ràng:
1. **Chưa triển khai Refresh Token & Revocation:** Trong lab này, token JWT có thời hạn ngắn (1 giờ). Cơ chế refresh token, token blacklist qua Redis hay OAuth Authorization Server chưa được xây dựng.
2. **Chưa triển khai Password Hashing:** Lab tập trung vào mô hình Resource Server nhận JWT đã ký; chưa xây dựng User Entity hay BCrypt password storage.
3. **Chưa có Multiple Security Chains:** Lab cấu hình một SecurityFilterChain duy nhất đáp ứng đồng thời public GET entries và protected market endpoints.
4. **Chưa áp dụng Method Security:** Quyền hạn role `TRADER` được kiểm tra tại tầng HTTP request matcher; các Java service nội bộ chưa gắn `@PreAuthorize`.

---

## 6. Question Tracker Mapping

| Mã câu hỏi | Trọng tâm câu hỏi | Trạng thái kịch bản Lab | Mức độ bao phủ câu hỏi | Đánh giá ứng viên |
|:---:|---|:---:|:---:|:---:|
| **SB61** | Authentication & Authorization trong Request Flow | **VERIFIED** (SEC1..SEC9) | **VERIFIED** (Pipeline chuẩn từ FilterChain -> Decoder -> Converter -> Controller -> DB) | `AWAITING_CANDIDATE` |
| **SB62, SB63** | Cấu hình SecurityFilterChain, matchers & HttpSecurity | **VERIFIED** (SEC1, SEC3, SEC9) | **VERIFIED** (Bao phủ cấu hình một filter chain hoàn chỉnh; chưa đa chain) | `AWAITING_CANDIDATE` |
| **SB64** | JWT signature & claims -> SecurityContext -> access decision | **VERIFIED** (SEC1, SEC2, SEC3) | **VERIFIED** (Kiểm chứng giải mã RS256, claim validation và role authorization) | `AWAITING_CANDIDATE` |
| **SB65** | Token Expiry & Not-Before (nbf) validation | **VERIFIED** (SEC2) | **PARTIAL** (Đã đo expiry/nbf với clock skew; chưa lab token revocation) | `AWAITING_CANDIDATE` |
| **SB66** | hasRole vs hasAuthority và ánh xạ prefix `ROLE_` | **VERIFIED** (SEC3 & Unit test) | **VERIFIED** (Đã kiểm chứng JwtRolesConverter và rule hasRole("TRADER")) | `AWAITING_CANDIDATE` |
| **SB67** | Cùng role nhưng không có quyền đọc dữ liệu người khác | **VERIFIED** (SEC5, SEC6) | **VERIFIED** (Kiểm chứng triệt để Data Owner Scoping tại SQL layer) | `AWAITING_CANDIDATE` |
| **SB68, SB69** | Cơ chế CORS & CSRF và giả định Bearer Header | **THEORY / PARTIAL** | **PARTIAL** (Đã giải thích cơ chế tắt CSRF cho stateless bearer; chưa browser exploit suite) | `AWAITING_CANDIDATE` |
| **SB70, SB71** | Đa SecurityFilterChain; Password Hashing BCrypt | **CHƯA THỰC HIỆN** | **NOT_STARTED** (Nằm ngoài phạm vi của Prompt 22) | `AWAITING_CANDIDATE` |
| **SB72** | So sánh endpoint/service/data ownership với CV Street Voice | **PARTIAL** | **PARTIAL** (AetherCore đã kiểm chứng mô hình; chưa chạy trên source Street Voice) | `AWAITING_CANDIDATE` |
