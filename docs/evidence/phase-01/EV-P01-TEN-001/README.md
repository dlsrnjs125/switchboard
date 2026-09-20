# EV-P01-TEN-001 — Control Plane Tenant Isolation and Persistence

- **Status:** PASS
- **Phase:** Phase 1 — Control Plane Domain & Persistence
- **Git commit:** `492f972ab41526df6c7931326ea8dcca459ce935`
- **Executed at:** 2026-09-20T03:45:25Z
- **Owner:** Switchboard maintainers
- **Related:** Phase 0B invariants; ADR-001, ADR-003, ADR-005; OpenAPI v1; Phase 0E table specification; `FM-CP-DB-001`

## Claim

The Phase 1 control plane can migrate an empty PostgreSQL 18 database, persist a tenant-scoped project/environment/flag/draft graph, and reject representative cross-tenant or immutable-state violations at the service and database boundaries.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS 26.6.2 (25G83), arm64 |
| JDK | OpenJDK 17.0.19 used in a temporary compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| Docker/Compose | Docker 29.5.3; Docker Compose 5.1.4 |
| PostgreSQL | `postgres:18.6-alpine` Testcontainers image |
| Spring Boot | 4.1.1 |
| Testcontainers | 2.0.5 |

## Topology and workload

- One control-plane test process and one isolated PostgreSQL container.
- A fresh Flyway migration is applied before the test suite.
- Test data covers two tenants, owners and viewer membership, projects, environments, boolean flags, variants, conditions, and rollout allocations.
- No Kafka or distribution-plane process participates in this Phase 1 verification.

## Commands

The host did not contain JDK 21, so verification copied the working tree to an isolated temporary directory and changed only that copy's Gradle toolchain declaration from 21 to 17:

```bash
./gradlew :services:control-plane:test --no-configuration-cache --console=plain
./gradlew clean build :contracts:check --no-configuration-cache --console=plain
docker compose -f infra/docker/docker-compose.yml config --quiet
```

## Expected

- [x] Flyway creates exactly the 17 Phase 0E baseline tables.
- [x] A tenant owner can create a project, environment, typed flag, and valid draft revision.
- [x] A principal cannot discover another tenant through a scoped lookup.
- [x] A viewer cannot perform flag-authoring writes.
- [x] Variant values that do not match the flag value type are rejected.
- [x] Rules without conditions are rejected by both the application and deferred database validation.
- [x] A zero-allocation `ROLLOUT` rule cannot commit.
- [x] A `VARIANT` rule carrying rollout allocations cannot commit.
- [x] An archived flag key cannot be reused.
- [x] Children of a published revision cannot be mutated.
- [x] A service credential cannot reference another tenant's client application.
- [x] JWT authentication, HTTP DTO validation, cross-tenant concealment, and duplicate-key conflict mapping pass through the complete web stack.
- [x] The complete multi-module build and executable contract validation pass.
- [x] Docker Compose configuration remains valid.

## Observed

- The control-plane suite completed 13 tests with 0 failures.
- The full Gradle build completed 72 tasks successfully.
- The OpenAPI, protobuf, schema, and example contract checks passed unchanged.
- The Compose configuration check exited successfully.
- PostgreSQL enforced non-empty rule conditions, final `VARIANT`/`ROLLOUT` allocation state, composite tenant ownership, immutable published children, and permanent public-key uniqueness during the executable test paths.
- The Spring Security Filter → JWT subject → Controller → Service → Repository path produced the expected 401, 201, 404, 409, and 400 responses.

## Result

The observed results meet the Phase 1 persistence and tenant-isolation criteria exercised by this test set. The application and database layers independently reject the representative boundary violations listed above.

## Artifact paths

- `services/control-plane/build/reports/tests/test/index.html`
- `services/control-plane/src/test/java/io/github/dlsrnjs125/switchboard/controlplane/ControlPlaneIntegrationTest.java`
- `services/control-plane/src/test/java/io/github/dlsrnjs125/switchboard/controlplane/DatabaseInvariantIntegrationTest.java`
- `services/control-plane/src/main/resources/db/migration/V001__create_control_plane_schema.sql`

## Limitations

- Local verification used JDK 17 because JDK 21 was unavailable on the host. Java 21 verification remains a CI requirement.
- This run is functional verification, not a latency, throughput, soak, recovery-time, or production-readiness claim.
- Publication, snapshot compilation, credential issuance, audit querying, and distribution behavior remain outside Phase 1.
