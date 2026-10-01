# Phase 9 — Performance and Operations Evidence

## Status

`IN_PROGRESS`. The implementation from Phases 1–8 is complete; Phase 9 records bounded performance, recovery, security, and operational claims without expanding product scope.

## Verified evidence

- `EV-P09-PUB-001`: clean-source transactional Publish and outbox commit baseline;
- `EV-P09-PRP-001`: clean-source commit-to-broker-to-Distribution-to-Provider apply/ACK baseline;
- `EV-P09-RCN-001`: 100/500/1,000-client Distribution restart and reconnect-storm recovery;
- `EV-P09-BKP-001`: sustained 20% slow-client pressure, coalescing, pending-memory, healthy-client latency, and cleanup bounds.
- `EV-P09-RCV-001`: clean-source 30-cycle timed recovery workloads covering PostgreSQL pre-commit atomic retry, ambiguous post-commit reconciliation, Kafka/outbox retry and persistence, Distribution restart with `READY_STALE` continuity, and credential-dependency retry.

All five recovery scenarios passed their predeclared p95, integrity, continuity, and cleanup gates. The corrected Kafka interval retains the persisted two-second retry, and every credential cycle proves its own unavailable response. Raw artifacts are tied to clean commit `4dd4c568b3dd6f2a7284b30fae3d3530bfb5b426` and a matching Git tree.

- `EV-P09-K8S-001`: clean-source bounded rolling-update and connected Pod-loss freshness recovery; 1,200 correct evaluations per scenario, sampled endpoint continuity, and final READY/version 2. Full Snapshot resync for a lagging Provider remains unmeasured.

See [Kubernetes-step regression verification](kubernetes-verification.md).

## Remaining gates

- Kubernetes Full Snapshot resync duration for a lagging Provider;
- Prometheus/Grafana/Tempo signal capture and resource saturation correlation;
- Evidence-backed alert threshold review;
- final tenant/credential/telemetry security-negative regression;
- HPA scale-out execution or an explicit final capacity limitation;
- immutable-source `make final-check` and `EV-P09-FINAL-001` aggregate result.

## Boundaries

Existing local measurements do not prove production HA, WAN behavior, multi-zone recovery, disaster recovery, long-duration soak, or capacity for an unspecified workload. See [Final Readiness](../../final-readiness.md), [Capacity and Limitations](../../operations/capacity-limits.md), and the [Phase 9 Evidence bundles](../../evidence/README.md).

PR #21 review remediation adds explicit Helm/kubectl context isolation, source-label/OCI-runtime provenance for initial and replacement Pods, and a strict equal-version Snapshot-count invariant. New immutable capture replaces the original provenance-incomplete bundle; see [TRB-017](../../troubleshooting/TRB-017-runtime-image-provenance.md). Phase 9 remains IN_PROGRESS.
