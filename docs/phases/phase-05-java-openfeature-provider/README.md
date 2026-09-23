# Phase 5 — Java OpenFeature Provider

## Outcome

Phase 5 gives Java applications a vendor-neutral OpenFeature evaluation API backed by validated Switchboard full Snapshots. Remote distribution runs asynchronously; every request evaluates locally from one immutable active Snapshot and continues from Last Known Good state during distribution outages and process restarts.

## Implemented scope

- OpenFeature Java SDK 1.20.2 `EventProvider` implementation for boolean, string, integer, double, and object values.
- OpenFeature Evaluation Context adapter and Evaluation Core result/reason/error mapping.
- Phase 0D gRPC Subscribe, ACK, NACK, RESYNC, heartbeat, credential-revocation, and reconnect client.
- Snapshot Schema v1, transport metadata, RFC 8785 checksum, and Evaluation Model validation.
- Lock-free request reads from an atomic immutable Snapshot reference.
- Canonical-JSON LKG with forced temporary-file write and atomic same-directory replace.
- `INITIALIZING`, `NOT_READY`, `READY`, `READY_STALE`, `ERROR`, and `CLOSED` lifecycle.
- Exponential reconnect backoff with jitter and last-applied-version bootstrap.
- Snapshot version/identity/checksum monotonicity and prior-LKG retention on rejection.
- OpenFeature-only Demo Service evaluation path.

## Default policy

| Setting | Default |
| --- | --- |
| Freshness threshold | 30 seconds |
| Maximum disk LKG age | 7 days |
| Initial reconnect backoff | 250 milliseconds |
| Maximum reconnect backoff | 30 seconds |
| Reconnect jitter | ±20% |
| Supported Snapshot schema | v1 |

All timings and the LKG path are explicit provider configuration. The seven-day default is a safety bound for process-restart bootstrap, not a claim that every business use case permits seven days of stale configuration.

## OpenFeature result mapping

- Semantic evaluation reasons map to OpenFeature `DISABLED`, `TARGETING_MATCH`, `SPLIT`, and `DEFAULT`.
- LKG evaluation in `READY_STALE` uses `CACHED`; the original semantic reason remains in flag metadata. Invalid candidates preserve an existing `READY` or `READY_STALE` state.
- Missing active Snapshot returns the application code default with `PROVIDER_NOT_READY`.
- Missing flags, type mismatch, invalid context, and missing targeting key map to the corresponding OpenFeature error codes.
- Metadata includes `snapshotVersion`, `snapshotChecksum`, `providerState`, `stale`, and `evaluationReason`.

## Verification

```bash
./gradlew :sdk:java-openfeature-provider:test :demo:sample-service:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache --rerun-tasks
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The SDK suite covers the OpenFeature Client and Provider SPI, all supported value shapes, real Netty gRPC Subscribe/ACK, local-only request evaluation, atomic version rules, invalid update retention, freshness/resync, deterministic reconnect backoff/jitter, disk LKG restart bootstrap, corrupt/expired LKG quarantine, and code-default behavior. The Demo test proves application evaluation through `dev.openfeature.sdk.Client` without a Switchboard-specific evaluation call.

## Troubleshooting

- [TRB-008 — SDK LKG readiness and filesystem durability](../../troubleshooting/TRB-008-sdk-lkg-readiness-and-durability.md)
- [TRB-012 — SDK ACK retry under reconnect pressure](../../troubleshooting/TRB-012-sdk-ack-retry-under-reconnect-pressure.md)

## Deferred boundaries

- TLS/mTLS and production credential injection belong to deployment/security hardening.
- Cross-process LKG coordination and encrypted-at-rest cache storage are not supported.
- Real process kill, network partition, reconnect storm, and Distribution rolling failure drills belong to Phase 6/8/9.
- Phase 6 adds parent-directory fsync and restart coverage for an interrupted temporary artifact; sudden host power loss remains outside the automated test envelope.
- SDK metrics, trace hooks, and alerting belong to Phase 7.
- Non-Java providers and OFREP remote evaluation remain extensions.
