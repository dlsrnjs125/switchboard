# Evidence Policy

## Purpose

Evidence is the durable, reviewable proof behind a Switchboard performance, reliability, security, or compatibility claim. It records what was tested, on which code and environment, what happened, and what remains unproven.

Phase 0F defines the format. Later phases create actual evidence only after executable implementation exists. Design targets alone are never recorded as achieved results.

## Directory and identifier convention

Store evidence by owning phase and stable evidence ID:

```text
docs/evidence/
  phase-06/
    EV-P06-KFK-001/
      README.md
      artifacts/
  phase-09/
    EV-P09-RCN-001/
      README.md
      artifacts/
```

Use `EV-P<phase>-<area>-<sequence>`:

- `EV-P01-TEN-001` — Phase 1 tenant-isolation integration evidence;
- `EV-P06-SNP-001` — Phase 6 corrupt-snapshot drill;
- `EV-P09-PRP-001` — Phase 9 publish-propagation experiment.

Recorded evidence:

- [`EV-P01-TEN-001`](phase-01/EV-P01-TEN-001/README.md) — Phase 1 control-plane tenant isolation and persistence.
- [`EV-P02-EVL-001`](phase-02/EV-P02-EVL-001/README.md) — Phase 2 deterministic local evaluation and JMH baseline.

An evidence ID is never reused for a materially different experiment. A rerun may add a dated run beneath the same experiment definition only when workload, success criteria, and method remain compatible.

## Required metadata

Every evidence `README.md` contains:

| Field | Requirement |
| --- | --- |
| Evidence ID | Stable identifier and concise title |
| Phase | Owning implementation/verification phase |
| Status | `PLANNED`, `PASS`, `FAIL`, or `INVALID` |
| Git commit | Full immutable commit SHA; branch alone is insufficient |
| Related requirements | Invariant, ADR, contract, failure, SLI, and test IDs |
| Environment fingerprint | OS/arch, CPU/memory, JDK, Docker, PostgreSQL, Kafka, dependency and tool versions |
| Topology | Replica count, client count, network path, resource limits |
| Command | Exact invocation and configuration needed to reproduce |
| Workload | Dataset, flag/payload size, concurrency, arrival model, warm-up, duration, fault timing |
| Expected | Predeclared pass/fail criteria |
| Observed | Counts, percentiles, state transitions, errors, and recovery outcome |
| Result | Why the observed data meets or misses the expected criteria |
| Artifact path | Raw logs/results/traces and derived summaries |
| Limitation | What this run does not prove |
| Timestamp/owner | UTC run time and accountable author/operator |

## Evidence record template

```markdown
# EV-PXX-AREA-NNN — Title

- Status: PLANNED
- Phase: Phase X
- Git commit: <full SHA>
- Executed at: <UTC timestamp>
- Owner: <name or team>
- Related: <INV-...>, <ADR-...>, <FM-...>, <SLI-...>

## Claim

The exact statement this experiment is intended to support.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | |
| CPU/memory | |
| JDK/JVM flags | |
| Docker/Compose | |
| PostgreSQL/Kafka | |
| Service image/digest | |
| Resource limits | |
| Time synchronization | |

## Topology and workload

- Replicas:
- Clients/concurrency:
- Dataset and payload:
- Arrival/ramp model:
- Warm-up/measured duration:
- Fault trigger and timing:

## Command

    <exact reproducible command>

## Expected

- [ ] Predeclared criterion

## Observed

Measured values and state transitions, with links to raw artifacts.

## Result

PASS, FAIL, or INVALID with reasoning tied to each criterion.

## Artifacts

- `artifacts/...`

## Limitations

Explicit boundaries and follow-up work.
```

## Artifact rules

- Keep the human-readable conclusion in `README.md`; keep machine-generated raw data under `artifacts/`.
- Prefer deterministic text/JSON/CSV summaries. Compress very large raw files or store them in approved external artifact storage and record an immutable digest and retention location.
- Record SHA-256 for externally stored artifacts and container images when available.
- Include the script, query, dashboard export, or calculation command that produced a derived number.
- Preserve the first failure output. A successful rerun does not erase the failed observation.
- Never hand-edit raw benchmark output. Add a separate normalized/derived file and document the transformation.
- Screenshots may support context but cannot be the only source for numeric or state-transition claims.

## Security and privacy

Evidence must not contain:

- raw service credentials, authorization headers, cookies, private keys, or database passwords;
- full targeting contexts, user IDs, email addresses, or production customer payloads;
- unredacted snapshot content when representative synthetic payload metadata is sufficient;
- unrestricted database dumps or environment files.

Use synthetic identifiers, bounded log excerpts, and redacted configuration. If redaction changes a value used by an assertion, document how the assertion remains reproducible. Secret scanning is part of evidence review.

## Result semantics

| Status | Meaning |
| --- | --- |
| `PLANNED` | Method and criteria exist, but no qualifying execution has occurred. |
| `PASS` | All predeclared criteria passed in the recorded environment. |
| `FAIL` | At least one criterion failed; artifacts and observed behavior remain preserved. |
| `INVALID` | The run cannot support a conclusion because setup, instrumentation, population, or cleanup was incomplete. |

Partial success is `FAIL` unless criteria explicitly define independent sub-results. A result is not promoted from `FAIL` to `PASS`; create a new run record or clearly append a separately identified rerun.

## Reproducibility checklist

- [ ] Commit exists and the worktree was clean or local differences are attached.
- [ ] Toolchain and dependency versions are captured.
- [ ] Topology, limits, dataset, and workload are explicit.
- [ ] Expected criteria were written before interpreting results.
- [ ] Raw artifacts and calculation steps are available.
- [ ] Fault timing and recovery completion are visible.
- [ ] Errors, timeouts, and excluded samples are counted.
- [ ] Cleanup completed and the environment returned to a known state.
- [ ] Secrets, PII, and high-cardinality targeting data are absent.
- [ ] Limitations prevent claims beyond the tested envelope.

## Evidence review gate

Reviewers verify:

1. the claim is narrower than or equal to what the experiment proves;
2. the commit and environment are reproducible;
3. the denominator and excluded population are visible;
4. safety failures are not hidden inside aggregate percentiles;
5. recovery means state convergence, not process restart;
6. artifacts contain enough information to audit the conclusion;
7. no design target is described as a production guarantee.

## Retention and traceability

Committed evidence summaries are retained with repository history. Artifact retention must outlive the release or portfolio claim that references it. Incident/legal holds override automatic cleanup. When an external artifact expires, the evidence record becomes `INVALID` unless an equivalent retained copy and digest exist.

Every PR that changes a verified behavior links the affected evidence ID or states why existing evidence remains applicable. Evidence does not replace tests; tests provide repeatable assertions, while evidence preserves the audited execution and its limits.
