# AetherCore Environment Specifications (Locked Versions)

- **Ngày ghi nhận thực tế:** 09/10/2026
- **Operating System:** Windows 11 (build 10.0, amd64)

## 1. Công cụ Cài đặt & Phiên bản Khóa (Locked Versions)
| Thành phần | Phiên bản thực tế | Ghi chú & Lý do chọn |
|---|---|---|
| **JDK Runtime / Compiler** | `Oracle JDK 22 (build 22+36-2370)` | Tương thích hoàn toàn với bytecode target Java 21 LTS (`-release 21`). |
| **Build Tool (Maven)** | `Apache Maven 3.9.12` | Hỗ trợ đầy đủ compiler release flag và multi-module project. |
| **Spring Boot** | `3.3.4` | Bản release ổn định của Spring Boot 3.x, tương thích JDK 21/22. |
| **PostgreSQL Driver** | `42.7.4` | Driver JDBC PostgreSQL chính thức hỗ trợ Type-4 direct socket protocol. |
| **Testcontainers** | `1.20.2` | Hỗ trợ khởi tạo PostgreSQL container cô lập trong JUnit 5. |
| **Database Image** | `postgres:16-alpine` | Phiên bản PostgreSQL 16 tinh gọn và nhanh chóng cho testing. |
| **Docker CLI / Engine** | `Docker CLI 29.5.3` | Engine daemon hiện tại chưa khởi chạy (Local Docker Desktop đang tắt). |

## 2. Lệnh kiểm tra môi trường đã chạy:
```bash
java -version
javac -version
mvn -v
docker --version
```

## 3. Giới hạn & Trở ngại Thực tế (Real Constraints):
- Docker Desktop daemon trên máy chưa được khởi chạy (Docker API pipe unavailable). Do đó, integration test chạy qua Testcontainers sẽ ghi nhận lỗi kết nối container daemon thực tế và không được dùng test mock để báo xanh giả lập.
