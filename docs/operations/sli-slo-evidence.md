# SLI/SLO Evidence

The canonical definitions and design targets remain in [sli-slo-design.md](sli-slo-design.md). This partial Phase 9 baseline calibrates only the rows explicitly backed by raw measurements; it does not convert a local result into a production SLO.

| SLI | Evidence source | Interpretation |
| --- | --- | --- |
| `SLI-EVAL-001` | `evaluation-jmh.json` | provider resolution sample-time p50/p95/p99 for each flag count and rule shape |
| `SLI-PRP-001` proxy | `grpc-capacity.json` | Distribution broadcast-to-ACK; explicitly not commit-to-SDK-apply |
| Snapshot compile/validation | `snapshot-publish-jmh.json` | CPU-side publish stages by Snapshot flag count; excludes transaction/outbox |
| Safety SLIs | failure drill and negative regressions | zero tolerance; any violation blocks readiness |
| Recovery SLIs | Phase 6 and Phase 8 correctness evidence only | convergence behavior is known, but Phase 9 p50/p95 recovery distributions remain unmeasured |

`SLI-PRP-001` end-to-end propagation and `SLI-RCV-001` fleet reconnect recovery remain uncalibrated. Percentiles are only valid with sample count, errors, workload, and environment. Alert thresholds in Phase 7 remain initial review defaults; this baseline does not claim they were recalibrated. No local benchmark establishes a production SLA.
