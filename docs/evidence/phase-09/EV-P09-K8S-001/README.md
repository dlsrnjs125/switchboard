# EV-P09-K8S-001 — Kubernetes Runtime Recovery

- **Status:** `PLANNED`
- **Phase:** 9
- **Source:** pending clean immutable capture

## Workload and boundary

Use the Phase 8 kind fixture with two Distribution replicas and authoritative Snapshot version 2. Each scenario creates a new Provider without an existing LKG and waits for a READY baseline before injecting its fault. Run one rolling restart and one forced loss of the Pod that owns the only connected probe stream. The latter is selected from per-Pod `switchboard_distribution_sessions_connected` metrics; zero or multiple owners invalidate the run.

Each probe performs 1,200 expected-false local evaluations at 100 ms intervals. Existing Provider timers record READY_STALE through READY after freshness confirmation. With local version equal to the authoritative version, reconnect delivers a Heartbeat rather than a duplicate Full Snapshot. The artifact includes full-Snapshot receipt and scheduled reconnect counts, completed stale intervals, and total stale milliseconds. This interval includes retry/backoff, connection, freshness confirmation and READY transition; it does not separately measure TCP/gRPC connection latency. EndpointSlice readiness is sampled approximately every second plus API latency. Only sampled availability is claimed.

## Reproduction

```sh
PATH=/path/to/kind-and-helm:$PATH SWITCHBOARD_GRADLE=./load-test/phase-09/java21-gradle.sh make kind-e2e
make phase9-kubernetes-evidence
PYTHONDONTWRITEBYTECODE=1 python3 load-test/phase-09/kubernetes/verify_test.py
```

The harness explicitly selects `kind-switchboard-phase8` (or the configured kind cluster) regardless of the current kubeconfig context. Default output is ignored `build/phase-09-kubernetes`; set `SWITCHBOARD_K8S_ARTIFACT_DIR` to capture elsewhere. Commit the harness and build matching images before immutable capture. Preserve `environment.txt`, Git status, image digests/resource limits, node fingerprint, baseline logs, runtime logs, sampled endpoints, fault identity, result and SHA256SUMS. Do not promote dirty captures.

## Gates

Both scenarios must have a READY/version-2 baseline, 1,200 correct evaluations, increasing probe-local monotonic timestamps, stable version 2, only READY/READY_STALE states, an observed reconnect, a completed positive stale interval, final READY, at least one sampled ready endpoint throughout, and two ready endpoints at convergence. Missing faults, wrong values, incomplete workloads and sampled full outages fail integrity regression.

## Limitations

One cycle per fault provides a bounded smoke measurement, not p50/p95 or a production SLO. Single-node PostgreSQL/Kafka/OIDC fixtures, endpoint polling gaps, forced process loss, local kind network and 120-second observation windows limit claims. Full Snapshot resync for a lagging Provider, production HPA, multi-zone recovery, soak and observability/alert calibration remain separate final gates.
