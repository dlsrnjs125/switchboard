# Switchboard

Switchboard is a multi-tenant feature flag and runtime configuration platform. It separates the control plane from the data plane so applications can evaluate configuration locally and continue from a Last Known Good snapshot during platform outages.

## Current phase

**Phase 0B — Domain Baseline**

The executable repository foundation is complete. This phase establishes the canonical vocabulary, domain ownership boundaries, invariants, and lifecycle transitions that later API, data model, event, and SDK contracts must share. Product behavior is documented but remains intentionally unimplemented.

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
