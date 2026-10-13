# M2-E5A: Đọc EXPLAIN, Chọn Index và Hiểu Chi Phí Truy Vấn PostgreSQL

**Mã thí nghiệm:** M2-E5A
**Môi trường:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12
**Trạng thái kiểm thử:** 4/4 scenarios PASS, 97/97 reactor tests PASS
**Bằng chứng kiểm chứng:** `docs/evidence/prompt16/query-plan-observations.json` (Run ID: `prompt16-20261013-171826-1927c1`)

---

## 1. Tương Quan Thực Tế: Ẩn Dụ Tra Cứu Sổ Giao Dịch

Để hiểu trực quan cách cơ sở dữ liệu định tuyến truy vấn, hãy tưởng tượng một cuốn sổ cái kế toán dày **100.000 trang** ghi chép các giao dịch tài chính:

1. **Không có mục lục (Seq Scan - Kịch bản EP1):**
   Khi cần tìm *"5 giao dịch PENDING mới nhất của người mua số 42"*, thủ thư không có bất kỳ bảng tra cứu nào. Họ buộc phải lật tuần tự từ trang 1 đến trang 100.000 (đọc toàn bộ các block dữ liệu), nhặt ra từng giao dịch của người mua 42 ở trạng thái PENDING, ghi vào một danh sách tạm, sau đó sắp xếp theo thời gian giảm dần rồi lấy 5 dòng trên cùng.
2. **Có mục lục theo Người Mua (Single-Column Index - Kịch bản EP2):**
   Cuốn sổ có trang mục lục phụ ở cuối ghi rõ: *"Người mua 42 nằm ở các trang X, Y, Z..."*. Thủ thư tra nhanh mục lục và chỉ cần lật đúng 100 trang của người mua 42 (Bitmap Index Scan + Bitmap Heap Scan). Tuy nhiên, vì mục lục chỉ sắp xếp theo ID người mua chứ không sắp xếp theo ngày giờ, thủ thư vẫn phải gom 10 giao dịch PENDING của người mua 42 lại và thực hiện bước sắp xếp riêng (Sort node) trước khi chọn ra 5 giao dịch.
3. **Có mục lục kép kết hợp Lọc và Thứ Tự (Composite Index - Kịch bản EP3):**
   Mục lục được đánh chỉ mục theo cụm: `(Người Mua, Trạng Thái, Ngày Tạo GIẢM DẦN)`. Thủ thư mở thẳng mục lục tới vị trí `(Người Mua 42, PENDING)`, và đọc ngay 5 dòng đầu tiên vì chúng đã được sắp sẵn theo thứ tự thời gian mới nhất. Họ không cần gom toàn bộ, không cần sắp xếp lại, và dừng đọc ngay khi đủ 5 dòng (Early exit at Limit).
4. **Mục lục khi tra cứu 90% cuốn sổ (Low Selectivity - Kịch bản EP4):**
   Khi có yêu cầu: *"Tìm toàn bộ giao dịch COMPLETED"*, mà trong sổ có tới 90.000 giao dịch COMPLETED trên tổng số 100.000 dòng. Nếu dùng mục lục theo trạng thái, thủ thư phải tra mục lục rồi thực hiện hàng chục nghìn lần tra cứu ngẫu nhiên (Random I/O) vào các trang dữ liệu (nhiều bản ghi có thể nằm chung trang heap). Thay vào đó, lật thẳng liên tục từ trang đầu đến trang cuối (Sequential Scan) giảm bớt chi phí tra cứu ngẫu nhiên và tận dụng tốt bộ đệm.

---

## 2. Cấu Trúc Bảng, Dữ Liệu Tái Lập và Tập Chỉ Mục

### 2.1. Lược đồ cơ sở dữ liệu (Probe Schema)

```sql
CREATE TABLE query_plan_trade (
    id BIGINT PRIMARY KEY,
    buyer_id INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL,
    payload TEXT NOT NULL
);
```

*Cột `payload` chứa chuỗi hash dài 128 ký tự (`repeat(md5(g::text), 4)`) nhằm phản ánh kích thước hàng thực tế (row width ~176 bytes), ngăn chặn việc engine bỏ qua I/O bảng.*

### 2.2. Dữ liệu mồi xác định (100.000 dòng)

Dữ liệu được tạo lập một lần duy nhất qua câu lệnh SQL mồi:

```sql
INSERT INTO query_plan_trade (id, buyer_id, status, created_at, payload)
SELECT
    g AS id,
    ((g - 1) % 1000) + 1 AS buyer_id,
    CASE WHEN ((g - 1) / 1000) % 10 = 0 THEN 'PENDING' ELSE 'COMPLETED' END AS status,
    TIMESTAMPTZ '2026-01-01 00:00:00+00' + (g * INTERVAL '1 second') AS created_at,
    repeat(md5(g::text), 4) AS payload
FROM generate_series(1, 100000) g;
```

**Bất biến kiểm chứng fixture trước khi đo:**
- Tổng số bản ghi: **100.000 dòng**.
- Số lượng người mua phân biệt: **1.000 buyers** (mỗi buyer có đúng 100 dòng).
- Phân bố trạng thái: **10.000 dòng PENDING** (10%), **90.000 dòng COMPLETED** (90%).
- Người mua số 42: có chính xác **100 dòng**, trong đó có đúng **10 dòng PENDING**.
- Câu lệnh `ANALYZE query_plan_trade;` được thực thi để cập nhật chính xác bảng thống kê `pg_statistic`.

### 2.3. Các câu truy vấn chuẩn đo

* **Truy vấn Q1 (Sàng lọc hẹp, sắp xếp & giới hạn):**
  ```sql
  SELECT id, buyer_id, status, created_at, payload
  FROM query_plan_trade
  WHERE buyer_id = ? AND status = ?
  ORDER BY created_at DESC, id DESC
  LIMIT ?;
  ```
  Tham số: `buyer_id = 42`, `status = 'PENDING'`, `limit = 5`.
  Tập ID kết quả kỳ vọng: `[90042, 80042, 70042, 60042, 50042]`.

* **Truy vấn Q2 (Sàng lọc rộng, độ chọn lọc thấp):**
  ```sql
  SELECT id, buyer_id, status, created_at, payload
  FROM query_plan_trade
  WHERE status = ?;
  ```
  Tham số: `status = 'COMPLETED'`.
  Số lượng kết quả kỳ vọng: **90.000 dòng**.

---

## 3. Hướng Dẫn Thực Thi Thí Nghiệm

```powershell
# 1. Chạy unit tests cho bộ thu thập bằng chứng
python -m unittest discover -s scripts/tests -p "test_*.py"

# 2. Chạy targeted test suite cho Query Plan và Pagination
mvn -pl backend-app -am "-Dtest=PostgresQueryPlanExperimentsTest,JpaCollectionPaginationExperimentsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

# 3. Thu thập bằng chứng tự động toàn bộ reactor
python scripts/collect_lab_evidence.py --run --prompt prompt16
```

---

## 4. Bảng Kết Quả Đo Lường Thực Tế (EP1..EP4)

Dữ liệu được trích xuất trực tiếp từ [query-plan-observations.json](file:///D:/Project%20CV/INTERVIEW/aethercore/docs/evidence/prompt16/query-plan-observations.json) (`prompt16-20261013-171826-1927c1`):

| Ca | Chỉ mục thứ cấp tồn tại | Query | Cây Node quan sát được (Node Path) | Có Sort node? | Số dòng trả về (Actual Rows) | Buffer Hit (Blocks) | Chi phí ước lượng (Total Cost) | Thời gian chạy thực (Execution Time) | Trạng thái giả thuyết |
| :--- | :--- | :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **EP1** | Không có (chỉ PK) | Q1 | `Limit -> Sort -> Seq Scan` | **Có** (`Sort`) | 5 | 2.440 | 3.940,18 | 7,247 ms | **MATCHED** |
| **EP2** | `idx_qpt_buyer` | Q1 | `Limit -> Sort -> Bitmap Heap Scan -> Bitmap Index Scan` | **Có** (`Sort`) | 5 | 102 | 339,80 | 0,183 ms | **MATCHED** |
| **EP3** | `idx_qpt_buyer_status_recent` | Q1 | `Limit -> Index Scan` | **KHÔNG** | 5 | 8 | 22,51 | 0,043 ms | **MATCHED** |
| **EP4** | `idx_qpt_status` | Q2 | `Seq Scan` | **KHÔNG** | 90.000 | 2.440 | 3.690,00 | 23,862 ms | **MATCHED** |

---

## 5. Phân Tích Chi Tiết Từng Kịch Bản và Đoạn EXPLAIN JSON Thật

### 5.1. EP1: Quét tuần tự và Sắp xếp trong bộ nhớ (Seq Scan + Top-N Sort)
* **Kế hoạch thực thi:** `Seq Scan` phải duyệt qua toàn bộ **2.440 blocks** của bảng `query_plan_trade`. Bộ lọc `Filter: ((buyer_id = 42) AND ((status)::text = 'PENDING'::text))` loại bỏ 99.990 dòng và giữ lại đúng 10 dòng khớp.
* Node `Sort` nhận 10 dòng này, sử dụng thuật toán `quicksort` (như quan sát trong raw plan JSON thực tế: `Sort Method: quicksort`) sắp xếp theo `created_at DESC, id DESC`. Sự xuất hiện của mệnh đề `LIMIT` không đồng nghĩa optimizer chắc chắn luôn chọn `top-N heapsort`.
* Node `Limit` lấy đúng 5 dòng trên cùng.
* **Chi phí:** Tổng cost lên tới **3.940,18**, tiêu tốn thời gian lớn nhất trong 3 kịch bản của Q1 (~7,2 ms).

### 5.2. EP2: Chỉ mục đơn `buyer_id` thu hẹp phạm vi nhưng không cung cấp thứ tự
* **Kế hoạch thực thi:** Thay vì Index Scan trực tiếp, PostgreSQL chọn **Bitmap Index Scan** trên chỉ mục `idx_qpt_buyer` (cost 5,04, 2 blocks) để xây dựng mảng bitmap gồm 100 con trỏ vật lý cho `buyer_id = 42`.
* **Bitmap Heap Scan** nạp các trang chứa 100 bản ghi đó (chỉ tiêu tốn **102 blocks** thay vì 2.440 blocks), sau đó áp dụng bộ lọc `Filter: (status = 'PENDING')` để chọn ra 10 dòng.
* **Tại sao vẫn cần Sort?** Chỉ mục `idx_qpt_buyer` chỉ sắp xếp theo `buyer_id`. Các bản ghi của cùng một người mua không được sắp xếp theo thời gian `created_at`, do đó planner bắt buộc phải chèn node `Sort` (`Sort Key: ['created_at DESC', 'id DESC']`) trước node `Limit`.

### 5.3. EP3: Chỉ mục ghép `(buyer_id, status, created_at DESC, id DESC)` tối ưu toàn diện
* **Kế hoạch thực thi:** Planner sử dụng trực tiếp **Index Scan** trên `idx_qpt_buyer_status_recent`.
* Vì chỉ mục đã sắp xếp sẵn theo đúng điều kiện lọc đẳng thức (`buyer_id = 42 AND status = 'PENDING'`) và chiều sắp xếp (`created_at DESC, id DESC`), engine chỉ cần duyệt xuôi theo cây B-Tree.
* **Triệt tiêu hoàn toàn Sort:** Kế hoạch **không có node Sort**. Node `Limit` kiểm soát việc dừng quét ngay khi trích xuất đủ 5 bản ghi đầu tiên (`loops=1, actualRows=5`).
* **Hiệu quả:** Chỉ cần đọc đúng **8 blocks** bộ đệm (so với 102 blocks ở EP2 và 2.440 blocks ở EP1), chi phí giảm xuống chỉ còn **22,51**, thời gian thực thi chỉ mất **0,043 ms**.

### 5.4. EP4: Tại sao có chỉ mục `status` mà PostgreSQL vẫn chọn `Seq Scan`?
* **Quan sát thực tế:** Chỉ mục `idx_qpt_status` tồn tại và hợp lệ, nhưng khi chạy truy vấn Q2 (`WHERE status = 'COMPLETED'`), PostgreSQL quyết định chọn **Seq Scan** với chi phí **3.690,00**, hoàn toàn không đụng đến chỉ mục.
* **Nguyên nhân toán học của Cost Model:**
  - 90.000 trên 100.000 dòng thỏa mãn điều kiện lọc (độ chọn lọc 90%).
  - Truy vấn yêu cầu lấy `payload` (cột dữ liệu rộng, nằm trên Heap table).
  - Dựa trên mô hình chi phí (Cost Model): Nếu đi qua chỉ mục, PostgreSQL ước lượng chi phí cho việc quét index pages cộng với hàng chục nghìn lần truy cập ngẫu nhiên (random page lookups) vào bảng dữ liệu với `random_page_cost = 4.0`. Planner tính toán thấy chi phí ước lượng này cao hơn chi phí quét tuần tự toàn bộ 2.440 blocks liên tiếp (`seq_page_cost = 1.0`), do đó đã lựa chọn `Seq Scan`. Đây là quyết định của optimizer dựa trên thống kê và cost model tại cấu hình cụ thể đó (chưa đo đạc thực nghiệm phương án ép index).

---

## 6. Minh Họa Cách Đọc Một Đoạn EXPLAIN JSON Thật

Dưới đây là đoạn JSON trích xuất từ kịch bản **EP3** minh họa cách đọc từ nút gốc (Root) xuống nút lá (Leaf Scan):

```json
[
  {
    "Plan": {
      "Node Type": "Limit",
      "Startup Cost": 0.29,
      "Total Cost": 22.51,
      "Plan Rows": 5,
      "Plan Width": 176,
      "Actual Rows": 5,
      "Actual Loops": 1,
      "Shared Hit Blocks": 8,
      "Shared Read Blocks": 0,
      "Plans": [
        {
          "Node Type": "Index Scan",
          "Parent Relationship": "Outer",
          "Scan Direction": "Forward",
          "Index Name": "idx_qpt_buyer_status_recent",
          "Relation Name": "query_plan_trade",
          "Startup Cost": 0.29,
          "Total Cost": 44.61,
          "Plan Rows": 10,
          "Plan Width": 176,
          "Actual Rows": 5,
          "Actual Loops": 1,
          "Index Cond": "((buyer_id = 42) AND ((status)::text = 'PENDING'::text))",
          "Rows Removed by Index Recheck": 0,
          "Shared Hit Blocks": 8,
          "Shared Read Blocks": 0
        }
      ]
    },
    "Planning Time": 0.063,
    "Execution Time": 0.043
  }
]
```

### Nguyên tắc đọc plan chuẩn:
1. **Đọc từ trong ra ngoài, từ dưới lên trên:** Nút con `Index Scan` khởi chạy trước, tìm kiếm các mục index thỏa mãn `Index Cond`. Nút cha `Limit` nhận các dòng từ nút con và cắt ngắn khi đạt đủ 5 dòng.
2. **`Actual Rows` ở nút con bị giới hạn bởi nút cha:** Chú ý rằng `Index Scan` ước lượng có 10 dòng (`Plan Rows = 10`), nhưng thực tế chỉ quét 5 dòng (`Actual Rows = 5`) vì nút cha `Limit` đã ngắt mạch thực thi ngay khi nhận đủ dữ liệu.
3. **Buffers là thuộc tính tích lũy (Cumulative Buffers):** Nút `Limit` ghi nhận `Shared Hit Blocks: 8`, con số này bao gồm chính 8 blocks mà `Index Scan` đã nạp, không được cộng dồn 8 + 8 = 16 blocks vì nút cha bao hàm các blocks của nút con.
4. **Phân biệt Buffer Hits vs Physical Disk Reads:**
   - `Shared Hit Blocks` là số lần truy cập tìm thấy block trong vùng đệm chia sẻ (Shared Buffer Cache) của PostgreSQL, không đồng nghĩa với số lần đọc đĩa vật lý hay số trang vật lý duy nhất.
   - `Shared Read Blocks` (nếu có) cũng không tự chứng minh số lần đọc đĩa vật lý trực tiếp từ thiết bị lưu trữ, do hệ điều hành còn có OS Page Cache.
5. **Ý nghĩa của `Plan Width`:* `Plan Width` (ví dụ 176 bytes) là ước lượng của optimizer về độ rộng trung bình của các cột ở đầu ra của node, không phải kích thước lưu trữ vật lý trên đĩa của hàng dữ liệu.
6. **Vị trí chỉ mục trong EP2:** Chỉ mục `idx_qpt_buyer` được định tuyến tại nút con `Bitmap Index Scan`. Nút cha `Bitmap Heap Scan` nạp các block dữ liệu từ bảng heap dựa trên bitmap, do đó trường index name ở nút scan đầu tiên có thể là null nhưng chỉ mục vẫn được sử dụng đầy đủ.

---

## 7. Giới Hạn Của Thí Nghiệm & Những Điều Chưa Kiểm Chứng

Dù kết quả 4 kịch bản rất rõ ràng, cần nhấn mạnh các giới hạn phạm vi để tránh ngộ nhận:
1. **Môi trường đo lường đã cấu hình đơn giản hóa:** Để đảm bảo tính tái lập và dễ giải thích, connection thí nghiệm đã tắt xử lý song song (`max_parallel_workers_per_gather = 0`) và tắt JIT compilation (`jit = off`). Trong môi trường Production với bảng nhiều triệu dòng, optimizer có thể chọn Parallel Seq Scan hoặc Parallel Bitmap Scan.
2. **Trạng thái Cache (Warm-ish Cache):** Thí nghiệm chạy sau bước seed dữ liệu và warm-up nhẹ. `Shared Read Blocks = 0` và `Shared Hit Blocks > 0` cho thấy dữ liệu đã nằm trong bộ đệm RAM của PostgreSQL. Thí nghiệm không đo lường tình trạng Cold Cache (đọc trực tiếp từ đĩa vật lý NVMe/SSD).
3. **Chi phí ghi chỉ mục (Index Write Overhead):** Mỗi chỉ mục tạo thêm làm tăng tốc độ đọc cho query cụ thể, nhưng sẽ làm chậm các thao tác `INSERT`, `UPDATE`, `DELETE` và tiêu tốn dung lượng ổ đĩa (WAL bloat, B-tree rebalancing). Phiên này chưa thực hiện đo lường tải ghi (write benchmark).
4. **Không suy diễn tốc độ API từ một lần đo EXPLAIN:** Thời gian thực thi `Execution Time: 0.043 ms` là thời gian CPU/Engine tại database server cho một câu lệnh nội bộ, chưa bao gồm Network Latency round-trip, thời gian serialize JDBC, và chi phí xử lý tầng ứng dụng.

---

## 8. Kết Nối Với Phỏng Vấn & Hồ Sơ Kỹ Năng (CV)

Khi trình bày với nhà tuyển dụng, ứng viên có thể tự tin khẳng định:
- *"Em đã tự tay thiết lập môi trường kiểm thử thực tế trên PostgreSQL 16 Testcontainers, sử dụng EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) để đo lường chính xác tác động của chỉ mục đơn so với chỉ mục ghép."*
- *"Em hiểu rõ rằng index ghép `(buyer_id, status, created_at DESC, id DESC)` không chỉ lọc dữ liệu mà còn loại bỏ hoàn toàn chi phí Sort trong bộ nhớ nhờ bảo toàn thứ tự sắp xếp của B-tree."*
- *"Em hiểu cơ chế chi phí của optimizer: khi một truy vấn lọc 90% bảng và cần dữ liệu từ heap, PostgreSQL sẽ cố tình chọn Seq Scan thay vì Index Scan để tránh chi phí Random I/O đắt đỏ."*
- **Tuyệt đối không:** Bịa đặt các con số *"giúp hệ thống tăng tốc 500%"* hay tuyên bố *"luôn luôn đánh index cho mọi cột được ORDER BY"*.
