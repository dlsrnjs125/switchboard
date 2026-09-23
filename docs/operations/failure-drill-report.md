# Failure Drill Report

The executable drill is `./infra/reliability/phase-06-drill.sh all`; the complete Phase 9 gate is designed to rerun it from `make final-check`. The current partial baseline does not contain Phase 9 recovery percentiles. Recovery is accepted only after authoritative and derived versions/checksums converge.

| Boundary | Executable evidence | Required invariant |
| --- | --- | --- |
| PostgreSQL cut / ambiguous commit | `PublicationIntegrationTest` | zero residue or complete committed record set; no version reuse |
| Kafka outage | `KafkaOutageIntegrationTest` | committed outbox survives and publishes after broker recovery |
| Distribution restart | `GrpcDistributionIntegrationTest` | evaluation continuity, `READY_STALE`, reconnect, convergence |
| Corrupt/reordered Snapshot | `SnapshotCoordinatorIntegrationTest`, SDK tests | reject candidate; preserve cache/LKG; monotonic version |
| Credential revoke/rotate | gRPC integration tests | old stream closes; new credential retains original scope only |
| Reconnect/backpressure | admission/backoff tests, `EV-P09-RCN-001`, `EV-P09-BKP-001` | bounded admission, reconnect convergence, and a single coalesced pending Snapshot per slow session |
| Pod loss/rolling update | `infra/kubernetes/phase-08-kind.sh` | rollout completes and SDK evaluation remains available |

The rows above combine previously verified safety/convergence behavior with the linked Phase 9 reconnect and backpressure timing envelopes. Remaining dependency-recovery rows do not yet have Phase 9 percentiles. Raw run output belongs in the matching Phase 9 artifact bundle. A process restart alone is not recovery, and a passing rerun never removes the first failure observation.
