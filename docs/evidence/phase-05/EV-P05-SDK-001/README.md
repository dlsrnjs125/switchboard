# EV-P05-SDK-001 — OpenFeature Local Evaluation and LKG Continuity

- **Status:** PASS
- **Phase:** Phase 5 — Java OpenFeature Provider
- **Git commit:** `9b3c7ffe634f7216d8bc7e6f540f83cdc099cf8c`
- **Executed at:** 2026-09-21T04:22:24Z
- **Owner:** Switchboard maintainers
- **Related:** `INV-SDK-001` through `INV-SDK-005`, `INV-SNP-001` through `INV-SNP-004`, ADR-001, ADR-003, ADR-004, ADR-009, ADR-012, `FM-DST-001`, `FM-SDK-001`, `FM-SNP-001`, `FM-GAP-001`

## Claim

The Java OpenFeature Provider evaluates all supported flag value types inside the application process from one validated immutable Snapshot. Distribution loss does not add request-time network calls or remove the active LKG, restart can bootstrap from a valid durable LKG, and corrupt, expired, older, conflicting, or incompatible artifacts never replace the active Snapshot.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS 26.6.2 (25G83), arm64 |
| CPU/memory | Apple M1 Pro, 16 GiB |
| JDK | OpenJDK 17.0.19 in an isolated compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| OpenFeature | Java SDK 1.20.2 |
| gRPC | grpc-java 1.83.1, Netty transport |
| Snapshot | Schema v1, RFC 8785 + SHA-256 |

## Topology and workload

- One OpenFeature API/Client and one Switchboard Provider per test application process.
- One immutable in-memory Snapshot reference and one temporary-directory disk LKG file.
- One actual Netty gRPC test server for Subscribe → Full Snapshot → ACK.
- Fake background transport for deterministic disconnect, heartbeat, resync, invalid update, and no-network evaluation assertions.
- 1,000 consecutive evaluations after distribution disconnection with transport action count held constant.
- Boolean, string, integer, double, and object flag values plus targeting metadata.

## Commands

```bash
./gradlew :sdk:java-openfeature-provider:test :demo:sample-service:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache --rerun-tasks
./gradlew build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

## Expected

- [x] Applications evaluate through the standard OpenFeature Client API.
- [x] Request-time evaluation performs no transport or disk operation.
- [x] A validated newer full Snapshot is durably persisted, atomically activated, and ACKed.
- [x] One evaluation observes one complete old or new Snapshot reference.
- [x] Distribution freshness loss moves `READY` to `READY_STALE` while the same results continue.
- [x] Restart with valid LKG and unavailable remote starts `READY_STALE` and evaluates locally.
- [x] First boot without LKG returns the OpenFeature code default and `PROVIDER_NOT_READY`.
- [x] Corrupt or expired disk LKG is quarantined and never activated.
- [x] Older, same-version identity/checksum-conflicting, and incompatible updates keep the prior LKG.
- [x] Reconnect delay grows exponentially, is capped, adds jitter, and resets after contact.
- [x] Demo Service contains no Switchboard-specific evaluation call.

## Observed

- Java OpenFeature Provider suite: 14 tests, 0 failures.
- Demo Service suite: 2 tests, 0 failures.
- Real gRPC component path: last-applied version 0 subscribed, Snapshot version 7 applied, exact version/checksum ACK observed.
- Local continuity: 1,000 post-disconnect evaluations returned the LKG result with no additional transport action.
- Restart continuity: version 4 loaded from disk, Subscribe resumed with last-applied version 4, and state remained `READY_STALE` until remote contact.
- Negative paths: corrupt and eight-day-old LKG rejected; invalid schema/checksum, older version, and same-version conflict retained the active version.
- Typed mapping: boolean, string, integer, double, and object evaluation PASS.
- Full multi-module build: 80 tasks successful; configuration cache stored, and the follow-up build reused it (`Configuration cache entry reused`).
- Control Plane, Distribution, Contract, and Evaluation Core regression suites PASS.

## Result

All predeclared Phase 5 local-evaluation, atomic-apply, LKG, lifecycle, reconnect, and application-boundary criteria passed in the recorded local environment. The evidence supports continuity and correctness for the tested single-process Java SDK topology; it is not fleet-scale or production outage evidence.

## Artifact paths

- `sdk/java-openfeature-provider/build/reports/tests/test/index.html`
- `sdk/java-openfeature-provider/src/test/java/io/github/dlsrnjs125/switchboard/sdk/SwitchboardProviderTest.java`
- `sdk/java-openfeature-provider/src/test/java/io/github/dlsrnjs125/switchboard/sdk/AtomicSnapshotStoreTest.java`
- `sdk/java-openfeature-provider/src/test/java/io/github/dlsrnjs125/switchboard/sdk/GrpcSnapshotTransportIntegrationTest.java`
- `sdk/java-openfeature-provider/src/test/java/io/github/dlsrnjs125/switchboard/sdk/ReconnectBackoffTest.java`
- `demo/sample-service/src/test/java/io/github/dlsrnjs125/switchboard/demo/SampleServiceApplicationTest.java`
- `docs/architecture/sdk-lifecycle.md`

## Limitations

- Local verification used JDK 17 because JDK 21 was unavailable. Java 21 GitHub Actions remains a required PR gate.
- The gRPC test uses one local plaintext Netty server without proxy, TLS, packet loss, or injected latency.
- The LKG file assumes one writer process and is not encrypted; Switchboard runtime configuration must not contain secrets.
- The seven-day LKG maximum age is configurable policy, not a universal safety recommendation.
- Wall-clock scheduled stale transition is exercised through the same deterministic state-check method rather than a 30-second test wait.
- Actual Distribution process failure, long partitions, credential rotation, and reconnect storms remain Phase 6/9 drills.
- Metrics, tracing, OpenFeature telemetry hooks, and capacity measurements are not included.
