# Nhật Ký Thực Thi Prompt 18: M2-E5C — LIKE, lower() và Expression Index trong PostgreSQL

**Mã phiên:** Prompt 18 — M2-E5C
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Git Baseline HEAD:** `ad8c5bfa8b915911712a63b36b38a1132b7e4053` (Commit `feat(prompt-17): verify keyset pagination and deep offset trade-offs`)
**Evidence Run ID:** `prompt18-20261013-202011-995f61`
**Kết quả kiểm thử:**
- Python collector: 17/17 tests PASS
- Targeted suite (PostgresPredicateIndexExperimentsTest + PostgresKeysetPaginationExperimentsTest + PostgresQueryPlanExperimentsTest): 12/12 tests PASS (18,2s)
- Full Reactor: 105/105 tests PASS (Core Lab: 34 tests, Backend App: 71 tests, 0 failures, 0 errors, 0 skipped, 44,3s)

---

## 1. Đính Chính Kỹ Thuật và Chuẩn Hóa Ranh Giới Assertion (Phần A)

Trong phiên này, các hoàn thiện kỹ thuật cho bài lab Keyset Pagination (Prompt 17) đã được triển khai đầy đủ:

1. **Oracle độc lập trong KP1:**
   - Thay thế việc copy kết quả từ OFFSET sang con trỏ Keyset bằng danh sách Oracle mong đợi độc lập:
     - Trang đầu (`OFFSET 0`): `[100000, 99999, ..., 99981]` (20 IDs).
     - Trang sâu (`OFFSET 80000` và `Keyset Seek`): `[20000, 19999, ..., 19981]` (20 IDs).
   - Thêm các assertion nghiêm ngặt về projection (`payload`, `created_at`, `buyer_id = 42`).
2. **Loại bỏ giả định biên giả tạo (Sentinel/Wide-threshold assertions):**
   - Đánh giá giả thuyết trong KP1 dựa trên việc trích xuất và phân tích trực tiếp cấu trúc cây kế hoạch thực thi:
     - Trang sâu OFFSET 80000: Node quét là `Index Scan` trên `idx_kst_buyer_recent` với `actual rows = 80020`, `loops = 1`, sau đó node `Limit` loại bỏ 80.000 dòng.
     - Keyset Seek: Node quét là `Index Scan` trên `idx_kst_buyer_recent` với `Index Cond` chứa con trỏ biên tuple, `actual rows = 20`, `loops = 1`.
3. **Chính xác hóa số lượng Fixture:**
   - Fixture tổng cộng có **100.005 dòng**: gồm 100.000 dòng của `buyer_id = 42` và 5 dòng cô lập của `buyer_id = 99` để kiểm tra độ chọn lọc của chỉ mục tổng hợp.
4. **Mô hình giao dịch tự động (Per-statement autocommit) trong KP3/KP4:**
   - Làm rõ rằng các truy vấn lật trang mô phỏng request HTTP độc lập, mỗi truy vấn chạy trong một giao dịch autocommit riêng biệt dưới mức cô lập `READ COMMITTED`. Khi có giao dịch khác chèn/xóa dòng giữa các lần lật trang, hiện tượng trôi vị trí (drift) xảy ra.
5. **Sửa lỗi logic trong Evidence Collector:**
   - Điều kiện kiểm tra `Actual Rows` và `Actual Loops` trong `scripts/collect_lab_evidence.py` được chuyển từ toán tử `and` sang kiểm tra bắt buộc cả hai (bác bỏ nếu thiếu bất kỳ chỉ số nào trong hai chỉ số).
   - Bổ sung xác thực kiểm tra tính tương đương giữa `expectedIds` và `returnedIds`.

---

## 2. Thiết Kế và Triển Khai Lab M2-E5C (Phần B)

### 2.1. Schema với Collation Tường Minh `"C"`
Tạo probe DDL trong `backend-app/src/test/resources/sql/predicate-index-probe.sql`:
```sql
CREATE TABLE predicate_trade (
    id           BIGINT PRIMARY KEY,
    display_name TEXT COLLATE "C" NOT NULL,
    payload      TEXT NOT NULL
);
```

### 2.2. Fixture 100.001 Dòng Dữ Liệu Xác Định
- 1..100: Tiền tố `Redstone-000001` .. `Redstone-000100` (khớp prefix và contains).
- 101..200: Chứa chuỗi `Mid-Redstone-000101` .. `Mid-Redstone-000200` (chỉ khớp contains).
- 50.000: `Redstone-050000` (chữ hoa/thường chuẩn).
- 100.001: `rEdStOnE-050000` (mixed-case xen kẽ).
- Các dòng còn lại: `Trade-000201` .. `Trade-100000`.

### 2.3. Bốn Kịch Bản Thực Nghiệm (IP1..IP4)
Triển khai trong `PostgresPredicateIndexExperimentsTest.java`:
1. **IP1 (Prefix LIKE `Redstone-%`):**
   - Trước index (`prefix_no_index`): `Seq Scan` trên `predicate_trade`, Cost 3576.01, 100 rows, 2.326 buffer hits, 6,480 ms.
   - Sau khi tạo `idx_pt_name` (`prefix_btree`): `Index Scan` trên `idx_pt_name`, Cost 8.44, 100 rows, 104 buffer hits, 0,113 ms. PostgreSQL tự động biến đổi thành `Index Cond: ((display_name >= 'Redstone-') AND (display_name < 'Redstone.'))`.
2. **IP2 (Contains LIKE `%Redstone%`):**
   - Với `idx_pt_name`: Ordinary B-tree không thể định vị seek range cho leading wildcard `%`. Kế hoạch thực thi là `Seq Scan` với Filter `(display_name ~~ '%Redstone%')`, Cost 3576.01, 200 rows, 2.326 buffer hits, 7,358 ms.
3. **IP3 (lower(display_name) = ? với Ordinary Index):**
   - Với `idx_pt_name`: B-tree index trên cột gốc bị bỏ qua. Kế hoạch là `Seq Scan` với Filter `(lower(display_name) = 'redstone-050000')`, Cost 3826.01, 2 rows (`[50000, 100001]`), 2.326 buffer hits, 12,926 ms.
   - Semantics control: Truy vấn `WHERE display_name = 'redstone-050000'` trả về 0 rows, chứng minh việc bỏ `lower()` làm mất dữ liệu.
4. **IP4 (lower(display_name) = ? với Expression Index):**
   - Tạo `idx_pt_lower_name(lower(display_name))`: Kế hoạch chuyển thành `Index Scan` trên `idx_pt_lower_name`, Cost 8.44, 2 rows, 5 buffer hits, 0,021 ms. Buffer hits giảm từ 2.326 xuống 5, thời gian giảm từ 12,9 ms xuống 0,021 ms.

---

## 3. Evidence Collection và Regression (Phần C)

1. Cập nhật `scripts/collect_lab_evidence.py` để bổ sung xác thực cho `predicate-index-observations.json` (kiểm tra đầy đủ 4 scenarios, 5 measurements, schemaVersion, collation, runId, raw plan với actual rows và actual loops).
2. Mở rộng `scripts/tests/test_collect_lab_evidence.py` lên 17 tests:
   - Thêm unit test kiểm tra valid predicate evidence payload.
   - Thêm unit test kiểm tra từ chối khi thiếu một measurement trong IP1.
   - Thêm unit test kiểm tra từ chối khi thiếu `Actual Rows` hoặc `Actual Loops` riêng lẻ.
   - Thêm unit test kiểm tra từ chối khi `expectedIds` không khớp `returnedIds`.
3. Chạy `python scripts/collect_lab_evidence.py --run --prompt prompt18`:
   - Maven build reactor thành công toàn diện: 105 tests run, 0 failures.
   - Evidence bundle được promote vào `docs/evidence/prompt18/` gồm 8 file observations, manifest, summary, và maven log.

---

## 4. Nghiệm Thu Các Gates (A-F)

- [x] **Gate A:** KP1 có independent oracles `[100000..99981]` và `[20000..19981]`; thiếu riêng một actual metric (`Actual Rows` hoặc `Actual Loops`) bị từ chối; docs không gọi buffer count là physical IO.
- [x] **Gate B:** Dataset 100.001 rows xác định, Collation `"C"` tường minh; index inventory được tách biệt theo từng test case.
- [x] **Gate C:** Prefix trả về 100 rows, contains trả về 200 rows, lower trả về 2 rows, raw lowercase equality trả về 0 rows; khớp chính xác toàn bộ IDs.
- [x] **Gate D:** 5 raw measured plans; phân biệt rõ `Index Cond` vs `Filter` và index existence vs index usage; không dùng gợi ý ép optimizer (`enable_seqscan = off`).
- [x] **Gate E:** Python unit tests (17/17), targeted tests (12/12), và full reactor (105/105) đều đạt; bộ evidence mới hợp lệ; toàn bộ suite cũ tiếp tục chạy thành công.
- [x] **Gate F:** Tài liệu `M2-E5C.md` có đầy đủ giới hạn kết luận; `question-tracker.md` cập nhật đúng; bài tập `M2-E5C-exercise.md` giữ nguyên trạng thái `[AWAITING_CANDIDATE]`.