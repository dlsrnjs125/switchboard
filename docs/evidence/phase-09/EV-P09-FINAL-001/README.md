# EV-P09-FINAL-001 — Performance and Operations Evidence

- Status: PLANNED
- Phase: Phase 9 — Performance & Operations Evidence
- Git commit: base `24eaca17d5918d63748a14139a7a96479e58fb7c`; Phase 9 worktree differences are listed in `artifacts/git-status.txt`
- Owner: Switchboard maintainers
- Related: `SLI-EVAL-001`, `SLI-PRP-001`, `SLI-RCV-001`, `SLI-OBX-001`, Phase 6 failure model

## Claim

The recorded code tree can reproduce bounded local-evaluation, Snapshot compile/validation, 100/500/1000-client Distribution, failure-recovery, and Kubernetes rollout observations without asserting production capacity beyond that environment.

## Method and expected result

The full method is versioned in [Performance Evidence Methodology](../../../testing/performance-methodology.md).

- Java 21 and Docker are mandatory; environment, Git commit/tree, working-tree state, runtime versions, CPU, and memory are captured before work starts.
- JMH uses three warm-up iterations, five one-second measurement iterations, one fork, and sample-time percentiles.
- gRPC requires every client in each cohort to connect, receive the next full Snapshot, and receive an accepted ACK with zero workload errors.
- Reliability and Kubernetes drills must converge to the authoritative version/checksum without an isolation, integrity, monotonicity, or committed-intent violation.
- Any missing sample/artifact, failed assertion, incomplete cleanup, or unknown source tree makes the run invalid.

## Command

```bash
make final-check
```

## Environment fingerprint

| Item | Observed |
| --- | --- |
| Host | macOS/Darwin arm64 |
| Benchmark runtime | Eclipse Temurin 21.0.12, Linux aarch64 container |
| Docker | Docker Desktop 29.5.3, 10 CPUs, 8,321,515,520 bytes memory |
| JVM heap for gRPC workload | 512 MiB max |
| PostgreSQL | `postgres:18.6-alpine` Testcontainer |

The image digest, timestamps, base commit/index tree, and working-tree state are retained in `artifacts/environment.txt` and `artifacts/git-status.txt`.

## Observed

### Local provider evaluation

All values are JMH sample-time p50 / p95 / p99. Allocation is normalized bytes per operation from the JMH GC profiler.

| Shape / flags | p50 | p95 | p99 | Allocation |
| --- | ---: | ---: | ---: | ---: |
| Static / 1 | 792 ns | 834 ns | 958 ns | 1,968 B/op |
| Static / 100 | 834 ns | 917 ns | 1,042 ns | 1,968 B/op |
| Static / 1,000 | 1,374 ns | 1,458 ns | 1,790 ns | 1,968 B/op |
| Targeting / 1 | 792 ns | 834 ns | 958 ns | 2,072 B/op |
| Targeting / 100 | 875 ns | 917 ns | 1,042 ns | 2,072 B/op |
| Targeting / 1,000 | 1,416 ns | 1,500 ns | 2,332 ns | 2,048 B/op |
| Split / 1 | 1,042 ns | 1,124 ns | 2,042 ns | 2,760 B/op |
| Split / 100 | 1,082 ns | 1,166 ns | 2,332 ns | 2,760 B/op |
| Split / 1,000 | 1,666 ns | 1,916 ns | 4,080 ns | 2,768 B/op |

The 1,000-flag SDK Snapshot retained object graph was 623,488 bytes; its synthetic canonical JSON member was 22,901 bytes.

### Snapshot compile and validation

| Stage / flags | p50 | p95 | p99 | Allocation |
| --- | ---: | ---: | ---: | ---: |
| Compile / 1 | 7.328 µs | 18.720 µs | 25.150 µs | 24,666 B/op |
| Compile / 100 | 486.400 µs | 547.840 µs | 741.069 µs | 1,651,520 B/op |
| Compile / 1,000 | 4,816.896 µs | 6,640.435 µs | 7,541.555 µs | 17,571,360 B/op |
| Validate / 1 | 17.184 µs | 20.704 µs | 27.392 µs | 40,436 B/op |
| Validate / 100 | 1,052.672 µs | 1,165.312 µs | 2,090.312 µs | 2,696,200 B/op |
| Validate / 1,000 | 10,993.664 µs | 13,090.816 µs | 13,625.262 µs | 30,830,490 B/op |

### gRPC connected-client envelope

Connections ramped in batches of 10; ACK authentication used four workers. All cohorts completed with zero final workload errors.

| Clients | Sessions | Connect p50/p95/p99 | Broadcast p50/p95/p99 | Broadcast→ACK p50/p95/p99 | Heap delta after GC |
| ---: | ---: | --- | --- | --- | ---: |
| 100 | 100 | 48.073 / 266.682 / 284.900 ms | 15.276 / 16.876 / 16.964 ms | 250.680 / 434.431 / 450.776 ms | 2,543,896 B |
| 500 | 500 | 38.379 / 46.630 / 58.182 ms | 13.208 / 17.209 / 17.492 ms | 956.137 / 1,762.201 / 1,832.927 ms | 3,135,384 B |
| 1,000 | 1,000 | 34.553 / 44.241 / 56.500 ms | 13.184 / 16.487 / 16.772 ms | 1,815.858 / 3,387.465 / 3,523.910 ms | 5,945,880 B |

## Result

The Java 21 JMH, JOL, gRPC load workload, and full `clean check` passed locally. The measured local-evaluation p99 is below the 2 ms design target throughout this dataset. These are component and local-topology results: compile/validation is not full publish latency, and broadcast-to-ACK is not commit-to-SDK-apply.

The aggregate evidence remains `PLANNED`, rather than `PASS`, until the Phase 9 branch is committed and the complete `make final-check` gate reruns Helm/kind and preserves its logs. The current machine lacked Helm/kind; Phase 8's unchanged production code retains its earlier verified Kubernetes evidence.

## Artifacts

- `artifacts/environment.txt`
- `artifacts/git-status.txt`
- `artifacts/evaluation-jmh.json`
- `artifacts/snapshot-publish-jmh.json`
- `artifacts/snapshot-footprint.json`
- `artifacts/grpc-capacity.json`
- `artifacts/SHA256SUMS`

## Limitations

The topology is local and single-region. The compile benchmark excludes transaction/outbox/broker latency; the gRPC result starts at Distribution broadcast and reports ACK as a proxy, not PostgreSQL commit-to-SDK-apply. HPA runtime behavior, WAN links, multi-zone failures, long soak, and workloads above 1,000 clients/flags remain unverified. See [Capacity and Limitations](../../../operations/capacity-limits.md) and [Final Readiness](../../../final-readiness.md).
