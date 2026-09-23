# Capacity and Limitations

This is an evidence envelope, not a production sizing promise. A number is claimable only when the matching raw Phase 9 artifact was produced on the documented Git tree and environment.

| Area | Tested envelope | Enforced limit / behavior | Claim boundary |
| --- | --- | --- | --- |
| Local evaluation | 1/100/1000 flags; static/targeting/split | In-memory lookup and local evaluation only | Excludes initialization, network, disk LKG, and arbitrary rule depth |
| Snapshot compile/validate | 1/100/1000 flags | Full immutable Snapshot | Excludes transaction and broker delay; those remain separate stages |
| Publish transaction | 1/100/1000 flags; 30 sequential measured commits/size | Atomic Snapshot, audit, and outbox commit | Local PostgreSQL Testcontainer; no HTTP/JWT or concurrent-author saturation claim |
| Publish propagation | 30 sequential single-client publications | Commit → broker ACK → Distribution apply → Java Provider apply/LKG → accepted ACK | Single process/partition/client; not a fleet or WAN bound |
| gRPC Distribution | 100/500/1000 streams; connection batch 10; ACK concurrency 4 | `maximum-sessions` admission cap; one coalesced pending Snapshot/client | Single process, shared synthetic credential, local Docker PostgreSQL; unbounded authentication burst is not a supported claim |
| Reconnect | 100/500/1000 clean-source `PASS` | SDK backoff/jitter, 16-connection DB pool, session admission, full Snapshot recovery, unique ACK | Shared channel with isolated reconnect/control schedulers and in-process server restart; local envelope, not a production SLO |
| Slow-client backpressure | 100/500/1000 sessions; 20% non-writable; 16 KiB full Snapshot at 10/s for 10 s | One coalesced pending latest Snapshot/client; pending count/bytes and coalescing telemetry | In-process synthetic observer readiness; no HTTP/2/TCP/WAN or real SDK read-loop claim |
| Kubernetes | Two replicas in local kind | PDB, readiness, graceful drain, rolling strategy | No cloud LB, zone failure, or production CNI proof |
| HPA | Manifests only | CPU request and configurable min/max | metrics-server response and scale-out latency remain unverified |

## Hard boundaries

- Default Distribution session limit: 10,000 per process. This is a guardrail, not verified capacity.
- The initial-connection and reconnect results are separate envelopes. The reconnect `PASS` includes a stream drop, jittered reconnect, PostgreSQL authentication/bootstrap amplification, recovery percentiles, and unique ACK completion in the recorded local topology.
- Slow clients retain at most one pending latest full Snapshot; intermediate versions may be coalesced.
- Notification retention must cover Pod churn and consumer-group recreation.
- A valid LKG preserves local evaluation during dependency loss, but freshness becomes `READY_STALE`.
- Safety failures—tenant escape, corrupt apply, version regression, or committed intent loss—have no error budget.

Scale beyond the measured envelope requires a new evidence run with explicit CPU/memory limits, connection topology, PostgreSQL pool/limit, Kafka partitions, payload distribution, duration, and error population.

The Publish transaction and single-client propagation rows are backed by clean-source `PASS` evidence at commit `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`. That status verifies reproducibility and integrity inside the stated envelope; it does not convert either row into a production capacity promise.

The reconnect result is recorded in [EV-P09-RCN-001](../evidence/phase-09/EV-P09-RCN-001/README.md) at clean commit `4ea121e8a9907c9f6926701178871e25e8ab6669`. Its one-run local percentiles establish only the recorded envelope; the scale-dependent run-to-run spread is not a production latency SLO.

The backpressure result is recorded in [EV-P09-BKP-001](../evidence/phase-09/EV-P09-BKP-001/README.md) at clean commit `15768b75984eb58ded8512af1346806759223c23`. At 1,000 sessions, 200 slow sessions retained 3,292,600 serialized bytes immediately before drain, coalesced 19,800 obsolete updates, sustained 9.989 scheduled updates/second with 6.139 ms schedule-lag p99, added no healthy-client p99 latency over the like-for-like all-ready baseline, and returned pending state to zero after drain. This is an in-process implementation envelope, not a network capacity promise.
