# Distribution Data Flow

## Authority and trust boundaries

PostgreSQL remains authoritative for the current environment version, immutable Snapshot payload, client application, and credential lifecycle. Kafka carries only a freshness notification. Distribution never constructs configuration, evaluates flags, or treats broker order as state.

Every Snapshot crosses an independent Distribution trust boundary:

1. Read the current full Snapshot from PostgreSQL using a tenant/project/environment-qualified query.
2. Validate relational metadata against the payload.
3. Validate Snapshot Schema v1 structure and semantic references.
4. Recompute the RFC 8785 SHA-256 checksum.
5. Apply the immutable artifact only when its version is monotonic and its same-version Snapshot identity and checksum are identical.

An invalid artifact or same-version identity/checksum conflict leaves the prior cache entry active and is never streamed.

## Notification reconciliation

```text
SNAPSHOT_PUBLISHED notification
        |
        v
stable event-id dedup window
        |
        v
load PostgreSQL current Snapshot
        |
        v
schema + semantic + checksum validation
        |
        v
monotonic immutable cache apply
        |
        v
coalesced full-Snapshot broadcast
```

- Duplicate event IDs are idempotent.
- An old notification loads the current authoritative Snapshot rather than regressing to the event version.
- A notification ahead of PostgreSQL is rejected.
- A same-version notification or authoritative candidate with a different Snapshot ID is an integrity violation.
- A same-version notification with a different checksum is an integrity violation.
- A version gap converges directly to the current full Snapshot; no delta chain is reconstructed.

The in-memory event-ID window is an optimization. Correctness rests on Snapshot version and checksum reconciliation, so process restart or cross-replica duplicate delivery remains safe.

## Multi-replica notification subscription

Every Distribution replica owns an independent `SnapshotCache`, `SessionRegistry`, and set of live gRPC streams. Kafka notifications therefore use a consumer group derived from the configured group prefix plus the Kubernetes Pod name. A publication is delivered once to each live replica group rather than load-balanced once across the Deployment. Each replica then reconciles the notification against authoritative PostgreSQL state and broadcasts the validated full Snapshot to its own sessions.

The Pod-scoped group is intentionally ephemeral. A new group uses `auto-offset-reset=earliest`, replays retained notifications, and reconciles every candidate against current PostgreSQL state; newly connected SDKs also bootstrap through `SnapshotCoordinator.current`. Kafka remains a freshness signal rather than the source of truth. Broker-side notification retention and cleanup of inactive consumer-group metadata must be configured operationally. Reusing one shared group across replicas is unsafe because it can leave the non-consuming replicas and their already-connected sessions on an older local cache version.

## Authenticated gRPC lifecycle

The bearer credential format is `<credential UUID>.<secret>`. The UUID selects the credential row and the secret is verified against its one-way BCrypt hash. The authenticated principal derives the client application and its exact tenant/project/environment scope from PostgreSQL; request keys can only narrow and match that server-derived scope.

`Subscribe` is server streaming. Separate unary RPCs carry ACK, NACK, and RESYNC as fixed by the v1 protobuf contract.

The server registers a scoped session before it reads authoritative bootstrap state. The session rejects a Snapshot at or below the client's declared applied version and rejects any offer below a Snapshot already offered by the server. Therefore, a live update that overlaps bootstrap is either delivered by the registered broadcast path or observed by reconciliation, and an older bootstrap result cannot overwrite it.

- A new or behind client receives the current full Snapshot.
- A client already at the current version receives a heartbeat.
- A client claiming a version ahead of authority receives `RESYNC_REQUIRED` and is not regressed.
- NACK and RESYNC reload authoritative current state and schedule a full Snapshot for the credential's active streams.
- ACK is accepted only for the exact cached version and checksum. Each actual full-Snapshot emission receives a unique `delivery_id`; the SDK echoes it in ACK so telemetry pairs latency with that exact session delivery even when multiple sessions share one client application. Missing or unknown delivery IDs do not change ACK correctness, but they do not produce a latency sample.

Credentials are revalidated every five seconds by default. Revoked or expired credentials cannot create a new stream; an existing stream receives `CredentialRevoked` when writable and closes with `PERMISSION_DENIED` within the polling bound.

## Backpressure and shutdown

Each stream holds at most one pending full Snapshot. When a client is not writable, a newer full Snapshot replaces the obsolete pending artifact instead of growing a queue. Ready clients have independent sessions and are not blocked by a slow peer.

Heartbeat messages are best effort and never displace a Snapshot. Graceful shutdown stops health serving, completes active streams, and then terminates the gRPC server within a bounded wait.
