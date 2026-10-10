# BÀI TẬP TỰ LÀM M1-E4: VIẾT ROGUEDAMAGESTRATEGY VÀ UNIT TEST

## 1. Yêu cầu bài toán
Trong hệ thống RPG của AetherCore, chức nghiệp **Rogue (Sát thủ)** có cách tính sát thương riêng biệt phụ thuộc mạnh vào độ nhanh nhẹn (`agility`) và sức mạnh (`strength`).

Hãy tự tay tạo class `RogueDamageStrategy` trong `com.aethercore.core.m1e4` thỏa mãn các tiêu chí kỹ thuật:

1. **Triển khai Interface:** `public class RogueDamageStrategy implements DamageStrategy`
2. **Công thức tính toán:**
   $$	ext{damage} = 	ext{strength} + 3 	imes 	ext{agility}$$
3. **Quy tắc an toàn (Invariants & Overflow Protection):**
   - Ném `NullPointerException` nếu `stats` truyền vào là `null` (thông điệp: `"CharacterStats must not be null"`).
   - Thực hiện tính toán bằng kiểu số học `long` 64-bit để không bao giờ bị tràn số khi các chỉ số chạm `Integer.MAX_VALUE`.
4. **Không sửa đổi mã nguồn:** Class `DamageCalculator` phải dùng được `RogueDamageStrategy` mà không cần sửa bất kỳ dòng code nào (Open/Closed Principle).

---

## 2. Yêu cầu viết Unit Test
Bổ sung test case trong `DamageStrategyExperimentsTest.java` (hoặc test class riêng):
1. **Case thông thường:** `stats(str=10, int=10, agi=20)` $\longrightarrow 	ext{damage} = 10 + 3 	imes 20 = 70$.
2. **Case biên số lớn:** `stats(Integer.MAX_VALUE, 0, Integer.MAX_VALUE)` $\longrightarrow 	ext{damage} = 2147483647 + 3 	imes 2147483647 = 8,589,934,588L$.
3. **Case an toàn null:** Kiểm tra `calculateDamage(null)` ném `NullPointerException`.
