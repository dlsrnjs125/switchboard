# Performance Evidence Methodology

## Purpose

Phase 9 measurements describe a bounded experiment, not general production capacity. Every run preserves the source identity, runtime environment, workload population, failures, raw output, and claim limitations.

## Environment fingerprint

Capture before the workload:

- Git commit, index tree, dirty-file list, UTC timestamp;
- host OS/architecture and Docker Desktop/server version;
- benchmark JDK image and immutable image digest;
- CPU count, memory limit, JVM max heap;
- PostgreSQL image and project Kafka baseline versions;
- the actual workload Kafka runtime version and mode when an embedded broker is used.

The runner snapshots commit, index tree, dirty count, and short status once before writing any Evidence artifact. A clean source state is recorded as `git_dirty_count=0` and `CLEAN`, preventing the fingerprint files themselves from making later bundles appear dirty. Verification resolves the recorded commit's tree from Git and requires it to equal the recorded index tree; a clean label alone is insufficient.

## Workloads

### Local evaluation

- 1, 100, and 1,000-flag immutable Snapshots;
- static default, targeting match, and deterministic percentage split;
- actual Java OpenFeature Provider path with active in-memory Snapshot;
- JMH sample-time mode, one thread/fork, three one-second warm-ups, five one-second measurement iterations;
- p50/p95/p99, sample count, maximum, normalized allocation, GC count/time.

Initialization, Snapshot decode, disk LKG persistence, and networking are excluded from local-evaluation latency and measured separately where applicable.

### Snapshot compilation and validation

- 1, 100, and 1,000 synthetic Boolean flags with two variants and one rollout rule;
- compiler and schema/semantic/checksum validator measured independently;
- the same JMH warm-up, measurement, percentile, allocation, and GC policy.

The result excludes HTTP, authorization, PostgreSQL transaction, audit/outbox insert, relay, and broker latency. It must not be labeled full publish latency.

### Control Plane publication transaction

- seed 1, 100, and 1,000 already-published Boolean flags into one environment;
- perform five warm-up and 30 measured sequential publications per size;
- execute the real `ControlPlaneService.publish` path inside a PostgreSQL transaction;
- verify environment-version advance and a committed outbox row for every publication;
- record Snapshot payload bytes, compile, validation, remaining transaction-path, full transaction-and-outbox-commit percentiles, and sequential operations/second.

The timer ends as soon as the transaction wrapper returns after commit; the committed-outbox assertion and Snapshot-size query run outside the timed boundary. The derived remaining-transaction-path duration subtracts directly timed compile and validation calls from the transaction wall clock. It deliberately combines scope and revision lookups, lock acquisition, repository reads, writes, deferred constraints, and commit overhead; it is not a persistence-only or database-server-only metric.

### End-to-end publish propagation

- one PostgreSQL Testcontainer, one embedded KRaft broker/partition, one Distribution process, and one real Java Provider;
- create one new immutable revision and full Snapshot per sample;
- commit the Draft Revision before the timer starts so Publish-start measurements exclude revision authoring;
- five warm-up and 30 measured sequential publications;
- record publish start, PostgreSQL commit return, broker ACK, Distribution reconcile/apply, SDK active-version observation, and server-accepted SDK ACK;
- enforce stage ordering and require the Provider to reach every version without workload errors.

The preferred end-to-end value is commit-to-server-observed SDK ACK because the ACK follows Provider validation, atomic apply, and LKG persistence. The separately reported SDK-apply observation uses 1 ms polling and includes that observation delay.

### gRPC client envelope

- one Distribution process, one PostgreSQL Testcontainer, one synthetic scoped credential;
- 100, 500, and 1,000 persistent streams;
- connection ramp in batches of 10; ACK authentication concurrency four;
- per-client connect, broadcast, and broadcast-to-ACK p50/p95/p99/max;
- connected sessions, zero/final errors, process CPU, and heap delta after explicit GC.

ACK is a server-observed proxy. It does not prove the full PostgreSQL commit-to-SDK atomic-apply SLI.

This workload measures initial connections only. A reconnect-storm result requires an explicit Distribution stop/restart, SDK backoff and jitter observations, admission rejects, PostgreSQL bootstrap query amplification, and `READY_STALE` to `READY` recovery percentiles. Until that workload exists, the 100/500/1,000-client cohort must not be described as reconnect capacity.

## Invalid-run rules

A run is invalid when any expected client/sample is missing, an assertion fails, errors are removed from the population, cleanup is incomplete, environment/source identity is unknown, a required raw artifact is missing, or instrumentation changes the claimed measurement boundary. Failed attempts remain reviewable and are not overwritten conceptually by a later success.

## Reproduction and artifact integrity

`make final-check` is the future aggregate Phase 9 gate. The current baseline bundle is stored under `EV-P09-BASELINE-001/artifacts`; `verify.sh` runs `shasum -a 256 -c SHA256SUMS`, and the tamper regression proves that a modified artifact fails verification. HPA runtime scaling and any workload beyond the captured envelope remain explicitly unverified.

See [TRB-011 — Phase 9 Evidence provenance and measurement boundaries](../troubleshooting/TRB-011-phase-09-evidence-provenance.md) for the review findings that established the clean-source, runtime-fingerprint, timer-boundary, and commit/tree guards.

The reconnect workload seeds the next authoritative Snapshot before the timed outage without notifying the running coordinator. This keeps fixture writes outside the outage-to-recovery interval. It uses a fixed 16-connection Hikari pool so a reconnect burst queues at the same kind of connection boundary as the runtime service instead of creating an unbounded `DriverManager` connection storm.
