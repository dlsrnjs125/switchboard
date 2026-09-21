# EV-P08-K8S-001 — Kubernetes Multi-Replica Rollout and Recovery

- **Status:** PASS
- **Phase:** Phase 8 — Kubernetes and Helm
- **Baseline commit:** `2728ead72a9bb414ad51e07c9b3f4f6f4bf9870a` with the uncommitted multi-replica review fixes described here
- **Executed at:** 2026-09-21T09:29:56Z
- **Owner:** Switchboard maintainers
- **Related:** Phase 8 completion criteria, `FM-DST-001`, `FM-RCN-001`, `EV-P07-OBS-001`

## Claim

The chart renders schema-valid hardened workloads with two Control Plane and two Distribution replicas. Every Distribution Pod has an independent Kafka notification group, so two already-connected SDKs pinned to different replicas both advance from authoritative Snapshot version 1 to version 2 after publication. During a rolling update the Provider continues local evaluation from its active Snapshot, and after one Distribution Pod is forcibly deleted at least one ready Service endpoint remains while the Deployment converges back to two ready replicas.

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
- Placement: hostname topology spread with `maxSkew: 1` and `ScheduleAnyway` fallback.
- Availability: rolling update `maxUnavailable: 0`, `maxSurge: 1`; PDB `minAvailable: 1`.
- Notification topology: one Pod-scoped Kafka consumer group per Distribution replica.
- Publish workload: one synthetic RS256 operator token, two draft revisions, two authenticated Publishes, two Snapshots, and two acknowledged outbox deliveries.
- Replica convergence workload: two Java Provider clients, each pinned through a dedicated Service to a different Distribution Pod; both wait for versions `1,2` and verify the version 2 value `checkout-v2=false`.
- Rollout workload: one Java Provider client, 600 boolean evaluations at 100 ms intervals, expected version 2 result `checkout-v2=false` for synthetic `plan=premium` context.
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
- [x] The two Distribution Pods expose different effective Kafka consumer groups derived from their Pod names.
- [x] Dedicated probe Services each resolve to exactly one different ready Distribution Pod.
- [x] Two authenticated Publishes return Snapshot versions 1 and 2 and create two outbox events.
- [x] Kafka acknowledges both events and both outbox rows record `published_at`.
- [x] Both already-connected, Pod-pinned SDKs observe version 1 and then version 2 without reconnecting to the other replica.
- [x] Both pinned SDKs evaluate `checkout-v2=false` after applying version 2.
- [x] The rollout probe completes all 600 evaluations with `checkout-v2=false` while Distribution performs a rolling restart.
- [x] Direct deletion of one ready Distribution Pod leaves the other replica available.
- [x] The deleted Pod is replaced and Distribution returns to `2/2` Ready with two Service endpoints.
- [x] Both application PDBs report one allowed disruption after recovery.

The observed database state was environment version `2`, two outbox events, and two delivered outbox events. The two recovered Distribution groups were `switchboard-distribution-v1-switchboard-switchboard-distribution-79bc9dd4ff-cc2tn` and `switchboard-distribution-v1-switchboard-switchboard-distribution-79bc9dd4ff-gqw44`. The final Distribution endpoints were `10.244.1.10` and `10.244.2.8`. The replacement Pod became Ready without a manual application restart or Snapshot reseed.

After the observations were captured, the disposable `switchboard-phase8` kind cluster and its nodes were deleted successfully.

## Result

PASS. The rendered baseline satisfies the declared workload, security, health, disruption, and scaling configuration checks. The runtime drill confirms that Kafka freshness notification is broadcast through independent Pod groups to each process-local cache and its existing SDK session. It also confirms the authenticated Publish → outbox/Kafka → Distribution → Java Provider path, readiness-based endpoint publication, continuous local SDK evaluation across a rolling restart, and convergence after one Distribution Pod loss.

## Artifact paths

- `infra/helm/switchboard/`
- `infra/kubernetes/validate.sh`
- `infra/kubernetes/phase-08-kind.sh`
- `infra/kubernetes/dev/`
- Gradle test reports under `*/build/test-results/test/`

## Limitations

- The initial Phase 8 commit passed Java 21 CI Run #41. The multi-replica review fixes in this rerun are still uncommitted, so Java 21 CI must run again after they are pushed.
- The kubectl client is three minor releases behind the server; basic resource and rollout commands succeeded, but this exceeds the generally supported client/server skew.
- The local PostgreSQL and Kafka fixtures are single replicas. This run does not prove dependency high availability, multi-zone survival, node-drain eviction behavior, or production NetworkPolicy reachability.
- HPA resources were linted and schema-validated but not behavior-tested because the kind fixture does not install metrics-server or generate scaling load.
- Pod-scoped Kafka groups intentionally trade shared-group load balancing for per-replica notification delivery. This run does not quantify inactive group metadata growth or validate the production broker cleanup policy.
- The local drill does not deploy the OpenTelemetry backend. Phase 7 telemetry remains wired through ConfigMap endpoints and bounded application labels, while end-to-end telemetry delivery is covered by `EV-P07-OBS-001` rather than this run.
- This is a bounded functional drill, not a soak, throughput, tail-latency, or maximum-session capacity result.
