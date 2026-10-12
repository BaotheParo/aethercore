# M2-E1C EXERCISE: JPA @VERSION, DIRTY CHECKING & FLUSH/COMMIT LIFECYCLE

**Học phần:** M2 — Database & Concurrency  
**Lab:** M2-E1C (JPA @Version, Persistence Context, Flush vs Commit)  
**Tài liệu tham chiếu:** `docs/experiments/M2-E1C.md`, `JpaVersionExperimentsTest.java`

---

## Phần 1: Bài toán Dự đoán Thực nghiệm (Hands-on Challenge)

### Bài tập 1: Dự đoán Timeline Hai Persistence Context (Stock 5, Mua 2)
Giả sử SKU `POTION` có `available = 5, version = 0`.
- **Bước 1:** Worker A mở `EntityManager` $EM_A$ và bắt đầu transaction $TX_A$. Worker B mở $EM_B$ và bắt đầu transaction $TX_B$.
- **Bước 2:** Cả hai bên cùng gọi `find()` cho SKU `POTION`, đọc được `version = 0, available = 5`.
- **Bước 3:** Cả hai bên cùng gọi `stock.decrementStock(2)` trong memory.
- **Bước 4:** Worker A gọi `emA.flush()` và `emA.getTransaction().commit()`.
- **Bước 5:** Worker B gọi `emB.flush()`.
- **Bước 6:** Worker B bắt ngoại lệ, rollback $TX_B$, đóng $EM_B$. Sau đó Worker B mở $EM_{retry}$ mới, transaction mới, đọc lại kho và mua 2 unit thành công.

**Câu hỏi dành cho ứng viên:**
1. Tại Bước 5, ngoại lệ gì sẽ xảy ra với Worker B? Ngoại lệ này do PostgreSQL hay Hibernate ném ra?
2. Trạng thái database (stock, version, purchase count, sum quantity) ngay sau Bước 5 là bao nhiêu?
3. Trạng thái database sau khi Worker B hoàn thành Bước 6 là bao nhiêu?

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Bài tập 2: Biến thể Flush-then-Rollback
Cùng kịch bản trên, nhưng tại Bước 4:
- Worker A gọi `emA.flush()` nhưng **không commit**, mà sau đó gọi `emA.getTransaction().rollback()`.
- Tại Bước 5: Worker B tiếp tục gọi `emB.flush()` và `emB.getTransaction().commit()`.

**Câu hỏi dành cho ứng viên:**
1. Worker B có bị `OptimisticLockException` không? Vì sao?
2. Trạng thái cuối cùng của database (stock, version, purchase count) sẽ như thế nào?

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

## Phần 2: Ba Câu hỏi Phỏng vấn Trọng tâm (Oral Interview Questions)

### Câu 1: Cơ chế Dirty Checking
> *"Trong JPA, em không hề gọi method `save()` hay `update()`, tại sao chỉ cần gọi setter/thay đổi trạng thái trên managed entity thì Hibernate vẫn tự động phát câu lệnh SQL UPDATE xuống database?"*

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Câu 2: Flush vs Commit & Transaction Visibility
> *"Sau khi em gọi `em.flush()`, câu lệnh SQL UPDATE và INSERT đã thực sự chạy xuống PostgreSQL socket rồi. Vậy tại sao các transaction khác trên hệ thống vẫn chưa nhìn thấy bản ghi purchase mới?"*

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`

---

### Câu 3: Tính Toàn vẹn của EntityManager khi gặp Exception
> *"Nếu em bắt `OptimisticLockException` bằng `try-catch`, rồi ngay sau đó em gọi lại logic nghiệp vụ trên chính đối tượng `EntityManager` và transaction cũ đó thì sẽ gặp vấn đề gì? Vì sao bắt buộc phải mở `EntityManager` mới?"*

**Trả lời của ứng viên:**
`[AWAITING_CANDIDATE]`