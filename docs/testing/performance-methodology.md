# Performance Evidence Methodology

## Purpose

Phase 9 measurements describe a bounded experiment, not general production capacity. Every run preserves the source identity, runtime environment, workload population, failures, raw output, and claim limitations.

## Environment fingerprint

Capture before the workload:

- Git commit, index tree, dirty-file list, UTC timestamp;
- host OS/architecture and Docker Desktop/server version;
- benchmark JDK image and immutable image digest;
- CPU count, memory limit, JVM max heap;
- PostgreSQL/Kafka image versions and topology.

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
