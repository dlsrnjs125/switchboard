# Capacity and Limitations

This is an evidence envelope, not a production sizing promise. A number is claimable only when the matching raw Phase 9 artifact was produced on the documented Git tree and environment.

| Area | Tested envelope | Enforced limit / behavior | Claim boundary |
| --- | --- | --- | --- |
| Local evaluation | 1/100/1000 flags; static/targeting/split | In-memory lookup and local evaluation only | Excludes initialization, network, disk LKG, and arbitrary rule depth |
| Snapshot compile/validate | 1/100/1000 flags | Full immutable Snapshot | Excludes transaction and broker delay; those remain separate stages |
| Publish transaction | 1/100/1000 flags; 30 sequential measured commits/size | Atomic Snapshot, audit, and outbox commit | Local PostgreSQL Testcontainer; no HTTP/JWT or concurrent-author saturation claim |
| Publish propagation | 30 sequential single-client publications | Commit → broker ACK → Distribution apply → Java Provider apply/LKG → accepted ACK | Single process/partition/client; not a fleet or WAN bound |
| gRPC Distribution | 100/500/1000 streams; connection batch 10; ACK concurrency 4 | `maximum-sessions` admission cap; one coalesced pending Snapshot/client | Single process, shared synthetic credential, local Docker PostgreSQL; unbounded authentication burst is not a supported claim |
| Reconnect | **NOT YET VERIFIED at 100/500/1000 scale** | SDK backoff/jitter and server admission are safety-tested only | The measured cohort covers initial connection, broadcast, and ACK; it is not a reconnect-storm result |
| Kubernetes | Two replicas in local kind | PDB, readiness, graceful drain, rolling strategy | No cloud LB, zone failure, or production CNI proof |
| HPA | Manifests only | CPU request and configurable min/max | metrics-server response and scale-out latency remain unverified |

## Hard boundaries

- Default Distribution session limit: 10,000 per process. This is a guardrail, not verified capacity.
- The 100/500/1,000-client result is an initial connection envelope. It does not include a stream drop, synchronized reconnect, PostgreSQL bootstrap amplification, or recovery percentiles.
- Slow clients retain at most one pending latest full Snapshot; intermediate versions may be coalesced.
- Notification retention must cover Pod churn and consumer-group recreation.
- A valid LKG preserves local evaluation during dependency loss, but freshness becomes `READY_STALE`.
- Safety failures—tenant escape, corrupt apply, version regression, or committed intent loss—have no error budget.

Scale beyond the measured envelope requires a new evidence run with explicit CPU/memory limits, connection topology, PostgreSQL pool/limit, Kafka partitions, payload distribution, duration, and error population.

The Publish transaction and single-client propagation rows are backed by clean-source `PASS` evidence at commit `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`. That status verifies reproducibility and integrity inside the stated envelope; it does not convert either row into a production capacity promise.
