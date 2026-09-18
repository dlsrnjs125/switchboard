# Switchboard Glossary

This glossary is the canonical vocabulary for product requirements, APIs, storage, events, and SDKs. Later phases may refine representation details, but they must preserve these meanings or record the change in an ADR.

The words **MUST**, **SHOULD**, and **MAY** describe required, recommended, and optional behavior respectively.

## Scope and identity

| Term | Definition |
| --- | --- |
| Tenant | The top-level isolation and authorization boundary. Every customer-owned resource belongs to exactly one tenant. |
| TenantMember | A user membership in a tenant, including the role used for tenant-scoped authorization. |
| Project | A namespace for flags and client applications within a tenant. A project key is unique within its tenant. |
| Environment | An independently published runtime context within a project, such as development, staging, or production. |
| Principal | The authenticated actor performing an operation: a human tenant member or a service credential. |
| Role | A named permission set. The baseline roles are `TENANT_OWNER`, `PROJECT_MAINTAINER`, `DEVELOPER`, `VIEWER`, `AUDITOR`, and `SERVICE_CLIENT`. |
| ClientApplication | The stable identity of an application that consumes configuration for one authorized project/environment scope. |
| ServiceCredential | A revocable secret-bearing credential issued to a client application. It is distinct from the client application's identity and lifecycle. |

## Authoring and publication

| Term | Definition |
| --- | --- |
| FeatureFlag | The stable identity and key of a feature flag inside a project. Mutable configuration is held in revisions, not on this identity. |
| FlagRevision | A versioned configuration of a feature flag, including type, variants, default, targeting rules, and rollout seed. A published revision is immutable. |
| FlagVariant | A named, typed value that a revision may return. Every variant in one revision has the same value type. |
| TargetingRule | An ordered rule evaluated against an evaluation context. Its conditions are combined with logical AND. |
| RuleCondition | One attribute/operator/operand predicate inside a targeting rule. Nested Boolean expressions are outside the baseline. |
| RolloutAllocation | A weighted mapping from variants to basis points. A complete rollout totals exactly 10,000 basis points. |
| EnvironmentFlagState | The authoritative current selection for one feature flag in one environment, including whether evaluation is enabled and which published revision is active. |
| ConfigurationSnapshot | An immutable, full configuration image for one environment at one monotonically increasing snapshot version. |
| SnapshotEntry | The representation of one environment flag state embedded in a configuration snapshot. |
| Publish | The atomic operation that validates a candidate revision, updates environment state, creates a new snapshot, records an audit event, and stages an outbox event. |
| Rollback | A new publish operation that selects a previously published revision. It creates a new snapshot version; it never rewrites an earlier snapshot or revision. |
| Snapshot version | A strictly increasing environment-local sequence used to order configuration snapshots. It is not a revision number. |
| Schema version | The version of the serialized snapshot contract. It determines whether a consumer can decode the payload. |
| Checksum | A digest of the canonical snapshot payload used to verify integrity before application. |

## Revision vocabulary

| Term | Definition |
| --- | --- |
| Draft | An editable flag revision that has not been published. |
| Published | An immutable revision that has been successfully selected by at least one publish operation. |
| Superseded | An immutable published revision that has been replaced by another revision in a particular environment's history. Currentness remains authoritative in `EnvironmentFlagState`; superseded is contextual rather than permission to mutate the revision. |
| Archived | A feature flag identity that is no longer available for new authoring or publication. Its key remains reserved and history remains readable. |

## Evaluation and distribution

| Term | Definition |
| --- | --- |
| EvaluationContext | The attributes supplied to local evaluation. It includes a stable targeting key when deterministic rollout is required. |
| Targeting key | The stable subject identifier used as an input to deterministic bucketing. It is not a tenant or flag key. |
| Rollout seed | Revision-controlled input that deliberately changes deterministic rollout assignment when changed in a new revision. |
| Evaluation result | The selected typed value and variant plus reason and error metadata defined by the evaluation contract. |
| Distribution plane | The read-optimized boundary that authenticates SDK clients and delivers already-published snapshots. It does not author configuration. |
| Local evaluation | Evaluation performed inside the consuming application from its active in-memory snapshot, without a request-time network call. |
| Last Known Good (LKG) | The most recent snapshot that passed compatibility and checksum validation and was atomically applied. It may be persisted for restart bootstrap. |
| Stale | A condition in which the SDK can continue evaluating from LKG but has not confirmed a fresh snapshot within the configured threshold. |
| ACK | A client acknowledgement that a snapshot version was received and applied successfully. |
| NACK | A client response that a delivered snapshot could not be applied, including a machine-readable reason. |
| RESYNC | A request for the current full snapshot after a gap, mismatch, or otherwise unrecoverable incremental state. |

## Reliability and traceability

| Term | Definition |
| --- | --- |
| AuditEvent | An append-only business record describing who changed what, where, and when. It supports historical reconstruction and compliance review. |
| OutboxEvent | A transactionally stored delivery intent produced with the state change and later published at least once. Consumers deduplicate it by event identity. |
| Source of truth | PostgreSQL's committed management and snapshot state. Kafka notifications and SDK caches are derived delivery mechanisms, not authorities. |

## Required distinctions

- `FeatureFlag` identifies a flag; `FlagRevision` contains its versioned behavior.
- `EnvironmentFlagState` identifies what is current; `ConfigurationSnapshot` is the immutable environment-wide artifact distributed to clients.
- A flag revision number orders revisions of one flag; a snapshot version orders publications for one environment.
- `ClientApplication` is an application identity; `ServiceCredential` is a revocable means of authenticating that identity.
- `AuditEvent` explains a committed business change; `OutboxEvent` reliably requests downstream delivery of that change.
