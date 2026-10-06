# FlowDock v0.1 — Technical Contracts & Architectural Specifications

Status: Accepted design baseline frozen at P0 closeout (2026-10-05).
Scope note: This document specifies the normative business, data, and reliability contracts approved for FlowDock v0.1 implementation (Phases P1–P8). These contracts represent the accepted design; runtime implementation in application source code begins in Phase P1.

---

## 1. Monetary Unit & Ingress Validation Constraints

### 1.1 Currency Validation & Ingress Pipeline
- **Ingress Validation:** Ingress validates ISO-4217 currency syntax (standard 3-letter alphabetic code). Valid standard currency codes (e.g. 'EUR', 'GBP', 'USD', 'JPY') pass initial schema validation.
- **Filter Stage:** The ingress pipeline filters valid non-USD currencies to `FILTERED` status (`status: FILTERED`), returning an explicit filtered acknowledgement without publishing to the payment Kafka topic. Filtered records do NOT constitute worker errors or dead-letter events.
- **Worker Scope:** The worker consumer processes USD only (`currency = 'USD'`).
- **Malformed Codes:** Non-standard, malformed, null, or empty currency values fail ingress validation immediately with HTTP 400 Bad Request.

### 1.2 Monetary Amount Representation & Bounds
- **Unit:** Integer minor-unit (cents), where $1.00 USD = `100` cents.
- **Field Naming:** In API requests, responses, and mapped JSON domain payloads, the monetary field is named `amount` (integer cents). In the PostgreSQL relational schema, the column is named `amount_cents` (`amount_cents` DB only).
- **Domain Value Constraints:**
  - `amount` MUST be an integer. Floating-point representations (`float`, `double`, `real`) are strictly prohibited across API, messaging, and database layers.
  - Lower bound: `amount >= 1` (strictly positive; zero-dollar authorizations and negative amounts are rejected at ingress).
  - Upper bound: `amount <= 9007199254740991` ($90,071,992,547,409.91 USD). This upper bound corresponds to `2^53 - 1` (`Number.MAX_SAFE_INTEGER` in IEEE 754 / ECMA-262), ensuring exact integer representation across JSON serialization, JavaScript runtimes, and JCS canonical hashing without precision loss.
- **Required Fields:** Ingress payloads MUST provide `amount` and `currency` as non-null fields.

### 1.3 Database Schema Specification (PostgreSQL 17)
Payments and receipts are run-scoped entities. The relational schema enforces composite uniqueness:
```sql
CREATE TABLE flowdock.payments (
    payment_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL,
    pipeline_id         VARCHAR(64) NOT NULL,
    transaction_id      VARCHAR(64) NOT NULL,
    amount_cents        BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_payments_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_payments_amount_max_safe CHECK (amount_cents <= 9007199254740991),
    CONSTRAINT ck_payments_currency_usd CHECK (currency = 'USD'),
    CONSTRAINT uq_payments_run_pipeline_tx UNIQUE (run_id, pipeline_id, transaction_id)
);

CREATE TABLE flowdock.receipts (
    receipt_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL,
    pipeline_id         VARCHAR(64) NOT NULL,
    event_id            VARCHAR(64) NOT NULL,
    payload_hash        VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_receipts_run_pipeline_event UNIQUE (run_id, pipeline_id, event_id)
);

CREATE TABLE flowdock.receipt_deliveries (
    delivery_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id          UUID NOT NULL REFERENCES flowdock.receipts(receipt_id),
    emission_id         VARCHAR(64),
    kafka_topic         VARCHAR(128) NOT NULL,
    kafka_partition     INT NOT NULL,
    kafka_offset        BIGINT NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
```

---

## 2. Canonical Business Payload Hashing (JCS RFC 8785 + SHA-256)

### 2.1 Specification Standard
- **Standard:** RFC 8785 (JSON Canonicalization Scheme - JCS).
- **Hash Function:** SHA-256, encoded as a 64-character lowercase hexadecimal string.
- **Encoding:** UTF-8 byte stream input to the SHA-256 digest.

### 2.2 Canonical Payload Scope & Structure
- The business payload hash covers strictly the domain business attributes:
  1. `transactionId` (string)
  2. `amount` (integer cents, 1..9007199254740991)
  3. `currency` (string, "USD")
- Transport coordinates (`topic`, `partition`, `offset`), logical identifiers (`runId`, `pipelineId`, `eventId`), and delivery sequence numbers (`emissionId`) MUST NOT be included in the business payload hash.

### 2.3 Field Handling & RFC 8785 Compliance
- **Key Sorting:** Lexicographically sorted by **UTF-16 code units** per RFC 8785 §3.2.3 and ECMA-262 §24.3.2. For ASCII keys, UTF-16 code unit ordering is identical to ASCII code point order, resulting in: `"amount"`, `"currency"`, `"transactionId"`.
- **Whitespace:** Zero whitespace outside JSON string literals.
- **Numbers:** Formatted per ECMA-262 §7.1.12.1 / RFC 8785 §3.2.2.3 (integer values formatted without exponent or decimal point, e.g., `1500`).
- **Null Handling:** In compliance with RFC 8785 §3.2.2.1, JCS does not strip `null` values. However, FlowDock ingress schema validation strictly requires non-null domain fields (`transactionId`, `amount`, `currency`). Payloads with `null` or missing required values are rejected at ingress before canonical hashing.
- **Hash Test Vectors:** Formal test vectors are generated and verified during Phase P1 test suite execution. No unofficial illustrative digests are certified in this specification.

### 2.4 Worker Hash Computation & Persistence Contract
- **Worker Hash Computation:** The worker MUST independently compute the canonical business payload hash per §2 on `{transactionId, amount, currency}` from the incoming record. The worker NEVER trusts any sender-provided hash header.
- **Persistence on Receipt Creation:** When registering a NEW logical receipt, the worker MUST persist this independently computed hash into the `flowdock.receipts.payload_hash` column within the same database transaction.
- **Authoritative Comparison Baseline:** The persisted `flowdock.receipts.payload_hash` column in the database is the SOLE authoritative source of comparison against incoming records. In Branch 2 of the invariant matrix (§4.1), the worker strictly compares the hash computed from the incoming record against this persisted `payload_hash`.

**Canonical Payload Structure:**
```json
{"amount":1500,"currency":"USD","transactionId":"tx-20261005-001"}
```

---

## 3. Identity Taxonomy & Kafka Coordinate Separation

### 3.1 Identity Levels
| Identifier | Format / Scope | Semantics |
|---|---|---|
| `run_id` | UUIDv4 (36 chars) | Unique identifier of an automated run/scenario execution. |
| `pipeline_id` | Bounded String (Proposal: $\le 64$ chars) | Logical configuration and routing pipeline definition. |
| `event_id` | Bounded String (Proposal: $\le 64$ chars) | Unique logical ingress event identity provided by the caller/simulator. |
| `transaction_id` | Bounded String (Proposal: $\le 64$ chars) | Business payment transaction reference within `(run_id, pipeline_id)`. |
| `emission_id` | Bounded String (Proposal: $\le 64$ chars) | Identifier of an individual physical Kafka produce attempt. |
| `source_coordinates` | Tuple: `(topic, partition, offset)` | Physical location of a record in the Kafka cluster. |

*Note on Bounded Lengths:* Bounded lengths ($\le 64$ characters) for string identifiers in tables and DTOs represent an explicit technical proposal for the upcoming implementation, not a frozen approved system-wide constraint.

### 3.2 Physical vs. Logical Separation
- When the simulator injects duplicate traffic (e.g. SC-02), two distinct emissions share the same `event_id`, `transaction_id`, and business payload hash, but carry distinct `emission_id` values and occupy distinct Kafka `source_coordinates` (e.g. partition 0 offset 101 vs. partition 0 offset 102).
- The worker preserves the single logical unique receipt for `event_id` (via `flowdock.receipts`) and appends physical coordinate delivery evidence separately (via `flowdock.receipt_deliveries`), while executing the accounting payment effect exactly once.

---

## 4. Deduplication & Conflict Policies

### 4.1 Invariant Decision Matrix
When the worker receives a record with `run_id`, `pipeline_id`, `event_id`, `transaction_id`, and payload attributes:

| Condition | Invariant Check | System Action | Terminal Category |
|---|---|---|---|
| **Same `event_id`, same `canonical_payload_hash`** | Valid duplicate record | Preserve existing unique receipt; record separate delivery evidence in `receipt_deliveries`; skip payment insertion; commit Kafka offset. | `IDEMPOTENT_DUPLICATE_ACKNOWLEDGED` |
| **Same `event_id`, different `canonical_payload_hash`** | Payload tampering / mutation | Stored `payload_hash` column is sole comparison baseline; rollback transaction; route record to DLQ; commit offset only upon confirmed DLQ ack; log non-retryable violation evidence. | `EVENT_PAYLOAD_MUTATED_CONFLICT` |
| **Different `event_id`, same `transaction_id`** | Cross-event business collision | Atomic rollback of **both** receipt and payment in current DB transaction; route to DLQ with collision evidence; commit offset only upon confirmed DLQ ack; 0 payments created. | `TRANSACTION_ID_COLLISION_INVARIANT_VIOLATION` |

### 4.2 Detection Mechanisms & Decision Flow
Cross-event collisions and payload mutations MUST NOT be silently swallowed, deduplicated, or partially committed. The worker executes the following explicit decision flow:

1. **Worker Hash Computation:**
   - Worker independently calculates the canonical payload hash per §2 over `{transactionId, amount, currency}` from the incoming message. Sender-provided hash headers are never trusted.

2. **Pre-Decision Lookup & Authoritative Concurrency Control:**
   - Worker queries for an existing receipt: `SELECT payload_hash FROM flowdock.receipts WHERE run_id = ? AND pipeline_id = ? AND event_id = ?` BEFORE deciding the processing branch.
   - *Concurrency Note:* This pre-lookup is an optimization step. Unguarded query-exists-then-insert is NEVER relied upon for concurrency safety. The database unique constraint `uq_receipts_run_pipeline_event` (via `INSERT ... ON CONFLICT DO NOTHING` or unique constraint catch) and transactional isolation are the authoritative concurrency barriers.

3. **Branch Mapping to Concrete Detection Mechanisms:**
   - **Nhánh 1 — Trùng lặp Idempotent (Idempotent Duplicate):**
     - *Cơ chế phát hiện:* Phát hiện qua `uq_receipts_run_pipeline_event` (hoặc receipt lookup tìm thấy bản ghi đã tồn tại) VÀ giá trị `receipts.payload_hash` đã lưu trong database khớp chính xác với canonical payload hash do worker tính toán từ bản tin nhận được.
     - *Hành động hệ thống:* Bảo lưu bản ghi receipt logic duy nhất hiện tại; ghi nhận bản ghi tọa độ phát mới vào `flowdock.receipt_deliveries`; bỏ qua việc chèn payment mới; commit Kafka source offset.
   - **Nhánh 2 — Xung đột Đột biến Payload (Event Payload Mutated Conflict):**
     - *Cơ chế phát hiện:* Phát hiện qua `uq_receipts_run_pipeline_event` (hoặc receipt lookup tìm thấy bản ghi đã tồn tại), nhưng đọc giá trị `receipts.payload_hash` đã lưu trong database và so sánh thì **KHÔNG KHỚP** với canonical payload hash do worker tính toán từ bản tin đến. Cột `payload_hash` đã lưu là nguồn căn cứ so sánh duy nhất.
     - *Hành động hệ thống:* Rollback giao dịch hiện tại; chuyển bản tin sang `flowdock.payment.dlq` với phân loại lỗi không thử lại (`NON_RETRYABLE_CONFLICT`); Kafka source offset CHỈ tiến lên sau khi nhận được xác nhận (Ack) đã ghi vào DLQ; 0 payment mới được tạo.
   - **Nhánh 3 — Va chạm Mã Giao dịch Chéo Event (Cross-Event Transaction Collision):**
     - *Cơ chế phát hiện:* Phát hiện khi bản tin là sự kiện mới (receipt lookup không tìm thấy, thao tác chèn receipt thành công), nhưng thao tác chèn payment tiếp theo kích hoạt vi phạm ràng buộc duy nhất **`uq_payments_run_pipeline_tx`** trên bảng `flowdock.payments` (do cùng `transaction_id` đã tồn tại trong cùng `(run_id, pipeline_id)` dưới một `event_id` khác).
     - *Hành động hệ thống:* Transaction manager kích hoạt **rollback nguyên tử cả receipt lẫn payment** trong giao dịch DB hiện tại. Phân loại lỗi là `NON_RETRYABLE_CONFLICT`, chuyển bản tin vào `flowdock.payment.dlq` kèm bằng chứng va chạm, Kafka source offset CHỈ tiến lên sau khi nhận được xác nhận đã ghi vào DLQ; 0 payment được tạo ra. Bản ghi trong DLQ là bằng chứng từ chối xử lý, không phải chứng từ thanh toán. Downstream DLQ observers deduplicate entries using source coordinates `(topic, partition, offset)`.

---

## 5. Error Classification & Poison Retry Policy

### 5.1 Three-Way Error Classification
1. **Intentional Retryable Demo (Poison Demo SC-03):**
   - In scenario SC-03, the test harness intentionally injects a retryable poison demo record to verify worker retry mechanics. This is an intentional simulation fixture and does NOT claim that business poison in general is transient.
   - **Retry Policy:** Exactly **3 total attempts** in an uninterrupted sequence (1 initial attempt + 2 retries) with a **500ms** backoff between attempts.
   - **Ephemeral State:** Retry state is held in-memory and is non-durable. A process restart or partition rebalance **MAY** reset the attempt counter and re-commence from the last committed offset.
   - **DLQ Routing & Offset Safety:** If all 3 attempts fail, the worker publishes the record to `flowdock.payment.dlq`. The source partition offset advances **ONLY after confirmed DLQ publication**. If DLQ publication fails, the error propagates and the source offset does NOT advance.
2. **Non-Retryable Invariant & True Poison Failures:**
   - Schema deserialization exceptions (`DeserializationException`), message conversion errors (`MessageConversionException`), malformed currency, negative amount, canonical hash mismatch, cross-event transaction collisions. These constitute true invariant violations.
   - **Policy:** Route IMMEDIATELY to DLQ on the first attempt without consuming retry attempts or delaying partition consumption. Offset advances only upon confirmed DLQ acknowledgement.
3. **Infrastructure Outage:**
   - PostgreSQL unreachable / pool exhaustion, Kafka broker disconnection, disk full.
   - **Policy:** **DO NOT ROUTE TO DLQ!** Routing to DLQ during an infrastructure outage would flood the dead-letter queue with valid records and skip source offsets. The consumer container MUST pause consumption, back off, and alert the coordinator. If unrecovered within deadline, abort the run.

---

## 6. Physical Partition Drain & High-Water Mark Semantics

### 6.1 Drain Completion Contract
A run cannot transition from `DRAINING` to `RECONCILING` based merely on receiving an expected count of messages. It MUST prove physical partition drain across all assigned partitions:
$$\text{Committed Next Offset}_p \ge \text{Max Confirmed Source Offset}_p + 1 \quad \forall p \in \text{Assigned Partitions}$$
- `Log End Offset (LEO)` and `High-Water Mark (HW)` are broker-side watermarks and are not conflated with the consumer's committed next-offset.
- Drain requires:
  1. Consumer has committed next-offset covering all confirmed producer emissions.
  2. Source and DLQ observers report zero in-flight backlog.
  3. Terminal evidence is durably persisted to the database.

---

## 7. Run Lifecycle State Machine & Verdicts

### 7.1 Lifecycle States (5 Non-Terminal + 2 Terminal)
The lifecycle defines 5 non-terminal execution states and 2 terminal states:
- **Non-Terminal States (5):** `CREATED`, `PREPARING`, `RUNNING`, `DRAINING`, `RECONCILING`.
- **Terminal States (2):** `FINISHED`, `ABORTED`.

**Legal State Transitions:**
```
[CREATED] ──→ [PREPARING] ──→ [RUNNING] ──→ [DRAINING] ──→ [RECONCILING] ──→ [FINISHED]
      │              │             │             │               │
      └──────────────┴─────────────┴─────────────┴───────────────┴────────→ [ABORTED]
```
1. `CREATED`: Run entity persisted with immutable configuration snapshot hash.
2. `PREPARING`: Infrastructure, topics, and initial offsets verified.
3. `RUNNING`: Simulator generating traffic; worker actively processing.
4. `DRAINING`: Traffic stopped; awaiting partition drain (`committed next-offset >= max confirmed offset + 1`).
5. `RECONCILING`: Set reconciliation verifying physical emissions vs. logical payments vs. DLQ entries.
6. `FINISHED`: Terminal state for completed scenarios.
7. `ABORTED`: Terminal state for cancelled, interrupted, or unrecoverable scenarios.

### 7.2 Active Run Exclusivity
- An active run holds exclusive execution ownership across **all four active execution states**: `PREPARING`, `RUNNING`, `DRAINING`, `RECONCILING`.
- The database enforces that at most one run can exist in these active states simultaneously.
- Atomic release of the active-run lock occurs strictly upon transitioning to a terminal state (`FINISHED` or `ABORTED`).

### 7.3 Terminal Verdicts (4 Verdicts)
- **`PASS`:** All scenario-specific assertions and physical evidence are verified (all expected emissions accounted for in DB or DLQ, partition drain completed, zero invariant violations). NOT based on a momentary snapshot of global lag zero.
- **`FAIL`:** Explicit counterexample proven (e.g. duplicate payment created, transaction collision swallowed, lost payment).
- **`INCONCLUSIVE`:** Insufficient evidence (e.g. observer gap, timeout before physical drain, coordinator or infrastructure interruption).
- **`CANCELLED`:** Explicit user/operator stop of the scenario. Partial evidence retained; never converted to PASS. Coordinator infrastructure interruption is classified as **`INCONCLUSIVE`** (with run state `ABORTED`), not `CANCELLED`.

---

## 8. Worker Crash Simulation Protocol (P2 Contract)

### 8.1 Crash Boundary Sequence
For fault-injection scenario SC-04 (crash worker after commit, before Kafka offset ack):
1. **Lab Claim:** Worker persists lab fault-injection intent to database (`fault_action_claimed = true`).
2. **DB Commit:** Database transaction commits receipt and payment effect.
3. **Fired Evidence:** Target delivery and fired action evidence persisted durably.
4. **Controlled Halt:** Worker executes `Runtime.getRuntime().halt(1)` before returning from the Kafka listener method and before committing the Kafka offset.
5. **Exit Confirmation:** Coordinator confirms actual process/container exit via operating system process status or Docker container status (`exited`), NOT merely through an HTTP health check failure.
6. **Recovery Proof:** On restart, Kafka redelivers the same unacknowledged coordinate; worker detects existing receipt, commits offset, and generates 0 additional payments.

---

## 9. Configuration Snapshot & Version Semantics
- Each run references an immutable `config_snapshot_id` and SHA-256 `config_snapshot_hash`.
- Runtime configuration edits create new version records and do not mutate in-flight runs.
- Worker processes pin their operational configuration to the active run's snapshot.

---

## 10. Technology Framework Baseline (Source-Referenced)
The system implementation builds on the pinned technologies established in setup ADR-000:
- **Language & Runtime:** Java 21 (Adoptium Temurin 21.0.12+8, Microsoft OpenJDK 21).
- **Application Framework:** Spring Boot 3.5.16, Spring Framework 6.2.19, Spring Kafka 3.3.x / Kafka Clients 3.9.2.
- **Relational Database:** PostgreSQL 17.11 (Bookworm) with Flyway 11.7.2 migration locking.
- **Event Streaming:** Apache Kafka 4.1.2 KRaft mode (dual advertised listeners).
