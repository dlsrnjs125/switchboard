# EV-P09-K8S-001 — Kubernetes Runtime Recovery

- **Status:** `PASS`
- **Phase:** 9
- **Source:** `5938b8dd98f3d51029dc788a251401a0e99c3960` / working tree CLEAN
- **Captured:** 2026-10-01T02:52:17Z
- **Result:** bounded Kubernetes freshness recovery PASS; lagging-version Full Snapshot resync remains unverified

## Workload and boundary

Use the Phase 8 kind fixture with two Distribution replicas and authoritative Snapshot version 2. Each scenario creates a new Provider without an existing LKG and waits for a READY baseline before injecting its fault. Run one rolling restart and one forced loss of the Pod that owns the only connected probe stream. The latter is selected from per-Pod `switchboard_distribution_sessions_connected` metrics; zero or multiple owners invalidate the run.

Each probe performs 1,200 expected-false local evaluations at 100 ms intervals. Existing Provider timers record READY_STALE through READY after freshness confirmation. With local version equal to the authoritative version, reconnect delivers a Heartbeat rather than a duplicate Full Snapshot. The artifact includes full-Snapshot receipt and scheduled reconnect counts, completed stale intervals, and total stale milliseconds. This interval includes retry/backoff, connection, freshness confirmation and READY transition; it does not separately measure TCP/gRPC connection latency. EndpointSlice readiness is sampled approximately every second plus API latency. Only sampled availability is claimed.

## Reproduction

```sh
export PATH=/path/to/kind-helm-and-kubectl:$PATH
SWITCHBOARD_GRADLE=./load-test/phase-09/java21-gradle.sh make kind-e2e
make phase9-kubernetes-evidence
PYTHONDONTWRITEBYTECODE=1 python3 load-test/phase-09/kubernetes/verify_test.py
```

The harness explicitly selects `kind-switchboard-phase8` (or the configured kind cluster) regardless of the current kubeconfig context. Default output is ignored `build/phase-09-kubernetes`; set `SWITCHBOARD_K8S_ARTIFACT_DIR` to a new empty directory for each additional capture. Commit the harness and build matching images before immutable capture. Preserve `environment.txt`, Git status, image digests/resource limits, node fingerprint, baseline logs, runtime logs, sampled endpoints, fault identity, result and SHA256SUMS. Do not promote dirty captures.

## Gates

Both scenarios must have a READY/version-2 baseline, 1,200 correct evaluations, increasing probe-local monotonic timestamps, stable version 2, only READY/READY_STALE states, an observed reconnect, a completed positive stale interval, final READY, at least one sampled ready endpoint throughout, and two ready endpoints at convergence. Missing faults, wrong values, incomplete workloads and sampled full outages fail integrity regression.

## Limitations

One cycle per fault provides a bounded smoke measurement, not p50/p95 or a production SLO. Single-node PostgreSQL/Kafka/OIDC fixtures, endpoint polling gaps, forced process loss, local kind network and 120-second observation windows limit claims. Full Snapshot resync for a lagging Provider, production HPA, multi-zone recovery, soak and observability/alert calibration remain separate final gates.

## Observed result

| Scenario | Evaluations without errors | Scheduled reconnects | Completed stale intervals | Total READY_STALE time | New Full Snapshots | Minimum sampled ready endpoints |
| --- | --- | --- | --- | --- | --- | --- |
| Rolling update | 1,200 / 1,200 | 1 | 1 | 563.570875 ms | 0 | 2 |
| Connected Pod forced loss | 1,200 / 1,200 | 1 | 1 | 396.543376 ms | 0 | 1 |

Both Providers ended READY at version 2; the Deployment and sampled endpoints converged to two ready replicas. There were no excluded cycles. The equal-version protocol path confirms freshness through Heartbeat, so these numbers are **not Full Snapshot resync durations**. See [TRB-016](../../../troubleshooting/TRB-016-kubernetes-freshness-recovery-boundary.md).

Docker Engine 29.5.3 provided 10 CPUs and 8,321,515,520 bytes of memory. kind v0.33.0 used Kubernetes v1.37.0 with matching kubectl v1.37.0 and Helm v4.2.2. Per-container limits, immutable runtime image IDs and node topology are preserved in the raw fingerprint.

The initial exploratory collection was excluded after its running shell script was edited. The final capture above ran without source edits in a fresh output directory. Reusing a nonempty output directory is rejected.

## Artifact verification

```sh
(cd docs/evidence/phase-09/EV-P09-K8S-001/artifacts && shasum -a 256 -c SHA256SUMS)
python3 load-test/phase-09/kubernetes/verify.py docs/evidence/phase-09/EV-P09-K8S-001/artifacts
```

The recorded tree `f29b1c0a8c1642f565a4926b51d24a15ae0d875c` matches the source commit. Checksums cover raw workload logs, baseline prefixes, fault identity, endpoint samples, images, node/environment fingerprints and result.
