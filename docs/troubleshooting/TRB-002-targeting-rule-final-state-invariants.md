# TRB-002 — Targeting-rule Final-state Invariants

## Context

Phase 1 persisted variants, targeting rules, conditions, and rollout allocations as a multi-table draft graph. The service validated the aggregate before writing, while PostgreSQL was also expected to reject invalid direct or partial mutations.

## Symptom

An empty condition list was accepted by application validation, and the database constraint checked only rollout allocation totals. A direct database write could therefore commit a targeting rule whose final state did not match its result type.

## Expected vs Actual

- Expected: every rule has at least one condition; `VARIANT` has no allocations; `ROLLOUT` has allocations totaling exactly 10,000 basis points.
- Actual: some invalid final states could bypass one enforcement layer or fail during valid multi-row assembly before the transaction was complete.

## Reproduction

Create a rule with no conditions, add allocations to a `VARIANT` rule, or directly insert a `ROLLOUT` rule without a complete 10,000-basis-point allocation set. Exercise both service and direct SQL paths inside a transaction.

## Impact

Invalid authoring state could reach publication and produce evaluation semantics that disagreed with the contract. Immediate row-level checks would also reject valid graphs while their children were still being assembled.

## Initial Hypothesis

Validating only individual rows and one aggregate sum was assumed to be equivalent to validating the committed rule graph.

## Evidence

Phase 1 review added HTTP, service, and direct-database negative tests covering empty conditions, result/allocation shape, tenant concealment, and deferred final-state validation.

## Root Cause

The invariant belongs to an aggregate whose valid state is known only at transaction completion. The original validation was incomplete and placed too much trust in the application write path.

## Fix

- reject empty condition collections in `ControlPlaneService`;
- add deferred constraint triggers for rule, condition, and allocation mutations;
- evaluate both previous and current rule IDs when children move;
- assert the complete final state at transaction commit.

## Verification

Run `./gradlew :services:control-plane:test`. `DatabaseInvariantIntegrationTest`, `ControlPlaneIntegrationTest`, and `ControlPlaneHttpSecurityIntegrationTest` must accept valid aggregate assembly and reject every invalid final state.

## Trade-off

Deferred triggers add database complexity and commit-time work, but they protect non-HTTP writers and allow valid multi-row transactions to be assembled in any safe order.

## Prevention

Classify cross-row rules as final-state invariants during schema design. Test each invariant through the API and by direct SQL, including updates that move a child between parents.

## Related ADR / PR / Commit

- ADR-007, ADR-010
- PR #7
- `492f972ab41526df6c7931326ea8dcca459ce935`
- `EV-P01-TEN-001`

## Blog Candidate Summary

Why aggregate invariants need deferred database constraints instead of only DTO validation or immediate row triggers.
