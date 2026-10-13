# M2-E5C: Phân Tích Kỹ Thuật LIKE, lower() và Expression Index trong PostgreSQL

**Mã thí nghiệm:** M2-E5C
**Môi trường:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12
**Trạng thái kiểm thử:** 4/4 scenarios PASS, 105/105 reactor tests PASS (Core: 34, Backend: 71)
**Bằng chứng kiểm chứng:** `docs/evidence/prompt18/predicate-index-observations.json` (Run ID: `prompt18-20261013-202011-995f61`)

---

## 1. Trực Quan Hóa: Ví Dụ Danh Bạ Điện Thoại & Khoảng Tìm Kiếm

Để hiểu bản chất vì sao các biểu thức vị từ (predicates) khác nhau lại dẫn đến các kế hoạch thực thi (execution plans) hoàn toàn khác nhau trên cùng một cấu trúc chỉ mục B-tree, hãy xem xét ví dụ trực quan về một cuốn **danh bạ điện thoại** được sắp xếp theo bảng chữ cái:

1. **Tìm kiếm tiền tố (Prefix Search - tương tự `LIKE 'Redstone-%'`):**
   - Người tra cứu cần tìm tất cả các mục có tên bắt đầu bằng `"Redstone-"`.
   - Vì danh bạ đã được sắp xếp tăng dần theo thứ tự từ điển, người tra cứu chỉ cần lật ngay đến trang đầu tiên có từ khóa `"Redstone-"` (Index Seek), sau đó đọc liên tục các dòng kế tiếp cho đến khi gặp từ khóa vượt quá phạm vi (ví dụ `"Redstone."` hoặc `"Redstone0"`).
   - Quá trình này xác định được một **khoảng tìm kiếm hẹp (bounded range)**: `[Redstone-, Redstone.)`. Không cần duyệt qua phần còn lại của cuốn danh bạ.

2. **Tìm kiếm chứa chuỗi (Contains Search - tương tự `LIKE '%Redstone%'`):**
   - Người tra cứu cần tìm tất cả các mục có chứa chữ `"Redstone"` ở bất kỳ vị trí nào (đầu, giữa hoặc cuối).
   - Từ khóa `"Redstone"` có thể nằm sau bất kỳ họ nào (ví dụ: `"Alpha-Redstone"`, `"Beta-Redstone"`, `"Omega-Redstone"`).
   - Vì cuốn danh bạ chỉ được sắp xếp theo ký tự đầu tiên của dòng, việc sắp xếp này hoàn toàn vô dụng đối với ký tự nằm ở giữa. Người tra cứu không có cách nào khác ngoài việc **lật từng trang từ đầu đến cuối cuốn danh bạ (Full Scan)** để đọc và kiểm tra từng dòng.

> [!NOTE]
> **Giới hạn mô hình hóa:** Ví dụ danh bạ điện thoại là công cụ trực quan để hình dung về *phạm vi tìm kiếm (search boundary)* trên cây B-tree. Ví dụ này không thay thế các cơ chế vật lý phức tạp của PostgreSQL engine như: phân trang heap (heap pages), bộ đệm bộ nhớ (shared buffers), cấu trúc trang lá B-tree (leaf pages), và các cờ trạng thái dòng MVCC (tuple headers).

---

## 2. Bốn Khế Ước Truy Vấn (Query Contracts) & Oracles

Thí nghiệm được thực hiện trên bảng `predicate_trade` với collation tường minh `"C"`:

```sql
CREATE TABLE predicate_trade (
    id           BIGINT PRIMARY KEY,
    display_name TEXT COLLATE "C" NOT NULL,
    payload      TEXT NOT NULL
);
```

Dataset chuẩn hóa gồm **100.001 dòng** dữ liệu xác định:
- Dòng 1 đến 100: Tiền tố `Redstone-000001` đến `Redstone-000100` (khớp cả prefix và contains).
- Dòng 101 đến 200: Chứa chuỗi `Mid-Redstone-000101` đến `Mid-Redstone-000200` (chỉ khớp contains).
- Dòng 50.000: `Redstone-050000` (chữ hoa/thường chuẩn).
- Dòng 100.001: `rEdStOnE-050000` (mixed case: xen kẽ hoa thường).
- Các dòng còn lại: `Trade-000201` đến `Trade-100000`.

### Khế ước và Oracle kết quả:

| Case ID | Khế ước truy vấn (SQL Predicate) | Oracle Expected IDs | Mục tiêu kỹ thuật |
|---|---|---|---|
| **IP1** | `WHERE display_name LIKE 'Redstone-%'` | 100 IDs `[1..100]` | So sánh đường truy cập trước và sau khi có B-tree index `idx_pt_name`. |
| **IP2** | `WHERE display_name LIKE '%Redstone%'` | 200 IDs `[1..200]` | Kiểm chứng chỉ mục B-tree thông thường không thể tạo seek range cho wildcard ở đầu. |
| **IP3** | `WHERE lower(display_name) = 'redstone-050000'` | 2 IDs `[50000, 100001]` | Chứng minh hàm bọc trên cột làm vô hiệu hóa index giá trị gốc `idx_pt_name`. |
| **IP4** | `WHERE lower(display_name) = 'redstone-050000'` | 2 IDs `[50000, 100001]` | Chứng minh Expression Index `idx_pt_lower_name` phục hồi index lookup chính xác. |

> [!IMPORTANT]
> **Ngữ nghĩa nghiệp vụ không tương đương:** IP1 trả về 100 dòng, trong khi IP2 trả về 200 dòng. Đây là hai yêu cầu nghiệp vụ hoàn toàn khác nhau. Tuyệt đối không được "tối ưu hóa" truy vấn tìm kiếm chứa chuỗi (contains) bằng cách tự ý đổi sang tìm tiền tố (prefix) chỉ để ép database sử dụng index, vì hành vi đó sẽ làm mất 50% dữ liệu hợp lệ của người dùng.

---

## 3. Bảng Thực Nghiệm So Sánh Đo Đạc Thực Tế (IP1..IP4)

Số liệu đo đạc trực tiếp từ suite `PostgresPredicateIndexExperimentsTest` dưới Testcontainers PostgreSQL 16.15:

| Case | Phép đo (Measurement) | Index Inventory | Index Được Dùng | Node Type | Index Cond / Filter | Actual Rows | Actual Loops | Shared Hits | Exec Time (ms) | Trạng Thái Giả Thuyết |
|---|---|---|---|---|---|---|---|---|---|---|
| **IP1** | `prefix_no_index` | PK only | *(None)* | **Seq Scan** | Filter: `(display_name ~~ 'Redstone-%')` | 100 | 1 | 2.326 | 6,480 ms | **MATCHED** |
| **IP1** | `prefix_btree` | PK + `idx_pt_name` | `idx_pt_name` | **Index Scan** | Index Cond: `((display_name >= 'Redstone-') AND (display_name < 'Redstone.'))` | 100 | 1 | 104 | 0,113 ms | **MATCHED** |
| **IP2** | `contains_like` | PK + `idx_pt_name` | *(None)* | **Seq Scan** | Filter: `(display_name ~~ '%Redstone%')` | 200 | 1 | 2.326 | 7,358 ms | **MATCHED** |
| **IP3** | `lower_ordinary_index` | PK + `idx_pt_name` | *(None)* | **Seq Scan** | Filter: `(lower(display_name) = 'redstone-050000')` | 2 | 1 | 2.326 | 12,926 ms | **MATCHED** |
| **IP4** | `lower_expression_index` | PK + `idx_pt_name` + `idx_pt_lower_name` | `idx_pt_lower_name` | **Index Scan** | Index Cond: `(lower(display_name) = 'redstone-050000')` | 2 | 1 | 5 | 0,021 ms | **MATCHED** |

### Nhận xét định lượng cốt lõi:
1. **IP1 (Prefix):** Khi có chỉ mục B-tree `idx_pt_name`, số lượng buffer hits giảm từ **2.326 xuống 104 blocks** (giảm 22,4 lần), thời gian thực thi giảm từ **6,480 ms xuống 0,113 ms** (tăng tốc ~57 lần).
2. **IP2 (Contains):** Dù `idx_pt_name` tồn tại trong schema, planner vẫn buộc phải chọn `Seq Scan` vì wildcard đứng đầu `%Redstone%` không có điểm bắt đầu để định vị trên cây B-tree.
3. **IP3 (Function vs Index):** Chỉ mục `idx_pt_name` hoàn toàn bị bỏ qua khi cột bị bọc bởi hàm `lower()`. Node `Seq Scan` phải đọc toàn bộ 100.001 dòng và tính toán hàm `lower()` trên từng dòng, mất **12,926 ms**.
4. **IP4 (Expression Index):** Khi tạo chỉ mục chuyên biệt `idx_pt_lower_name(lower(display_name))`, query phục hồi đường truy cập `Index Scan`. Buffer hits giảm ngoạn mục từ **2.326 xuống chỉ còn 5 blocks** (giảm 465 lần), thời gian thực thi giảm xuống **0,021 ms** (tăng tốc ~615 lần).

---

## 4. Cây Kế Hoạch Thực Thi Gốc (Raw Execution Trees)

Dưới đây là hai cây kế hoạch thực thi trích xuất nguyên bản từ cấu trúc JSON của `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON)`:

### 4.1. Kế hoạch Prefix LIKE với B-tree Index (`prefix_btree` trong IP1)

```
Index Scan using idx_pt_name on predicate_trade  (cost=0.42..8.44 rows=100 width=74)
                                                  (actual rows=100 loops=1)
  Index Cond: ((display_name >= 'Redstone-'::text) AND (display_name < 'Redstone.'::text))
  Filter: (display_name ~~ 'Redstone-%'::text)
  Buffers: shared hit=104
Planning:
  Buffers: shared hit=8
Planning Time: 0.083 ms
Execution Time: 0.113 ms
```

**Phân tích kỹ thuật:**
- PostgreSQL query rewrite engine đã nhận diện mẫu `LIKE 'Redstone-%'` dưới Collation `"C"` và tự động chuyển đổi thành hai điều kiện biên của khoảng giá trị:
  - Cận dưới: `display_name >= 'Redstone-'`
  - Cận trên: `display_name < 'Redstone.'` (với ký tự `.` là ký tự liền kề sau `-` trong bảng mã ASCII: ASCII 45 `-` -> ASCII 46 `.`).
- Điều kiện `Filter: (display_name ~~ 'Redstone-%')` đóng vai trò là re-check filter để đảm bảo loại bỏ các trường hợp cạnh nếu có.

### 4.2. Kế hoạch Expression Index (`lower_expression_index` trong IP4)

```
Index Scan using idx_pt_lower_name on predicate_trade  (cost=0.42..8.44 rows=2 width=74)
                                                        (actual rows=2 loops=1)
  Index Cond: (lower(display_name) = 'redstone-050000'::text)
  Buffers: shared hit=5
Planning:
  Buffers: shared hit=7
Planning Time: 0.026 ms
Execution Time: 0.021 ms
```

**Phân tích kỹ thuật:**
- Biểu thức `lower(display_name)` trong mệnh đề `WHERE` khớp hoàn toàn với biểu thức đã lập chỉ mục trên `idx_pt_lower_name`.
- Planner trực tiếp biến đổi thành phép tra cứu đẳng thức (equality seek) trên cây B-tree với `Index Cond: (lower(display_name) = 'redstone-050000')`.
- Không cần bất kỳ bộ lọc lọc lại (Filter) nào ở cấp bảng, chỉ đọc đúng 5 buffer blocks (các khối B-tree root, branch, leaf và heap pages chứa 2 dòng kết quả).

---

## 5. Phân Tích Chuyên Sâu: Function trên Cột & Expression Index

### 5.1. Vì sao Index trên giá trị gốc không hỗ trợ hàm?
Một B-tree index thông thường trên cột `display_name` lưu trữ các giá trị nguyên bản của cột đó. Các khóa trong cây được sắp xếp theo thứ tự so sánh của collation (ví dụ: chuỗi `"Redstone-050000"` và `"rEdStOnE-050000"` nằm ở hai vị trí hoàn toàn khác nhau trên cây index).

Khi câu truy vấn yêu cầu:
```sql
WHERE lower(display_name) = 'redstone-050000'
```
Để biết giá trị sau khi qua hàm `lower()` có bằng `'redstone-050000'` hay không, database không thể tra cứu trên cây index của `display_name` gốc vì cây đó không chứa giá trị chữ thường. Cách duy nhất là duyệt qua từng dòng dữ liệu và tính toán hàm `lower()`.

### 5.2. Phản biện cạm bẫy: Có nên "bỏ lower() để ép dùng index"?
Nhiều lập trình viên khi thấy câu truy vấn chạy chậm vì `Seq Scan` thường có xu hướng bỏ hàm `lower()`, chuyển câu truy vấn thành:
```sql
WHERE display_name = 'redstone-050000'
```
**Kiểm chứng thực nghiệm (Semantics Control Test):**
- Khi chạy câu truy vấn trên với tham số `'redstone-050000'`, số lượng dòng trả về là **0 dòng**!
- Lý do: Bản ghi ID 50000 có tên là `Redstone-050000` (chữ R viết hoa) và bản ghi ID 100001 có tên là `rEdStOnE-050000` (mixed case). Cả hai đều không khớp với chuỗi chữ thường hoàn toàn `'redstone-050000'`.
- **Kết luận:** Việc bỏ hàm `lower()` để ép database dùng index thông thường đã làm sai lệch hoàn toàn nghiệp vụ tìm kiếm không phân biệt hoa thường (case-insensitive search), biến kết quả từ đúng (2 dòng) thành rỗng (0 dòng).

### 5.3. Cơ chế Expression Index (Functional Index)
PostgreSQL cung cấp tính năng chỉ mục trên biểu thức:
```sql
CREATE INDEX idx_pt_lower_name ON predicate_trade(lower(display_name));
```
- Khi câu lệnh này được thực thi, PostgreSQL tính toán giá trị `lower(display_name)` cho toàn bộ các dòng hiện có và đưa các giá trị chữ thường này vào cấu trúc B-tree.
- Khi có câu truy vấn chứa đúng biểu thức `lower(display_name) = ?`, bộ tối ưu hóa nhận diện sự tương thích giữa biểu thức trong truy vấn và biểu thức của index, lập tức kích hoạt `Index Scan` mà không cần sửa đổi câu truy vấn nghiệp vụ.

---

## 6. Collation `"C"` và Giới Hạn Operator Class (`text_pattern_ops`)

### 6.1. Vai trò của Collation `"C"` trong thí nghiệm
Trong thí nghiệm này, cột `display_name` được định nghĩa rõ ràng với `COLLATE "C"`:
```sql
display_name TEXT COLLATE "C" NOT NULL
```
Collation `"C"` (hoặc `POSIX`) sử dụng thứ tự sắp xếp dựa trên mã byte nhị phân (byte-by-byte ASCII comparison). Dưới quy tắc này:
- Thứ tự sắp xếp của chuỗi trùng khớp hoàn toàn với thứ tự mã nhị phân.
- Nhờ đó, PostgreSQL có thể an toàn biến đổi `LIKE 'prefix%'` thành phép so sánh khoảng B-tree `(col >= 'prefix' AND col < 'prefix_next')`.

### 6.2. Cạm bẫy với Locale tự nhiên (ví dụ `en_US.UTF-8` hoặc `vi_VN.UTF-8`)
Nếu bảng dữ liệu sử dụng collation ngôn ngữ tự nhiên (linguistic collation):
- Các quy tắc sắp xếp từ điển (như bỏ qua dấu câu, không phân biệt dấu, quy tắc sắp xếp đặc thù của ngôn ngữ) khiến cho B-tree index mặc định **không thể** đảm bảo thứ tự byte nhị phân.
- Do đó, PostgreSQL sẽ **từ chối sử dụng B-tree index mặc định cho toán tử LIKE**, ngay cả khi đó là tiền tố (`LIKE 'prefix%'`)!

**Giải pháp trong thực tế (Production Pattern):**
Nếu muốn dùng B-tree index cho tìm kiếm prefix LIKE trên các cột có collation ngôn ngữ tự nhiên, bắt buộc phải khai báo toán tử mẫu (operator class):
```sql
CREATE INDEX idx_trade_name_pattern ON predicate_trade(display_name text_pattern_ops);
```
Chỉ mục với `text_pattern_ops` sẽ sắp xếp các khóa theo mã byte độc lập với collation của cột, cho phép tìm kiếm tiền tố LIKE hoạt động hiệu quả.

> [!WARNING]
> **Giới hạn kết luận:** Thí nghiệm M2-E5C được xác nhận kiểm chứng trên Collation `"C"`. Chúng ta chưa thực nghiệm trên các locale khác hoặc đo đạc `text_pattern_ops`, do đó trạng thái chỉ được ghi nhận là PARTIAL cho chủ đề này. Tuyệt đối không tự ý đổi collation toàn bộ database sang `"C"` chỉ để bắt chước kết quả lab!

---

## 7. Phương Pháp Luận Đọc Công Việc Của Scan Node

Khi phân tích kế hoạch thực thi EXPLAIN ANALYZE, cần tuân thủ các nguyên tắc sau:

1. **Số dòng bị loại bỏ (Rows Removed by Filter):**
   - Một node trả về 2 dòng không đồng nghĩa node đó chỉ làm việc trên 2 dòng. Trong IP3, node `Seq Scan` trả về `actual rows=2`, nhưng thực tế nó đã quét qua toàn bộ 100.001 dòng và loại bỏ 99.999 dòng (`Rows Removed by Filter: 99999`).
2. **Sự tồn tại của Index vs Việc sử dụng Index:**
   - Việc index tồn tại trong cơ sở dữ liệu (`idx_pt_name`) không đảm bảo optimizer sẽ sử dụng nó (minh chứng: IP2 và IP3 đều bỏ qua index).
3. **Chi phí (Cost) vs Thời gian thực (Time):**
   - Giá trị `cost=0.42..8.44` là điểm số ước lượng nội bộ của bộ lập kế hoạch (dựa trên các tham số `seq_page_cost`, `random_page_cost`, `cpu_tuple_cost`), không phải số mili-giây.
4. **Chế độ `TIMING OFF`:**
   - Khi đo đạc với `TIMING OFF`, PostgreSQL tắt đồng hồ đo thời gian của từng node con để giảm overhead đo đạc, chỉ giữ lại tổng `Execution Time` ở mức câu lệnh. Do đó, không thể suy diễn thời gian chi tiết của từng bước trung gian.
5. **Bộ đệm bộ nhớ (Shared Hit Blocks):**
   - Số lượng `Shared Hit Blocks` ở root node phản ánh tổng số lần đọc khối từ buffer pool của toàn bộ câu lệnh. Không được lấy số liệu này cộng dồn thủ công với các node con vì sẽ gây hiện tượng đếm đúp (double counting).

---

## 8. Đánh Đổi & Chi Phí Ghi Của Expression Index

Expression Index là một công cụ mạnh mẽ nhưng đi kèm chi phí đáng kể:

1. **Chi phí lưu trữ (Storage Overhead):**
   - Mỗi expression index tạo ra một cấu trúc cây B-tree riêng biệt trên đĩa và chiếm dụng không gian trong bộ nhớ đệm `shared_buffers`.
2. **Chi phí thao tác ghi (Write Penalty - INSERT / UPDATE / DELETE):**
   - Mỗi khi một dòng mới được chèn vào hoặc cập nhật, database engine không chỉ ghi vào bảng heap mà còn phải **tính toán lại hàm biểu thức** (ví dụ `lower()`) và cập nhật con trỏ trong cây B-tree của expression index.
   - Nếu bảng có tần suất ghi cao (write-heavy), việc tạo nhiều expression index sẽ làm suy giảm thông lượng ghi (write throughput) và tăng nguy cơ phân mảnh bảng (table bloat / HOT update limitations).
3. **Quy tắc thực hành:**
   - Chỉ tạo Expression Index khi câu truy vấn có tần suất đọc đủ lớn và biểu thức vị từ là cố định.
   - Không tạo bừa bãi mọi index "cho chắc chắn".

---

## 9. Giới Hạn Của Thí Nghiệm

- **Phạm vi chuỗi ASCII:** Dữ liệu thử nghiệm hoàn toàn là chuỗi ký tự ASCII không dấu. Thí nghiệm không chứng minh tính đúng đắn hoặc hiệu năng đối với tiếng Việt có dấu, chuẩn hóa Unicode (NFC / NFD), hoặc các biểu thức `ILIKE` phức tạp.
- **`lower()` không phải là tìm kiếm không dấu:** Hàm `lower()` chỉ chuyển đổi ký tự hoa thành ký tự thường trong bảng mã. Để tìm kiếm tiếng Việt không dấu (accent-insensitive search), hệ thống cần các giải pháp chuyên biệt như extension `unaccent` hoặc cấu hình PostgreSQL Full-Text Search.
- **Chưa đo đạc chi phí ghi:** Lab tập trung vào đặc tính đọc (read path) và kế hoạch thực thi, chưa thực hiện bài benchmark định lượng về độ trễ ghi (write latency overhead) của expression index.