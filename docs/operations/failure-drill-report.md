# Failure Drill Report

The executable correctness drill is `./infra/reliability/phase-06-drill.sh all`; the complete Phase 9 gate reruns it from `make final-check`. `EV-P09-RCV-001` adds clean-source 30-cycle recovery distributions for PostgreSQL pre/post-commit boundaries, Kafka/outbox, Distribution restart, and credential dependency failure. Recovery is accepted only after authoritative and derived versions/checksums converge.

| Boundary | Executable evidence | Required invariant |
| --- | --- | --- |
| PostgreSQL cut / ambiguous commit | `PublicationIntegrationTest`, `EV-P09-RCV-001` | zero residue or complete committed record set; no version reuse; 30-cycle recovery distribution |
| Kafka outage | `KafkaOutageIntegrationTest`, `EV-P09-RCV-001` | committed outbox survives and publishes after broker recovery; 30-cycle recovery distribution |
| Distribution restart | `GrpcDistributionIntegrationTest`, `EV-P09-RCV-001` | evaluation continuity, `READY_STALE`, reconnect, convergence; 30-cycle recovery distribution |
| Corrupt/reordered Snapshot | `SnapshotCoordinatorIntegrationTest`, SDK tests | reject candidate; preserve cache/LKG; monotonic version |
| Credential revoke/rotate/dependency loss | gRPC integration tests, `EV-P09-RCV-001` | old stream closes; new credential retains original scope; dependency retry converges across 30 cycles |
| Reconnect/backpressure | admission/backoff tests, `EV-P09-RCN-001`, `EV-P09-BKP-001` | bounded admission, reconnect convergence, and a single coalesced pending Snapshot per slow session |
| Pod loss/rolling update | `infra/kubernetes/phase-08-kind.sh` | rollout completes and SDK evaluation remains available |

The rows above combine previously verified safety/convergence behavior with the linked Phase 9 reconnect, backpressure, and timed recovery envelopes. PostgreSQL pre-commit/post-commit, Kafka/outbox, Distribution restart, and credential dependency p50/p95/p99/max are recorded in `EV-P09-RCV-001`. Raw run output belongs in the matching Phase 9 artifact bundle. A process restart alone is not recovery, and a passing rerun never removes the first failure observation.
