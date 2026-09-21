# Java SDK Lifecycle and LKG

## Application boundary

Business code depends on the OpenFeature Java API. `SwitchboardProvider` implements the OpenFeature provider SPI and is the only Switchboard-specific integration object required during application bootstrap. Boolean, string, integer, double, and object evaluations delegate to `evaluation-core`; the request-time path reads one immutable `SdkSnapshot` reference and performs no gRPC, file, database, or broker I/O.

```text
OpenFeature Client
        |
        v
SwitchboardProvider
        |
        v
AtomicReference<SdkSnapshot> --> EvaluationEngine

background only:
gRPC stream --> validate/decode --> atomic disk LKG replace --> atomic swap --> ACK
```

## Apply transaction

For a newer full Snapshot, the SDK performs these steps in order:

1. Parse the canonical JSON and require Snapshot Schema v1.
2. Match gRPC version/schema/checksum metadata to the payload.
3. Recompute the RFC 8785 SHA-256 checksum.
4. Construct and semantically validate immutable Evaluation Core flag models.
5. Reject older, same-version/different-identity, and same-version/different-checksum candidates.
6. Write the canonical artifact to a same-directory temporary file and force its contents.
7. Replace the configured LKG file with an atomic filesystem move. This rename is the logical disk commit point.
8. Force the parent directory so the renamed directory entry is durable.
9. Replace the active in-memory Snapshot with one `AtomicReference.set`.
10. ACK only after the forced temporary-file write, atomic replacement, parent-directory fsync, and memory application succeed.

Any failure before the atomic rename leaves the prior disk and memory Snapshot active. If the rename succeeds but parent-directory fsync fails, the new file is already the restart-visible logical commit: memory advances to the same candidate, the provider exposes `READY_STALE`, and it NACKs with `LKG_DURABILITY_UNCERTAIN`. Redelivery retries directory fsync; only a successful confirmation returns the provider to `READY` and emits ACK. This prevents the running process from retaining version N while a normal process restart loads version N+1. Invalid input is NACKed without changing an existing `READY` or `READY_STALE` lifecycle state. A validation failure moves the provider to `ERROR` only when no valid active Snapshot exists.

## Lifecycle

| Switchboard state | OpenFeature state | Evaluation behavior |
| --- | --- | --- |
| `INITIALIZING` | `NOT_READY` | Bootstrap in progress |
| `NOT_READY` | `NOT_READY` | No valid Snapshot; return OpenFeature code default with `PROVIDER_NOT_READY` |
| `READY` | `READY` | Current validated memory Snapshot |
| `READY_STALE` | `STALE` | Continue local LKG evaluation with `CACHED` reason and stale metadata |
| `ERROR` | `ERROR` | Provider cannot safely establish or continue its runtime contract, such as credential revocation or a rejected initial candidate with no active Snapshot |
| `CLOSED` | `FATAL` | Provider no longer evaluates from runtime state |

Stream disconnection immediately moves a provider with an active Snapshot to `READY_STALE`. The default 30-second freshness threshold separately detects a connected-but-silent stream. A real Snapshot or heartbeat refreshes freshness; reconnect attempts alone do not. A heartbeat ahead of the active version requests a full resync. The default disk LKG maximum age is seven days and is configurable with the provider options.

## Reconnect and recovery

The gRPC client subscribes with `lastAppliedSnapshotVersion`. Stream failure uses exponential backoff from 250 milliseconds to 30 seconds with ±20% jitter by default. Successful server messages reset the backoff. `RESYNC_REQUIRED` and an ahead heartbeat invoke the unary full-resync request. Credential revocation stops reconnect and exposes `ERROR`.

Disk bootstrap validates the artifact exactly like a remote Snapshot. A corrupt, incompatible, or expired file is quarantined and cannot become active. A valid file starts the provider in `READY_STALE` until remote freshness is confirmed.

## Deliberate boundaries

- Disk LKG is a single-process file contract; shared multi-process writers are unsupported.
- Persistence guarantees a forced temporary-file write and atomic same-directory replacement, then requires parent-directory fsync before ACK. Phase 6 injects a post-rename fsync failure and verifies memory/restart version alignment plus successful redelivery confirmation. Sudden host power loss remains outside the automated test envelope.
- The file contains published runtime configuration and is not an encrypted secret store.
- Plaintext gRPC is suitable for the current local topology; TLS and deployment identity belong to Phase 8/security hardening.
- Fleet reconnect, prolonged outage, and fault injection belong to Phase 6 and Phase 9.
- Metrics and tracing belong to Phase 7; resolution metadata already includes Snapshot version, checksum, lifecycle state, staleness, and semantic evaluation reason.
