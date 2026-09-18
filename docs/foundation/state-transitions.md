# State Transitions

This document defines allowed lifecycle transitions and the event that makes each transition authoritative. Published artifacts are immutable; transition history is preserved rather than overwritten.

## Feature flag identity

| From | Event | To | Preconditions and effects |
| --- | --- | --- | --- |
| — | Create flag | `ACTIVE` | The key is not currently or historically assigned in the project. |
| `ACTIVE` | Archive flag | `ARCHIVED` | New authoring and publication stop; history and the key reservation remain. |

`ARCHIVED -> ACTIVE` is not part of the baseline. A new flag cannot reuse an archived key.

## Flag revision

| From | Event | To | Preconditions and effects |
| --- | --- | --- | --- |
| — | Create revision | `DRAFT` | Starts editable and belongs to one feature flag. |
| `DRAFT` | Edit | `DRAFT` | Structural validation applies; no published state changes. |
| `DRAFT` | Publish successfully | `PUBLISHED` | Full validation passes and the publication transaction commits. The revision becomes immutable. |
| `PUBLISHED` | Publish another revision in an environment | `SUPERSEDED` in that environment's history | The old revision remains immutable and addressable for audit and rollback. |
| `SUPERSEDED` | Roll back in an environment | `PUBLISHED`/current in that environment | The historical revision is selected without editing it; a new snapshot version is created. |

`SUPERSEDED` is a contextual label in an environment's publication history. The authoritative current pointer is `EnvironmentFlagState`; the revision itself never becomes editable again. A revision may be current in one environment and superseded in another.

## Environment flag state

| From | Event | To | Effect |
| --- | --- | --- | --- |
| — | First publish | `ENABLED(revision N)` or `DISABLED(revision N)` | Creates the first authoritative selection and a new full snapshot. |
| `ENABLED(revision N)` | Publish revision M | `ENABLED(revision M)` | Advances the environment version and creates a new snapshot. |
| `DISABLED(revision N)` | Enable | `ENABLED(revision N)` | Creates a new snapshot; the revision is unchanged. |
| `ENABLED(revision N)` | Disable | `DISABLED(revision N)` | Creates a new snapshot with explicit disabled evaluation behavior. |
| Any existing state | Roll back to revision K | `ENABLED(revision K)` or `DISABLED(revision K)` | Uses the same publication path and creates a higher snapshot version. |

Every transition uses optimistic concurrency through the expected environment version. A conflict causes no transition and no audit, snapshot, or outbox side effect.

## Configuration snapshot

A snapshot has no editable domain state. The following labels describe processing observations, not mutations of the snapshot record.

| Observation | Meaning | Next observation |
| --- | --- | --- |
| `COMMITTED` | The immutable snapshot and its publication records committed atomically. | `ANNOUNCED` after outbox relay; retries are allowed. |
| `ANNOUNCED` | At least one notification has been emitted. Duplicate notifications are valid. | `APPLIED` per client after successful validation and atomic swap. |
| `APPLIED` | A particular SDK acknowledged successful application. | Remains historical when a newer snapshot is applied. |
| `REJECTED` | A particular consumer rejected the payload or contract. | Consumer retains LKG and may request `RESYNC`. |

These observations are not a single global status: different clients can observe the same snapshot differently.

## SDK provider lifecycle

| From | Event | To | Evaluation behavior |
| --- | --- | --- | --- |
| `NOT_READY` | Valid bootstrap or full snapshot applied | `READY` | Evaluate from the active snapshot. |
| `READY` | Freshness threshold exceeded or stream disconnected | `STALE` | Continue evaluation from LKG and expose degraded telemetry. |
| `STALE` | New valid snapshot applied | `READY` | Atomically replace LKG and resume fresh state. |
| `READY` or `STALE` | Invalid/incompatible update | Same prior state | NACK the update and retain LKG. |
| Any | Shutdown | `CLOSED` | Stop transport and reject new lifecycle operations according to the SDK contract. |

If no valid bootstrap or LKG exists, the provider remains `NOT_READY`; later SDK contracts define the precise OpenFeature error mapping.

## Service credential

| From | Event | To | Effect |
| --- | --- | --- | --- |
| — | Issue | `ACTIVE` | Raw secret is returned only through the credential issuance contract; stored authentication material is non-reversible. |
| `ACTIVE` | Revoke | `REVOKED` | Future authentication fails; audit history remains. |
| `ACTIVE` | Rotate | `REVOKED` plus new `ACTIVE` credential | Rotation creates a new credential identity and revokes the old credential according to policy. |

`REVOKED -> ACTIVE` is forbidden.

## Outbox event

| From | Event | To | Effect |
| --- | --- | --- | --- |
| — | Publication commit | `PENDING` | Delivery intent exists in the same transaction as the snapshot. |
| `PENDING` | Relay failure | `PENDING` | Record retry metadata; authoritative publication remains committed. |
| `PENDING` | Broker publish confirmed | `PUBLISHED` | Mark delivery completion. Duplicate downstream delivery remains possible. |

Consumers must treat the event identity idempotently and reconcile against the current snapshot version when delivery is duplicated, delayed, or reordered.
