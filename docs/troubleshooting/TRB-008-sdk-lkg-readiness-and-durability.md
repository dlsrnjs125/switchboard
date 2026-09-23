# TRB-008 — SDK LKG Readiness and Filesystem Durability

## Context

The Java Provider validates a full Snapshot, persists a disk Last Known Good copy, atomically swaps memory state, and ACKs only when the applied state satisfies its durability contract.

## Symptom

Two related failures were found. First, an invalid candidate or immediate stream disconnect moved the Provider to `ERROR` even when a valid active LKG could still evaluate. Second, a failure fsyncing the parent directory after atomic rename left the new file visible but crash durability uncertain; a later heartbeat could incorrectly promote the Provider to `READY`.

## Expected vs Actual

- Expected: valid LKG keeps evaluation available as `READY_STALE`; `READY` and ACK require confirmed freshness and durable restart state.
- Actual: candidate failure could discard the evaluation-ready lifecycle, while heartbeat freshness could hide unresolved disk durability uncertainty.

## Reproduction

Apply a valid Snapshot, then deliver a corrupt candidate or disconnect the stream. For durability, inject an `IOException` after the temporary file is fsynced and atomically renamed but while the parent directory is being fsynced; then deliver a heartbeat for the same version.

## Impact

Applications could unnecessarily lose feature evaluation, or the server could receive an ACK for a version that might not survive host power loss. Memory and restart-visible state could diverge.

## Initial Hypothesis

Any invalid remote update was treated as a provider-wide error, and successful atomic rename was treated as equivalent to durable replacement.

## Evidence

Provider and `AtomicSnapshotStore` tests cover corrupt candidates, disconnect with/without LKG, post-rename fsync failure, same-version redelivery, and heartbeat behavior. `EV-P06-REL-001` records zero evaluation failures during stale operation and the durability-confirmation sequence.

## Root Cause

Remote candidate validity, stream freshness, in-memory applicability, and filesystem durability are distinct dimensions that had been collapsed into one state transition.

## Fix

- retain a valid active LKG when a later candidate is rejected;
- move immediately to `READY_STALE` on disconnect when LKG exists;
- fsync file contents, atomically rename, then fsync the parent directory;
- return `COMMITTED_DURABILITY_UNCERTAIN` if rename succeeded but directory fsync failed;
- keep the Provider stale, NACK, and request redelivery until same-version durability confirmation succeeds;
- prevent heartbeat alone from promoting an uncertain LKG.

## Verification

Run `./gradlew :sdk:java-openfeature-provider:test` and `./infra/reliability/phase-06-drill.sh`. The active Snapshot must remain evaluable, ACK must be withheld during uncertainty, and redelivery must confirm durability before `READY`.

## Trade-off

The Provider may evaluate from a newer in-memory Snapshot while reporting stale and requesting redelivery. This favors request-path continuity without claiming restart durability that was not proved.

## Prevention

Model freshness, validity, active applicability, and disk durability separately. Fault-inject every filesystem step around atomic replacement, including the parent-directory fsync.

## Related ADR / PR / Commit

- ADR-001, ADR-009
- PR #11, PR #12
- `35397deaae607e675f4334bff14a2658a5069f7b`
- `4136a5d977e79c2b2439320b27c5e7fe9065add5`
- `0d10623ab4622ac164d0600bc361aac10e68dcba`
- `EV-P05-SDK-001`, `EV-P06-REL-001`

## Blog Candidate Summary

Atomic rename is not the end of durability: keeping an SDK available without lying about its LKG.
