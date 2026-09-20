# Switchboard

Switchboard is a multi-tenant feature flag and runtime configuration platform. It separates the control plane from the data plane so applications can evaluate configuration locally and continue from a Last Known Good snapshot during platform outages.

## Current phase

**Phase 1 — Control Plane Domain & Persistence**

The first executable control-plane slice is implemented: PostgreSQL migrations create the 17-table baseline, tenant-scoped repositories enforce ownership boundaries, and authenticated REST endpoints create projects, environments, feature flags, and validated draft revisions. Publishing, rollback, snapshot compilation, client credentials, and audit APIs remain assigned to later phases.

## Requirements

- Java 21
- Docker with Docker Compose

The Gradle wrapper is included; a system Gradle installation is not required.

## Modules

| Path | Responsibility |
| --- | --- |
| `services/control-plane` | Deployable control-plane application boundary |
| `services/distribution` | Deployable distribution-plane application boundary |
| `libs/evaluation-core` | Framework-independent Java evaluation library |
| `sdk/java-openfeature-provider` | Java/OpenFeature integration boundary |
| `demo/sample-service` | End-to-end sample application boundary |

Additional top-level boundaries are `contracts`, `docs`, `infra`, and `load-test`.

## Verify

```bash
make verify
make compose-up
docker compose -f infra/docker/docker-compose.yml ps
make compose-down
```

Run application skeletons with `make run-control-plane`, `make run-distribution`, or `make run-sample`.

## Package namespace

All Java code uses `io.github.dlsrnjs125.switchboard` as its root package.

## Documentation

- [Phase 0A foundation](docs/phases/phase-00-foundation/README.md)
- [Bootstrap verification](docs/evidence/phase-00a/bootstrap-verification.md)
- [Phase 0B glossary](docs/foundation/glossary.md)
- [Phase 0B domain boundaries](docs/foundation/domain-boundaries.md)
- [Phase 0B invariants](docs/foundation/invariants.md)
- [Phase 0B state transitions](docs/foundation/state-transitions.md)
- [Phase 0C Architecture Decision Records](docs/adr/README.md)
- [Phase 0D contract baseline](contracts/README.md)
- [Contract versioning](docs/architecture/contract-versioning.md)
- [Phase 0E PostgreSQL ERD](docs/data-model/erd.md)
- [Phase 0E table specification](docs/data-model/table-spec.md)
- [Phase 0E index strategy](docs/data-model/index-strategy.md)
- [Phase 0E migration policy](docs/data-model/migration-policy.md)
- [Phase 0F failure model](docs/operations/failure-model.md)
- [Phase 0F SLI/SLO design targets](docs/operations/sli-slo-design.md)
- [Phase 0F test strategy](docs/testing/test-strategy.md)
- [Phase 0F evidence policy](docs/evidence/README.md)
- [Phase 1 control-plane implementation](docs/phases/phase-01-control-plane/README.md)
