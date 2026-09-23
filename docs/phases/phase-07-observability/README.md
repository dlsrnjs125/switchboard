# Phase 7 — Observability

## Outcome

Phase 7 makes publish-to-SDK freshness diagnosable by layer without placing user identity, evaluation context, credentials, or unbounded identifiers in metric labels. Control Plane, outbox, Distribution, and Java Provider expose a shared Micrometer/OpenTelemetry signal contract, while Prometheus, Grafana, and Tempo provide the local collection and investigation path.

## Implemented scope

- Shared `libs:observability` module with metric naming, bounded-label, and sensitive-attribute policy.
- Control Plane publish/rollback, transaction, Snapshot compile, and validation observations, with final publish success qualified by transaction commit.
- Outbox backlog/oldest-age gauges, delivery outcomes, and broker-ACK latency.
- Distribution reconciliation, cache version, connected sessions, admission/revocation, exact per-delivery Snapshot-send-to-ACK latency, and finite gRPC event observations. A server-generated delivery ID prevents concurrent sessions for one client application from overwriting each other's latency correlation.
- Java Provider state, Snapshot age/apply, reconnect/LKG, stale duration, and local evaluation duration/reason metrics.
- Trace/span IDs in structured log context and stable correlation/event/Snapshot identifiers at finite operation boundaries.
- Prometheus scrape/rule configuration, provisioned Grafana dashboard/datasources, OpenTelemetry Collector, and Tempo.
- Alerts linked to existing recovery runbooks.
- Automated cardinality/privacy guard tests.

## Diagnostic path

```text
publish/commit -> outbox/Kafka -> Distribution reconcile/cache -> gRPC event -> SDK apply/state
```

The dashboard exposes a signal at each boundary. If SDK Snapshot age rises, operators walk left until they find the last layer that advanced. Individual operations are then correlated in Tempo and structured logs by correlation ID, event ID, and Snapshot version. Long-lived gRPC Subscribe streams are not modeled as long-running spans; subscribe, send, ACK, NACK, and resync are finite event observations.

## Verification

```bash
./gradlew :libs:observability:test :services:control-plane:test :services:distribution:test :sdk:java-openfeature-provider:test
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
jq empty infra/observability/grafana/dashboards/switchboard-overview.json
```

The tests enforce approved metric labels, reject sensitive trace keys and bearer values, prove dynamic Distribution reasons collapse to `other`, verify independent ACK-latency samples for concurrent sessions sharing one application, and inspect emitted SDK meters for forbidden identity/scope labels.

The runtime evidence additionally starts the provisioned stack, verifies Prometheus scrape health and Grafana health, and confirms an authenticated normal Publish trace in Tempo. The Kafka outage, Distribution restart, and corrupt-Snapshot Phase 6 drills assert distinct metric signals as part of their recovery tests.

## Troubleshooting

- [TRB-009 — Transaction and delivery telemetry correlation](../../troubleshooting/TRB-009-telemetry-transaction-and-delivery-correlation.md)

## Deliberate limits

- The local stack is a development topology, not a production retention, authentication, high-availability, or capacity prescription.
- SDK meters are registered in the host application's registry. A host must expose or export that registry for fleet collection.
- Stable identifiers correlate asynchronous boundaries, but a database transaction and later outbox delivery are not represented as one synthetic parent span.
- Per-client freshness is investigated through bounded structured logs/traces; metric labels intentionally do not contain client, environment, tenant, or user identifiers.
- Alert thresholds are initial operational defaults and require Phase 9 capacity/performance evidence before production tuning.
