# SLI/SLO Evidence

The canonical definitions and design targets remain in [sli-slo-design.md](sli-slo-design.md). This partial Phase 9 baseline calibrates only the rows explicitly backed by raw measurements; it does not convert a local result into a production SLO.

| SLI | Evidence source | Interpretation |
| --- | --- | --- |
| `SLI-EVAL-001` | `evaluation-jmh.json` | provider resolution sample-time p50/p95/p99 for each flag count and rule shape |
| `SLI-PRP-001` component proxy | `grpc-capacity.json` | Distribution broadcast-to-ACK under 100/500/1,000 initially connected clients |
| `SLI-PRP-001` end-to-end baseline | `EV-P09-PRP-001/publish-propagation.json` | single-client commit-to-server-observed SDK ACK p50/p95/p99 after Provider atomic apply and LKG write |
| Publish transaction | `EV-P09-PUB-001/publish-transaction.json` | 1/100/1,000-flag transaction/outbox commit latency, payload bytes, and sequential throughput |
| Snapshot compile/validation | `snapshot-publish-jmh.json` | CPU-side publish stages by Snapshot flag count; excludes transaction/outbox |
| Safety SLIs | failure drill and negative regressions | zero tolerance; any violation blocks readiness |
| Recovery SLIs | `EV-P09-RCN-001` and `EV-P09-RCV-001` | fleet reconnect plus 30-cycle PostgreSQL, Kafka/outbox, Distribution, and credential recovery p50/p95/p99/max in bounded local topologies |

`EV-P09-PUB-001` and `EV-P09-PRP-001` are immutable-source `PASS` baselines at commit `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`. `SLI-PRP-001` now has a single-client local baseline, but representative concurrency and multi-replica topology remain uncalibrated. `EV-P09-RCN-001` records 1,000-client reconnect p95 956.640 ms and p99 978.858 ms. Corrected `EV-P09-RCV-001` records PostgreSQL pre-commit p95 129.381 ms, post-commit reconciliation p95 40.284 ms, Kafka/outbox p95 2,070.396 ms including the persisted two-second first retry, Distribution restart p95 71.114 ms, and credential dependency p95 86.205 ms with a new fault proven in every cycle. Percentiles are valid only for their recorded sample count, retry configuration, workload, and environment. Alert thresholds in Phase 7 remain initial review defaults; these baselines do not establish a production SLA.
