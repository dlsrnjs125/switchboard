# TRB-010 — Distribution Replica Notification Fan-out

## Context

Each Kubernetes Distribution Pod owns an independent in-memory Snapshot cache, gRPC session registry, and set of connected SDK clients. Kafka carries publication freshness notifications.

## Symptom

All replicas used one Kafka consumer group. Kafka correctly load-balanced each notification to only one Pod, but non-consuming replicas never reconciled their local cache or broadcast the update to their own existing sessions.

## Expected vs Actual

- Expected: every live replica observes each publication notification and updates the sessions it owns.
- Actual: one notification was processed once per Deployment, despite state and clients being local to each Pod.

## Reproduction

Run two Distribution replicas with the same effective consumer group, pin one SDK probe to each Pod, publish version 2, and observe that only the Pod assigned the Kafka record pushes the update.

## Impact

Clients connected to another healthy replica can remain indefinitely stale even though the Deployment, Kafka consumer group, and Kubernetes readiness all look healthy.

## Initial Hypothesis

A shared consumer group was assumed to provide horizontal scaling for notification processing without accounting for process-local caches and sessions.

## Evidence

The Phase 8 kind drill pins probes to two replica-specific Services, verifies distinct effective groups, publishes versions 1 and 2, and requires both probes to observe both versions before the rolling-update drill continues.

## Root Cause

Kafka consumer-group semantics provide competing consumption, not broadcast. The notification's required fan-out scope is one copy per owner of local derived state.

## Fix

Derive the effective consumer group from a stable prefix plus Kubernetes Pod name. New Pod groups replay retained notifications from `earliest`, reconcile each notification against authoritative PostgreSQL state, and broadcast the validated current full Snapshot to local sessions.

## Verification

Run `make kind-e2e`. Confirm two distinct `SWITCHBOARD_DISTRIBUTION_CONSUMER_GROUP` values and that probes pinned to separate Pods both apply the second publication.

## Trade-off

Kafka now stores offsets and delivers notifications per Pod group, increasing broker metadata and traffic. Inactive group cleanup and notification retention become explicit operational requirements.

## Prevention

For each message consumer, document whether the required semantic is competing work, tenant partitioning, or broadcast. Match the group boundary to the ownership boundary of derived state.

## Related ADR / PR / Commit

- ADR-002, ADR-004, ADR-005, ADR-006
- PR #14
- `ec035bb145a644009608b763ef91a3b24c9a3280`
- `EV-P08-K8S-001`

## Blog Candidate Summary

Why a healthy Kafka consumer group can leave half of a stateful gRPC fleet stale.
