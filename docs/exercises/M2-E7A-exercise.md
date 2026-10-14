# B?i T?p M2-E7A: R?ng Bu?c CHECK, NULL Logic V? Rollback Khi N?ng C?p Schema

**M? b?i t?p:** M2-E7A-EX
**Ch? ??:** SQL 3-Valued Logic, Conditional CHECK Constraint, Flyway Forward Migration & Transactional DDL Rollback
**M?c ti?u:** T? x?y d?ng r?ng bu?c to?n v?n kh?ng b? loophole NULL, thi?t k? chi?n l??c migration khi DB c? d? li?u legacy l?i, v? hi?u s?u c? ch? rollback DDL c?a PostgreSQL.

---

## 1. B?i C?nh B?i T?p

M?t h? th?ng ph?n t?n thu th?p d? li?u vi?n tr?c (Telemetry Service) l?u c?c job x? l? v?o b?ng `telemetry_job`:

```sql
CREATE TABLE telemetry_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Kh? ??c nghi?p v? (Business Contract):
1. `status` ch? nh?n hai gi? tr?: `'PENDING'` ho?c `'DONE'`.
2. Khi `status = 'PENDING'`: job ch?a ho?n th?nh, do ?? `processed_at` **b?t bu?c ph?i l? `NULL`**.
3. Khi `status = 'DONE'`: job ?? x? l? xong, do ?? `processed_at` **b?t bu?c ph?i c? gi? tr? (`NOT NULL`)**.

---

## 2. Ph?n T? L?m 1: Vi?t R?ng Bu?c CHECK V? L?p Truth Table

M?t k? s? junior tr??c ?? ?? vi?t r?ng bu?c nh? sau:

```sql
ALTER TABLE telemetry_job ADD CONSTRAINT chk_telemetry_job_status_time CHECK (
    (status = 'PENDING' AND processed_at IS NULL)
    OR
    (status = 'DONE' AND processed_at IS NOT NULL)
);
```

### C?u h?i 1.1:
Bi?u th?c tr?n c? b? l? h?ng ch?p nh?n d?ng sai kh?ng n?u m?t ai ?? `INSERT` ho?c `UPDATE` m?t d?ng c? `status = 'DONE'` v? `processed_at = NULL`? H?y ph?n t?ch theo c? ch? SQL 3-valued logic (TRUE, FALSE, UNKNOWN).

*Tr? l?i c?a h?c vi?n:*
[AWAITING_CANDIDATE]

---

### C?u h?i 1.2:
H?y ho?n th?nh b?ng ch?n tr? (Truth Table) b?n d??i ?? ki?m tra to?n b? c?c kh? n?ng gi?a `status` v? `processed_at`:

| STT | status | processed_at | Mong mu?n nghi?p v? (ACCEPT/REJECT) | K?t qu? bi?u th?c CHECK (TRUE / FALSE / UNKNOWN) | PostgreSQL th?c t? (ACCEPT / REJECT) | Ghi ch? l? h?ng |
|---|---|---|---|---|---|---|
| 1 | `'PENDING'` | `NULL` | ACCEPT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | H?p l? |
| 2 | `'PENDING'` | `NOW()` | REJECT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | |
| 3 | `'DONE'` | `NOW()` | ACCEPT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | H?p l? |
| 4 | `'DONE'` | `NULL` | REJECT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | |
| 5 | `'FAILED'` | `NULL` | REJECT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | Status l? |
| 6 | `'FAILED'` | `NOW()` | REJECT | [AWAITING_CANDIDATE] | [AWAITING_CANDIDATE] | Status l? |

---

### C?u h?i 1.3:
H?y vi?t l?i c?u l?nh SQL ??nh ngh?a r?ng bu?c `chk_telemetry_job_status_time` ho?n ch?nh nh?t ?? b?o ??m kh?ng c? b?t k? tr?ng th?i invalid n?o l?t qua ???c.

*C?u l?nh SQL ?? xu?t:*
```sql
-- [AWAITING_CANDIDATE]
```

---

## 3. Ph?n T? L?m 2: X? L? N?ng C?p Schema Tr?n B?ng C? D? Li?u L?i

Gi? s? b?ng `telemetry_job` tr?n production ?? t?n t?i h?n 1 tri?u d?ng v? ?ang c? 50 d?ng l?i `status = 'DONE'` nh?ng `processed_at IS NULL`.

### C?u h?i 2.1:
N?u b?n tr?c ti?p t?o file migration Flyway m?i `V2__enforce_telemetry_status_time.sql` ch?a c?u l?nh:
```sql
ALTER TABLE telemetry_job ADD CONSTRAINT chk_telemetry_job_status_time CHECK (...);
```
?i?u g? s? x?y ra khi deploy ?ng d?ng l?n production? PostgreSQL s? ph?n ?ng th? n?o? ?ng d?ng Spring Boot c? kh?i ??ng ???c kh?ng?

*Tr? l?i c?a h?c vi?n:*
[AWAITING_CANDIDATE]

---

### C?u h?i 2.2:
T?i sao b?n **kh?ng ???c ph?p** s?a tr?c ti?p file migration `V1__create_telemetry_job.sql` ban ??u? H?y gi?i th?ch c? ch? validation c?a Flyway.

*Tr? l?i c?a h?c vi?n:*
[AWAITING_CANDIDATE]

---

### C?u h?i 2.3:
H?y ?? xu?t quy tr?nh n?ng c?p an to?n g?m c?c b??c c? th? (Pre-migration data cleanup / Data patch, Forward Migration, Validation) ?? deploy th?nh c?ng m? kh?ng g?y downtime hay crash ?ng d?ng.

*Quy tr?nh ?? xu?t:*
[AWAITING_CANDIDATE]

---

## 4. V?n ??p Ph?ng V?n (Oral Exam ? 60 ??n 90 Gi?y M?i C?u)

### C?u 1: "T?i sao trong SQL, m?nh ?? WHERE th? l?c b? NULL, nh?ng CHECK constraint l?i v?n cho ph?p d?ng ch?a NULL ?i qua?"
- **Th?i l??ng:** 60 - 90 gi?y
- **Ti?u ch? ch?m (Rubric):** Ph?i n?u ???c logic 3 gi? tr? (Three-Valued Logic); `WHERE` ch? nh?n `TRUE` (filter out `FALSE` v? `UNKNOWN`); `CHECK` t? ch?i khi `FALSE` v? ch?p nh?n khi `TRUE` ho?c `UNKNOWN`.
- *Ghi ch? ph?n x? c?a h?c vi?n:*
[AWAITING_CANDIDATE]

---

### C?u 2: "T?i sao khi ph?t hi?n file migration V5 c? l?i constraint, em kh?ng s?a lu?n n?i dung file V5 r?i ch?y l?i cho s?ch s? m? l?i ph?i t?o file V6?"
- **Th?i l??ng:** 60 - 90 gi?y
- **Ti?u ch? ch?m (Rubric):** Gi?i th?ch ???c t?nh b?t bi?n c?a migration ?? commit/deploy; c? ch? ki?m tra SHA-256 checksum c?a Flyway (`flyway_schema_history`); ph?n bi?t gi?a m?i tr??ng dev c?c b? v? production database ?ang ch?a d? li?u th?t.
- *Ghi ch? ph?n x? c?a h?c vi?n:*
[AWAITING_CANDIDATE]

---

### C?u 3: "Sau khi m?t file migration Flyway b? fail gi?a ch?ng tr?n PostgreSQL, em s? query nh?ng th?ng tin g? t? database tr??c khi quy?t ??nh th? l?i?"
- **Th?i l??ng:** 60 - 90 gi?y
- **Ti?u ch? ch?m (Rubric):**
  1. Ki?m tra transactional DDL c?a database: l?nh tr??c ?? (v? d? DROP CONSTRAINT) c? b? rollback kh?ng qua `pg_constraint`.
  2. Ki?m tra `flyway_schema_history` xem c? d?ng migration b? ??nh d?u `success = false` hay transaction ?? rollback to?n b? kh?ng ghi nh?n.
  3. Query b?ng nghi?p v? ?? t?m ch?nh x?c c?c d?ng vi ph?m d? li?u g?y ra l?i (v? d? t?m c?c d?ng `COMPLETED / null / null`).
- *Ghi ch? ph?n x? c?a h?c vi?n:*
[AWAITING_CANDIDATE]
