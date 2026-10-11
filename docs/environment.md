# AETHERCORE â€” ENVIRONMENT SPECIFICATION & VERIFICATION LOG
**NgÃ y cáº­p nháº­t:** 11/10/2026 (Prompt 08)  
**Tráº¡ng thÃ¡i mÃ´i trÆ°á»ng:** `VERIFIED` (ÄÃ£ kiá»ƒm chá»©ng Ä‘áº§y Ä‘á»§ trÃªn Testcontainers PostgreSQL 16.15 vÃ  Docker Desktop Engine)

---

## 1. PhiÃªn báº£n cÃ´ng cá»¥ thá»±c táº¿ trÃªn mÃ¡y host

| CÃ´ng cá»¥ / Runtime | PhiÃªn báº£n thá»±c táº¿ | Lá»‡nh kiá»ƒm tra | Ghi chÃº & Giá»›i háº¡n xÃ¡c minh |
|---|---|---|---|
| **Host Java Runtime** | Oracle JDK 22 (build 22+36-2370) | `java -version` | Runtime mÃ¡y host lÃ  JDK 22; mÃ£ nguá»“n Ä‘Æ°á»£c biÃªn dá»‹ch vá»›i `--release 21` Ä‘á»ƒ tÆ°Æ¡ng thÃ­ch bytecode Java 21. |
| **Java Compiler** | javac 22 | `javac -version` | Sá»­ dá»¥ng cá» `-release 21` trong `maven-compiler-plugin`. |
| **Apache Maven** | 3.9.12 | `mvn -v` | Sá»­ dá»¥ng runtime `C:\Program Files\Java\jdk-22`. |
| **Spring Boot** | 3.3.4 | `pom.xml` / BOM | Quáº£n lÃ½ qua BOM `spring-boot-dependencies:3.3.4`. |
| **PostgreSQL Driver** | 42.7.4 | `backend-app/pom.xml` | Káº¿ thá»«a version tá»« Spring Boot BOM. |
| **Database Migration** | Flyway 10.10.0 (`flyway-core` + `flyway-database-postgresql`) | `backend-app/pom.xml` | CÆ¡ cháº¿ migration phiÃªn báº£n duy nháº¥t táº¡i `db/migration/V1..V3`. |
| **Testcontainers** | **1.21.4** (resolved) | `mvn dependency:tree` | Khai bÃ¡o trong root `pom.xml` qua `testcontainers-bom:1.21.4`, Ä‘áº·t **trÆ°á»›c** Spring Boot BOM Ä‘á»ƒ override báº£n 1.19.8 cÅ©. |
| **docker-java** | **3.4.2** (resolved) | `mvn dependency:tree` | Äi kÃ¨m Testcontainers 1.21.4, há»— trá»£ Docker Engine API 1.54 / Docker 29+. |
| **PostgreSQL Docker Image** | `postgres:16-alpine` (PostgreSQL 16.15) | Testcontainers runtime | Khá»Ÿi cháº¡y tá»± Ä‘á»™ng bá»Ÿi Testcontainers trÃªn database test riÃªng biá»‡t. |
| **Docker Engine / Desktop** | Engine 29.5.3 (API 1.54, context `desktop-linux`) | `docker info` | Cháº¡y container Linux trÃªn ná»n WSL2 qua local Windows named pipe máº·c Ä‘á»‹nh (`npipe:////./pipe/docker_engine`). |

---

## 2. Lá»‹ch sá»­ cháº©n Ä‘oÃ¡n mÃ´i trÆ°á»ng (Environment Evolution)

1. **Prompt 01â€“06 (Engine Stopped):**
   - Docker daemon chÆ°a khá»Ÿi Ä‘á»™ng trÃªn mÃ¡y host Windows (`open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`).
   - MÃ£ nguá»“n Ä‘Æ°á»£c báº£o Ä‘áº£m cháº¥t lÆ°á»£ng qua `mvn test-compile` mÃ  khÃ´ng dÃ¹ng H2/Mock Ä‘á»ƒ lÃ m giáº£ káº¿t quáº£.
2. **Prompt 07 (Engine Active, Client Version Incompatibility):**
   - Docker Desktop Engine Ä‘Ã£ Ä‘Æ°á»£c báº­t vÃ  cháº¡y Ä‘Æ°á»£c container qua CLI (`docker run --rm hello-world`).
   - Tuy nhiÃªn, Testcontainers gáº·p lá»—i `BadRequestException (Status 400)` trÃªn named pipe do Maven resolve Testcontainers báº£n `1.19.8` tá»« Spring Boot BOM (chÆ°a tÆ°Æ¡ng thÃ­ch vá»›i Docker Engine 29.5.3 API 1.54).
3. **Prompt 08 (Resolved via Dependency Ordering & 1.21.4 Upgrade):**
   - NÃ¢ng Testcontainers lÃªn `1.21.4` vÃ  Ä‘áº£o thá»© tá»± BOM trong root `pom.xml`: `testcontainers-bom:1.21.4` Ä‘Æ°á»£c import trÆ°á»›c `spring-boot-dependencies:3.3.4`.
   - Káº¿t quáº£: `testcontainers:1.21.4` vÃ  `docker-java:3.4.2` káº¿t ná»‘i thÃ nh cÃ´ng qua Windows named pipe local máº·c Ä‘á»‹nh mÃ  khÃ´ng cáº§n má»Ÿ TCP khÃ´ng an toÃ n.
   - ToÃ n bá»™ 48 tests trÃªn reactor (`core-lab` 34 tests, `backend-app` 14 tests) cháº¡y thÃ nh cÃ´ng `100% PASS` trÃªn PostgreSQL 16.15 tháº­t.

4. **Prompt 09 (M2-E1B Verified, Flyway V4 Migration):**
   - Bổ sung Flyway Migration `V4__add_market_stock_version.sql` (`version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)`).
   - Triển khai và kiểm chứng 3 chiến lược concurrency control: Atomic Conditional UPDATE, Optimistic Version Check (kèm exponential backoff và jitter), Pessimistic Row Locking (`SELECT ... FOR UPDATE` quan sát qua `pg_blocking_pids()`).
   - Tổng cộng **56 tests** trên toàn bộ reactor (`core-lab` 34 tests, `backend-app` 22 tests) chạy thành công `100% PASS` trên PostgreSQL 16.15 thật.