# EV-P08-K8S-001 — Kubernetes Multi-Replica Rollout and Recovery

- **Status:** PASS
- **Phase:** Phase 8 — Kubernetes and Helm
- **Baseline commit:** `abecfc1c64687dda85a8dfd2622e915787ae2c4a` with the uncommitted Phase 8 worktree described here
- **Executed at:** 2026-09-21T09:05:53Z
- **Owner:** Switchboard maintainers
- **Related:** Phase 8 completion criteria, `FM-DST-001`, `FM-RCN-001`, `EV-P07-OBS-001`

## Claim

The chart renders schema-valid hardened workloads with two Control Plane and two Distribution replicas. An authenticated Control Plane Publish commits and reaches Kafka, Distribution, and the Java Provider. During a Distribution rolling update the Provider continues local evaluation from its active Snapshot, and after one Distribution Pod is forcibly deleted at least one ready Service endpoint remains while the Deployment converges back to two ready replicas.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host | Darwin arm64 |
| Docker Engine | 29.5.3 |
| kind | v0.33.0 |
| Kubernetes server | v1.37.0, linux/arm64 |
| kubectl client | v1.34.1 |
| Helm | v4.2.2 |
| kubeconform | v0.7.0, strict Kubernetes 1.31 schema validation |
| Java configuration | Repository targets Java 21; images use Java 21 JRE |
| PostgreSQL/Kafka fixtures | PostgreSQL 18.6; Kafka 4.3.1 KRaft |
| Cluster | 1 control-plane node and 2 worker nodes |

## Topology and workload

- Control Plane: two replicas, ClusterIP port `8080`.
- Distribution: two replicas, ClusterIP ports `8081` and `9090`.
- Placement: required hostname topology spread with `maxSkew: 1`.
- Availability: rolling update `maxUnavailable: 0`, `maxSurge: 1`; PDB `minAvailable: 1`.
- Publish workload: one synthetic RS256 operator token, one draft boolean flag, one authenticated Publish, one Snapshot, and one outbox delivery.
- Probe workload: one Java Provider client, 600 boolean evaluations at 100 ms intervals, expected result `checkout-v2=true` for synthetic `plan=premium` context.
- Faults: rolling restart while the probe runs, followed by direct deletion of one ready Distribution Pod.

## Commands

```bash
make helm-validate
make kind-e2e
kubectl --context kind-switchboard-phase8 -n switchboard get deployment,pod,service,pdb
kubectl --context kind-switchboard-phase8 -n switchboard get endpointslice \
  -l kubernetes.io/service-name=switchboard-switchboard-distribution
```

## Expected and observed

- [x] `helm lint` succeeds.
- [x] Default and HPA-enabled chart renders pass strict kubeconform validation: 10/10 and 12/12 resources respectively.
- [x] Control Plane and Distribution reach `2/2` Ready.
- [x] The Distribution Service exposes exactly two ready endpoints in steady state.
- [x] The authenticated Publish returns Snapshot version 1 and creates one outbox event.
- [x] Kafka acknowledges the event and the outbox row records `published_at` before the SDK probe starts.
- [x] The probe completes all 600 evaluations with `checkout-v2=true` while Distribution performs a rolling restart.
- [x] Direct deletion of one ready Distribution Pod leaves the other replica available.
- [x] The deleted Pod is replaced and Distribution returns to `2/2` Ready with two Service endpoints.
- [x] Both application PDBs report one allowed disruption after recovery.

The observed database state was environment version `1`, one outbox event, and one delivered outbox event. The final Distribution endpoints were `10.244.2.24` and `10.244.1.17`. The replacement Pod became Ready without a manual application restart or Snapshot reseed.

After the observations were captured, the disposable `switchboard-phase8` kind cluster and its nodes were deleted successfully.

## Result

PASS. The rendered baseline satisfies the declared workload, security, health, disruption, and scaling configuration checks. The runtime drill confirms the authenticated Publish → outbox/Kafka → Distribution → Java Provider path, multi-replica placement, readiness-based endpoint publication, continuous local SDK evaluation across a rolling restart, and convergence after one Distribution Pod loss.

## Artifact paths

- `infra/helm/switchboard/`
- `infra/kubernetes/validate.sh`
- `infra/kubernetes/phase-08-kind.sh`
- `infra/kubernetes/dev/`
- Gradle test reports under `*/build/test-results/test/`

## Limitations

- The evidence was captured before the Phase 8 commit existed, so it records the immutable parent commit plus the exact worktree paths instead of claiming an unavailable commit SHA. CI on the pushed commit remains the merge gate.
- The kubectl client is three minor releases behind the server; basic resource and rollout commands succeeded, but this exceeds the generally supported client/server skew.
- The local PostgreSQL and Kafka fixtures are single replicas. This run does not prove dependency high availability, multi-zone survival, node-drain eviction behavior, or production NetworkPolicy reachability.
- HPA resources were linted and schema-validated but not behavior-tested because the kind fixture does not install metrics-server or generate scaling load.
- The local drill does not deploy the OpenTelemetry backend. Phase 7 telemetry remains wired through ConfigMap endpoints and bounded application labels, while end-to-end telemetry delivery is covered by `EV-P07-OBS-001` rather than this run.
- This is a bounded functional drill, not a soak, throughput, tail-latency, or maximum-session capacity result.
