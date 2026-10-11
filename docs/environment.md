# AETHERCORE — ENVIRONMENT SPECIFICATION & VERIFICATION LOG
**Ngày cập nhật:** 11/10/2026 (Prompt 08)  
**Trạng thái môi trường:** `VERIFIED` (Đã kiểm chứng đầy đủ trên Testcontainers PostgreSQL 16.15 và Docker Desktop Engine)

---

## 1. Phiên bản công cụ thực tế trên máy host

| Công cụ / Runtime | Phiên bản thực tế | Lệnh kiểm tra | Ghi chú & Giới hạn xác minh |
|---|---|---|---|
| **Host Java Runtime** | Oracle JDK 22 (build 22+36-2370) | `java -version` | Runtime máy host là JDK 22; mã nguồn được biên dịch với `--release 21` để tương thích bytecode Java 21. |
| **Java Compiler** | javac 22 | `javac -version` | Sử dụng cờ `-release 21` trong `maven-compiler-plugin`. |
| **Apache Maven** | 3.9.12 | `mvn -v` | Sử dụng runtime `C:\Program Files\Java\jdk-22`. |
| **Spring Boot** | 3.3.4 | `pom.xml` / BOM | Quản lý qua BOM `spring-boot-dependencies:3.3.4`. |
| **PostgreSQL Driver** | 42.7.4 | `backend-app/pom.xml` | Kế thừa version từ Spring Boot BOM. |
| **Database Migration** | Flyway 10.10.0 (`flyway-core` + `flyway-database-postgresql`) | `backend-app/pom.xml` | Cơ chế migration phiên bản duy nhất tại `db/migration/V1..V3`. |
| **Testcontainers** | **1.21.4** (resolved) | `mvn dependency:tree` | Khai báo trong root `pom.xml` qua `testcontainers-bom:1.21.4`, đặt **trước** Spring Boot BOM để override bản 1.19.8 cũ. |
| **docker-java** | **3.4.2** (resolved) | `mvn dependency:tree` | Đi kèm Testcontainers 1.21.4, hỗ trợ Docker Engine API 1.54 / Docker 29+. |
| **PostgreSQL Docker Image** | `postgres:16-alpine` (PostgreSQL 16.15) | Testcontainers runtime | Khởi chạy tự động bởi Testcontainers trên database test riêng biệt. |
| **Docker Engine / Desktop** | Engine 29.5.3 (API 1.54, context `desktop-linux`) | `docker info` | Chạy container Linux trên nền WSL2 qua local Windows named pipe mặc định (`npipe:////./pipe/docker_engine`). |

---

## 2. Lịch sử chẩn đoán môi trường (Environment Evolution)

1. **Prompt 01–06 (Engine Stopped):**
   - Docker daemon chưa khởi động trên máy host Windows (`open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`).
   - Mã nguồn được bảo đảm chất lượng qua `mvn test-compile` mà không dùng H2/Mock để làm giả kết quả.
2. **Prompt 07 (Engine Active, Client Version Incompatibility):**
   - Docker Desktop Engine đã được bật và chạy được container qua CLI (`docker run --rm hello-world`).
   - Tuy nhiên, Testcontainers gặp lỗi `BadRequestException (Status 400)` trên named pipe do Maven resolve Testcontainers bản `1.19.8` từ Spring Boot BOM (chưa tương thích với Docker Engine 29.5.3 API 1.54).
3. **Prompt 08 (Resolved via Dependency Ordering & 1.21.4 Upgrade):**
   - Nâng Testcontainers lên `1.21.4` và đảo thứ tự BOM trong root `pom.xml`: `testcontainers-bom:1.21.4` được import trước `spring-boot-dependencies:3.3.4`.
   - Kết quả: `testcontainers:1.21.4` và `docker-java:3.4.2` kết nối thành công qua Windows named pipe local mặc định mà không cần mở TCP không an toàn.
   - Toàn bộ 48 tests trên reactor (`core-lab` 34 tests, `backend-app` 14 tests) chạy thành công `100% PASS` trên PostgreSQL 16.15 thật.
