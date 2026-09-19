# Contract Versioning and Compatibility

## Version dimensions

- HTTP uses a major path version (`/v1`). Additive schema changes do not change the path.
- Protobuf uses a versioned package (`switchboard.distribution.v1`).
- Snapshots carry `schemaVersion` independently from `snapshotVersion`.
- Test-vector files carry their own contract version.

## Compatible changes

- Add an optional OpenAPI field or endpoint without changing existing semantics.
- Add a protobuf field with a new number, enum value with a new number, or new RPC.
- Add an optional JSON Schema property while preserving canonicalization rules.
- Add documented error codes while retaining the common error envelope.

## Breaking changes

- Remove or rename a field, or change its type, requiredness, meaning, or tenant scope.
- Reuse a protobuf field number/name, or change an RPC direction.
- Change canonical JSON, checksum, rollout preimage, digest slice, or bucket mapping.
- Accept a payload that existing consumers interpret differently.

Breaking changes require consumer-impact analysis, a new major/package/schema version, migration fixtures, and an ADR when an architecture boundary changes.

## Protobuf rules

- Removed field numbers and names MUST be `reserved` permanently.
- Existing numeric enum values MUST NOT be reassigned.
- Unknown fields are tolerated according to protobuf semantics.
- The v1 shape is server-streaming `Subscribe` plus unary ACK/NACK/RESYNC.

## Change workflow

1. Classify compatibility before editing a contract.
2. Update related contracts and shared terminology together.
3. Add or update valid, invalid, and golden fixtures.
4. Run `./gradlew :contracts:check` and consumer tests.
5. Document migration and supported-version windows before release.
