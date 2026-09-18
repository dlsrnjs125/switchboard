# ADR-001: Use local evaluation by default

- Status: Accepted
- Date: 2026-09-18
- Foundation: Local evaluation, Evaluation boundary, `INV-SDK-005`

## Context

Feature evaluation is on the application request path. A remote call per evaluation would add latency, consume platform capacity, expose evaluation context, and couple application availability to Switchboard availability.

## Decision

Server-side applications evaluate flags inside their process from an immutable active snapshot. The Evaluation Core is deterministic and performs no network or database I/O. Distribution is asynchronous and is not part of the evaluation critical path.

## Alternatives considered

- **Remote evaluation only** — rejected because every request inherits network latency and platform failure.
- **Application-specific evaluation logic** — rejected because semantics would drift between services.
- **Hybrid selected per flag in MVP** — rejected because it doubles contract and operational complexity before measurements exist.

## Trade-offs

- **Benefit:** low latency, failure isolation, context privacy, and horizontal scalability.
- **Cost:** SDK compatibility, snapshot distribution, and cross-runtime conformance become mandatory.

## Consequences

- Evaluation semantics require versioned snapshot contracts and golden vectors.
- The Java SDK owns snapshot lifecycle but delegates decisions to the Evaluation Core.
- Runtime telemetry must not require synchronous platform calls.

## Revisit conditions

- A supported client cannot safely host the evaluator, or a use case requires centrally held data that cannot enter an evaluation context.
