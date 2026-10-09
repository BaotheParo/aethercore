# GHI CHÚ THỰC THI PROMPT 03 (EXECUTION PROMPT 03)
**Chủ đề:** M1-E1 — PlayerKey, Hợp đồng equals/hashCode và Hành vi HashMap/HashSet  
**Ngày thực hiện:** 09/10/2026  
**Chủ trì:** Orchestrator & Agent Tree

---

## 1. Các thành phần đã xây dựng trong `core-lab`
1. **Variant A (Identity Baseline):** `IdentityPlayerKey.java` — Kế thừa mặc định từ `Object.class`. Hai instance riêng biệt cùng dữ liệu sẽ fail lookup trên HashMap (`map.get` trả về `null`).
2. **Variant B (Equals Only):** `EqualsOnlyPlayerKey.java` — Chỉ override `equals()`, không override `hashCode()`. Minh chứng hash code không đồng nhất khiến HashMap tìm sai bucket.
3. **Variant C (Value Key):** `ValuePlayerKey.java` — `final class`, field bất biến, `equals()` & `hashCode()` chuẩn mực. Đạt đầy đủ tính chất phản xạ, đối xứng, bắc cầu, an toàn null/type và tính nhất quán hash.
4. **Variant D (Mutable Key):** `MutablePlayerKey.java` — Có field `serverId` thay đổi được. Minh chứng lỗi **Ghost Entry** và rò rỉ bộ nhớ khi mutate key sau khi đưa vào collection.
5. **Test Suite:** `PlayerKeyExperimentsTest.java` — 8 test case JUnit 5 độc lập chạy thuần Java, không phụ thuộc Spring hay Docker.

---

## 2. Bằng chứng kiểm thử kỹ thuật
- **Lệnh thực thi:** `mvn -pl core-lab -am test`
- **Kết quả:** `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (Thời gian: 0.086s).
- **Trạng thái Technical:** `VERIFIED`
- **Trạng thái Learning:** `AWAITING_CANDIDATE` (Đang chờ ứng viên dự đoán và làm bài tự code `ItemKey`).

---

## 3. Tài liệu & Bài tập đi kèm
- `docs/experiments/M1-E1.md`: Tài liệu thực nghiệm chi tiết và bảng tra cứu cơ chế HashMap.
- `docs/exercises/M1-E1-exercise.md`: Bài tập tự tay code `ItemKey` bất biến và bộ câu hỏi vặn.
- `docs/prediction-log.md`: Log ghi nhận dự đoán trước khi chạy thực nghiệm.
- `docs/question-tracker.md`: Cập nhật tiến độ 13 câu hỏi phỏng vấn liên quan.
