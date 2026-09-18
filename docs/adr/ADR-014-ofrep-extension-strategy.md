# ADR-014: Treat OFREP remote evaluation as an extension

- Status: Accepted
- Date: 2026-09-18
- Foundation: Local evaluation, OpenFeature Provider, Distribution plane

## Context

OpenFeature Remote Evaluation Protocol (OFREP) can support clients that cannot host the evaluator. Making it the default would conflict with local evaluation's latency and failure-isolation goals, while ignoring it would reduce interoperability.

## Decision

The primary server-side Java path remains local evaluation through the Switchboard OpenFeature Provider. A future OFREP-compatible endpoint may be added as a Data Plane adapter over the same published snapshot and Evaluation Core semantics. It does not become a separate source of truth.

## Alternatives considered

- **OFREP as the only evaluation path** — rejected because every evaluation becomes a network dependency.
- **Never support remote evaluation** — rejected because some runtimes cannot safely embed the evaluator.
- **Custom remote API** — rejected where OFREP can express the required contract.

## Trade-offs

- **Benefit:** preserves the default failure boundary while leaving a standards-based extension path.
- **Cost:** local and remote adapters require conformance testing and compatible resolution semantics.

## Consequences

- Phase 0D does not need to expose OFREP as an MVP contract.
- A future endpoint reuses canonical snapshots, authorization, evaluation rules, and golden vectors.
- Remote clients receive an explicitly different availability and latency profile.

## Revisit conditions

- OFREP reaches required maturity and a supported client class cannot use local evaluation, with security and capacity evidence for remote operation.
