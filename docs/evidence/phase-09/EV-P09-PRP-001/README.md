# EV-P09-PRP-001 — Publish Propagation Baseline

- Status: PLANNED
- Phase: Phase 9 — Performance & Operations Evidence
- Owner: Switchboard maintainers
- Related: `SLI-PRP-001`, `SLI-OBX-001`, `ADR-005`, `ADR-006`, `FM-KFK-001`

## Claim

The workload measures one real publication path from PostgreSQL transaction commit through Outbox relay, Kafka broker acknowledgement, Distribution authoritative reconciliation, Java Provider atomic apply and durable LKG write, and a server-accepted SDK ACK.

## Workload

- one PostgreSQL `18.6-alpine` Testcontainer;
- one Spring Embedded KRaft broker (Apache Kafka 4.2.1) and one topic/partition;
- one Control Plane service object, one Outbox relay, one Distribution process, and one Java Provider;
- five warm-up publications and 30 measured sequential publications;
- a new immutable revision and full Snapshot for every publication;
- 1 ms polling resolution for SDK apply and server ACK observations.

Each Draft Revision is created and committed before `publishStarted`. The Publish start boundary therefore covers only the Control Plane publication transaction and excludes revision-authoring latency.

The environment fingerprint distinguishes `kafka_project_baseline_image`, the Docker Compose baseline used elsewhere in the repository, from `kafka_test_runtime_version` and `kafka_test_mode`, which identify the Embedded Kafka runtime used by this workload.

## Command

```bash
make phase9-publish-evidence
```

## Preliminary observed result

| Stage | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Publish start → transaction commit | 25.929 ms | 32.137 ms | 35.865 ms |
| Commit → broker ACK | 15.656 ms | 19.911 ms | 20.144 ms |
| Broker ACK → Distribution apply | 13.620 ms | 16.820 ms | 16.936 ms |
| Distribution apply → SDK apply observed | 9.845 ms | 12.282 ms | 14.328 ms |
| SDK apply observed → server ACK observed | 12.398 ms | 15.964 ms | 21.194 ms |
| Commit → SDK ACK observed | 52.563 ms | 57.784 ms | 65.448 ms |
| Publish start → SDK ACK observed | 79.104 ms | 88.429 ms | 91.925 ms |

## Boundary semantics

The SDK sends ACK only after validation, atomic in-memory apply, and durable LKG replacement. The terminal timestamp is the Distribution server's accepted ACK observation. `SDK apply observed` is an external 1 ms polling observation of the provider's active version, so its sub-stage values include observation delay; `commit → SDK ACK observed` is the preferred end-to-end SLI in this record.

## Result status

The workload, stage ordering assertions, and zero-error completion pass locally. The record remains `PLANNED` until rerun on an immutable commit and its environment fingerprint and checksum manifest are preserved from that run.

## Artifacts

- `artifacts/publish-propagation.json`
- `artifacts/environment.txt`
- `artifacts/git-status.txt`
- `artifacts/SHA256SUMS`

## Limitations

This is a single-client, single-process, single-partition local topology. It does not include HTTP/JWT ingress, multiple Distribution replicas, WAN latency, concurrent publications, broker quorum/storage faults, or reconnect storms. It is not a production SLO.
