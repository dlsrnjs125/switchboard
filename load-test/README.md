# Phase 9 evidence harness

Phase 9 turns the implementation and failure semantics from Phases 1–8 into repeatable performance and operations evidence. The current retained bundle is a partial baseline; the harness deliberately separates measured facts from design targets and missing final workloads.

## Commands

```bash
# Complete release-evidence gate (Java 21 and Docker required)
make final-check

# Individual workloads
./load-test/phase-09/run.sh benchmark
./load-test/phase-09/run.sh publish
./load-test/phase-09/run.sh grpc
./load-test/phase-09/run.sh failure
./load-test/phase-09/run.sh kubernetes
./load-test/phase-09/run.sh verify
```

The complete run executes SDK evaluation and Snapshot compile/validation JMH workloads, the opt-in 100/500/1000-client initial-connection gRPC experiment, regression and failure drills, Helm validation, and the kind rolling-update drill. Raw output is copied to `docs/evidence/phase-09/EV-P09-BASELINE-001/artifacts/` together with an environment fingerprint and SHA-256 manifest. Verification checks every manifest entry and runs a tamper-negative regression.

The publish command snapshots Git source identity once before writing artifacts. A qualifying immutable recapture records `git_dirty_count=0` in both PUB/PRP environment files and `CLEAN` in both Git-status files. `verify.sh` also resolves each recorded commit's tree and requires it to equal the recorded index tree, in addition to enforcing checksums and the secret guard.

## Method rules

- JMH: three warm-up iterations, five one-second measurement iterations, one fork, sample-time mode.
- Dataset: synthetic 1/100/1000-flag Snapshots; static, targeting, and 50/50 split evaluation shapes.
- Publish transaction: 1/100/1,000-flag PostgreSQL publications with 5 warm-ups and 30 measured sequential commits; compile, validation, persistence/commit, payload bytes, and throughput are retained separately.
- Publish propagation: 5 warm-ups and 30 measured single-client publications across transaction commit, embedded Kafka broker ACK, Distribution reconciliation, Java Provider atomic apply/LKG, and server-observed SDK ACK.
- gRPC: 100/500/1000 streams initially opened against one Distribution process and one PostgreSQL Testcontainer; connections ramp in batches of 10 and ACK authentication is bounded to four workers. All clients must connect, receive the next full Snapshot, and return an accepted ACK. This is not a reconnect-storm workload.
- Invalid run: any missing sample, workload error, failed assertion, incomplete cleanup, unknown commit/tree, or missing raw artifact.
- Results are valid only for the captured environment and topology. They are not production capacity guarantees.
