# ADR-011: Use a monorepo with deployable module boundaries

- Status: Accepted
- Date: 2026-09-18
- Foundation: Domain boundaries, Control plane, Distribution plane, Evaluation boundary

## Context

Control Plane, Distribution, Evaluation Core, Java Provider, and sample application evolve together through shared contracts, but they have different runtime and dependency constraints.

## Decision

Keep one version-controlled repository with explicit Gradle modules. Control Plane, Distribution, and sample service remain independently deployable applications. Evaluation Core and Java Provider remain libraries, with dependency rules matching domain boundaries.

## Alternatives considered

- **Repository per component** — rejected initially because cross-contract changes and bootstrap overhead dominate.
- **Single application module** — rejected because it hides deployable and failure boundaries.
- **Unstructured shared library** — rejected because it permits dependency leakage.

## Trade-offs

- **Benefit:** atomic contract changes, unified CI, and discoverable architecture.
- **Cost:** broader CI impact and the need for dependency-boundary enforcement.

## Consequences

- Module names express product responsibility, not framework layer.
- Deployables may release independently once versioning automation exists.
- Evaluation Core cannot depend on frameworks, services, transports, or OpenFeature.

## Revisit conditions

- Ownership, release cadence, or repository scale creates measured coordination cost that exceeds atomic-change benefits.
