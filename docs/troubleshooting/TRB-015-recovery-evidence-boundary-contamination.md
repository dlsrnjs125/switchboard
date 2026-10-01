# TRB-015 — Recovery Evidence Boundary Contamination

## Context

`EV-P09-RCV-001` measures 30 recovery cycles for Kafka/outbox and credential-dependency failures. A recovery percentile is valid only when each sample crosses the declared fault boundary and retains the runtime state that controls recovery.

## Symptom

The first Kafka workload rewrote `outbox_events.next_attempt_at` into the past before starting its timer. The credential workload carried a cumulative authentication-failure baseline across cycles and updated it before clearing the previous fault.

## Expected vs Actual

- Expected: Kafka recovery includes the retry time persisted by `OutboxRelay`, and every credential sample proves a new authentication failure from its own injected outage.
- Actual: Kafka measured an immediately eligible manual relay call, while a late failure from one credential cycle could satisfy the next cycle's cumulative-counter wait.

## Reproduction

Persist an incorrect future `next_attempt_at`; the original Kafka workload overwrites it and still reports a fast recovery. For credentials, let one reconnect fail again after the shared baseline is captured but before the fault is cleared; the next cycle can observe the carried count and clear its outage without a new authentication attempt.

## Impact

The raw result could report 30 successful recoveries while measuring a narrower boundary than `SLI-OBX-001`, or while mixing real credential-failure recovery samples with ordinary reconnect samples. Checksums and source fingerprints would faithfully preserve the wrong experiment.

## Initial Hypothesis

A shortcut used by functional outbox tests was assumed to be safe in a timing workload, and a monotonically increasing asynchronous counter was assumed to identify the current cycle.

## Evidence

PR #20 review traced both execution orders in source. The corrected clean-source run at `4dd4c568b3dd6f2a7284b30fae3d3530bfb5b426` retained the two-second first retry and produced Kafka p95 2,070.396 ms. Its credential result recorded one new unavailable response in each of 30 cycles.

## Root Cause

The harness modified the state under test instead of observing it, and used a cross-cycle cumulative counter without taking the baseline at the fault boundary. Both are measurement-boundary contamination: the test can satisfy its assertions without traversing the state transition claimed by the Evidence.

## Fix

- use a progressing system clock for `OutboxRelay`;
- preserve the failed delivery's stored `next_attempt_at` and poll until it becomes eligible, broker ACK completes, and `published_at` is persisted;
- record that all 30 retry schedules were preserved and that the harness performed zero application retry-state mutations;
- capture `failuresBeforeFault` immediately before each credential outage;
- keep the dependency unavailable until a new failure, `READY_STALE`, and LKG evaluation are observed for that cycle;
- record `failuresPerCycle`, `faultedCycles`, and actual recovered streams;
- make the Evidence verifier reject missing retry-state and per-cycle fault proof even when checksums are valid.

## Verification

Run `make phase9-recovery-evidence`, then `./load-test/phase-09/verify-integrity-test.sh`. The raw Kafka outcome must show 30 preserved schedules and zero state mutations. Credential output must contain 30 per-cycle counts, each at least one, plus 30 faulted and recovered cycles. A checksum-valid mutation of the retry-state claim must fail verification.

## Trade-off

Including the production two-second first retry adds about one minute to a 30-cycle local run. The relay is polled every 25 ms for deterministic experiment resolution, so the result still excludes production scheduler cadence.

## Prevention

Never repair the state whose correctness defines a recovery measurement. For asynchronous counters, take the baseline inside the cycle immediately before fault injection and retain a per-cycle outcome rather than relying on a final aggregate count.

## Related ADR / PR / Commit

- ADR-004, ADR-006
- PR #20
- `4dd4c568b3dd6f2a7284b30fae3d3530bfb5b426`
- `EV-P09-RCV-001`
- TRB-005, TRB-011, TRB-013

## Blog Candidate Summary

Why a green recovery benchmark can lie: directly normalizing retry state and reusing asynchronous counters across iterations.
