# Infrastructure

- `docker/`: local PostgreSQL and Kafka dependencies
- `helm/switchboard/`: Control Plane and Distribution workload chart with probes, disruption budgets, optional autoscaling, network policy, and secret injection boundaries
- `kubernetes/validate.sh`: Helm lint/render plus Kubernetes schema validation
- `kubernetes/phase-08-kind.sh`: repeatable multi-node kind rollout, restart, readiness, and SDK local-evaluation continuity drill
- `kubernetes/dev/`: local-only PostgreSQL/Kafka/synthetic OIDC plus Publish and SDK probe fixtures used by the Phase 8 drill; these are not production dependency charts
