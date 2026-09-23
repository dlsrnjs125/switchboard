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
./load-test/phase-09/run.sh reconnect
./load-test/phase-09/run.sh failure
./load-test/phase-09/run.sh kubernetes
./load-test/phase-09/run.sh verify
```

The complete run executes SDK evaluation and Snapshot compile/validation JMH workloads, the opt-in 100/500/1000-client initial-connection and reconnect-storm gRPC experiments, regression and failure drills, Helm validation, and the kind rolling-update drill. Raw output is copied to the matching `docs/evidence/phase-09` bundle together with an environment fingerprint and SHA-256 manifest. Verification checks every manifest entry and runs tamper-negative, source-identity, and all-bundle secret-guard regressions.

The publish command snapshots Git source identity once before writing artifacts. A qualifying immutable recapture records `git_dirty_count=0` in both PUB/PRP environment files and `CLEAN` in both Git-status files. `verify.sh` also resolves each recorded commit's tree and requires it to equal the recorded index tree, in addition to enforcing checksums and the secret guard.

## Method rules

- JMH: three warm-up iterations, five one-second measurement iterations, one fork, sample-time mode.
- Dataset: synthetic 1/100/1000-flag Snapshots; static, targeting, and 50/50 split evaluation shapes.
- Publish transaction: 1/100/1,000-flag PostgreSQL publications with 5 warm-ups and 30 measured sequential commits; compile, validation, persistence/commit, payload bytes, and throughput are retained separately.
- Publish propagation: 5 warm-ups and 30 measured single-client publications across transaction commit, embedded Kafka broker ACK, Distribution reconciliation, Java Provider atomic apply/LKG, and server-observed SDK ACK.
- gRPC: 100/500/1000 streams initially opened against one Distribution process and one PostgreSQL Testcontainer; connections ramp in batches of 10 and ACK authentication is bounded to four workers. All clients must connect, receive the next full Snapshot, and return an accepted ACK. This is not a reconnect-storm workload.
- Reconnect storm: 100/500/1000 already-connected logical transports observe a Distribution stop, emit the actual scheduled exponential-backoff delay through SDK telemetry, reconnect after restart, load the new authoritative full Snapshot, and return accepted ACKs on an isolated control-RPC executor. Recovery percentiles, admission rejection, and PostgreSQL authentication/bootstrap query amplification are retained separately from initial connection capacity.
- Invalid run: any missing sample, workload error, failed assertion, incomplete cleanup, unknown commit/tree, or missing raw artifact.
- Results are valid only for the captured environment and topology. They are not production capacity guarantees.
