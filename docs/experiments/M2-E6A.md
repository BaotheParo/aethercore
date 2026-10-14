# M2-E6A: Transactional Idempotency cho Market Purchase trong PostgreSQL

**Mã thí nghiệm:** M2-E6A
**Môi trường:** Java 22, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Maven 3.9.12, Spring Boot 3.3.4
**Trạng thái kiểm thử:** 7/7 acceptance cases PASS (ID1..ID7 + Isolation Guard), toàn bộ reactor tests PASS
**Bằng chứng kiểm chứng:** `docs/evidence/prompt19/idempotency-observations.json`

---

## 1. Vấn Đề Người Dùng & Rủi Ro Thất Bại Mạng (The Network Retry Problem)

Trong các hệ thống phân tán, thương mại điện tử hoặc giao dịch trò chơi trực tuyến, một thao tác mua hàng (Purchase) có thể gặp sự cố mạng tại nhiều ranh giới:
1. Client gửi request mua hàng đến server.
2. Server thực thi giao dịch thành công (trừ kho, tạo đơn hàng trong cơ sở dữ liệu).
3. Khi server gửi phản hồi HTTP 200 OK quay lại client, kết nối TCP bị ngắt (network timeout / dropped connection) hoặc proxy trung gian báo lỗi 504 Gateway Timeout.
4. Client không biết request ban đầu đã thành công hay thất bại. Theo bản năng, client gửi lại (retry) cùng một request mua hàng.

**Rủi ro nghiêm trọng nếu thiếu Idempotency:**
- Nếu không có cơ chế chặn trùng lặp, server sẽ coi request gửi lại như một đơn hàng mới, tiếp tục trừ kho lần thứ 2 và tạo bản ghi purchase thứ 2 (double charge / over-deduction).
- Nếu chỉ dựa vào UUID của bản ghi purchase ở tầng client, client không thể biết được trạng thái của lần mua trước (ví dụ: đã hết hàng `NOT_AVAILABLE` hay đã mua thành công `SUCCESS`) để nhận lại kết quả tương ứng.

---

## 2. Khế Ước Nghiệp Vụ (Business Contract) & Request Fingerprint

### 2.1. Không gian định danh (Scope Key)
Một yêu cầu idempotent được định danh duy nhất bởi bộ ba:
$$\text{Scope Key} = (\text{caller\_id}, \text{operation}, \text{idempotency\_key})$$
- `caller_id` (UUID): Định danh người gọi (được xác thực ở biên ứng dụng). Hai caller khác nhau có quyền sử dụng cùng một idempotency key mà không bị xung đột.
- `operation` (String): Hằng số phía máy chủ `PURCHASE_V1`.
- `idempotency_key` (UUID): Khóa bất biến do client khởi tạo cho mỗi ý định giao dịch.

### 2.2. Dấu vân tay yêu cầu chuẩn tắc (Canonical Request Fingerprint)
Để ngăn chặn hành vi sử dụng lại cùng một idempotency key cho các nội dung đơn hàng khác nhau (Key Reuse Attack / Payload Mismatch), hệ thống tính toán một giá trị fingerprint chuẩn tắc độc lập với platform:
- Định dạng encoding: Binary Big-Endian có phiên bản (Version 1):
  ```text
  writeByte(1)
  writeInt(sku_utf8_length)
  write(sku_utf8_bytes)
  writeInt(quantity)
  ```
- Hàm băm: SHA-256 digested thành chuỗi hex thường 64 ký tự (`[0-9a-f]{64}`).
- Bất biến: Cùng `(caller_id, operation, idempotency_key)` nhưng khác SKU hoặc khác `quantity` sẽ tạo ra fingerprint khác nhau và bị từ chối với ngoại lệ `IdempotencyConflictException`.

### 2.3. Chính sách kết quả nghiệp vụ (Outcome Policy)
- `SUCCESS`: Mua thành công, trừ kho và tạo `purchase_id` (UUID) duy nhất. Replay trả về chính xác cùng `purchase_id` ban đầu và không trừ kho thêm.
- `NOT_AVAILABLE`: Thiếu kho hoặc SKU không tồn tại. Kết quả này được lưu dưới dạng trạng thái cuối cùng (terminal outcome). Khi client retry lại cùng key, hệ thống vẫn trả về `NOT_AVAILABLE` (kể cả sau khi bảng kho đã được restock). Muốn mua kho mới, client bắt buộc phải khởi tạo một idempotency key mới.
- Lỗi SQL / Hạ tầng: Toàn bộ claim và thay đổi kho bị rollback sạch sẽ, không lưu cache lỗi hạ tầng để client có thể retry lại sau khi sự cố khắc phục.

---

## 3. Lược Đồ Cơ Sở Dữ Liệu & Ràng Buộc Rõ Ràng (Schema & Constraints)

Bảng `market_idempotency` được tạo trong migration `V5__create_market_idempotency.sql`:

```sql
CREATE TABLE IF NOT EXISTS market_idempotency (
    caller_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_sku VARCHAR(64) NOT NULL,
    request_quantity INT NOT NULL CHECK (request_quantity > 0),
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROCESSING', 'COMPLETED')),
    result_code VARCHAR(16) CHECK (result_code IN ('SUCCESS', 'NOT_AVAILABLE')),
    purchase_id UUID REFERENCES market_purchase(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (caller_id, operation, idempotency_key),
    CONSTRAINT chk_idempotency_operation CHECK (operation = 'PURCHASE_V1'),
    CONSTRAINT chk_idempotency_state_shape CHECK (
        (state = 'PROCESSING' AND result_code IS NULL AND purchase_id IS NULL)
        OR
        (state = 'COMPLETED' AND (
            (result_code = 'SUCCESS' AND purchase_id IS NOT NULL)
            OR
            (result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)
        ))
    )
);
```

**Đặc điểm kiến trúc:**
- Cột `request_sku` **không có Foreign Key** sang `market_stock(sku)`. Điều này cho phép lưu trữ kết quả `NOT_AVAILABLE` khi khách hàng yêu cầu mua một SKU hoàn toàn không tồn tại trong hệ thống.
- Cột `purchase_id` có Foreign Key sang `market_purchase(id)`, đảm bảo tính toàn vẹn dữ liệu đơn hàng.
- Ràng buộc `chk_idempotency_state_shape` sử dụng tường minh các mệnh đề `IS NOT NULL` và `IS NULL` để kiểm soát chặt chẽ trạng thái của máy trạng thái (state machine): `PROCESSING` không được phép có kết quả; `COMPLETED` bắt buộc phải có `result_code` phù hợp.

---

## 4. Thuật Toán Giao Dịch Bắt Buộc (Claim & Replay Workflow)

Dịch vụ `IdempotentMarketPurchaseService` sử dụng `TransactionTemplate` với cấu hình:
- `propagationBehavior = PROPAGATION_REQUIRED`
- `isolationLevel = ISOLATION_READ_COMMITTED`

### Lưu đồ thực thi chi tiết:
```text
Client Request (callerId, key, sku, quantity)
      │
      ▼
Kiểm tra tính hợp lệ của tham số & Tính Request Fingerprint (SHA-256)
      │
      ▼
Mở Transaction (READ COMMITTED)
Kiểm tra actual connection isolation == TRANSACTION_READ_COMMITTED
      │
      ▼
INSERT INTO market_idempotency (...) VALUES (..., 'PROCESSING', ...)
ON CONFLICT (caller_id, operation, idempotency_key) DO NOTHING
      │
      ├───[Số dòng chèn == 1]───► ĐÃ CHIẾM ĐƯỢC CLAIM (Worker A)
      │                               │
      │                               ▼
      │                         Gọi FixedMarketPurchaseService.purchase(purchaseId, sku, qty)
      │                         (UPDATE market_stock trừ kho + INSERT market_purchase)
      │                               │
      │                               ▼
      │                         UPDATE market_idempotency
      │                         SET state = 'COMPLETED', result_code = ?, purchase_id = ?
      │                         WHERE caller_id = ? AND key = ? AND state = 'PROCESSING'
      │                               │
      │                               ▼
      │                         COMMIT Transaction & Trả về Business Outcome (replayed = false)
      │
      └───[Số dòng chèn == 0]───► XUNG ĐỘT KHÓA (Worker B - Replay hoặc Chờ)
                                      │
                                      ▼
                                Chạy câu lệnh SQL MỚI (New Statement Snapshot):
                                SELECT request_sku, request_quantity, request_fingerprint,
                                       state, result_code, purchase_id
                                FROM market_idempotency WHERE caller_id = ? AND key = ?
                                      │
                                      ▼
                                Kiểm tra payload & fingerprint:
                                Nếu không khớp ──► Ném IdempotencyConflictException
                                      │
                                      ▼
                                Kiểm tra state == 'COMPLETED':
                                Nếu đúng ──► Trả về Stored Outcome (replayed = true)
```

### Vì sao Worker B phải chờ Worker A trong DB (Cơ chế ID3)?
Khi Worker A chèn một dòng vào `market_idempotency` nhưng chưa commit:
- Khóa chính `(caller_id, operation, idempotency_key)` được bảo vệ bởi chỉ mục B-tree duy nhất (Unique Index).
- Khi Worker B gửi câu lệnh `INSERT ... ON CONFLICT DO NOTHING`, PostgreSQL nhận thấy khóa trùng lặp nhưng transaction của Worker A vẫn đang hoạt động (`in-flight`).
- PostgreSQL buộc Worker B phải chuyển sang trạng thái chờ khóa (Lock Wait) trên transaction ID của Worker A.
- Trong lúc này, tiến trình giám sát (Observer) gọi hàm `pg_blocking_pids(B_pid)` sẽ thấy danh sách chứa `A_pid`.
- Khi Worker A commit, Worker B được giải phóng. Câu lệnh `INSERT` của Worker B kết thúc với kết quả 0 dòng chèn. Nhờ mức cô lập `READ COMMITTED`, câu lệnh `SELECT` kế tiếp của Worker B lấy một snapshot mới và đọc được trạng thái `COMPLETED` mà Worker A vừa commit!

---

## 5. Bảng Tổng Hợp Kết Quả Thực Nghiệm ID1–ID7

| Kịch bản | Mô tả tình huống kiểm thử | Hành vi kỳ vọng (Expected) | Kết quả quan sát thực tế (Observed) | Trạng thái |
|:---:|---|---|---|:---:|
| **ID1** | Replay tuần tự mô phỏng mất response sau commit | Trả cùng `purchaseId`, không trừ kho lần 2 (stock 3, purchase 1) | Cùng `purchaseId`, stock 3, purchase 1, `replayed1=false`, `replayed2=true` | **PASS (VERIFIED)** |
| **ID2** | Key reuse với payload khác hoặc tham số không hợp lệ | Đổi quantity/SKU ném `IdempotencyConflictException`; input sai bị chặn | Ném `IdempotencyConflictException`, stock 4 không đổi, 0 orphan record | **PASS (VERIFIED)** |
| **ID3** | Hai request trùng key chờ nhau trong cơ sở dữ liệu | B bị chặn bởi A trên DB unique lock, quan sát qua `pg_blocking_pids` | `observedBlocking=True`, `blockingPidList=[A_pid]`, 2 worker cùng outcome | **PASS (VERIFIED)** |
| **ID4** | Phân tách không gian định danh (Scope Isolation) | Khác caller hoặc key mới tạo các purchase độc lập (stock 7, purchase 3) | 3 purchase IDs khác nhau, stock 7, purchase 3, replay giữ nguyên | **PASS (VERIFIED)** |
| **ID5** | Lỗi SQL sau trừ kho phải rollback toàn bộ claim | Gặp lỗi constraint 23514 thì rollback cả claim, stock và purchase; cho phép retry | `SQLSTATE=23514`, rollback về stock 5, 0 claim; retry thành công về stock 3 | **PASS (VERIFIED)** |
| **ID6** | Replay kết quả `NOT_AVAILABLE` sau khi restock | Key cũ vẫn trả `NOT_AVAILABLE`; key mới mua được hàng (bảo toàn supply 5) | Replay trả `NOT_AVAILABLE`, key mới `SUCCESS`, stock 3, tổng bán 2, bảo toàn 5 | **PASS (VERIFIED)** |
| **ID7** | Outer transaction rollback hủy bỏ cả inner claim | Outer exception rollback sạch sẽ; retry độc lập thành công | Sau rollback stock 5, 0 claim; retry ngoài transaction thành công về stock 3 | **PASS (VERIFIED)** |

---

## 6. Phân Tích Chuyên Sâu Các Câu Hỏi Kiến Trúc (Reverse Learning)

### 1. Race condition ở SELECT-then-INSERT khác gì Claim bằng Unique Constraint?
Nếu ứng dụng kiểm tra bằng cách: `SELECT * FROM idempotency WHERE key = ?` rồi sau đó mới `INSERT`:
- Dưới mức cô lập `READ COMMITTED`, hai luồng đồng thời có thể cùng thực hiện `SELECT` tại cùng một thời điểm, cả hai đều thấy chưa có bản ghi, và cả hai cùng tiến hành trừ kho.
- Ngược lại, việc sử dụng `INSERT ... ON CONFLICT DO NOTHING` dựa trên Unique Constraint của PostgreSQL đẩy quyền phân xử xuống tầng transaction của database engine. Một luồng duy nhất chèn thành công (nhận 1 row affected), trong khi luồng còn lại bị chặn hoặc nhận 0 rows affected. Không có khe hở thời gian (time-of-check to time-of-use) giữa kiểm tra và ghi dữ liệu.

### 2. Vì sao cần một câu lệnh SELECT statement mới sau ON CONFLICT DO NOTHING?
Dưới mức cô lập `READ COMMITTED`, một câu lệnh SQL đơn lẻ hoạt động trên một snapshot cố định được chụp tại thời điểm câu lệnh đó bắt đầu chạy. Nếu gộp `INSERT` và fallback `SELECT` vào chung một câu lệnh CTE, câu lệnh có thể không nhìn thấy dòng dữ liệu do transaction đồng thời vừa commit. Việc tách thành một câu lệnh `SELECT` mới độc lập đảm bảo PostgreSQL lấy một snapshot MVCC mới nhất, nhìn thấy toàn bộ dữ liệu đã được commit trước đó.

### 3. Fingerprint khác Scope Key thế nào? Vì sao quantity khác phải conflict?
- **Scope Key** `(caller_id, operation, idempotency_key)` là địa chỉ định danh của một ý định giao dịch.
- **Fingerprint** là bản tóm lược mật mã của nội dung yêu cầu (`sku` + `quantity`).
- Nếu một client gửi lại cùng một key nhưng thay đổi `quantity` (ví dụ từ 1 thành 5), đây không phải là một thao tác retry hợp lệ mà là một yêu cầu nghiệp vụ hoàn toàn khác đang cố ý tái sử dụng key cũ. Hệ thống bắt buộc phải ném lỗi xung đột `IdempotencyConflictException` để bảo vệ tính nhất quán tài chính.

### 4. Vì sao UUID purchase PK một mình chưa cho client cơ chế replay outcome đầy đủ?
Nếu client chỉ tự sinh UUID cho bản ghi `market_purchase`:
- Khi giao dịch thất bại vì hết hàng (`NOT_AVAILABLE`), không có bản ghi nào được ghi vào `market_purchase`. Client khi retry sẽ không biết lần trước thất bại vì lý do gì.
- Client không thể phân biệt giữa trường hợp "yêu cầu đang xử lý" và "yêu cầu đã hoàn tất". Bảng idempotency chuyên biệt lưu trữ cả trạng thái xử lý lẫn kết quả kinh doanh cuối cùng.

### 5. Commit rồi mất response khác gì SQL transaction rollback?
- **Commit rồi mất response (ID1):** Dữ liệu đã được ghi bền vững vào cơ sở dữ liệu (`stock` đã trừ, `purchase` đã tạo). Khi retry, server **không được phép** thực thi lại logic nghiệp vụ mà chỉ đọc lại kết quả đã lưu và gửi lại cho client.
- **SQL transaction rollback (ID5, ID7):** Mọi thay đổi bị hủy bỏ hoàn toàn trong WAL log, dữ liệu trở về trạng thái ban đầu như chưa có gì xảy ra. Khi retry, server **bắt buộc phải** thực thi lại toàn bộ quy trình từ đầu.

### 6. Vì sao REQUIRED SUCCESS có thể chưa durable khi có outer transaction?
Khi một service được đánh dấu `PROPAGATION_REQUIRED`, nếu nó được gọi bên trong một outer transaction đang hoạt động:
- Việc inner service return `PurchaseResult.SUCCESS` chỉ có nghĩa là câu lệnh SQL đã chạy thành công trong transaction context hiện tại.
- Nếu outer transaction sau đó gặp lỗi và rollback (như được chứng minh trong ID7), toàn bộ công việc của inner service cũng bị rollback theo. Do đó, kết quả trả về của một method tham gia outer transaction chỉ thực sự bền vững khi toàn bộ transaction ngoài cùng đã commit thành công.

### 7. Điều gì được bảo đảm và điều gì chưa được chứng minh trong lab này?
- **Được bảo đảm:** Tính đúng đắn của việc replay kết quả, bảo toàn số lượng tồn kho, ngăn chặn double purchase dưới môi trường đa luồng PostgreSQL có ràng buộc unique và transaction ACID.
- **Chưa chứng minh:** Hệ thống chưa kiểm thử khả năng chịu lỗi khi process crash giữa chừng trong trạng thái `PROCESSING` (chưa có recovery worker), chưa có cơ chế dọn dẹp theo thời gian (TTL / retention policy), và chưa chứng minh tính an toàn đối với các side-effect bên ngoài cơ sở dữ liệu (ví dụ: gửi email hoặc gọi cổng thanh toán bên thứ ba).

### 8. Vì sao NOT_AVAILABLE được lưu lại, còn lỗi SQL bất ngờ thì không?
- `NOT_AVAILABLE` là một **kết quả nghiệp vụ hợp lệ và xác định** (Business Outcome) phản ánh tình trạng hết hàng tại thời điểm yêu cầu. Nó cần được lưu lại để đảm bảo tính tất định khi client retry.
- Lỗi SQL bất ngờ (ví dụ: mất kết nối, lỗi phần cứng, vi phạm ràng buộc kỹ thuật) là **sự cố hạ tầng tạm thời** (Transient Technical Failure). Toàn bộ transaction bị rollback để giải phóng tài nguyên, cho phép client có cơ hội thử lại khi sự cố được khắc phục.

---

## 7. Giới Hạn Của Thí Nghiệm & Khuyến Nghị Sản Xuất

1. **Phạm vi trong cùng Database:** Khế ước idempotency ở đây chỉ bảo vệ các tài nguyên lưu trữ trong PostgreSQL. Đối với các side-effects bên ngoài (gửi webhook, gọi payment provider), cần kết hợp với mẫu thiết kế Transactional Outbox.
2. **Thiếu cơ chế giải phóng Lock hết hạn (Lease Expiration):** Trạng thái `PROCESSING` hiện tại chỉ an toàn khi transaction còn sống. Nếu ứng dụng bị crash trước khi commit, kết nối bị đóng và PostgreSQL tự động rollback claim. Tuy nhiên, nếu xây dựng kiến trúc phân tán đa service, cần bổ sung cơ chế lease timeout và recovery worker.
3. **Chưa có TTL:** Bảng `market_idempotency` sẽ tăng trưởng theo thời gian. Môi trường production cần job dọn dẹp định kỳ (retention window, ví dụ 24-48 giờ).

---

## 9. Đính Chính Kỹ Thuật & Giới Hạn Nghiệm Thu (Prompt 20 Technical Audit)

Sau đợt rà soát kỹ thuật độc lập tại Prompt 20, các điểm sau đây được chính thức đính chính và làm rõ:

| Điểm rà soát | Diễn đạt cũ / Nguy cơ nhầm lẫn | Thực tế mã nguồn & Ranh giới kỹ thuật |
|---|---|---|
| **Tên bảng DB** | Báo cáo cũ từng nhắc `market_idempotency_record`, `market_purchase_order` | Tên bảng thực tế trong migration V3/V5 là `market_idempotency` và `market_purchase`. Không đổi schema chỉ để khớp văn bản. |
| **Trường payload** | Từng nhắc `maxUnitPriceCents` | Command chỉ gồm `caller_id`, `operation`, `idempotency_key`, `sku`, `quantity`. Không tự tiện bịa thêm trường nghiệp vụ. |
| **Xác thực Caller** | "Caller đã được xác thực" | `caller_id` là input tin cậy theo contract tầng ứng dụng; authentication/authorization chưa được triển khai trong lab này. |
| **Payload Mismatch** | Coi mismatch là tấn công cố ý | Khi key trùng nhưng payload khác, hệ thống ném `IdempotencyConflictException`; đây là xung đột tham số nghiệp vụ, không mặc định quy kết là tấn công có chủ đích. |
| **Bảo đảm của Fingerprint** | Tuyệt đối hóa hàm băm | Hàm băm không bảo đảm toán học 100% không đụng độ; service so sánh trực tiếp payload nghiệp vụ gốc. |
| **Outbox & 2PC** | Coi outbox là exactly-once, 2PC là bắt buộc | Outbox không tự mang lại exactly-once external side effects; 2PC không phải yêu cầu mặc định cho mọi API. Phân tích cụ thể transaction/dedup/retention. |
| **Quan sát Lock (ID3)** | Tuyên bố quan sát `ExclusiveLock` trên unique-index tuple | Test thực tế chỉ đo lường sự tồn tại của PID Worker A trong mảng kết quả của `pg_blocking_pids(?::int)`. Khung thời gian worker thiết lập `lock_timeout = '10s'` và `statement_timeout = '15s'`. Không tự dựng kết luận về lock mode chưa đo. |
| **Cơ chế Rollback (ID7)** | Từng nhắc `setRollbackOnly()` | Test ném `RuntimeException` ở outer transaction callback để Spring rollback tự nhiên. |
| **Lỗ hổng CHECK V5** | V5 được coi là hoàn hảo | `CHECK` của V5 gặp lỗ hổng 3-valued logic: `(state='COMPLETED' AND result_code IS NULL AND purchase_id IS NULL)` cho ra `UNKNOWN`, và PostgreSQL `CHECK` chấp nhận `UNKNOWN`. Lỗ hổng này được tái hiện tại MG1 và sửa bằng forward migration V6. |
