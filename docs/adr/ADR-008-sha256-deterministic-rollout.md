# ADR-008: Use SHA-256 for deterministic rollout

- Status: Accepted
- Date: 2026-09-18
- Foundation: Targeting key, Rollout seed, `INV-RUL-003`, `INV-RUL-004`

## Context

Percentage rollout must assign the same subject consistently across processes, restarts, and language runtimes. Platform-specific hash functions are unstable or incompatible.

## Decision

Rollout hashes a canonical, unambiguous encoding of `targetingKey`, `flagKey`, and `rolloutSeed` with SHA-256, converts the specified digest bytes to an unsigned value, and maps it to buckets `0..9,999`. Phase 0D fixes byte encoding and golden vectors. Allocations total exactly 10,000 basis points.

## Alternatives considered

- **Runtime `hashCode`** — rejected because algorithms differ and may change.
- **Random assignment per evaluation** — rejected because assignments are not sticky.
- **Persist every assignment** — rejected because it adds synchronous state to evaluation.
- **MurmurHash** — viable and faster, but SHA-256 is ubiquitous and adequate until measured otherwise.

## Trade-offs

- **Benefit:** portable, stable, stateless assignment.
- **Cost:** canonicalization is contract-critical and rollout seed changes can reassign subjects.

## Consequences

- Implementations must pass shared golden vectors.
- Missing targeting keys cannot silently enter percentage rollout.
- Hash inputs and algorithm version are explicit snapshot contract fields or fixed schema semantics.

## Revisit conditions

- Benchmarks show hashing is a material bottleneck, with a migration plan that preserves existing assignments.
