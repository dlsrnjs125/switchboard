# Phase 2 — Deterministic Evaluation Engine

## Outcome

Phase 2 implements the framework-independent local evaluation boundary defined by ADR-001, ADR-003, and ADR-008. The module evaluates an immutable supplied flag model only; it performs no network, database, broker, framework, or OpenFeature operation.

## Implemented scope

- Typed `BOOLEAN`, `STRING`, `NUMBER`, and `OBJECT` flag values.
- Evaluation context with a distinct stable `targetingKey` and arbitrary attributes.
- Disabled and default resolution paths.
- Ascending rule priority with logical-AND conditions.
- All Snapshot Schema v1 operators: equality, membership, numeric comparison, existence, and string matching.
- Fixed-variant and weighted rollout rule results.
- Explicit resolution reason, variant, error code, error message, and metadata.
- Default-variant fallback for invalid context and missing rollout targeting keys.
- SHA-256 rollout using unsigned first-eight-byte modulo and u32be length-prefixed UTF-8 inputs.
- Direct verification against `contracts/test-vectors/sha256-rollout-v1.json`.
- Runtime dependency Gate requiring an empty production `runtimeClasspath`.
- JMH baseline for static default, targeting, and percentage rollout paths.

## Evaluation order

1. Reject a requested type that differs from the flag's declared value type.
2. Return the default variant with `DISABLED` when the flag is disabled.
3. Evaluate rules in ascending priority order.
4. Combine every condition in one rule with logical AND.
5. Resolve a fixed variant or calculate a deterministic rollout bucket for the first matching rule.
6. Return the default variant with `DEFAULT` when no rule matches.

Malformed immutable flag models are rejected at construction. Runtime context type errors and missing rollout targeting keys return the default variant with `ERROR` plus explicit error metadata; they do not silently enter a rollout.

## Deterministic rollout

The preimage concatenates these UTF-8 fields in order, each preceded by its unsigned 32-bit big-endian byte length:

1. `targetingKey`
2. `flagKey`
3. `rolloutSeed`

SHA-256 is applied to the preimage. The first eight digest bytes are interpreted as an unsigned big-endian integer and reduced modulo 10,000. Allocation ranges are half-open, so a 50/50 split maps bucket `4,999` to the first allocation and `5,000` to the second.

## Verification

```bash
./gradlew :libs:evaluation-core:test :libs:evaluation-core:verifyRuntimeIsolation
./gradlew :libs:evaluation-core:jmh
./gradlew clean build :contracts:check
```

The unit suite covers all 13 operators, four value types, disabled/default/error paths, rule ordering, exact allocation boundaries, model rejection, process-instance determinism, and all canonical golden vectors.

## Deferred boundaries

- Snapshot JSON parsing and atomic active-snapshot replacement belong to publication/distribution and SDK phases.
- OpenFeature adaptation and code-default behavior belong to Phase 5.
- Production latency targets, multi-JDK comparison, allocation profiling, concurrency scaling, and long-duration benchmarks require later performance evidence.
