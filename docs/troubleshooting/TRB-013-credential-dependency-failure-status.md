# TRB-013 — Credential Dependency Failure Status

## Context

Every Distribution gRPC call passes through `CredentialServerInterceptor`, which parses the bearer token and asks PostgreSQL-backed `DistributionRepository` to authenticate the service credential. SDK retry behavior depends on the resulting gRPC status code.

## Symptom

During a 500-client reconnect run, every client recovered the new Snapshot but only 484 unique delivery ACKs reached the server. The missing ACKs did not retry even though PostgreSQL connection pressure, not invalid credentials, caused the authentication failure.

## Expected vs Actual

- Expected: malformed or rejected credentials return `UNAUTHENTICATED`; a credential-store dependency failure returns retryable `UNAVAILABLE`; an unexpected repository defect returns `INTERNAL`.
- Actual: the interceptor caught every runtime exception and converted both cases to `UNAUTHENTICATED`.

## Reproduction

Make `DistributionRepository.authenticate` throw while intercepting an otherwise valid bearer token. Before the fix, the call closed with `UNAUTHENTICATED`. A reconnect storm reproduced the same classification when PostgreSQL connection attempts timed out.

## Impact

Clients treated a transient dependency outage as a permanent authentication failure. Unary ACKs stopped retrying, and streaming reconnects could also report misleading credential failures during database pressure.

## Initial Hypothesis

The SDK ACK deadline and retry count first appeared too small. Increasing them did not explain why a subset of calls stopped retrying consistently.

## Evidence

`Phase9ReconnectStormEvidenceTest` recovered 500 Snapshots but timed out with 484 unique accepted ACK delivery IDs. Inspection of `CredentialServerInterceptor` showed one catch-all path. `CredentialServerInterceptorTest` now injects the repository failure and locks the expected status.

## Root Cause

Token syntax failure, credential rejection, and repository/runtime failure shared one broad `catch (RuntimeException)` block. The security boundary erased whether the fault belonged to the caller or to the server dependency.

## Fix

Parse the token separately, then call the repository in a second guarded block. Invalid syntax and an empty authentication result remain `UNAUTHENTICATED`; Spring `DataAccessException` failures become retryable `UNAVAILABLE`; unexpected repository runtime failures become `INTERNAL` instead of being hidden as dependency availability problems.

## Verification

Run `./gradlew :services:distribution:test --tests '*CredentialServerInterceptorTest*'` and `make phase9-reconnect-evidence`. Unit tests must preserve `UNAUTHENTICATED`, `UNAVAILABLE`, and `INTERNAL` as separate status classes, and all reconnect cohorts must produce one accepted unique delivery ID per recovered client.

## Trade-off

Returning `UNAVAILABLE` reveals that a server dependency is unhealthy, but it does not expose credential data or database details. Clients may add retry load, so exponential backoff and an attempt bound remain mandatory.

## Prevention

At authentication boundaries, classify caller-invalid, policy-denied, and dependency-unavailable outcomes separately. Add injected repository failure tests before relying on client retry policy.

## Related ADR / PR / Commit

- ADR-004, ADR-009
- PR #18
- `71d3dac374d99a6c6e1fdf454bbec178f13e09da`
- `EV-P09-RCN-001`

## Blog Candidate Summary

Why mapping every authentication exception to `UNAUTHENTICATED` can turn a recoverable database incident into client-visible credential failure.
