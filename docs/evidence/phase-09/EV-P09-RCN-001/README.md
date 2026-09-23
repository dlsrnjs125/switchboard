# EV-P09-RCN-001 — SDK Reconnect Storm

- **Phase:** 9 — Performance & Operations Evidence
- **Status:** `PASS`
- **Evidence ID:** `EV-P09-RCN-001`
- **Git commit:** `71d3dac374d99a6c6e1fdf454bbec178f13e09da`
- **Executed at:** `2026-09-23T06:26:35Z`
- **Related:** `FM-RCN-001`, ADR-004, ADR-009, TRB-012, TRB-013
- **Command:** `make phase9-reconnect-evidence`
- **Artifact path:** `docs/evidence/phase-09/EV-P09-RCN-001/artifacts/`

## Claim

The opt-in workload measures how 100, 500, and 1,000 already-connected logical Java SDK transports recover after the Distribution gRPC server is stopped and restarted on the same endpoint. It records first reconnect backoff+jitter distribution, outage-to-new-Snapshot recovery percentiles, server-accepted ACK completion, session admission rejection, and PostgreSQL authentication/bootstrap query amplification.

This is a bounded local reconnect experiment. It is not a production fleet, WAN, multi-replica, load-balancer, TLS, or PostgreSQL saturation claim.

## Environment fingerprint

`artifacts/environment.txt` records the source commit/index tree/status, host and Java details, Docker resources, and dependency image baselines. `artifacts/git-status.txt` records `CLEAN`; the captured commit tree and index tree are identical, so the run qualifies as immutable-source Evidence.

## Workload

- cohorts: 100, 500, and 1,000 logical transports;
- one shared Netty channel with one authenticated gRPC stream per logical client and separate bounded reconnect/control-RPC schedulers;
- one PostgreSQL 18.6 Testcontainer, an explicit 16-connection Hikari pool, and one Distribution server process;
- initial full Snapshot application before fault injection; ACK measurement is reserved for the recovered Snapshot;
- seed version `N+1` directly without notifying the running coordinator, then stop and restart the Distribution server on the same port;
- configured initial backoff 500 ms, maximum 5 seconds, jitter ±40%;
- exact recovery requirement: every client receives version `N+1`, every ACK is accepted, and no admission rejection or unexpected resync occurs;
- authentication and authoritative Snapshot queries counted at the repository boundary.

## Expected

- every cohort recovers all clients within the five-minute test deadline;
- first backoff requests are dispersed inside the configured jitter envelope;
- zero session admission rejection and zero workload errors;
- authentication and authoritative Snapshot bootstrap amplification recorded per client and bounded rather than assumed to be exactly one attempt;
- no client observes a regressive heartbeat or credential-revocation event.

## Observed

The clean-source run captured all cohorts without an admission rejection, workload error, stream error callback, missing recovery, or missing unique ACK.

| Clients | Recovery p50 | p95 | p99 | Max | Reconnect auth/client | Bootstrap/client |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 553.591 ms | 741.530 ms | 767.963 ms | 768.040 ms | 2.000 | 1.000 |
| 500 | 646.017 ms | 830.081 ms | 839.874 ms | 852.432 ms | 2.000 | 1.000 |
| 1,000 | 978.599 ms | 1,270.886 ms | 1,292.648 ms | 1,299.004 ms | 2.000 | 1.000 |

The first scheduled delays observed at `SwitchboardProviderTelemetry.reconnectScheduled` remained inside the configured 300–700 ms jitter window: p50 was 498–499 ms, p95 671–679 ms, p99 694–695 ms, and maximum 699 ms. The deterministic random source makes the cohort reproducible, and the samples come from the actual production scheduling path. Reconnect authentication is exactly two repository calls per recovered client in this topology—one Subscribe and one ACK—while authoritative Snapshot bootstrap is exactly one.

Compared with the preceding dirty-source candidate, clean-source recovery p99 changed by +1.48% at 100 clients, +10.82% at 500 clients, and +43.17% at 1,000 clients. Both runs satisfy the predeclared safety/completion gate, but the scale-dependent spread means a single local run must not be treated as a stable production latency SLO. Multi-run confidence intervals and controlled resource isolation remain future measurement work.

## Result

`PASS`. The source fingerprint is clean and immutable, every cohort recovered within the five-minute deadline, every recovered delivery produced an accepted unique ACK, admission rejection and workload errors were zero, and query amplification stayed inside the declared bounds.

## Integrity

`artifacts/SHA256SUMS` covers the raw JSON and source fingerprint files. Phase 9 verification checks the manifest, includes this bundle in the all-bundle secret guard, and exercises checksum tamper detection.

## Limitations

- Logical clients share one Netty channel plus separate bounded reconnect and control-RPC schedulers to avoid measuring 1,000 JVM thread/channel allocations while preserving executor isolation.
- The workload restarts an in-process gRPC server, not a Kubernetes Pod, load balancer, or process namespace.
- PostgreSQL query counts are repository method calls; the 16-connection pool bounds connection pressure, but database CPU, lock contention, pool wait percentiles, and wire-level query timing are not measured here.
- Authentication uses one synthetic credential and BCrypt cost configured by the test fixture.
- READY_STALE evaluation continuity, Kubernetes duration, sustained slow-client backpressure, and multi-fault recovery remain separate Phase 9 gates.
