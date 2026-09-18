# Domain Invariants

These invariants are normative across storage, APIs, events, distribution, and SDK behavior. Each identifier is stable and should be referenced by later tests and ADRs.

## Isolation and identity

| ID | Invariant |
| --- | --- |
| `INV-TEN-001` | Every customer-owned resource belongs to exactly one tenant. |
| `INV-TEN-002` | Tenant scope is derived from the authenticated principal and enforced in every repository query and mutation; a request field alone never grants scope. |
| `INV-TEN-003` | A project key is unique within a tenant. |
| `INV-FLG-001` | A feature flag key is unique within a project and remains reserved after archival. |
| `INV-FLG-002` | A feature flag, its revisions, and every environment that activates it belong to the same project and tenant. |

## Revision and rule integrity

| ID | Invariant |
| --- | --- |
| `INV-REV-001` | A published flag revision and its variants, rules, conditions, allocations, and rollout seed are immutable. A change requires a new revision. |
| `INV-REV-002` | Every variant in a revision has the revision's declared value type: `BOOLEAN`, `STRING`, `NUMBER`, or `OBJECT`. |
| `INV-REV-003` | Every revision has exactly one valid default variant. |
| `INV-RUL-001` | Rules have an unambiguous ascending evaluation order. Publication rejects duplicate or otherwise ambiguous priorities. |
| `INV-RUL-002` | All conditions inside one rule are combined with logical AND. Nested Boolean expressions are not part of this contract. |
| `INV-RUL-003` | A weighted rule result allocates exactly 10,000 basis points across variants belonging to the same revision. |
| `INV-RUL-004` | Deterministic rollout uses the specified stable hash inputs and maps to a bucket from 0 through 9,999. The same inputs and algorithm version produce the same bucket across runtimes. |

## Publication and snapshot integrity

| ID | Invariant |
| --- | --- |
| `INV-PUB-001` | Publish uses the caller's expected environment version. A mismatch fails with a conflict and commits no partial state. |
| `INV-PUB-002` | Updating `EnvironmentFlagState`, creating the `ConfigurationSnapshot` and entries, recording the `AuditEvent`, and creating the `OutboxEvent` occur in one database transaction. |
| `INV-PUB-003` | No external notification is emitted before the publication transaction commits. |
| `INV-SNP-001` | A configuration snapshot is immutable and represents the full published configuration of exactly one environment. |
| `INV-SNP-002` | Snapshot versions are unique and strictly increase within an environment. They never decrease or get reused. |
| `INV-SNP-003` | A snapshot entry references only published revision content valid for the snapshot's tenant, project, and environment. |
| `INV-SNP-004` | The checksum is calculated from the canonical serialized payload and is verified before a consumer applies that payload. |
| `INV-RBK-001` | Rollback is a new publish selecting a historical published revision and creating a new, higher snapshot version. It never mutates historical revisions or snapshots. |

## Delivery and SDK safety

| ID | Invariant |
| --- | --- |
| `INV-MSG-001` | Outbox delivery is at least once. Every event has a stable identity and every consumer is idempotent for that identity. |
| `INV-MSG-002` | Message delivery is a freshness signal, not the source of truth; consumers can recover the current snapshot from PostgreSQL-backed distribution state. |
| `INV-SDK-001` | An SDK validates schema compatibility and checksum before replacing its active snapshot. |
| `INV-SDK-002` | Snapshot replacement is atomic: one evaluation observes either the complete old snapshot or the complete new snapshot. |
| `INV-SDK-003` | An SDK never replaces a newer active snapshot with an older version. The same version with a different checksum is rejected as corruption or contract violation. |
| `INV-SDK-004` | LKG contains only a snapshot that was successfully validated and applied. Failure to apply an update leaves the previous LKG active. |
| `INV-SDK-005` | Runtime evaluation does not make a network or database call. |

## Traceability and credentials

| ID | Invariant |
| --- | --- |
| `INV-AUD-001` | An audit event is append-only and identifies the principal, tenant scope, operation, target, and time needed to reconstruct a management change. |
| `INV-CRD-001` | A service credential is scoped to one client application and its authorized project/environment boundary. Revocation prevents future authentication without deleting its audit history. |

## Enforcement order

Structural invariants are checked while authoring where possible and checked again at publication. Transactional invariants are enforced by database constraints and transaction boundaries. Consumer-safety invariants are enforced independently by Distribution and each SDK so a malformed or reordered delivery cannot corrupt LKG.
