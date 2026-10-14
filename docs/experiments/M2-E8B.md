# M2-E8B: Method Security, Trusted Caller & Bẫy Self-Invocation (Spring AOP Proxy)

**Module:** AetherCore Market Application Security & AOP Facade  
**Trạng thái kiểm chứng:** VERIFIED (Spring Security 6.3.3 / Spring AOP 6.1.13 / PostgreSQL 16 Testcontainers)

---

## 1. Tình Huống Trực Quan & Sự Phân Tầng Bảo Vệ

Để hiểu kiến trúc an ninh nhiều lớp (Defense-in-Depth) trong ứng dụng backend doanh nghiệp, hãy hình dung mô hình bảo vệ một trung tâm thương mại / kho hàng:
1. **Cổng tòa nhà (HTTP Filter Chain):** Lực lượng bảo vệ ở cổng chính (`SecurityFilterChain`). Kiểm tra giấy thông hành (Bearer JWT token), kiểm tra hạn sử dụng (`exp`, `nbf`), cơ quan cấp (`iss`, `aud`), và gắn thẻ vai trò (`ROLE_TRADER`). Ai không có thẻ bị chặn ngay tại cổng (HTTP 401/403) trước khi bước vào sảnh.
2. **Cửa phòng giao dịch / Mở kho (Method Security Gate):** Dù đã vào được tòa nhà, khi bước tới quầy kho (`MarketPurchaseFacade`), nhân viên thủ kho kiểm tra thẩm quyền tại cửa phòng bằng thiết bị quét thẻ nghiệp vụ (`@PreAuthorize("hasRole('TRADER')")`). Nếu một tiến trình nội bộ trong tòa nhà hoặc một dịch vụ nội bộ gọi trực tiếp đến quầy mà không xuất trình quyền, thủ kho lập tức từ chối (`AccessDeniedException`).
3. **Tra cứu hồ sơ cá nhân (Owner Scope SQL):** Khi khách hàng yêu cầu "Cho tôi xem đơn hàng của tôi", thủ kho không dựa vào lời khai bằng miệng ("Tôi là Alice, hãy đưa đơn của Bob"). Hệ thống chỉ dùng mã số định danh trên thẻ của chính người đang giao dịch (`caller_id` từ SecurityContext) để lọc dữ liệu trong ngăn tủ (`WHERE caller_id = ?`). Khách hàng Bob không thể xem đơn của Alice dù cả hai cùng có thẻ TRADER.

```
+---------------------------------------------------------------------------------------------------+
|                                          CLIENT REQUEST                                           |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v (HTTP Authorization: Bearer <JWT>)
+---------------------------------------------------------------------------------------------------+
| 1. HTTP SERVLET CONTAINER & FILTER CHAIN                                                          |
|    - SecurityFilterChain: BearerTokenAuthenticationFilter -> NimbusJwtDecoder (Verify RS256)      |
|    - AetherJwtValidators: iss, aud, exp, nbf (60s skew), canonical UUID sub                       |
|    - JwtRolesConverter: claims.roles -> GrantedAuthority (ROLE_TRADER)                            |
|    - AuthorizationFilter: Matchers (POST /api/market/purchases -> hasRole('TRADER'))              |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v (SecurityContextHolder populated)
+---------------------------------------------------------------------------------------------------+
| 2. DISPATCHER SERVLET & CONTROLLER (Transport Boundary)                                          |
|    - MarketPurchaseController: validates Idempotency-Key header & JSON request body                |
|    - DOES NOT extract callerId from HTTP params/body (Chống Caller Spoofing)                       |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v (Direct Java Call to Injected Spring Bean Proxy)
+---------------------------------------------------------------------------------------------------+
| 3. SPRING AOP PROXY (Application Facade Layer)                                                    |
|    - Dynamic Proxy (CGLIB) intercepts call                                                        |
|    - AuthorizationManagerBeforeMethodInterceptor -> PreAuthorizeAuthorizationManager              |
|    - Evaluates: @PreAuthorize("hasRole('TRADER')") against SecurityContextHolder                  |
|    - Throws AccessDeniedException if role missing or unauthorized                                 |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v (Proxy Advice Passed -> Target Method Body)
+---------------------------------------------------------------------------------------------------+
| 4. MARKET PURCHASE FACADE (Target Bean)                                                           |
|    - Extracts trusted callerId from SecurityContextHolder (auth.getName() -> UUID)                |
|    - Delegates to domain: IdempotentMarketPurchaseService.purchase(command)                       |
|    - Queries completed requests: PurchaseRequestQueryService.findCompletedPurchaseRequest(caller) |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v (SQL Scope: WHERE caller_id = ?)
+---------------------------------------------------------------------------------------------------+
| 5. DATABASE (PostgreSQL 16)                                                                       |
|    - Strict tenant/owner scoping, row-level idempotency lock, optimistic stock version             |
+---------------------------------------------------------------------------------------------------+
```

---

## 2. Giải Thích Chuyên Sâu 8 Câu Hỏi Cơ Chế Kỹ Thuật

### 2.1. Vì sao HTTP tests pass chưa chứng minh direct service invocation bị chặn?
HTTP integration test (sử dụng `MockMvc` hoặc `TestRestTemplate`) gửi request qua Servlet Filter Chain. Tại đây, `AuthorizationFilter` kiểm tra các quy tắc URL matchers (`.requestMatchers("/api/market/**").hasRole("TRADER")`).
Nếu một component khác trong cùng JVM (ví dụ một scheduled task, một event listener, một gRPC service, hoặc một controller nội bộ khác) tiêm (`@Autowired`) trực tiếp bean dịch vụ mà không qua HTTP, toàn bộ HTTP Filter Chain bị bỏ qua hoàn toàn! Nếu service không có Method Security, lời gọi trực tiếp này sẽ thực thi mà không có bất kỳ bước kiểm tra quyền nào. Do đó, chỉ có kiểm thử gọi trực tiếp bean proxy trong Java test mới chứng minh được tầng service được bảo vệ độc lập với HTTP.

### 2.2. Annotation `@PreAuthorize` được interpreter nào xử lý, proxy chặn trước method ở đâu?
Trong Spring Security 6.3.3:
- Khi bật `@EnableMethodSecurity`, Spring đăng ký `AuthorizationManagerBeforeMethodInterceptor` làm method advisor.
- Pointcut của advisor này quét các method có gắn `@PreAuthorize` (hoặc các annotation bảo mật khác).
- Đối với mỗi bean đủ điều kiện, Spring AOP bọc bean đó trong một Proxy (mặc định là CGLIB class-based proxy trong Spring Boot).
- Khi method được gọi, luồng thực thi đi vào `CglibAopProxy.DynamicAdvisedInterceptor.intercept()`.
- Proxy ủy thác cho `AuthorizationManagerBeforeMethodInterceptor.attemptAuthorization()`.
- Interceptor này gọi `PreAuthorizeAuthorizationManager.check(supplier, mi)`.
- `PreAuthorizeAuthorizationManager` dùng Spring Expression Language (SpEL) đánh giá biểu thức `hasRole('TRADER')` dựa trên `Authentication` lấy từ `SecurityContextHolder.getContext().getAuthentication()`.
- Nếu biểu thức trả về `false`, interceptor ném `AccessDeniedException` ngay lập tức; method thực tế của target bean **hoàn toàn chưa được gọi**.

### 2.3. Vì sao `this.guardedAction()` khác gọi một injected bean proxy? (Bẫy Self-Invocation)
Spring AOP hoạt động dựa trên cơ chế **Proxy-based AOP**:
- Khi Client gọi `guardedTargetBean.guardedAction()`, Client đang giữ tham chiếu đến đối tượng **Proxy** do Spring sinh ra. Proxy chặn lời gọi, thực thi security advice, rồi mới chuyển tiếp (delegate) tới target object thực.
- Tuy nhiên, khi một method trong chính target object (`callGuardedInternally()`) gọi `this.guardedAction()`, từ khóa `this` trỏ trực tiếp đến chính target instance trong bộ nhớ Heap, **hoàn toàn không thông qua đối tượng Proxy**!
- Vì không đi qua Proxy, Spring Security interceptor không được kích hoạt, dẫn đến việc `@PreAuthorize` bị bỏ qua âm thầm!
- Ngược lại, khi một bean khác (`GuardedTargetWrapper`) được Spring tiêm bean proxy của `GuardedTargetBean`, lời gọi qua wrapper sẽ đi qua Proxy và bị chặn quyền chính xác.

### 2.4. `hasRole('TRADER')` kiểm tra authority gì? Ai tạo authority từ JWT?
- Trong Spring Security, biểu thức `hasRole('TRADER')` tự động kiểm tra xem `Authentication.getAuthorities()` có chứa `GrantedAuthority` có tên chính xác là `ROLE_TRADER` hay không (mặc định thêm tiền tố `ROLE_`).
- Ai tạo ra authority này? Trong kiến trúc OAuth2 Resource Server:
  1. `NimbusJwtDecoder` giải mã token và trích xuất các claims JSON.
  2. Spring Security gọi `Converter<Jwt, ? extends AbstractAuthenticationToken>` (được cấu hình là `JwtAuthenticationConverter`).
  3. Converter này ủy thác cho [`JwtRolesConverter`](file:///D:/Project%20CV/INTERVIEW/aethercore/backend-app/src/main/java/com/aethercore/backend/security/JwtRolesConverter.java).
  4. `JwtRolesConverter` đọc mảng JSON `roles` trong token (ví dụ `["TRADER"]`), kiểm tra tính hợp lệ kiểu chuỗi, và ánh xạ thành đối tượng `SimpleGrantedAuthority("ROLE_TRADER")`.

### 2.5. Tại sao role check không thay thế được SQL ownership scope?
- Role check (`hasRole('TRADER')`) là kiểm tra quyền hạn cấp vai trò / thô (Coarse-grained Authorization): nó chỉ trả lời câu hỏi *"Người dùng này có phải là Trader hợp lệ để được sử dụng tính năng mua bán hay không?"*.
- Tuy nhiên, trong hệ thống có hàng ngàn Trader, cả Alice và Bob đều có `ROLE_TRADER`. Nếu chỉ dựa vào role, Alice có thể gửi request đọc kết quả đơn hàng của Bob.
- Kiểm soát sở hữu dữ liệu (Fine-grained Data Ownership) bắt buộc phải giải quyết ở tầng truy vấn dữ liệu:
  ```sql
  SELECT * FROM market_idempotency
  WHERE caller_id = ? AND operation = 'PURCHASE_V1' AND idempotency_key = ? AND state = 'COMPLETED'
  ```
  Bằng cách truyền `caller_id` lấy từ `SecurityContext` của người đang gọi vào tham số SQL, cơ sở dữ liệu cô lập dữ liệu tự nhiên. Dù Bob biết idempotency key của Alice, câu lệnh SQL truy vấn với `caller_id = Bob` sẽ trả về 0 dòng, hệ thống trả về 404 Not Found mà không tiết lộ sự tồn tại của đơn hàng.

### 2.6. Tại sao direct Java denial là Exception, còn qua HTTP mới có status code?
- Trong thế giới Java thuần túy, phương thức giao tiếp và báo lỗi giữa các hàm / objects là **Call Stack & Exceptions**. Khi một method call bị chặn bởi proxy AOP, cách duy nhất để ngăn chặn luồng thực thi tiếp tục là ném một RuntimeException (`AccessDeniedException` hoặc `AuthenticationCredentialsNotFoundException`). Java language runtime không hề có khái niệm "HTTP Status Code".
- Khái niệm HTTP Status Code (401 Unauthorized, 403 Forbidden, 404 Not Found) chỉ tồn tại ở **Transport Boundary** (tầng Web/Servlet).
- Khi request đến qua HTTP:
  - Nếu bị chặn tại Filter Chain: `AuthenticationEntryPoint` (trả về 401) hoặc `AccessDeniedHandler` (trả về 403) trực tiếp ghi mã HTTP vào `HttpServletResponse`.
  - Nếu bị chặn tại Controller/Facade: `ExceptionTranslationFilter` hoặc `@RestControllerAdvice` bắt các exceptions của Java và chuyển dịch (serialize) thành HTTP response status code tương ứng.

### 2.7. Vì sao không dùng `@PostAuthorize` sau write để ngăn side effect?
- `@PreAuthorize` kiểm tra điều kiện TRƯỚC KHI gọi method thực tế. Nếu không đủ quyền, method không bao giờ chạy.
- `@PostAuthorize` cho phép method thực tế chạy xong, lấy kết quả trả về (`returnObject`), rồi mới đánh giá biểu thức bảo mật.
- Đối với các thao tác GHI (Write / Mutation) như mua hàng, trừ kho, tạo giao dịch: nếu dùng `@PostAuthorize`, kho đã bị trừ và đơn hàng đã được ghi vào DB rồi mới kiểm tra quyền! Dù `@PostAuthorize` có ném `AccessDeniedException` sau đó, nếu không có transaction rollback toàn cục phù hợp, side effect đã xảy ra; hoặc nếu rollback thì đã tiêu tốn tài nguyên khóa và ghi vô ích.
- Do đó, đối với mọi thao tác state-modifying, bắt buộc phải dùng `@PreAuthorize` để chặn đứng trước khi có bất kỳ side effect nào.

### 2.8. Làm sao chứng minh no-write bằng snapshot thật thay vì boolean tự ghi?
- Việc chỉ assert một cờ boolean `stateUnchanged = true` là không đáng tin cậy vì code test có thể tự set `true` mà không thực sự đo đạc database.
- Cơ chế kiểm chứng thực nghiệm của AetherCore:
  1. Trước khi thực thi request bị từ chối, test chụp snapshot toàn diện từ PostgreSQL:
     ```java
     DbSnapshot before = takeSnapshot(sku);
     // stockAvailable, stockVersion, purchaseCount, purchaseSumQuantity, idempotencyRowCount
     ```
  2. Thực thi request bị từ chối (401/403/Exception).
  3. Sau khi kết thúc, chụp snapshot mới:
     ```java
     DbSnapshot after = takeSnapshot(sku);
     ```
  4. Assert cấp Java: `assertThat(after).isEqualTo(before)`.
  5. Cấp Evidence Collector: Tool thẩm định độc lập (`scripts/collect_lab_evidence.py`) đọc cả 2 snapshot thô (`before` và `after`) từ file JSON và tự động so sánh từng trường số nguyên. Nếu bất kỳ trường nào sai lệch (ví dụ `after.stockAvailable = -99`), collector sẽ đánh fail ngay lập tức, loại bỏ hoàn toàn khả năng ngụy tạo kết quả.

---

## 3. Giới Hạn Của Kiến Trúc Hiện Tại

Module M2-E8B tập trung vào việc làm chủ Method Security và AOP Proxy, chưa bao gồm các thành phần sau:
- Chưa có cơ chế lan truyền SecurityContext qua các luồng bất đồng bộ (`@Async`, ThreadPoolTaskExecutor) bằng `DelegatingSecurityContextAsyncTaskExecutor`.
- Chưa có hệ thống Token Revocation / Blacklist tập trung (chỉ kiểm tra hạn qua `exp`/`nbf`).
- Chưa có tính năng Refresh Token hoặc OAuth2 Authorization Server.
- Chưa hỗ trợ lưu trữ mật khẩu nội bộ (BCrypt/Argon2).
- Các giới hạn này được ghi nhận trung thực và không được đánh dấu là `VERIFIED` trong question tracker.
