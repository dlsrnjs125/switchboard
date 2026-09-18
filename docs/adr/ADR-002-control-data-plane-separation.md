# ADR-002: Separate the control plane and data plane

- Status: Accepted
- Date: 2026-09-18
- Foundation: Control plane, Distribution plane, `INV-MSG-002`, `INV-SDK-005`

## Context

Authoring and publication have different security, consistency, and scaling needs from runtime distribution and evaluation. Management failure must not directly stop application request processing.

## Decision

The Control Plane owns identity, authoring, publication, audit, and credential administration. The Data Plane owns published snapshot delivery, SDK connectivity, recovery, and local-evaluation support. They communicate only through committed published artifacts and explicit contracts.

## Alternatives considered

- **Single deployable service** — simpler initially, but shares failure and scaling boundaries.
- **Completely independent data store from day one** — stronger isolation, but premature replication and consistency complexity.

## Trade-offs

- **Benefit:** independent failure containment, authorization surfaces, and scaling.
- **Cost:** explicit contracts, propagation monitoring, and cross-plane integration tests.

## Consequences

- Control Plane outages block management but not LKG evaluation.
- MVP Distribution may bootstrap snapshots read-only from PostgreSQL.
- No Data Plane endpoint may mutate authored or published configuration.

## Revisit conditions

- Measured load or regional requirements justify a dedicated snapshot store, replica, or independently deployed regional Data Plane.
