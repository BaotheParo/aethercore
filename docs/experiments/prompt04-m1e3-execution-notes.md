# GHI CHÚ THỰC THI PROMPT 04 (EXECUTION PROMPT 04)
**Chủ đề:** Rà soát M1-E1 & Triển khai M1-E3 (Wrapper, BigDecimal, Record)  
**Ngày thực hiện:** 09/10/2026  
**Chủ trì:** Orchestrator & Agent Tree

---

## 1. Rà soát M1-E1
- Hiệu chỉnh thuật ngữ trong `docs/experiments/M1-E1.md` thành mô tả chính xác theo bằng chứng thực nghiệm (tránh các từ tuyệt đối hóa như "chắc chắn gây memory leak", "gây mất dữ liệu").
- Chạy lại suite M1-E1: 8/8 tests pass độc lập.

---

## 2. Triển khai M1-E3 trong `core-lab`
- Đã xây dựng 4 lớp mã nguồn chính:
  - `CharacterStats.java`: Record với compact constructor validation và wither pattern.
  - `InventoryBag.java`: Record với Defensive Copying (`List.copyOf`).
  - `UnsafeInventoryBag.java`: Fixture minh chứng rò rỉ trạng thái khi không phòng vệ tham chiếu.
  - `OrderPriceCalculator.java`: Helper tính tiền chính xác với `BigDecimal`, từ chối scale vi phạm và hỗ trợ làm tròn tường minh.
- Đã xây dựng 2 test suite JUnit 5:
  - `WrapperAndPrecisionExperimentsTest.java`: 12 tests (B1..B4: Primitive/Wrapper, Integer Cache, Double vs BigDecimal, Scale/Rounding/Calculator).
  - `RecordExperimentsTest.java`: 5 tests (B5..B6: Record components, validations, shallow immutability).
- Toàn bộ 25 unit test trong `core-lab` đạt **BUILD SUCCESS** (0 failures, 0 errors, 0 skipped).

---

## 3. Trạng thái phân hệ
- **Core Lab (M1-E1 & M1-E3):** `VERIFIED` (25/25 tests pass).
- **Learning Progress:** `AWAITING_CANDIDATE` (Chờ người học đưa ra dự đoán và nộp bài tự code).
- **Backend App / PostgreSQL:** `BLOCKED` (Do Docker daemon host chưa bật, giữ nguyên trạng thái trung thực).
