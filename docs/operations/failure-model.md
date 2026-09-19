# Failure Model

## Purpose

This document defines the failure behavior that later implementation phases must preserve and prove. It translates the Foundation invariants, accepted ADRs, Phase 0D contracts, and Phase 0E data model into testable failure boundaries.

It is a design baseline, not evidence that the runtime already satisfies these behaviors. Executable failure drills begin in the owning implementation phase and produce artifacts according to the [evidence policy](../evidence/README.md).

## System safety priorities

When goals conflict, implementations use this order:

1. Prevent cross-tenant disclosure or mutation.
2. Never apply corrupt, incompatible, partial, or regressive configuration.
3. Preserve committed publication history and delivery intent.
4. Continue request-time local evaluation from a validated Last Known Good snapshot.
5. Restore freshness without version reuse or history mutation.
6. Recover capacity gradually without an unbounded retry or reconnect cascade.

Availability never justifies weakening tenant scope, checksum validation, version monotonicity, or immutable history.

## Failure vocabulary

| Term | Meaning |
| --- | --- |
| Authoritative state | Committed PostgreSQL management, publication, snapshot, audit, and outbox state. |
| Delivery state | Kafka notification, Distribution cache/session state, and SDK active/LKG state derived from authoritative state. |
| Safe stale | A previously validated snapshot remains usable while freshness is unconfirmed; the SDK exposes `READY_STALE`. |
| Fail closed | Reject an operation or payload rather than risk isolation or integrity. |
| Reconcile | Read authoritative state and converge derived state without assuming message order. |
| Recovery complete | The component is healthy and all affected derived state has been reconciled, not merely restarted. |

Reconciliation applies to derived Distribution cache/session and SDK state. It does not complete or discard an outbox delivery intent. An outbox event reaches `PUBLISHED` only after Kafka broker acknowledgement and persistence of its publication metadata, including `published_at`.

## Failure-case contract

Every executable drill must record:

- **Trigger**: the exact fault and injection point;
- **Expected behavior**: externally observable state and response;
- **User impact**: management, freshness, bootstrap, or evaluation effect;
- **Consistency risk**: the invariant that could be violated;
- **Detection**: metrics, logs, traces, health, and alert signal;
- **Recovery**: convergence path and abort condition;
- **Verification**: assertions that decide PASS or FAIL;
- **Evidence path**: durable artifacts linked to a Git commit and environment fingerprint.

## Summary matrix

| ID | Failure boundary | Safety outcome | Owning phase |
| --- | --- | --- | --- |
| `FM-CP-001` | Control Plane unavailable | Existing local evaluation continues; management stops safely | 1, 5, 6 |
| `FM-PG-001` | PostgreSQL unavailable during management/publish | No partial publication; committed state remains authoritative | 1, 3, 6 |
| `FM-KFK-001` | Kafka unavailable after DB commit | Publish remains committed; outbox retries without loss | 3, 6 |
| `FM-DST-001` | Distribution unavailable | SDK retains LKG and becomes `READY_STALE` | 4, 5, 6 |
| `FM-SNP-001` | Corrupt or incompatible snapshot | Payload is rejected; active snapshot is unchanged | 4, 5, 6 |
| `FM-ORD-001` | Duplicate or out-of-order delivery | Consumer is idempotent and never regresses | 3, 4, 5, 6 |
| `FM-GAP-001` | Version gap or conflicting checksum | Full resync is required; no partial merge occurs | 4, 5, 6 |
| `FM-CRD-001` | Credential revoked or rotated | Future authentication fails; existing streams close within the configured bound; audit history remains | 1, 4, 6 |
| `FM-TEN-001` | Cross-tenant access attempt | Access is denied without existence disclosure or side effect | 1, 3, 4, 6 |
| `FM-SDK-001` | SDK restart while remote is unavailable | Valid disk LKG boots stale; otherwise remains `NOT_READY` | 5, 6 |
| `FM-RCN-001` | Reconnect storm | Recovery is bounded, jittered, and capacity-protected | 4, 5, 6, 9 |
| `FM-BKP-001` | Slow client or downstream backpressure | Memory remains bounded; lagging client resyncs from full snapshot | 4, 6, 9 |

## `FM-CP-001` — Control Plane unavailable

- **Trigger:** stop or isolate every Control Plane instance while Distribution and already-bootstrapped SDKs remain running.
- **Expected behavior:** management APIs become unavailable. Distribution continues serving its valid cache and may read committed snapshots from PostgreSQL. SDK evaluation never falls back to the Control Plane or remote evaluation.
- **User impact:** authoring, publish, rollback, credential administration, and audit queries may be unavailable. Existing applications continue local evaluation from active snapshots.
- **Consistency risk:** a client could couple request-time evaluation to management availability or accept an uncommitted configuration.
- **Detection:** Control Plane availability, request error rate, and dependency health identify the outage; SDK evaluation and freshness telemetry remain separately visible.
- **Recovery:** restart or reconnect the Control Plane, verify PostgreSQL state, and resume management traffic. No snapshot version or audit record is synthesized merely because the process restarted.
- **Verification:** sustained evaluation uses the same version and values throughout the outage; no evaluation network call occurs; management requests fail explicitly rather than partially commit.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-CP-001/`.

## `FM-PG-001` — PostgreSQL unavailable

- **Trigger:** make PostgreSQL unavailable before commit, during a publication attempt, and after a known successful commit.
- **Expected behavior:** operations that cannot establish a successful transaction fail. An uncommitted publish produces no state, snapshot, audit, or outbox residue. A committed publish remains authoritative even if the response outcome was ambiguous.
- **User impact:** management writes, new snapshot bootstrap, and outbox polling may stop. Existing Distribution cache and SDK LKG can continue serving already-known configuration.
- **Consistency risk:** partial publication, version reuse, blind retry of an ambiguous outcome, or treating cache/Kafka as authority.
- **Detection:** database health, transaction failures, publish errors, outbox poll failures, and stale Distribution/SDK signals.
- **Recovery:** restore PostgreSQL, then re-read the tenant-qualified environment version, snapshot/checksum, audit record, and outbox row. Reconcile from committed state before retrying; never decrement or reuse a version.
- **Verification:** pre-commit failure leaves all publication tables unchanged; post-commit recovery finds a complete atomic record set; no external event exists for rolled-back work.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-PG-001/`.

## `FM-KFK-001` — Kafka unavailable after commit

- **Trigger:** stop or isolate Kafka before the outbox relay publishes a committed snapshot notification.
- **Expected behavior:** the publish API may succeed because PostgreSQL commit is the publication boundary. The outbox event remains retryable and Distribution freshness may lag. No committed delivery intent is lost.
- **User impact:** new configuration is durable but may not reach clients until messaging recovers. Existing evaluation continues from the prior snapshot.
- **Consistency risk:** marking an event published without acknowledgement, losing the row, or assuming exactly-once delivery.
- **Detection:** oldest pending outbox age, pending count, attempt count, relay failure rate, and propagation latency.
- **Recovery:** resume relay polling after Kafka recovery. Mark an event `PUBLISHED` and set `published_at` only after broker acknowledgement. A crash after broker acknowledgement but before that update may publish a duplicate; consumers reconcile derived state by event identity and authoritative snapshot version.
- **Verification:** every committed outbox event is eventually broker-acknowledged and marked `PUBLISHED` with `published_at`; duplicate publication does not duplicate derived state or regress a cache. Distribution/SDK reconciliation cannot substitute for publishing the committed outbox intent.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-KFK-001/`.

## `FM-DST-001` — Distribution unavailable

- **Trigger:** stop Distribution, interrupt the stream, or isolate it from SDKs.
- **Expected behavior:** an SDK with an active LKG transitions from `READY` to `READY_STALE` when freshness is no longer confirmed and continues local evaluation. It does not call the Control Plane on the evaluation path.
- **User impact:** configuration freshness and new-client bootstrap are degraded. Existing applications keep using the last validated state.
- **Consistency risk:** clearing LKG, silently presenting stale state as fresh, or introducing request-time remote evaluation.
- **Detection:** stream connectivity, heartbeat age, SDK provider state, snapshot age, and reconnect attempts.
- **Recovery:** reconnect with exponential backoff and jitter, report the last applied version, receive the current full snapshot when required, validate, atomically apply, persist LKG, and return to `READY`.
- **Verification:** evaluations remain deterministic during the outage; provider state exposes staleness; recovery never exposes a partial snapshot.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-DST-001/`.

## `FM-SNP-001` — Corrupt or incompatible snapshot

- **Trigger:** alter canonical payload bytes, checksum, schema version, typed value, variant reference, priority, or allocation total.
- **Expected behavior:** Distribution and SDK independently validate the payload at their trust boundaries. The consumer rejects it, records a bounded diagnostic, NACKs or requests resync, and retains its prior valid cache/LKG.
- **User impact:** affected clients remain on older known-good configuration and become observably stale; they do not apply the bad update.
- **Consistency risk:** corrupt LKG, mixed snapshot state, or accepting the same version with different content.
- **Detection:** checksum/schema/semantic validation failure counters, NACK reason, version, expected/observed checksum, and correlation ID. Payload content and secrets are not logged.
- **Recovery:** reload the immutable authoritative snapshot. If the authoritative artifact itself is invalid, halt propagation and repair through a new publication; never edit the committed snapshot in place.
- **Verification:** corrupt snapshot applications equal zero; active version/checksum remain unchanged; a later valid higher version restores freshness.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-SNP-001/`.

## `FM-ORD-001` — Duplicate or out-of-order delivery

- **Trigger:** deliver the same outbox event repeatedly and deliver versions `N+1`, `N`, `N+1` in that order.
- **Expected behavior:** event processing is idempotent by stable event ID. A snapshot lower than the active version is ignored. The same version with the same checksum is idempotent; the same version with a different checksum is rejected as corruption.
- **User impact:** none beyond telemetry; a duplicate must not cause a second logical transition.
- **Consistency risk:** snapshot regression, duplicate side effects, or trusting broker order.
- **Detection:** duplicate-event, stale-version, and checksum-conflict counters.
- **Recovery:** reconcile the current version/checksum from PostgreSQL-backed Distribution state when order is uncertain.
- **Verification:** active versions are monotonically non-decreasing and the number of logical applies matches unique accepted versions.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-ORD-001/`.

## `FM-GAP-001` — Version gap or conflicting checksum

- **Trigger:** a client at version `N` observes a newer current version after missing one or more notifications, or receives version `N` with a different checksum.
- **Expected behavior:** MVP never reconstructs a delta chain. The client requests resync and receives the current full snapshot. A same-version checksum conflict is rejected and escalated as an integrity violation.
- **User impact:** freshness is delayed during resync; current LKG remains available.
- **Consistency risk:** merging partial state, inventing intermediate versions, or overwriting valid same-version content.
- **Detection:** resync reason, version gap size, checksum conflict, resync success/failure, and time to recover.
- **Recovery:** load, validate, and atomically apply the current full snapshot; acknowledge only after successful application.
- **Verification:** the post-recovery snapshot exactly matches the authoritative version/checksum and no intermediate partial state is observable.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-GAP-001/`.

## `FM-CRD-001` — Credential revoked or rotated

- **Trigger:** revoke an active credential, attempt fresh authentication with it, then rotate to a new credential.
- **Expected behavior:** revocation invalidates both future authentication and every already-authenticated stream using that credential. Existing streams stop receiving snapshots and close within a finite, configured enforcement interval measured from the authoritative revocation commit. The server may emit `CredentialRevoked` before closing the stream. Phase 4 chooses the revalidation/notification mechanism and concrete interval, but it may not defer enforcement until an otherwise optional reconnect.
- **User impact:** the revoked client must reconnect with a valid credential. Other tenant/project/environment sessions are unaffected.
- **Consistency risk:** authorization surviving revocation, scope expansion during rotation, raw secret persistence, or loss of audit history.
- **Detection:** credential ID/prefix, tenant-qualified client application, revoke audit event, rejected authentication, and active-session enforcement telemetry. Raw secrets are never logged.
- **Recovery:** issue and distribute a new credential through the authorized control path; reconnect and verify identical scope. `REVOKED -> ACTIVE` is forbidden, and an old stream is never transferred to the new credential identity.
- **Verification:** old credential authentication fails; every stream authenticated by it receives no new snapshot after enforcement and closes within the configured bound; the new credential succeeds only in its scope; raw secret is absent from DB/log/evidence; and audit continuity is preserved.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-CRD-001/`.

## `FM-TEN-001` — Cross-tenant access attempt

- **Trigger:** use a valid Tenant A principal or credential with Tenant B IDs, keys, publish versions, snapshots, or stream parameters.
- **Expected behavior:** server-derived authorization scope rejects the request before data access or mutation. The response does not reveal whether Tenant B's resource exists. No audit/outbox/snapshot side effect is created except a security audit event when policy explicitly requires one.
- **User impact:** the unauthorized operation fails; authorized traffic is unaffected.
- **Consistency risk:** IDOR, credential-to-application cross-link, cross-tenant publish, or identifier enumeration.
- **Detection:** sanitized authorization-denied metric and security audit data with derived scope; no target-tenant payload or existence detail is logged.
- **Recovery:** none for the rejected operation. Repeated attempts may invoke rate-limit or incident procedures in later operational phases.
- **Verification:** read, write, archive, publish, rollback, credential, audit, outbox, and Distribution paths all reject cross-tenant inputs with indistinguishable missing/unauthorized responses.
- **Evidence path:** `docs/evidence/phase-01/EV-P01-TEN-001/`, extended in Phases 3 and 4.

## `FM-SDK-001` — SDK restart while remote is unavailable

- **Trigger:** persist a valid LKG, stop the SDK/application, make Distribution unavailable, and restart. Repeat with missing, corrupt, incompatible, and older LKG files.
- **Expected behavior:** a compatible checksum-valid LKG boots into evaluation-ready stale behavior and exposes `READY_STALE`. Without a valid LKG the provider remains `NOT_READY` and follows the Phase 5 OpenFeature default/error contract.
- **User impact:** applications with valid LKG continue known configuration; first boot or damaged cache cannot claim readiness.
- **Consistency risk:** treating an unvalidated file as LKG, losing atomicity, or hiding bootstrap failure.
- **Detection:** bootstrap source, validation result, provider state, snapshot version/checksum, and cache read/write failure counters.
- **Recovery:** reconnect, obtain a valid current full snapshot, atomically apply it, durably replace LKG, and transition to `READY`.
- **Verification:** each cache condition maps to the expected state; corrupt cache is never active; application evaluation never observes a partial replacement.
- **Evidence path:** `docs/evidence/phase-06/EV-P06-SDK-001/`.

## `FM-RCN-001` — Reconnect storm

- **Trigger:** disconnect a representative client fleet simultaneously and restore Distribution connectivity.
- **Expected behavior:** clients use exponential backoff with jitter; Distribution applies admission and connection limits; snapshot loading is shared or cached; recovery does not synchronize into an immediate retry wave.
- **User impact:** freshness recovery is staggered. Existing LKG evaluation continues while clients wait.
- **Consistency risk:** cascading resource exhaustion, database overload, retry amplification, or clients bypassing validation to recover faster.
- **Detection:** connection attempts, accepted/rejected streams, retry delay distribution, active connections, DB snapshot loads, CPU, memory, queue depth, and recovery percentiles.
- **Recovery:** gradually admit connections, serve the current full snapshot, and verify fleet convergence. Capacity protection may delay freshness but never drops integrity checks.
- **Verification:** resource use remains within the declared test envelope, no unbounded queue appears, and all admitted clients converge monotonically to the authoritative version.
- **Evidence path:** `docs/evidence/phase-09/EV-P09-RCN-001/`.

## `FM-BKP-001` — Slow client and backpressure

- **Trigger:** make one or more clients stop reading while snapshots continue to publish.
- **Expected behavior:** per-client buffering is bounded. Because MVP snapshots are full images, obsolete queued updates may be coalesced to the newest current version. A client that cannot keep up is disconnected or told to resync; server memory is not allowed to grow without bound.
- **User impact:** slow clients become stale and may reconnect. Healthy clients continue receiving updates.
- **Consistency risk:** global head-of-line blocking, memory exhaustion, or partial snapshot delivery interpreted as complete.
- **Detection:** per-stream queue depth, write latency, dropped/coalesced update count, disconnect reason, memory, and healthy-client propagation latency.
- **Recovery:** reconnect with last applied version and perform full-snapshot resync. The client acknowledges only after complete validation and atomic apply.
- **Verification:** slow-client isolation holds, queues respect configured bounds, and healthy-client SLI remains inside the experiment target.
- **Evidence path:** `docs/evidence/phase-09/EV-P09-BKP-001/`.

## Cross-cutting recovery rules

- PostgreSQL is always the reconciliation authority; Kafka arrival order and process memory are not.
- A process restart never rewrites published history or resets snapshot versions.
- Recovery uses full immutable snapshots until a separately specified, gap-safe delta protocol exists.
- A valid LKG is retained until a newer snapshot passes compatibility, semantic, checksum, and atomic-apply checks.
- Retry loops require bounded exponential backoff, jitter, cancellation, maximum resource limits, and observable terminal outcomes.
- Logs and evidence must exclude raw credentials, authorization tokens, full targeting contexts, and unbounded payloads.

## Phase gate

An implementation phase may claim a failure case only when its test names the failure ID, asserts every applicable expected behavior, and stores reproducible evidence. Restarting a component without proving state convergence is not a completed recovery drill.
