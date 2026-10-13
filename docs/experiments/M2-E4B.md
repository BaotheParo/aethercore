# M2-E4B: Collection Fetch, Phân Trang và Truy Vấn Hai Bước Trên PostgreSQL 16

**Mã thí nghiệm:** M2-E4B
**Môi trường:** Java 22, Hibernate 6.5.3.Final (Java SE JPA, RESOURCE_LOCAL), Spring Boot 3.3.4, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12
**Mục tiêu:** Mổ xẻ hiện tượng phân trang trong bộ nhớ (in-memory pagination) khi fetch collection, kiểm chứng cơ chế fail-fast guard của Hibernate, và triển khai giải pháp phân trang hai bước (Two-Step ID Pagination) bảo đảm tính đúng đắn và kiểm soát số lượng entity được nạp.

---

## 1. Ẩn dụ Thực tế: Lấy Hai Hồ sơ Kèm Toàn bộ Tài liệu

Hãy hình dung một thủ thư cần phục vụ bạn:
- **Yêu cầu:** "Cho tôi xem 2 bộ hồ sơ khách hàng đầu tiên theo thứ tự tên, kèm toàn bộ hợp đồng giao dịch của họ."
- **Cách 1 (In-Memory Pagination - PG1):** Thủ thư vào kho bê **tất cả 1.000 bộ hồ sơ** và toàn bộ hợp đồng của cả kho ra quầy lễ tân (chỉ mất đúng 1 chuyến đi). Sau đó, tại quầy lễ tân, thủ thư chọn ra 2 bộ đầu tiên đưa cho bạn và cất 998 bộ còn lại.
  $\rightarrow$ Chuyến đi chỉ là 1 lần, nhưng công sức và tải trọng bê vác là toàn bộ kho lưu trữ. Nếu kho có 1.000.000 hồ sơ, quầy lễ tân sẽ sập (Out Of Memory)!
- **Cách 2 (Phân trang Hai bước - PG3):**
  - **Chuyến 1:** Thủ thư tra cứu sổ mục lục, lấy nhanh mã định danh (ID) của đúng 2 bộ hồ sơ đầu tiên (ví dụ: hồ sơ #1 và #2).
  - **Chuyến 2:** Thủ thư đi thẳng vào giá để lấy chính xác hồ sơ #1 và #2 cùng giấy tờ của chúng ra.
  $\rightarrow$ Đi 2 chuyến, nhưng lượng tài liệu mang vác đúng bằng nhu cầu hiển thị.

---

## 2. Bản chất Kỹ thuật: Vì Sao Database LIMIT Bị Vô Hiệu Khi Fetch Collection?

### 2.1. Sự Lệch pha giữa Database Row và Domain Entity
Xét quan hệ 1-N giữa `FetchBuyer` và `FetchOrder`:
- 1 Buyer có thể có 0, 1, hoặc nhiều Orders.
- Khi thực hiện câu SQL JOIN:
  ```sql
  SELECT b.*, o.* FROM fetch_buyer b LEFT JOIN fetch_order o ON b.id = o.buyer_id
  ```
  Nếu Buyer #1 có 3 orders, Database Engine sẽ trả về **3 dòng kết quả (result rows)** cho cùng một Buyer #1:
  - Row 1: Buyer 1 - Order 101
  - Row 2: Buyer 1 - Order 102
  - Row 3: Buyer 1 - Order 103
- **Vấn đề cốt lõi:** Nếu bạn gắn `LIMIT 2` vào câu SQL này ở mức database, database sẽ cắt lấy đúng 2 dòng đầu tiên. Kết quả là Buyer #1 chỉ có Order 101 và Order 102, còn Order 103 bị mất! Đồng thời Buyer #2 hoàn toàn không xuất hiện trên trang!
- **Hành vi của Hibernate (PG1):** Để tránh làm rách/thiếu dữ liệu của collection trong entity, Hibernate **bỏ hoàn toàn mệnh đề LIMIT/OFFSET khỏi câu SQL** gửi tới PostgreSQL. Thay vào đó, Hibernate nạp toàn bộ bảng vào bộ nhớ Heap rồi mới cắt phân trang ở tầng ứng dụng!

### 2.2. Vì sao phải dùng `LEFT JOIN`?
Trong fixture bài toán, Buyer #2 không có bất kỳ đơn hàng nào.
- Nếu dùng `INNER JOIN`: Buyer #2 bị loại bỏ hoàn toàn khỏi kết quả truy vấn.
- Dùng `LEFT JOIN`: Đảm bảo mọi Buyer đều có mặt trên trang theo đúng thứ tự phân trang, với collection đơn hàng rỗng (`orders = []`).

---

## 3. Bảng Đối chiếu Thực nghiệm: Expected vs Observed (PG1 – PG5)

Dữ liệu đo đạc thực tế trên suite `JpaCollectionPaginationExperimentsTest`:

| Case | Kịch bản | Chính sách | Offset/Size | Statements (Exp/Obs) | Buyer Loads (Exp/Obs) | Order Loads (Exp/Obs) | Returned IDs | Outcome | Assertions |
|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **PG1** | Collection fetch phân trang (Lenient) | `fail=false` | 0 / 2 | 1 / **1** | 5 / **5** | 10 / **10** | `[1, 2]` | RETURNED_PAGE | **PASS** |
| **PG2** | Fail-fast guard từ chối phân trang collection | `fail=true` | 0 / 2 | 0 / **0** | 0 / **0** | 0 / **0** | `[]` | EXPECTED_REJECTION | **PASS** |
| **PG3** | Phân trang hai bước (Trang 0) | `fail=true` | 0 / 2 | 2 / **2** | 2 / **2** | 3 / **3** | `[1, 2]` | RETURNED_PAGE | **PASS** |
| **PG4** | Phân trang hai bước (Trang 1) | `fail=true` | 2 / 2 | 2 / **2** | 2 / **2** | 3 / **3** | `[3, 4]` | RETURNED_PAGE | **PASS** |
| **PG5** | Phân trang hai bước (Trang rỗng out-of-bounds) | `fail=true` | 99 / 2 | 1 / **1** | 0 / **0** | 0 / **0** | `[]` | RETURNED_PAGE | **PASS** |

---

## 4. Giải phẫu Cơ chế Thực nghiệm

### 4.1. PG1: 1 Câu SQL nhưng Tải Toàn bộ Bảng vào Bộ nhớ
- **JPQL:**
  ```sql
  select b from FetchBuyer b left join fetch b.orders order by b.name asc, b.id asc
  ```
- **SQL gửi xuống PostgreSQL:**
  ```sql
  select fb1_0.id,fb1_0.name,o1_0.buyer_id,o1_0.id,o1_0.quantity
  from fetch_buyer fb1_0
  left join fetch_order o1_0 on fb1_0.id=o1_0.buyer_id
  order by fb1_0.name,fb1_0.id
  ```
  *(Hoàn toàn không có LIMIT / OFFSET / FETCH FIRST)*
- **Hậu quả đo được:** Dù trang chỉ yêu cầu 2 buyers (Buyer 1 & 2 với tổng cộng 3 orders), Hibernate đã nạp **toàn bộ 5 buyers và 10 orders** của cơ sở dữ liệu vào Session (`buyerLoads = 5`, `orderLoads = 10`).

### 4.2. PG2: Cấu hình Bảo vệ Fail-Fast
- Kích hoạt cấu hình:
  ```properties
  hibernate.query.fail_on_pagination_over_collection_fetch=true
  ```
- **Hành vi:** Ngay khi ứng dụng gọi `query.getResultList()`, Hibernate phát hiện có `setFirstResult`/`setMaxResults` đi kèm collection fetch join và ném ngay ngoại lệ:
  `org.hibernate.HibernateException: setFirstResult() or setMaxResults() specified with collection fetch join (in-memory pagination was about to be applied, but 'hibernate.query.fail_on_pagination_over_collection_fetch' is enabled)`
- **Kết quả:** Ngăn chặn tuyệt đối câu query tai hại trước khi nó kịp gửi xuống Database (`preparedStatementCount = 0`).

### 4.3. PG3 & PG4: Thuật toán Phân trang Hai Bước (Two-Step ID Pagination)
Triển khai thông qua lớp `BuyerPageReader`:

#### Bước 1 — Lấy danh sách ID phân trang ở mức Database:
```sql
select b.id from FetchBuyer b order by b.name asc, b.id asc
```
- Vì truy vấn chỉ chọn scalar ID (không join collection), PostgreSQL áp dụng an toàn mệnh đề phân trang:
  ```sql
  fetch first ? rows only
  ```
- Trả về đúng danh sách ID của trang: `ids = [1, 2]` cho offset 0, và `ids = [3, 4]` cho offset 2.

#### Bước 2 — Fetch Collection cho đúng danh sách ID đã chọn:
```sql
select b from FetchBuyer b left join fetch b.orders where b.id in :ids
```
- Không áp dụng limit/offset ở bước này. Toàn bộ orders của đúng 2 buyers được nạp đầy đủ.

#### Yếu tố sống còn: Khôi phục Thứ tự (Deterministic Reordering)
Mệnh đề `WHERE b.id IN (1, 2)` của SQL **không cam kết** trả về các dòng theo thứ tự input của mảng `[1, 2]`. Vì vậy, `BuyerPageReader` lập index `Map<Long, FetchBuyer>` và duyệt lại theo đúng thứ tự `ids` từ Bước 1. Điều này bảo đảm trang giữ đúng hợp đồng sắp xếp `order by name, id`.

### 4.4. PG5: Tối ưu Ngắt mạch (Short-Circuit) khi Trang Rỗng
Khi `offset = 99`, Bước 1 trả về danh sách ID rỗng (`ids.isEmpty() == true`).
`BuyerPageReader` lập tức trả về kết quả rỗng mà **không thực hiện Bước 2** (`WHERE id IN ()`), tiết kiệm tài nguyên mạng và DB.

---

## 5. Giới hạn Kỹ thuật Cần Lưu ý

1. **Một SQL $\neq$ Luôn nhanh hơn; Hai SQL $\neq$ Luôn chậm hơn:**
   PG1 chỉ tốn 1 statement nhưng có thể kéo hàng triệu records gây nghẽn RAM và mạng. PG3 tốn 2 statements nhưng lượng dữ liệu chuyển giao là tối thiểu và có thể tận dụng index trên khóa chính `id`.
2. **Snapshot Consistency giữa Hai Bước:**
   Trong mức cô lập giao dịch mặc định `READ COMMITTED`, Bước 1 và Bước 2 là hai câu SELECT độc lập. Nếu có transaction khác xen vào xóa hoặc thêm dữ liệu giữa hai câu SELECT, dữ liệu nạp ở Bước 2 có thể không khớp hoàn toàn với snapshot của Bước 1. Nếu hệ thống yêu cầu snapshot tuyệt đối, cần cân nhắc `REPEATABLE READ`.
3. **Giới hạn Thể tích Con (Child Volume):**
   Phân trang hai bước giới hạn được số lượng root entities (ví dụ: 2 buyers), nhưng nếu một buyer có 100.000 orders thì câu fetch thứ hai vẫn tải 100.000 orders. Khi đó, cần phân trang ở mức collection con (hoặc dùng subselect / batch fetching có kiểm soát).
4. **Hiện tượng Page Drift:**
   Phân trang bằng Offset (`LIMIT / OFFSET`) luôn tiềm ẩn nguy cơ lệch trang (duplicate hoặc skip phần tử) nếu có thao tác INSERT/DELETE diễn ra đồng thời giữa các lần lật trang của người dùng.
