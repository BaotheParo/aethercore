# GHI CHÚ THỰC THI PROMPT 05 (EXECUTION PROMPT 05)
**Chủ đề:** Sửa sai lệch M1-E1/M1-E3 & Triển khai M1-E4 (Strategy Pattern, Dispatch, Spring DI)  
**Ngày thực hiện:** 10/10/2026  
**Chủ trì:** Orchestrator & Agent Tree

---

## 1. Các điểm sửa có mục tiêu trong M1-E1 & M1-E3
1. **Integer Cache Contract vs Observation:**
   - Cập nhật `WrapperAndPrecisionExperimentsTest.java` để phân định rõ: `[-128, 127]` là Contract được bảo đảm bởi JLS §5.1.7; ngoài khoảng này dùng `Objects.equals()` để kiểm tra giá trị, trong khi `==` được ghi nhận như một runtime observation trên OpenJDK mặc định.
2. **BigDecimal Scale Normalization:**
   - Chuẩn hóa `OrderPriceCalculator.java`: dùng `rawUnitPrice.setScale(2, RoundingMode.UNNECESSARY)` để chấp nhận `"10.000"` (thành `10.00`), `"19.990"` (thành `19.99`), `"0"` (thành `0.00`), đồng thời từ chối `"10.001"` và `"0.001"` vì vi phạm contract scale 2 không làm tròn.
   - Bổ sung test kiểm chứng `BigDecimal.valueOf(0.1 + 0.2)` không thể phục hồi sai số nhị phân đã xảy ra trong phép toán `double`.
3. **HashMap Iteration Assertion:**
   - Bổ sung assertion duyệt `map.entrySet()` trong `PlayerKeyExperimentsTest` để minh chứng entry của Mutable Key vẫn nằm trong bảng băm dù `map.get(key)` trả về `null`.

---

## 2. Triển khai M1-E4
- **Core Lab (Java thuần):**
   - `DamageStrategy` (Interface), `PhysicalDamageStrategy`, `MagicalDamageStrategy`.
   - `DamageCalculator` với Pure Java Constructor Injection.
   - `DispatchDemonstrator` minh chứng Compile-time Overload Resolution vs Runtime Dynamic Override Dispatch.
   - 7 unit test mới kiểm chứng tính toán, bảo vệ tràn số (`Integer.MAX_VALUE`), null safety và cơ chế dispatch.
- **Backend App (Spring Context Slice):**
   - `DamageConfig` đăng ký `@Bean("physicalDamageStrategy")` và `@Bean("magicalDamageStrategy")`.
   - `DamageCalculationService` sử dụng Constructor Injection với `@Qualifier`.
   - `DamageStrategyWiringTest` chạy thuần Spring Context slice (không bật Docker) kiểm tra wiring thành công và bắt lỗi `NoUniqueBeanDefinitionException` khi lookup không có qualifier.

---

## 3. Tổng kết kết quả kiểm thử
- `mvn -pl core-lab -am test`: **33/33 tests PASS** (M1-E1: 8, M1-E3: 18, M1-E4: 7).
- `mvn -pl backend-app -am "-Dtest=DamageStrategyWiringTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`: **2/2 tests PASS**.
- Toàn bộ test chạy độc lập 100% không phụ thuộc Docker/PostgreSQL.
