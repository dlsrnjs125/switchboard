# ADR-012: Distribute full snapshots before deltas

- Status: Accepted
- Date: 2026-09-18
- Foundation: ConfigurationSnapshot, RESYNC, `INV-SNP-001`–`INV-SNP-004`, `INV-SDK-002`, `INV-SDK-003`

## Context

Delta protocols require gap detection, ordering, base-version compatibility, replay, and partial-update recovery. MVP correctness and outage recovery are more valuable than unmeasured bandwidth savings.

## Decision

Every distribution update is a complete immutable environment snapshot. Clients validate schema, version, and checksum and atomically replace the active snapshot. Reconnect and RESYNC request the current full snapshot. Delta delivery is deferred.

## Alternatives considered

- **Delta only** — rejected because missing one update can make state unrecoverable without extra protocol.
- **Full bootstrap plus immediate deltas** — deferred because it still requires two correctness paths.
- **Per-flag fetch** — rejected because clients could observe mixed publication state.

## Trade-offs

- **Benefit:** simple atomic application, recovery, cache, and test semantics.
- **Cost:** payload size and serialization work grow with environment configuration.

## Consequences

- Snapshot schema represents a full environment view.
- Clients never merge an update into the active snapshot.
- Metrics record payload size, serialization time, and propagation cost to justify future deltas.

## Revisit conditions

- Measured snapshot size or fleet bandwidth exceeds an agreed operational target and a gap-safe delta protocol is specified with full-resync fallback.
