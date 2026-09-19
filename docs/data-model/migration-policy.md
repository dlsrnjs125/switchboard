# PostgreSQL Migration and Retention Policy

## Purpose

This policy governs schema evolution beginning in Phase 1. It preserves the Phase 0 invariants while allowing rolling deployment and recovery. Phase 0E defines the rules; the first executable Flyway migration is intentionally deferred to Phase 1.

The migration target is the [ERD](erd.md) and [table specification](table-spec.md), with physical access paths from the [index strategy](index-strategy.md).

## Migration tool and layout

- Use Flyway versioned SQL migrations as the authoritative schema history.
- Store migrations under the Control Plane runtime module because PostgreSQL authoring/publication ownership belongs there.
- Use names such as `V001__create_tenant_and_project.sql`; never rename or modify an applied versioned migration.
- Use repeatable migrations only for replaceable views or functions whose replacement is idempotent. Tables, constraints, and durable triggers stay versioned.
- Flyway schema history is operational metadata, not a substitute for application audit events.
- CI provisions an empty PostgreSQL database, migrates from zero to head, and runs integration tests. A periodic compatibility job must also migrate from the most recent released schema snapshot.

## Forward-only default

Production schema history is forward-only. A failed release is normally repaired by a new migration and compatible application rollback, not by executing destructive down migrations.

Reasons:

- Published revisions, snapshots, and audit history cannot be reconstructed safely after destructive rollback.
- Old and new application versions may overlap during deployment.
- A down script can falsely suggest that irreversible data transformation is recoverable.

Before a destructive or irreversible migration, create and verify a backup/restore point and document the recovery procedure. `flyway repair` is limited to correcting metadata after the underlying cause is understood; it is never an automatic response to a failed migration.

## Expand, migrate, contract

Breaking schema changes require at least three independently deployable stages.

### 1. Expand

- Add nullable columns, new tables, new indexes, or dual-readable representations.
- Keep old readers and writers functional.
- Deploy code that can read both shapes and writes the migration target where necessary.

### 2. Migrate

- Backfill in bounded, restartable batches with progress telemetry.
- Avoid a single transaction over an unbounded table.
- Validate counts, nullability, tenant ownership, checksums, and referential integrity.
- Move reads to the new representation only after the backfill is complete.

### 3. Contract

- Stop writes to the old representation.
- Observe at least one release window with no old-version application instances.
- Add `NOT NULL` or stricter checks after validation.
- Drop old columns or tables in a later release with an explicit backup and recovery note.

Contract changes must follow the Phase 0D compatibility policy as well as this database policy.

## Transaction and lock discipline

- Keep DDL transactions small and deterministic.
- Set `lock_timeout` and `statement_timeout` explicitly in risky migrations so deploys fail rather than wait indefinitely.
- Do not combine a large backfill and structural DDL in one migration transaction.
- Add foreign keys and expensive checks as `NOT VALID`, backfill/repair existing data, then `VALIDATE CONSTRAINT` where PostgreSQL supports it.
- Create large-table indexes with `CREATE INDEX CONCURRENTLY` in a non-transactional Flyway migration. The file must contain only operations compatible with non-transactional execution.
- Adding a column with a volatile default, rewriting a large table, or changing a column type requires an impact plan and production-sized rehearsal.
- Publication and outbox tables must not be locked for an unbounded duration because that can halt management writes or delivery recovery.

## Naming and data-type rules

- Use lowercase `snake_case`, plural table names, and explicit constraint/index names.
- Use `uuid` for identities, `bigint` for monotonic revision/snapshot versions, `integer` for basis points and schema versions, and `timestamptz` for instants.
- Use `text` or bounded `varchar` plus named checks for lifecycle values; do not use PostgreSQL enum types in the baseline.
- Store money nowhere in the Phase 0 model. Floating point is forbidden for rollout weights; basis points are integers.
- Store variant numbers as JSON numbers but validate them through the Phase 0D type contract. Evaluation defines numeric semantics separately.
- Use `jsonb` only at the boundaries documented in the ERD. A migration adding JSONB must document ownership, validation, query pattern, and why relational columns are insufficient.
- Application-generated UUIDv7 avoids requiring database extensions and keeps time-correlated B-tree insertion behavior. Imported IDs must still be valid UUIDs.

## Tenant-safe migration rules

- New customer-owned tables include `tenant_id` from their first migration.
- Backfills derive tenant scope through an unambiguous parent join and abort on orphan or conflicting ownership.
- Composite tenant/project foreign keys are created before the table is exposed to application writes.
- A migration must never infer tenant from a client-provided public key without joining through the authoritative parent.
- RLS, if introduced later, is defense in depth. Its rollout requires application session-context design, policy tests, and an ADR or amendment; it does not replace scoped repository APIs.

## Publication and version migrations

The following columns are compatibility-critical:

- `environments.current_snapshot_version`
- `flag_revisions.revision_number`
- `configuration_snapshots.snapshot_version`
- `configuration_snapshots.schema_version`
- `configuration_snapshots.checksum`
- `outbox_events.id` and `event_version`

Migration rules:

1. Never reset, reuse, or renumber committed revision or snapshot versions.
2. Never rewrite a committed snapshot payload or checksum in place.
3. Snapshot Schema changes follow the Phase 0D versioning workflow and require migration/compatibility fixtures.
4. Rollback of product configuration creates a new snapshot version; it is not a database migration and never restores an old counter.
5. Backfills that create derived snapshots must use a separately approved procedure and emit audit/outbox records or explicitly document why no publication occurred.

## Immutability enforcement

Phase 1 migrations must install named trigger functions with test coverage:

- Reject update/delete of a `PUBLISHED` `flag_revisions` row.
- Reject insert/update/delete of variants, rules, conditions, or allocations whose parent revision is `PUBLISHED`.
- Reject update/delete of `configuration_snapshots` and `snapshot_entries` for application roles.
- Reject update/delete of `audit_events` for application roles.
- Allow `outbox_events` updates only to relay metadata columns.

Database privileges should express the same policy. Triggers protect against accidental privileged application SQL; privileges prevent ordinary application roles from attempting the mutation.

## Seed and reference data

- Migrations may seed closed reference data needed for a constraint, but tenant/customer data is never embedded in migrations.
- Role, lifecycle, operator, and value-type sets are checks maintained in versioned migrations and mirrored in application code/contracts.
- Seed operations must be idempotent only when the migration itself may be retried safely; applied versioned migrations are otherwise immutable.

## Retention and purge baseline

| Data | MVP retention | Purge rule |
| --- | --- | --- |
| Tenant/project/environment/flag/application identity | Indefinite, archived in place | No automatic hard delete |
| Published revisions and children | Indefinite | Never purge while any state, snapshot entry, or audit history references them |
| Configuration snapshots and entries | Indefinite in MVP | Future policy may archive artifacts, but versions and audit lineage remain immutable and non-reusable |
| Audit events | Indefinite in MVP | Any regulatory/export policy change requires an explicit retention decision and evidence |
| Active/revoked credentials | Indefinite metadata; raw secret never stored | Revoked rows remain for audit; hash removal requires a separate security policy |
| Pending or failed outbox events | Until successfully delivered and reconciled | Never purge |
| Published outbox events | Minimum 30 days, configurable upward | Purge only when `published_at` is set, referenced snapshot remains available, and no investigation hold applies |

Purge jobs operate in bounded batches, are observable, and never cascade from tenant or project deletion. Legal/incident holds override scheduled purge. Partitioning is deferred until volume evidence justifies it; retention rules do not require partitioning to be valid.

## Backup and recovery

Before a migration classified as high risk:

- record PostgreSQL version and migration checksum;
- verify a recent backup or snapshot and restore procedure;
- estimate lock/rewrite behavior with production-like row counts;
- identify the last compatible application version;
- document abort conditions and the forward-fix migration owner.

Recovery validation must confirm tenant ownership, environment counters, latest snapshot/checksum pairs, pending outbox rows, and audit continuity. A database restore does not authorize version reuse against clients that may have observed a newer snapshot; reconciliation is required.

## Review checklist

Every migration PR answers:

- [ ] Is it additive, migratory, or contract/destructive?
- [ ] Does it preserve old application compatibility during rollout?
- [ ] Are tenant and project scope present and constrained?
- [ ] Are all new foreign keys indexed or already covered?
- [ ] Can it rewrite or lock an unbounded table?
- [ ] Does it touch immutable revision, snapshot, audit, or outbox identity/payload?
- [ ] Is a backfill bounded, restartable, and observable?
- [ ] Are contract/schema versions affected?
- [ ] Are empty-database and upgrade-path tests included?
- [ ] Is rollback an application rollback, forward fix, or verified restore?
- [ ] Are retention and sensitive-data consequences documented?

## Phase 1 migration readiness

Phase 1 can start when its first migration implements the tables and constraints in `table-spec.md`, preserves the query shapes in `index-strategy.md`, and includes integration tests for:

- two-tenant isolation and composite FK rejection;
- archived key non-reuse;
- typed variants and valid references;
- allocation total and rule priority constraints;
- published revision immutability;
- environment-version conflict with no partial side effect;
- snapshot version monotonicity and immutability;
- raw credential absence;
- atomic state/snapshot/audit/outbox commit.
