# Phase 1 — Control Plane Domain & Persistence

## Outcome

Phase 1 turns the Phase 0 domain, contract, and relational baselines into an executable Spring Boot control plane. The implementation keeps tenant identity in every repository lookup and relies on composite PostgreSQL foreign keys as a second isolation boundary.

## Implemented scope

- Spring Boot 4.1 control-plane application on the repository Java 21 toolchain.
- Flyway `V001` migration for all 17 baseline tables, named keys/checks, query indexes, deferred relational checks, and immutability triggers.
- UUIDv7 identifiers for application-created aggregates.
- Tenant membership lookup and role policy for owner, maintainer, developer, viewer, and auditor roles.
- Project and environment creation plus project listing.
- Feature flag creation/listing and transactional draft revision creation.
- Typed variants, non-empty ordered targeting conditions, fixed variant results, and 10,000-basis-point rollout validation.
- OAuth2 resource-server authentication and contract-shaped JSON error responses.
- PostgreSQL 18 integration tests for migrations, final targeting-rule state, tenant isolation, archived-key non-reuse, published revision immutability, and cross-tenant credential rejection.
- JWT-to-HTTP integration tests covering the Security Filter, Controller validation, Tenant Scope, conflict mapping, and Repository path.

## REST endpoints

The following Phase 1 operations implement the existing `contracts/openapi/control-plane-v1.yaml` baseline:

| Method | Path | Minimum tenant role for writes |
| --- | --- | --- |
| `GET` | `/v1/tenants/{tenantKey}/projects` | Any member |
| `POST` | `/v1/tenants/{tenantKey}/projects` | `TENANT_OWNER`, `PROJECT_MAINTAINER` |
| `POST` | `/v1/tenants/{tenantKey}/projects/{projectKey}/environments` | `TENANT_OWNER`, `PROJECT_MAINTAINER` |
| `GET` | `/v1/tenants/{tenantKey}/projects/{projectKey}/flags` | Any member |
| `POST` | `/v1/tenants/{tenantKey}/projects/{projectKey}/flags` | Owner, maintainer, or developer |
| `POST` | `/v1/tenants/{tenantKey}/projects/{projectKey}/flags/{flagKey}/revisions` | Owner, maintainer, or developer |

The authenticated JWT subject is the `principal_id` matched against `tenant_members`. Missing membership, a cross-tenant identifier, and a role denied at the tenant boundary all return `RESOURCE_NOT_FOUND`, preventing resource-existence disclosure.

## Draft revision invariants

- Every revision contains at least one uniquely keyed variant and references one of them as its default.
- Variant JSON must match the flag's immutable `value_type`.
- Rule priorities and condition order values are non-negative and unique in their parent scope.
- Every targeting rule contains at least one condition, matching Snapshot Schema v1.
- `VARIANT` rules reference exactly one declared variant and have no allocations.
- `ROLLOUT` rules have unique declared variants and allocations totaling exactly 10,000 basis points.
- Deferred PostgreSQL triggers validate the complete final rule state when rules, conditions, or allocations change.
- Published revisions and their variants, rules, conditions, and allocations cannot be updated or deleted.
- Archived flag keys remain reserved and cannot be reused.

## Verification

```bash
./gradlew clean build
./gradlew :contracts:check
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The control-plane integration tests start an isolated PostgreSQL 18.6 container and execute the production Flyway migration before each test suite. They do not substitute H2 or mock persistence.

## Troubleshooting

- [TRB-002 — Targeting-rule final-state invariants](../../troubleshooting/TRB-002-targeting-rule-final-state-invariants.md)

## Deferred boundaries

Phase 1 intentionally does not implement publication, rollback, snapshot compilation/distribution, SDK evaluation, client credential issuance, or audit query APIs. Their tables and constraints exist because `V001` establishes the approved relational baseline; their application behavior remains owned by subsequent phases.
