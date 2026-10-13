# M2-E4A: JPA Fetching, N+1 Problem, JOIN FETCH, EntityGraph và DTO Projection trên PostgreSQL 16

**Mã thí nghiệm:** M2-E4A
**Môi trường:** Java 22, Hibernate 6.5.3.Final (test-scope Java SE JPA, RESOURCE_LOCAL), Spring Boot 3.3.4, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12
**Mục tiêu:** Đo lường chính xác hành vi phát sinh câu truy vấn SQL (`prepared statement count`) khi đọc dữ liệu có quan hệ ManyToOne, mổ xẻ cơ chế Lazy Loading, First-Level Cache (Persistence Context Identity Map) và so sánh 3 chiến lược giải quyết (JOIN FETCH, Dynamic EntityGraph, JPQL Constructor DTO Projection) trên cùng một bài toán nghiệp vụ.

---

## 1. Bài toán nghiệp vụ và Thiết kế Schema

### 1.1. Yêu cầu bài toán
Hệ thống cần đọc lịch sử đơn hàng gồm 3 trường thông tin:
- `orderId` (ID đơn hàng)
- `buyerName` (Tên người mua)
- `quantity` (Số lượng vật phẩm)

Mục tiêu là xây dựng danh sách DTO `OrderSummary(Long orderId, String buyerName, int quantity)` đáp ứng đúng hợp đồng nghiệp vụ này.

### 1.2. Schema DDL
Bảng được khởi tạo độc lập qua file SQL `backend-app/src/test/resources/sql/fetch-probe.sql`:

```sql
CREATE TABLE IF NOT EXISTS fetch_buyer (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS fetch_order (
    id BIGINT PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES fetch_buyer(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);
```

### 1.3. Entity JPA Mapping
- `FetchBuyer`: Chứa `id` và `name`.
- `FetchOrder`: Quan hệ đơn hướng (unidirectional) ManyToOne:
  ```java
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "buyer_id", nullable = false)
  private FetchBuyer buyer;
  ```
- **Ranh giới bất biến:** Không cấu hình Bidirectional OneToMany collection ngược lại; không dùng Lombok `@Data`, `@ToString`, `@EqualsAndHashCode` (để tránh vô tình kích hoạt getter trên proxy liên kết).

### 1.4. Thiết lập Cố định (Datasets)
1. **Dataset D1 (Tỷ lệ 1:1 - Tối đa hóa N+1):**
   - 6 buyers ID 1..6 (`Buyer-1` .. `Buyer-6`).
   - 6 orders ID 101..106, order `100+i` thuộc về buyer `i`, quantity = `i`.
2. **Dataset D2 (Buyer lặp lại - Khảo sát First-Level Cache):**
   - 3 buyers ID 1..3 (`Buyer-1` .. `Buyer-3`).
   - 6 orders ID 101..106, buyerId lần lượt `[1, 1, 2, 2, 3, 3]`, quantity lần lượt `1..6`.

---

## 2. Hướng dẫn Tái hiện và Đo lường

```powershell
# Chạy suite kiểm thử riêng biệt
mvn -pl backend-app -am "-Dtest=JpaFetchExperimentsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

# Thu thập toàn bộ evidence bundle chuẩn
python scripts/collect_lab_evidence.py --run --prompt prompt14
```

---

## 3. Kết quả Đo đạc: Bảng Đối chiếu Expected vs Observed

Đo đạc dựa trên `org.hibernate.stat.Statistics.getPrepareStatementCount()` và `FetchStatementInspector` ghi nhận các câu SQL được Hibernate chuẩn bị.

| Case ID | Dataset | Chiến lược truy vấn | Expected Root Stmts | Expected Final Stmts | **Observed Root Stmts** | **Observed Final Stmts** | **Observed SQL Statements Count** | Assertions Passed |
|:---|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **NQ1** | D1 | Baseline N+1 (`FetchType.LAZY`) | 1 | 7 (1 + 6) | **1** | **7** | **7** | **TRUE** |
| **NQ2** | D1 | JOIN FETCH (`join fetch o.buyer`) | 1 | 1 | **1** | **1** | **1** | **TRUE** |
| **NQ3** | D1 | Dynamic EntityGraph (`fetchgraph`) | 1 | 1 | **1** | **1** | **1** | **TRUE** |
| **NQ4** | D1 | JPQL Constructor Projection (Scalar DTO) | 1 | 1 | **1** | **1** | **1** | **TRUE** |
| **NQ5** | D2 | Baseline N+1 với Buyer lặp lại | 1 | 4 (1 + 3) | **1** | **4** | **4** | **TRUE** |

---

## 4. Giải phẫu Cơ chế Kỹ thuật (Mechanisms)

### 4.1. NQ1: Bản chất của N+1 và ByteBuddy Proxy
- **Root Query:** Khi gọi `select o from FetchOrder o order by o.id`, Hibernate phát đúng 1 câu SQL:
  ```sql
  select fo1_0.id, fo1_0.buyer_id, fo1_0.quantity from fetch_order fo1_0 order by fo1_0.id
  ```
- **Trạng thái Proxy ban đầu:** Trường `buyer` trong mỗi instance `FetchOrder` được gán một dynamic ByteBuddy proxy. Tại thời điểm này:
  - `PersistenceUnitUtil.isLoaded(order, "buyer") == false`
  - `Hibernate.isInitialized(order.getBuyer()) == false`
- **Kích hoạt Lazy Loading:** Khi mapper duyệt danh sách và gọi `order.getBuyer().getName()`, phương thức `getName()` chạm vào proxy chưa khởi tạo. Proxy gửi tín hiệu đến Persistence Context mở câu SELECT thứ cấp:
  ```sql
  select fb1_0.id, fb1_0.name from fetch_buyer fb1_0 where fb1_0.id=?
  ```
  Vì D1 có 6 đơn hàng ứng với 6 buyer khác nhau, vòng lặp phát sinh thêm đúng 6 câu SELECT thứ cấp: $1 + 6 = 7$ câu truy vấn.
- **Lưu ý quan trọng về `getId()`:** Nếu chỉ gọi `order.getBuyer().getId()`, Hibernate có thể trích xuất trực tiếp giá trị Foreign Key `buyer_id` đã có sẵn trong bảng `fetch_order` mà **không cần** phát câu SELECT phụ tới `fetch_buyer`. Vì vậy, bắt buộc phải truy cập thuộc tính dữ liệu (`getName()`) để kiểm chứng hành vi nạp lười thật sự.

### 4.2. NQ2: JOIN FETCH
- **JPQL:** `select o from FetchOrder o join fetch o.buyer order by o.id`
- **SQL sinh ra:**
  ```sql
  select fo1_0.id, fo1_0.buyer_id, b1_0.id, b1_0.name, fo1_0.quantity
  from fetch_order fo1_0
  join fetch_buyer b1_0 on b1_0.id=fo1_0.buyer_id
  order by fo1_0.id
  ```
- **Cơ chế:** Mệnh đề `join fetch` báo hiệu cho Hibernate nạp đồng thời cả entity gốc (`FetchOrder`) và entity liên kết (`FetchBuyer`) ngay trong câu SELECT đầu tiên.
- **Trạng thái:** `PersistenceUnitUtil.isLoaded(order, "buyer") == true` ngay sau khi query xong. Khi gọi `order.getBuyer().getName()`, dữ liệu đã nằm sẵn trong bộ nhớ, không phát sinh bất kỳ câu lệnh SQL bổ sung nào.

### 4.3. NQ3: Dynamic EntityGraph
- **Cấu hình:**
  ```java
  EntityGraph<FetchOrder> entityGraph = em.createEntityGraph(FetchOrder.class);
  entityGraph.addAttributeNodes("buyer");
  TypedQuery<FetchOrder> query = em.createQuery("select o from FetchOrder o order by o.id", FetchOrder.class);
  query.setHint("jakarta.persistence.fetchgraph", entityGraph);
  ```
- **Cơ chế:** EntityGraph là một fetch plan chuẩn của Jakarta Persistence API 3.1. Hint `jakarta.persistence.fetchgraph` hướng dẫn persistence provider xem tất cả các thuộc tính được chỉ định trong graph là EAGER, còn các thuộc tính khác là LAZY.
- **SQL sinh ra:** Trên Hibernate 6.5.3, câu query sử dụng INNER/LEFT JOIN nạp `fetch_buyer` ngay lập tức, tương tự `JOIN FETCH`.
- **Lưu ý chuẩn chuẩn mực:** Hợp đồng JPA không cam kết mọi EntityGraph trên mọi provider đều sinh đúng 1 SQL duy nhất (một số provider có thể chọn batch fetch hoặc secondary query phụ thuộc dialect/cấu hình). Tuy nhiên, trên cấu hình Hibernate 6.5 + PostgreSQL của lab này, số câu lệnh prepared là **1**.

### 4.4. NQ4: JPQL Constructor Projection (Scalar DTO)
- **JPQL:**
  ```sql
  select new com.aethercore.backend.fetch.OrderSummary(o.id, b.name, o.quantity)
  from FetchOrder o join o.buyer b
  order by o.id
  ```
- **SQL sinh ra:**
  ```sql
  select fo1_0.id, b1_0.name, fo1_0.quantity
  from fetch_order fo1_0
  join fetch_buyer b1_0 on b1_0.id=fo1_0.buyer_id
  order by fo1_0.id
  ```
- **Cơ chế:** Hibernate không khởi tạo entity managed, không tạo ByteBuddy proxy, không đưa object vào Persistence Context/Identity Map, mà ánh xạ trực tiếp các cột SQL vào constructor của Java Record `OrderSummary`.
- **Ưu điểm vượt trội cho Read-Only Screen:** Sau khi gọi `em.close()`, đối tượng DTO vẫn giữ nguyên vẹn dữ liệu, hoàn toàn không bị nguy cơ `LazyInitializationException` và tiêu tốn cực ít bộ nhớ heap.

### 4.5. NQ5: First-Level Cache (Persistence Context Identity Map)
- **Kịch bản:** D2 chứa 6 đơn hàng nhưng chỉ có 3 buyers (Buyer 1: 2 đơn, Buyer 2: 2 đơn, Buyer 3: 2 đơn).
- **Trình tự thực thi:**
  1. Root query: 1 SELECT `fetch_order`. (Count = 1)
  2. Order 101 (Buyer 1): Cache miss -> SELECT `fetch_buyer` với ID = 1. (Count = 2). Entity Buyer 1 được lưu vào Persistence Context.
  3. Order 102 (Buyer 1): Cache hit -> Trả về instance Buyer 1 đã có trong Persistence Context. **Không phát SQL!** (Count = 2)
  4. Order 103 (Buyer 2): Cache miss -> SELECT `fetch_buyer` với ID = 2. (Count = 3).
  5. Order 104 (Buyer 2): Cache hit. (Count = 3)
  6. Order 105 (Buyer 3): Cache miss -> SELECT `fetch_buyer` với ID = 3. (Count = 4).
  7. Order 106 (Buyer 3): Cache hit. (Count = 4)
- **Kết luận:** Tổng số câu lệnh là $1 + 3 = 4$. N không nhất thiết bằng số đơn hàng; N bị chặn trên bởi **số lượng entity duy nhất (distinct entity count)** chưa có trong Session/EntityManager hiện tại.

---

## 5. So sánh Chiến lược và Cảnh báo Hiệu năng

| Tiêu chí | Baseline (Lazy N+1) | JOIN FETCH | Dynamic EntityGraph | DTO Projection |
|:---|:---|:---|:---|:---|
| **Mục đích phù hợp** | Đọc đơn lẻ khi không cần liên kết | Cần thực thể đầy đủ để chỉnh sửa/lưu | Cần fetch plan linh hoạt tùy API | Màn hình danh sách/Read-only report |
| **Số prepared statements (D1)** | 7 | 1 | 1 | 1 |
| **Quản lý Entity lifecycle** | Managed | Managed | Managed | **Không (Pure DTO)** |
| **Nguy cơ LazyInitException** | Có (nếu ra ngoài Session) | Không (cho association đã fetch) | Không (cho association trong graph) | **Không bao giờ** |
| **Payload dữ liệu DB gửi lên** | Nhỏ giọt nhiều roundtrip | Đầy đủ entity | Đầy đủ entity | **Tối thiểu (chỉ cột cần dùng)** |

### Cảnh báo thực nghiệm:
1. **Không suy diễn tốc độ từ Query Count:** Giảm từ 7 câu xuống 1 câu giảm roundtrip mạng, nhưng **không đồng nghĩa nhanh hơn 7 lần**. Câu JOIN SQL có thể có cost thực thi khác nhau tùy thuộc vào index, selectivity và execution plan của PostgreSQL.
2. **EAGER không đồng nghĩa JOIN:** Việc khai báo `FetchType.EAGER` trong JPA entity không bảo đảm Hibernate luôn sinh câu JOIN SQL; khi gọi `em.createQuery(...)` không kèm JOIN FETCH, Hibernate vẫn có thể phát sinh N+1 câu SELECT riêng lẻ ngay khi nạp entity cha!

---

## 6. Giới hạn Phạm vi Thực nghiệm
- Lab được triển khai với Java SE JPA thuần (RESOURCE_LOCAL) trong test scope để cô lập chính xác hành vi của Hibernate 6.5.
- Chưa bao gồm: Spring Data JPA repository annotations (`@EntityGraph`), HTTP Open Session in View (OSIV), phân trang trên collection fetch (sẽ giải quyết trong M2-E4B), hay benchmark latency dưới tải cao.
