# Troubleshooting Index

| Symptom | First check | Reference |
| --- | --- | --- |
| PostgreSQL 18 container will not initialize | volume mounted at `/var/lib/postgresql` | [TRB-001](TRB-001-postgresql-18-compose-volume-layout.md) |
| Publish outcome is ambiguous | current version, Snapshot, audit, outbox as one set | [Reliability runbook](../operations/runbook.md) |
| Pending outbox age rises | broker health, lease expiry, relay ACK/completion | [Observability runbook](../operations/observability-runbook.md) |
| SDK is `READY_STALE` | stream health, heartbeat, LKG durability, version lag | [SDK lifecycle](../architecture/sdk-lifecycle.md) |
| Client receives NACK/resync | schema/checksum/version and authoritative Snapshot | [Reliability runbook](../operations/runbook.md) |
| Streams are rejected | session cap, reconnect storm, credential validity | [Capacity limits](../operations/capacity-limits.md) |
| Rollout stalls | readiness, PDB, terminating sessions, dependency health | [Kubernetes runbook](../operations/kubernetes-runbook.md) |
| HPA does not scale | metrics-server and CPU requests | [Kubernetes runbook](../operations/kubernetes-runbook.md) |

Preserve the first failure logs and redact credentials, targeting contexts, and payloads before attaching evidence.
