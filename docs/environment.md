# AETHERCORE — ENVIRONMENT SPECIFICATION & VERIFICATION LOG
**Ngày cập nhật:** 09/10/2026  
**Trạng thái môi trường:** Đã kiểm chứng có bằng chứng (PARTIALLY_VERIFIED - Chờ Docker Engine)

---

## 1. Phiên bản công cụ thực tế trên máy host

| Công cụ / Runtime | Phiên bản thực tế | Lệnh kiểm tra | Ghi chú & Giới hạn xác minh |
|---|---|---|---|
| **Host Java Runtime** | Oracle JDK 22 (build 22+36-2370) | `java -version` | Runtime máy host là JDK 22; mã nguồn được biên dịch với `--release 21` để tương thích bytecode Java 21. |
| **Java Compiler** | javac 22 | `javac -version` | Sử dụng cờ `-release 21` trong `maven-compiler-plugin`. |
| **Apache Maven** | 3.9.12 | `mvn -v` | Sử dụng runtime `C:\Program Files\Java\jdk-22`. |
| **Spring Boot** | 3.3.4 | `pom.xml` / BOM | Quản lý qua BOM `spring-boot-dependencies:3.3.4`. |
| **PostgreSQL Driver** | 42.7.4 | `backend-app/pom.xml` | Kế thừa version từ Spring Boot BOM. |
| **Database Migration** | Flyway 10.10.0 (`flyway-core` + `flyway-database-postgresql`) | `backend-app/pom.xml` | Cơ chế migration phiên bản duy nhất tại `db/migration/V1__...` & `V2__...`. |
| **Testcontainers** | 1.20.2 | `pom.xml` / BOM | Quản lý qua `testcontainers-bom:1.20.2`. |
| **PostgreSQL Docker Tag** | `postgres:16-alpine` | `LabEntryIntegrationTest.java` | Tag động (mutable tag); image digest sẽ được ghi lại khi container được kéo thực tế. |
| **Docker CLI** | 29.5.3 (context `desktop-linux`) | `docker info` | CLI sẵn sàng; Docker daemon Windows named pipe (`//./pipe/dockerDesktopLinuxEngine`) chưa khởi động. |

---

## 2. Kết quả kiểm chứng Build & Test

- **Production & Test Source Compilation:**  
  `mvn test-compile` -> **`BUILD SUCCESS`** (0 errors, bytecode target Java 21).
- **Test Discovery & Execution:**  
  `mvn test` -> Surefire 3.5.0 JUnitPlatformProvider đã phát hiện `LabEntryIntegrationTest`.
- **Test Failure Mode:**  
  Bị chặn tại giai đoạn `GenericContainer.start()` với lỗi: `java.lang.IllegalStateException: Could not find a valid Docker environment` do Docker engine chưa chạy.
- **Cam kết kỹ thuật:** Tuyệt đối không mock database hoặc dùng H2 in-memory để làm xanh test; giữ nguyên Testcontainers PostgreSQL làm tiêu chuẩn kiểm chứng thật duy nhất.
