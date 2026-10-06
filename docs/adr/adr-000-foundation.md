# ADR-000: Development foundation

Status: accepted for setup scope, 2026-10-05.
Note: Re-indexed from initial setup document (`docs/adr-001-foundation.md`) to preserve the original 10-ADR business architecture sequence (ADR-001 through ADR-010).

Build Java 21 bằng Maven Wrapper 3.9.11, dùng Spring Boot parent 3.5.16 để quản lý dependency/plugin versions thống nhất. Existing Java 17 và global Git/environment config được giữ nguyên. IntelliJ chọn JDK riêng cho project/importer/runner.

Một Maven reactor gồm core/backend/worker. Core chứa migration namespace dùng chung và opt-in infrastructure tests; hai executable application độc lập có health endpoint. Worker chỉ có management HTTP để kiểm tra startup/DB, chưa xử lý event. Backend/worker cùng migration location; Flyway locking quản lý startup, clean disabled.

Dev mode đặt Kafka/Postgres trong riêng Compose project FlowDock, Java trên Windows host. Listener EXTERNAL quảng bá localhost:19092; INTERNAL dùng kafka:29092; controller không publish. KRaft combined node phù hợp lab, không chứng minh failover. Topic RF=1, 3 partitions. Health dependency + idempotent init cho startup có kiểm soát.

Migration chỉ tạo application namespace và Flyway history. Không chốt event identities, money unit, hashing, run states hoặc payment schema trong scope setup. Không đánh dấu P0 hoặc các phase trong kế hoạch bên ngoài hoàn thành.

Focused tests độc lập Docker. Infrastructure tests là profile riêng, bắt buộc broker/database thật và fail khi unavailable; CI runner Docker khởi động hạ tầng trước. Test-created DB có UUID được cleanup riêng; không clean app database. Kafka fixture là payload lab có marker, không phải event contract nghiệp vụ.

Public lab defaults không phải secrets; các cổng chỉ bind loopback. Ignore secrets/local configs/caches trước staging; Actions pin full SHA, Maven/image versions pin exact. Chưa có full application containers, UI, business processing hoặc reliability evidence.
