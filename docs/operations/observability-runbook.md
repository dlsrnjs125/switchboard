# Observability Runbook

## Local stack

Start infrastructure and the two services:

```bash
docker compose -f infra/docker/docker-compose.yml up -d --wait
SWITCHBOARD_OUTBOX_RELAY_ENABLED=true ./gradlew :services:control-plane:bootRun
./gradlew :services:distribution:bootRun
```

Open Grafana at `http://localhost:3000` and select **Switchboard Reliability Overview**. Prometheus is available at `http://localhost:9091`; Tempo is provisioned as the trace datasource. Applications export Prometheus metrics from `/actuator/prometheus` and OTLP traces to `http://localhost:4318/v1/traces` by default.

## Freshness-delay triage

Use the following order to locate the delayed layer:

1. Check `switchboard_control_publish_total` and transaction outcome. A failure here means no authoritative publication was committed.
2. Check `switchboard_outbox_pending` and `switchboard_outbox_oldest_pending_age_seconds`. Growth after a successful commit isolates the delay to the outbox/Kafka boundary.
3. Check Distribution reconcile rate and cache version. Outbox recovery without a matching reconcile indicates consumer or authoritative-read trouble.
4. Check connected sessions and gRPC event outcomes. A current Distribution cache with no Snapshot-send activity points to admission, authentication, or stream health.
5. Check SDK provider state and `switchboard_sdk_snapshot_age_seconds`. `READY_STALE` or rising age after a send points to transport, apply validation, or LKG durability.
6. Search Tempo and structured logs by correlation ID, event ID, or Snapshot version. Do not paste a credential, user ID, targeting key, or Snapshot payload into a query or incident note.

## Alert response

| Alert | First checks | Existing recovery procedure |
| --- | --- | --- |
| `SwitchboardOutboxBacklogOld` | PostgreSQL pending/age, relay failures, Kafka availability | [Kafka unavailable after commit](runbook.md#kafka-unavailable-after-commit--fm-kfk-001) |
| `SwitchboardDistributionAdmissionRejected` | connected sessions, reconnect rate, session bound | [Reconnect pressure and slow clients](runbook.md#reconnect-pressure-and-slow-clients--fm-rcn-001-fm-bkp-001) |
| `SwitchboardSdkReadyStale` | Snapshot age, Distribution cache/send, reconnects | [Distribution outage or restart](runbook.md#distribution-outage-or-restart--fm-dst-001) |
| `SwitchboardSnapshotIntegrityReject` | SDK apply reason, Distribution reconcile, Snapshot version/checksum | [Invalid or reordered Snapshot](runbook.md#corrupt-incompatible-reordered-or-gapped-snapshot--fm-snp-001-fm-ord-001-fm-gap-001) |

## Verification and safe shutdown

```bash
docker compose -f infra/docker/docker-compose.yml config --quiet
curl -fsS http://localhost:9091/-/ready
curl -fsS http://localhost:3000/api/health
curl -fsS http://localhost:3200/ready
docker compose -f infra/docker/docker-compose.yml down
```

Prometheus rules are in `infra/observability/alerts.yml`; dashboard and datasource provisioning live under `infra/observability/grafana`. A dashboard screenshot is supporting context only—preserve queries or exported JSON for evidence.
