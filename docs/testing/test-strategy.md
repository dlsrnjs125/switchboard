# Test Strategy

## Purpose

This strategy defines how Switchboard verifies its contracts and failure boundaries from Phase 1 onward. It does not require every test type in every module; it assigns each invariant to the lowest-cost layer that can prove it and retains end-to-end tests for boundaries that unit tests cannot establish.

The normative sources are:

- [Foundation invariants](../foundation/invariants.md)
- [State transitions](../foundation/state-transitions.md)
- [Accepted ADRs](../adr/README.md)
- [Executable contracts](../../contracts/README.md)
- [Data model baseline](../data-model/table-spec.md)
- [Failure model](../operations/failure-model.md)
- [SLI/SLO design](../operations/sli-slo-design.md)

## Test layers

| Layer | Proves | Typical dependencies | Default cadence |
| --- | --- | --- | --- |
| Unit | Pure domain rules, validation, hashing, state transitions | None or in-memory fakes | Every PR |
| Contract | OpenAPI, protobuf, snapshot schema, compatibility, golden vectors | Contract tooling | Every PR |
| Database integration | PostgreSQL constraints, transactions, locking, migration, tenant-qualified queries | Real supported PostgreSQL | Every relevant PR |
| Component integration | One deployable boundary with real protocol/storage adapters | Containerized dependencies | Every relevant PR |
| End-to-end | Publish → outbox → Distribution → SDK apply → evaluation | Full local stack | Main/release gate |
| Security negative | Tenant isolation, credential scope/revoke, non-disclosure | Real auth and persistence boundaries | Every relevant PR plus release |
| Failure drill | Behavior during dependency/process/network faults and recovery | Fault injection and full/partial stack | Scheduled and release gate |
| Performance | Latency, propagation, capacity, reconnect, backpressure | Controlled load environment | Scheduled and Phase 9 gate |

Mocks may verify caller behavior but cannot prove database isolation, transaction atomicity, wire compatibility, or failure recovery.

## Core quality gates

### Pull request gate

- formatting/static checks and `git diff --check`;
- all unit tests;
- `:contracts:check` and compatibility fixtures;
- affected PostgreSQL migration-from-zero and integration tests;
- deterministic tests with fixed clocks/seeds where applicable;
- security negative tests for changed authorization or credential paths;
- relative documentation link and required-section checks for baseline documents.

### Main and release gate

- clean build from a fresh checkout;
- supported PostgreSQL/Kafka stack health;
- end-to-end publish and rollback path;
- cross-tenant read/write/stream rejection;
- immutable revision/snapshot checks;
- outbox duplicate/recovery path;
- full-snapshot ACK/NACK/resync and LKG behavior;
- evidence record for every scheduled failure/performance run used as a release claim.

### Scheduled gate

- upgrade-path migrations from the most recent released schema;
- reconnect storm and slow-client/backpressure experiments;
- dependency outage and process-kill drills;
- representative performance suites;
- longer-running leak, queue-bound, and convergence checks.

## Required fixture model

Integration and E2E suites create at least:

- Tenant A and Tenant B with deliberately similar project, environment, flag, and client application keys;
- one authorized and one unauthorized human principal per tenant;
- active, revoked, expired, and rotated service credentials without persisting raw secrets;
- draft and published revisions with typed variants, rules, and allocations;
- at least two immutable snapshots with distinct increasing versions and checksums;
- pending and published outbox events, including duplicate delivery;
- a valid LKG plus corrupt, incompatible, and older cache fixtures.

Fixtures use generated non-production data. Tests must never embed live credentials or user-identifying targeting contexts.

## Invariant verification matrix

| Invariant group | Primary layer | Required assertion |
| --- | --- | --- |
| `INV-TEN-*`, `INV-FLG-002`, `INV-CRD-001` | DB integration + security E2E | Server-derived scope and composite constraints prevent every cross-tenant association/access. |
| `INV-REV-*`, `INV-RUL-*` | Unit + contract + DB integration | Invalid typed values/references/priorities/allocations cannot publish; published content cannot mutate. |
| `INV-PUB-*` | DB integration + E2E | Version conflict and injected failure commit no partial state; successful publish creates the complete atomic set. |
| `INV-SNP-*`, `INV-RBK-001` | Contract + DB integration + E2E | Full snapshot is immutable, checksummed, monotonic, and rollback creates a higher version. |
| `INV-MSG-*` | Component integration + failure drill | Duplicate/lost-order notification is safe and current state can be reconciled from PostgreSQL. |
| `INV-SDK-*` | Unit + component + failure drill | Validation precedes atomic apply; version never regresses; valid LKG survives remote failure; evaluation performs no I/O. |
| `INV-AUD-001` | DB integration + E2E | Management change is reconstructable and audit data is append-only. |

## Failure verification matrix

| Failure ID | Minimum setup | PASS condition | Primary evidence phase |
| --- | --- | --- | --- |
| `FM-CP-001` | Running SDK with LKG; stop Control Plane | Evaluation continues unchanged; management fails explicitly | 6 |
| `FM-PG-001` | Publish fault points around commit | No partial record set; committed outcome reconciles without version reuse | 6 |
| `FM-KFK-001` | Kafka unavailable with committed outbox | Pending event survives and later converges; duplicate is harmless | 6 |
| `FM-DST-001` | Connected SDK; stop Distribution | `READY_STALE`, LKG evaluation, valid full resync on recovery | 6 |
| `FM-SNP-001` | Corrupt/checksum/schema/semantic fixtures | NACK/reject, unchanged LKG, zero corrupt applies | 6 |
| `FM-ORD-001` | Duplicate and reordered notifications | Idempotency and monotonic active version | 6 |
| `FM-GAP-001` | Client behind authoritative version | Full resync to exact current version/checksum | 6 |
| `FM-CRD-001` | Active stream plus revoke/rotation | Old auth denied, new scoped auth accepted, raw secret absent | 6 |
| `FM-TEN-001` | Two-tenant fixture | No read/write/stream leak or target existence disclosure | 1, 3, 4 |
| `FM-SDK-001` | Restart with valid/invalid LKG and no remote | Valid LKG becomes stale-ready; invalid/missing remains `NOT_READY` | 6 |
| `FM-RCN-001` | Simultaneous reconnect cohort | Jittered bounded recovery and monotonic convergence | 9 |
| `FM-BKP-001` | Slow/non-reading clients | Bounded queues and healthy-client isolation | 9 |

## Security negative suite

For every externally supplied ID or key, repeat the operation with:

1. a missing resource inside the authorized tenant;
2. an existing equivalent resource in another tenant;
3. a valid resource with an unauthorized role;
4. a valid resource with a revoked or wrong-scope service credential.

Responses for missing and cross-tenant resources must not disclose target existence through status, body, timing assumptions, logs, or metrics. Tests assert that failed operations create no target mutation, snapshot, or outbox side effect.

The minimum paths are list/get/create/update/archive, revision reads/writes, publish, rollback, current snapshot, client application, credential issuance/revoke, audit query, Distribution subscribe, ACK/NACK, and resync.

## Transaction and persistence testing

- Inject failure after each write in the publication unit of work and before commit.
- Prove all-or-nothing state across environment state, snapshot, entries, audit, and outbox.
- Execute concurrent publishes with the same expected version; exactly one may commit.
- Verify migration from empty schema and the latest released schema.
- Prove immutable tables reject unauthorized update/delete at both application and database layers.
- Prove outbox leasing/retry is safe across relay crash before publish, after publish, and before completion update.
- Reconcile database state after connection-loss ambiguity instead of asserting from the client response alone.

## Contract and snapshot testing

- Compile protobuf and verify reserved fields are not reused.
- Parse OpenAPI and resolve every local reference.
- Validate known-valid and known-invalid snapshot fixtures structurally and semantically.
- Recompute RFC 8785 canonical checksum after removing the top-level `checksum` member.
- Verify same-version/same-checksum idempotency and same-version/different-checksum rejection.
- Run rollout golden vectors across every SDK implementation.
- Keep invalid fixtures focused: one intentional violation per fixture unless the test documents a combined attack.

## SDK and evaluation testing

- Evaluation unit tests use immutable snapshots and cover every value type, operator, default, disabled, targeting, split, and missing-targeting-key path.
- Concurrency tests prove an evaluation sees the complete old or complete new snapshot, never a mixture.
- A request-time network/database spy fails the test if evaluation performs I/O.
- LKG persistence tests include interrupted writes and require atomic file replacement.
- Provider state tests distinguish `READY`, `READY_STALE`, `NOT_READY`, `ERROR`, and `CLOSED` without inferring freshness from evaluation success alone.
- Fake time controls heartbeat/freshness transitions; wall-clock sleeps are avoided in deterministic suites.

## Failure-injection discipline

Each drill has a control run, one declared fault, and a recovery observation period. The harness must record the fault start/end, process/container identity, network rule, workload, and cleanup result.

Allowed mechanisms include process termination, container pause/stop, Toxiproxy-style latency/loss/reset, broker outage, database connection termination, malformed payload fixture, and controlled slow reader. Broad machine failure or destructive data deletion is not required to prove these baselines.

Cleanup is part of PASS. A drill that leaves network rules, corrupted fixtures, or uncontrolled processes behind is invalid.

## Performance methodology baseline

- Warm up JIT and connections separately from measured intervals.
- Pin tool/runtime versions and record CPU, memory, OS, JDK, container limits, topology, payload size, flag count, and client count.
- Keep arrival model, concurrency, duration, ramp, and timeout explicit.
- Report failures/timeouts with latency; do not calculate percentiles over successful samples only.
- Repeat enough runs to expose variance and publish raw result files plus the aggregation command.
- Separate local evaluation, publish compilation, outbox lag, Distribution load, network delivery, SDK apply, and ACK latency.
- Treat coordinated omission and client-side clock skew as known measurement risks.

## Flaky-test policy

A failing test is not rerun until green and ignored. The first failure artifact is retained. A quarantined test requires an owner, linked issue, reason, scope, and expiry date; quarantined release-gate security or integrity tests make the gate fail.

Tests avoid shared tenant keys, fixed ports, real wall-clock deadlines, unseeded randomness, order dependence, and global mutable fixtures. Randomized/property tests record the seed needed for reproduction.

## Phase ownership

| Phase | Verification added |
| --- | --- |
| 1 | PostgreSQL migrations, tenant/RBAC negative suite, draft lifecycle, immutability foundations |
| 2 | Pure evaluation semantics, rollout golden vectors, local latency baseline |
| 3 | Atomic publication, concurrency, rollback lineage, outbox crash/duplicate recovery |
| 4 | Authentication, full-snapshot distribution, ACK/NACK/resync, ordering and backpressure |
| 5 | OpenFeature mapping, atomic snapshot, LKG persistence, state/reconnect behavior |
| 6 | Cross-component failure drills and recovery evidence |
| 7 | Telemetry completeness and SLI calculation validation |
| 8 | Multi-replica, readiness, draining, rolling update, and network policy tests |
| 9 | Representative capacity/performance experiments and final evidence index |

## Completion rule

A test result is a release claim only when the code, command, input, expected result, actual result, environment, and limitation are reproducible from its evidence record. A green test without traceable inputs is useful feedback but not durable evidence.
