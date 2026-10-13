# M2-E4A-EXERCISE: Bài tập Thực hành JPA Fetching & N+1 Problem

**Mục tiêu:** Tự xây dựng kịch bản kiểm thử, dự đoán chính xác số lượng prepared statements phát sinh, và triển khai giải pháp tối ưu hóa bằng JPA EntityGraph mà không làm sai lệch hợp đồng nghiệp vụ.

---

## Phần 1: Dự đoán Thực nghiệm trên Fixture Mới

Cho cơ sở dữ liệu với tập dữ liệu (dataset D3) như sau:
- **Bảng `fetch_buyer`:** 4 buyers ID `[1, 2, 3, 4]`, tên tương ứng `Buyer-1` .. `Buyer-4`.
- **Bảng `fetch_order`:** 8 orders ID `[101, 102, 103, 104, 105, 106, 107, 108]`, đều có `quantity = 1`.
  - Danh sách `buyer_id` tương ứng với từng đơn: `[1, 2, 1, 3, 2, 4, 4, 3]`.

Giả sử chạy trên cùng cấu hình của lab M2-E4A (Second-level cache tắt, batch fetch size = 1, EntityManager mới hoàn toàn):

### Bảng Dự đoán (Dành cho Học viên)

| Giai đoạn thực thi | Dự đoán số Prepared Statements | Giải thích cơ chế (Cache hit / Cache miss / Proxy loading) |
|:---|:---:|:---|
| Ngay sau khi thực hiện Root Query `select o from FetchOrder o order by o.id` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| Sau khi duyệt qua 8 đơn hàng và gọi `order.getBuyer().getName()` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |
| Nếu thay bằng Dynamic EntityGraph nạp thuộc tính `buyer` | `[AWAITING_CANDIDATE]` | `[AWAITING_CANDIDATE]` |

---

## Phần 2: Yêu cầu Viết Code Thực hành

Học viên tự viết một class kiểm thử mới (hoặc bổ sung method) đáp ứng các tiêu chí sau:
1. **Reset và seed đúng fixture D3** qua JDBC trước khi chạy.
2. **Kịch bản 1 (Baseline):**
   - Truy vấn `FetchOrder` thông thường.
   - Assert số câu lệnh sau root query.
   - Duyệt và trích xuất `buyer.getName()`.
   - Assert số câu lệnh cuối cùng khớp với dự đoán của First-level cache.
   - Assert kết quả nghiệp vụ đủ đúng 8 DTO `OrderSummary` với thứ tự và tên buyer tương ứng.
3. **Kịch bản 2 (Tối ưu hóa bằng EntityGraph):**
   - Tạo dynamic `EntityGraph` và áp dụng hint `jakarta.persistence.fetchgraph`.
   - Assert số câu lệnh phát sinh.
   - Assert tính đúng đắn của dữ liệu nghiệp vụ 8 đơn hàng.

### Không gian nộp code của Học viên:
```java
// [AWAITING_CANDIDATE: Học viên tự viết mã nguồn kiểm thử tại đây]
```

---

## Phần 3: Phỏng vấn Nhanh (Oral Defense - 60 đến 90 giây mỗi câu)

Học viên chuẩn bị câu trả lời trực tiếp cho 6 câu hỏi sau:

1. **Vì sao code Java chỉ gọi một câu `createQuery` duy nhất nhưng Database Engine lại ghi nhận nhiều câu lệnh SQL?**
2. **Vì sao việc đọc `order.getBuyer().getId()` trong một số trường hợp không tái hiện được lỗi N+1, trong khi `order.getBuyer().getName()` lại kích hoạt ngay lập tức?**
3. **Nếu có hai đơn hàng liên tiếp thuộc về cùng một người mua, liệu Hibernate có luôn phát sinh hai câu SELECT thứ cấp không? Điều kiện tiên quyết nào quyết định điều này?**
4. **Sự khác biệt cốt lõi giữa `JOIN` thông thường và `JOIN FETCH` trong JPQL khi kết quả trả về là một Entity là gì?**
5. **Tại sao DTO Projection (Constructor Expression) lại là lựa chọn ưu tiên cho các màn hình danh sách/báo cáo chỉ đọc (read-only), và lập trình viên phải đánh đổi điều gì khi dùng nó?**
6. **Hợp đồng Jakarta Persistence có bảo đảm `EntityGraph` luôn sinh đúng duy nhất 1 câu SQL trên mọi provider và mọi hệ quản trị DB không? Em dựa vào bằng chứng nào để trả lời cho repo này?**

---

## Phần 4: Khung Đánh giá (Rubric dành cho Mentor)

*Không tự điền điểm. Khung chấm dùng khi phỏng vấn học viên:*

| Mức điểm | Tiêu chí đánh giá |
|:---:|:---|
| **0** | Không giải thích được cơ chế hoặc suy diễn sai (nhầm lẫn giữa JDBC roundtrip và application cache). |
| **1** | Nêu đúng thuật ngữ (Lazy loading, Proxy, First-level cache, EntityGraph, Constructor projection) nhưng chưa kết nối được với luồng SQL thực tế. |
| **2** | Kết nối chính xác: Code Java $\rightarrow$ Proxy/State $\rightarrow$ Câu lệnh SQL cụ thể phát sinh $\rightarrow$ Số đếm thực nghiệm. |
| **3** | Nắm vững ranh giới và ngoại lệ: Giải thích được First-level cache scope (Session-bound), tính năng uninitialized proxy của `getId()`, và hiểu vì sao EntityGraph là gợi ý (hint) chứ không phải cam kết tuyệt đối của mọi JPA provider. |
