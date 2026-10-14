# M2-E8B: Bài Tập Tự Luyện — Bẫy Self-Invocation & Refactoring Bean Proxy

**Mục tiêu:** Giúp ứng viên tự mình trải nghiệm và khắc phục bẫy Self-Invocation trong Spring AOP thông qua việc viết test hồi quy và tái cấu trúc mã nguồn.  
**Quy tắc:** Không có lời giải sẵn trong tài liệu này. Ứng viên phải tự hoàn thành phần code và trả lời 3 câu hỏi phỏng vấn oral.

---

## 1. Đề Bài Thực Hành

### 1.1. Bối Cảnh Mã Nguồn (Fixture Hiện Có)
Trong hệ thống đang có một lớp dịch vụ xử lý kho hàng với phương thức công khai `processOrder()` gọi nội bộ phương thức `reserveStock()`:

```java
public class WarehouseService {
    private final AtomicInteger reservedUnits = new AtomicInteger(0);

    public int processOrder(int units) {
        // Lời gọi nội bộ qua 'this'
        return this.reserveStock(units);
    }

    @PreAuthorize("hasRole('TRADER')")
    public int reserveStock(int units) {
        return reservedUnits.addAndGet(units);
    }

    public int getReservedUnits() {
        return reservedUnits.get();
    }
}
```

### 1.2. Nhiệm Vụ 1: Viết Test Tái Hiện Bẫy Self-Invocation
Tạo một test class mới hoặc phương thức kiểm thử chứng minh rằng:
1. Khi caller mang vai trò `ROLE_VIEWER` gọi trực tiếp `warehouseService.reserveStock(5)` qua Spring bean proxy, hệ thống ném `AccessDeniedException` và `reservedUnits` giữ nguyên giá trị 0.
2. Nhưng khi caller mang vai trò `ROLE_VIEWER` gọi `warehouseService.processOrder(5)`, phương thức **thực thi thành công** và `reservedUnits` tăng lên 5 (bỏ qua kiểm tra quyền `@PreAuthorize` do self-invocation).

### 1.3. Nhiệm Vụ 2: Tái Cấu Trúc (Refactoring) Để Khắc Phục Bẫy
Tái cấu trúc lại thiết kế để đảm bảo khi caller gọi xử lý đơn hàng, lời gọi giữ kho bắt buộc phải đi qua proxy bảo mật:
- **Yêu cầu thiết kế:** Tách trách nhiệm (Separation of Concerns). Tạo một collaborator bean độc lập (ví dụ `StockReservationService`) chứa phương thức được gắn `@PreAuthorize`, và tiêm bean proxy này vào `WarehouseService`.
- **Ràng buộc:** Tuyệt đối không dùng các kỹ thuật phản mô hình (anti-patterns) như `AopContext.currentProxy()`, self-injection (`@Autowired private WarehouseService self`), hoặc bật AspectJ compile-time/load-time weaving.

---

## 2. Ba Câu Hỏi Phỏng Vấn Oral (60–90 Giây Mỗi Câu)

### Câu 1: Filter Chain vs Method Security
> *"Khi nào hệ thống cần dùng Method Security `@PreAuthorize` nếu chúng ta đã cấu hình chặt chẽ các URL matchers trong `SecurityFilterChain`?"*

- **Gợi ý & Tiêu chí trả lời (Rubric):**
  - Nêu rõ: URL matchers chỉ bảo vệ ở cổng HTTP Servlet.
  - Chỉ ra các trường hợp lời gọi không qua HTTP: Lời gọi nội bộ giữa các service, asynchronous job, message queue listener (Kafka/RabbitMQ), RPC (gRPC), hoặc batch process.
  - Nhấn mạnh nguyên tắc Defense-in-Depth: Bảo vệ dữ liệu tại từng method nghiệp vụ độc lập với giao thức truyền tải.
- **Dự đoán / Câu trả lời của ứng viên:** `[AWAITING_CANDIDATE]`

---

### Câu 2: Bản Chất Bẫy Self-Invocation Trong Spring AOP
> *"Vì sao phương thức `this.someGuardedMethod()` lại bỏ qua annotation `@PreAuthorize` trong Spring Boot, và nếu tự tạo đối tượng bằng `new MyService()` thì Spring Security có hoạt động không?"*

- **Gợi ý & Tiêu chí trả lời (Rubric):**
  - Giải thích cơ chế Proxy: Spring AOP tạo ra class con (CGLIB) bọc lấy target bean; các annotations `@PreAuthorize`, `@Transactional`, `@Async` chỉ được kích hoạt khi lời gọi đi qua đối tượng Proxy này.
  - Từ khóa `this` tham chiếu trực tiếp đến target instance, hoàn toàn không đi qua Proxy.
  - Khi dùng `new MyService()`, đối tượng được tạo trực tiếp trên JVM heap mà không qua ApplicationContext quản lý, không có proxy bọc ngoài, do đó mọi annotations của Spring đều vô hiệu.
- **Dự đoán / Câu trả lời của ứng viên:** `[AWAITING_CANDIDATE]`

---

### Câu 3: Bằng Chứng "No-Write" Khi Bị Chặn Quyền
> *"Làm thế nào bạn chứng minh được trong automated tests rằng khi một request bị từ chối 403 Forbidden hoặc AccessDeniedException, hệ thống hoàn toàn không ghi bất kỳ dữ liệu rác nào xuống database?"*

- **Gợi ý & Tiêu chí trả lời (Rubric):**
  - Phê phán việc chỉ kiểm tra cờ boolean hoặc chỉ assert status code HTTP.
  - Mô tả kỹ thuật snapshot: Query trạng thái bảng trước request (`before`), thực hiện request bị từ chối, query lại trạng thái sau request (`after`).
  - So sánh chi tiết từng trường (stock available, version, row counts).
  - Viện dẫn công cụ thu thập bằng chứng độc lập (Evidence Collector) thẩm định snapshot thô.
- **Dự đoán / Câu trả lời của ứng viên:** `[AWAITING_CANDIDATE]`

---

## 3. Tiêu Chí Đánh Giá (Rubric)

| Mức Độ | Tiêu Chí Đạt Được |
|:---:|---|
| **Chưa Đạt** | Không giải thích được vai trò của Proxy; nhầm lẫn giữa HTTP matchers và method security; dùng `AopContext.currentProxy()` để vá lỗi. |
| **Đạt (Intern/Fresher)** | Trình bày đúng luồng gọi qua CGLIB proxy; giải thích được cơ chế `this`; tách được bean độc lập để sửa lỗi; trả lời được 2/3 câu oral. |
| **Xuất Sắc (Junior Ready)** | Giải thích chi tiết cấu trúc `AuthorizationManagerBeforeMethodInterceptor` và SpEL expression; nêu rõ sự khác biệt giữa compile-time weaving (AspectJ) và proxy-based AOP; làm chủ kỹ thuật snapshot kiểm chứng no-write. |
