# EV-P02-EVL-001 — Deterministic Local Evaluation Baseline

- **Status:** PASS
- **Phase:** Phase 2 — Evaluation Engine
- **Git commit:** `565a52e500f15aa844eed423352248e4672ec155`
- **Executed at:** 2026-09-20T06:13:01Z
- **Owner:** Switchboard maintainers
- **Related:** ADR-001, ADR-003, ADR-008, `INV-RUL-001` through `INV-RUL-004`, `INV-SDK-005`, Snapshot Schema v1, SHA-256 Rollout Golden Vector v1

## Claim

The Phase 2 Java Evaluation Core produces deterministic local decisions from an immutable typed flag model, defensively captures nested JSON-compatible inputs, matches every canonical SHA-256 rollout vector, and has no production runtime dependency outside the JDK.

The benchmark numbers below are a development baseline for this exact environment. They are not a production SLO, capacity limit, or cross-machine comparison.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS 26.6.2 (25G83), arm64 |
| CPU/memory | Apple M1 Pro, 16 GiB |
| JDK | OpenJDK 17.0.19 used in a temporary compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| JMH | 1.37 via `me.champeau.jmh` 0.7.3 |
| JVM options | Default JMH fork plus UTF-8 locale properties recorded in the raw artifact |

## Topology and workload

- One fork, one thread, Average Time mode.
- Three warm-up iterations of one second each.
- Five measured iterations of one second each.
- Static path: enabled flag with no targeting rules, resolves default.
- Targeting path: two matching equality conditions, resolves fixed variant.
- Rollout path: one matching condition, SHA-256 bucket, two 5,000-basis-point allocations.

## Commands

The host did not contain JDK 21, so local verification copied the working tree to an isolated temporary directory and changed only that copy's Gradle toolchain declaration from 21 to 17:

```bash
./gradlew :libs:evaluation-core:test :libs:evaluation-core:verifyRuntimeIsolation --no-configuration-cache
./gradlew :libs:evaluation-core:verifyRuntimeIsolation --configuration-cache
./gradlew :libs:evaluation-core:jmh --no-configuration-cache
./gradlew clean build :contracts:check --no-configuration-cache
./gradlew clean build --configuration-cache
```

## Expected

- [x] Every Snapshot Schema v1 operator has a passing semantic test.
- [x] Boolean, string, number, and object values remain type-safe.
- [x] Disabled, default, targeting, rollout, invalid-context, type-mismatch, and missing-targeting-key paths are explicit.
- [x] Rules are evaluated in ascending priority and conditions use logical AND.
- [x] Exact bucket boundary `4,999`/`5,000` maps to adjacent allocations.
- [x] Independent evaluator instances return the same bucket for the same inputs.
- [x] Every canonical Golden Vector preimage, digest, and bucket matches.
- [x] Production runtime classpath contains no third-party dependency.
- [x] Runtime isolation verification stores and reuses the Gradle configuration cache.
- [x] Source collection and nested context mutation cannot change an already constructed evaluation input.
- [x] JMH produces a reproducible local baseline for all three requested paths.
- [x] Full multi-module build and contract validation pass.

## Observed

- Evaluation Core suite: 34 tests, 0 failures.
- Full build: 73 tasks completed successfully with configuration cache both enabled and disabled.
- Runtime dependency Gate: PASS with zero resolved production files; configuration cache entry stored and reused.
- Golden Vector v1: 3 of 3 vectors matched preimage, digest, and bucket.

| Benchmark | Score | 99.9% error | Unit |
| --- | ---: | ---: | --- |
| Static default | 11.487 | ± 2.077 | ns/op |
| Two-condition targeting | 38.858 | ± 9.163 | ns/op |
| SHA-256 percentage rollout | 429.475 | ± 3.049 | ns/op |

## Result

All functional and dependency-isolation criteria passed. The measurements establish only a local baseline and show the relative cost of the three implemented paths under the recorded configuration.

## Artifact paths

- `docs/evidence/phase-02/EV-P02-EVL-001/artifacts/jmh-results.json`
- `libs/evaluation-core/build/reports/tests/test/index.html`
- `libs/evaluation-core/src/test/java/io/github/dlsrnjs125/switchboard/evaluation/DeterministicRolloutTest.java`
- `libs/evaluation-core/src/test/java/io/github/dlsrnjs125/switchboard/evaluation/ImmutableInputTest.java`
- `libs/evaluation-core/src/jmh/java/io/github/dlsrnjs125/switchboard/evaluation/EvaluationBenchmark.java`

## Limitations

- The local benchmark used JDK 17 because JDK 21 was unavailable on the host. Java 21 build/test remains a CI Gate, and benchmark values must not be compared across JDKs without a controlled rerun.
- The benchmark has one fork, one thread, a small fixed model, and short iterations; it is unsuitable for production sizing.
- Allocation rate, GC behavior, many-flag lookup, concurrency, CPU isolation, thermal state, and long-run variance were not measured.
- Snapshot parsing, atomic replacement, OpenFeature adaptation, and LKG behavior remain outside Phase 2.
