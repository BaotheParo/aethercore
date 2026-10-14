# M2-E7A: CHECK/NULL, Flyway Forward Migration và DDL Rollback Trên Dữ Liệu Cũ Không Hợp Lệ

**Mã thử nghiệm:** M2-E7A
**Môi trường thử nghiệm:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Flyway 10.10.0, Spring Boot 3.3.4
**Bằng chứng kiểm chứng:** `docs/evidence/prompt20/migration-observations.json` & `docs/evidence/prompt20/idempotency-observations.json`
**Trạng thái kiểm thử:** 3/3 Acceptance Tests PASS (MG1, MG2, MG3), 10/10 Observation Sets Validated, Full Reactor Clean Run

---

## 1. Giới Thiệu & Mô Hình Tư Duy: Quy Định Mới Trên Kho Hàng Cũ

Hãy tưởng tượng một tổng kho giao dịch hàng hóa đã vận hành nhiều năm. Ban đầu, kho chỉ yêu cầu ghi chép: *"Nếu đơn hàng đang xử lý thì chưa có mã kết quả; nếu đơn hàng đã hoàn thành thì phải có mã kết quả và mã giao dịch mua."*

Tuy nhiên, người soạn thảo quy chế ban đầu đã viết một mệnh đề logic lỏng lẻo. Kết quả là trong kho xuất hiện những kiện hàng được dán nhãn `COMPLETED` nhưng cả hai ô `result_code` và `purchase_id` đều để trống (`NULL`). Không ai phát hiện ra cho đến khi hệ thống đối soát tài chính bị treo.

Khi ban quản lý quyết định ban hành quy chế mới chặt chẽ hơn:
1. **Làm thế nào để áp dụng luật mới mà không làm gián đoạn các giao dịch hợp lệ đang chạy?**
2. **Nếu trong kho vẫn còn những kiện hàng cũ bị lỗi, điều gì sẽ xảy ra khi thanh tra áp dụng quy chế mới?**
3. **Nếu đợt thanh tra thất bại giữa chừng, toàn bộ kho hàng có bị rơi vào trạng thái "dở dang, nửa cũ nửa mới" hay không?**

Thí nghiệm **M2-E7A** giải quyết chính xác bài toán này dưới góc độ kỹ thuật cơ sở dữ liệu:
- Tìm hiểu bản chất của **3-Valued Logic** trong SQL và vì sao mệnh đề `CHECK` lại để lọt dữ liệu rỗng.
- Thực hiện **Forward Migration (V6)** tuân thủ nguyên tắc bất biến (immutability) của Flyway.
- Chứng minh cơ chế **Transactional DDL Rollback** của PostgreSQL khi gặp dữ liệu cũ không hợp lệ.
- Phân biệt ranh giới giữa kiểm thử tự động, chuẩn hóa dữ liệu trên môi trường lab và quy trình xử lý thực tế trên production.

---

## 2. Bản Chất Ba Giá Trị (3-Valued Logic) & Lỗ Hổng CHECK Constraint Trong V5

### 2.1. TRUE, FALSE và UNKNOWN trong SQL

Trong hầu hết các ngôn ngữ lập trình (Java, C++, Python), kiểu boolean chỉ có hai trạng thái: `true` và `false`. Nhưng trong SQL chuẩn và PostgreSQL, luận lý học là **Tam trị (Three-Valued Logic)** gồm:
- `TRUE`
- `FALSE`
- `UNKNOWN` (phát sinh khi so sánh với giá trị `NULL`)

Quy tắc cơ bản của `NULL`:
- Phép so sánh bằng hoặc khác với NULL: `col = NULL` hoặc `col <> 'SUCCESS'` khi `col` là NULL đều trả về **`UNKNOWN`** (không phải `TRUE`, cũng không phải `FALSE`).
- Phép kiểm tra sự hiện diện của NULL: `col IS NULL` khi `col` là NULL trả về **`TRUE`**.
- Phép kiểm tra sự tồn tại giá trị: `col IS NOT NULL` khi `col` là NULL trả về **`FALSE`**.

Bảng chân lý của các toán tử logic khi có `UNKNOWN`:
- `TRUE AND UNKNOWN` -> `UNKNOWN`
- `FALSE AND UNKNOWN` -> `FALSE`
- `TRUE OR UNKNOWN` -> `TRUE`
- `FALSE OR UNKNOWN` -> `UNKNOWN`
- `NOT UNKNOWN` -> `UNKNOWN`

### 2.2. Sự Khác Biệt Giữa WHERE và CHECK Constraint

Đây là điểm mấu chốt thường gây nhầm lẫn nghiêm trọng giữa lập trình viên ứng dụng và kỹ sư cơ sở dữ liệu:

| Ngữ cảnh | Điều kiện chấp nhận bản ghi | Hành vi với `UNKNOWN` |
|---|---|---|
| **Mệnh đề `WHERE`** | Chỉ chấp nhận bản ghi khi điều kiện là `TRUE` | **Loại bỏ (Reject):** Dòng có kết quả `UNKNOWN` sẽ không được trả về trong kết quả truy vấn. |
| **Ràng buộc `CHECK`** | Chấp nhận bản ghi khi điều kiện **KHÔNG PHẢI LÀ `FALSE`** (tức là `TRUE` hoặc `UNKNOWN`) | **Chấp nhận (Accept):** Dòng có kết quả `UNKNOWN` được phép INSERT/UPDATE vào bảng! |

> **Quy chuẩn SQL:** `CHECK` constraint kiểm tra tính toàn vẹn dữ liệu theo nguyên tắc: *"Chỉ từ chối nếu khẳng định điều kiện bị vi phạm một cách chắc chắn (`FALSE`). Nếu không chắc chắn (`UNKNOWN`), cơ sở dữ liệu mặc định cho qua."*

### 2.3. Lỗ Hổng Cụ Thể Trong V5

Trong migration `V5__create_market_idempotency.sql`, ràng buộc `chk_idempotency_state_shape` được định nghĩa như sau:

```sql
CONSTRAINT chk_idempotency_state_shape CHECK (
    (state = 'PROCESSING' AND result_code IS NULL AND purchase_id IS NULL)
    OR
    (state = 'COMPLETED' AND (
        (result_code = 'SUCCESS' AND purchase_id IS NOT NULL)
        OR
        (result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)
    ))
)
```

Giả sử ta chèn một bản ghi bị lỗi (malformed): `state = 'COMPLETED'`, `result_code = NULL`, `purchase_id = NULL`:
1. Nhánh `PROCESSING`: `state = 'PROCESSING'` là `FALSE`. Toàn bộ nhánh là `FALSE`.
2. Nhánh `COMPLETED`: `state = 'COMPLETED'` là `TRUE`.
   - Xét biểu thức con 1: `(result_code = 'SUCCESS' AND purchase_id IS NOT NULL)`
     `result_code = 'SUCCESS'` với `NULL = 'SUCCESS'` cho ra `UNKNOWN`.
     `purchase_id IS NOT NULL` với `NULL IS NOT NULL` cho ra `FALSE`.
     -> `UNKNOWN AND FALSE` = `FALSE`.
   - Xét biểu thức con 2: `(result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)`
     `result_code = 'NOT_AVAILABLE'` với `NULL = 'NOT_AVAILABLE'` cho ra `UNKNOWN`.
     `purchase_id IS NULL` với `NULL IS NULL` cho ra `TRUE`.
     -> `UNKNOWN AND TRUE` = **`UNKNOWN`**!
   - Kết hợp hai biểu thức con: `FALSE OR UNKNOWN` = **`UNKNOWN`**.
3. Kết hợp toàn bộ ràng buộc: `FALSE OR (TRUE AND UNKNOWN)` = `FALSE OR UNKNOWN` = **`UNKNOWN`**!

Vì kết quả là `UNKNOWN` (không phải `FALSE`), PostgreSQL chấp nhận dòng dữ liệu này! Đây chính là **lỗ hổng hình thái (state shape flaw)** mà thí nghiệm MG1 đã tái hiện thành công.

---

## 3. NOT NULL Cứng vs Ràng Buộc Phụ Thuộc Trạng Thái

Tại sao ta không đơn giản đặt `result_code VARCHAR(16) NOT NULL` ngay từ đầu?

Bởi vì `market_idempotency` là một **máy trạng thái (state machine)**:
- Khi request vừa tới, trạng thái là `PROCESSING`: tại thời điểm này, giao dịch chưa hoàn tất, kho hàng chưa được trừ, chưa thể biết kết quả thành công hay thất bại. Do đó, `result_code` và `purchase_id` **bắt buộc phải là `NULL`**.
- Khi giao dịch hoàn tất, trạng thái chuyển sang `COMPLETED`: lúc này `result_code` **bắt buộc phải có giá trị** (`SUCCESS` hoặc `NOT_AVAILABLE`).

Nếu đặt `NOT NULL` ở cấp độ cột:
- Ta không thể lưu được bản ghi ở trạng thái `PROCESSING`.
- Hoặc ta bị ép buộc phải bịa ra các giá trị giả tạo như `result_code = 'PENDING'`, làm ô nhiễm miền giá trị nghiệp vụ và phức tạp hóa các câu lệnh truy vấn báo cáo.

Giải pháp chuẩn mực là: Cho phép cột mang giá trị `NULL` ở cấp độ schema vật lý, nhưng kiểm soát chặt chẽ mối liên hệ giữa các cột theo trạng thái thông qua `CHECK` constraint.

Trong `V6__fix_market_idempotency_state_shape.sql`, ta sửa lại mệnh đề nhánh `COMPLETED`:

```sql
ALTER TABLE market_idempotency DROP CONSTRAINT chk_idempotency_state_shape;

ALTER TABLE market_idempotency ADD CONSTRAINT chk_idempotency_state_shape CHECK (
    (state = 'PROCESSING' AND result_code IS NULL AND purchase_id IS NULL)
    OR
    (state = 'COMPLETED' AND result_code IS NOT NULL AND (
        (result_code = 'SUCCESS' AND purchase_id IS NOT NULL)
        OR
        (result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)
    ))
);
```

Nhờ có `result_code IS NOT NULL`:
- Nếu `result_code` là NULL, điều kiện `result_code IS NOT NULL` trả về `FALSE`.
- `FALSE AND (...)` lập tức biến toàn bộ nhánh `COMPLETED` thành `FALSE`.
- Biểu thức tổng thể là `FALSE OR FALSE` = **`FALSE`**.
- Bản ghi malformed bị PostgreSQL từ chối dứt khoát với mã lỗi SQLSTATE `23514` (`check_violation`).

---

## 4. Tại Sao Phải Forward Migration (V6) Thay Vì Sửa V5?

Một phản xạ sai lầm rất phổ biến của lập trình viên là: *"File V5 viết sai thì mở file `V5__create_market_idempotency.sql` ra sửa lại constraint, rồi commit lại Git."*

Trong phát triển phần mềm doanh nghiệp, cách làm này bị **nghiêm cấm tuyệt đối** vì các lý do sau:

1. **Nguyên tắc Bất biến (Immutability):** Migration một khi đã được merge vào nhánh chính (`main`) và đã được deploy lên bất kỳ môi trường nào (dev, staging, production) thì nội dung file đó là bất biến.
2. **Cơ chế Checksum của Flyway:**
   - Khi Flyway thực thi một script migration, nó tính toán một giá trị checksum theo thuật toán **CRC32** (`java.util.zip.CRC32`) dựa trên các dòng văn bản của file và lưu vào bảng `flyway_schema_history`.
   - Lưu ý quan trọng về thuật ngữ: Giá trị checksum của Flyway 10.10.0 là số nguyên CRC32 (ví dụ: `1540923812`), hoàn toàn phân biệt với chuỗi băm byte-level SHA-256 (ví dụ: `a4f8c9...`) mà bộ harness của lab tính toán trong hàm `computeV6ResourceHash()` để chứng thực tính toàn vẹn của file trên ổ đĩa.
   - Nếu bạn sửa nội dung file V5 trên đĩa, trong lần khởi động tiếp theo, Flyway gọi `validate()`: nó so sánh CRC32 của file V5 mới với CRC32 đã ghi trong `flyway_schema_history`. Phát hiện lệch checksum, Flyway sẽ ném ngoại lệ `ValidateException` và dừng toàn bộ ứng dụng ngay lập tức!
3. **Phân kỳ môi trường (Environment Drift):** Database production đã chạy V5 cũ sẽ không bao giờ chạy lại V5 nữa (vì đã có bản ghi `success = true` trong lịch sử). Database mới tạo lại chạy theo V5 mới. Hai môi trường sẽ mang hai cấu trúc constraint khác nhau dù cùng mang danh là "ở version 5".

**Quy tắc vàng:** Lỗi ở version $N$ phải được sửa bằng một forward migration ở version $N+1$.

---

## 5. DDL Rollback Trong PostgreSQL & Giới Hạn Của Flyway

### 5.1. PostgreSQL Hỗ Trợ Transactional DDL

PostgreSQL là một trong số ít các hệ quản trị cơ sở dữ liệu hiện đại hỗ trợ **Transactional DDL** một cách toàn diện (khác với MySQL hay Oracle nơi hầu hết các câu lệnh DDL đều ngầm kích hoạt commit và không thể rollback).

Trong PostgreSQL, bạn có thể thực hiện:
```sql
BEGIN;
ALTER TABLE market_idempotency DROP CONSTRAINT chk_idempotency_state_shape;
ALTER TABLE market_idempotency ADD CONSTRAINT chk_idempotency_state_shape CHECK (...);
-- Nếu có lỗi xảy ra tại đây:
ROLLBACK;
```
Lệnh `ROLLBACK` sẽ hoàn tác toàn bộ: constraint cũ được phục hồi, bảng trở lại trạng thái nguyên vẹn như trước khi mở transaction.

### 5.2. Chuyện Gì Xảy Ra Khi Bảng Chứa Dữ Liệu Cũ Không Hợp Lệ?

Khi chạy câu lệnh `ALTER TABLE ... ADD CONSTRAINT ... CHECK (...)`:
PostgreSQL sẽ thực hiện quét toàn bộ các dòng hiện có trong bảng (table scan) để xác thực (validate) dữ liệu cũ có tuân thủ ràng buộc mới hay không.

Nếu phát hiện dù chỉ **một dòng duy nhất** vi phạm:
1. Câu lệnh `ALTER TABLE` ném ngoại lệ vi phạm ràng buộc (SQLSTATE `23514`).
2. Do Flyway mặc định bọc toàn bộ script migration trong một transaction (`BEGIN ... COMMIT`), transaction gặp lỗi bị hủy bỏ.
3. PostgreSQL tự động rollback toàn bộ transaction:
   - Lệnh `DROP CONSTRAINT` trước đó bị hủy bỏ, constraint V5 ban đầu được bảo toàn nguyên vẹn.
   - Không có thay đổi nào được áp dụng vào catalog của PostgreSQL.
4. Flyway bắt được ngoại lệ SQL:
   - Vì toàn bộ transaction bị rollback, câu lệnh ghi log vào `flyway_schema_history` cũng bị rollback theo!
   - Bảng `flyway_schema_history` **hoàn toàn không có dòng nào của version 6** (không có cả dòng failed lẫn dòng success). Trạng thái schema history vẫn dừng lại ở version 5 nguyên thủy.

### 5.3. Bằng Chứng Thực Nghiệm Từ Lab MG3

Kịch bản **MG3** đã chứng minh thực nghiệm cơ chế này:
- Schema `mg3_schema` đang ở V5, được cố tình chèn 1 dòng hợp lệ (`COMPLETED/SUCCESS/purchaseId`) và 1 dòng malformed (`COMPLETED/null/null`).
- Chạy `flywayV6.migrate()`: Quá trình thất bại chính xác với SQLSTATE `23514`.
- Mở kết nối mới kiểm tra:
  - Cả 2 dòng dữ liệu (hợp lệ và malformed) vẫn còn nguyên vẹn trong bảng (`rowsBefore == rowsAfter`).
  - Định nghĩa constraint hiện tại trùng khớp 100% với định nghĩa V5 ban đầu (`v5ConstraintDef.equals(restoredDef)`).
  - Thử nghiệm chèn một bản ghi vi phạm cấu trúc V5 (`state = 'COMPLETED', result_code = 'SUCCESS', purchase_id = NULL`) bị PostgreSQL từ chối với SQLSTATE `23514` và tên constraint `chk_idempotency_state_shape`, chứng minh constraint V5 vẫn đang bảo vệ bảng.
  - Bảng `flyway_schema_history` có đúng 5 dòng (V1..V5), số lượng bản ghi V6 là 0 (`v6HistoryCountAfterFailure = 0`).

---

## 6. Ý Nghĩa Của `migrate`, `validate`, và `repair`

Ba lệnh cốt lõi của Flyway có ranh giới và trách nhiệm hoàn toàn khác nhau:

| Lệnh Flyway | Trách nhiệm chính | Hành vi trên PostgreSQL trong kịch bản MG3 |
|---|---|---|
| **`migrate`** | Quét các script chưa thực thi và chạy tuần tự theo phiên bản | Khi gặp lỗi ở V6, transactional DDL rollback giúp database và history sạch sẽ. Sau khi dọn dữ liệu lỗi, gọi lại `migrate()` thành công ngay mà không cần can thiệp metadata. |
| **`validate`** | Kiểm tra tính tương thích giữa các file migration trên classpath và lịch sử trong DB (so sánh version, description, checksum CRC32) | Xác nhận các file V1..V5 không bị sửa đổi trái phép và không có migration nào bị bỏ sót hoặc phân kỳ. |
| **`repair`** | Sửa chữa metadata trong bảng `flyway_schema_history` (xóa các dòng failed, cập nhật lại checksum nếu có thay đổi được phê duyệt) | **Không cần dùng trong MG3**, vì PostgreSQL đã rollback sạch dòng V6. (Lưu ý: `repair` sẽ tối quan trọng trong các trường hợp non-transactional migration như `CREATE INDEX CONCURRENTLY` ở module M2-E7B). |

---

## 7. Xử Lý Dữ Liệu Lỗi Trên Production: Không Tự Ý Phỏng Đoán

Trong bài test lab `MarketIdempotencyMigrationTest.java`, sau khi migration V6 thất bại, ta đã thực hiện xóa dòng malformed bằng câu lệnh:
```sql
DELETE FROM market_idempotency WHERE caller_id = ? AND idempotency_key = ?;
```

> **CẢNH BÁO KIẾN TRÚC NGHIỆP VỤ:**
> Thao tác `DELETE` trong bài test là để phục vụ kịch bản kiểm thử tự động trên một fixture cô lập có key xác định. **Đây tuyệt đối không phải là khuyến nghị hay cẩm nang vận hành trên môi trường Production!**

Trên hệ thống thật, nếu migration gặp lỗi dữ liệu:
1. **Tuyệt đối không chạy lệnh `DELETE` bừa bãi:** Một bản ghi `COMPLETED` bị thiếu `result_code` có thể là kết quả của một giao dịch thanh toán thực tế của khách hàng nhưng bị lỗi mạng ở bước ghi nhận kết quả. Xóa bản ghi có thể dẫn tới mất dấu vết kiểm toán (audit trail), gây sai lệch số dư tài khoản hoặc khiến khách hàng bị trừ tiền mà không nhận được hàng.
2. **Quy trình chuẩn hóa dữ liệu 3 bước trên Production:**
   - **Bước 1: Điều tra và Đối soát (Investigation):** Truy vấn chéo với bảng thanh toán bên thứ ba (payment gateway) hoặc bảng đơn hàng `market_purchase` để xác định bản chất thực của giao dịch.
   - **Bước 2: Cập nhật dữ liệu hợp lệ (Data Remediation Script):** Nếu giao dịch thực tế đã thành công, chạy script cập nhật bổ sung `result_code = 'SUCCESS'` và `purchase_id` tương ứng. Nếu giao dịch thực tế đã hủy, cập nhật `result_code = 'NOT_AVAILABLE'`.
   - **Bước 3: Phê duyệt nghiệp vụ (Sign-off):** Mọi script sửa đổi dữ liệu cũ phải được review bởi Data Lead và Product Owner trước khi chạy trên Production.

---

## 8. Bảng Tổng Hợp Kiểm Nghiệm Thực Tế (MG1 – MG3)

Toàn bộ kết quả kiểm chứng được trích xuất từ `docs/evidence/prompt20/migration-observations.json`:

| Case ID | Mục tiêu kiểm chứng | Trạng thái mong đợi (Expected) | Kết quả quan sát thực tế (Observed) | SQLSTATE | Kết luận |
|---|---|---|---|:---:|:---:|
| **MG1** | Tái hiện lỗi V5 & Forward Migration V6 bảo toàn dữ liệu | V5 nhận malformed row; V6 nâng cấp thành công; 3 dòng hợp lệ được bảo toàn nguyên vẹn; chạy lại migrate 0 script | `v5MalformedAccepted = true`, `v6Success = true`, `validDataPreserved = true`, `repeatMigrationCount = 0`, V1-V5 checksums không đổi | - | **PASS** |
| **MG2** | Bảng chân lý 8 tổ hợp sau V6 & Chặn đứng thao tác UPDATE sai | Chấp nhận 3 tổ hợp đúng; từ chối 5 tổ hợp sai; chặn UPDATE thành malformed shape | Chấp nhận đúng 3 dạng; 5 dạng sai bị từ chối với SQLSTATE `23514`; lệnh UPDATE bị chặn, dữ liệu cũ giữ nguyên | `23514` | **PASS** |
| **MG3** | DDL Rollback khi dữ liệu cũ vi phạm & Retry không đổi file | V6 fail do dữ liệu; DDL rollback khôi phục constraint V5; history không chứa V6; xóa fixture lỗi & retry thành công | `migrationFailureSqlState = 23514`, `oldConstraintRestored = true`, `v6HistoryCountAfterFailure = 0`, `retrySuccess = true`, V6 file hash không đổi | `23514` | **PASS** |

---

## 9. Kết Luận Kiến Trúc

1. **Hiểu rõ 3-Valued Logic là bắt buộc đối với kỹ sư Backend/Data:** Luôn lường trước khả năng điều kiện trả về `UNKNOWN` khi viết các ràng buộc `CHECK`. Một ràng buộc không bao hàm `IS NOT NULL` rất dễ trở thành "cửa sau" tiếp tay cho dữ liệu rác.
2. **Tôn trọng tính bất biến của Migration:** Không bao giờ sửa các migration đã commit. Luôn tiến về phía trước bằng Forward Migration để đảm bảo tính nhất quán trên toàn bộ các môi trường.
3. **Tận dụng sức mạnh của PostgreSQL Transactional DDL:** Hiểu rõ cơ chế tự bảo vệ của PostgreSQL giúp kỹ sư tự tin triển khai các đợt nâng cấp schema phức tạp, biết chính xác khi nào hệ thống an toàn và khi nào cần can thiệp chuẩn hóa dữ liệu.
