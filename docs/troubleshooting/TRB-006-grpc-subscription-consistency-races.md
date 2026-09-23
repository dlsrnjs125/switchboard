# TRB-006 — gRPC Subscription Consistency Races

## Context

Distribution bootstraps a new server-streaming subscriber from authoritative PostgreSQL state while live Kafka-driven broadcasts may update the process-local cache at the same time.

## Symptom

A session was registered after reading the bootstrap Snapshot. A publication arriving between those operations could be broadcast before the client was visible, then the older bootstrap result could be sent. Heartbeats or resync messages could also overtake a pending newer Snapshot.

## Expected vs Actual

- Expected: a subscriber receives a monotonic sequence, never misses an overlapping update, and never sees an older control message after a newer Snapshot is offered.
- Actual: bootstrap and registration were separate unsynchronized steps, and a same-version candidate with a different Snapshot identity was not distinguished from an idempotent replay.

## Reproduction

Block authoritative bootstrap after reading version `N`, apply and broadcast version `N+1`, then resume subscription registration. Separately offer a Snapshot while the observer is not writable and attempt an older heartbeat or resync.

## Impact

The client could remain stale until another publication, incorrectly accept a conflicting same-version object, or observe misleading freshness state.

## Initial Hypothesis

Reading current state immediately before session registration was assumed to be close enough to atomic because all updates were full Snapshots.

## Evidence

`SnapshotDistributionGrpcServiceTest`, `ClientSessionTest`, and `SnapshotCacheTest` deterministically interleave bootstrap and broadcast, verify pending coalescing, and exercise snapshot-ID and checksum conflicts.

## Root Cause

Registration did not establish the live-delivery happens-before relationship before bootstrap reconciliation. Session state tracked only pending payload, not the client's baseline and highest offered version.

## Fix

- register the session before loading current authority;
- record the client's last applied version and the highest offered version;
- ignore stale or duplicate offers and suppress older heartbeat/resync messages;
- treat same-version different Snapshot ID or checksum as integrity conflicts;
- unregister sessions when bootstrap fails.

## Verification

Run `./gradlew :services:distribution:test`. The overlap test must deliver the newer Snapshot exactly once, and the cache/session tests must preserve version and identity monotonicity.

## Trade-off

Per-session synchronization serializes offer/drain/control-message decisions. Each slow stream still retains only one coalesced pending Snapshot, keeping memory bounded.

## Prevention

For every bootstrap-plus-live-stream protocol, explicitly test the handoff race. Track monotonic state inside the session rather than depending on call ordering outside it.

## Related ADR / PR / Commit

- ADR-004, ADR-012
- PR #10
- `4b8f03b9444642cc21b72ecc00d6ad49fd997a64`
- `EV-P04-DST-001`

## Blog Candidate Summary

How register-before-bootstrap closes the classic gap between initial state and a live update stream.
