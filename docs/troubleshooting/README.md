# Troubleshooting Index

This directory preserves engineering investigations that changed a contract, safety invariant, runtime behavior, measurement boundary, or recurrence guard. Runbooks answer "what should an operator do now?"; these records answer "why did this happen, how was it proved, and what prevents it from returning?"

The [development-history audit](development-history-audit.md) maps PRs #1–#18 and their remediation commits to ADR, Evidence, Runbook, and Troubleshooting coverage.

| Symptom | First check | Investigation |
| --- | --- | --- |
| PostgreSQL 18 container will not initialize | volume mounted at `/var/lib/postgresql` | [TRB-001](TRB-001-postgresql-18-compose-volume-layout.md) |
| Invalid targeting rule commits or cross-scope children appear | deferred constraints and tenant-qualified foreign keys | [TRB-002](TRB-002-targeting-rule-final-state-invariants.md) |
| Evaluation changes after caller input mutation | deep immutable copies and JSON-compatible keys | [TRB-003](TRB-003-immutable-evaluation-inputs.md) |
| Published Snapshot passes persistence but violates the wire contract | schema, semantic, checksum, and ordering validation | [TRB-004](TRB-004-snapshot-contract-validation.md) |
| Outbox delivery duplicates, stalls, or is completed by a stale worker | claim token, lease expiry, broker ACK, completion predicate | [TRB-005](TRB-005-outbox-lease-ownership.md) |
| A subscribing client misses an overlapping update or regresses | register-before-bootstrap and monotonic offer state | [TRB-006](TRB-006-grpc-subscription-consistency-races.md) |
| Publish response is lost after commit | authoritative version/Snapshot/audit/outbox reconciliation | [TRB-007](TRB-007-ambiguous-publish-commit.md) |
| SDK leaves `READY_STALE` although disk LKG durability is uncertain | active version, parent-directory fsync, redelivery | [TRB-008](TRB-008-sdk-lkg-readiness-and-durability.md) |
| Publish success or ACK latency metrics disagree with reality | transaction completion and per-delivery correlation ID | [TRB-009](TRB-009-telemetry-transaction-and-delivery-correlation.md) |
| Only one Distribution replica receives a publication | effective Kafka group per Pod and replica-local cache/session state | [TRB-010](TRB-010-distribution-replica-notification-fanout.md) |
| Performance numbers cannot be reproduced or compare different boundaries | source fingerprint, measured interval, raw manifest | [TRB-011](TRB-011-phase-09-evidence-provenance.md) |
| SDK applies a recovered Snapshot but the server never observes its ACK | transient unary failure and same-delivery bounded retry | [TRB-012](TRB-012-sdk-ack-retry-under-reconnect-pressure.md) |
| Valid credentials fail permanently during database pressure | separate credential rejection from dependency unavailability | [TRB-013](TRB-013-credential-dependency-failure-status.md) |
| A failed initial Snapshot lookup leaves pending gauges positive | idempotent session termination during unregister | [TRB-014](TRB-014-bootstrap-session-cleanup.md) |
| Streams are rejected | session cap, reconnect storm, credential validity | [Capacity limits](../operations/capacity-limits.md) |
| Rollout stalls or HPA does not scale | readiness, PDB, metrics-server, CPU requests | [Kubernetes runbook](../operations/kubernetes-runbook.md) |

## Capture rule

Preserve the first failure logs and exact commit before changing code. Redact credentials, targeting contexts, and payloads before attaching Evidence. A new record must include the Notion template fields: Context, Symptom, Expected vs Actual, Reproduction, Impact, Initial Hypothesis, Evidence, Root Cause, Fix, Verification, Trade-off, Prevention, Related ADR/PR/Commit, and Blog Candidate Summary.
