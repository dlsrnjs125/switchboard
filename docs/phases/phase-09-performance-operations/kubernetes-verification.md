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
