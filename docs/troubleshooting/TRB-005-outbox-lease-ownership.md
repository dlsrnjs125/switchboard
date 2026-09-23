# TRB-005 — Outbox Lease Ownership Across Broker I/O

## Context

Publication writes an outbox event atomically with the Snapshot. A relay later sends the event to Kafka and records broker acknowledgement.

## Symptom

The original relay selected rows with `FOR UPDATE SKIP LOCKED` and then performed external broker I/O while the transaction remained open. If the process stalled or crashed, ownership and recovery semantics were not explicit; a stale worker could also race a reclaimed delivery.

## Expected vs Actual

- Expected: database locks are short, external I/O occurs outside the claim transaction, expired work is reclaimable, and only the current claimant can complete or fail a row.
- Actual: row-lock lifetime was coupled to Kafka latency and no persistent claim token fenced stale workers.

## Reproduction

Pause or terminate a relay after it selects an event but before broker completion. Start another relay and attempt early and post-expiry reclaim; then let the original worker attempt completion.

## Impact

Long database lock retention reduces relay concurrency, while ambiguous ownership risks duplicate work, stalled rows, or stale completion metadata. At-least-once delivery permits duplicates but not loss or false completion.

## Initial Hypothesis

Holding a row lock across publish was assumed to be the simplest way to preserve single-worker ownership.

## Evidence

Phase 3 integration tests cover broker acknowledgement, failure retry, lease expiry, and claim-token completion. Phase 6 drills verify an expired claim is reclaimable and a stale token cannot complete it.

## Root Cause

Database transaction scope was incorrectly extended across a remote system boundary. Row locks cannot express crash-recoverable ownership after the process or connection disappears.

## Fix

Claim one event in a short transaction with `claim_token` and `claimed_at`, publish outside the transaction, and update success/failure only when the token still matches. Allow reclaim only after a bounded lease expires.

## Verification

Run `./gradlew :services:control-plane:test` and `./infra/reliability/phase-06-drill.sh`. Confirm early reclaim is rejected, expired reclaim succeeds, stale completion updates zero rows, and `published_at` is set only after broker ACK.

## Trade-off

A crash after broker ACK but before database completion can still cause a duplicate. Consumers must remain idempotent by event identity and authoritative Snapshot version.

## Prevention

Never hold a database transaction open across broker or network I/O. Use explicit lease ownership and fence every completion with the claim token.

## Related ADR / PR / Commit

- ADR-005, ADR-006
- PR #9, PR #12
- `2342a663653f5f4892ae1ba3e5c4c6b860331733`
- `4136a5d977e79c2b2439320b27c5e7fe9065add5`
- `EV-P03-PUB-001`, `EV-P06-REL-001`

## Blog Candidate Summary

Replacing database locks across Kafka I/O with crash-recoverable leases and fencing tokens.
