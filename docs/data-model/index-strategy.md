# PostgreSQL Index Strategy

## Principles

Indexes are derived from known command and query paths rather than added speculatively.

Table ownership and constraints are defined in the [table specification](table-spec.md); schema rollout rules are defined in the [migration policy](migration-policy.md).

1. Tenant scope is the leading column for administrative lookups unless a globally unique internal ID is used only after authorization has established scope.
2. Unique constraints enforce business invariants and provide their own B-tree indexes.
3. Every foreign key used for parent deletion checks or joins receives a supporting index.
4. List APIs use stable keyset pagination: ordered timestamp plus UUID tie-breaker. Offset pagination is not the baseline for unbounded tables.
5. Partial indexes serve active or pending subsets only when that predicate is present in the query.
6. JSONB receives no baseline GIN index. Current queries do not filter by arbitrary variant, condition, audit, or event payload properties.
7. Indexes are reviewed with `EXPLAIN (ANALYZE, BUFFERS)` against representative data before Phase 1 is declared complete.

## Required uniqueness indexes

The following are constraints first and indexes second.

| Table | Key | Invariant or query |
| --- | --- | --- |
| `tenants` | `(tenant_key)` | Globally stable tenant key |
| `projects` | `(tenant_id, project_key)` | Project key unique within tenant |
| `environments` | `(tenant_id, project_id, environment_key)` | Environment key unique within project |
| `feature_flags` | `(tenant_id, project_id, flag_key)` | Flag key unique and permanently reserved within project |
| `flag_revisions` | `(feature_flag_id, revision_number)` | Monotonic revision identity per flag |
| `flag_variants` | `(revision_id, variant_key)` | Variant key unique within revision |
| `targeting_rules` | `(revision_id, priority)` | Unambiguous rule order |
| `rule_conditions` | `(rule_id, condition_order)` | Stable condition serialization order |
| `rollout_allocations` | `(rule_id, variant_key)` | One allocation per rule and variant |
| `environment_flag_states` | `(environment_id, feature_flag_id)` | One current state per flag and environment |
| `configuration_snapshots` | `(environment_id, snapshot_version)` | Snapshot version unique within environment |
| `snapshot_entries` | `(snapshot_id, feature_flag_id)` | One entry per flag in a snapshot |
| `client_applications` | `(tenant_id, project_id, client_application_key)` | Stable application identity within project |

Composite alternate keys required by tenant-safe foreign keys also create indexes, for example `(tenant_id, id)` and `(tenant_id, project_id, id)`. Migration review must avoid creating duplicate indexes whose leading columns and uniqueness are already equivalent.

## Administrative query indexes

### Project and environment lookup

```sql
-- GET project by scoped public key
UNIQUE (tenant_id, project_key)

-- GET environment by scoped public key
UNIQUE (tenant_id, project_id, environment_key)
```

The unique indexes already cover point lookups. Project and environment lists are expected to be small in the MVP. Add `(tenant_id, created_at, id)` or `(tenant_id, project_id, created_at, id)` only after list-query evidence shows the need.

### Feature flag lookup and active list

```sql
UNIQUE (tenant_id, project_id, flag_key)

CREATE INDEX ix_feature_flags_active_list
    ON feature_flags (tenant_id, project_id, created_at DESC, id DESC)
    WHERE lifecycle_status = 'ACTIVE';
```

The first supports exact API resolution. The partial index supports an active-flag keyset page without weakening permanent key reservation.

### Revision history

```sql
UNIQUE (feature_flag_id, revision_number)

CREATE INDEX ix_flag_revisions_history
    ON flag_revisions (tenant_id, project_id, feature_flag_id, revision_number DESC);
```

The history index supports tenant-qualified newest-first revision reads. Do not add a partial unique “one draft per flag” index until the authoring product rule explicitly limits concurrent drafts.

### Revision children

```sql
CREATE INDEX ix_targeting_rules_revision
    ON targeting_rules (revision_id, priority);

CREATE INDEX ix_rule_conditions_rule
    ON rule_conditions (rule_id, condition_order);

CREATE INDEX ix_rollout_allocations_revision_rule
    ON rollout_allocations (revision_id, rule_id);
```

The first two may already be provided by their unique constraints. The migration must create only the missing physical indexes.

## Publication query indexes

### Current environment state and snapshot compilation

```sql
-- Primary key is sufficient for one flag.
PRIMARY KEY (environment_id, feature_flag_id)

-- Full state scan used by the snapshot compiler.
CREATE INDEX ix_environment_flag_states_compile
    ON environment_flag_states (tenant_id, project_id, environment_id, feature_flag_id);
```

The compiler reads all current states for one authorized environment, joins revisions and children, and produces one immutable full snapshot. `environment_id` remains before `feature_flag_id` because the environment-wide read is the dominant path.

### Current and historical snapshot reads

```sql
UNIQUE (environment_id, snapshot_version)

CREATE INDEX ix_configuration_snapshots_current
    ON configuration_snapshots
       (tenant_id, project_id, environment_id, snapshot_version DESC)
    INCLUDE (id, schema_version, checksum, generated_at);
```

This supports Distribution bootstrap and Control Plane current-summary lookup. The large `payload` is intentionally not included in the index; after selecting the single row, PostgreSQL fetches it from the table.

### Snapshot lineage

```sql
PRIMARY KEY (snapshot_id, feature_flag_id)

CREATE INDEX ix_snapshot_entries_revision_history
    ON snapshot_entries (feature_flag_id, revision_id, snapshot_id);
```

The secondary index answers where a revision appeared without scanning all snapshot entries. It is not on the request-time evaluation path.

## Distribution and credential indexes

### Client application lookup

```sql
UNIQUE (tenant_id, project_id, client_application_key)

CREATE INDEX ix_client_applications_environment
    ON client_applications (tenant_id, project_id, environment_id, id)
    WHERE lifecycle_status = 'ACTIVE';
```

### Credential authentication

Credential tokens carry the public credential UUID separately from the secret. Authentication first performs a PK lookup and then verifies the slow password hash.

```sql
PRIMARY KEY (id)

CREATE INDEX ix_service_credentials_client_application
    ON service_credentials (tenant_id, project_id, client_application_id);
```

The non-partial index supports the composite FK for both active and revoked credentials. A separate active-only index would overlap this access path and is not part of the baseline without list-query evidence. Never index `secret_hash`, and never search credentials by raw secret or prefix. The prefix is display-only.

## Audit indexes

```sql
CREATE INDEX ix_audit_events_tenant_time
    ON audit_events (tenant_id, created_at DESC, id DESC);

CREATE INDEX ix_audit_events_resource_time
    ON audit_events
       (tenant_id, resource_type, resource_id, created_at DESC, id DESC);

CREATE INDEX ix_audit_events_correlation
    ON audit_events (tenant_id, correlation_id);
```

These cover the Phase 0D tenant audit list, resource history, and request-trace reconstruction. `details` receives no GIN index. If action/type filters become frequent, production evidence must justify an additional index with tenant and time as leading dimensions.

## Outbox relay indexes

```sql
CREATE INDEX ix_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_events_snapshot
    ON outbox_events (tenant_id, snapshot_id);
```

Relay workers claim work with a bounded query:

```sql
SELECT id
FROM outbox_events
WHERE published_at IS NULL
  AND next_attempt_at <= clock_timestamp()
ORDER BY next_attempt_at, created_at, id
FOR UPDATE SKIP LOCKED
LIMIT :batch_size;
```

The partial index stays small after delivered rows age out. A separate index on `published_at` is unnecessary for the relay and should be introduced only for purge-job evidence.

## Indexes intentionally omitted

- No JSONB GIN indexes: arbitrary payload search is outside the baseline.
- No index on boolean `enabled`: low selectivity and environment-leading queries make it ineffective.
- No checksum-only index: checksum is verified after selecting by environment and version.
- No index on credential prefix or hash: neither is an authentication lookup key.
- No indexes for metrics or SDK acknowledgements: those are not durable relational entities in the Phase 0E baseline.
- No redundant single-column tenant indexes when a tenant-leading composite index already serves the query.

## Cross-tenant query review

Every repository SQL statement must meet all applicable checks:

1. Tenant is derived from the authenticated principal, never trusted from the payload alone.
2. The predicate contains `tenant_id = :authorized_tenant_id` for customer-owned tables.
3. Project/environment keys are resolved under that tenant rather than globally.
4. Updates include tenant scope in the `WHERE` clause even when the UUID is globally unique.
5. Publication locks or CAS-updates the tenant-qualified environment row.
6. A missing scoped resource returns the chosen non-disclosing 404/403 policy consistently.

Phase 1 integration tests must run the same UUID and public-key access patterns against two tenants and prove that read, update, archive, publish, credential, audit, and outbox paths cannot cross the boundary.

## Evidence required before adding an index

Record the following in a migration or evidence document:

- exact query and expected cardinality;
- representative row counts and tenant distribution;
- `EXPLAIN (ANALYZE, BUFFERS)` before and after;
- write/storage cost and overlapping indexes;
- whether a constraint already supplies the index;
- rollback or removal plan.

This keeps the Phase 0E design implementable without claiming unmeasured performance.
