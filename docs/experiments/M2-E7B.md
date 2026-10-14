# M2-E7B: Non-Transactional Migration, INVALID Index Và Phục Hồi Có Kiểm Chứng

**Mã thử nghiệm:** M2-E7B
**Môi trường thử nghiệm:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Flyway 10.10.0, Spring Boot 3.3.4
**Bằng chứng kiểm chứng:** `docs/evidence/prompt21/nontransactional-index-observations.json`
**Trạng thái kiểm thử:** 3/3 Acceptance Tests PASS (NC1, NC2, NC3), Full Reactor Clean Run

---

## 1. Giới Thiệu & Mô Hình Tư Duy: Nâng Cấp Không Transactional

Trong mô hình M2-E7A, chúng ta đã chứng kiến sức mạnh bảo vệ của PostgreSQL Transactional DDL: khi câu lệnh `ADD CONSTRAINT` gặp lỗi dữ liệu, toàn bộ script migration bị hủy bỏ (rollback), bảng trở lại trạng thái nguyên vẹn, và bảng lịch sử Flyway không bị lưu vết hỏng.

Tuy nhiên, trong thế giới thực của các cơ sở dữ liệu quy mô lớn (High Traffic / Large Scale Databases), có những câu lệnh DDL **bắt buộc không được phép chạy trong transaction**. Điển hình nhất là:
```sql
CREATE INDEX CONCURRENTLY ...
```

Khi tạo index thông thường trên bảng hàng chục triệu dòng, PostgreSQL phải lấy khóa `ShareLock` trên bảng, chặn đứng toàn bộ các thao tác ghi dữ liệu (`INSERT`, `UPDATE`, `DELETE`) trong suốt quá trình xây dựng index (có thể kéo dài hàng chục phút đến vài giờ). Để tránh downtime, PostgreSQL cung cấp tùy chọn `CONCURRENTLY`:
- `CREATE INDEX CONCURRENTLY` xây dựng index mà không chặn các thao tác ghi (writes).
- **Cái giá phải trả:** Lệnh này bắt buộc phải tự quản lý nhiều scans và các commit nội bộ, do đó **không thể chạy bên trong một transaction block**.

Khi chuyển sang chế độ **Non-transactional migration** trong Flyway:
1. **Nếu câu lệnh DDL bị lỗi giữa chừng (ví dụ: vi phạm dữ liệu trùng lặp), database sẽ rơi vào trạng thái nào?**
2. **Liệu database có tự rollback sạch sẽ như trong M2-E7A hay không?**
3. **Lệnh `flyway.repair()` có thực sự "sửa" được lỗi hay chỉ là một ảo tưởng metadata?**
4. **Quy trình phục hồi chuẩn mực (recovery workflow) cần được tiến hành ra sao?**

Thí nghiệm **M2-E7B** khảo sát toàn diện các câu hỏi này qua 3 kịch bản thực nghiệm: **NC1**, **NC2**, và **NC3**.

---

## 2. So Sánh Kiến Trúc: Transactional (M2-E7A) vs Non-Transactional (M2-E7B)

Bảng đối chiếu tổng hợp cơ chế và hành vi giữa hai mô hình:

| Tiêu chí | M2-E7A (V6 Transactional Migration) | M2-E7B (V2 Non-Transactional Migration) |
|---|---|---|
| **Cơ chế transaction** | Mặc định: Flyway bọc toàn bộ script trong một transaction (`BEGIN ... COMMIT`) | Flyway không bọc script trong transaction (`executeInTransaction=false` qua sidecar `.conf`). PostgreSQL vẫn thực thi các transaction nội bộ theo từng statement |
| **Cơ chế khóa Flyway** | Khóa Transactional Advisory Lock (`pg_advisory_xact_lock`) | Khóa Session-level Advisory Lock (`flyway.postgresql.transactional.lock=false`) |
| **Lệnh DDL điển hình** | `ALTER TABLE ... ADD CONSTRAINT` | `CREATE INDEX CONCURRENTLY` |
| **Khi script gặp lỗi** | PostgreSQL kích hoạt **DDL Rollback** toàn bộ transaction | **Không có transaction cấp script để rollback**: Script migration không bọc trong transaction ngoài nên các thay đổi catalog dở dang (như INVALID index) được giữ lại |
| **Trạng thái Database Objects sau lỗi** | Khôi phục 100% về trạng thái cũ; constraint cũ bảo toàn | Bỏ lại **INVALID index** trong catalog (`indisvalid = false`) |
| **Trạng thái `flyway_schema_history`** | Sạch sẽ: Dòng ghi lịch sử bị rollback, không lưu bản ghi failed | Ghi nhận bản ghi migration với `success = false` |
| **Hành vi `flyway.validate()`** | PASS (nếu classpath khớp với version trước lỗi) | **FAIL**: Báo lỗi tồn tại failed migration trong lịch sử |
| **Vai trò của `flyway.repair()`** | **Không cần dùng**: Rollback đã dọn sạch metadata | **Bắt buộc**: Xóa bản ghi `success = false` khỏi history |
| **Giới hạn của `flyway.repair()`** | Không áp dụng | **Chỉ sửa metadata của Flyway**; hoàn toàn không drop index INVALID, không sửa dữ liệu trùng! |
| **Quy trình phục hồi (Recovery)** | Dọn dẹp dữ liệu lỗi trên bảng -> Chạy lại `migrate()` | `flyway.repair()` -> `DROP INDEX` thủ công -> Dọn dữ liệu -> Chạy lại `migrate()` |

---

## 3. Hai Loại Cấu Hình Transaction Bắt Buộc Khi Dùng CONCURRENTLY

Để chạy `CREATE INDEX CONCURRENTLY` thông qua Flyway trên PostgreSQL, kỹ sư bắt buộc phải thiết lập **đồng thời cả hai cấu hình** sau:

### 3.1. Cấu hình cấp độ Script (Sidecar Configuration)
Trong lab thực nghiệm này, file sidecar được tạo tường minh nằm cạnh script migration:
`V2__create_unique_token_index.sql.conf`
```properties
executeInTransaction=false
```
Cấu hình này yêu cầu Flyway không bọc script migration này vào transaction block (`conn.setAutoCommit(false)` hoặc `BEGIN`) ở cấp độ migration runner. Đây là cấu hình tường minh của kịch bản lab; tránh khẳng định mọi phiên bản Flyway đều bắt buộc người dùng tạo sidecar để nhận diện CIC.

### 3.2. Cấu hình cấp độ Engine Flyway (Advisory Lock Mode)
Trong cấu hình của Flyway (hoặc `application.properties`):
```properties
flyway.postgresql.transactional.lock=false
```
**Bản chất kỹ thuật:**
Mặc định trên PostgreSQL, Flyway sử dụng `pg_advisory_xact_lock` (transactional advisory lock) để khóa database tránh việc nhiều tiến trình cùng migrate đồng thời. Khóa transactional này tự giải phóng khi transaction kết thúc.
Thiết lập `flyway.postgresql.transactional.lock=false` yêu cầu Flyway chuyển sang sử dụng session-level advisory lock (`pg_advisory_lock`), cho phép thực thi migration khi script không bọc trong transaction ngoài. Khóa session-level advisory lock tồn tại trong phiên kết nối và có thể được giải phóng bằng `pg_advisory_unlock`, hoặc tự động giải phóng khi đóng kết nối/phiên làm việc (session termination); hai việc này không phải cùng một thao tác.
*Lưu ý phạm vi thực nghiệm:* Lab hiện tại tập trung kiểm chứng chế độ session-level advisory lock này; lab chưa so sánh đối chứng trực tiếp lỗi cụ thể của chế độ transactional advisory lock mặc định khi gặp CIC, nên không ghi nguyên nhân lỗi của mode còn lại như một kết quả đã đo.

---

## 4. Phân Tích Thực Nghiệm: NC1 – NC3

### 4.1. NC1: CIC Trong Explicit Transaction (Bị Từ Chối Ngay Từ Đầu)

- **Mục tiêu:** Chứng minh PostgreSQL từ chối thực thi `CREATE INDEX CONCURRENTLY` nếu đang ở trong transaction block.
- **Kịch bản:**
  1. Tạo bảng `index_probe` (3 dòng: `(1, 'IRON'), (2, 'IRON'), (3, 'GOLD')`).
  2. Mở kết nối JDBC, gọi `conn.setAutoCommit(false)`.
  3. Thực thi `CREATE UNIQUE INDEX CONCURRENTLY uq_index_probe_token ON index_probe (token)`.
- **Quan sát thực tế:**
  - Lệnh ném ngay `SQLException` với mã lỗi **SQLSTATE `25001`** (`active_sql_transaction`).
  - Thông báo lỗi từ PostgreSQL: `ERROR: CREATE INDEX CONCURRENTLY cannot run inside a transaction block`.
  - Lưu ý quan trọng: Lỗi này xảy ra **trước khi** PostgreSQL kiểm tra dữ liệu hay quét bảng. Do đó, mã lỗi nhận được là `25001` (vi phạm chế độ transaction), hoàn toàn không phải là `23505` (trùng dữ liệu).
  - Sau khi `conn.rollback()`, truy vấn catalog xác nhận: `exists = false` (không có index nào được tạo), 3 dòng dữ liệu nguyên vẹn.

---

### 4.2. NC2: Non-Transactional Build Thất Bại & INVALID Index

- **Mục tiêu:** Quan sát trạng thái catalog và lịch sử Flyway khi migration non-transactional gặp lỗi dữ liệu trùng lặp.
- **Kịch bản:**
  1. Cấu hình Flyway với `executeInTransaction=false` và `flyway.postgresql.transactional.lock=false`.
  2. Bảng `index_probe` chứa 2 token trùng nhau: `(1, 'IRON')` và `(2, 'IRON')`.
  3. Chạy `flyway.migrate()` để thực thi migration V2 (`CREATE UNIQUE INDEX CONCURRENTLY`).
- **Quan sát thực tế:**
  - Lệnh bắt đầu tiến trình quét và build index. Ở giai đoạn kiểm tra tính duy nhất, PostgreSQL phát hiện token `'IRON'` bị trùng lặp.
  - Flyway ném `FlywayException` với root cause là `PSQLException` mang mã lỗi **SQLSTATE `23505`** (`unique_violation`).
  - Truy vấn catalog `pg_index` và `pg_class` qua fresh connection:
    * `exists = true`: Index `uq_index_probe_token` **vẫn tồn tại** trong database!
    * `indisunique = true`: Index được đánh dấu là unique.
    * **`indisvalid = false`**: Index bị đánh dấu là **INVALID**!
    * `indisready = false`, `indislive = true`.
  - Truy vấn `flyway_schema_history`:
    * Bản ghi V1: `success = true`.
    * Bản ghi V2: **`success = false`**!
  - Bảng dữ liệu `index_probe` vẫn giữ nguyên 3 dòng.
  - Gọi `flyway.validate()`: Ném `FlywayException` vì lịch sử chứa bản ghi migration failed.

> **Ý nghĩa của INVALID Index:**
> Một index mang trạng thái `indisvalid = false` là một index hỏng dở dang. Trình tối ưu hóa truy vấn (Query Planner) sẽ **không bao giờ sử dụng** index này để phục vụ các câu truy vấn đọc (`SELECT`). Tuy nhiên, trong một số giai đoạn build, PostgreSQL vẫn có thể phải tốn chi phí duy trì index này khi có thao tác ghi, làm suy giảm hiệu năng hệ thống mà không đem lại bất kỳ lợi ích nào!

---

### 4.3. NC3: Repair Không Sửa Index; Quy Trình Recovery Có Kiểm Soát

- **Mục tiêu:** Chứng minh giới hạn thực sự của `flyway.repair()` và xác thực quy trình 4 bước phục hồi an toàn.
- **Diễn biến qua 4 Checkpoint kiểm chứng:**

#### Checkpoint 1 (Trước khi Repair):
- Trạng thái: Index tồn tại với `indisvalid = false`, OID = `16416`. History có V2 `success = false`. Dữ liệu có 1 nhóm trùng lặp (`IRON` count = 2).
- Đo lường hash SHA-256 của file `V1`, `V2`, và `V2.conf` trên đĩa.

#### Checkpoint 2 (Sau khi chạy `flyway.repair()`):
- Chạy lệnh `flyway.repair()`. Flyway thông báo: `Successfully repaired schema history table`.
- Kiểm tra `flyway_schema_history`: Dòng V2 failed đã được gỡ bỏ; history chỉ còn lại V1 (`success = true`).
- **Nhưng kiểm tra catalog PostgreSQL:**
  * Index `uq_index_probe_token` **vẫn tồn tại** nguyên vẹn với cùng OID `16416`!
  * Index **vẫn là INVALID** (`indisvalid = false`)!
  * Bảng dữ liệu **vẫn chứa 2 dòng `IRON` trùng lặp**!
- **Kết luận thực nghiệm:** `flyway.repair()` hoàn toàn **không** chạm vào database objects và **không** sửa dữ liệu nghiệp vụ. Nó chỉ "xóa ký ức" về việc migration đã thất bại trong bảng lịch sử.

#### Checkpoint 3 (Dọn dẹp Object và Chuẩn hóa Dữ liệu):
- Kỹ sư bắt buộc phải can thiệp thủ công:
  1. Xóa index hỏng: `DROP INDEX nc3_schema.uq_index_probe_token;` (Sau lệnh này, catalog xác nhận `exists = false`).
  2. Chuẩn hóa dữ liệu trùng: `UPDATE nc3_schema.index_probe SET token = 'SILVER' WHERE id = 2;` (Xác nhận duplicate groups rỗng, bảng có 3 dòng riêng biệt: `IRON`, `SILVER`, `GOLD`).
- Kiểm tra lại hash SHA-256 của file `V1`, `V2`, `V2.conf`: Hoàn toàn trùng khớp 100% với ban đầu. Tuyệt đối không sửa script migration.

#### Checkpoint 4 (Retry và Kiểm chứng Enforcement):
- Gọi `flyway.migrate()`: Migration V2 chạy lại thành công rực rỡ (`migrationsExecuted = 1`, `success = true`).
- Kiểm tra catalog: Index mới được tạo với OID mới (`16417`), mang cờ `indisunique = true`, **`indisvalid = true`**, `indisready = true`.
- Kiểm tra tính thực thi (Enforcement):
  * Chèn thử dòng trùng lặp `(4, 'IRON')`: PostgreSQL lập tức từ chối với SQLSTATE **`23505`**! Dữ liệu bảng không bị biến đổi.
  * Chèn dòng hợp lệ `(4, 'DIAMOND')`: Thành công, bảng đạt 4 dòng hoàn hảo.
- Gọi `flyway.validate()`: Thành công (PASS).
- Gọi lại `flyway.migrate()`: Chạy 0 migration, đảm bảo tính lũy kế (idempotent).

---

## 5. Ranh Giới Kỹ Thuật & Giới Hạn Của Thí Nghiệm

1. **Về `CONCURRENTLY` và Lock:**
   - Không mô tả `CONCURRENTLY` là "hoàn toàn không có lock" hay "zero downtime tuyệt đối". Thực tế, `CREATE INDEX CONCURRENTLY` vẫn lấy các khóa nhẹ (`ShareUpdateExclusiveLock`) ở các phase khác nhau, có thể gây chờ đợi nếu có transaction dài đang mở.
   - Trong phiên này, lab kiểm chứng cơ chế catalog, tính toàn vẹn trạng thái và quy trình phục hồi trên bảng nhỏ có kiểm soát; chưa đo lường writer latency hoặc throughput dưới tải nặng.
2. **Về câu lệnh `DROP INDEX`:**
   - Trong lab, ta sử dụng `DROP INDEX <index>` thông thường.
   - Trên môi trường production với bảng lớn, việc drop một INVALID index cũng cần cân nhắc câu lệnh `DROP INDEX CONCURRENTLY` để tránh chặn writes.
3. **Về việc sửa dữ liệu:**
   - Thao tác `UPDATE id=2 SET token = 'SILVER'` là quy tắc giải quyết định sẵn cho fixture của lab. Trên thực tế, dữ liệu trùng lặp phải được đối soát nghiệp vụ trước khi quyết định merge, update hay deactivate.

---

## 6. Bảng Ánh Xạ Câu Hỏi Phỏng Vấn (Question Tracker Mapping)

Phân biệt rõ: **Kịch bản Lab thực tế** (NC1..NC3) đã được đo lường và xác nhận (VERIFIED); **Độ bao phủ câu hỏi phỏng vấn chuẩn** ở mức PARTIAL vì chưa bao quát toàn bộ bài toán phân tán/rolling; và **Năng lực người học** tiếp tục ở trạng thái `AWAITING_CANDIDATE`.

| Mã câu hỏi | Trọng tâm câu hỏi | Trạng thái kịch bản Lab | Mức độ bao phủ câu hỏi | Đánh giá ứng viên |
|:---:|---|:---:|:---:|:---:|
| **SB59** | Flyway/Liquibase quản lý schema; migration lỗi khi deploy | **VERIFIED** (NC1..NC3) | **VERIFIED** (Flyway PostgreSQL 16; chưa lab Liquibase) | `AWAITING_CANDIDATE` |
| **SQL69** | Tạo index bảng lớn ảnh hưởng ghi và deploy | **VERIFIED** (NC1..NC3) | **PARTIAL** (Đã đo SQLSTATE `25001`, INVALID index, recovery; chưa đo writer latency dưới tải lớn) | `AWAITING_CANDIDATE` |
| **SQL68** | Schema mới, dữ liệu cũ, phiên bản code đang chạy | **VERIFIED** (NC2..NC3) | **PARTIAL** (Đã đo duplicate data làm hỏng unique index; chưa kiểm chứng rolling deployment) | `AWAITING_CANDIDATE` |
| **SB60** | Thay schema tương thích code cũ/mới | **VERIFIED** (NC2..NC3) | **PARTIAL** (Chưa thí nghiệm expand-contract deployment đa phiên bản) | `AWAITING_CANDIDATE` |

---

## 7. Kết Luận Kiến Trúc

1. **`CREATE INDEX CONCURRENTLY` là con dao hai lưỡi:** Giúp tránh chặn writes nhưng tước đi lớp đệm an toàn của Transactional DDL. Khi fail, nó để lại "rác" trong database (INVALID index).
2. **`flyway.repair()` không phải là cây gậy thần:** Repair chỉ phục vụ việc đồng bộ lại metadata trong Flyway history. Kỹ sư phải có trách nhiệm dọn dẹp invalid objects và xử lý dữ liệu trước khi thử lại.
3. **Quy trình phục hồi có kiểm chứng trong lab:** Thí nghiệm NC3 đã chứng minh một quy trình cụ thể: (1) Repair metadata qua `flyway.repair()`, (2) Drop invalid object và sửa dữ liệu trùng, (3) Retry migration. Tuyệt đối không sửa file script migration đã chạy. Đây là quy trình kiểm chứng cho kịch bản lab; không diễn đạt thứ tự này là duy nhất đúng cho mọi hệ thống, và trên production với bảng lớn chịu tải, việc dọn dẹp index cần cân nhắc các kỹ thuật như `DROP INDEX CONCURRENTLY` tùy trường hợp cụ thể.
