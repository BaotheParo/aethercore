# Bài tập Thực hành M2-E3B: Transaction Propagation & Failure Handling

**Mã bài tập:** M2-E3B-EX  
**Mục tiêu:** Vận dụng hiểu biết về Spring `@Transactional` propagation (`REQUIRED`, `REQUIRES_NEW`, `NESTED`) để thiết kế luồng xử lý ghi nhận nhật ký kiểm toán (Audit Trail) độc lập với tính thành bại của giao dịch nghiệp vụ chính.

---

## 1. Tình huống Nghiệp vụ (Business Scenario)

Hệ thống bảo hiểm số cần xử lý các yêu cầu bồi thường (`ClaimService.processClaim`). 
- Để chống gian lận và tuân thủ pháp lý, **mọi nỗ lực gửi claim (dù thành công, bị từ chối, hay bị lỗi hệ thống bất ngờ)** đều phải được ghi lại vĩnh viễn vào bảng kiểm toán (`AttemptAuditService.recordAttempt`).
- Nếu việc xử lý bồi thường thất bại (ném ngoại lệ), số tiền trong tài khoản và trạng thái claim phải rollback. Tuy nhiên, bản ghi kiểm toán nỗ lực gửi claim **tuyệt đối không được phép bị rollback**.
- Ngược lại, nếu service kiểm toán gặp lỗi kết nối DB tạm thời, hệ thống có cho phép tiếp tục duyệt claim không?

---

## 2. Khung Mã Nguồn Thử Thách (Code Skeleton)

Ứng viên hãy phân tích và hoàn thiện các annotation `@Transactional` cùng cấu hình propagation phù hợp cho hai Service sau:

```java
package com.aethercore.claim;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptAuditService {

    private final AuditRepository auditRepository;

    public AttemptAuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * YÊU CẦU: Bản ghi audit này PHẢI ĐƯỢC COMMIT NGAY LẬP TỨC và tồn tại
     * độc lập, kể cả khi nghiệp vụ gọi nó (ClaimService) bị rollback.
     *
     * TODO [CANDIDATE]: Điền cấu hình @Transactional phù hợp vào đây.
     */
    // @Transactional(propagation = Propagation.________) // [AWAITING_CANDIDATE]
    public void recordAttempt(String claimId, String userId, String status) {
        auditRepository.insertAuditLog(claimId, userId, status);
    }
}
```

```java
package com.aethercore.claim;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    private final AttemptAuditService attemptAuditService;
    private final ClaimRepository claimRepository;
    private final PaymentGateway paymentGateway;

    public ClaimService(AttemptAuditService attemptAuditService,
                        ClaimRepository claimRepository,
                        PaymentGateway paymentGateway) {
        this.attemptAuditService = attemptAuditService;
        this.claimRepository = claimRepository;
        this.paymentGateway = paymentGateway;
    }

    /**
     * Luồng xử lý claim nghiệp vụ chính.
     *
     * TODO [CANDIDATE]: Thiết kế khối try-catch và điều phối transaction
     * sao cho audit log được lưu bất kể kết quả của processClaim.
     */
    // @Transactional(propagation = Propagation.________) // [AWAITING_CANDIDATE]
    public void processClaim(String claimId, String userId, long amount) {
        // Bước 1: Ghi nhận nỗ lực xử lý
        attemptAuditService.recordAttempt(claimId, userId, "PROCESSING");

        // Bước 2: Thực hiện trừ quỹ / thanh toán
        claimRepository.deductPolicyLimit(userId, amount);

        // Giả lập lỗi nghiệp vụ hoặc ngoại lệ hệ thống
        if (amount > 100_000_000L) {
            throw new ClaimExceededLimitException("Vượt hạn mức bồi thường tối đa");
        }

        claimRepository.markClaimApproved(claimId);
    }
}
```

---

## 3. Câu hỏi Phỏng vấn Nhanh (Oral Interview Questions — 60 đến 90 giây)

### Câu hỏi 1 (60s):
**Ngoại lệ `UnexpectedRollbackException` trong Spring xảy ra khi nào? Tại sao việc lập trình viên đặt khối `try-catch` bao bọc lời gọi method con lại không cứu được transaction cha?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 2 (60s):
**Khi nào bạn nên sử dụng `Propagation.REQUIRES_NEW`? Cái giá và rủi ro lớn nhất về mặt Connection Pool (HikariCP) là gì?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 3 (60s):
**Điểm khác biệt cốt lõi giữa `Propagation.NESTED` và `Propagation.REQUIRES_NEW` về mặt Connection JDBC và tính độc lập của transaction vật lý là gì?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

### Câu hỏi 4 (90s):
**Nếu inner method với `REQUIRES_NEW` đã commit thành công xuống Database, nhưng outer method sau đó bị crash server đột ngột (OOM hoặc mất điện), dữ liệu inner có bị rollback không? Trong thực tế, bạn xử lý bài toán này như thế nào?**

- **Trả lời của Ứng viên:** `[AWAITING_CANDIDATE]`
- **Đánh giá / Rubric:** `[AWAITING_CANDIDATE]`

---

## 4. Bảng Đánh Giá Kỹ Năng Ứng Viên

| Tiêu chí | Mức độ mong đợi | Kết quả Ứng viên |
| :--- | :--- | :--- |
| **Hiểu cờ rollback-only** | Nắm được cơ chế `TransactionInterceptor` đánh dấu cờ khi qua boundary | `[AWAITING_CANDIDATE]` |
| **Phân biệt Logical vs Physical Tx** | Phân biệt được số lượng physical connection giữa REQUIRED và REQUIRES_NEW | `[AWAITING_CANDIDATE]` |
| **Nhận diện rủi ro Pool Starvation** | Tính toán được pool sizing khi dùng nested REQUIRES_NEW | `[AWAITING_CANDIDATE]` |
| **Tư duy Kiến trúc Bù trừ (Compensate)** | Hiểu rằng REQUIRES_NEW không phải 2PC, cần Saga/Compensate khi outer fail | `[AWAITING_CANDIDATE]` |
