# Runbook Index

| Situation | Runbook |
| --- | --- |
| PostgreSQL, Kafka, Distribution, Snapshot, credential, SDK, reconnect/backpressure | [Reliability recovery](runbook.md) |
| Metrics, traces, dashboards, alert diagnosis | [Observability](observability-runbook.md) |
| Helm install, readiness, rolling update, drain, scaling | [Kubernetes](kubernetes-runbook.md) |
| Capacity envelope and non-claims | [Capacity and limitations](capacity-limits.md) |
| Known symptoms and fixes | [Troubleshooting index](../troubleshooting/README.md) |

Incident order is safety first: preserve tenant isolation, immutable history, Snapshot integrity, committed outbox intent, and LKG continuity before optimizing freshness or throughput.
