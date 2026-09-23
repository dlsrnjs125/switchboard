# TRB-003 — Immutable Evaluation Inputs

## Context

The Evaluation Core is a pure Java library that must return deterministic results from an immutable Snapshot and an evaluation context supplied by application code.

## Symptom

Top-level maps were copied, but nested maps and collections in object flag values and evaluation attributes could still be mutated by the caller after construction. Non-string nested map keys were also converted with `toString()`, creating ambiguous JSON semantics.

## Expected vs Actual

- Expected: constructed evaluation values and contexts are deeply immutable and accept only JSON-compatible shapes.
- Actual: caller-owned nested collections remained aliased, and unsupported key types could be silently normalized.

## Reproduction

Construct an object flag or evaluation context from a nested mutable map/list, mutate the original child after construction, and evaluate again. Also supply a nested map with a non-string key.

## Impact

The same nominal Snapshot and context could yield different results over time, breaking deterministic evaluation, thread safety, cache assumptions, and Golden Vector reproducibility.

## Initial Hypothesis

An unmodifiable top-level map was assumed to make the complete object graph immutable.

## Evidence

`ImmutableInputTest` mutates source objects after construction and verifies stored values remain unchanged. It also verifies unsupported values and non-string object keys are rejected.

## Root Cause

Immutability was shallow and the JSON boundary was not centralized. Different call sites applied different normalization rules.

## Fix

Introduce `ImmutableJson` to recursively copy maps and collections, normalize numbers to `BigDecimal`, require string object keys, reject unsupported types, and reuse the same boundary for flag values and evaluation attributes.

## Verification

Run `./gradlew :libs:evaluation-core:test`. `ImmutableInputTest`, deterministic rollout tests, typed evaluation tests, and Phase 2 Golden Vector checks must pass.

## Trade-off

Construction performs a recursive copy and numeric normalization. Evaluation remains allocation-conscious because the copy occurs at input construction rather than on every rule operation.

## Prevention

Treat all caller-supplied structured values as mutable. Centralize recursive normalization and test mutation of every nested collection type.

## Related ADR / PR / Commit

- ADR-001, ADR-008
- PR #8
- `565a52e500f15aa844eed423352248e4672ec155`
- `EV-P02-EVL-001`

## Blog Candidate Summary

How a shallow immutable wrapper can quietly destroy deterministic feature-flag evaluation.
