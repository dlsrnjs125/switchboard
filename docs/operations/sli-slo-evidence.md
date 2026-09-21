# SLI/SLO Evidence

The canonical definitions and design targets remain in [sli-slo-design.md](sli-slo-design.md). Phase 9 calibrates them without converting a local result into a production SLO.

| SLI | Evidence source | Interpretation |
| --- | --- | --- |
| `SLI-EVAL-001` | `evaluation-jmh.json` | provider resolution sample-time p50/p95/p99 for each flag count and rule shape |
| `SLI-PRP-001` proxy | `grpc-capacity.json` | Distribution broadcast-to-ACK; explicitly not commit-to-SDK-apply |
| Snapshot compile/validation | `snapshot-publish-jmh.json` | CPU-side publish stages by Snapshot flag count; excludes transaction/outbox |
| Safety SLIs | failure drill and negative regressions | zero tolerance; any violation blocks readiness |
| Recovery SLIs | Phase 6 and kind drill logs | state convergence is required; restart time alone is insufficient |

Percentiles are only valid with sample count, errors, workload, and environment. Alert thresholds in Phase 7 are review baselines; paging thresholds require representative production traffic and an operating history. No local benchmark establishes a production SLA.
