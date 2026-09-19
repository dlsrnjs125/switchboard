# SLI and SLO Design Targets

## Status and intent

These values are pre-implementation **design targets**, not production promises. Phase 7 supplies production-shaped telemetry, and Phase 9 calibrates targets with repeatable load and failure evidence. A target becomes an SLO only after its measurement point, population, window, exclusions, and alert policy are implemented and reviewed.

The [failure model](failure-model.md) defines safety behavior. This document defines how to measure whether the system preserves that behavior and how quickly freshness recovers.

## Measurement principles

- Measure end-to-end user-visible outcomes, then use component metrics to explain them.
- Partition by environment and deployment, not by user or targeting key.
- Use monotonic durations and synchronized wall-clock timestamps only where cross-process correlation requires them.
- Publish latency starts at authoritative PostgreSQL commit, not HTTP request receipt or Kafka enqueue.
- Propagation completes when the SDK successfully validates and atomically applies the snapshot. ACK receipt is an observable proxy and must be labeled as such.
- Retries and failed attempts remain in the denominator unless a documented invalid-test exclusion applies.
- Report sample count, p50, p95, p99, maximum, error count, and confidence limitations; a percentile without its population is invalid evidence.
- Never average percentiles across instances. Aggregate raw histograms with compatible buckets or recompute from event samples.

## Canonical event timestamps

| Event | Source | Meaning |
| --- | --- | --- |
| `publish_committed_at` | PostgreSQL/application transaction boundary | Snapshot, state, audit, and outbox are durable. |
| `outbox_published_at` | Outbox relay | Broker acknowledged the notification attempt. |
| `distribution_loaded_at` | Distribution | Version/checksum is validated and available for delivery. |
| `sdk_applied_at` | SDK | Full snapshot validation and atomic swap completed. |
| `ack_received_at` | Distribution | Server observed SDK acknowledgement. |
| `freshness_lost_at` | SDK | Heartbeat/stream policy changed `READY` to `READY_STALE`. |
| `freshness_restored_at` | SDK | A valid current snapshot restored `READY`. |

Every correlated record carries tenant-safe environment identity, snapshot version, checksum, client application identity, and correlation/event ID where applicable. Metrics must not use raw IDs as unbounded labels.

## Design-target catalog

| ID | SLI | Definition | Design target | Status |
| --- | --- | --- | --- | --- |
| `SLI-EVAL-001` | Local evaluation latency | Duration inside provider resolution using an already active in-memory snapshot | p99 ≤ 2 ms | Canonical target |
| `SLI-PRP-001` | Publish-to-apply latency | `sdk_applied_at - publish_committed_at` for 1,000 connected clients | p95 ≤ 1 s; p99 ≤ 3 s | Canonical target |
| `SLI-CP-001` | Control Plane isolation | Successful eligible local evaluations during a Control Plane outage / attempted eligible evaluations | 100%; no request-time network calls | Safety target |
| `SLI-SNP-001` | Snapshot integrity | Corrupt or incompatible snapshots atomically applied | 0 | Safety target |
| `SLI-VER-001` | Version monotonicity | Active snapshot regressions or same-version checksum conflicts applied | 0 | Safety target |
| `SLI-TEN-001` | Tenant isolation | Successful cross-tenant reads/writes/streams | 0 | Security target |
| `SLI-OUT-001` | Publish durability | Committed snapshots without a durable outbox intent, or unreconciled committed intents after the recovery window | 0 | Safety target |
| `SLI-SDK-001` | LKG evaluation continuity | Successful eligible local evaluations while Distribution is unavailable / attempted eligible evaluations with valid LKG | 100% | Safety target |
| `SLI-FRS-001` | Fleet freshness | Connected clients on the authoritative current version / eligible connected clients | Observe continuously; threshold calibrated in Phase 9 | Calibration required |
| `SLI-RCV-001` | Reconnect recovery | Time from restored Distribution readiness to SDK return to `READY` | p95 ≤ 60 s; p99 ≤ 180 s for 1,000 clients | Experiment target |
| `SLI-OBX-001` | Outbox recovery | Time from Kafka recovery to publication/reconciliation of events pending at recovery | p95 ≤ 60 s; all within 5 min at Phase 9 workload | Experiment target |

`SLI-RCV-001` and `SLI-OBX-001` are initial experiment bounds, not commitments. Phase 9 may replace them only with recorded evidence and an explicit decision.

## SLI definitions

### `SLI-EVAL-001` — Local evaluation latency

- **Population:** provider resolution calls after an active snapshot reference is available.
- **Start/stop:** immediately before snapshot lookup through completed resolution details.
- **Segments:** static, targeting, and percentage split; 1, 100, and 1,000-flag snapshots.
- **Exclusions:** benchmark warm-up is reported separately; initialization, network, and LKG disk I/O are not part of this SLI.
- **Guardrail:** benchmark inputs and JDK/JVM/CPU metadata must accompany results.

### `SLI-PRP-001` — Publish propagation

- **Population:** clients connected before commit and authorized for the environment at commit time.
- **Start:** durable publication transaction commit.
- **Stop:** each SDK successfully applies the exact version/checksum. If only server telemetry is available, ACK receipt is reported as `publish-to-ack`, not mislabeled as apply time.
- **Failures:** NACK, disconnect before apply, timeout, wrong checksum, or wrong version remain failures.
- **Segments:** payload size, client count, Distribution replica, and fresh versus reconnect delivery.

### Safety SLIs

`SLI-SNP-001`, `SLI-VER-001`, `SLI-TEN-001`, and `SLI-OUT-001` have a zero-tolerance target. They are verified with explicit negative tests and failure drills rather than statistical sampling alone. Any observed violation fails the release gate regardless of aggregate availability.

### `SLI-FRS-001` — Fleet freshness

Track both:

```text
version_lag = authoritative_environment_version - sdk_applied_version
snapshot_age = observation_time - sdk_applied_at
fresh_client_ratio = clients at authoritative version / eligible connected clients
```

Clients with unknown version, expired heartbeat, or `READY_STALE` are not counted as fresh. Dashboards must expose unknown and excluded populations rather than dropping them.

### Recovery SLIs

Recovery time begins when the dependency is demonstrably healthy and ready to serve, not when a restart command is issued. It ends only when the affected state converges:

- Control Plane: management health and tenant-qualified read/write probes succeed.
- Kafka/outbox: the recovery cohort is published or explicitly reconciled.
- Distribution/SDK: clients apply the authoritative current version and return to `READY`.
- PostgreSQL: atomic publication records, pending outbox, and current version/checksum pass reconciliation.

## Error-budget policy baseline

Safety targets do not receive an error budget. One integrity, monotonicity, tenant-isolation, or committed-intent-loss event blocks release and starts incident analysis.

Latency and availability budgets are introduced only after Phase 9 establishes a trustworthy denominator and representative workload. Until then:

- missed performance targets create evidence and follow-up work, not a production SLA breach;
- changes may not improve a percentile by excluding slow or failed clients;
- a run with instrumentation loss or an incomplete client cohort is invalid rather than successful.

## Telemetry requirements

### Metrics

- publish commit count and duration;
- oldest pending outbox age, pending count, attempts, and failures;
- Distribution snapshot load/validation duration and cache version;
- connected client count, stream failures, reconnect attempts, and backpressure actions;
- ACK/NACK/resync counts by bounded reason code;
- SDK provider state, applied version, snapshot age, and evaluation duration/reason;
- cross-tenant authorization denials without target-resource identity labels.

### Cardinality and privacy

- Never label metrics with targeting key, user ID, raw tenant/project/environment ID, credential ID, flag key, or correlation ID.
- Use logs/traces for sampled correlation with redaction and access control.
- Bound reason codes to contract-defined enumerations; free-form exception text is not a metric label.
- Snapshot payloads and Evaluation Context values are excluded from ordinary telemetry.

## Alert design baseline

| Signal | Alert intent |
| --- | --- |
| Oldest pending outbox age increases while publish commits continue | Delivery is not converging after commit. |
| NACK/checksum-conflict count > 0 | Snapshot integrity or compatibility needs immediate investigation. |
| SDK `READY_STALE` ratio rises | Fleet freshness is degraded even if evaluation remains available. |
| Version lag grows after dependency recovery | Reconciliation or reconnect is stalled. |
| Cross-tenant authorization test failure | Release-blocking security regression. |
| Reconnect attempts and stream queue depth rise together | Reconnect storm or backpressure is threatening capacity. |

Numeric paging thresholds remain Phase 7/9 work; the signal definitions and safety severity are fixed here.

## Evidence requirements

Every SLI result links to an evidence record containing the exact Git commit, runtime/tool versions, topology, dataset, client count, payload size, warm-up, duration, fault timing, raw artifacts, calculations, observed values, and limitations. See [Evidence Policy](../evidence/README.md).

No document may convert a design target into a production guarantee without representative evidence and an explicit status change in this file.
