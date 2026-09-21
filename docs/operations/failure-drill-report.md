# Failure Drill Report

The executable drill is `./infra/reliability/phase-06-drill.sh all`; the complete Phase 9 gate is designed to rerun it from `make final-check`. The current partial baseline does not contain Phase 9 recovery percentiles. Recovery is accepted only after authoritative and derived versions/checksums converge.

| Boundary | Executable evidence | Required invariant |
| --- | --- | --- |
| PostgreSQL cut / ambiguous commit | `PublicationIntegrationTest` | zero residue or complete committed record set; no version reuse |
| Kafka outage | `KafkaOutageIntegrationTest` | committed outbox survives and publishes after broker recovery |
| Distribution restart | `GrpcDistributionIntegrationTest` | evaluation continuity, `READY_STALE`, reconnect, convergence |
| Corrupt/reordered Snapshot | `SnapshotCoordinatorIntegrationTest`, SDK tests | reject candidate; preserve cache/LKG; monotonic version |
| Credential revoke/rotate | gRPC integration tests | old stream closes; new credential retains original scope only |
| Reconnect/backpressure | admission, backoff, and client-session tests | bounded admission and a single coalesced pending Snapshot |
| Pod loss/rolling update | `infra/kubernetes/phase-08-kind.sh` | rollout completes and SDK evaluation remains available |

The rows above describe previously verified safety and convergence behavior, not new Phase 9 timing evidence. Raw run output belongs in the CI log or a complete Phase 9 artifact bundle. A process restart alone is not recovery, and a passing rerun never removes the first failure observation.
