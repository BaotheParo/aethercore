# M2-E5B: Phân Trang Keyset (Seek Method) và Đánh Đổi với OFFSET Sâu trong PostgreSQL

**Mã thí nghiệm:** M2-E5B
**Môi trường:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12
**Trạng thái kiểm thử:** 4/4 scenarios PASS, 101/101 reactor tests PASS (Core: 34, Backend: 67)
**Bằng chứng kiểm chứng:** `docs/evidence/prompt17/keyset-pagination-observations.json` (Run ID: `prompt17-20261013-191256-f0cf3e`)

---

## 1. Bài Toán Lịch Sử Giao Dịch & Khế Ước Trang Kế Tiếp

Trong các hệ thống tài chính, thương mại điện tử hoặc nhật ký sự kiện, truy vấn xem lịch sử giao dịch là một trong những tác vụ phổ biến nhất. Khế ước API phân trang thông thường yêu cầu:
- Hiển thị danh sách giao dịch của một người mua cụ thể (`buyer_id = ?`).
- Sắp xếp theo thứ tự mới nhất trước (`created_at DESC`), nếu trùng thời gian thì ưu tiên ID lớn hơn (`id DESC`).
- Mỗi trang trả về một kích thước cố định (`LIMIT ?`, ví dụ: 20 bản ghi).

### Vấn đề cốt lõi của OFFSET sâu (Deep OFFSET)
Khi người dùng cuộn đến trang thứ 4.001 (tương ứng `OFFSET 80000 LIMIT 20`) trên một bảng có 100.000 dòng:
- Để trả về 20 dòng từ vị trí 80.001 đến 80.020, database engine không thể nhảy cóc thẳng vào vị trí thứ 80.001 nếu chỉ dựa vào số đếm dòng.
- Thay vào đó, bộ quét (Scan node) bắt buộc phải đọc qua và nạp đủ **80.020 dòng**, sau đó node `Limit` mới loại bỏ 80.000 dòng đầu tiên để lấy 20 dòng cuối cùng.
- Khi giá trị OFFSET càng lớn, chi phí CPU và I/O tăng tuyến tính theo số dòng bị vứt bỏ, gây áp lực nghẽn tài nguyên trên cơ sở dữ liệu.

### Hướng giải quyết bằng Keyset (Seek Method)
Thay vì ra lệnh cho cơ sở dữ liệu "hãy bỏ qua N dòng", client lưu giữ **giá trị biên của dòng cuối cùng của trang trước** (cursor: gồm cả `created_at` và `id`), sau đó gửi kèm vào mệnh đề `WHERE` của truy vấn trang kế tiếp:
$$\text{WHERE buyer\_id} = ? \quad \text{AND} \quad (created\_at, id) < (cursorTime, cursorId)$$
Bằng cách này, database engine tận dụng cây B-Tree của chỉ mục để **định vị thẳng (Index Seek)** tới vị trí kế tiếp trong thời gian $O(\log N)$, chỉ nạp đúng 20 dòng cần thiết mà không phải duyệt qua hay vứt bỏ bất kỳ dòng nào trước đó.

---

## 2. Cấu Trúc Bảng, Chỉ Mục và Hai Dạng Truy Vấn Chính

### 2.1. Lược đồ cơ sở dữ liệu (Probe Schema)

```sql
CREATE TABLE keyset_trade (
    id         BIGINT PRIMARY KEY,
    buyer_id   BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    payload    TEXT NOT NULL
);

CREATE INDEX idx_kst_buyer_recent
    ON keyset_trade (buyer_id, created_at DESC, id DESC);
```

*Lưu ý kỹ thuật:*
- Cột `payload` chứa chuỗi hash dài 128 ký tự (`repeat(md5(g::text), 4)`) để phản ánh kích thước hàng thực tế (row width ~156 bytes), ngăn chặn việc optimizer tự chuyển sang Index-Only Scan.
- Chỉ mục kết hợp `idx_kst_buyer_recent` chứa `buyer_id` ở vị trí dẫn đầu để lọc bình đẳng, theo sau bởi hai cột sắp xếp `created_at DESC, id DESC` khớp hoàn toàn với mệnh đề `ORDER BY`.

### 2.2. Hai dạng truy vấn so sánh

**Truy vấn OFFSET (Trang sâu):**
```sql
SELECT id, buyer_id, created_at, payload
FROM keyset_trade
WHERE buyer_id = ?
ORDER BY created_at DESC, id DESC
LIMIT ? OFFSET ?;
```

**Truy vấn Keyset (Trang kế tiếp dựa trên Composite Cursor):**
```sql
SELECT id, buyer_id, created_at, payload
FROM keyset_trade
WHERE buyer_id = ?
  AND (created_at, id) < (?, ?)
ORDER BY created_at DESC, id DESC
LIMIT ?;
```

### 2.3. Cú pháp so sánh Tuple Constructor trong PostgreSQL
Phép so sánh bộ hàng `(created_at, id) < (cursorTime, cursorId)` trong chuẩn SQL và PostgreSQL tuân theo quy tắc so sánh từ điển (lexicographical order):
$$(A, B) < (a, b) \iff (A < a) \lor (A = a \land B < b)$$

> [!CAUTION]
> **Cạm bẫy logic phổ biến:** Tuyệt đối không viết thành `created_at < cursorTime AND id < cursorId`. Điều kiện `AND` này sẽ loại bỏ hoàn toàn những dòng có cùng `created_at = cursorTime` nhưng có `id < cursorId`, đồng thời loại bỏ cả những dòng có `created_at < cursorTime` nhưng ngẫu nhiên có `id >= cursorId`, dẫn đến mất mát dữ liệu nghiêm trọng.

---

## 3. Bảng Thực Nghiệm So Sánh KP1: OFFSET Sâu vs Keyset

Thí nghiệm được thực hiện trên bảng `keyset_trade` với tổng cộng **100.005 dòng** (bao gồm 100.000 dòng của `buyer_id = 42` và 5 dòng cô lập của `buyer_id = 99` để kiểm tra độ chọn lọc của chỉ mục tổng hợp).
Kích thước trang: `LIMIT = 20`.
- Trang đầu: OFFSET 0.
- Trang sâu: OFFSET 80.000.
- Keyset: Con trỏ lấy từ bản ghi biên ID 20001 (`cursorTime = 2026-01-01 00:33:20+00`).
Cả hai phương pháp truy vấn trang sâu đều trả về **chính xác cùng danh sách 20 IDs theo thứ tự giảm dần: `[20000, 19999, ..., 19981]`**.

### Bảng số liệu đo đạc thực tế (Run ID: `prompt17-20261013-191256-f0cf3e`)

| Chỉ số đo đạc | Trang Đầu (Baseline) | Deep OFFSET (OFFSET 80.000) | Deep Keyset (Boundary ID 20001) |
|---|---|---|---|
| **SQL Parameters** | `limit=20, offset=0` | `limit=20, offset=80000` | `limit=20, cursorId=20001` |
| **Danh sách IDs trả về** | `[100000..99981]` | `[20000..19981]` | `[20000..19981]` |
| **Root Node Type** | `Limit` | `Limit` | `Limit` |
| **Root Plan Rows / Actual Rows** | 20 / 20 | 20 / 20 | 20 / 20 |
| **Scan Node Type** | `Index Scan` | `Index Scan` | `Index Scan` |
| **Scan Index Name** | `idx_kst_buyer_recent` | `idx_kst_buyer_recent` | `idx_kst_buyer_recent` |
| **Scan Index Cond** | `buyer_id = 42` | `buyer_id = 42` | `(buyer_id = 42) AND ((created_at, id) < (?::timestamptz, ?::bigint))` |
| **Scan Plan Rows / Actual Rows** | 99.998 / **20** | 99.998 / **80.020** | 19.810 / **20** |
| **Số dòng Scan cung cấp (Delivered)** | **20** | **80.020** | **20** |
| **Số dòng Limit loại bỏ (Discarded)** | **0** | **80.000** | **0** |
| **Shared Hit Blocks (Bộ đệm)** | **4 blocks** | **2.591 blocks** | **5 blocks** |
| **Shared Read Blocks (Đọc đĩa)** | 0 blocks | 0 blocks | 0 blocks |
| **Estimated Startup Cost** | 0.42 | 8.639,41 | 0.42 |
| **Estimated Total Cost** | 2.58 | 8.641,57 | 5.93 |
| **Execution Time (Thực tế)** | ~0.033 ms | ~7.774 ms | ~0.055 ms |

### Phân tích cơ chế bộ xử lý (Execution Plan Analysis)
1. **Ở truy vấn Deep OFFSET:** Node con `Index Scan` quét tuần tự qua chỉ mục và nạp ra **80.020 dòng**, tiêu tốn **2.591 blocks bộ đệm**. Node cha `Limit` nhận 80.020 dòng này, vứt bỏ 80.000 dòng đầu tiên và chỉ giữ lại 20 dòng cuối cùng.
2. **Ở truy vấn Deep Keyset:** Nhờ điều kiện `Index Cond` chứa phép so sánh hàng `(created_at, id) < (?, ?)`, engine thực hiện **Index Seek trực tiếp** đến vị trí của con trỏ trên cây B-Tree. Node con `Index Scan` chỉ nạp đúng **20 dòng**, tiêu tốn vỏn vẹn **5 blocks bộ đệm**, và node `Limit` nhận đủ 20 dòng lập tức kết thúc quá trình truy vấn.
3. **Mức độ chênh lệch:** Keyset giảm khối lượng dòng cần duyệt ở tầng scan từ **80.020 dòng xuống đúng 20 dòng** (giảm 4.001 lần), giảm số blocks bộ đệm từ **2.591 xuống 5** (giảm hơn 518 lần).

---

## 4. KP2: Cạm Bẫy Trùng Timestamp và Cursor Một Cột

Trong thực tế, khi tần suất giao dịch cao, nhiều giao dịch chắc chắn sẽ được tạo ra trong cùng một giây hoặc cùng một microsecond.

### Kịch bản kiểm chứng (24 bản ghi, Page Size = 7)
- Dữ liệu thử nghiệm gồm 24 dòng, cứ mỗi nhóm 10 IDs liên tiếp sẽ có cùng một giá trị `created_at` (công thức: `base + ((g - 1) / 10) * 1s`).
  - IDs 24..21: `created_at = base + 2s` (4 dòng)
  - IDs 20..11: `created_at = base + 1s` (10 dòng - nhóm trùng timestamp)
  - IDs 10..1: `created_at = base + 0s` (10 dòng)
- **Trang 1 (Limit 7):** Lấy được các IDs `[24, 23, 22, 21, 20, 19, 18]`. Dòng cuối cùng có ID 18 với `created_at = base + 1s`. Con trỏ trả về là `(cursorTime = base + 1s, cursorId = 18)`.

### So sánh Trang 2:

```
Nhóm trùng timestamp (base + 1s):
[ ID 20, ID 19, ID 18 ]  <--- Đã đọc ở Trang 1
[ ID 17, ID 16, ID 15, ID 14, ID 13, ID 12, ID 11 ]  <--- Còn lại, PHẢI đọc ở Trang 2
```

1. **Khi dùng Cursor một cột sai (`created_at < cursorTime`):**
   - Mệnh đề lọc: `WHERE created_at < 'base + 1s'`
   - Vì các bản ghi từ ID 17 đến ID 11 đều có `created_at = 'base + 1s'`, biểu thức `<` trả về `FALSE`!
   - **Hậu quả:** Database bỏ qua toàn bộ 7 bản ghi `[17, 16, 15, 14, 13, 12, 11]` và nhảy cóc xuống nhóm `base + 0s`, trả về `[10, 9, 8, 7, 6, 5, 4]`.
   - **Người dùng bị mất trắng 7 giao dịch chưa từng được hiển thị!**

2. **Khi dùng Composite Tuple Cursor chuẩn (`(created_at, id) < (cursorTime, cursorId)`):**
   - Mệnh đề lọc: `WHERE (created_at, id) < ('base + 1s', 18)`
   - Với các bản ghi có `created_at = 'base + 1s'`, phép so sánh chuyển sang xét nhánh thứ hai: `created_at = 'base + 1s' AND id < 18`.
   - Các bản ghi có ID từ 17 xuống 11 thỏa mãn điều kiện `id < 18` và được trả về đầy đủ: `[17, 16, 15, 14, 13, 12, 11]`.
   - Toàn bộ 24 bản ghi được duyệt trọn vẹn qua 4 trang với kích thước `[7, 7, 7, 3]`, đảm bảo **0 dòng bị trùng và 0 dòng bị sót**.

---

## 5. KP3 & KP4: Hiện Tượng Trôi Vị Trí (Drift) Dưới Giao Dịch Đồng Thời

Mô hình giao dịch mô phỏng các yêu cầu lật trang độc lập (stateless HTTP requests) trong ứng dụng web: mỗi truy vấn trang chạy trong một transaction tự động (per-statement autocommit) dưới mức cô lập mặc định `READ COMMITTED`. Ở chế độ này, mỗi câu lệnh SQL nhận một snapshot MVCC mới tại thời điểm câu lệnh bắt đầu thực thi. Khi có các giao dịch khác chèn hoặc xóa dữ liệu giữa các lần người dùng lật trang, hiện tượng trôi vị trí (drift) sẽ xảy ra.

### 5.1. KP3 — Chèn dữ liệu đồng thời (Concurrent INSERT)
- **Tình huống:** Bảng có 24 dòng, Page size = 5.
  1. Reader đọc Trang 1: Nhận IDs `[24, 23, 22, 21, 20]`. Con trỏ biên là ID 20.
  2. Writer chèn một bản ghi mới có ID 25 với `created_at = base + 3s` (mới nhất) và COMMIT.
  3. Reader yêu cầu nạp Trang 2.
- **Kết quả với OFFSET 5:**
  - Dữ liệu hiện tại sau khi chèn: `[25, 24, 23, 22, 21, 20, 19, 18, 17, 16, ...]`.
  - Mệnh đề `OFFSET 5` bỏ qua 5 dòng đầu `[25, 24, 23, 22, 21]`.
  - Trang 2 trả về: `[20, 19, 18, 17, 16]`.
  - **Lỗi lặp dòng (Duplication):** Bản ghi **ID 20** đã xuất hiện ở cuối Trang 1 nay lại bị lặp lại ở đầu Trang 2!
- **Kết quả với Keyset Cursor (`cursorId = 20`):**
  - Mệnh đề lọc tìm các dòng có vị trí nhỏ hơn con trỏ `(base + 1s, 20)`. Bản ghi mới ID 25 nằm ở vị trí trước con trỏ nên tự động bị bỏ qua.
  - Trang 2 trả về chính xác: `[19, 18, 17, 16, 15]`.
  - **Keyset hoàn toàn miễn nhiễm với lỗi lặp dòng do chèn dữ liệu mới ở đầu bảng.**

### 5.2. KP4 — Xóa dữ liệu đồng thời (Concurrent DELETE)
- **Tình huống:** Bảng có 24 dòng, Page size = 5.
  1. Reader đọc Trang 1: Nhận IDs `[24, 23, 22, 21, 20]`. Con trỏ biên là ID 20.
  2. Writer xóa bản ghi ID 24 (nằm trong Trang 1 mà reader đã đọc) và COMMIT.
  3. Reader yêu cầu nạp Trang 2.
- **Kết quả với OFFSET 5:**
  - Dữ liệu hiện tại sau khi xóa: `[23, 22, 21, 20, 19, 18, 17, 16, 15, ...]`.
  - Mệnh đề `OFFSET 5` bỏ qua 5 dòng đầu `[23, 22, 21, 20, 19]`.
  - Trang 2 trả về: `[18, 17, 16, 15, 14]`.
  - **Lỗi mất dòng (Skipped Row):** Bản ghi **ID 19** chưa từng được hiển thị cho người dùng nhưng đã bị trượt qua và biến mất khỏi kết quả!
- **Kết quả với Keyset Cursor (`cursorId = 20`):**
  - Mệnh đề lọc tìm các dòng nằm sau con trỏ `(base + 1s, 20)`. Việc bản ghi ID 24 bị xóa không làm thay đổi vị trí của con trỏ hay các bản ghi phía sau nó.
  - Trang 2 trả về chính xác: `[19, 18, 17, 16, 15]`.
  - **Bản ghi ID 19 vẫn được hiển thị nguyên vẹn, không bị mất.**

---

## 6. Giới Hạn Kỹ Thuật của Keyset Pagination

Mặc dù Keyset giải quyết triệt để vấn đề hiệu năng và trôi trang, kỹ sư cần hiểu rõ các giới hạn để không áp dụng một cách mù quáng:

1. **Không hỗ trợ nhảy trang tùy ý (No Random Page Jump):**
   Keyset không thể trả lời câu hỏi "cho tôi xem trang 42" nếu chưa tuần tự đọc qua các trang trước để lấy con trỏ biên. Để nhảy đến trang 42 mà không có con trỏ, hệ thống vẫn phải tính toán vị trí bằng OFFSET hoặc phải lưu trước vị trí bookmark của các trang.
2. **Không có thông tin tổng số trang (No Total Page Count):**
   Keyset chỉ biết có dòng kế tiếp hay không (bằng cách fetch `LIMIT + 1`). Nếu giao diện bắt buộc hiển thị thanh phân trang dạng `Trang 1 / 100`, hệ thống buộc phải chạy thêm câu truy vấn `COUNT(*)`, vốn là thao tác tốn kém trên bảng lớn.
3. **Không phải là Snapshot Pagination xuyên suốt nhiều HTTP Requests:**
   Keyset ổn định hơn OFFSET khi có insert/delete ở đầu danh sách, nhưng nếu có bản ghi bị xóa hoặc sửa đổi ngay trong phạm vi trang kế tiếp, hoặc có giao dịch chèn lùi thời gian (backdated insert) với timestamp nhỏ hơn con trỏ, kết quả nhận được vẫn phản ánh dữ liệu mới nhất tại thời điểm truy vấn.
4. **Độ phức tạp khi có nhiều tiêu chí sắp xếp hoặc cột NULL:**
   Khi mệnh đề `ORDER BY` có các cột sắp xếp theo chiều ngược nhau (ví dụ: `colA ASC, colB DESC`), phép so sánh tuple dạng đơn giản `(colA, colB) < (?, ?)` không còn áp dụng được trực tiếp mà phải tách thành các mệnh đề logic phức tạp. Ngoài ra, cột chứa giá trị `NULL` đòi hỏi xử lý đặc biệt vì quy tắc so sánh `NULL` trong SQL có thể gây ra kết quả không xác định.

---

## 7. Khi Nào OFFSET Vẫn Là Lựa Chọn Phù Hợp?

Keyset không phải là giải pháp thay thế hoàn toàn cho OFFSET. OFFSET vẫn là lựa chọn kỹ thuật tối ưu trong các trường hợp sau:
1. **Tập dữ liệu nhỏ hoặc vừa phải (Dưới vài chục nghìn dòng):** Chi phí quét của OFFSET là không đáng kể so với độ phức tạp bảo trì của Keyset.
2. **Trang nông (Shallow Pages):** Đa số người dùng chỉ xem từ trang 1 đến trang 5. Tại các trang nông, chi phí của OFFSET 0..100 và Keyset là hoàn toàn tương đương.
3. **Giao diện quản trị (Admin Dashboard):** Nơi người quản trị cần nhảy trực tiếp đến số trang cụ thể hoặc cần thanh điều hướng trang cố định.
4. **Dữ liệu tĩnh, ít biến động:** Nơi các nguy cơ trôi trang do ghi đồng thời là tối thiểu.

---

## 8. Mối Liên Hệ với Kiến Trúc Phân Trang Hai Bước (Prompt 15)

Trong Prompt 15 (M2-E4B), chúng ta đã chứng minh rằng việc kết hợp `JOIN FETCH` collection với phân trang trong Hibernate sẽ khiến cơ sở dữ liệu loại bỏ mệnh đề `LIMIT/OFFSET` và tải toàn bộ tập kết quả vào bộ nhớ ứng dụng. Để khắc phục, kiến trúc phân trang hai bước (Two-Step Fetch) đã được thiết lập:
- **Bước 1:** Thực hiện truy vấn phân trang trên thực thể gốc để lấy danh sách scalar IDs của trang.
- **Bước 2:** Sử dụng danh sách IDs đó để nạp các quan hệ collection bằng mệnh đề `WHERE parent_id IN (:ids)`.

**Sự kết hợp hoàn hảo:**
Keyset Pagination có thể được tích hợp trực tiếp vào **Bước 1** của kiến trúc Two-Step:
- Thay vì dùng `OFFSET ? LIMIT ?` ở Bước 1 (dễ bị chậm ở trang sâu), ta dùng Keyset Cursor để lấy danh sách IDs của trang hiện tại với chi phí tối ưu:
  ```sql
  SELECT id FROM buyer WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?
  ```
- Sau đó, Bước 2 tiếp tục nhận danh sách IDs này và thực hiện fetch collection qua `IN (:ids)`, bảo toàn thứ tự hiển thị và kiểm soát chặt chẽ dung lượng heap.
