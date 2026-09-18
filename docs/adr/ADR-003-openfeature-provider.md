# ADR-003: Use an OpenFeature Provider as the application contract

- Status: Accepted
- Date: 2026-09-18
- Foundation: Application Client, Evaluation result, Evaluation boundary

## Context

Applications need a stable API that does not expose Switchboard transport or storage details. A proprietary application-facing API increases migration cost and makes instrumentation inconsistent.

## Decision

The supported Java integration is an OpenFeature Provider. Applications use the OpenFeature API; the provider manages Switchboard snapshot lifecycle and adapts Evaluation Core results to OpenFeature resolution details.

## Alternatives considered

- **Proprietary SDK API** — rejected because it creates unnecessary application lock-in.
- **OpenFeature hooks without a provider** — rejected because lifecycle and evaluation still need a provider boundary.
- **OFREP remote provider as the default** — deferred by ADR-014 because local evaluation is the default.

## Trade-offs

- **Benefit:** standardized application API, hooks, context, and telemetry integration.
- **Cost:** provider lifecycle and error semantics must track OpenFeature compatibility.

## Consequences

- Evaluation Core remains independent of OpenFeature.
- Phase 5 defines exact provider states, errors, and resolution-detail mapping.
- Provider compatibility is tested separately from evaluation semantics.

## Revisit conditions

- OpenFeature cannot represent required typed results or lifecycle behavior without unsafe proprietary leakage.
