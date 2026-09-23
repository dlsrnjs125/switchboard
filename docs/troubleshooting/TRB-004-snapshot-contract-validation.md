# TRB-004 — Snapshot Contract Validation Before Publication

## Context

The Control Plane compiles a canonical full Snapshot that becomes the immutable contract consumed independently by Distribution and SDK processes.

## Symptom

A payload could satisfy the authoring model yet still violate the published JSON Schema or cross-field Snapshot semantics. Checksum verification was implemented by consumers, but publication did not initially execute the same complete contract gate.

## Expected vs Actual

- Expected: only schema-valid, semantically consistent, RFC 8785-canonical, checksum-correct payloads can be committed as published Snapshots.
- Actual: compiler assumptions and persistence constraints were relied on without an executable publication-boundary validation step.

## Reproduction

Compile or construct a payload with a duplicate variant key, wrong typed value, missing operand, invalid variant reference, allocation total other than 10,000, or checksum that does not match the unsigned canonical payload.

## Impact

An invalid immutable Snapshot would force every downstream consumer to reject it, leaving applications stale while the database still described the publication as current.

## Initial Hypothesis

Valid draft persistence plus a deterministic compiler was assumed to imply a valid wire contract.

## Evidence

PR #4 added schema fixtures and executable contract tests. PR #9 added `SnapshotValidator` at the publication boundary and integration tests that reject semantic and checksum violations.

## Root Cause

The authoring aggregate, persisted relational model, and distributed Snapshot are different trust boundaries. Validation at one boundary does not prove the next representation.

## Fix

Validate the compiled payload against JSON Schema 2020-12, then check variant uniqueness, value types, rule references, operand rules, allocation totals, and RFC 8785/SHA-256 checksum before persistence completes.

## Verification

Run `./gradlew :contracts:check :services:control-plane:test`. Valid fixtures and publication paths pass; invalid schema, semantic, and checksum candidates fail before becoming current.

## Trade-off

Publication pays an additional parse/schema/semantic pass. This cost is measured separately in Phase 9 and is accepted to keep consumer-visible state safe.

## Prevention

Every generated cross-process artifact needs validation at the producer boundary and independently at each consumer boundary. Compiler ownership is not a substitute for contract validation.

## Related ADR / PR / Commit

- ADR-007, ADR-012
- PR #4, PR #9
- `bd98e10b963a003f96380be024781576ee13c42b`
- `2342a663653f5f4892ae1ba3e5c4c6b860331733`
- `EV-P03-PUB-001`

## Blog Candidate Summary

Why a deterministic Snapshot compiler still needs an executable schema and semantic gate before commit.
