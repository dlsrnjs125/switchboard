# Domain Boundaries

This document fixes responsibility and dependency boundaries before API, database, and event contracts are designed. PostgreSQL is the source of truth; delivery transports and client caches are derived views.

## Bounded contexts

| Boundary | Owns | Responsible for | Must not do |
| --- | --- | --- | --- |
| Identity and Access | `Tenant`, `TenantMember`, roles | Resolve the authenticated principal, derive tenant scope server-side, and authorize actions | Trust a client-supplied tenant identifier as proof of access |
| Configuration Authoring | `Project`, `Environment`, `FeatureFlag`, draft `FlagRevision`, `FlagVariant`, `TargetingRule`, `RuleCondition`, `RolloutAllocation` | Manage editable intent and validate revision structure | Mutate a published revision or distribute uncommitted state |
| Publication | `EnvironmentFlagState`, `ConfigurationSnapshot`, `SnapshotEntry` | Perform optimistic concurrency checks, compile a full snapshot, and commit state, audit, and outbox records atomically | Publish an external event before the database transaction commits |
| Distribution | `ClientApplication`, `ServiceCredential`, delivery sessions and acknowledgements | Authenticate runtime clients and deliver the latest authorized full snapshot | Author flags, decide publication, or become the source of truth |
| Evaluation | Evaluation algorithm and result contract | Evaluate a supplied snapshot deterministically and locally | Query PostgreSQL, Kafka, the control plane, or any network service during evaluation |
| Audit and Observability | `AuditEvent`, operational telemetry conventions | Preserve business traceability and expose health, freshness, latency, error, and cardinality-safe metrics | Treat logs or metrics as a replacement for the audit record |
| Reliable Messaging | `OutboxEvent` and relay behavior | Bridge committed publication state to at-least-once notifications with idempotent identity | Infer authoritative state solely from message arrival order |

## Entity ownership

| Entity | Write owner | Primary readers |
| --- | --- | --- |
| Tenant, TenantMember | Identity and Access | All control-plane authorization paths |
| Project, Environment | Configuration Authoring | Publication, Distribution, Audit |
| FeatureFlag, draft FlagRevision and children | Configuration Authoring | Publication, Audit |
| Published FlagRevision and children | Publication freezes the authored aggregate | Snapshot compiler, Audit, historical reads |
| EnvironmentFlagState | Publication | Control-plane reads, snapshot compiler |
| ConfigurationSnapshot, SnapshotEntry | Publication | Distribution, SDK bootstrap/recovery, Audit |
| ClientApplication, ServiceCredential | Distribution administration | Distribution authentication, Audit |
| AuditEvent | Audit and Observability | Operators, auditors, administrative APIs |
| OutboxEvent | Reliable Messaging, created by Publication | Outbox relay and idempotent downstream consumers |

“Write owner” identifies the only boundary allowed to change authoritative state. It does not prevent another boundary from maintaining a disposable read model.

## Dependency direction

1. Identity and Access is consulted by control-plane operations before tenant-scoped data access.
2. Configuration Authoring produces valid drafts but does not activate them.
3. Publication consumes an authorized draft or historical published revision and writes the new authoritative environment state and snapshot.
4. Reliable Messaging announces committed snapshot availability.
5. Distribution reads published snapshots and sends them to authorized SDK clients.
6. SDK adapters manage transport and lifecycle, while the Evaluation boundary evaluates only the supplied immutable model.

The evaluation core is the innermost dependency: it has no framework, database, broker, transport, or OpenFeature dependency. The Java SDK may depend on it; the reverse dependency is forbidden.

## Isolation rules

- An actor describes a product or operational persona; authorization is performed against an authenticated principal, its assigned role and permissions, and the target scope.
- `Platform Admin` is outside tenant RBAC, and `Operator` is an operational persona rather than an implicit privileged role.
- Every tenant-owned query and mutation MUST include the tenant scope derived from the authenticated principal.
- A project, environment, flag, revision, client application, credential, snapshot, audit event, or outbox event MUST NOT be associated across tenants.
- Project-scoped identities MUST be resolved through their tenant-qualified parent, not by globally trusting a public key.
- Distribution credentials MUST be limited to their authorized project/environment scope.
- Cross-tenant access failures MUST not disclose whether the target resource exists.

## Control plane and data plane

The control plane covers identity, authoring, publication, audit, and credential administration. The data plane covers snapshot distribution, SDK lifecycle, and local evaluation. A control-plane outage may stop management changes, but an SDK with LKG continues local evaluation. A distribution outage may delay freshness, but it must not cause request-time evaluation to fall back to the control plane.

## Boundary contract

Later API, database, protobuf, and event designs MUST use the terms in the glossary and preserve these ownership rules. A deviation requires an explicit ADR that updates the affected foundation documents.
