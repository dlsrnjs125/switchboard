# EV-P07-OBS-001 — Observability Contract and Local Stack Verification

- **Status:** PASS
- **Phase:** Phase 7 — Observability
- **Baseline commit:** `6d67c7a2b76f0beb8e5d035a75ddc67390b2e5a7`
- **Runtime rerun:** 2026-09-21T06:58:24Z
- **Owner:** Switchboard maintainers
- **Related:** Phase 7 completion criteria, `FM-KFK-001`, `FM-DST-001`, `FM-SNP-001`, `FM-RCN-001`, ADR-002, ADR-004, ADR-006, ADR-009

## Claim

The repository provides a coherent low-cardinality telemetry contract and a working local Prometheus/Grafana/Tempo topology that can distinguish Control Plane commit, outbox/Kafka delay, Distribution reconciliation/session state, and Java SDK freshness/apply state without putting targeting identity or credentials in metric labels.

## Commands

```bash
./gradlew :libs:observability:test :services:control-plane:test :services:distribution:test :sdk:java-openfeature-provider:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
docker compose -f infra/docker/docker-compose.yml up -d postgres otel-collector tempo prometheus grafana
jq empty infra/observability/grafana/dashboards/switchboard-overview.json
curl -fsS http://localhost:8080/actuator/health
curl -fsS 'http://localhost:9091/api/v1/query?query=up%7Bjob%3D%22switchboard-control-plane%22%7D'
curl -fsS 'http://localhost:3200/api/search?limit=50'
curl -fsS http://localhost:3000/api/health
```

## Expected and observed

- [x] Metric names and label keys pass the shared policy tests.
- [x] Targeting/user/credential/environment identity is absent from SDK metric dimensions.
- [x] Caller-controlled Distribution reasons collapse to a bounded `other` label.
- [x] Publish, outbox, Distribution, and SDK freshness signals are represented in the provisioned dashboard.
- [x] Alert rules link backlog, admission, stale SDK, and integrity rejection to recovery procedures.
- [x] Docker Compose and Grafana dashboard JSON parse successfully.
- [x] The Spring Boot 4 runtime applies all three Flyway migrations before serving traffic.
- [x] An authenticated normal Publish is exported over OTLP and stored in Tempo.
- [x] Prometheus scrapes the unauthenticated Control Plane endpoint with target `up == 1`.
- [x] Grafana reports database health `ok` and loads the provisioned Prometheus/Tempo datasources.
- [x] The Kafka outage, Distribution restart, and corrupt-Snapshot drills assert their representative telemetry signals in addition to recovery behavior.
- [x] Concurrent sessions sharing a client application retain independent delivery-to-ACK latency samples through exact delivery-ID correlation.
- [x] Java module tests and the multi-module build pass in the recorded compatibility-validation environment; Java 21 CI remains the merge gate.

The runtime Publish used correlation ID `11111111-2222-4333-8444-555555555555` and committed Snapshot version `1`. Tempo stored trace `e7ed2b9dfa60dc62bd576ce59f88cbad`; the trace contains the HTTP Publish root span and the `switchboard.control.publish` child span with `outcome=success`, `reason=none`, the correlation ID, and Snapshot version. This confirms application observation, OpenTelemetry bridge/exporter, Collector ingestion, and Tempo persistence as one exercised path.

The representative fault assertions are deliberately tied to the existing Phase 6 drills:

- `KafkaOutageIntegrationTest.committedOutboxSurvivesKafkaPauseAndPublishesAfterBrokerRecovery` pauses a real Kafka container and observes delivery failure `publisher_error`, then success `broker_ack` and broker-ACK latency after recovery.
- `GrpcDistributionIntegrationTest.providerKeepsLocalEvaluationDuringDistributionRestartAndConvergesAgain` restarts the actual gRPC server and observes SDK `READY_STALE`, reconnect activity, and recovery to `READY`.
- `SwitchboardProviderTest.invalidUpdateIsNackedAndPreservesReadyStateAndActiveSnapshot` injects a corrupt candidate and observes an integrity rejection without replacing the active Snapshot.

## Result

The repository-level contract and actual local telemetry delivery path passed validation. A normal authenticated Publish was visible in Tempo, the Control Plane scrape was healthy in Prometheus, Grafana was healthy, and representative Phase 6 faults produced distinct asserted signals. Publish success now means transaction completion with committed status, and Snapshot-to-ACK latency starts from and correlates to the exact gRPC delivery rather than a shared client-application key.

## Artifact paths

- `libs/observability/`
- `infra/observability/`
- `docs/observability/conventions.md`
- `docs/operations/observability-runbook.md`
- module test reports under `*/build/test-results/test/`

## Limitations

- The host provides Java 17, so local verification uses an isolated source copy with the toolchain temporarily lowered; repository configuration remains Java 21 and CI is authoritative.
- This record proves a local runtime path and focused fault drills; it does not claim a long-running telemetry soak, production retention/HA, notification delivery to an external paging system, or fleet-scale cardinality.
- Asynchronous Publish, outbox, Distribution, and SDK operations remain separate finite traces correlated by stable identifiers; this record does not claim one synthetic parent span across database and streaming boundaries.
- Dashboard queries are designed for Micrometer's Prometheus naming convention; production naming changes require dashboard contract tests and review.
