# FlowDock foundation

Repository nền tảng cho lab Java/Spring/PostgreSQL/Kafka. Đây chưa phải MVP và chưa có xử lý payment.

`core` đóng gói migration dùng chung và integration tests; `backend` và `worker` là hai Spring Boot application riêng có Actuator health. Worker chưa có Kafka listener. Migration V1 chỉ tạo namespace `flowdock`, không tạo schema nghiệp vụ tương lai.

## Phân định: Kiến trúc đã duyệt (Accepted Design) vs. Hiện trạng mã nguồn (Implemented Foundation)

- **Hiện trạng mã nguồn (Foundation implemented):**
  - Khung dự án Maven đa module (`core`, `backend`, `worker`), Spring Boot 3.5.16, Java 21 Enforcer.
  - Endpoints giám sát Actuator health (`/actuator/health`).
  - Migration Flyway V1 chỉ khởi tạo namespace cơ sở dữ liệu `flowdock`.
  - Hạ tầng Docker Compose cục bộ gồm PostgreSQL 17.11 và Apache Kafka 4.1.2 KRaft.
  - Bộ kiểm thử tích hợp `InfrastructureIT` xác minh kết nối broker và database thật độc lập.
- **Kiến trúc đã duyệt (Accepted Design - Chuẩn bị cho P1–P8):**
  - Toàn bộ các đặc tả kỹ thuật nghiệp vụ đã được phê duyệt chính thức tại [Tài liệu Hợp đồng Kỹ thuật (docs/contracts.md)](docs/contracts.md) và [Chỉ mục 10 ADR Gốc (docs/adr/index.md)](docs/adr/index.md).
  - Bao gồm: Hợp đồng tiền tệ `BIGINT` USD cents (1..9007199254740991), Canonical JSON Hash JCS (RFC 8785) + SHA-256, Ingress API validation, Synchronous worker listener với atomic DB effect, Idempotent receipt insertion & deduplication, Phân loại lỗi (3 lần retry cho poison, DLQ ngay cho schema/identity, dừng consumer khi mất hạ tầng), Xả cạn partition vật lý (`committed next-offset >= max confirmed offset + 1`), Vòng đời 7 trạng thái & 4 phán quyết nghiệm thu, và Cơ chế tiêm lỗi crash worker (`Runtime.getRuntime().halt(1)`).
  - **Lưu ý:** Các tính năng nghiệp vụ và độ tin cậy nêu trên **chưa được cài đặt trong mã nguồn runtime hiện tại**; việc cài đặt sẽ được tiến hành tuần tự theo các lát cắt dọc từ Phase P1 trở đi. Không suy diễn trạng thái kiểm thử hay hoàn thành cho các tính năng chưa implement.

## Versions và nguồn

| Thành phần | Version pin |
|---|---|
| Java release | 21 (local Microsoft OpenJDK 21.0.12+8; CI Temurin 21.0.12+8) |
| Spring Boot parent/BOM | 3.5.16 |
| Spring Framework / Kafka client | 6.2.19 / 3.9.2 (theo BOM) |
| Flyway / PostgreSQL JDBC | 11.7.2 / 42.7.11 (theo BOM) |
| JUnit Jupiter | 5.12.2 (theo BOM) |
| Maven / Wrapper | 3.9.11 / 3.3.4, only-script |
| Maven compiler / Surefire + Failsafe / Enforcer | 3.14.1 / 3.5.6 / 3.6.2 |
| Kafka broker image | apache/kafka:4.1.2 |
| PostgreSQL image | postgres:17.11-bookworm |

[Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) xác nhận hỗ trợ Java 21. Dependency/plugin versions dùng parent/BOM cố định; không nâng riêng từng dependency. [BOM coordinates](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [Kafka Docker 4.1](https://kafka.apache.org/41/getting-started/docker/), [PostgreSQL release notes](https://www.postgresql.org/docs/release/), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

CI dùng selector Temurin `21.0.12+8.0.LTS` theo SemVer trong [catalog GA Adoptium](https://api.adoptium.net/v3/assets/feature_releases/21/ga?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&page_size=5&project=jdk&vendor=eclipse); đây vẫn là runtime Java 21.0.12 build 8, không phải nâng version.

Policy: pin exact Maven parent, dependencies ngoài BOM, plugins và image tags; không dùng `latest`/range cho dependencies. Enforcer dùng range chỉ để kiểm tra JVM là Java 21. Actions pin full commit SHA kèm version: checkout v7.0.1 và setup-java v6.0.1. Image tags vẫn có thể được registry cập nhật; digest của image đã chạy được ghi trong verification report. Khi nâng version phải chạy lại gates. Wrapper distribution có SHA-256; `only-script` không cần wrapper JAR.

## Windows: Java và build

Cần JDK 21, Git, Docker Desktop dùng Linux containers/WSL2. Không cần Maven global. Giữ Java 17 cho project khác; chỉ đặt JDK 21 trong terminal đang dùng:

```powershell
cd <thu-muc-checkout-flowdock>
$env:JAVA_HOME = Read-Host 'Duong dan JDK 21 da cai tren may'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
javac -version
.\mvnw.cmd -v
.\mvnw.cmd -B -ntp clean verify
```

Không dùng `setx`, không thay global PATH/JAVA_HOME. Nếu chưa có JDK 21, tải Temurin từ [Adoptium chính thức](https://adoptium.net/temurin/releases/?version=21); không gỡ Java 17. `.idea` và JDK paths cá nhân không cần để build.

## Dev mode: hạ tầng Docker, Java chạy trên host

Kiểm tra ports trước khi chạy. Defaults: Postgres `127.0.0.1:15432`, Kafka EXTERNAL `127.0.0.1:19092`, backend `127.0.0.1:18080`, worker `127.0.0.1:18081`.

```powershell
docker version
docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml up -d --wait --wait-timeout 180 postgres kafka
docker compose -f infra/compose.yaml run --rm kafka-init
docker compose -f infra/compose.yaml ps
.\mvnw.cmd -B -ntp -Pintegration verify
```

Chạy mỗi ứng dụng trong terminal riêng sau build:

```powershell
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
java -jar worker/target/worker-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
Invoke-RestMethod http://127.0.0.1:18081/actuator/health
docker compose -f infra/compose.yaml exec -T postgres psql -U flowdock_lab -d flowdock -c 'SELECT version(), current_database();'
docker compose -f infra/compose.yaml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe --topic flowdock.payment.events
```

Health trả `{"status":"UP"}` khi ứng dụng và datasource hoạt động. Health chưa kiểm tra Kafka; integration tests kiểm tra Kafka riêng. Dừng Java bằng Ctrl+C. Dừng riêng hạ tầng FlowDock và giữ dữ liệu: `docker compose -f infra/compose.yaml down`. Không dùng `down -v`.

Compose project cố định `flowdock`, volumes `flowdock_postgres-data` và `flowdock_kafka-data`. Kafka chạy UID 1000; init container UID 0 chỉ chown thư mục gốc volume Kafka thuộc project. Host ports bind localhost, INTERNAL là `kafka:29092`, CONTROLLER là `kafka:29093` và không publish. Cấu hình dựa trên [ví dụ Apache đúng version](https://github.com/apache/kafka/blob/4.1.2/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml). PLAINTEXT và credentials `flowdock_lab` / `flowdock_lab_only` chỉ dành cho lab local.

Hai topic `flowdock.payment.events` và `flowdock.payment.dlq` có 3 partitions, RF=1. `kafka-init` dùng `--if-not-exists`; chạy lại an toàn. Test kiểm tra shape thực tế, không tự sửa topic đã có cấu hình khác. Resource caps: Kafka 1 GiB/1.5 CPU, Postgres 512 MiB/1 CPU; Kafka heap 256–512 MiB, retention 24 giờ.

`infra/.env.example` dùng được nhưng không bắt buộc copy: Compose có cùng lab defaults. Nếu cần override, copy sang `infra/.env` đã ignore. Khi đổi DB port/name/credentials, đặt tương ứng `FLOWDOCK_DB_URL`, `FLOWDOCK_DB_USER`, `FLOWDOCK_DB_PASSWORD` cho Java; đổi Kafka port thì đặt `FLOWDOCK_KAFKA_BOOTSTRAP` cho integration tests. Backend/worker port dùng `FLOWDOCK_BACKEND_PORT` / `FLOWDOCK_WORKER_PORT`. JDBC URL của test dùng dạng `jdbc:postgresql://host:port/database`, không thêm query parameters. `.env` của Compose không tự được nạp vào Java host.

### Troubleshooting: Ranh giới Host vs Container & Profile local (Fail-Fast)

- **Nguyên tắc cô lập Host-Only:** Toàn bộ cổng dịch vụ (`15432`, `19092`, `18080`, `18081`) được bind độc quyền vào `127.0.0.1` (loopback), ngăn chặn truy cập ngoài máy và bảo vệ an toàn cho môi trường lab.
- **Bắt buộc `--spring.profiles.active=local`:** Khi chạy backend/worker trên Windows Host, tham số này kích hoạt profile `local` để nạp cấu hình kết nối mặc định (Postgres `127.0.0.1:15432`, user `flowdock_lab`). Nếu thiếu profile này, ứng dụng sẽ cố gắng resolve các placeholder chưa khai báo và kích hoạt cơ chế fail-fast ngắt tiến trình khởi động.
- **Phân định rõ Listener Kafka:**
  - *Ứng dụng Java trên Host:* Bắt buộc kết nối qua `127.0.0.1:19092` (`EXTERNAL` listener).
  - *Container nội bộ Docker:* Kết nối qua `kafka:29092` (`INTERNAL` listener). Cấm cấu hình app trên host trỏ vào `kafka:29092` vì tên miền container không thể phân giải trực tiếp từ Windows Host.
- **Ranh giới cấu hình `.env`:** File `infra/.env` chỉ được nạp bởi Docker Compose; tiến trình Java trên Windows Host **không tự động nhận** các biến này. Để ghi đè cấu hình cho Java, hãy thiết lập trực tiếp biến môi trường shell (`$env:FLOWDOCK_DB_URL`, `$env:FLOWDOCK_KAFKA_BOOTSTRAP`) hoặc khai báo trong IntelliJ Run Configuration.

Full application Compose chưa được làm ở scope này. Không có frontend, simulator, coordinator, payment/receipt logic, retry/DLQ handler, crash hook hoặc dashboard. Reliability scenarios chưa implement/test.

## IntelliJ IDEA và VS Code

Open root `pom.xml` như Maven project. Trong Project Structure đặt Project SDK = JDK 21, Language level = 21. Trong Settings → Build Tools → Maven chọn project Maven Wrapper; Importing → JDK for importer = 21; Runner → JRE = 21. Reload Maven.

Tạo hai Application run configurations: `BackendApplication` (classpath module backend), `WorkerApplication` (classpath module worker), JRE 21; program arguments `--spring.profiles.active=local`. Khởi động Compose trước rồi Run/Debug từng app. Có thể đặt env overrides trong run configuration cá nhân. Không commit `.idea`, `*.iml` hoặc absolute JDK paths.

VS Code mở root repo để sửa docs/config. Frontend được để sau; chưa cần npm install.

## Tests, CI và public repo

`clean verify` chạy focused health test: HTTP 200, status UP không lộ details, `/actuator/env` không được expose. Test đó chủ động loại datasource/Flyway để không phụ thuộc Docker; không chứng minh DB integration.

`-Pintegration verify` chạy thêm `InfrastructureIT` qua Failsafe, không skip nếu dependency unavailable. Test DB tạo database `flowdock_verify_<UUID>` trên riêng Postgres FlowDock, migrate/validate/chạy lại và kiểm tra namespace; chỉ drop DB do test tạo. Test Kafka kiểm tra cả hai topic rồi publish fixture thực vào events partition 0, đọc đúng acknowledged offset, không commit consumer-group offsets. Test ghi topic/partition/offset; đây là connectivity evidence, chưa phải payment/reliability evidence.

CI dùng `ubuntu-24.04` GitHub hosted runner có Docker: job focused build/test, job infrastructure chạy Compose, topic init hai lần, tests thật và health của cả hai JAR. Timeout rõ ràng; failure làm job fail. Xem trạng thái thực tế trong [verification report](docs/verification.md); không suy CI PASS từ local build.

Trước commit/push: `git check-ignore -v infra/.env core/target/example.class`, `git check-ignore infra/.env.example` (exit 1 mong đợi), `git diff --cached --check`, review `git diff --cached`. Không commit token, keys, logs, local config hoặc caches. Giữ fixtures an toàn và shareable VS Code launch/tasks nếu sau này cần. Không có dedicated secret scanner cài sẵn tại lần setup; review thủ công/pattern scan có giới hạn, không chứng minh tuyệt đối không có secrets.

[Hợp đồng kỹ thuật P0 đã chốt](docs/contracts.md), [Chỉ mục ADR (ADR-000 đến ADR-010)](docs/adr/index.md), [Quyết định foundation ADR-000](docs/adr/adr-000-foundation.md) và [commands/results đã chạy](docs/verification.md).
