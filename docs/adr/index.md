# FlowDock Architecture Decision Records (ADRs)

This index records the official architectural decision baseline for FlowDock v0.1. ADR-000 captures the foundation setup decisions, while ADR-001 through ADR-010 preserve the exact original architectural decisions established in the FlowDock implementation plan (`tasks/plan.md`). Domain contracts for money, canonical hashing, identity taxonomy, and partition drain are detailed in [`docs/contracts.md`](../contracts.md).

---

## Index of Decisions

- [ADR-000: Development foundation](adr-000-foundation.md)
- [ADR-001: Modular backend and separate worker process](#adr-001-modular-backend-and-separate-worker-process)
- [ADR-002: Linear pipeline with asynchronous Kafka messaging](#adr-002-linear-pipeline-with-asynchronous-kafka-messaging)
- [ADR-003: Atomic receipt and business effect then offset commit](#adr-003-atomic-receipt-and-business-effect-then-offset-commit)
- [ADR-004: Spring-managed record-listener retry](#adr-004-spring-managed-record-listener-retry)
- [ADR-005: Short blocking retry with non-durable state](#adr-005-short-blocking-retry-with-non-durable-state)
- [ADR-006: Non-atomic DLQ and source commit with duplicate deduplication](#adr-006-non-atomic-dlq-and-source-commit-with-duplicate-deduplication)
- [ADR-007: Independent manifest and minimal set reconciliation](#adr-007-independent-manifest-and-minimal-set-reconciliation)
- [ADR-008: One active run with frozen configuration snapshot](#adr-008-one-active-run-with-frozen-configuration-snapshot)
- [ADR-009: Fixed templates, topic namespace and allowlisted destinations](#adr-009-fixed-templates-topic-namespace-and-allowlisted-destinations)
- [ADR-010: Basic observability first](#adr-010-basic-observability-first)

---

### ADR-001: Modular backend and separate worker process
- **Status:** Accepted (Design Baseline).
- **Context:** Ingress HTTP API and Kafka event consumption have distinct scaling, threading, and failure profiles. Fault simulation and worker crash testing must terminate consumer execution without tearing down API ingress or coordinator services.
- **Decision:** Architect the system as a multi-module Maven reactor (`core`, `backend`, `worker`). Run `backend` (HTTP API and Coordinator) and `worker` (Kafka Consumer and Payment Processor) as independent operating system processes and separate container images.
- **Consequences:** Worker crash hooks, container kills, or memory exhaustions do not crash the backend API.

---

### ADR-002: Linear pipeline with asynchronous Kafka messaging
- **Status:** Accepted (Design Baseline).
- **Context:** Decoupling client request intake from heavy accounting processing ensures high ingress throughput and buffering during worker outages.
- **Decision:** Implement a linear processing pipeline structured as: HTTP Ingress $\rightarrow$ Validate $\rightarrow$ Map $\rightarrow$ Filter $\rightarrow$ Asynchronous Kafka Transport (`flowdock.payment.events`) $\rightarrow$ Synchronous Worker Listener. The ingress pipeline validates schema syntax, maps payloads to canonical envelopes, and filters valid non-USD currencies to `FILTERED` status (which are acknowledged without publishing to the payment topic).
- **Consequences:** Decoupled flow; non-USD traffic is filtered cleanly at ingress, and valid USD traffic bursts are buffered safely in Kafka partitions.

---

### ADR-003: Atomic receipt and business effect then offset commit
- **Status:** Accepted (Design Baseline).
- **Context:** A system crash between executing a database payment and committing the Kafka offset must not cause duplicate payments upon redelivery.
- **Decision:** Wrap receipt registration and accounting payment creation within a single relational database transaction (`@Transactional`). Commit the Kafka offset strictly *after* the database transaction has successfully committed.
- **Consequences:** Guarantees at-least-once transport delivery with idempotent database side-effects (not an end-to-end exactly-once messaging claim).

---

### ADR-004: Spring-managed record-listener retry
- **Status:** Accepted (Design Baseline).
- **Context:** Transient processing issues must be handled cleanly through framework-managed retries without blocking partition processing indefinitely.
- **Decision:** Use Spring Kafka's `DefaultErrorHandler` with a synchronous record-by-record listener (`AckMode.RECORD`). Classify exceptions to route fatal/non-retryable errors immediately to DLQ while retrying transient errors.
- **Consequences:** Deterministic retry policy with clear separation between retryable and non-retryable exceptions.

---

### ADR-005: Short blocking retry with non-durable state
- **Status:** Accepted (Design Baseline).
- **Context:** Heavy external retry persistence mechanisms introduce complexity and race conditions during worker restarts.
- **Decision:** Keep retry attempts short, synchronous, and held entirely in-memory. In-flight retry state is non-durable; a process restart or consumer partition rebalance resets the attempt counter and restarts consumption from the last committed offset.
- **Consequences:** Simple, predictable worker failure semantics without distributed retry state coordination.

---

### ADR-006: Non-atomic DLQ and source commit with duplicate deduplication
- **Status:** Accepted (Design Baseline).
- **Context:** Publishing an unrecoverable poison message to the Dead Letter Queue (DLQ) and committing the source partition offset cannot be combined into a distributed transaction with PostgreSQL. While Kafka transactions could link DLQ publishing and consumer offsets within Kafka, that approach was not chosen for v0.1 to avoid transactional producer overhead and coordinator complexity.
- **Decision:** Accept that DLQ publication followed by source offset commit is non-atomic without two-phase commit (2PC). In case of failure after DLQ publish but before offset commit, redelivery may produce duplicate DLQ messages. Downstream DLQ observers and audit tools must deduplicate entries using source record identity coordinates `(topic, partition, offset)`.
- **Consequences:** High-performance, partition-safe error handling with an explicit deduplication contract for DLQ consumers.

---

### ADR-007: Independent manifest and minimal set reconciliation
- **Status:** Accepted (Design Baseline).
- **Context:** Verification cannot trust internal application logs alone to prove that no records were dropped or skipped.
- **Decision:** Generate deterministic seed manifests before scenario runs. Employ independent source and DLQ observers to track physical emissions and verify through minimal set reconciliation that:
  $$\text{Expected} = \text{Succeeded (Receipts/Payments)} \cup \text{DLQ (Poison/Failed)} \cup \text{Unresolved (In-flight/Ambiguous)}$$
  combined with Ingress outcomes (`FILTERED`, `REJECTED`), verified against physical partition confirmed-record boundaries.
- **Consequences:** Discrepancies, lost messages, and unexplained drops are proven mathematically.

---

### ADR-008: One active run with frozen configuration snapshot
- **Status:** Accepted (Design Baseline).
- **Context:** Multiple concurrent simulation runs in a local single-user lab cause resource contention and cross-contamination of Kafka partitions and database tables.
- **Decision:** Enforce a database-level active-run constraint allowing at most one active run across all four active execution states (`PREPARING`, `RUNNING`, `DRAINING`, `RECONCILING`). Exclusive lock ownership is released atomically strictly upon transitioning to a terminal state (`FINISHED` or `ABORTED`). Bind each run to an immutable configuration snapshot hash at creation time.
- **Consequences:** Clean run isolation, reproducible test results, and deterministic scenario assertions.

---

### ADR-009: Fixed templates, topic namespace and allowlisted destinations
- **Status:** Accepted (Design Baseline).
- **Context:** Arbitrary topic generation or unrestricted destination URLs present security and operational stability risks.
- **Decision:** Define strictly versioned, fixed JSON envelope templates, dedicated topic namespaces (`flowdock.payment.*`), and enforce an allowlist of valid destination endpoints and topic names.
- **Consequences:** Guarded lab boundaries; arbitrary container or network targets cannot be manipulated by test payloads.

---

### ADR-010: Basic observability first
- **Status:** Accepted (Design Baseline).
- **Context:** Complex graphical dashboards (e.g. Grafana) add maintenance overhead during early foundation stages.
- **Decision:** Prioritize fundamental observability built directly into the application: structured JSON logs, low-cardinality RED metrics (Rate, Errors, Duration), per-partition lag timeline tracking, and versioned JSON test execution reports.
- **Consequences:** Zero external dashboard dependency for test verification and root-cause debugging.
