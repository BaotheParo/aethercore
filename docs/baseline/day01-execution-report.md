# BÁO CÁO THỰC THI NGÀY 1 — AETHERCORE
**Ngày thực tế thực thi:** 09/10/2026  
**Chủ trì:** Orchestrator & Agent Tree (Explorer, Builder, Verifier, Interview Coach)

---

## 1. Mục tiêu và Phạm vi Ngày 1
- Đọc và khóa môi trường phát triển dựa trên kế hoạch 42 ngày.
- Xây dựng skeleton multi-module Maven tối thiểu:
  - core-lab: Java thuần (Java 21/22), không Spring.
  - backend-app: Spring Boot 3.3.4, Spring JDBC, PostgreSQL Driver, Testcontainers.
- Dựng luồng HTTP API: GET /api/lab/entries/{id} đọc dữ liệu PostgreSQL qua Constructor Injection & JdbcTemplate.
- Viết integration test thật với PostgreSQL (Testcontainers 1.20.2).
- Khởi tạo hệ thống tài liệu: environment.md, question-tracker.md, prediction-log.md, README.md.
- Đưa ra 10 câu hỏi baseline phỏng vấn và bài toán dự đoán M1-E1 (PlayerKey HashMap lookup).

---

## 2. Bảng kiểm chứng kỹ thuật

| Hạng mục | Trạng thái | Chi tiết |
|---|:---:|---|
| Khóa phiên bản môi trường | **PASS** | JDK 22, Maven 3.9.12, Spring Boot 3.3.4, Postgres 16-alpine, Testcontainers 1.20.2 |
| Maven Compile | **PASS** | mvn clean compile -DskipTests -> BUILD SUCCESS |
| HTTP API & SQL | **PASS** | LabEntryController, LabEntryRepository, schema.sql, data.sql |
| Integration Test | **BLOCKED** | Testcontainers test sẵn sàng, chờ bật Docker Desktop daemon |
| 10 câu Baseline | **AWAITING_CANDIDATE** | Chờ ứng viên trả lời |
| Dự đoán M1-E1 | **AWAITING_CANDIDATE** | Chờ ứng viên trả lời |

---

## 3. Danh mục tệp đã tạo
- pom.xml (Root)
- core-lab/pom.xml & core-lab/src/main/java/com/aethercore/core/PlayerKey.java
- ackend-app/pom.xml
- ackend-app/src/main/java/com/aethercore/backend/AetherCoreApplication.java
- ackend-app/src/main/java/com/aethercore/backend/model/LabEntry.java
- ackend-app/src/main/java/com/aethercore/backend/repository/LabEntryRepository.java
- ackend-app/src/main/java/com/aethercore/backend/controller/LabEntryController.java
- ackend-app/src/main/resources/schema.sql & data.sql & pplication.properties
- ackend-app/src/test/java/com/aethercore/backend/LabEntryIntegrationTest.java
- sql/01_init_lab_entry.sql
- docs/environment.md
- docs/question-tracker.md
- docs/prediction-log.md
- README.md
- .gitignore
