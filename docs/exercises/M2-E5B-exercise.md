# Bài Tập M2-E5B: Thực Hành Keyset Pagination và Tư Duy Phân Trang Bền Vững

**Mã bài tập:** M2-E5B-EXERCISE
**Mục tiêu:** Tự triển khai truy vấn phân trang Keyset với composite cursor, xử lý dữ liệu trùng timestamp, kiểm chứng bất biến duyệt trang và phân tích tác động của giao dịch đồng thời.
**Trạng thái học viên:** `[AWAITING_CANDIDATE]`

---

## 1. Ngữ Cảnh và Dữ Liệu Thử Nghiệm Riêng (Dedicated Fixture)

Để rèn luyện kỹ năng mà không phụ thuộc vào dữ liệu có sẵn của bài lab, học viên sử dụng một tập dữ liệu thử nghiệm độc lập gồm **37 giao dịch**:
- Mỗi nhóm **4 IDs** liên tiếp có cùng một giá trị thời gian `created_at`.
- Kích thước trang chuẩn: **`pageSize = 6`**.

### Câu lệnh SQL tạo dữ liệu thử nghiệm:
```sql
CREATE TABLE IF NOT EXISTS exercise_trade (
    id         BIGINT PRIMARY KEY,
    buyer_id   BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    payload    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ex_trade_order
    ON exercise_trade (buyer_id, created_at DESC, id DESC);

-- Chèn 37 bản ghi: g=1..37, cứ 4 IDs cùng 1 timestamp
INSERT INTO exercise_trade (id, buyer_id, created_at, payload)
SELECT g, 99,
       TIMESTAMPTZ '2026-03-01 00:00:00+00' + (((g - 1) / 4) * INTERVAL '1 second'),
       'payload-' || g
FROM generate_series(1, 37) g;
```

---

## 2. Các Bài Tập Tự Code (Hands-on Coding Exercises)

> [!IMPORTANT]
> **Quy định làm bài:**
> - Học viên tự viết code, tự chạy truy vấn và quan sát kết quả.
> - Tuyệt đối không hardcode danh sách IDs dự kiến vào code bài làm.
> - Đánh dấu trạng thái trả lời bằng `[AWAITING_CANDIDATE]`.

### Bài tập 1: Viết truy vấn Trang đầu và Trang kế tiếp
1. Viết câu lệnh SQL lấy **Trang đầu tiên** (kích thước 6 bản ghi, `buyer_id = 99`, sắp xếp theo `created_at DESC, id DESC`).
2. Viết câu lệnh SQL lấy **Trang kế tiếp** sử dụng cú pháp Tuple Comparison `(created_at, id) < (?, ?)` dựa trên con trỏ lấy từ bản ghi cuối cùng của Trang đầu.

*Câu trả lời của học viên:*
```sql
-- [AWAITING_CANDIDATE: Học viên điền SQL trang đầu tại đây]
```

```sql
-- [AWAITING_CANDIDATE: Học viên điền SQL trang kế tiếp tại đây]
```

### Bài tập 2: Dự đoán và Kiểm chứng duyệt toàn bộ tập dữ liệu tĩnh
Trước khi chạy mã duyệt tự động:
1. Dự đoán tổng số trang cần duyệt để lấy hết 37 bản ghi với `pageSize = 6`.
2. Dự đoán kích thước của trang cuối cùng.
3. Viết vòng lặp Java hoặc SQL script duyệt toàn bộ 37 bản ghi:
   - Thu thập toàn bộ IDs được trả về qua các trang.
   - Assert rằng: danh sách IDs thu được không chứa bất kỳ bản ghi nào bị trùng lặp (`no duplicates`).
   - Assert rằng: toàn bộ các IDs từ 37 xuống 1 đều xuất hiện đầy đủ (`no missing rows`).

*Kết quả dự đoán và ghi nhận của học viên:*
- Tổng số trang dự đoán: `[AWAITING_CANDIDATE]`
- Kích thước trang cuối dự đoán: `[AWAITING_CANDIDATE]`
- Kết quả kiểm chứng tự động: `[AWAITING_CANDIDATE]`

### Bài tập 3: Mô phỏng giao dịch chèn lùi thời gian (Backdated Transaction)
Giả sử người dùng vừa đọc xong Trang 1 (gồm 6 bản ghi có ID lớn nhất).
Ngay sau đó, một tiến trình background thực hiện chèn một bản ghi giao dịch trễ hạn:
```sql
INSERT INTO exercise_trade (id, buyer_id, created_at, payload)
VALUES (999, 99, TIMESTAMPTZ '2026-03-01 00:00:08+00', 'backdated-tx');
```
1. Xác định vị trí logic của bản ghi ID 999 so với con trỏ của Trang 1.
2. Bản ghi ID 999 có xuất hiện ở Trang 2 hoặc các trang tiếp theo khi tiếp tục phân trang bằng Keyset không? Vì sao?
3. Nếu hệ thống dùng OFFSET phân trang, bản ghi này sẽ gây ra hiện tượng gì cho các trang tiếp theo?

*Phân tích của học viên:*
- Vị trí bản ghi so với con trỏ: `[AWAITING_CANDIDATE]`
- Khả năng xuất hiện trong Keyset: `[AWAITING_CANDIDATE]`
- Ảnh hưởng nếu dùng OFFSET: `[AWAITING_CANDIDATE]`

### Bài tập 4: Tái định nghĩa bất biến khi dữ liệu biến động
Khi kiểm thử trên tập dữ liệu tĩnh, bất biến kiểm chứng là: *"Tập hợp các phần tử của mọi trang phải bằng đúng tập dữ liệu gốc, không thừa không thiếu"*.
Khi hệ thống chạy trong môi trường thực tế (dữ liệu chèn/sửa/xóa liên tục):
1. Vì sao tiêu chuẩn bất biến trên không còn áp dụng được nguyên vẹn?
2. Trong môi trường thực tế, khế ước nhất quán nào mà Keyset pagination cam kết bảo đảm cho người dùng?

*Câu trả lời của học viên:*
- Giải thích: `[AWAITING_CANDIDATE]`
- Khế ước bảo đảm: `[AWAITING_CANDIDATE]`

---

## 3. Sáu Câu Hỏi Phỏng Vấn Kỹ Thuật Chuyên Sâu (Oral Interview Questions)

Học viên chuẩn bị câu trả lời miệng cho 6 câu hỏi kỹ thuật dưới đây mà không nhìn tài liệu:

1. **Câu hỏi 1 (Limit vs Scan Overhead):**
   Trong câu truy vấn `OFFSET 80000 LIMIT 20`, kết quả trả về cho client chỉ có đúng 20 dòng. Vì sao trong EXPLAIN ANALYZE, node Index Scan ở dưới lại ghi nhận `Actual Rows: 80020`? 80.000 dòng dư thừa đó đã đi đâu và tiêu tốn những tài nguyên gì của database?
   *Trả lời:* `[AWAITING_CANDIDATE]`

2. **Câu hỏi 2 (Composite Index vs Deep Offset):**
   Nếu ta đã tạo một composite index tối ưu bao phủ cả điều kiện lọc và sắp xếp `(buyer_id, created_at DESC, id DESC)`, tại sao câu truy vấn với `OFFSET 80000` vẫn bị chậm rõ rệt so với `OFFSET 0`? Index có giúp nhảy cóc đến dòng thứ 80.001 được không?
   *Trả lời:* `[AWAITING_CANDIDATE]`

3. **Câu hỏi 3 (Timestamp Cursor Trap):**
   Một lập trình viên thiết kế API phân trang dùng con trỏ đơn `created_at < cursorTime`. Tại sao hệ thống vẫn chạy bình thường trong môi trường dev/staging nhưng lại gây ra lỗi mất đơn hàng nghiêm trọng khi đưa lên production có lượng traffic cao?
   *Trả lời:* `[AWAITING_CANDIDATE]`

4. **Câu hỏi 4 (Tuple Comparison vs AND Predicate):**
   Trong SQL, tại sao điều kiện `(created_at, id) < (cursorTime, cursorId)` lại cho kết quả hoàn toàn khác biệt so với `created_at < cursorTime AND id < cursorId`? Viết biểu thức logic tương đương của phép so sánh tuple bằng các toán tử AND / OR cơ bản.
   *Trả lời:* `[AWAITING_CANDIDATE]`

5. **Câu hỏi 5 (Keyset vs Snapshot Isolation):**
   Keyset pagination giải quyết được lỗi lặp dòng (KP3) và mất dòng (KP4) khi có giao dịch chèn/xóa ở đầu danh sách. Tuy nhiên, tại sao nói Keyset pagination vẫn KHÔNG PHẢI là một giải pháp phân trang Snapshot thực sự qua nhiều request HTTP?
   *Trả lời:* `[AWAITING_CANDIDATE]`

6. **Câu hỏi 6 (Cost Boundary in Benchmark):**
   Trong thí nghiệm so sánh hiệu năng giữa Keyset và Deep Offset (KP1), chúng ta đã dùng một con trỏ biên biết trước (`cursorId = 20001`). Nếu trong nghiệp vụ thực tế, người dùng muốn nhảy ngay lập tức từ trang 1 sang trang 4.001, phép đo trên đã bỏ qua chi phí tiềm ẩn nào? Làm thế nào để giải quyết nhu cầu này trong thực tế?
   *Trả lời:* `[AWAITING_CANDIDATE]`

---

## 4. Gợi Ý Định Hướng Theo Cấp Độ (Graduated Hints)

*Lưu ý: Chỉ mở gợi ý khi học viên đã suy nghĩ ít nhất 15 phút.*

<details>
<summary>Gợi ý Cấp 1 (Tư duy cú pháp SQL)</summary>

- Kiểm tra thứ tự các cột trong mệnh đề `ORDER BY` và chỉ mục B-Tree: cả hai đều phải cùng chiều `DESC`.
- Cú pháp tuple trong PostgreSQL hỗ trợ trực tiếp dấu so sánh: `WHERE (col1, col2) < (?, ?)`.
</details>

<details>
<summary>Gợi ý Cấp 2 (Tư duy toán học phép so sánh bộ)</summary>

- Hãy nhớ lại cách so sánh hai từ trong từ điển: từ nào có chữ cái đầu tiên nhỏ hơn sẽ đứng trước; chỉ khi hai chữ cái đầu bằng nhau ta mới so sánh chữ cái thứ hai.
- `(A, B) < (a, b)` tương đương với `(A < a) OR (A = a AND B < b)`.
</details>

<details>
<summary>Gợi ý Cấp 3 (Tư duy trạng thái phân trang)</summary>

- Con trỏ của Keyset chỉ neo vào dòng cuối cùng mà client đã nhìn thấy. Mọi thay đổi dữ liệu xảy ra phía trên (trước) con trỏ sẽ không làm xê dịch vị trí tương đối của các dòng nằm phía dưới (sau) con trỏ.
- Tuy nhiên, nếu một dòng dữ liệu mới được chèn vào với giá trị thời gian nằm sau con trỏ, nó sẽ xuất hiện ở trang tiếp theo như một phần tử mới.
</details>
