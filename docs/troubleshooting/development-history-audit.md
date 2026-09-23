# Development History Troubleshooting Audit

## Scope

This audit reviews the repository history through PR #18 and merge commit `71d3dac374d99a6c6e1fdf454bbec178f13e09da`. It compares PR descriptions, remediation commits, implementation and test changes, Phase summaries, ADRs, Runbooks, and Evidence against the project's Troubleshooting creation criteria.

The audit deliberately does not turn every review correction into an incident. Terminology edits, planned contract refinement, and one-off build corrections remain in Git history or ADRs. A dedicated TRB is required when the change exposes a reusable failure mode, incorrect infrastructure assumption, safety/consistency defect, runtime-only failure, measurement error, or important trade-off.

## Result

Before this audit, only the PostgreSQL 18 Compose layout had a complete TRB. Runtime and evidence failures were described across PR bodies, tests, Evidence, and Runbooks, but they were not discoverable as root-cause investigations. TRB-002 through TRB-011 close that gap without duplicating full execution logs.

## PR and commit coverage

| PR | Phase | Material finding or correction | Durable record |
| --- | --- | --- | --- |
| #1 | 0A | PostgreSQL 18 image rejected the legacy Compose volume layout | [TRB-001](TRB-001-postgresql-18-compose-volume-layout.md) |
| #2 | 0B | Domain vocabulary and boundary review corrections | Foundation documents and Git history; no runtime incident |
| #3 | 0C | Delivery semantics clarified across accepted ADRs | ADRs and Git history; no runtime incident |
| #4 | 0D | Executable contract tests closed cross-field schema/domain gaps | [TRB-004](TRB-004-snapshot-contract-validation.md), contract fixtures |
| #5 | 0E | Service credential ownership needed composite tenant enforcement | Data model specification and ADR-010; no observed runtime incident |
| #6 | 0F | Failure/recovery contracts were clarified before implementation | Failure model and evidence policy; no observed runtime incident |
| #7 | 1 | Empty-condition and invalid final rule states could bypass the intended database invariant | [TRB-002](TRB-002-targeting-rule-final-state-invariants.md) |
| #8 | 2 | Nested evaluation input remained caller-mutable and non-string object keys could be normalized ambiguously | [TRB-003](TRB-003-immutable-evaluation-inputs.md) |
| #9 | 3 | Persisted Snapshot needed executable contract validation; outbox row locks could not safely span broker I/O | [TRB-004](TRB-004-snapshot-contract-validation.md), [TRB-005](TRB-005-outbox-lease-ownership.md) |
| #10 | 4 | Subscribe bootstrap overlapped live broadcast and same-version identity conflicts were under-specified | [TRB-006](TRB-006-grpc-subscription-consistency-races.md) |
| #11 | 5 | Invalid candidates and disconnects incorrectly destroyed evaluation-ready semantics despite valid LKG | [TRB-008](TRB-008-sdk-lkg-readiness-and-durability.md) |
| #12 | 6 | Post-commit response loss was ambiguous; post-rename directory fsync failure could falsely promote freshness | [TRB-007](TRB-007-ambiguous-publish-commit.md), [TRB-008](TRB-008-sdk-lkg-readiness-and-durability.md) |
| #13 | 7 | Publish metrics stopped before transaction outcome; ACK latency keyed by shared application identity was ambiguous | [TRB-009](TRB-009-telemetry-transaction-and-delivery-correlation.md) |
| #14 | 8 | A shared Kafka consumer group delivered a notification to only one replica although caches and sessions were process-local | [TRB-010](TRB-010-distribution-replica-notification-fanout.md) |
| #15 | 9 | Partial local measurements were initially labeled like final evidence and lacked sufficient negative integrity gates | [TRB-011](TRB-011-phase-09-evidence-provenance.md) |
| #16 | 9 | Kafka/runtime fingerprints and publish timing boundaries changed the meaning of reported percentiles | [TRB-011](TRB-011-phase-09-evidence-provenance.md) |
| #17 | 9 | Evidence generation made its own worktree fingerprint dirty; commit/tree identity needed cross-validation | [TRB-011](TRB-011-phase-09-evidence-provenance.md) |
| #18 | 9 | ACK retry could lose recovered deliveries; credential-store failures were classified as permanent authentication failures; shared scheduling inflated reconnect recovery | [TRB-012](TRB-012-sdk-ack-retry-under-reconnect-pressure.md), [TRB-013](TRB-013-credential-dependency-failure-status.md), reconnect Evidence limitations |

## Remediation commits reviewed

The audit examined the complete commit list and gave particular attention to commits that changed an invariant or corrected a review finding:

- `bd98e10`, `5608a0b`, `492f972`: contract, tenant ownership, and targeting-rule final-state enforcement;
- `565a52e`: immutable evaluation inputs;
- `2342a66`: Snapshot validation and leased outbox delivery;
- `4b8f03b`: subscription/cache consistency races;
- `35397de`, `4136a5d`, `0d10623`: evaluation-ready lifecycle and LKG durability uncertainty;
- `7343b74`, `a43fda1`: transaction-aware telemetry and per-delivery ACK correlation;
- `ec035bb`: per-replica Kafka notification fan-out;
- `616833d`, `f661cf0`, `4c07d38`, `1542b1c`, `abd22c4`: Phase 9 claim, fingerprint, timing, clean-source, and commit/tree integrity corrections;
- `71d3dac`: reconnect ACK retry, credential failure classification, scheduler isolation, and troubleshooting coverage.

Merge commits and documentation-only verification commits remain the authoritative history for their PR, but they do not need a duplicate TRB when the underlying failure is already covered above.

## PR #18 Phase 9 findings

The reconnect-storm gate added after PR #17 exposed two additional runtime failure modes that were resolved in PR #18:

- a recovered Snapshot could lose its separate unary acknowledgement under transient pressure; see [TRB-012](TRB-012-sdk-ack-retry-under-reconnect-pressure.md);
- credential-store timeouts were incorrectly mapped to permanent caller authentication failure; see [TRB-013](TRB-013-credential-dependency-failure-status.md).

Both investigations are tied to PR #18 and merge commit `71d3dac374d99a6c6e1fdf454bbec178f13e09da`. The subsequent clean-source recapture retains the measured run-to-run spread as an Evidence limitation instead of inventing an unproved root cause.

## Current PR #19 finding

The first reconnect promotion changed the machine-generated raw JSON lifecycle from `candidate` to `pass` after capture. Although its checksum was regenerated, recorded source commit `71d3dac374d99a6c6e1fdf454bbec178f13e09da` could not reproduce that file. [TRB-011](TRB-011-phase-09-evidence-provenance.md) now records the failure mode. Commit `9842d5222e3b0ce84a567caeebcd7746306ed6a5` introduced the reproducible `workloadResult: pass` field; clean source commit `4ea121e8a9907c9f6926701178871e25e8ab6669` includes the matching verifier and successfully reproduces the complete command. The Evidence README separately owns lifecycle promotion.

The subsequent sustained backpressure workload did not expose a new root cause. It quantified the existing one-pending-Snapshot invariant from [TRB-006](TRB-006-grpc-subscription-consistency-races.md), added pending count/byte and coalescing telemetry, and preserved the synthetic observer boundary as an explicit Evidence limitation rather than claiming real-network capacity.

## Ongoing gate

Before a Phase is marked `VERIFIED`:

1. inspect every `fix:` commit and material review remediation;
2. decide whether it meets the Notion Troubleshooting creation criteria;
3. link qualifying findings from the Phase README, Evidence, and this index;
4. verify each TRB contains reproducible verification and exact PR/commit references;
5. keep raw logs in Evidence, not in the TRB.
