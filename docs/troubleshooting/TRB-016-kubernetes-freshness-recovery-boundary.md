# TRB-016 — Kubernetes Freshness Recovery Is Not Full Snapshot Resync

## Context

Phase 9 adds timing evidence to Phase 8's two-replica kind rolling-update and Pod-loss drills.

## Symptom

A probe completed READY_STALE intervals and returned to READY with successful local evaluations, but the Snapshot receipt count remained at its initial value of one.

## Expected vs Actual

The initial verifier expected every reconnect to deliver a Full Snapshot. Actual equal-version reconnects confirm freshness through Heartbeats. No duplicate Snapshot is needed.

## Reproduction

Connect the Java Provider at authoritative version 2, restart both Distribution replicas through a rolling update or forcibly delete the Pod owning the stream, and retain version 2 throughout. Compare reconnect and stale-interval counters with Snapshot receipt counters.

## Impact

Calling the observed stale interval a Full Snapshot resync duration would overstate the measurement. A verifier demanding a duplicate Snapshot would reject correct protocol behavior.

## Initial Hypothesis

The Snapshot metric might have failed to register duplicate deliveries.

## Evidence

The exploratory run observed reconnects and completed stale timers while `snapshots=1`. `ClientSession.offerSnapshot` skips Snapshots at or below the client's advertised version. `SwitchboardProvider.onHeartbeat` confirms freshness when authoritative and active versions match. The final clean-source capture is preserved in [EV-P09-K8S-001](../evidence/phase-09/EV-P09-K8S-001/README.md).

## Root Cause

The proposed measurement equated Provider READY recovery with Full Snapshot transfer, ignoring the equal-version protocol path.

## Fix

Record READY_STALE duration as freshness recovery, retain actual Snapshot receipt counts, and explicitly leave lagging-version Full Snapshot resync unmeasured. Fail runs with no observed fault/recovery, invalid evaluations, incomplete samples or sampled full outages. Prevent artifact reuse across captures.

## Verification

Heartbeat-only recovery is accepted with zero new Snapshot receipts, and the result explicitly identifies Full Snapshot resync as unmeasured. Negative regressions reject absent reconnects, truncated logs, incorrect evaluation values and zero ready endpoints. Source identity, image digests, raw workload logs and checksum manifest accompany the final capture.

## Trade-off

This bounded smoke run provides two Kubernetes freshness recovery observations, not a distribution of latency or proof of lagging-version resync.

## Prevention

Define timers from concrete protocol events before choosing assertions. Freeze executing scripts until they exit and use a fresh artifact directory. During exploration an edit to a running shell script shifted its read position and caused `ubectl: command not found`; that failed collection was excluded and a new immutable run performed. Do not patch a verifier while its parent measurement script is active.

## Related ADR / PR / Commit

- ADR-009: LKG and READY_STALE behavior.
- Phase 8 Kubernetes deployment and Phase 9 evidence boundaries.
- Final evidence source and PR are linked from EV-P09-K8S-001.

## Blog Candidate Summary

Reconnect can restore freshness without transferring configuration. Recovery evidence must identify the actual convergence event before labeling a duration.
