# TRB-007 — Ambiguous PostgreSQL Publish Commit

## Context

A publish transaction atomically advances the environment version and writes the immutable Snapshot, audit event, and outbox intent. The client can lose its response after PostgreSQL has committed.

## Symptom

The caller observes an exception or timeout and cannot tell whether the transaction rolled back before commit or committed successfully before the response was lost.

## Expected vs Actual

- Expected: recovery determines the authoritative outcome without blindly repeating the operation or reusing a version.
- Actual: transport failure alone gives no trustworthy commit result; an unconditional retry can create another publication.

## Reproduction

Register a transaction callback that drops the application response immediately after a successful server commit. Compare it with a dependency cut that aborts the transaction before commit.

## Impact

Blind retry can create duplicate business intent, a higher Snapshot version, and misleading audit/outbox history. Assuming failure can leave a committed publication unacknowledged by the operator.

## Initial Hypothesis

An application exception was initially treated as sufficient evidence that publication failed.

## Evidence

`PublicationIntegrationTest` injects deterministic pre-commit failure and post-commit response loss. `EV-P06-REL-001` records that post-commit reconciliation finds one complete atomic record set, a blind retry is avoided, and the next intentional write uses version 2.

## Root Cause

The transaction outcome and delivery of the application response are separate events. Once the commit reaches PostgreSQL, the client-side exception cannot roll it back.

## Fix

Re-read the tenant-qualified environment current version and verify Snapshot ID/checksum, audit correlation, and outbox event as one authoritative set. Retry only after reconciliation proves the original transaction did not commit.

## Verification

Run `./gradlew :services:control-plane:test --tests '*PublicationIntegrationTest*'` and the Phase 6 reliability drill. Pre-commit failure must leave every publication table unchanged; post-commit response loss must expose exactly one complete publication.

## Trade-off

Recovery requires a stable correlation/idempotency strategy and an extra authoritative read. This is safer than pretending a network outcome proves a database outcome.

## Prevention

Document ambiguous-commit behavior for every externally invoked transaction. Do not map all exceptions to rollback, and never retry a publish until authoritative state is reconciled.

## Related ADR / PR / Commit

- ADR-005, ADR-006, ADR-007
- PR #12
- `4136a5d977e79c2b2439320b27c5e7fe9065add5`
- `EV-P06-REL-001`

## Blog Candidate Summary

Why a lost HTTP response does not mean a PostgreSQL transaction failed, and how to reconcile safely.
