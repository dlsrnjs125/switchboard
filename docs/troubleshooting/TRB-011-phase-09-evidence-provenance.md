# TRB-011 — Phase 9 Evidence Provenance and Measurement Boundaries

## Context

Phase 9 turns performance and operations claims into versioned raw artifacts. A result is meaningful only when source identity, runtime fingerprint, measured interval, workload, checksum, and limitation all refer to the same execution.

## Symptom

Several review rounds exposed different ways valid-looking numbers could mean the wrong thing:

- a partial local baseline was initially named like final aggregate Evidence;
- the documented Kafka project baseline differed from the embedded test runtime;
- publish timing included draft authoring or post-commit verification queries;
- Evidence generation wrote files before reading Git status, so the harness made its own source fingerprint dirty;
- `CLEAN` and dirty-count fields did not cryptographically tie the recorded index tree to the recorded commit.
- reconnect raw output encoded `candidate`, but the committed artifact was hand-promoted to `pass`, so the recorded source could not reproduce the checksummed raw file.
- the first backpressure harness started healthy-client latency at actual broadcast entry, ordered every healthy session before every slow session, and compared a burst baseline with a scheduled pressure window;
- the backpressure raw field named `pendingSerializedBytesBeforeDrain` contained the run-wide maximum, including one transient ready-session slot, rather than the value captured immediately before drain.

## Expected vs Actual

- Expected: every percentile and PASS label is traceable to one clean source tree, exact runtime, explicit boundary, raw artifact, and tamper-evident manifest.
- Actual: the workload could pass while metadata, naming, or timer placement overstated what had been measured.

## Reproduction

Run the original harness in a clean tree and inspect status after the first artifact is written; time publish from draft creation or through assertion queries; alter a fingerprint and regenerate only its checksum; compare the configured Kafka baseline with the embedded broker version. For the reconnect mismatch, check out recorded commit `71d3dac374d99a6c6e1fdf454bbec178f13e09da` and run `make phase9-reconnect-evidence`: the harness produces `status: candidate`, while the superseded PR artifact recorded `status: pass`. For the backpressure blind spot, model a slow-session traversal that makes 100 nominal 100 ms publications take longer than 20 seconds: resetting latency at each delayed loop entry can still report no healthy p99 addition.

## Impact

The repository could preserve precise but non-comparable numbers, promote candidate evidence to final, claim immutable-source provenance without proving that the commit and index tree describe the measured code, or checksum a hand-edited raw result that the recorded harness cannot reproduce.

## Initial Hypothesis

Passing workload assertions and storing a Git SHA were assumed to be enough. Harness file writes, dependency-runtime differences, and code placed just inside a timer were treated as incidental.

## Evidence

PRs #15–#17 preserve candidate and immutable runs, raw JSON, environment files, Git status, SHA-256 manifests, and candidate-to-immutable variability. Integrity regression tests mutate artifacts, inject secret patterns, and substitute a mismatched tree while recomputing the manifest.

## Root Cause

Evidence collection was initially treated as test output rather than a measurement system with its own state transitions and trust boundaries. Raw workload outcome and human Evidence lifecycle were also collapsed into one ambiguous `status` field.

## Fix

- rename incomplete aggregate output to `EV-P09-BASELINE-001` and keep Phase 9 `IN_PROGRESS`;
- record project baseline and actual test runtime separately;
- create draft revisions before starting publish timing and stop at transaction commit before verification queries;
- snapshot commit, index tree, dirty count, and short status once before any artifact write;
- require `git_dirty_count=0` and `CLEAN` for immutable PUB/PRP promotion;
- resolve `git_commit^{tree}` and require equality with `git_index_tree`;
- emit only reproducible workload outcome (`workloadResult: pass`) from reconnect schema version 2 and keep lifecycle promotion (`Status: PASS`) in the Evidence README;
- use one fixed-seed mixed session order and identical 100-update/10 Hz baseline and pressure windows for backpressure;
- measure delivery from scheduled publication time, gate schedule-lag p99 and achieved publication rate, and retain a regression proving injected delay fails those gates;
- capture pending count and bytes atomically in the harness immediately before drain, while preserving the run-wide byte maximum under a separately named field;
- verify every manifest entry, scan every Phase 9 bundle for secret patterns, and run negative tamper tests.

## Verification

Run:

```bash
./load-test/phase-09/verify.sh
./load-test/phase-09/verify-integrity-test.sh
```

Both must pass for retained bundles. A checksum-valid commit/tree mismatch, artifact mutation, dirty source, failed raw workload result, unpromoted README lifecycle, or synthetic secret must fail verification.

## Trade-off

Verification requires the recorded Git commit object to be available, and immutable recapture is slower than editing summaries. Local 30-sample results remain bounded baselines, not production thresholds.

## Prevention

Review the harness like production code. Define timing boundaries before measurement, include scheduler debt rather than resetting the clock after it, compare like-for-like workloads, snapshot provenance before output, preserve candidate runs when useful, never hand-edit raw output, and keep machine workload outcomes separate from human review lifecycle.

## Related ADR / PR / Commit

- ADR-005, ADR-006, ADR-012
- PR #15, PR #16, PR #17, PR #19
- `616833d34bdd4e0697004701a05532473cff4615`
- `f661cf003f0d83f5822a883287fc806c6608e3af`
- `4c07d38a59df5039966fb547be9935c1f33cc697`
- `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`
- `abd22c48512bfefb1a329d242fda5db0985e4787`
- `9842d5222e3b0ce84a567caeebcd7746306ed6a5`
- `4ea121e8a9907c9f6926701178871e25e8ab6669`
- `15768b75984eb58ded8512af1346806759223c23`
- `EV-P09-BASELINE-001`, `EV-P09-PUB-001`, `EV-P09-PRP-001`, `EV-P09-RCN-001`, `EV-P09-BKP-001`

## Blog Candidate Summary

Performance Evidence is a data product: how self-dirty fingerprints and contaminated timers can invalidate otherwise correct benchmarks.
