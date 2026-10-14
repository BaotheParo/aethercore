# M2-E8A: Bài Tập Thực Hành — Kiểm Thử Hồi Quy Xâm Phạm Quyền Sở Hữu (Ownership Spoofing & Scope Isolation)

## 1. Bối Cảnh Bài Tập

Trong hệ thống AetherCore Market, hai người dùng $A$ và $B$ đều mang vai trò `TRADER`.
Giả sử người dùng $B$ biết được mã Idempotency Key $K$ mà người dùng $A$ vừa sử dụng để mua hàng thành công.
Người dùng $B$ thực hiện kịch bản tấn công/thử nghiệm sau:
1. Gửi request POST mua hàng với token của $B$, mang key $K$, nhưng cố tình đính kèm header `X-Caller-Id: <caller_A>`.
2. Sau đó, người dùng $B$ tiếp tục gửi request POST mua hàng với token của $B$, mang chính key $K$ đó với payload hợp lệ của $B$.

---

## 2. Yêu Cầu Dành Cho Ứng Viên

Ứng viên không được sửa code main của ứng dụng. Hãy bổ sung một test method hồi quy vào `MarketSecurityIntegrationTest.java` (hoặc test class riêng) để chứng minh tính bất biến của hệ thống bảo mật:

1. **Dự đoán và kiểm chứng HTTP status codes:**
   - Khi $B$ gửi header giả mạo `X-Caller-Id: <caller_A>`, hệ thống phản hồi HTTP code nào? Vì sao?
   - Dữ liệu giao dịch được lưu dưới danh nghĩa của ai trong database?
2. **Kiểm chứng tính cô lập phạm vi (Scope Isolation):**
   - Khi $B$ dùng chính key $K$ của $A$ để mua hàng dưới tài khoản $B$:
     * Trường hợp 1: Key $K$ chưa từng được $B$ sử dụng ──> Kết quả ra sao? Số lượng đơn hàng của $A$ và của $B$ thay đổi thế nào?
     * Trường hợp 2: Key $K$ đã được $B$ mua thành công trước đó ──> Hệ thống xử lý ra sao nếu $B$ gửi lại cùng payload vs gửi khác payload?
3. **Phân tích truy vấn SQL:**
   - Viết câu lệnh SQL mà ứng viên sẽ dùng để khẳng định rằng người dùng $A$ hoàn toàn không bị ảnh hưởng bởi các request do $B$ phát sinh.

---

## 3. Câu Hỏi Phỏng Vấn Nhanh (Oral Interview 60–90 Giây)

### Câu 1: Khác biệt cốt lõi giữa Decode, Verify và Authorize một JWT?
- **[AWAITING_CANDIDATE]**

### Câu 2: Tại sao kiểm tra quyền theo Role (`hasRole('TRADER')`) là chưa đủ để ngăn chặn người dùng truy cập dữ liệu của nhau?
- **[AWAITING_CANDIDATE]**

### Câu 3: Tại sao một bài test dùng Mock JWT (như `with(jwt())`) có thể PASS nhưng khi chạy thực tế hệ thống vẫn có thể chấp nhận token mang chữ ký giả hoặc sai Issuer?
- **[AWAITING_CANDIDATE]**

---

## 4. Tiêu Chí Đánh Giá (Rubric Đánh Giá)

| Tiêu chí | Mức Đạt (Pass) | Mức Xuất Sắc (Exceeds) |
|---|---|---|
| **Vị trí file & cú pháp** | Đặt đúng test class, sử dụng đúng `MockMvc` và `TestJwtSupport` | Viết code gọn gàng, assert đầy đủ cả HTTP response và DB snapshot |
| **Hiểu cơ chế Caller Scope** | Giải thích đúng danh tính caller luôn lấy từ JWT `sub`, header giả mạo bị phớt lờ | Phân tích được khóa chính 3 thành phần `(caller_id, operation, idempotency_key)` trong PostgreSQL |
| **Oral Interview** | Trả lời gãy gọn cả 3 câu hỏi trong 3 phút, đúng thuật ngữ kỹ thuật | Viện dẫn được rủi ro BOLA/IDOR, phân biệt được Mock layer vs Real Security Filter Chain |

---

## 5. Không Gian Trả Lời Của Ứng Viên

### Phần Bài Làm Của Ứng Viên:
```
[AWAITING_CANDIDATE]
```

### Đánh Giá Của Người Phỏng Vấn:
```
[AWAITING_CANDIDATE]
```
