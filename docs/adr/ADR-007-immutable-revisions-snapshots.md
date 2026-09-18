# ADR-007: Keep published revisions and snapshots immutable

- Status: Accepted
- Date: 2026-09-18
- Foundation: FlagRevision, ConfigurationSnapshot, Rollback, `INV-REV-001`, `INV-SNP-001`–`INV-SNP-004`, `INV-RBK-001`

## Context

In-place edits destroy reproducibility, make audit ambiguous, and can change the meaning of data already cached by clients. Rollback must not rewrite history.

## Decision

A draft may be edited until publication. A successfully published revision and every committed snapshot are immutable. All changes, including rollback and enable/disable, create a new publication with a strictly higher environment snapshot version.

## Alternatives considered

- **Mutable active configuration** — rejected because history and checksums lose stable meaning.
- **Copy-on-write snapshots but mutable revisions** — rejected because old snapshots could reference changing content.
- **Restore an old snapshot version during rollback** — rejected because versions would regress.

## Trade-offs

- **Benefit:** deterministic history, safe caching, auditability, and simple concurrency semantics.
- **Cost:** additional rows, retention policy, and explicit archive behavior.

## Consequences

- `FlagRevision` lifecycle ends at immutable `PUBLISHED`; `SUPERSEDED` is environment-relative history.
- Publish uses expected environment version and commits no partial state on conflict.
- Storage and contracts expose revision identity separately from snapshot version.

## Revisit conditions

- Never for published artifacts; only retention and archival representation may change without weakening immutability.
