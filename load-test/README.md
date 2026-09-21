# Phase 9 evidence harness

Phase 9 turns the implementation and failure semantics from Phases 1–8 into repeatable performance and operations evidence. The harness deliberately separates measured facts from design targets.

## Commands

```bash
# Complete release-evidence gate (Java 21 and Docker required)
make final-check

# Individual workloads
./load-test/phase-09/run.sh benchmark
./load-test/phase-09/run.sh grpc
./load-test/phase-09/run.sh failure
./load-test/phase-09/run.sh kubernetes
./load-test/phase-09/run.sh verify
```

The complete run executes SDK evaluation and Snapshot compile/validation JMH workloads, the opt-in 100/500/1000-client gRPC experiment, regression and failure drills, Helm validation, and the kind rolling-update drill. Raw output is copied to `docs/evidence/phase-09/EV-P09-FINAL-001/artifacts/` together with an environment fingerprint and SHA-256 manifest.

## Method rules

- JMH: three warm-up iterations, five one-second measurement iterations, one fork, sample-time mode.
- Dataset: synthetic 1/100/1000-flag Snapshots; static, targeting, and 50/50 split evaluation shapes.
- gRPC: 100/500/1000 streams opened against one Distribution process and one PostgreSQL Testcontainer; connections ramp in batches of 10 and ACK authentication is bounded to four workers. All clients must connect, receive the next full Snapshot, and return an accepted ACK.
- Invalid run: any missing sample, workload error, failed assertion, incomplete cleanup, unknown commit/tree, or missing raw artifact.
- Results are valid only for the captured environment and topology. They are not production capacity guarantees.
