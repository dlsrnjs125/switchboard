# Phase 9 Kubernetes Step — Regression Verification

## Source and environment

The runtime workload source is identified in EV-P09-K8S-001. Regression checks ran against the same Sample Service implementation with Java 21 in the existing Docker Gradle wrapper. Docker Gradle cache reuse affects build time, not asserted security behavior.

## Commands and observed results

```sh
./load-test/phase-09/java21-gradle.sh :demo:sample-service:test :demo:sample-service:installDist
PYTHONDONTWRITEBYTECODE=1 python3 load-test/phase-09/kubernetes/verify_test.py
./load-test/phase-09/verify.sh
./load-test/phase-09/verify-integrity-test.sh
./load-test/phase-09/java21-gradle.sh :libs:observability:test :services:control-plane:test --tests '*ControlPlaneHttpSecurityIntegrationTest' :services:distribution:test --tests '*CredentialServerInterceptorTest' --tests '*DistributionTelemetryTest'
```

All commands passed. The security run executed the existing tenant HTTP security, credential interceptor and Distribution telemetry tests; the observability module’s policy tests were also forced to rerun with --rerun-tasks and passed. These selected regressions complement existing security evidence; they do not complete the entire Phase 9 Final Security Gate or add new attack-surface coverage. Full aggregate `make final-check` remains unexecuted for this step.

## Runtime gate

Two clean-source Kubernetes freshness-recovery smoke observations are recorded in [EV-P09-K8S-001](../../evidence/phase-09/EV-P09-K8S-001/README.md). Full Snapshot resync for a lagging Provider, workload-linked Prometheus/Grafana/Tempo capture, alert calibration, HPA disposition and final aggregate evidence remain open.

## PR #21 review remediation

- P1: Both kubectl and Helm use the explicit kind context. A full-script stub regression sets an unrelated current context and rejects unbound CLI calls before any real cluster mutation.
- P2 provenance: Clean source revision, tree and clean status are recorded in immutable image config labels at build time. The harness fetches content-addressed OCI index/manifest/config JSON from each kind node, verifies SHA-256 and descriptor links, and matches Pod imageID/config digest to the locally built image root. Initial Control Plane/Distribution Pods, both rolling-update replacements, the Pod-loss replacement and both probe images are checked. Preflight rejects stale images before fault injection.
- P2 mechanism: The normal fixture receives one initial Snapshot and zero additional Full Snapshots. Every runtime sample must preserve that counter. An unexpected equal-version Full Snapshot fails verification.
- P3: Helm is no longer required by the capture-only runner. Demo evidence instrumentation is isolated in EvidenceProbe.

Source labels are first-party build declarations, not signed supply-chain attestations. The recorded hash chain establishes source-label/build/runtime consistency in the controlled local fixture. It does not prove trust in an arbitrary external builder.

The previous capture remains in Git history but is superseded because it lacked source-image linkage. New immutable evidence is required and does not close lagging-version Full Snapshot resync, Observability/Alert, final Security/HPA or final aggregate gates.

Recollection source: `88d443eb1780b07aacf97e98937f2015a728279a` / tree `3d139aba63a610c5f5bd3bbeaabb2e27248a9029`. Runtime image provenance, initial/replacement/probe identity, zero-new-Snapshot invariant and both 1,200-evaluation workloads: PASS.
