# Nhật Ký Thực Thi Prompt 17: M2-E5B — Keyset Pagination và Đánh Đổi với Deep OFFSET

**Mã phiên:** Prompt 17 — M2-E5B
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Git Baseline HEAD:** `72dd1b6b6d1542159f7dc40143efffdfe2716a3d` (Commit feat prompt-16)
**Evidence Run ID:** `prompt17-20261013-191256-f0cf3e`
**Kết quả kiểm thử:** Reactor 101/101 tests PASS (Core: 34, Backend: 67) | Python collector: 15/15 tests PASS

---

## 1. Đính Chính Kỹ Thuật và Chuẩn Hóa Ranh Giới Assertion (Phần A)

Nhằm đảm bảo tính trung thực học thuật và khớp hoàn toàn với raw evidence, các điểm diễn giải kỹ thuật từ Prompt 16 đã được chuẩn hóa lại trong `docs/experiments/M2-E5A.md`, `prompt16-execution-notes.md` và mã kiểm thử:

1. **Thuật toán sắp xếp trong EP1/EP2:**
   Raw EXPLAIN JSON thực tế ghi nhận `Sort Method: quicksort` (không tự suy diễn là `top-N heapsort`). Raw evidence lịch sử được bảo toàn nguyên vẹn.
2. **Bản chất của Buffer Hits:**
   `Shared Hit Blocks` đo số lần engine tìm thấy block trong shared buffers; không phản ánh số lần đọc đĩa vật lý hay số trang vật lý duy nhất. Không cộng dồn parent hit và child hit thành tổng vì chỉ số node cha đã bao gồm node con.
3. **Ý nghĩa của Shared Read Blocks:**
   `Shared Read Blocks` phản ánh các block không có trong shared buffers của database engine và phải gọi hàm đọc hệ thống (`read()`), nhưng các block này vẫn có thể được thỏa mãn bởi OS Page Cache trước khi chạm vào phần cứng đĩa.
4. **Bản chất của Plan Width:**
   `Plan Width` là ước lượng của optimizer về độ rộng dòng xuất (output width) trong bộ nhớ phục vụ tính toán chi phí, không phải kích thước lưu trữ vật lý của hàng trên đĩa.
5. **Node chỉ mục ở EP2:**
   Chỉ mục được định vị tại node con `Bitmap Index Scan`. Lớp trích xuất dữ liệu không suy diễn sai lệch chỉ vì thuộc tính `indexName` ở node gốc là null.
6. **Tách bạch tính đúng đắn ngữ nghĩa và giả thuyết optimizer (Phần A2):**
   Trong `PostgresQueryPlanExperimentsTest.java`, các assertion về tính đúng đắn dữ liệu (số dòng, mã định danh, sự tồn tại của index, kết quả parse plan) được giữ vững tuyệt đối. Trong khi đó, việc optimizer lựa chọn node nào (Index Scan vs Bitmap Scan vs Seq Scan) được đánh giá linh hoạt thành `hypothesisStatus = MATCHED | DIFFERENT`, ghi nhận trung thực hiện trạng mà không làm gãy test bộ điều phối.

---

## 2. Thiết Kế và Thực Thi Thí Nghiệm M2-E5B (Phần B)

### 2.1. Cấu trúc Lược đồ và Fixture
- Tạo file SQL `backend-app/src/test/resources/sql/keyset-pagination-probe.sql` chứa bảng thử nghiệm `keyset_trade` với composite index `idx_kst_buyer_recent(buyer_id, created_at DESC, id DESC)`.
- Bảng chứa cột `payload` có kích thước ~156 bytes để mô phỏng tải I/O thực tế.
- Khởi tạo fixture lớn với 100.000 dòng thuộc `buyer_id = 42` và fixture nhỏ 24 dòng có các nhóm trùng timestamp.

### 2.2. Bốn Kịch Bản Thực Nghiệm Bắt Buộc (KP1..KP4)
Được triển khai trong `PostgresKeysetPaginationExperimentsTest.java`:
1. **KP1 — So sánh cùng một trang sâu giữa OFFSET và Keyset:**
   - Trên bảng 100.000 dòng với `pageSize = 20`.
   - Trang đầu: OFFSET 0.
   - Trang sâu: OFFSET 80.000 vs Keyset với boundary ID 20001.
   - Cả hai câu truy vấn trang sâu đều trả về **chính xác cùng 20 IDs giảm dần: `[20000..19981]`**.
   - Node `Index Scan` ở Deep OFFSET quét qua **80.020 dòng** (Limit vứt bỏ 80.000 dòng), tốn 2.591 blocks bộ đệm.
   - Node `Index Scan` ở Deep Keyset định vị thẳng bằng `Index Cond`, chỉ nạp đúng **20 dòng**, tốn 5 blocks bộ đệm.
2. **KP2 — Cạm bẫy trùng timestamp và Cursor một cột:**
   - Fixture 24 dòng, cứ 10 dòng cùng 1 timestamp, `pageSize = 7`.
   - Cursor đơn `created_at < cursorTime` trên Trang 2 bỏ qua toàn bộ 7 bản ghi `[17..11]` trong nhóm trùng timestamp.
   - Composite Tuple Cursor `(created_at, id) < (?, ?)` duyệt tuần tự toàn bộ 24 bản ghi qua các trang `[7, 7, 7, 3]` mà không bị lặp hay sót bản ghi nào.
3. **KP3 — Hiện tượng lặp dòng khi INSERT đồng thời:**
   - Sử dụng 2 connection độc lập ở mức cô lập `READ COMMITTED`.
   - Reader đọc Trang 1 `[24..20]`. Writer chèn bản ghi mới ID 25 (+3s) và COMMIT.
   - Reader đọc Trang 2 bằng `OFFSET 5`: ID 20 bị lặp lại ở đầu trang.
   - Reader đọc Trang 2 bằng Keyset: danh sách trả về là `[19..15]`, hoàn toàn không bị trùng lặp.
4. **KP4 — Hiện tượng mất dòng khi DELETE đồng thời:**
   - Reader đọc Trang 1 `[24..20]`. Writer xóa bản ghi ID 24 và COMMIT.
   - Reader đọc Trang 2 bằng `OFFSET 5`: bản ghi ID 19 bị trượt qua và biến mất khỏi danh sách.
   - Reader đọc Trang 2 bằng Keyset: danh sách trả về là `[19..15]`, bản ghi ID 19 vẫn được hiển thị đầy đủ.

---

## 3. Tích Hợp Evidence Collector và Kiểm Thử Hồi Quy (Phần C)

1. **Cập nhật `scripts/collect_lab_evidence.py`:**
   - Bổ sung schema validator cho `keyset-pagination-observations.json`.
   - Kiểm tra chặt chẽ sự hiện diện của 4 case `KP1`, `KP2`, `KP3`, `KP4`.
   - Kiểm tra KP1 chứa đầy đủ 3 measurements (`first_page`, `deep_offset`, `deep_keyset`), raw explain plan với `Actual Rows` và `Actual Loops`, và danh sách IDs của deep offset bằng deep keyset.
2. **Mở rộng `scripts/tests/test_collect_lab_evidence.py`:**
   - Bổ sung các ca kiểm thử: fixture hợp lệ, thiếu case, sai runId, thiếu measurement, danh sách IDs trang sâu bị lệch nhau.
   - Tổng cộng 15 unit tests Python đều PASS (100%).
3. **Quy trình thực thi và kiểm định:**
   - Targeted tests: `PostgresKeysetPaginationExperimentsTest` và `PostgresQueryPlanExperimentsTest` hoàn thành trong 16.587s (8 tests PASS).
   - Full Reactor test: Toàn bộ 101 tests (34 core + 67 backend) đều PASS, 0 failures, 0 errors, 0 skipped.
   - Promotion: Bằng chứng được lưu vào `docs/evidence/prompt17/` (Run ID: `prompt17-20261013-191256-f0cf3e`).

---

## 4. Xử Lý Sự Cố Môi Trường Thực Tế

- **Sự cố:** Do máy chủ vừa khởi động lại, dịch vụ Docker Desktop daemon bị gián đoạn, khiến kết nối Testcontainers qua named pipe `\\.\pipe\docker_engine` bị từ chối (`open \\.\pipe\docker_engine: The system cannot find the file specified`).
- **Khắc phục:** Tiến trình `com.docker.backend.exe` được khởi động lại thành công trong nền; đường ống named pipe phục hồi bình thường. Testcontainers kết nối lại và cấp phát container PostgreSQL 16.15 ổn định. Không can thiệp sửa đổi trái phép cấu hình mạng hoặc phiên bản dependency.

---

## 5. Tổng Kết Số Liệu Đo Đạc Thực Nghiệm KP1..KP4

| Scenario | Thao tác kiểm chứng | Expected Behavior | Observed Result (PostgreSQL 16) | Đánh giá |
|:---:|---|---|---|:---:|
| **KP1** | Deep Offset vs Keyset (100k rows, page size 20) | Cùng trả về IDs `[20000..19981]`; Keyset nạp ít rows hơn | Deep OFFSET: Scan nạp 80.020 rows, Limit discard 80.000 rows, 2.591 hits. Keyset: Scan nạp 20 rows, 5 hits. | **VERIFIED** |
| **KP2** | Duplicate timestamp trap (24 rows, page size 7) | Cursor đơn mất rows; Tuple cursor đủ 24 rows | Cursor đơn nhảy cóc bỏ qua IDs `[17..11]`. Tuple cursor duyệt trọn 24 rows qua 4 trang `[7, 7, 7, 3]`. | **VERIFIED** |
| **KP3** | Concurrent INSERT (24 rows + 1 row, page size 5) | OFFSET bị lặp row; Keyset không lặp | OFFSET 5 trả về ID 20 (lặp từ trang 1). Keyset trả về `[19..15]` (ổn định). | **VERIFIED** |
| **KP4** | Concurrent DELETE (24 rows - 1 row, page size 5) | OFFSET bị mất row; Keyset không mất | OFFSET 5 nhảy cóc bỏ qua ID 19 (mất dòng). Keyset trả về `[19..15]` (ổn định). | **VERIFIED** |
