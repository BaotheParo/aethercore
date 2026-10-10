# BÀI TẬP TỰ LÀM M1-E3: PHÒNG VỆ IMMUTABILITY TRONG RECORD & TÍNH GIÁ BIGDECIMAL

## Đề bài (Chọn thực hiện 1 trong 2 bài):

### Bài A: Thiết kế Record `GuildRoster` có phòng vệ Immutability
Tạo một record `GuildRoster(String guildName, List<String> memberNames)` thỏa mãn:
1. `guildName` không được null hoặc rỗng.
2. `memberNames` không được null.
3. Áp dụng **Defensive Copying** trong compact constructor để:
   - Thay đổi danh sách `List` truyền vào sau khi tạo record KHÔNG làm đổi danh sách bên trong record.
   - Thao tác gọi `roster.memberNames().add("New Member")` từ bên ngoài sẽ ném `UnsupportedOperationException`.

---

### Bài B: Viết helper `calculateDiscountedPrice`
Viết method trong Java thuần:
```java
public static BigDecimal calculateDiscountedPrice(String originalPriceStr, String discountPercentStr)
```
1. Validate `originalPriceStr` không âm, tối đa 2 chữ số thập phân.
2. Validate `discountPercentStr` nằm trong khoảng `"0.00"` đến `"100.00"`.
3. Công thức: $	ext{finalPrice} = 	ext{originalPrice} 	imes (1 - rac{	ext{discountPercent}}{100})$.
4. Làm tròn với scale = 2 bằng `RoundingMode.HALF_UP`.
