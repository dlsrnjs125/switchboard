# Capacity and Limitations

This is an evidence envelope, not a production sizing promise. A number is claimable only when the matching raw Phase 9 artifact was produced on the documented Git tree and environment.

| Area | Tested envelope | Enforced limit / behavior | Claim boundary |
| --- | --- | --- | --- |
| Local evaluation | 1/100/1000 flags; static/targeting/split | In-memory lookup and local evaluation only | Excludes initialization, network, disk LKG, and arbitrary rule depth |
| Snapshot compile/validate | 1/100/1000 flags | Full immutable Snapshot | Excludes transaction and broker delay; those remain separate stages |
| gRPC Distribution | 100/500/1000 streams; connection batch 10; ACK concurrency 4 | `maximum-sessions` admission cap; one coalesced pending Snapshot/client | Single process, shared synthetic credential, local Docker PostgreSQL; unbounded authentication burst is not a supported claim |
| Reconnect | Same 100/500/1000 cohort | SDK exponential backoff/jitter and server admission bound | Protocol-load result is not a WAN or multi-region claim |
| Kubernetes | Two replicas in local kind | PDB, readiness, graceful drain, rolling strategy | No cloud LB, zone failure, or production CNI proof |
| HPA | Manifests only | CPU request and configurable min/max | metrics-server response and scale-out latency remain unverified |

## Hard boundaries

- Default Distribution session limit: 10,000 per process. This is a guardrail, not verified capacity.
- Slow clients retain at most one pending latest full Snapshot; intermediate versions may be coalesced.
- Notification retention must cover Pod churn and consumer-group recreation.
- A valid LKG preserves local evaluation during dependency loss, but freshness becomes `READY_STALE`.
- Safety failures—tenant escape, corrupt apply, version regression, or committed intent loss—have no error budget.

Scale beyond the measured envelope requires a new evidence run with explicit CPU/memory limits, connection topology, PostgreSQL pool/limit, Kafka partitions, payload distribution, duration, and error population.
