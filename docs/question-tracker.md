# AETHERCORE — QUESTION TRACKER & MAPPING SYSTEM

> **Nguyên tắc:**
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
| **J63** | Race condition; thao tác read/modify/write | M2-E1A `BrokenMarketPurchaseService` (2 worker lost update) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J64** | Thread safety và cách kiểm chứng harness đồng thời | M2-E1A `MarketPurchaseConcurrencyTest` (latches & nanoTime) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J82** | `CountDownLatch`, `CyclicBarrier`, `Semaphore` | M2-E1A (`CyclicBarrier` & `CountDownLatch`) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J101** | Lambda khác anonymous class thế nào? | M1-E4 `lambdaStubDelegation` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J102** | Functional interface là gì? Function, Predicate, Consumer, Supplier dùng khi nào? | M1-E4 `@FunctionalInterface` trên `DamageStrategy` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J107** | Java Record và Shallow Immutability | M1-E3 `RecordExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **SB03** (SB-02) | IoC và DI là gì? Nếu tự new mọi dependency trong service thì gặp vấn đề gì? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB04** | Vì sao constructor injection thường phù hợp với dependency bắt buộc hơn field injection? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB06** | Khi có hai bean cùng kiểu, Spring quyết định inject bean nào? Qualifier và Primary giúp gì? | M1-E4 `DamageStrategyWiringTest` (`@Qualifier` vs `NoUniqueBeanDefinitionException`) | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB07** (SB-08) | Singleton Bean có tự động thread-safe không? Shared mutable state có thể ở đâu? | M1-E4 Stateless strategy services | PARTIAL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB21** | Spring AOP giải quyết bài toán gì, khi nào dùng | M2-E3A `TransactionProxyExperimentsTest` (Declarative transaction management use case) | PARTIAL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB22** | Proxy Spring chặn lời gọi method như thế nào | M2-E3A `TransactionProxyExperimentsTest` (CGLIB AopProxy invocation boundary & Interceptor) | FULL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB23** | `this.someMethod()` với Transactional/Async/Cacheable | M2-E3A `TransactionProxyExperimentsTest` (TX3 self-invocation bypass vs TX8 outer context) | PARTIAL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB24** | JDK dynamic proxy và class-based proxy | M2-E3A `TransactionProxyExperimentsTest` (`verifyAopProxying`: CGLIB class-based proxy verification) | PARTIAL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB25** | `@Transactional` bắt đầu và kết thúc transaction tại ranh giới nào? | M2-E3A (TX1–TX8 demarcation & proxy) & M2-E3B (PR1–PR6 propagation boundaries & connection suspension) | FULL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB26** | Rollback mặc định và catch exception | M2-E3A (TX2, TX5, TX6, TX7) & M2-E3B (PR2 UnexpectedRollbackException, PR4 REQUIRES_NEW failure catch) | FULL | `VERIFIED` (Spring AOP) | `AWAITING_CANDIDATE` | - |
| **SB27** | REQUIRED, REQUIRES_NEW và NESTED giải quyết những nhu cầu khác nhau nào? | M2-E3B `TransactionPropagationExperimentsTest` (PR1–PR6 propagation, savepoints, connection isolation) | FULL | `VERIFIED` (Spring AOP / PG16) | `AWAITING_CANDIDATE` | - |
| **SB50** | Persistence context là gì? Dirty checking khiến UPDATE được phát ra khi nào? | M2-E1C (JPA-1 dirty checking & SQL) & M2-E4A (NQ5 identity map cache tránh query lặp) | PARTIAL | VERIFIED (Hibernate/JPA) | AWAITING_CANDIDATE | - |
| **SB51** | save(), flush() và saveAndFlush() khác nhau ra sao? Flush có đồng nghĩa commit không? | M2-E1C `JpaVersionExperimentsTest` (JPA-4: EntityManager flush/commit; chưa Spring Data repo) | PARTIAL | `VERIFIED` (Hibernate/JPA) | `AWAITING_CANDIDATE` | - |
| **SB55** | Optimistic locking với @Version phát hiện xung đột như thế nào? | M2-E1C `JpaVersionExperimentsTest` (JPA-2 flush conflict, JPA-3 commit conflict, JPA-5 fresh retry) | FULL | `VERIFIED` (Hibernate/JPA) | `AWAITING_CANDIDATE` | - |
| **SQL04** | `NULL`, `0`, chuỗi rỗng `""` và `= NULL` | Chuẩn bị cho các lab DB | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **SQL53** (DB-07,29) | Transaction: `COMMIT`, `ROLLBACK`, `SAVEPOINT` | M2-E1A (Commit/Rollback), M2-E3B (PR5/PR6 Savepoints), M2-E2A (DL1 Victim atomicity rollback) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL54** | ACID và lỗi từng thuộc tính ngăn chặn | M2-E1A (Rollback & Read Committed) & M2-E2A (Atomicity: marker và UPDATE trước lỗi của victim cũng mất hoàn toàn khi deadlock) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL55** | Hiện tượng Lost Update vs Dirty Read | M2-E1A Tái hiện Lost Update thành công trên PostgreSQL 16 | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL56** | Mức cô lập READ COMMITTED của PostgreSQL khi đồng thời | M2-E1A Atomic conditional update & WHERE re-evaluation | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL57** | Khi nào chọn optimistic locking với version, khi nào cần pessimistic locking? | M2-E1B & M2-E1C Phân tích cơ chế và trade-offs (chưa benchmark hiệu năng cao) | PARTIAL | `VERIFIED` (PostgreSQL 16 & Hibernate) | `AWAITING_CANDIDATE` | - |
| **SQL58** | SELECT ... FOR UPDATE khóa dữ liệu để làm gì? Giữ transaction mở lâu sẽ gây tác hại gì? | M2-E1B C4 & M2-E2A DL2 (pg_blocking_pids quan sát tiến trình bị chặn khi giữ transaction mở) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL59** | Deadlock khi cập nhật lấy khóa khác thứ tự (Lock Ordering) | M2-E2A `PostgresDeadlockExperimentsTest` (DL1 chu trình 2 hàng ngược thứ tự, DL2 chuẩn hóa thứ tự [1,2]; cơ chế nền tảng, chưa batch/upsert) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL60** | Retry câu SQL hay toàn bộ transaction sau khi thành victim deadlock? | M2-E2A `PostgresDeadlockExperimentsTest` (DL3 chứng minh aborted state 40P01 bắt buộc retry toàn bộ transaction với fresh txid_current) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SB52** | Lazy loading gây N+1 trong API danh sách | M2-E4A JpaFetchExperimentsTest (NQ1 đo đạc prepared statement count 1+6=7 tầng persistence; chưa HTTP/serialization) | PARTIAL | VERIFIED (Hibernate/JPA) | AWAITING_CANDIDATE | - |
| **SB53** | JOIN FETCH, @EntityGraph và DTO projection phù hợp với truy vấn nào | M2-E4A (NQ2..NQ4) & M2-E4B (PG1–PG5: Trade-off collection fetch join vô hiệu hóa DB LIMIT, ưu thế của two-step ID pagination; chưa Spring Data repo) | PARTIAL | VERIFIED (Hibernate/JPA) | AWAITING_CANDIDATE | - |
| **SQL51** | N+1 tầng JPA khác một JOIN SQL không hiệu quả thế nào | M2-E4A (NQ1..NQ4 so sánh query count) & M2-E4B (PG1 chứng minh 1 SQL JOIN collection tải toàn bộ dataset vào bộ nhớ so với PG3 hai bước; chưa execution-plan benchmark) | PARTIAL | VERIFIED (Hibernate/JPA / PostgreSQL 16) | AWAITING_CANDIDATE | - |
| **SB54** | Collection fetch join cùng pagination | M2-E4B JpaCollectionPaginationExperimentsTest (PG1 in-memory pagination, PG2 fail-fast guard, PG3–PG5 two-step pagination) | FULL | VERIFIED (Hibernate/JPA / PostgreSQL 16) | AWAITING_CANDIDATE | - |
| **SQL50** | Offset pagination và keyset khi bảng lớn | M2-E4B (PG3–PG5 two-step offset) & M2-E5B (KP1–KP4: deep offset 80k scan 80.020 rows vs keyset scan 20 rows, tie-breaker cursor, drift under concurrent insert/delete) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SB44** | Offset hạn chế gì khi dữ liệu tăng/thêm liên tục? | M2-E5B (KP1 scan overhead trên bảng lớn, KP3 drift lặp dòng khi INSERT đồng thời, KP4 drift mất dòng khi DELETE đồng thời) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL39** | Index tránh quét bảng; vì sao không index mọi cột | M2-E5A (EP1 vs EP2/EP3 index giảm từ 2.440 blocks xuống 8 blocks) & M2-E5C (IP1/IP4 index thu hẹp phạm vi tìm kiếm; phân tích chi phí bảo trì ghi và storage của expression index, chưa có write benchmark) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL40** | Index một cột, ghép, unique | M2-E5A (EP2 index đơn vs EP3 index ghép so sánh cơ chế lọc và sắp xếp; uniqueness chưa thí nghiệm) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL41** | Thứ tự cột index ghép ảnh hưởng query | M2-E5A (EP3) & M2-E5B (KP1 idx_kst_buyer_recent(buyer_id, created_at DESC, id DESC) phục vụ cả lọc buyer và tuple keyset ordering; chưa hoán vị mọi thứ tự) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL43** | Có index nhưng optimizer chọn scan | M2-E5A (EP4 Seq Scan khi low selectivity 90% bảng) & M2-E5C (IP2 Seq Scan với contains LIKE vì không có prefix range; IP3 Seq Scan khi bọc hàm lower; cập nhật evidence, không nhận bao phủ mọi nguyên nhân) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL44** | LIKE tiền tố vs chứa chuỗi với B-tree index thông thường, vai trò collation | M2-E5C (IP1 prefix B-tree Index Scan [Redstone-, Redstone.) vs IP2 contains Seq Scan '%Redstone%'; collation C xác định, chưa kiểm chứng mọi locale/opclass) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL45** | Hàm trên cột lower() làm vô hiệu hóa index và cách giải quyết bằng Expression Index | M2-E5C (IP3 Seq Scan khi dùng lower(col) vs IP4 Index Scan với idx_pt_lower_name; semantics control 0 rows khi bỏ lower; chưa làm implicit cast) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL46** | EXPLAIN; estimated rows vs actual | M2-E5A (EP1..EP4), M2-E5B (KP1) & M2-E5C (IP1..IP4 so sánh Plan Rows vs Actual Rows, Index Cond range vs Filter re-check, và raw execution trees) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL47** | Đọc EXPLAIN ANALYZE tìm bước tốn thời gian | M2-E5A, M2-E5B & M2-E5C (IP1..IP4 phân tích Actual Rows vs Rows Removed by Filter 99999, Shared Hit Blocks; lưu ý TIMING OFF không đo thời gian từng node riêng) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **H02** | POST timeout rồi gửi lại; biết request đầu đã tạo tác vụ chưa | M2-E6A IdempotentMarketPurchaseService (ID1 replay sau khi commit mô phỏng mất response; mô hình retry ở tầng service, chưa test HTTP/network) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **H03** | Idempotency key; lưu và kiểm tra để tránh tạo hai tác vụ | M2-E6A (ID1..ID7 lưu scope (caller, op, key) + canonical SHA-256 fingerprint, chặn double purchase, DB unique lock wait) | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **H04** | 200/201/202 cho tác vụ nền và tra cứu tiến độ | M2-E6A (Chưa triển khai async/polling API trong phiên này) | NOT_COVERED | - | `AWAITING_CANDIDATE` | - |
| **SB42** | Phân biệt 400/401/403/404/409/422/500 | M2-E6A (Ném IdempotencyConflictException nội bộ, chưa map HTTP status codes ở controller) | NOT_COVERED | - | `AWAITING_CANDIDATE` | - |
| **SB43** | API tạo tài nguyên an toàn khi request gửi lại | M2-E6A (ID1..ID7 transactional claim & replay logic đã kiểm chứng chặt chẽ, API end-to-end chưa có) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SB59** | Flyway/Liquibase quản lý schema; migration lỗi khi deploy | M2-E7A (MG1..MG3: Transactional DDL rollback trên PostgreSQL 16) & M2-E7B (NC1..NC3: Non-transactional migration failure, INVALID index, giới hạn của Flyway repair và quy trình recovery có kiểm chứng; chưa lab Liquibase) | VERIFIED | VERIFIED (PostgreSQL 16) | AWAITING_CANDIDATE | - |
| **SQL68** | Schema mới, dữ liệu cũ, phiên bản code đang chạy | M2-E7A (MG1 upgrade V5->V6; MG3 DDL rollback khi dữ liệu cũ vi phạm CHECK) & M2-E7B (NC2 duplicate data khiến unique index build fail để lại INVALID index; NC3 data cleanup & recovery; chưa rolling deployment) | PARTIAL | VERIFIED (PostgreSQL 16) | AWAITING_CANDIDATE | - |
| **SB60** | Thay schema tương thích với code cũ/mới | M2-E7A (Thêm NOT NULL guard trong CHECK constraint giữ tương thích contract) & M2-E7B (Thêm unique index non-transactional; chưa expand-contract deployment) | PARTIAL | VERIFIED (PostgreSQL 16) | AWAITING_CANDIDATE | - |
| **SB56** | H2 có thể bỏ sót lỗi gì so với DB thật | M2-E7A (Phân tích lý thuyết sự khác biệt cú pháp CHECK, transactional DDL và 3-valued logic; không có H2 đối chứng runtime trong phiên) | PARTIAL | PARTIAL (Lý thuyết) | AWAITING_CANDIDATE | - |
| **SQL69** | Tạo index bảng lớn ảnh hưởng ghi và deploy | M2-E7B (NC1..NC3: CREATE INDEX CONCURRENTLY build failure, INVALID index catalog state và quy trình recovery đã kiểm chứng; tải lớn và writer latency chưa đo) | PARTIAL | VERIFIED (PostgreSQL 16) | AWAITING_CANDIDATE | - |

---

## Câu hỏi mở rộng (Ngoài ngân hàng chuẩn / Chủ đề thực nghiệm nâng cao)

| Mã nội bộ | Câu hỏi / Chủ đề thảo luận | Module & Bằng chứng | Độ phủ | Xác minh kỹ thuật | Trạng thái người học |
|:---:|---|---|:---:|:---:|:---:|
| **Q-M2-03** | So sánh sâu Tradeoffs của 3 chiến lược: Atomic Conditional UPDATE vs Optimistic Locking (OCC) vs Pessimistic Locking (`SELECT FOR UPDATE`). | M2-E1B & M2-E1C (JDBC & JPA) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` |
| **Q-M2-04** | Cơ chế Version Tracking và các vấn đề khi retry đồng thời (Thundering Herd / Livelock). | M2-E1B & M2-E1C (Retry in Fresh TX) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` |
| **Q-M2-05** | PostgreSQL Locking Internals: Cơ chế hoạt động của `pg_blocking_pids()` và chuỗi chờ khóa hàng. | M2-E1B C4 `pg_blocking_pids` assertion | FULL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` |
