# Nhật Ký Thực Thi Prompt 16: M2-E5A — Đọc EXPLAIN, Chọn Index và Hiểu Chi Phí Truy Vấn PostgreSQL

**Mã phiên:** Prompt 16 — M2-E5A
**Thời gian thực thi:** 13/10/2026
**Môi trường:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Git Baseline HEAD:** `df943cc17db37a230be9b91a8571bdf4447e8276` (Commit feat prompt-15)
**Evidence Run ID:** `prompt16-20261013-171826-1927c1`
**Kết quả kiểm thử:** Reactor 97/97 tests PASS (Core: 34, Backend: 63) | Python collector: 14/14 tests PASS

---

## 1. Đính Chính Kỹ Thuật (Technical Clarifications)

Nhằm đảm bảo tính trung thực và chuẩn xác học thuật, các nhận định quá rộng hoặc chưa có bằng chứng ở các phiên trước được đính chính như sau:

1. **Phạm vi bảo vệ của Two-Step Pagination:**
   Thuật toán hai bước (*two-step pagination*) giải quyết triệt để vấn đề nhân dòng Cartesian ở cấp độ phân trang thực thể gốc (Root page items). Tuy nhiên, **không thể tuyên bố loại bỏ hoàn toàn nguy cơ tràn heap**: nếu một thực thể gốc (ví dụ một Buyer) sở hữu một danh sách liên kết khổng lồ (hàng chục nghìn Orders), việc fetch join toàn bộ collection của buyer đó trong Bước 2 vẫn có thể gây áp lực bộ nhớ đáng kể.
2. **Khái niệm "Nạp toàn bộ bảng" trong PG1:**
   Trong kịch bản PG1 của Prompt 15, việc Hibernate tải toàn bộ 5 buyers và 10 orders vào heap chỉ đúng với bộ dữ liệu fixture cụ thể (do query không có mệnh đề lọc `WHERE`). Trong trường hợp tổng quát, in-memory pagination sẽ nạp **toàn bộ các dòng thỏa mãn điều kiện lọc của câu query** chứ không nhất thiết là toàn bộ bảng vật lý.
3. **Cơ chế hoạt động của Statement Inspector:**
   `PaginationStatementInspector` và `FetchStatementInspector` trong repo hoạt động theo cơ chế **instance-based gắn với từng SessionFactory / EntityManagerFactory**, không phải là thread-bound instrumentation can thiệp sâu vào bytecode hay toàn cục JVM.
4. **Mức cô lập giao dịch và Tính toàn vẹn:**
   Snapshot của mức cô lập mặc định `READ COMMITTED` có thể gặp hiện tượng non-repeatable read hoặc phantom read giữa 2 bước của truy vấn. Tuy nhiên, việc nâng mức cô lập lên `REPEATABLE READ` hoặc `SERIALIZABLE` là một sự đánh đổi về mặt hiệu năng và nguy cơ serialization failure (40001), không thể gọi là bảo đảm "toàn vẹn tuyệt đối" mà không có cơ chế retry thích hợp.
5. **Ngưỡng tham số SQL (Parameter Limits):**
   Không được lấy con số giả định `pageSize = 1000` làm ngưỡng vượt giới hạn tham số chung cho mọi RDBMS. Mỗi hệ quản trị cơ sở dữ liệu có giới hạn riêng (ví dụ PostgreSQL giới hạn 65.535 tham số bind, SQL Server giới hạn 2.100 tham số, SQLite giới hạn 999 hoặc 32.766 tùy phiên bản).
6. **Không quy kết tốc độ phân trang khi chưa có Execution Plan:**
7. **Hiệu chỉnh chi tiết kỹ thuật cho M2-E5A (đối chiếu Prompt 17):**
   - Thuật toán Sort quan sát thực tế trong raw plan của EP1 và EP2 là `quicksort` (`Sort Method: quicksort`), không tự quy kết là `top-N heapsort`.
   - `Shared Hit Blocks` là số lần tìm thấy block trong buffer cache của database, không phải số lần đọc đĩa vật lý hay số trang vật lý duy nhất.
   - `Shared Read Blocks` cũng không phản ánh trực tiếp I/O thiết bị phần cứng do ảnh hưởng của OS Page Cache.
   - `Plan Width` là ước lượng kích thước output của node trong bộ nhớ, không phải kích thước hàng trên đĩa vật lý.
   - Lựa chọn `Seq Scan` ở EP4 là quyết định dựa trên thống kê và cost model tại cấu hình cụ thể; không tuyên bố "nhanh hơn nhiều lần" đối với phương án chưa chạy thực nghiệm.

   Trong Prompt 15, chưa có phép đo nào về `EXPLAIN ANALYZE` trên câu lệnh phân trang hai bước. Do đó, không được tuyên bố rằng *"index đã giúp phân trang nhanh hơn X%"* trước khi có số liệu thực nghiệm từ Prompt 16.

---

## 2. Các Sửa Đổi Nền Tảng Đã Thực Hiện

### A1. Sửa nhãn tóm tắt và kiểm tra toàn diện điều kiện ALL PASS
- **Vấn đề phát hiện:** `generate_summary_text` trước đây chỉ dựa vào `failures == 0 and errors == 0` để gắn cờ `ALL PASS`, dẫn đến trường hợp build thất bại (`exitCode: 2` hoặc exitCode bị thiếu) nhưng summary vẫn chứa `ALL PASS`.
- **Giải pháp áp dụng:** Cập nhật `collect_lab_evidence.py` yêu cầu đồng thời:
  - `exitCode` phải tồn tại và bằng đúng `0`.
  - `failures == 0`, `errors == 0`, và `skipped == 0`.
  - Tổng số test phải lớn hơn 0 (`total_reactor > 0`).
  - Nếu thiếu exitCode hoặc exitCode != 0: gắn cờ `FAILURES DETECTED` và `BUILD FAILURE (Exit Code: ...)`.
- **Kiểm thử tự động:** Mở rộng `test_collect_lab_evidence.py` với các ca kiểm thử cho exit code khác 0, thiếu exit code, có test bị skipped, và một ca đối chứng PASS (tổng cộng 14 unit tests đều PASS).

### A2. Chặt chẽ hóa kiểm tra nguyên nhân từ chối ở PG2
- **Vấn đề phát hiện:** Trong `JpaCollectionPaginationExperimentsTest.java`, điều kiện kiểm tra nguyên nhân ở kịch bản PG2 trước đây dùng toán tử `||` (`cause instanceof HibernateException || message.contains("collection fetch")`), có thể chấp nhận nhầm ngoại lệ không liên quan nếu message vô tình chứa từ khóa.
- **Giải pháp áp dụng:** Yêu cầu đồng thời trong chuỗi nguyên nhân:
  - `cause instanceof HibernateException`
  - Message phải chứa `"collection fetch"` VÀ chứa ít nhất một từ khóa phân trang: `"pagination"`, `"setfirstresult"`, `"setmaxresults"`, hoặc `"fail_on_pagination_over_collection_fetch"`.
- **Xác nhận:** Đã kiểm thử lại suite PG2 độc lập và trong toàn bộ reactor, xác nhận vượt qua chính xác.

---

## 3. Triển Khai Thí Nghiệm M2-E5A (EP1..EP4)

1. **Lược đồ và Dữ liệu mồi:**
   - Tạo bảng `query_plan_trade` trong file `/sql/query-plan-probe.sql`.
   - Seed 100.000 dòng xác định qua `generate_series` với phân bố 10% PENDING (10.000 dòng) và 90% COMPLETED (90.000 dòng).
   - Kiểm tra toàn bộ 6 assertions về dữ liệu trước khi đo; chạy `ANALYZE query_plan_trade`.
2. **Bộ đọc Execution Plan có ngữ nghĩa:**
   - Xây dựng `ExplainPlanReader.java` duyệt đệ quy cây `Plans` bằng Jackson, trích xuất chi phí, số dòng thực tế, bộ đệm, điều kiện lọc, và kiểu sắp xếp.
3. **Thực thi 4 kịch bản:**
   - **EP1 (Không secondary index):** Q1 thực thi `Limit -> Sort -> Seq Scan`. Chi phí 3.940,18, thời gian 7,247 ms, quét 2.440 blocks.
   - **EP2 (Index đơn `buyer_id`):** Q1 thực thi `Limit -> Sort -> Bitmap Heap Scan -> Bitmap Index Scan`. Giảm số block quét xuống 102 blocks, chi phí 339,80, nhưng vẫn cần node `Sort`.
   - **EP3 (Index ghép `(buyer_id, status, created_at DESC, id DESC)`):** Q1 thực thi `Limit -> Index Scan`. Loại bỏ hoàn toàn node `Sort`, chỉ tốn 8 blocks, chi phí 22,51, thời gian 0,043 ms.
   - **EP4 (Index `status` với truy vấn 90% bảng):** Q2 thực thi `Seq Scan` trực tiếp dù có index. Optimizer nhận diện việc duyệt index + random heap lookup cho 90.000 dòng là quá đắt đỏ so với sequential scan toàn bộ bảng.
4. **Bằng chứng thu thập:**
   - Tệp [query-plan-observations.json](file:///D:/Project%20CV/INTERVIEW/aethercore/docs/evidence/prompt16/query-plan-observations.json) chứa toàn bộ cấu trúc thô và chỉ số phân tích.
   - Được xác thực và đưa vào gói bằng chứng chính thức tại `docs/evidence/prompt16/`.
