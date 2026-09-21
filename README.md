# Switchboard

Switchboard is a multi-tenant feature flag and runtime configuration platform. It separates the control plane from the data plane so applications can evaluate configuration locally and continue from a Last Known Good snapshot during platform outages.

## Current phase

**Phase 5 — Java OpenFeature Provider**

The Java SDK exposes Switchboard through the standard OpenFeature API. It validates gRPC full Snapshots, persists a Last Known Good artifact with a forced temporary-file write and atomic same-directory replacement, swaps one immutable in-memory Snapshot reference, and delegates request-time decisions to the framework-independent Evaluation Core. Distribution loss transitions the provider to `READY_STALE` while local evaluation continues without network I/O.

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

Run the applications with `make run-control-plane`, `make run-distribution`, or `make run-sample`.

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
- [Phase 1 tenant-isolation evidence](docs/evidence/phase-01/EV-P01-TEN-001/README.md)
- [Phase 2 evaluation engine](docs/phases/phase-02-evaluation-engine/README.md)
- [Phase 2 evaluation evidence](docs/evidence/phase-02/EV-P02-EVL-001/README.md)
- [Phase 3 atomic snapshot publishing](docs/phases/phase-03-snapshot-publishing/README.md)
- [Phase 3 publication evidence](docs/evidence/phase-03/EV-P03-PUB-001/README.md)
- [Phase 4 distribution plane](docs/phases/phase-04-distribution-plane/README.md)
- [Phase 4 distribution data flow](docs/architecture/distribution-dataflow.md)
- [Phase 4 distribution evidence](docs/evidence/phase-04/EV-P04-DST-001/README.md)
- [Phase 5 Java OpenFeature Provider](docs/phases/phase-05-java-openfeature-provider/README.md)
- [Phase 5 SDK lifecycle](docs/architecture/sdk-lifecycle.md)
- [Phase 5 continuity evidence](docs/evidence/phase-05/EV-P05-SDK-001/README.md)
