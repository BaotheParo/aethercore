# Nh?t K? Th?c Thi Prompt 20: M2-E7A ? CHECK/NULL, Flyway Forward Migration & DDL Rollback

**M? phi?n:** Prompt 20 ? M2-E7A
**Th?i gian th?c thi:** 14/10/2026
**M?i tr??ng:** Java 22, Maven 3.9.12, Docker Server 29.5.3, Testcontainers PostgreSQL 16.15 (image `postgres:16-alpine`), Flyway 10.10.0, Spring Boot 3.3.4, Hibernate 6.5.3.Final
**Git Baseline HEAD:** `722be6baf774bb67d52ce57173bea225fdc9e378` (Commit `feat(prompt-19): implement transactional purchase idempotency`)
**Evidence Run ID:** `prompt20-20261014-095203-b9fd81`
**K?t qu? ki?m th?:**
- Python collector unit tests: 21/21 tests PASS (0.40s)
- Targeted integration tests (`MarketIdempotencyIntegrationTest` + `RequestFingerprintTest` + `MarketIdempotencyMigrationTest`): 14/14 tests PASS (12.3s)
- Full Reactor: 119/119 tests PASS (Core Lab: 34 tests, Backend App: 85 tests, 0 failures, 0 errors, 0 skipped, 58.0s)
- Evidence Promotion: 10/10 observation sets validated and promoted to `docs/evidence/prompt20/`

---

## 1. T?m T?t Nhi?m V? & Ph?n Chia C?c Ph?n

1. **Part A ? S?a constraint b?ng Forward Migration m?i:**
   - T?o file `V6__fix_market_idempotency_state_shape.sql`.
   - Gi? nguy?n v?n 100% c?c file migration V1?V5 ?? commit.
   - Th?m ?i?u ki?n `result_code IS NOT NULL` ? nh?nh `COMPLETED` ?? tri?t ti?u l? h?ng 3-valued logic (khi `result_code` l? `NULL`, bi?u th?c ??nh gi? ra `UNKNOWN`, m? `CHECK` ch?p nh?n `UNKNOWN`).

2. **Part B ? Lab Flyway tr?n PostgreSQL th?t:**
   - T?o `MarketIdempotencyMigrationTest.java` (package `com.aethercore.backend.market.idempotency.migration`).
   - `MG1`: Ch?y t?i V5, ch?n d?ng malformed `COMPLETED / null / null` (th?nh c?ng do l? h?ng V5), x?a d?ng n?y, seed d? li?u h?p l? (`PROCESSING`, `COMPLETED/NOT_AVAILABLE`, `COMPLETED/SUCCESS/purchaseId`), ch?y migrate V6 th?nh c?ng, ki?m tra d? li?u b?o to?n, checksum V1?V5 kh?ng ??i, ch?y migrate l?p l?i th?c thi 0 migration.
   - `MG2`: Ki?m tra to?n di?n truth table g?m 8 t? h?p state sau V6 (ch? 3 t? h?p h?p l? ???c ACCEPT, 5 t? h?p sai b? REJECT v?i SQLSTATE `23514`), ki?m tra UPDATE d?ng vi ph?m b? ch?n v? d?ng trong DB c?n nguy?n v?n.
   - `MG3`: T?i hi?n rollback DDL khi n?ng c?p tr?n b?ng c? d? li?u c? sai: ch?y t?i V5, ch?n d?ng malformed, ch?y migrate V6 n?m ngo?i l? Flyway (SQLSTATE `23514`). ?o l??ng th?c t?:
     - C?u l?nh `DROP CONSTRAINT` tr??c ?? ???c rollback, kh?i ph?c constraint V5 (`oldConstraintRestored = true`).
     - B?ng `flyway_schema_history` kh?ng b? d?nh b?n ghi failed V6 (`v6HistoryCountAfterFailure = 0`), version v?n l? 5.
     - Sau khi d?n d?p d?ng r?c nghi?p v?, ch?y l?i ??ng file V6 (v?i SHA-256 hash nguy?n v?n), migration th?nh c?ng 100% m? kh?ng c?n `flyway repair`.

3. **Part C ? Ho?n thi?n Evidence Idempotency hi?n c?:**
   - N?ng c?p `MarketIdempotencyIntegrationTest.java`:
     - ?o l??ng metadata th?c t? t? DB (`SHOW server_version` -> `16.15`, `SHOW transaction_isolation` -> `read committed`, `current_database()`, `current_schema()`).
     - ID1: ?o l??ng tr?ng th?i l?u tr? th?c t? (`COMPLETED`, `SUCCESS`) trong DB.
     - ID2: Ki?m tra b?n ghi l?u tr? kh?ng h? b? thay ??i tr??c v? sau khi th? g?i payload sai.
     - ID3: S? d?ng hai ??i t??ng service ri?ng bi?t, c?u h?nh timeout h?u h?n (`lock_timeout = 5s`, `statement_timeout = 8s`), truy v?n m?ng JDBC th?c t? `pg_blocking_pids(?::int)` ?? l?u danh s?ch blocker th?c thay v? add tay, b? sung shutdown/awaitTermination h?u h?n cho thread pool.
     - ID5 & ID7: ?o l??ng ??ng v? ghi nh?n snapshot DB th?c t? ngay sau rollback (`stockAfterRollback = 5`, `purchaseCountAfterRollback = 0`, `idempotencyCountAfterRollback = 0`) thay v? d?ng gi? tr? g?n c?ng.
     - ID6: Ki?m tra b?o to?n ngu?n cung tr??c v? sau restock (`finalStock (3) + totalSold (2) == 5`).
     - Isolation Guard: Assert to?n b? stock, purchase, idempotency kh?ng b? ? nhi?m.

4. **Part D ? Collector & Negative Fixtures:**
   - C?p nh?t `scripts/collect_lab_evidence.py`:
     - Th?m ki?m tra ki?u s? nghi?m ng?t (`type(v) is int and not isinstance(v, bool)`).
     - B? sung `_validate_migration_scenario` ki?m tra MG1, MG2, MG3.
     - Th?m `migration-observations.json` v?o t?p evidence b?t bu?c c?a Prompt 20.
   - C?p nh?t `scripts/tests/test_collect_lab_evidence.py`:
     - T?i hi?n v? ch?n ??ng 4 l? h?ng validator m? reviewer ph?t hi?n:
       1. `ID1 observed.stock = -99`
       2. `ID3 observed.blockingPidList = []`
       3. `ID3 observed.workerBPid = observed.workerAPid`
       4. `ID7 observed.purchaseCountAfterRetry = 99`
     - B? sung negative fixtures cho: rollback c?n claim s?t l?i (`idempotencyCountAfterRollback > 0`), MG3 ghi nh?n V6 success sau failed migration, MG3 retry thay ??i file hash, MG2 ch?p nh?n d?ng malformed `COMPLETED / null`.

5. **Part E ? T?i Li?u, B?i T?p, Trackers:**
   - ??nh ch?nh k? thu?t cho `M2-E6A.md` v? `prompt19-execution-notes.md`.
   - T?o t?i li?u m?i `docs/experiments/M2-E7A.md`.
   - T?o b?i t?p h?c vi?n `docs/exercises/M2-E7A-exercise.md` (gi? `[AWAITING_CANDIDATE]`).
   - C?p nh?t `prediction-log.md` v? `question-tracker.md`.

---

## 2. B?ng ??i So?t Nghi?m Thu Gates

| Gate | ?i?u ki?n nghi?m thu | K?t qu? th?c t? | Tr?ng th?i |
|---|---|---|---|
| **A** | V1?V5 gi? nguy?n v?n; V6 c? NULL guard r? r?ng | Checksum V1?V5 kh?ng ??i; V6 b? sung `result_code IS NOT NULL` | **PASS** |
| **B** | MG1: upgrade gi? valid data/history, V6 ch?y 1 l?n | `validDataPreserved=true`, `repeatMigrationCount=0`, `historyCount=6` | **PASS** |
| **C** | MG2: truth table 8 bi?n th? v? UPDATE ki?m ch?ng tr?n PG | 8/8 bi?n th? kh?p expected; UPDATE b? ch?n v?i SQLSTATE `23514` | **PASS** |
| **D** | MG3: DDL rollback ?o th?t; retry c?ng file V6 kh?ng repair | `oldConstraintRestored=true`, `v6HistoryCountAfterFailure=0`, retry th?nh c?ng c?ng hash | **PASS** |
| **E** | ID measurements l? gi? tr? query th?t; ID3 timeouts h?u h?n | ?o th?c t? serverVersion 16.15; ID3 array blocking th?t; ID5/ID7 measured rollback snapshot | **PASS** |
| **F** | Collector t? ch?i 4 reviewer loopholes v? negative fixtures | 21/21 Python tests pass; t? ch?i m?i mutation sai | **PASS** |
| **G** | Docs ??nh ch?nh, mapping ??ng v? b?i t?p ?? tr?ng | ?? c?p nh?t ??y ?? docs, trackers v? b?i t?p candidate | **PASS** |
