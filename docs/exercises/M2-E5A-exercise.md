# Bài Tập Thực Hành M2-E5A: EXPLAIN, Lựa Chọn Index và Tối Ưu Hóa Truy Vấn

**Mã bài tập:** M2-E5A-EXERCISE
**Chủ đề:** Tối ưu hóa truy vấn SQL, phân tích cây thực thi EXPLAIN, lựa chọn Index đơn vs Index ghép trên PostgreSQL 16.
**Trạng thái ứng viên:** `[AWAITING_CANDIDATE]` (Tất cả câu trả lời, dự đoán và code bài tập dưới đây do ứng viên tự hoàn thành).

---

## 1. Bối Cảnh & Đề Bài Thực Hành

Giữ nguyên bộ dữ liệu chuẩn 100.000 dòng trên bảng `query_plan_trade` (1.000 buyers, mỗi buyer 100 dòng, 10% PENDING và 90% COMPLETED).

Ta thay đổi truy vấn nghiệp vụ Q1 sang một người mua khác và giới hạn trang nhỏ hơn:

```sql
SELECT id, buyer_id, status, created_at, payload
FROM query_plan_trade
WHERE buyer_id = 77
  AND status = 'PENDING'
ORDER BY created_at DESC, id DESC
LIMIT 3;
```

---

## 2. Nhiệm Vụ Của Ứng Viên

### Nhiệm vụ 1: Tính toán tập kết quả độc lập từ công thức dữ liệu
Dựa trên công thức sinh dữ liệu của bảng `query_plan_trade`:
- `id = g` (1..100000)
- `buyer_id = ((g - 1) % 1000) + 1`
- `batch = (g - 1) / 1000`
- `status = 'PENDING'` khi `batch % 10 = 0`
- `created_at` tăng dần theo `g`

**Yêu cầu:** Hãy tự tính toán thủ công danh sách 3 ID giao dịch PENDING mới nhất của người mua số 77:
- Danh sách 3 IDs kỳ vọng: `[AWAITING_CANDIDATE]`
- Giải thích các bước tính toán: `[AWAITING_CANDIDATE]`

---

### Nhiệm vụ 2: Dự đoán cây kế hoạch thực thi (Query Plan Prediction)
Hãy đưa ra dự đoán kế hoạch thực thi cho 2 trường hợp:

1. **Trường hợp A — Khi chỉ có chỉ mục đơn:** `CREATE INDEX idx_qpt_buyer ON query_plan_trade(buyer_id)`
   - Cây node dự đoán (Node path): `[AWAITING_CANDIDATE]`
   - Có xuất hiện node `Sort` không? Vì sao: `[AWAITING_CANDIDATE]`
   - Loại scan dự kiến (Index Scan / Bitmap Scan / Seq Scan): `[AWAITING_CANDIDATE]`

2. **Trường hợp B — Khi có chỉ mục ghép:** `CREATE INDEX idx_qpt_buyer_status_recent ON query_plan_trade(buyer_id, status, created_at DESC, id DESC)`
   - Cây node dự đoán (Node path): `[AWAITING_CANDIDATE]`
   - Có xuất hiện node `Sort` không? Vì sao: `[AWAITING_CANDIDATE]`
   - Số lượng dòng thực tế mà engine quét ở node lá (`Actual Rows`): `[AWAITING_CANDIDATE]`

---

### Nhiệm vụ 3: Tự viết mã kiểm thử và xuất EXPLAIN JSON
Viết một phương thức test trong Java sử dụng JDBC (tương tự `PostgresQueryPlanExperimentsTest`) để thực thi câu truy vấn trên đối với 2 loại index và xuất ra tệp `EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON)`.

*Vị trí dán mã nguồn thực hiện của bạn:*
```java
// [AWAITING_CANDIDATE]
```

*Vị trí dán kết quả JSON EXPLAIN thu được:*
```json
// [AWAITING_CANDIDATE]
```

---

### Nhiệm vụ 4: Phân tích so sánh giữa Kế hoạch Thực tế và Dự đoán
- Trường hợp A: Kế hoạch thực tế quan sát được là gì? Có khớp với dự đoán không? `[AWAITING_CANDIDATE]`
- Trường hợp B: Kế hoạch thực tế quan sát được là gì? Node `Limit` đã tác động đến `Index Scan` ra sao? `[AWAITING_CANDIDATE]`
- Số lượng Shared Hit Blocks chênh lệch giữa 2 trường hợp: `[AWAITING_CANDIDATE]`

---

### Nhiệm vụ 5: Câu hỏi mở về thiết kế chỉ mục B-tree (Open-ended Design Question)
Giả sử hệ thống phát sinh thêm một nghiệp vụ mới trên màn hình Admin Dashboard:
*"Tìm 10 giao dịch PENDING mới nhất của TOÀN BỘ hệ thống (không lọc theo buyer_id)"*:

```sql
SELECT id, buyer_id, status, created_at, payload
FROM query_plan_trade
WHERE status = 'PENDING'
ORDER BY created_at DESC, id DESC
LIMIT 10;
```

**Câu hỏi:** Tại sao chúng ta **KHÔNG THỂ** mặc định cho rằng chỉ mục ghép cũ `idx_qpt_buyer_status_recent (buyer_id, status, created_at DESC, id DESC)` vẫn tối ưu cho truy vấn này? Nguyên tắc B-Tree Leftmost Prefix (tiền tố ngoài cùng bên trái) giải thích vấn đề này như thế nào? Cần thiết kế chỉ mục mới ra sao cho truy vấn của Admin?

*Câu trả lời của bạn:*
`[AWAITING_CANDIDATE]`

---

## 3. Ngân Hàng Câu Hỏi Phỏng Vấn Nhanh (Oral Defense 60–90 giây)

Hãy chuẩn bị câu trả lời ngắn gọn (60–90 giây), súc tích, đi thẳng vào bản chất kỹ thuật cho 6 câu hỏi kinh điển sau:

### Câu 1: `Cost = 200` trong EXPLAIN có đồng nghĩa với việc truy vấn mất 200 mili-giây không?
*Gợi ý trả lời:* Cost là đơn vị tính toán trừu tượng nội bộ của optimizer (dựa trên các tham số như `seq_page_cost=1.0`, `random_page_cost=4.0`, `cpu_tuple_cost=0.01`), hoàn toàn không phải đơn vị thời gian. Một truy vấn cost 200 trên server RAM khủng có thể chạy trong 0.05ms, nhưng trên đĩa chậm có thể mất nhiều hơn.
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

### Câu 2: Trong node `Seq Scan`, nếu thấy `Actual Rows = 10`, điều đó có chứng minh database chỉ xem đúng 10 dòng không?
*Gợi ý trả lời:* Không. `Actual Rows` ở đầu ra của node chỉ là số dòng thỏa mãn bộ lọc (`Filter`). Để biết database đã duyệt qua bao nhiêu dòng, phải nhìn vào thuộc tính `Rows Removed by Filter` (ví dụ bị loại bỏ 99.990 dòng) và số lượng Blocks được nạp (`Shared Hit/Read Blocks`).
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

### Câu 3: Tại sao khi đã tạo index trên cột `buyer_id`, câu lệnh có `ORDER BY created_at DESC` vẫn có thể cần thêm node `Sort`?
*Gợi ý trả lời:* Bởi vì chỉ mục đơn trên `buyer_id` chỉ sắp xếp các trang theo ID người mua. Trong cùng một người mua, các bản ghi nằm phân tán trên các trang dữ liệu và không được lưu theo thứ tự `created_at`. Cơ sở dữ liệu bắt buộc phải nạp các dòng thỏa mãn rồi thực hiện phép sắp xếp riêng trong bộ nhớ.
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

### Câu 4: Khi nào một câu truy vấn có chỉ mục nhưng PostgreSQL vẫn quyết định chạy `Seq Scan`?
*Gợi ý trả lời:* Khi độ chọn lọc của truy vấn thấp (truy vấn khớp với tỷ lệ lớn của bảng, ví dụ 80-90%) và truy vấn cần đọc các cột ngoài index (cần nạp dữ liệu từ heap table). Chi phí đọc ngẫu nhiên (random I/O) qua index nhiều lần sẽ đắt hơn nhiều so với việc quét tuần tự (sequential I/O) toàn bộ các block dữ liệu liên tiếp.
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

### Câu 5: Tại sao không được lấy tổng `Shared Hit Blocks` của tất cả các node trong cây EXPLAIN cộng lại để tính tổng I/O?
*Gợi ý trả lời:* Vì bộ đệm trong cây EXPLAIN có tính chất tích lũy (hierarchical cumulative). Nút cha kế thừa và bao gồm cả số block mà nút con đã đọc. Nếu cộng dồn toàn bộ các node, ta sẽ tính trùng nhiều lần cùng một block dữ liệu.
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

### Câu 6: Từ một kết quả `EXPLAIN ANALYZE` trong môi trường kiểm thử, em được phép và chưa được phép kết luận điều gì về tốc độ thực tế của API?
*Gợi ý trả lời:* Được phép kết luận về đường đi dữ liệu (Access Path), các bước tốn kém (I/O, Sort), và chi phí ước lượng của engine. Chưa được phép kết luận về SLA hay latency của API thực tế vì EXPLAIN không bao gồm Network latency, JDBC overhead, tranh chấp lock đồng thời, cũng như chi phí phát sinh khi có tải ghi đồng thời (write concurrency).
*Câu trả lời của ứng viên:* `[AWAITING_CANDIDATE]`
*Điểm đánh giá (0-5):* `[AWAITING_CANDIDATE]`

---

## 4. Hướng Dẫn Tự Ôn Tập Độc Lập
1. Vẽ lại sơ đồ cây kế hoạch thực thi của 4 kịch bản EP1–EP4 mà không nhìn vào tài liệu.
2. Tự mở tệp JSON `query-plan-observations.json` và phân tích từng trường `Index Cond` vs `Filter`.
3. Tự thực hiện bài tập trên branch cá nhân.
4. Ghi âm câu trả lời cho 6 câu hỏi phỏng vấn nhanh trong 60–90 giây và tự đối chiếu với phần gợi ý.
