# AetherCore — Benchmark Lab & Reverse Learning System

Dự án học tập và thực nghiệm chuyên sâu về Java Core, Spring Boot, Concurrency, SQL & Performance Optimization.

## 🏗️ Cấu trúc Skeleton Tối thiểu (Multi-Module Maven)

```text
aethercore/
├── pom.xml                   # Root parent POM (Quản lý dependency version)
├── core-lab/                 # Module Java thuần (M1 experiments, no Spring)
│   └── src/
│       └── main/java/com/aethercore/core/PlayerKey.java
├── backend-app/              # Module Spring Boot 3.x (HTTP API + JDBC + Postgres)
│   └── src/
│       ├── main/java/com/aethercore/backend/
│       │   ├── AetherCoreApplication.java
│       │   ├── controller/LabEntryController.java
│       │   ├── repository/LabEntryRepository.java
│       │   └── model/LabEntry.java
│       └── test/java/com/aethercore/backend/LabEntryIntegrationTest.java
├── docs/                     # Tài liệu học tập & theo dõi
│   ├── environment.md        # Khóa phiên bản công cụ (JDK, Maven, Spring Boot, Postgres)
│   ├── question-tracker.md   # Theo dõi 10 câu baseline & ngân hàng câu hỏi
│   └── prediction-log.md     # Nhật ký dự đoán trước khi thực nghiệm
└── sql/                      # Scripts khởi tạo database
    └── 01_init_lab_entry.sql
```

## 🔄 Đường Đi Của Request (Request Execution Flow)

```text
[HTTP Client Request: GET /api/lab/entries/entry-01]
       │
       ▼
[Spring DispatcherServlet]
       │
       ▼
[LabEntryController (Constructor Injection)]
       │
       ▼
[LabEntryRepository (Spring JdbcTemplate)]
       │
       ▼
[HikariCP Connection Pool -> PostgreSQL Connection Socket]
       │
       ▼
[PostgreSQL Database: SELECT id, label FROM lab_entry WHERE id = ?]
       │
       ▼
[RowMapper -> Java Record: LabEntry("entry-01", "AetherCore Baseline...")]
       │
       ▼
[HTTP Response: 200 OK + JSON Body]
```

## 🚀 Hướng dẫn Chạy Build & Test

```bash
# Compile toàn bộ multi-module project
mvn clean compile

# Chạy test trong core-lab
mvn test -pl core-lab

# Chạy backend-app
mvn spring-boot:run -pl backend-app
```
