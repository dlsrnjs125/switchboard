# Reliability Recovery Runbook

## Purpose and safety order

Use this runbook for the Phase 6 failure boundaries. Protect tenant isolation, Snapshot integrity, immutable history, committed outbox intent, and LKG continuity before freshness or throughput. Do not repair an incident by editing a published Snapshot, decrementing a version, bypassing checksum validation, reviving a revoked credential, or marking an outbox row published without broker acknowledgement.

## Common incident procedure

1. Record start time, affected tenant/project/environment scope, process/container identity, current authoritative version/checksum, Distribution cache version, SDK version/state, and oldest pending outbox age.
2. Stop new management changes for the affected scope when the commit outcome is ambiguous.
3. Preserve logs and the first failure artifact; never erase it with a successful rerun.
4. Apply the boundary-specific recovery below.
5. Declare recovery complete only after authoritative and derived state converge.
6. Remove the injected fault and verify cleanup. Abort the drill if isolation or integrity can no longer be proved.

## PostgreSQL unavailable during publish — `FM-PG-001`

- **Trigger/detection:** connection timeout, transaction failure, database health failure, or an ambiguous publish response.
- **Immediate action:** stop blind retries for the same expected version and correlation ID. Existing SDK LKG evaluation may continue.
- **Recovery:** restore connectivity; read environment current version, Snapshot ID/checksum, flag state, audit event, and outbox row in one tenant-qualified investigation.
- **Verification:** an uncommitted attempt has zero residue. A committed attempt has the complete record set. The next publish uses the observed current version and never reuses a version.
- **Abort:** any partial authoritative record set, cross-tenant result, mutable historical row, or unexplained version decrease.

## Kafka unavailable after commit — `FM-KFK-001`

- **Trigger/detection:** publisher timeout/failure with a committed pending outbox row.
- **Immediate action:** do not roll back the committed publication and do not mark the event published manually.
- **Recovery:** restore the broker, allow the expired/failed row to become retryable, and resume relay polling with the same stable event ID.
- **Verification:** broker acknowledgement occurs before `published_at`; retry may duplicate delivery but Distribution logical state remains idempotent and monotonic.
- **Abort:** missing outbox intent, `published_at` without broker ACK, changed event ID, or a derived-state regression.

## Distribution outage or restart — `FM-DST-001`

- **Trigger/detection:** stream close, failed gRPC health, reconnect attempts, or SDK `READY_STALE`.
- **Immediate action:** preserve SDK memory/disk LKG; do not introduce request-time remote evaluation.
- **Recovery:** restore Distribution, authenticate again, subscribe with last applied version, load the PostgreSQL-authoritative full Snapshot when required, validate, persist, atomically apply, and ACK.
- **Verification:** evaluations remain stable during outage; state transitions `READY → READY_STALE → READY`; final version/checksum matches authority.
- **Abort:** LKG cleared, request-time network call, partial Snapshot observation, or version regression.

## Corrupt, incompatible, reordered, or gapped Snapshot — `FM-SNP-001`, `FM-ORD-001`, `FM-GAP-001`

- **Trigger/detection:** schema/semantic/checksum error, same-version conflict, duplicate event, stale version, or heartbeat ahead.
- **Immediate action:** reject/NACK the candidate and retain current cache/LKG. Never patch the committed Snapshot in place.
- **Recovery:** reload the current immutable full Snapshot from PostgreSQL and resync; publish a new higher version if authoritative data itself is invalid.
- **Verification:** corrupt applies remain zero; duplicate is idempotent; final version/checksum exactly matches authority.
- **Abort:** mixed Snapshot state, changed same-version content, or an intermediate delta reconstruction.

## Credential revoke and rotation — `FM-CRD-001`

- **Trigger/detection:** revoke commit, failed scheduled revalidation, or an old stream still receiving data after the enforcement bound.
- **Immediate action:** reject new authentication, close every session for the credential, and preserve sanitized audit context without raw secrets.
- **Recovery:** issue a new credential for the same explicitly authorized application scope and reconnect as a new identity. Never reactivate the revoked row or transfer its stream.
- **Verification:** old authentication fails; old streams close; rotated authentication works only in the original scope; raw secret is absent from persistence and evidence.
- **Abort:** authorization survives revoke, scope expands, or raw secret appears in logs/database/evidence.

## SDK restart and disk LKG — `FM-SDK-001`

- **Trigger/detection:** application restart while Distribution is unavailable, corrupt/expired cache, or failed LKG replacement.
- **Immediate action:** use only checksum-valid compatible LKG. Missing or invalid cache stays `NOT_READY` and returns OpenFeature code defaults.
- **Recovery:** reconnect and replace LKG using forced temporary-file write and atomic same-directory rename. After rename, treat the candidate as the logical disk commit: align memory immediately, require parent-directory fsync before ACK, and keep `READY_STALE` with `LKG_DURABILITY_UNCERTAIN` NACK until redelivery confirms the fsync.
- **Verification:** valid cache starts `READY_STALE`; corrupt/expired cache is quarantined; interrupted temporary files never become active; post-rename fsync failure leaves the running process and a normal restart on the same version; same-version heartbeat cannot restore `READY` before fsync confirmation.
- **Abort:** unvalidated bytes become active or memory advances before filesystem persistence completes.

## Reconnect pressure and slow clients — `FM-RCN-001`, `FM-BKP-001`

- **Trigger/detection:** simultaneous reconnect attempts, `RESOURCE_EXHAUSTED`, non-reading stream, or pending coalescing.
- **Immediate action:** keep exponential backoff with jitter, enforce the configured session maximum, and keep one pending latest full Snapshot per client.
- **Recovery:** gradually admit clients; serve cached/current full Snapshots; let rejected clients retry with backoff.
- **Verification:** rejected admission occurs before authoritative Snapshot bootstrap DB load, queue depth remains one, and admitted clients converge monotonically. Credential lookup and BCrypt verification still occur before admission.
- **Abort:** unbounded queue, synchronized retry loop, healthy-client head-of-line blocking, or disabled integrity checks.

## Outbox relay or consumer process crash

- **Trigger/detection:** claimed row exceeds lease, broker record may have been ACKed without completion update, or consumer restarts/rebalances.
- **Immediate action:** retain the row and stable event ID; do not infer completion from derived cache state.
- **Recovery:** reclaim only expired leases, republish at least once, reject stale claim-token completion, restart consumer, and reconcile from PostgreSQL.
- **Verification:** one current claim owns completion, duplicate delivery causes no duplicate logical apply, and cache reaches the authoritative version/checksum.
- **Abort:** two live claims complete the same row, delivery intent is discarded, or cache regresses.

## Reproduction commands

```bash
./infra/reliability/phase-06-drill.sh dependencies
./infra/reliability/phase-06-drill.sh distribution
./infra/reliability/phase-06-drill.sh sdk
./infra/reliability/phase-06-drill.sh all
```

The harness and tests restore proxy connectivity, unpause Kafka in `finally`, stop gRPC servers/channels/providers, and rely on Testcontainers cleanup. A run with incomplete cleanup is invalid.
