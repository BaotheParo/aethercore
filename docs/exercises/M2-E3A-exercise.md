# M2-E3A EXERCISE: SPRING @TRANSACTIONAL & AOP PROXY SEMANTICS

**Học phần:** M2 — Database & Concurrency
**Lab:** M2-E3A (Spring AOP Proxy, Self-invocation, Rollback Rules)
**Tài liệu tham chiếu:** `docs/experiments/M2-E3A.md`, `TransactionProxyExperimentsTest.java`

---

## Phần 1: Bài tập Thực hành Độc lập (Hands-on Challenge)

### Đề bài Skeleton: "Ghi Marker Giao Dịch & Thông Báo Lỗi Nghiệp Vụ"

Cho cấu trúc mã nguồn ban đầu của một service trong game AetherCore:

```java
@Service
public class QuestRewardService {

    private final JdbcTemplate jdbcTemplate;

    public QuestRewardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Outer method: Nhận yêu cầu trả thưởng nhiệm vụ từ người chơi
    public void claimReward(UUID playerId, String questId) throws QuestVerificationException {
        // Gọi nội bộ ghi nhận marker
        this.recordRewardMarker(playerId, questId);

        // Kiểm tra điều kiện phụ (giả lập ném checked exception)
        throw new QuestVerificationException("Hệ thống phát hiện nhiệm vụ chưa hoàn thành tại NPC!");
    }

    // Inner method: Ghi marker nhận thưởng vào database
    @Transactional
    public void recordRewardMarker(UUID playerId, String questId) {
        jdbcTemplate.update(
            "INSERT INTO quest_claim_marker (player_id, quest_id, claimed_at) VALUES (?, ?, NOW())",
            playerId, questId
        );
    }
}
```

### Nhiệm vụ của Học viên:

1. **Vẽ luồng thực thi (Call Path Diagram):**
   Vẽ sequence hoặc biểu đồ ASCII mô tả đường đi của lời gọi từ caller vào `claimReward()` và từ `claimReward()` tới `this.recordRewardMarker()`. Chỉ rõ đối tượng nào là CGLIB Proxy và đối tượng nào là Target Instance trên JVM Heap.
   **Trả lời của bạn:**
   `[AWAITING_CANDIDATE]`

2. **Dự đoán Trạng thái Database (Prediction):**
   Trước khi chạy code, hãy dự đoán: Sau khi `claimReward()` ném ra `QuestVerificationException`, bản ghi `quest_claim_marker` trong cơ sở dữ liệu PostgreSQL sẽ tồn tại (committed) hay bị hủy bỏ (rolled back)? Giải thích cơ chế vì sao.
   **Trả lời của bạn:**
   `[AWAITING_CANDIDATE]`

3. **Tái cấu trúc Tách Bean (Refactoring):**
   Viết lại cấu trúc code bằng cách tách việc ghi marker sang một Bean riêng biệt (`QuestMarkerWriter`) thông qua Constructor Injection để kích hoạt Spring AOP Proxy.
   **Mã nguồn tái cấu trúc của bạn:**
   `[AWAITING_CANDIDATE]`

4. **Xử lý Checked Exception với `rollbackFor` & Ranh giới Transaction:**
   - Phân biệt giữa việc "gọi method writer qua proxy" với "transaction bao quanh toàn bộ ranh giới claimReward".
   - Nếu `QuestVerificationException` là một Checked Exception ném ra tại `claimReward` sau khi writer đã hoàn tất: Method nào bắt buộc phải mang `@Transactional(rollbackFor = QuestVerificationException.class)`?
   - Lưu ý quan trọng: Thêm `rollbackFor` vào một method writer độc lập đã return và commit thành công trước đó có thể hồi tố hủy commit đó không? Vì sao?
   **Câu trả lời và Mã nguồn cấu hình của bạn:**
   `[AWAITING_CANDIDATE]`

5. **Viết Unit/Integration Test Assertions:**
   Viết 2 câu lệnh assertion kiểm tra trạng thái DB sau lời gọi. Giải thích: *Tại sao trong class test kiểm tra cơ chế `@Transactional` của service, ta TUYỆT ĐỐI KHÔNG NÊN đặt `@Transactional` lên method test?*
   **Trả lời và Mã test của bạn:**
   `[AWAITING_CANDIDATE]`

---

## Phần 2: Bộ 6 Câu hỏi Phỏng vấn Oral (60–90 giây/câu)

### Câu 1:
*“Annotation trong Java bản chất chỉ là metadata tĩnh; vậy thành phần cụ thể nào trong Spring Framework làm cho `@Transactional` có hiệu lực khi chạy ứng dụng?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

---

### Câu 2:
*“Vì sao một method tự gọi chính nó (`this.someMethod()`) lại không kích hoạt transaction advice, nhưng đôi khi trong method đó ta vẫn thấy `isActualTransactionActive() == true`?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

---

### Câu 3:
*“Vì sao caller từ xa nhận được Checked Exception từ service nhưng câu lệnh INSERT thực hiện trước đó trong service vẫn tồn tại nguyên vẹn trong PostgreSQL?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

---

### Câu 4:
*“Có nhận định cho rằng: ‘Chỉ cần try-catch `RuntimeException` trong method `@Transactional` thì giao dịch chắc chắn sẽ commit bình thường’. Nhận định này có luôn đúng không? Cần có những điều kiện biên nào?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

---

### Câu 5:
*“`TransactionTemplate` và `@Transactional` (dùng proxy) khác nhau ở vị trí điều khiển ranh giới (demarcation boundary) như thế nào? Ưu và nhược điểm của từng loại?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`

---

### Câu 6:
*“Nếu ta vô tình đánh dấu `@Transactional` lên chính method kiểm thử JUnit (`@Test @Transactional void testMethod()`), điều đó có thể che giấu lỗi self-invocation bypass trong service đang được kiểm thử như thế nào?”*
**Ứng viên trả lời:** `[AWAITING_CANDIDATE]`
