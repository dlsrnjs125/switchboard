# Contract Baseline

Phase 0D turns the Foundation and accepted ADRs into executable version 1 contracts.

| Contract | Source | Verification |
| --- | --- | --- |
| Control Plane HTTP API | `openapi/control-plane-v1.yaml` | OpenAPI structure and local `$ref` resolution test |
| Distribution API | `proto/switchboard/distribution/v1/distribution.proto` | `protoc` compilation |
| Full snapshot | `snapshot-schema/configuration-snapshot-v1.schema.json` | Draft 2020-12 validation against valid and invalid fixtures |
| Deterministic rollout | `test-vectors/sha256-rollout-v1.json` | SHA-256 preimage, digest, unsigned conversion, and bucket test |

Run all contract gates with `./gradlew :contracts:check`.

## Contract decisions

- HTTP uses `/v1`, JSON, and a common `ErrorResponse` with stable machine-readable codes.
- Distribution uses server streaming for snapshots and separate unary ACK/NACK/RESYNC RPCs, selecting option A from ADR-004.
- Authentication is carried in transport metadata (`authorization: Bearer ...`), never in protobuf messages.
- The snapshot checksum is lowercase SHA-256 over RFC 8785 canonical JSON after removing the top-level `checksum` member.
- Snapshot updates are full environment images. Delta fields and messages are reserved for later evolution.
- Rollout hashing length-prefixes three UTF-8 fields with unsigned 32-bit big-endian byte lengths. The first eight digest bytes are an unsigned big-endian integer reduced modulo 10,000.

See [contract versioning](../docs/architecture/contract-versioning.md) for compatibility policy.
