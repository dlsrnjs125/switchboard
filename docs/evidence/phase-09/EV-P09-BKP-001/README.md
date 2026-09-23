# EV-P09-BKP-001 — Sustained Slow-Client Backpressure

- **Phase:** 9 — Performance & Operations Evidence
- **Status:** `PASS`
- **Evidence ID:** `EV-P09-BKP-001`
- **Git commit:** `15768b75984eb58ded8512af1346806759223c23`
- **Executed at:** `2026-09-23T15:44:59Z`
- **Related:** `FM-BKP-001`, ADR-004, TRB-006, TRB-011, TRB-014
- **Command:** `make phase9-backpressure-evidence`
- **Artifact path:** `docs/evidence/phase-09/EV-P09-BKP-001/artifacts/`

## Claim

Within the recorded in-process session topology, sustained non-writable clients retain only one latest full Snapshot each, do not create an unbounded queue, and do not push healthy-client p99 delivery latency or GC-observed heap growth beyond the predeclared experiment targets.

This is a `ClientSession` flow-control and memory envelope. It is not a Netty, HTTP/2, TLS, kernel-buffer, WAN, Kubernetes, or real client read-loop capacity claim.

## Environment fingerprint

`artifacts/environment.txt` records clean source commit `15768b75984eb58ded8512af1346806759223c23`, matching index tree `4582a1dc476719f558a3b310164411bf67afda74`, host/container resources, and Java 21 image identity. `artifacts/git-status.txt` records `CLEAN`.

## Workload

- cohorts: 100, 500, and 1,000 production `ClientSession` instances;
- 20% non-writable slow sessions and 80% healthy sessions;
- 16 KiB full Snapshot payloads;
- a fixed-seed shuffle mixing healthy and slow sessions in traversal order;
- ten warm-up updates, followed by equal 100-update all-ready baseline and pressure windows at ten updates/second for ten seconds each;
- healthy-client latency measured from the scheduled publication time, including schedule debt;
- actual pending-slot replacement and drain path with synthetic controllable `ServerCallStreamObserver` readiness;
- heap sampled after explicit GC before pressure, while slow sessions retain the latest Snapshot, and after drain.

## Expected

- steady pending Snapshot count equals the slow-client count;
- each slow session coalesces every obsolete intermediate Snapshot and receives only the latest version after becoming writable;
- one ready session may transiently occupy one additional aggregate slot between offer and immediate drain;
- healthy-client pressure p99 adds no more than 10 ms over the same cohort's all-ready baseline;
- baseline and pressure schedule-lag p99 remain at or below 25 ms and each window sustains at least 9.5 updates/second;
- GC-observed heap growth remains at or below 32 MiB;
- pending count and serialized bytes return to zero after drain;
- workload errors remain zero.

## Observed

| Clients | Slow | Healthy baseline p99 | Healthy pressure p99 | p99 addition | Steady pending | Pending bytes | Coalesced | Heap delta |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 20 | 11.380 ms | 9.289 ms | -2.091 ms | 20 | 329,260 B | 1,980 | 0 B |
| 500 | 100 | 11.342 ms | 18.858 ms | +7.516 ms | 100 | 1,646,300 B | 9,900 | 1,652,344 B |
| 1,000 | 200 | 65.516 ms | 16.297 ms | -49.219 ms | 200 | 3,292,600 B | 19,800 | 3,306,424 B |

Baseline/pressure schedule-lag p99 was 6.949/5.312 ms, 4.580/5.837 ms, and 3.839/6.139 ms. Baseline/pressure publication rate was 9.998/9.997, 9.999/9.993, and 9.995/9.989 updates/second. All values include scheduler debt and satisfy their predeclared gates.

The maximum aggregate pending count was the slow-client count plus one transient ready-session slot: 21, 101, and 201. The table reports bytes captured immediately before drain; the separately recorded maxima, including that transient ready-session slot, were 345,723 B, 1,662,763 B, and 3,309,063 B. After the observers became writable, every slow session received the final version and aggregate pending count/bytes returned to zero. Post-drain heap deltas were 0 B, 240 B, and 2,320 B respectively. No workload error occurred.

## Result

`PASS`. All predeclared queue, coalescing, healthy-client isolation, heap, latest-version recovery, and cleanup conditions passed for every cohort.

## Integrity

The machine-generated raw JSON records `workloadResult: pass`; this README owns lifecycle promotion. `artifacts/SHA256SUMS` covers the raw JSON and source fingerprint. Phase 9 verification independently checks workload result, lifecycle, clean source/tree identity, checksums, secret guards, and tamper-negative behavior.

## Limitations

- Observer readiness is synthetic and deterministic; no real TCP/HTTP/2 receive window or SDK reader is stalled.
- Delivery latency begins at the scheduled publication time and ends when the in-process observer accepts `onNext`; it includes harness schedule debt but excludes network transit, client validation, atomic apply, LKG persistence, and ACK.
- Heap deltas after explicit GC are local observations, not retained-object sizing guarantees or production memory limits.
- The 16 KiB full Snapshot and ten-updates/second rate do not cover larger payloads, burstier publication, longer soak, or multiple Distribution replicas.
- A production alert threshold requires the later Prometheus/Grafana runtime-signal gate.
