# EV-P09-BKP-001 — Sustained Slow-Client Backpressure

- **Phase:** 9 — Performance & Operations Evidence
- **Status:** `PASS`
- **Evidence ID:** `EV-P09-BKP-001`
- **Git commit:** `4c053bc766f63229fae22a30ebfb1b06fad86570`
- **Executed at:** `2026-09-23T13:15:28Z`
- **Related:** `FM-BKP-001`, ADR-004, TRB-006, TRB-011
- **Command:** `make phase9-backpressure-evidence`
- **Artifact path:** `docs/evidence/phase-09/EV-P09-BKP-001/artifacts/`

## Claim

Within the recorded in-process session topology, sustained non-writable clients retain only one latest full Snapshot each, do not create an unbounded queue, and do not push healthy-client p99 delivery latency or GC-observed heap growth beyond the predeclared experiment targets.

This is a `ClientSession` flow-control and memory envelope. It is not a Netty, HTTP/2, TLS, kernel-buffer, WAN, Kubernetes, or real client read-loop capacity claim.

## Environment fingerprint

`artifacts/environment.txt` records clean source commit `4c053bc766f63229fae22a30ebfb1b06fad86570`, matching index tree `18f3ff175697381a8df366012db04946e8c18609`, host/container resources, and Java 21 image identity. `artifacts/git-status.txt` records `CLEAN`.

## Workload

- cohorts: 100, 500, and 1,000 production `ClientSession` instances;
- 20% non-writable slow sessions and 80% healthy sessions;
- 16 KiB full Snapshot payloads;
- ten warm-up updates and 20 all-ready baseline updates;
- 100 pressure updates at ten updates/second for ten seconds;
- actual pending-slot replacement and drain path with synthetic controllable `ServerCallStreamObserver` readiness;
- heap sampled after explicit GC before pressure, while slow sessions retain the latest Snapshot, and after drain.

## Expected

- steady pending Snapshot count equals the slow-client count;
- each slow session coalesces every obsolete intermediate Snapshot and receives only the latest version after becoming writable;
- one ready session may transiently occupy one additional aggregate slot between offer and immediate drain;
- healthy-client pressure p99 adds no more than 10 ms over the same cohort's all-ready baseline;
- GC-observed heap growth remains at or below 32 MiB;
- pending count and serialized bytes return to zero after drain;
- workload errors remain zero.

## Observed

| Clients | Slow | Healthy baseline p99 | Healthy pressure p99 | p99 addition | Steady pending | Pending bytes | Coalesced | Heap delta |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 20 | 10.047 ms | 4.480 ms | -5.567 ms | 20 | 345,723 B | 1,980 | 0 B |
| 500 | 100 | 10.281 ms | 12.943 ms | +2.662 ms | 100 | 1,662,763 B | 9,900 | 1,652,616 B |
| 1,000 | 200 | 14.028 ms | 11.035 ms | -2.993 ms | 200 | 3,309,063 B | 19,800 | 3,305,128 B |

The maximum aggregate pending count was the slow-client count plus one transient ready-session slot: 21, 101, and 201. After the observers became writable, every slow session received the final version and aggregate pending count/bytes returned to zero. Post-drain heap deltas were 0 B, 512 B, and 1,024 B respectively. No workload error occurred.

## Result

`PASS`. All predeclared queue, coalescing, healthy-client isolation, heap, latest-version recovery, and cleanup conditions passed for every cohort.

## Integrity

The machine-generated raw JSON records `workloadResult: pass`; this README owns lifecycle promotion. `artifacts/SHA256SUMS` covers the raw JSON and source fingerprint. Phase 9 verification independently checks workload result, lifecycle, clean source/tree identity, checksums, secret guards, and tamper-negative behavior.

## Limitations

- Observer readiness is synthetic and deterministic; no real TCP/HTTP/2 receive window or SDK reader is stalled.
- Delivery latency ends when the in-process observer accepts `onNext`; it excludes network transit, client validation, atomic apply, LKG persistence, and ACK.
- Heap deltas after explicit GC are local observations, not retained-object sizing guarantees or production memory limits.
- The 16 KiB full Snapshot and ten-updates/second rate do not cover larger payloads, burstier publication, longer soak, or multiple Distribution replicas.
- A production alert threshold requires the later Prometheus/Grafana runtime-signal gate.
