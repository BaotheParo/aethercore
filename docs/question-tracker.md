# AETHERCORE — QUESTION TRACKER (TIẾN ĐỘ PHỎNG VẤN)
**Cập nhật:** 11/10/2026 (Prompt 07 — Rà soát M1-E4, Gia cố M2-E1A & Đồng bộ Ngân Hàng Câu Hỏi)

> **Nguyên tắc điều hành:**
> - Câu hỏi và mã định danh đối chiếu 100% với ngân hàng gốc `500 CAU HOI PV(1).md`.
> - Tách biệt rõ: Phạm vi bao phủ (Coverage), Xác minh kỹ thuật (Verification Status), và Trạng thái người học (Learning Readiness).
> - Điểm số và lời giải người học được bảo lưu nghiêm ngặt ở trạng thái `AWAITING_CANDIDATE`.

| Mã câu hỏi | Chủ đề chuẩn (500 CAU HOI PV) | Bằng chứng Code / Test liên quan | Độ phủ (Coverage) | Xác minh kỹ thuật | Trạng thái người học | Điểm (0-3) |
|:---:|---|---|:---:|:---:|:---:|:---:|
| **J01** (JC-01) | JVM, JRE, JDK | Đang chờ phân tích thêm | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **J05** | Primitive và Wrapper | M1-E3 `WrapperAndPrecisionExperimentsTest` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J06** | Autoboxing, Unboxing & Null Unboxing NPE | M1-E3 `WrapperAndPrecisionExperimentsTest` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J07** | Integer Cache & So sánh Identity Wrapper | M1-E3 (chạy thường & flag `-XX:AutoBoxCacheMax=2048`) | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J08** | Biểu diễn giá trị thập phân chính xác & BigDecimal | M1-E3 `WrapperAndPrecisionExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J11** (JC-10,14) | `==`, `equals()`, `hashCode()` | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J12** (JC-16) | Override `equals()` nhưng không `hashCode()` | M1-E1 Biến thể B | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J13** (JC-11..13) | Hợp đồng `equals()` (Reflexive, Symmetric, Transitive...) | M1-E1 Biến thể C | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J14** | Mutable Key trong HashMap | M1-E1 Biến thể D | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J15** | Overloading và overriding khác nhau thế nào? | M1-E4 `MethodDispatchTests`, `DispatchDemonstrator` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J16** | Java chọn method bị overload và method bị override vào thời điểm nào? | M1-E4 `MethodDispatchTests` (Compile-time vs Runtime) | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J17** | Interface và abstract class khác nhau thế nào? Chọn loại nào trong một thiết kế cụ thể? | M1-E4 `DamageStrategy` interface & implementation | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J18** | default method trong interface giải quyết vấn đề gì và có thể gây xung đột thế nào? | Chưa có lab về xung đột default method | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **J19** | final với biến, method và class mang ý nghĩa gì? | M1-E3 Records & M1-E1 final key fields | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J44** | HashSet phát hiện và xử lý phần tử trùng | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J46** (JC-48) | Cơ chế `put`/`get` của `HashMap` | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J63** | Race condition; thao tác read/modify/write | M2-E1A `BrokenMarketPurchaseService` | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **J64** | Thread safety và cách kiểm chứng harness đồng thời | M2-E1A `MarketPurchaseConcurrencyTest` | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **J82** | `CountDownLatch`, `CyclicBarrier`, `Semaphore` | M2-E1A (`CyclicBarrier` & `CountDownLatch`) | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **J101** | Lambda khác anonymous class thế nào? | M1-E4 `lambdaStubDelegation` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J102** | Functional interface là gì? Function, Predicate, Consumer, Supplier dùng khi nào? | M1-E4 `@FunctionalInterface` trên `DamageStrategy` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J107** | Java Record và Shallow Immutability | M1-E3 `RecordExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **SB03** (SB-02) | IoC và DI là gì? Nếu tự new mọi dependency trong service thì gặp vấn đề gì? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB04** | Vì sao constructor injection thường phù hợp với dependency bắt buộc hơn field injection? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB06** | Khi có hai bean cùng kiểu, Spring quyết định inject bean nào? Qualifier và Primary giúp gì? | M1-E4 `DamageStrategyWiringTest` (`@Qualifier` vs `NoUniqueBeanDefinitionException`) | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB07** (SB-08) | Singleton Bean có tự động thread-safe không? Shared mutable state có thể ở đâu? | M1-E4 Stateless strategy services | PARTIAL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB25** | `@Transactional` bắt đầu và kết thúc transaction tại ranh giới nào? | M2-E1A `TransactionTemplate` programmatic boundary & outer rollback test | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **SB27** | REQUIRED, REQUIRES_NEW và NESTED giải quyết những nhu cầu khác nhau nào? | M2-E1A `FixedMarketPurchaseService` (kiểm chứng `PROPAGATION_REQUIRED`) | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **SQL04** | `NULL`, `0`, chuỗi rỗng `""` và `= NULL` | Chuẩn bị cho các lab DB | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **SQL53** (DB-07,29) | Transaction: `COMMIT`, `ROLLBACK`, `SAVEPOINT` | M2-E1A Commit & Rollback tests | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **SQL54** | ACID và lỗi từng thuộc tính ngăn chặn | M2-E1A (Atomicity Rollback & Read Committed) | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **SQL55** | Hiện tượng Lost Update vs Dirty Read | M2-E1A Tái hiện Lost Update có kiểm soát | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
| **SQL56** | Mức cô lập READ COMMITTED của PostgreSQL khi đồng thời | M2-E1A Atomic conditional update & WHERE re-evaluation | PARTIAL | `IMPLEMENTED_UNVERIFIED` | `AWAITING_CANDIDATE` | - |
