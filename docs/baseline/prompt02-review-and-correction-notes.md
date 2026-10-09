# GHI CHÚ THỰC THI PROMPT 02 (EXECUTION PROMPT 01B)
**Chủ đề:** Rà soát bằng chứng, sửa sai lệch và hoàn tất nền tảng Ngày 1  
**Ngày thực hiện:** 09/10/2026  
**Chủ trì:** Orchestrator & Agent Tree

---

## 1. Các sai lệch đã phát hiện và đính chính
1. **Lỗi biên dịch PlayerKey:** Phát hiện lỗi cú pháp ký tự (`'''`) trong `PlayerKey.java` khiến `mvn test-compile` bị FAIL. Đã sửa triệt để.
2. **Chuẩn hóa Database Migration:** Thay thế `schema.sql` và `data.sql` ad-hoc bằng **Flyway 10.10.0** (`flyway-core` + `flyway-database-postgresql`). Đã tạo `V1__create_lab_entry_table.sql` và `V2__seed_demo_entries.sql`.
3. **Đính chính Testcontainers:** Báo cáo cũ khẳng định "bật Docker sẽ pass 100%". Đã đính chính thành `BLOCKED` (Do thiếu Docker engine host).
4. **Khóa Java Runtime:** Làm rõ việc runtime host là JDK 22 trong khi bytecode được cấu hình `--release 21` để tương thích Java 21.
5. **Đối chiếu ngân hàng câu hỏi:** Ánh xạ bộ mã `JC-*`, `SB-*`, `DB-*` trong `CÂU HỎI PHỎNG VẤN.md` với các mã rút gọn `J01, SB03...` của kế hoạch 42 ngày.

---

## 2. Bằng chứng kiểm chứng kỹ thuật
- `mvn test-compile` -> **`BUILD SUCCESS`** (0 errors).
- `mvn test` -> Surefire 3.5.0 phát hiện `LabEntryIntegrationTest`, dừng lại tại container startup do thiếu Docker daemon.
- Report lưu tại `backend-app/target/surefire-reports/TEST-com.aethercore.backend.LabEntryIntegrationTest.xml`.
