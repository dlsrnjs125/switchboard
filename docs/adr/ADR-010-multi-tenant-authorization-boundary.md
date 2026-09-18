# ADR-010: Enforce multi-tenant authorization server-side

- Status: Accepted
- Date: 2026-09-18
- Foundation: Tenant, Principal, Role, Permission, `INV-TEN-001`–`INV-TEN-003`, `INV-FLG-002`, `INV-CRD-001`

## Context

Tenant identifiers are attacker-controlled when received in a path, query, or body. Identifier lookup without tenant qualification creates IDOR risk across control and distribution interfaces.

## Decision

Every request derives authorized tenant and project/environment scope from the authenticated principal. Repositories require tenant-qualified access paths, resources cannot cross tenants, and failures do not disclose cross-tenant existence. Human OIDC principals and service credentials use separate authentication flows but the same explicit authorization model.

## Alternatives considered

- **Trust client-provided tenant ID** — rejected as an authorization bypass.
- **Globally unique IDs without scoped queries** — rejected because uniqueness is not authorization.
- **PostgreSQL RLS as the only control** — deferred as defense in depth; application authorization remains mandatory.

## Trade-offs

- **Benefit:** uniform isolation and testable authorization boundaries.
- **Cost:** scoped repository APIs, composite constraints, and pervasive negative tests.

## Consequences

- Cross-tenant read/write and credential tests are release gates.
- `Platform Admin` authority is a separate platform contract, not an implicit tenant role.
- Audit records include derived scope and acting principal.

## Revisit conditions

- Add RLS, tenant keys, or dedicated tiers as defense in depth; never relax server-derived scope.
