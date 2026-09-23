# TRB-009 — Transaction and Delivery Telemetry Correlation

## Context

Phase 7 added metrics, traces, and structured logs for Control Plane publication and Distribution delivery. These signals must describe committed business outcomes and actual per-stream delivery latency.

## Symptom

Publish success and duration were initially recorded when the service method returned, before Spring transaction completion. ACK latency was first correlated by client application and Snapshot version, so concurrent sessions sharing one application identity could overwrite each other's send timestamp.

## Expected vs Actual

- Expected: publish success means the transaction committed; ACK latency pairs one emitted full Snapshot with its matching ACK.
- Actual: a later rollback could still increment success, and shared identities could produce missing or incorrect latency samples.

## Reproduction

Prepare a successful `PublishResult` and force transaction rollback after the method returns. For ACKs, open multiple sessions for the same client application, deliver the same Snapshot, and acknowledge them in a different order.

## Impact

Dashboards and alerts could overstate successful publication or report meaningless delivery latency, undermining SLI/SLO evidence and incident diagnosis.

## Initial Hypothesis

Method return was assumed to represent transaction success, and `(clientApplicationId, snapshotVersion)` was assumed to uniquely identify a delivery.

## Evidence

`ControlPlaneTelemetryTest` verifies prepared versus committed/rolled-back counters and duration completion. Distribution telemetry tests verify unique delivery IDs, out-of-order ACKs, unknown IDs, version mismatches, and cleanup on session close.

## Root Cause

Instrumentation boundaries were attached to application control flow rather than the authoritative transaction and network-delivery lifecycle.

## Fix

- register transaction synchronization and finalize publish metrics/observations in `afterCompletion`;
- keep a separate prepared counter for pre-commit work;
- add optional `delivery_id` to `FullSnapshot` and `AckRequest`;
- generate one ID per actual stream emission and key timing state by that ID;
- remove outstanding delivery state when the session closes.

## Verification

Run `./gradlew :services:control-plane:test :services:distribution:test :sdk:java-openfeature-provider:test`. Rollbacks must never count as publish success, and only a matching delivery ID/version may record ACK latency.

## Trade-off

Older clients can ACK without `delivery_id`; their ACK correctness remains valid, but they intentionally produce no latency sample. The contract change is additive under protobuf semantics.

## Prevention

Define every metric's authoritative start and stop event before implementation. Never correlate concurrent events using a business identity that is not unique to the event instance.

## Related ADR / PR / Commit

- ADR-004, ADR-006
- PR #13
- `7343b74d69e802d2abdf4c4823b37a69cde8350a`
- `a43fda100b9506e4a496067ee0e48dc78c33cada`
- `EV-P07-OBS-001`

## Blog Candidate Summary

Why transaction-aware metrics and per-delivery correlation IDs matter more than adding another dashboard.
