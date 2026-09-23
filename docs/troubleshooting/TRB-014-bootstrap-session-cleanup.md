# TRB-014 — Bootstrap Session Cleanup Ownership

## Context

Distribution registers a gRPC `ClientSession` before loading the authoritative initial Snapshot so a publication that overlaps bootstrap cannot be missed. A non-writable registered session may therefore hold one coalesced pending Snapshot while the database lookup is still running.

## Symptom

When the initial Snapshot lookup failed after an overlapping broadcast, the stream returned `UNAVAILABLE` and disappeared from the registry, but `switchboard.distribution.snapshot.pending` and `switchboard.distribution.snapshot.pending.bytes` could remain positive.

## Expected vs Actual

- Expected: every cancellation, bootstrap failure, integrity failure, credential revocation, and server shutdown closes the session and subtracts pending count/bytes exactly once.
- Actual: bootstrap exception handlers removed the registry entry and ended the observer directly without closing the `ClientSession`; only close, revoke, or client cancellation cleared its pending slot.

## Reproduction

Register a non-writable observer, invoke `SessionRegistry.onSnapshotApplied` while `SnapshotCoordinator.current` is executing, then make that lookup throw. Before the fix, registry size and connected-session gauge reached zero while pending count and bytes remained non-zero.

## Impact

Repeated failed subscriptions could accumulate ghost backpressure telemetry after every affected stream had ended. Operators following the backpressure runbook could diagnose a nonexistent slow-client incident or miss the real bootstrap dependency failure.

## Initial Hypothesis

The server-side `responseObserver.onError` path was assumed to trigger the client cancellation handler and clean the session indirectly.

## Evidence

Code-path review showed that gRPC server termination does not make the registered cancel callback the owner of cleanup. `SnapshotDistributionGrpcServiceTest.bootstrapFailureTerminatesPendingSessionAndClearsTelemetry` deterministically reproduces register, overlapping pending broadcast, lookup exception, and final gauge cleanup.

## Root Cause

Session lifecycle ownership was split across `ClientSession`, `SessionRegistry`, and service exception handlers. `SessionRegistry.unregister` removed external membership but did not terminate internal session state, so one exit path skipped the pending-slot accounting invariant.

## Fix

Add one idempotent `ClientSession.terminate` transition that rejects later offers and clears pending telemetry once. Make cancellation, close, revoke, and explicit registry unregister use that transition while keeping observer responses separate, so existing `UNAVAILABLE` and integrity-failure response semantics remain intact.

## Verification

Run `./gradlew :services:distribution:test --tests '*SnapshotDistributionGrpcServiceTest*' --tests '*ClientSessionTest*'`. The bootstrap-failure regression must finish with registry size, connected sessions, pending snapshots, and pending bytes all equal to zero.

## Trade-off

Registry removal now also owns internal termination, which is a stronger lifecycle contract. The transition is idempotent so a later transport cancellation cannot double-decrement gauges or repeat close behavior.

## Prevention

Treat removal and termination as different concepts but expose one unregister operation that performs both. Every new stream exit path must assert registry membership, connection telemetry, and per-session retained state together.

## Related ADR / PR / Commit

- ADR-004
- PR #19
- `15768b75984eb58ded8512af1346806759223c23`
- `EV-P09-BKP-001`

## Blog Candidate Summary

Why removing a stream from a registry is not enough: making retained-state cleanup an idempotent lifecycle invariant.
