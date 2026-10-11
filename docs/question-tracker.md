# AETHERCORE â€” QUESTION TRACKER (TIáº¾N Äá»˜ PHá»ŽNG Váº¤N)
**Cáº­p nháº­t:** 11/10/2026 (Prompt 08 â€” Kháº¯c phá»¥c Testcontainers & Nghiá»‡m thu Runtime PostgreSQL)

> **NguyÃªn táº¯c Ä‘iá»u hÃ nh:**
> - CÃ¢u há»i vÃ  mÃ£ Ä‘á»‹nh danh Ä‘á»‘i chiáº¿u 100% vá»›i ngÃ¢n hÃ ng gá»‘c `500 CAU HOI PV(1).md`.
> - TÃ¡ch biá»‡t rÃµ: Pháº¡m vi bao phá»§ (Coverage), XÃ¡c minh ká»¹ thuáº­t (Verification Status), vÃ  Tráº¡ng thÃ¡i ngÆ°á»i há»c (Learning Readiness).
> - Äiá»ƒm sá»‘ vÃ  lá»i giáº£i ngÆ°á»i há»c Ä‘Æ°á»£c báº£o lÆ°u nghiÃªm ngáº·t á»Ÿ tráº¡ng thÃ¡i `AWAITING_CANDIDATE`.

| MÃ£ cÃ¢u há»i | Chá»§ Ä‘á» chuáº©n (500 CAU HOI PV) | Báº±ng chá»©ng Code / Test liÃªn quan | Äá»™ phá»§ (Coverage) | XÃ¡c minh ká»¹ thuáº­t | Tráº¡ng thÃ¡i ngÆ°á»i há»c | Äiá»ƒm (0-3) |
|:---:|---|---|:---:|:---:|:---:|:---:|
| **J01** (JC-01) | JVM, JRE, JDK | Äang chá» phÃ¢n tÃ­ch thÃªm | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **J05** | Primitive vÃ  Wrapper | M1-E3 `WrapperAndPrecisionExperimentsTest` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J06** | Autoboxing, Unboxing & Null Unboxing NPE | M1-E3 `WrapperAndPrecisionExperimentsTest` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J07** | Integer Cache & So sÃ¡nh Identity Wrapper | M1-E3 (cháº¡y thÆ°á»ng & flag `-XX:AutoBoxCacheMax=2048`) | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J08** | Biá»ƒu diá»…n giÃ¡ trá»‹ tháº­p phÃ¢n chÃ­nh xÃ¡c & BigDecimal | M1-E3 `WrapperAndPrecisionExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J11** (JC-10,14) | `==`, `equals()`, `hashCode()` | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J12** (JC-16) | Override `equals()` nhÆ°ng khÃ´ng `hashCode()` | M1-E1 Biáº¿n thá»ƒ B | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J13** (JC-11..13) | Há»£p Ä‘á»“ng `equals()` (Reflexive, Symmetric, Transitive...) | M1-E1 Biáº¿n thá»ƒ C | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J14** | Mutable Key trong HashMap | M1-E1 Biáº¿n thá»ƒ D | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J15** | Overloading vÃ  overriding khÃ¡c nhau tháº¿ nÃ o? | M1-E4 `MethodDispatchTests`, `DispatchDemonstrator` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J16** | Java chá»n method bá»‹ overload vÃ  method bá»‹ override vÃ o thá»i Ä‘iá»ƒm nÃ o? | M1-E4 `MethodDispatchTests` (Compile-time vs Runtime) | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J17** | Interface vÃ  abstract class khÃ¡c nhau tháº¿ nÃ o? Chá»n loáº¡i nÃ o trong má»™t thiáº¿t káº¿ cá»¥ thá»ƒ? | M1-E4 `DamageStrategy` interface & implementation | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J18** | default method trong interface giáº£i quyáº¿t váº¥n Ä‘á» gÃ¬ vÃ  cÃ³ thá»ƒ gÃ¢y xung Ä‘á»™t tháº¿ nÃ o? | ChÆ°a cÃ³ lab vá» xung Ä‘á»™t default method | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **J19** | final vá»›i biáº¿n, method vÃ  class mang Ã½ nghÄ©a gÃ¬? | M1-E3 Records & M1-E1 final key fields | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J44** | HashSet phÃ¡t hiá»‡n vÃ  xá»­ lÃ½ pháº§n tá»­ trÃ¹ng | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J46** (JC-48) | CÆ¡ cháº¿ `put`/`get` cá»§a `HashMap` | M1-E1 `PlayerKeyExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J63** | Race condition; thao tÃ¡c read/modify/write | M2-E1A `BrokenMarketPurchaseService` (2 worker lost update) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J64** | Thread safety vÃ  cÃ¡ch kiá»ƒm chá»©ng harness Ä‘á»“ng thá»i | M2-E1A `MarketPurchaseConcurrencyTest` (latches & nanoTime) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J82** | `CountDownLatch`, `CyclicBarrier`, `Semaphore` | M2-E1A (`CyclicBarrier` & `CountDownLatch`) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **J101** | Lambda khÃ¡c anonymous class tháº¿ nÃ o? | M1-E4 `lambdaStubDelegation` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J102** | Functional interface lÃ  gÃ¬? Function, Predicate, Consumer, Supplier dÃ¹ng khi nÃ o? | M1-E4 `@FunctionalInterface` trÃªn `DamageStrategy` | PARTIAL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **J107** | Java Record vÃ  Shallow Immutability | M1-E3 `RecordExperimentsTest` | FULL | `VERIFIED` (Core Lab) | `AWAITING_CANDIDATE` | - |
| **SB03** (SB-02) | IoC vÃ  DI lÃ  gÃ¬? Náº¿u tá»± new má»i dependency trong service thÃ¬ gáº·p váº¥n Ä‘á» gÃ¬? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB04** | VÃ¬ sao constructor injection thÆ°á»ng phÃ¹ há»£p vá»›i dependency báº¯t buá»™c hÆ¡n field injection? | M1-E4 `DamageStrategyWiringTest` | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB06** | Khi cÃ³ hai bean cÃ¹ng kiá»ƒu, Spring quyáº¿t Ä‘á»‹nh inject bean nÃ o? Qualifier vÃ  Primary giÃºp gÃ¬? | M1-E4 `DamageStrategyWiringTest` (`@Qualifier` vs `NoUniqueBeanDefinitionException`) | FULL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB07** (SB-08) | Singleton Bean cÃ³ tá»± Ä‘á»™ng thread-safe khÃ´ng? Shared mutable state cÃ³ thá»ƒ á»Ÿ Ä‘Ã¢u? | M1-E4 Stateless strategy services | PARTIAL | `VERIFIED` (Spring DI) | `AWAITING_CANDIDATE` | - |
| **SB25** | `@Transactional` báº¯t Ä‘áº§u vÃ  káº¿t thÃºc transaction táº¡i ranh giá»›i nÃ o? | M2-E1A `TransactionTemplate` programmatic boundary & outer rollback test | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SB27** | REQUIRED, REQUIRES_NEW vÃ  NESTED giáº£i quyáº¿t nhá»¯ng nhu cáº§u khÃ¡c nhau nÃ o? | M2-E1A `FixedMarketPurchaseService` (kiá»ƒm chá»©ng `PROPAGATION_REQUIRED`) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL04** | `NULL`, `0`, chuá»—i rá»—ng `""` vÃ  `= NULL` | Chuáº©n bá»‹ cho cÃ¡c lab DB | NOT_STARTED | - | `AWAITING_CANDIDATE` | - |
| **SQL53** (DB-07,29) | Transaction: `COMMIT`, `ROLLBACK`, `SAVEPOINT` | M2-E1A Commit & Rollback tests trÃªn PostgreSQL 16 | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL54** | ACID vÃ  lá»—i tá»«ng thuá»™c tÃ­nh ngÄƒn cháº·n | M2-E1A (Atomicity Rollback & Read Committed) | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL55** | Hiá»‡n tÆ°á»£ng Lost Update vs Dirty Read | M2-E1A TÃ¡i hiá»‡n Lost Update thÃ nh cÃ´ng trÃªn PostgreSQL 16 | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |
| **SQL56** | Má»©c cÃ´ láº­p READ COMMITTED cá»§a PostgreSQL khi Ä‘á»“ng thá»i | M2-E1A Atomic conditional update & WHERE re-evaluation | PARTIAL | `VERIFIED` (PostgreSQL 16) | `AWAITING_CANDIDATE` | - |

---

## 5. M2-E1B: Concurrency Control & Locking Questions

| ID | Câu hỏi / Chủ đề | Module & Lab | Độ ưu tiên | Trạng thái |
|---|---|---|:---:|:---:|
| **Q-M2-03** | So sánh Tradeoffs của 3 chiến lược: Atomic Conditional UPDATE vs Optimistic Locking (OCC) vs Pessimistic Locking (`SELECT FOR UPDATE`). | M2-E1B | P1 | `AWAITING_CANDIDATE` |
| **Q-M2-04** | Cơ chế Version Tracking và kỹ thuật chống Thundering Herd / Retry Storms với Exponential Backoff & Jitter. | M2-E1B | P1 | `AWAITING_CANDIDATE` |
| **Q-M2-05** | PostgreSQL Locking Internals: Cơ chế hoạt động của `pg_blocking_pids()` và giải pháp phòng chống Deadlock khi lock nhiều tài nguyên. | M2-E1B | P1 | `AWAITING_CANDIDATE` |