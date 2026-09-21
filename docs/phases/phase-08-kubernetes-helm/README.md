# Phase 8 — Kubernetes and Helm

## Outcome

Phase 8 packages the Control Plane and Distribution applications as hardened Kubernetes workloads. The Helm chart keeps runtime credentials outside the release, starts at two replicas per service, removes unready pods from Service endpoints, and provides bounded disruption and scaling controls.

## Implemented scope

- Java 21 runtime images for Control Plane, Distribution, and the end-to-end SDK probe, all running as UID/GID `10001`.
- One Helm chart with Deployments, ClusterIP Services, ServiceAccounts, ConfigMap injection, existing-Secret references, topology spread, PodDisruptionBudgets, optional HPAs, and a default-deny workload NetworkPolicy.
- Startup, readiness, and liveness probes on Spring Boot Actuator health groups. Distribution also exposes the gRPC service on port `9090`.
- Restricted container security contexts: no privilege escalation, all capabilities dropped, read-only root filesystems, and writable `/tmp` backed by `emptyDir`.
- Thirty-five-second termination windows and five-second pre-stop drain delays for rolling replacement.
- Strict `helm lint`, default/HPA render validation, and Kubernetes schema validation in CI.
- A three-node kind drill with two application replicas, a continuous Java Provider evaluation probe, a rolling update, and forced Distribution Pod deletion.

## Runtime configuration boundary

The chart renders non-secret connection and telemetry settings into a ConfigMap. Database credentials come from an existing Secret named by `runtimeSecret.name`; the chart never renders or owns the credential values. Production platforms should provide the Secret through their normal secret manager integration.

The default NetworkPolicy permits inbound application ports and egress to DNS plus the declared dependency ports. It is a portable baseline, not a cluster-specific trust boundary: production overlays should restrict egress destinations by namespace, workload identity, CIDR, or service mesh policy.

## Verification

```bash
make helm-validate
make kind-e2e
```

`make helm-validate` verifies both the default two-replica topology and the HPA-enabled variant. `make kind-e2e` builds the runtime images, creates a three-node kind cluster, deploys local PostgreSQL, Kafka, and synthetic OIDC fixtures, installs the chart, and checks:

1. both application Deployments reach `2/2` Ready;
2. both Distribution replicas appear as ready Service endpoints;
3. an authenticated Control Plane Publish commits Snapshot version 1 and its outbox record receives a Kafka broker acknowledgement;
4. the Distribution and Java Provider converge on the published Snapshot;
5. 600 local SDK evaluations remain `true` while Distribution rolls;
6. deleting one Distribution Pod leaves at least one ready endpoint;
7. the Deployment converges back to two ready replicas and endpoints.

See [EV-P08-K8S-001](../../evidence/phase-08/EV-P08-K8S-001/README.md) for the recorded run and [Kubernetes runbook](../../operations/kubernetes-runbook.md) for operations.

## Deliberate limits

- PostgreSQL and Kafka in `infra/kubernetes/dev` are single-node test fixtures, not production dependency charts.
- The chart does not create an Ingress, TLS identity, external Secret, database, Kafka cluster, OpenTelemetry backend, or metrics-server.
- HPA manifests are schema-validated; scaling behavior requires a cluster with metrics-server and a representative load.
- The kind drill proves rolling replacement and a direct Pod-loss case. It does not prove multi-zone failure, node-drain eviction behavior, dependency high availability, or long-duration load.
