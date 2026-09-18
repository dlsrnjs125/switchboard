# ADR-004: Use gRPC streaming for snapshot distribution

- Status: Accepted
- Date: 2026-09-18
- Foundation: Distribution plane, ACK, NACK, RESYNC, `INV-SDK-001`–`INV-SDK-004`

## Context

Connected server applications need low-latency updates, typed contracts, acknowledgement, reconnect, and resynchronization without polling each flag.

## Decision

Distribution uses a long-lived gRPC stream. A client authenticates, reports its last applied version, receives full snapshots, and responds through defined ACK/NACK/RESYNC operations. Phase 0D MUST choose exactly one interaction shape: a server-streaming subscription with separate unary ACK/NACK/RESYNC RPCs, or a bidirectional stream carrying both server updates and client responses. Reconnect uses exponential backoff with jitter.

## Alternatives considered

- **HTTP polling** — operationally simple but increases staleness and repeated transfer.
- **WebSocket** — flexible but provides less contract and code-generation discipline for the primary Java path.
- **Kafka clients in applications** — rejected because broker topology and credentials would leak into every service.

## Trade-offs

- **Benefit:** typed efficient streams and explicit flow/lifecycle semantics.
- **Cost:** connection management, load balancing, backpressure, and reconnect storms require testing.

## Consequences

- Proto evolution follows backward-compatible field rules.
- A server-streaming RPC alone is not treated as a client-to-server response channel; the selected RPC direction is explicit in the Phase 0D protobuf contract.
- Distribution authenticates scope before sending any snapshot.
- Stream loss moves a ready SDK to `READY_STALE`, not to remote evaluation.

## Revisit conditions

- Network environments cannot support stable HTTP/2, or measured fleet behavior favors polling or another transport.
