# Kubernetes Runbook

## Validate and install

Create the runtime Secret before installing the chart:

```bash
kubectl -n switchboard create secret generic switchboard-runtime \
  --from-literal=SWITCHBOARD_DB_USER='<database-user>' \
  --from-literal=SWITCHBOARD_DB_PASSWORD='<database-password>'

make helm-validate
helm upgrade --install switchboard infra/helm/switchboard \
  --namespace switchboard \
  --create-namespace \
  --wait \
  --timeout 5m
```

Do not place credential values in `values.yaml`, rendered manifests, evidence, or command history. In production, use an external secret manager or a separately managed Secret.

## Readiness and rollout

```bash
kubectl -n switchboard get deployment,pod,service,poddisruptionbudget
kubectl -n switchboard get endpointslice \
  -l kubernetes.io/service-name=switchboard-switchboard-distribution
kubectl -n switchboard rollout status deployment/switchboard-switchboard-control-plane
kubectl -n switchboard rollout status deployment/switchboard-switchboard-distribution
```

A Pod enters a Service endpoint only after application readiness succeeds. Control Plane readiness includes the database. Distribution readiness includes the database and an application readiness indicator that becomes unhealthy while its cache is unavailable. Liveness intentionally checks only process health so a dependency outage does not create a restart loop.

Rollouts use `maxUnavailable: 0` and `maxSurge: 1`. Keep at least two replicas in production; do not lower the Distribution PDB below one available replica without an approved maintenance plan.

## Graceful shutdown and drain

Kubernetes invokes a five-second pre-stop delay, removes an unready/terminating Pod from Service routing, and grants a 35-second termination window. Spring Boot has a 20-second graceful shutdown phase timeout. Monitor the terminating Pod and its replacement:

```bash
kubectl -n switchboard rollout restart deployment/switchboard-switchboard-distribution
kubectl -n switchboard rollout status deployment/switchboard-switchboard-distribution --timeout=180s
kubectl -n switchboard get pod -l app.kubernetes.io/component=distribution -w
```

If termination regularly reaches the grace-period limit, inspect active gRPC sessions, dependency latency, and shutdown logs before increasing the timeout. A larger timeout can hide a blocked drain.

## Scaling

Enable HPA only after installing metrics-server and confirming resource metrics:

```bash
helm upgrade switchboard infra/helm/switchboard \
  --namespace switchboard \
  --reuse-values \
  --set controlPlane.autoscaling.enabled=true \
  --set distribution.autoscaling.enabled=true
kubectl -n switchboard get hpa
```

CPU requests are required for utilization-based HPA calculations. The default minimum is two replicas. Validate session capacity, reconnect pressure, Kafka partitioning, database load, and topology spread before raising maxima.

On an HPA-enabled first install, the Deployment starts at `minReplicas`; later upgrades omit the replica field so Helm does not overwrite the HPA controller's current desired count.

## Distribution replica freshness

Every Distribution Pod expands the configured consumer-group prefix with its Pod name. Confirm that live replicas have different effective groups:

```bash
for pod in $(kubectl -n switchboard get pod -l app.kubernetes.io/component=distribution -o name); do
  kubectl -n switchboard exec "${pod}" -- printenv SWITCHBOARD_DISTRIBUTION_CONSUMER_GROUP
done
```

Do not configure all replicas with one identical effective group. Kafka would load-balance a publication to only one Pod even though caches and gRPC sessions are process-local. New Pod groups replay retained notifications from `earliest`, reconcile against authoritative PostgreSQL, and bootstrap newly connected clients from PostgreSQL. Configure notification retention and consumer-group metadata cleanup for the expected Pod churn rate.

## Network and telemetry

The baseline NetworkPolicy allows DNS and dependency ports but does not identify specific destinations. Apply cluster-specific egress selectors or service-mesh policy before production use. Preserve the application labels added by the chart; Prometheus or an OpenTelemetry agent should add bounded infrastructure dimensions such as namespace and Pod while avoiding tenant, user, client, credential, flag, or evaluation-context labels.

Use the [observability runbook](observability-runbook.md) to diagnose publish-to-SDK freshness. A local kind drill does not deploy the Phase 7 telemetry backend, so exporter connection errors are expected there and must not be interpreted as a successful telemetry integration test.

## Reproduce the local drill

```bash
make kind-e2e
```

The drill generates an ephemeral RSA key and synthetic operator token, performs an authenticated Publish through a local OIDC fixture, and uses a disposable `switchboard-phase8` kind cluster. Delete it after collecting the required evidence:

```bash
kind delete cluster --name switchboard-phase8
```
