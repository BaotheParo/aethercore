# Phiếu Bài Tập Thực Hành: M2-E1A (Tự Làm)

**Mã bài tập:** `M2-E1A-EX01`  
**Chủ đề:** Kiểm thử luồng mua hàng đồng thời với số lượng lớn hơn 1 (`quantity > 1`) và tồn kho lẻ  
**Trạng thái người học:** `AWAITING_CANDIDATE`

---

## 1. Đề bài

Cho một mặt hàng `sku = "ANCIENT_DEBRIS"` có tồn kho ban đầu:
- **Tồn kho ban đầu:** `7` sản phẩm.
- Có **4 luồng đồng thời** (buyers: T1, T2, T3, T4), mỗi luồng yêu cầu mua đúng **2** sản phẩm (`quantity = 2`).
- Mỗi luồng chạy qua service chuẩn `FixedMarketPurchaseService`.

### Nhiệm vụ của bạn:
1. **Dự đoán kết quả trước khi viết mã:**
   - Có bao nhiêu yêu cầu mua thành công (`SUCCESS`)?
   - Có bao nhiêu yêu cầu bị từ chối (`NOT_AVAILABLE`)?
   - Tồn kho cuối cùng trong bảng `market_stock` là bao nhiêu?
   - Số lượng bản ghi trong bảng `market_purchase` (số order) là bao nhiêu?
   - Tổng số lượng sản phẩm đã bán $\sum \text{quantity}$ là bao nhiêu?
   - Giải thích: Tại sao trong bài này số lượng đơn hàng (order count) khác với tổng số lượng sản phẩm bán ra?
2. **Tự viết một bài test:**
   - Viết phương thức test trong `MarketPurchaseConcurrencyTest` (hoặc class test riêng của bạn) mô phỏng đúng kịch bản trên với `CountDownLatch` hoặc `ExecutorService`.
   - Viết các assertion kiểm tra bất biến dữ liệu:
     $$\text{stock}_{\text{cuối}} + \sum \text{quantity}_{\text{purchase}} = 7$$

---

## 2. Tiêu chí nghiệm thu (Acceptance Criteria)

- Không được hardcode kết quả; test phải gọi qua `FixedMarketPurchaseService.purchase(...)`.
- Bất biến toàn vẹn $\text{stock}_{\text{cuối}} + \sum \text{quantity} = 7$ phải đúng $100\%$.
- Tồn kho không bao giờ được âm ($available \ge 0$).
- Không có hiện tượng deadlock hoặc thread bị treo khi chạy test.

---

## 3. Gợi ý theo tầng (Tiered Hints)

> Chỉ mở gợi ý khi bạn đã suy nghĩ ít nhất 15 phút.

<details>
<summary>Gợi ý Tầng 1: Về toán học tồn kho</summary>

Mỗi giao dịch cần 2 đơn vị. Nếu có 7 đơn vị:
- $7 - 2 = 5$ (Order 1 thành công)
- $5 - 2 = 3$ (Order 2 thành công)
- $3 - 2 = 1$ (Order 3 thành công)
- Với 1 đơn vị còn lại, người mua thứ 4 đòi mua 2 đơn vị $\rightarrow$ Điều kiện `available >= 2` trong câu lệnh SQL sẽ đánh giá thế nào?
</details>

<details>
<summary>Gợi ý Tầng 2: Về cấu trúc Harness Concurrency</summary>

Dùng `CountDownLatch(1)` làm start gate để cả 4 worker thread tập trung tại một vạch xuất phát trước khi cùng gọi `purchase()`. Dùng `Future<PurchaseResult>` để thu thập kết quả và đếm số lượng `SUCCESS` và `NOT_AVAILABLE`.
</details>

<details>
<summary>Gợi ý Tầng 3: Về truy vấn đối chiếu DB</summary>

Sử dụng câu truy vấn:
`SELECT available FROM market_stock WHERE sku = ?` và  
`SELECT COALESCE(SUM(quantity), 0) FROM market_purchase WHERE sku = ?`
để đối chiếu trực tiếp dữ liệu thực tế trong DB sau khi cả 4 luồng đã kết thúc.
</details>
