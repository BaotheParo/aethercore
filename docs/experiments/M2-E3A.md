# M2-E3A: SPRING @TRANSACTIONAL, AOP PROXY & ROLLBACK SEMANTICS

**Module:** M2 — Database & Concurrency
**Lab Code:** `M2-E3A`
**Test Suite:** `com.aethercore.backend.transaction.TransactionProxyExperimentsTest`
**Môi trường:** Java 22, Spring Boot 3.3.4 (Spring Framework 6.1.13), PostgreSQL 16.15 Testcontainers

---

## 1. Tình huống Thực tế & Vấn đề Cốt lõi

Trong hệ thống game/thương mại điện tử AetherCore, việc ghi nhận giao dịch mua hàng (`market_purchase`) và trừ tồn kho đòi hỏi tính toàn vẹn tuyệt đối (ACID). Trong các bài lab trước, chúng ta sử dụng `TransactionTemplate` (lập trình quản lý giao dịch - Programmatic Transaction Management) để kiểm soát chặt chẽ từng ranh giới transaction.

Tuy nhiên, hầu hết các dự án Spring Boot thực tế đều sử dụng **Declarative Transaction Management** với annotation `@Transactional`.
Nhiều kỹ sư lầm tưởng rằng:
1. *“Chỉ cần gắn `@Transactional` lên bất kỳ method nào trong service là chắc chắn có transaction.”*
2. *“Bất kỳ exception nào ném ra trong method `@Transactional` cũng sẽ khiến transaction tự động rollback.”*
3. *“Self-invocation (`this.method()`) thì chắc chắn không bao giờ có transaction.”*

Lab M2-E3A được thiết kế để giải phẫu chi tiết cơ chế hoạt động bên dưới của Spring AOP Proxy, ranh giới can thiệp của `TransactionInterceptor`, và các quy tắc rollback chuẩn theo đặc tả Spring Framework.

---

## 2. Kiến trúc Spring AOP Proxy & Sequence Diagram

Spring Framework tạo ra một đối tượng **Proxy** bọc quanh Bean thực tế (Target Object). Khi một caller từ bên ngoài gọi một method của Bean:
- Nếu gọi qua tham chiếu Spring Bean trong IoC container, caller thực chất gọi vào Proxy.
- Proxy thực thi chuỗi Advice (trong đó có `TransactionInterceptor`).
- `TransactionInterceptor` mở transaction trước khi chuyển tiếp lời gọi đến Target Object, và bắt lấy kết quả trả về hoặc ngoại lệ để quyết định `commit()` hay `rollback()`.

```mermaid
sequenceDiagram
    autonumber
    actor Caller as Client / External Caller
    participant Proxy as CGLIB Proxy (AopProxy)
    participant Interceptor as TransactionInterceptor
    participant TM as PlatformTransactionManager
    participant Target as ProbeWriter (Target Instance)
    participant DB as PostgreSQL Database

    Caller->>Proxy: successfulWrite(id, marker)
    Proxy->>Interceptor: invokeWithinTransaction(...)
    Interceptor->>TM: getTransaction(TransactionDefinition)
    TM->>DB: con.setAutoCommit(false); BEGIN;
    Interceptor->>Target: invokeJoinpoint(...) (doInsertAndObserve)
    Target->>DB: INSERT INTO market_purchase ...
    Target-->>Interceptor: return normal
    Interceptor->>TM: commit(status)
    TM->>DB: con.commit(); con.setAutoCommit(true);
    Interceptor-->>Proxy: return
    Proxy-->>Caller: return void (1 row committed)
```

---

## 3. Bảng Tổng hợp Kết quả Thực nghiệm: 8 Kịch bản (TX1 – TX8)

Toàn bộ 8 kịch bản được chạy thực tế trên container PostgreSQL 16 và Spring Framework 6.1.13:

| Case ID | Lời gọi & Luồng thực thi | Spring TX khi INSERT | Auto-commit khi INSERT | Kết quả Caller | Số dòng Committed thực tế | Cơ chế cốt lõi |
|:---:|---|:---:|:---:|:---:|:---:|---|
| **TX1** | Caller gọi method annotated qua proxy, INSERT rồi return bình thường | `true` | `false` | Thành công | **1** | Proxy chặn thành công, mở transaction, target hoàn tất và commit. |
| **TX2** | Caller gọi method annotated qua proxy, INSERT rồi ném `RuntimeException` | `true` | `false` | `IllegalStateException` | **0** | `TransactionInterceptor` bắt `RuntimeException`, gọi `rollback()` làm hủy bỏ toàn bộ thay đổi. |
| **TX3** | Caller gọi outer không annotated; outer gọi `this.innerAnnotated()`, ném `RuntimeException` | `false` | `true` | `IllegalStateException` | **1** | **Self-invocation bypass proxy:** Lời gọi `this.` đi thẳng trên heap, không qua proxy. Không có Spring TX, `autoCommit=true` tự commit INSERT vào DB! |
| **TX4** | Caller gọi outer không annotated; outer gọi method annotated của bean khác được inject | `true` (trong writer) | `false` | `IllegalStateException` | **0** | Lời gọi cross-bean đi qua Proxy của bean được inject $
ightarrow$ Proxy can thiệp và rollback thành công. |
| **TX5** | Caller gọi method annotated qua proxy, ném checked `BusinessCheckedException` | `true` | `false` | `BusinessCheckedException` | **1** | **Default Rollback Rule:** Mặc định Spring chỉ rollback với `RuntimeException` và `Error`. Checked exception được xem là tín hiệu nghiệp vụ và được **COMMIT theo mặc định trong điều kiện của TX5** (lưu ý: việc Spring commit không chứng minh dữ liệu này hợp lệ về mặt nghiệp vụ)! |
| **TX6** | Caller gọi method với `@Transactional(rollbackFor = BusinessCheckedException.class)` | `true` | `false` | `BusinessCheckedException` | **0** | Thuộc tính `rollbackFor` ghi đè quy tắc mặc định, ép `TransactionInterceptor` rollback với checked exception này. |
| **TX7** | Method annotated thực hiện INSERT, ném `RuntimeException` nhưng `catch` ngay trong body | `true` | `false` | Thành công | **1** | Exception bị nuốt cục bộ, không thoát ra khỏi method boundary $
ightarrow$ Interceptor thấy return bình thường và tiến hành commit. |
| **TX8** | Caller gọi outer annotated; outer gọi `this.innerAnnotated()`, ném `RuntimeException` | `true` (từ outer) | `false` | `IllegalStateException` | **0** | Dù inner method bị bypass advice, nhưng outer transaction vẫn đang active và bao bọc toàn bộ $
ightarrow$ Exception thoát ra outer khiến outer TX rollback. |

---

## 4. Phân tích Chuyên sâu các Điểm then chốt (Deep-dive)

### 4.1. TX3 vs TX8: Phá bỏ hiểu lầm về Self-Invocation
- **Hiểu lầm phổ biến:** *"Khi gọi `this.method()`, vì proxy bị bypass nên chắc chắn câu lệnh không bao giờ nằm trong transaction."*
- **Sự thật kỹ thuật:**
  - Trong **TX3**, outer method không có annotation $
ightarrow$ Không có transaction nào được mở $
ightarrow$ Lời gọi `this.innerAnnotated()` bỏ qua proxy $
ightarrow$ Lệnh INSERT chạy với `autoCommit = true` trên connection raw. Khi exception ném ra, statement đã được PostgreSQL commit xong!
  - Trong **TX8**, outer method có `@Transactional` $
ightarrow$ Proxy đã mở transaction ngay từ đầu. Khi outer gọi `this.innerAnnotated()`, advice của inner quả thực bị bỏ qua, nhưng connection và thread vẫn đang bị ràng buộc bởi transaction của outer (`isActualTransactionActive() == true`). Khi exception ném ra thoát khỏi outer, outer interceptor lập tức rollback toàn bộ!

### 4.2. TX5 vs TX6: Cơ chế Rollback mặc định và `rollbackFor`
- Theo quy tắc mặc định của Spring Framework (được hiện thực trong DefaultTransactionAttribute, không phải quy định của ngôn ngữ Java JLS):
  - `RuntimeException` và `Error` đại diện cho lỗi hệ thống không thể lường trước (Unchecked) $
ightarrow$ Mặc định kích hoạt Rollback.
  - `Exception` (Checked exception) trong thiết kế Java ban đầu được dùng cho các tình huống dự kiến có thể phục hồi (ví dụ: `InsufficientFundsException`) $
ightarrow$ Mặc định Spring coi đây là luồng nghiệp vụ hợp lệ và **COMMIT** dữ liệu.
- Trong thực tế, nếu không khai báo `rollbackFor = BusinessException.class` (hoặc `rollbackFor = Exception.class`), lập trình viên có thể vô tình commit các trạng thái hỏng khi ném checked exception.

### 4.3. TX7: Rủi ro khi Try-Catch bên trong `@Transactional`
- `TransactionInterceptor` can thiệp trên cả hai nhánh: xử lý commit khi phương thức trả về bình thường (commitTransactionAfterReturning) và xử lý rollback khi exception thoát ra khỏi phương thức (completeTransactionAfterThrowing). Trong TX7, exception thuần Java được bắt và xử lý hoàn toàn bên trong cùng method body (chưa có interceptor inner nào đánh dấu rollback-only và chưa có lỗi SQL làm hỏng DB connection), do đó interceptor chỉ quan sát thấy nhánh return bình thường và tiến hành commit.
- Nếu code nghiệp vụ dùng `try-catch` và nuốt exception (swallow) mà không ném lại hoặc không gọi `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`, Spring sẽ hoàn toàn không biết có lỗi và commit dữ liệu như bình thường.

---

## 5. Giới hạn & Những phần Chưa kiểm chứng

1. **Chưa kiểm chứng Propagation nâng cao:** Lab này tập trung vào invocation proxy boundary và rollback rule. Các thuộc tính `REQUIRES_NEW`, `NESTED`, và ngoại lệ `UnexpectedRollbackException` (khi inner đánh dấu rollback-only mà outer cố commit) sẽ được kiểm chứng chuyên biệt tại lab M2-E3B.
2. **Loại Proxy:** Đang kiểm nghiệm CGLIB class-based proxy (mặc định của Spring Boot). Chưa so sánh chi tiết với JDK Interface-based Dynamic Proxy.
3. **Các annotation AOP khác:** `@Async`, `@Cacheable` cũng chịu cùng hạn chế self-invocation bypass nhưng chưa được đưa vào test suite này.

---

## 6. Liên hệ Thực tiễn với AetherCore

Trước Prompt 11, các service `FixedMarketPurchaseService`, `OptimisticMarketPurchaseService`, `PessimisticMarketPurchaseService` đều sử dụng `TransactionTemplate`.
- `TransactionTemplate` là **Programmatic Transaction Management**: ranh giới transaction được xác định tường minh bằng closure `execute(status -> ...)`. Do đó, nó không bị ảnh hưởng bởi lỗi self-invocation của Spring AOP proxy.
- Lab M2-E3A chứng minh rằng việc chuyển sang dùng `@Transactional` tiện lợi hơn về mặt cú pháp nhưng đòi hỏi hiểu biết sâu sắc về AOP Proxy, cấu trúc Bean, và các quy tắc rollback ngoại lệ.
