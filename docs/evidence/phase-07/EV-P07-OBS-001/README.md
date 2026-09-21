# EV-P07-OBS-001 — Observability Contract and Local Stack Verification

- **Status:** PASS
- **Phase:** Phase 7 — Observability
- **Git commit:** `77fed2a5e620df0894aac886c10ceb44e7783101`
- **Executed at:** 2026-09-21T06:31:26Z
- **Owner:** Switchboard maintainers
- **Related:** Phase 7 completion criteria, `FM-KFK-001`, `FM-DST-001`, `FM-SNP-001`, `FM-RCN-001`, ADR-002, ADR-004, ADR-006, ADR-009

## Claim

The repository provides a coherent low-cardinality telemetry contract and a valid local Prometheus/Grafana/Tempo topology that can distinguish Control Plane commit, outbox/Kafka delay, Distribution reconciliation/session state, and Java SDK freshness/apply state without putting targeting identity or credentials in metric labels.

## Commands

```bash
./gradlew :libs:observability:test :services:control-plane:test :services:distribution:test :sdk:java-openfeature-provider:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
jq empty infra/observability/grafana/dashboards/switchboard-overview.json
```

## Expected and observed

- [x] Metric names and label keys pass the shared policy tests.
- [x] Targeting/user/credential/environment identity is absent from SDK metric dimensions.
- [x] Caller-controlled Distribution reasons collapse to a bounded `other` label.
- [x] Publish, outbox, Distribution, and SDK freshness signals are represented in the provisioned dashboard.
- [x] Alert rules link backlog, admission, stale SDK, and integrity rejection to recovery procedures.
- [x] Docker Compose and Grafana dashboard JSON parse successfully.
- [x] Java module tests and the multi-module build pass in the recorded compatibility-validation environment; Java 21 CI remains the merge gate.

Observed test totals were 2 shared-observability tests, 27 Control Plane tests, 19 Distribution tests, and 20 Java Provider tests, with zero failures or errors. The complete multi-module build executed 86 tasks successfully and reused the Gradle configuration cache.

## Result

The repository-level instrumentation contract, automated privacy/cardinality guards, dashboard provisioning, alert definitions, and container topology passed validation. This evidence establishes implementation and configuration correctness for the local topology; it does not claim production telemetry delivery, retention, alert delivery, or fleet-scale cardinality.

## Artifact paths

- `libs/observability/`
- `infra/observability/`
- `docs/observability/conventions.md`
- `docs/operations/observability-runbook.md`
- module test reports under `*/build/test-results/test/`

## Limitations

- The host provides Java 17, so local verification uses an isolated source copy with the toolchain temporarily lowered; repository configuration remains Java 21 and CI is authoritative.
- Compose parsing and provisioning files are verified, but this record does not claim a long-running telemetry soak or notification delivery to an external paging system.
- Dashboard queries are designed for Micrometer's Prometheus naming convention; production naming changes require dashboard contract tests and review.
