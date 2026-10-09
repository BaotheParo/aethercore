# BÀI TẬP TỰ LÀM M1-E1: THIẾT KẾ IMMUTABLE VALUE KEY (ITEMKEY)

## 1. Yêu cầu bài toán
Trong hệ thống game/inventory của AetherCore, mỗi vật phẩm được định danh duy nhất bởi một cặp:
- `UUID itemId` (mã định danh vật phẩm, không được null).
- `String itemType` (loại vật phẩm, ví dụ: `"WEAPON"`, `"ARMOR"`, `"POTION"`, không được null).

Hãy tự tay viết class `ItemKey` bằng Java thuần thỏa mãn các tiêu chí kỹ thuật:
1. Class phải là `final` và các field phải là `final` (Immutable).
2. Constructor validate null (`IllegalArgumentException` nếu field null).
3. Override `equals(Object o)` đúng chuẩn:
   - Kiểm tra tham chiếu `this == o`.
   - Kiểm tra `null` và `getClass() != o.getClass()`.
   - Ép kiểu và so sánh cả 2 field bằng `Objects.equals(...)`.
4. Override `hashCode()` nhất quán bằng `Objects.hash(itemId, itemType)`.

---

## 2. Thử thách câu hỏi vặn sau khi code
1. Nếu hai vật phẩm có `itemId` khác nhau nhưng do trùng ngẫu nhiên dẫn đến `hashCode()` bằng nhau, `HashMap` có phân biệt được không? Cơ chế nào giúp phân biệt?
2. Tại sao ta nên dùng `getClass() != o.getClass()` thay vì `o instanceof ItemKey` khi viết `equals()` cho một Value Object?
